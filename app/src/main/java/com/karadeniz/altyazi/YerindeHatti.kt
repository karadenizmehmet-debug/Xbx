package com.karadeniz.altyazi

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * Yerinde çeviri hattı (Telefon, Bildirim): kırpıkta her yazı bloğunu bulur, ayrı çevirir
 * ve TV'de orijinalin tam üstüne çizilmesi için konumuyla gönderir.
 * Aynı metin ikinci kez çevrilmez (önbellek); bloklar kayınca yalnızca konum güncellenir.
 */
class YerindeHatti(
    private val context: Context,
    private val ayar: Ayarlar,
    private val alan: Alan,
    /** Son çeviriler (ana iş parçacığında). Boş liste = alan temizlendi. */
    private val gosterildi: (Alan, List<String>) -> Unit
) {
    companion object {
        private const val CANLI_TUT_MS = 8000L   // TV'nin 20 sn'lik güvenlik gizlemesine yakalanmasın
        private const val ONBELLEK = 400
    }

    private val ana = Handler(Looper.getMainLooper())
    private var isci: HandlerThread? = null
    private var isciH: Handler? = null
    private var tanima: TextRecognizer? = null
    private var cevirmen: Translator? = null
    @Volatile private var modelHazir = false
    @Volatile private var mesgul = false
    @Volatile private var kapandi = false
    private val tv = TvGonderici()
    private val cevrimici = CevrimiciCeviri()
    private val takip = KararlilikTakip()
    private val onbellek = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > ONBELLEK
    }

    /** TV'de şu an duran bloklar (orijinal metin + konum) ve son gönderim zamanı. */
    private var yayinda: List<Blok> = emptyList()
    private var sonGonderim = 0L

    @Volatile var sonOkunan = ""
        private set
    @Volatile var sonCeviriler: List<String> = emptyList()
        private set

    val hazir: Boolean get() = modelHazir && !mesgul && !kapandi

    fun baslat() {
        isci = HandlerThread("yerinde-${alan.id}").also { it.start() }
        isciH = Handler(isci!!.looper)
        tanima = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        cevirmen = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
        )
        cevirmen!!.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { if (!kapandi) modelHazir = true }
    }

    /** Kırpılmış kare (bölgenin videodaki yeri [bolge]). Meşgulse kare atılır; bitmap'in sahibi hattır. */
    fun isle(kirpik: Bitmap, bolge: Oran) {
        val h = isciH
        if (!hazir || h == null) { kirpik.recycle(); return }
        mesgul = true
        h.post {
            try { calis(kirpik, bolge) }
            catch (_: Exception) { }
            finally { kirpik.recycle(); mesgul = false }
        }
    }

    /** Hat iş parçacığında: OCR → filtre → kararlılık → çeviri → yayın. Engelleyen çağrılar burada serbest. */
    private fun calis(kirpik: Bitmap, bolge: Oran) {
        val t = tanima ?: return
        val sonuc = Tasks.await(t.process(InputImage.fromBitmap(kirpik, 0)))
        val min = ayar.minKelime(alan)
        val bloklar = sonuc.textBlocks.mapNotNull { b ->
            val metin = b.lines.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
            val k = b.boundingBox ?: return@mapNotNull null
            if (!YerindeIsleme.uygunMu(metin, min)) return@mapNotNull null
            Blok(metin, YerindeIsleme.kirpiktanVideoya(
                Oran(k.left.toFloat(), k.top.toFloat(), k.right.toFloat(), k.bottom.toFloat()),
                kirpik.width, kirpik.height, bolge
            ))
        }.sortedWith(compareBy({ it.kutu.ust }, { it.kutu.sol }))
        sonOkunan = bloklar.joinToString(" | ") { it.metin }

        when (val k = takip.gelen(bloklar.joinToString(" | ") { it.metin }, ayar.aralikMs)) {
            is Karar.Cevir -> { yayinla(bloklar); takip.ceviriGosterildi() }
            Karar.Temizle -> yayinla(emptyList())
            Karar.Bekle -> {
                // Metin aynı ama bloklar kaydıysa (mesajlar kaydırıldı) yalnızca konumları güncelle
                val ayniMetin = bloklar.map { it.metin } == yayinda.map { it.metin }
                if (yayinda.isNotEmpty() && ayniMetin && YerindeIsleme.kaydiMi(yayinda, bloklar)) yayinla(bloklar)
                else if (yayinda.isNotEmpty() && SystemClock.elapsedRealtime() - sonGonderim > CANLI_TUT_MS) yayinla(yayinda)
            }
        }
    }

    private fun cevir(metin: String): String {
        onbellek[metin]?.let { return it }
        val tr = (if (ayar.cevrimici) try { cevrimici.cevir(metin) } catch (_: Exception) { null } else null)
            ?.takeIf { it.isNotBlank() }
            ?: cevirmen?.let { try { Tasks.await(it.translate(metin)) } catch (_: Exception) { null } }
            ?: metin
        onbellek[metin] = tr
        return tr
    }

    private fun yayinla(bloklar: List<Blok>) {
        if (kapandi) return
        yayinda = bloklar
        sonGonderim = SystemClock.elapsedRealtime()
        val ceviriler = bloklar.map { b -> cevir(b.metin).let { if (ayar.sansur) Sansur.temizle(it) else it } }
        sonCeviriler = ceviriler
        val dizi = JSONArray()
        bloklar.forEachIndexed { i, b ->
            dizi.put(JSONObject().put("text", ceviriler[i]).put("kutu", JSONArray()
                .put(b.kutu.sol.toDouble()).put(b.kutu.ust.toDouble())
                .put(b.kutu.sag.toDouble()).put(b.kutu.alt.toDouble())))
        }
        tv.gonderBloklar(ayar.tvAdresi, alan.id, dizi, ayar.stilJson())
        ana.post { gosterildi(alan, ceviriler) }
    }

    /** Alan kapatıldı ya da görüntü kesildi: TV'den sil. */
    fun temizle() {
        isciH?.post {
            takip.zorlaTemizle()
            if (yayinda.isNotEmpty()) yayinla(emptyList())
        }
    }

    fun sifirla() { isciH?.post { takip.sifirla() } }

    fun kapat() {
        kapandi = true
        modelHazir = false
        val h = isciH; val t = isci
        isciH = null; isci = null
        val kapatIsi = Runnable {
            if (yayinda.isNotEmpty()) tv.gonderBloklar(ayar.tvAdresi, alan.id, JSONArray(), null)
            tanima?.close(); cevirmen?.close()
            tanima = null; cevirmen = null
            tv.kapat()
        }
        if (h == null || t == null) { kapatIsi.run(); return }
        h.removeCallbacksAndMessages(null)
        h.post(kapatIsi)
        t.quitSafely()
    }
}
