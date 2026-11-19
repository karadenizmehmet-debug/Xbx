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
        // Telefon alanı: oyundaki telefon ekranda değilse (harita, oyun) okuma; ekrandaki çeviriyi sil
        if (alan == Alan.TELEFON && ayar.telefonAlgila && !telefonGorunuyor(kirpik)) {
            sonOkunan = "(telefon ekranı görünmüyor)"
            when (takip.gelen("", ayar.aralikMs)) {
                Karar.Temizle -> yayinla(emptyList())
                else -> {}
            }
            return
        }
        val sonuc = Tasks.await(t.process(InputImage.fromBitmap(kirpik, 0)))
        val min = ayar.minKelime(alan)
        val bloklar = sonuc.textBlocks.flatMap { b ->
            val satirlar = b.lines.filter { YerindeIsleme.dugmeleriAt(it.text).isNotBlank() }
            val metinler = satirlar.map { YerindeIsleme.dugmeleriAt(it.text) }
            // Satır satır yalnızca Bildirim'de (kumanda ipuçları). Telefonda bir balon/kart tek parça çevrilir:
            // "Stoplight Parrotfish / Spot" iki kısa satır olsa da tek başlıktır.
            val satirSatir = alan == Alan.BILDIRIM && YerindeIsleme.satirSatirMi(metinler)
            if (satirSatir || (satirlar.size == 1 && b.lines.size == 1)) {
                // Kumanda ipuçları gibi kısa satırlar: her satır kendi yerine, düğme simgesi açıkta kalır
                satirlar.mapIndexedNotNull { i, l -> satirKoseleri(l)?.let { blokYap(metinler[i], it, kirpik, bolge, true) } }
            } else {
                val k = koseler(b.cornerPoints, b.boundingBox) ?: return@flatMap emptyList()
                listOf(blokYap(metinler.joinToString(" ").replace(Regex("\\s+"), " ").trim(), k, kirpik, bolge, false))
            }
        }.filter { YerindeIsleme.uygunMu(it.metin, min) }
            .sortedWith(compareBy({ it.kutu.ust }, { it.kutu.sol }))
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

    /** Kırpıktan 48×48 ızgara örnekle, telefon ekranı mı bak. */
    private fun telefonGorunuyor(bmp: Bitmap): Boolean {
        val n = 48
        val p = IntArray(n * n) { i ->
            val x = (((i % n) + 0.5f) * bmp.width / n).toInt().coerceIn(0, bmp.width - 1)
            val y = (((i / n) + 0.5f) * bmp.height / n).toInt().coerceIn(0, bmp.height - 1)
            bmp.getPixel(x, y)
        }
        return YerindeIsleme.telefonEkraniMi(p)
    }

    /** Köşe noktaları (x[4], y[4]); yoksa düz kutudan. */
    private fun koseler(p: Array<android.graphics.Point>?, r: android.graphics.Rect?): Pair<FloatArray, FloatArray>? {
        if (p != null && p.size == 4) return FloatArray(4) { p[it].x.toFloat() } to FloatArray(4) { p[it].y.toFloat() }
        r ?: return null
        return floatArrayOf(r.left.toFloat(), r.right.toFloat(), r.right.toFloat(), r.left.toFloat()) to
            floatArrayOf(r.top.toFloat(), r.top.toFloat(), r.bottom.toFloat(), r.bottom.toFloat())
    }

    /** Satırın köşeleri, düğme simgesi ("RB", "(L)") hariç: ilk ve son yazı kelimesinin köşelerinden. */
    private fun satirKoseleri(l: com.google.mlkit.vision.text.Text.Line): Pair<FloatArray, FloatArray>? {
        val kel = l.elements.filter { !YerindeIsleme.dugmeMi(it.text) && it.cornerPoints?.size == 4 }
        if (kel.isEmpty()) return koseler(l.cornerPoints, l.boundingBox)
        val ilk = kel.first().cornerPoints!!; val son = kel.last().cornerPoints!!
        return floatArrayOf(ilk[0].x.toFloat(), son[1].x.toFloat(), son[2].x.toFloat(), ilk[3].x.toFloat()) to
            floatArrayOf(ilk[0].y.toFloat(), son[1].y.toFloat(), son[2].y.toFloat(), ilk[3].y.toFloat())
    }

    /** Eğik kutu + orijinalden örneklenen arka plan rengi (yazının hemen çevresi) ve okunur yazı rengi. */
    private fun blokYap(metin: String, k: Pair<FloatArray, FloatArray>, bmp: Bitmap, bolge: Oran, tek: Boolean): Blok {
        val e = YerindeIsleme.egikKutu(k.first, k.second)
        val arka = renkOrnekle(bmp, e)
        val parlak = android.graphics.Color.luminance(arka) > 0.5f
        return Blok(
            metin, YerindeIsleme.kirpiktanVideoya(e.kutu, bmp.width, bmp.height, bolge), tek, e.aci,
            arka, if (parlak) android.graphics.Color.rgb(20, 20, 20) else android.graphics.Color.WHITE
        )
    }

    /** Yazı kutusunun biraz dışındaki halkadan 32 nokta; kanal başına ortanca (yazı pikselleri karışmasın). */
    private fun renkOrnekle(bmp: Bitmap, e: EgikKutu): Int {
        val cx = (e.kutu.sol + e.kutu.sag) / 2; val cy = (e.kutu.ust + e.kutu.alt) / 2
        val boy = e.kutu.alt - e.kutu.ust
        val yw = (e.kutu.sag - e.kutu.sol) / 2 + boy * 0.3f; val yh = boy / 2 + boy * 0.3f
        val r = Math.toRadians(e.aci.toDouble()); val c = Math.cos(r).toFloat(); val sn = Math.sin(r).toFloat()
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (i in 0 until 32) {
            val t = i / 32f * 4f
            val (dx, dy) = when {
                t < 1f -> (-yw + 2 * yw * t) to -yh
                t < 2f -> yw to (-yh + 2 * yh * (t - 1f))
                t < 3f -> (yw - 2 * yw * (t - 2f)) to yh
                else -> -yw to (yh - 2 * yh * (t - 3f))
            }
            val x = (cx + dx * c - dy * sn).toInt(); val y = (cy + dx * sn + dy * c).toInt()
            if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
            val p = bmp.getPixel(x, y)
            rs.add(android.graphics.Color.red(p)); gs.add(android.graphics.Color.green(p)); bs.add(android.graphics.Color.blue(p))
        }
        if (rs.isEmpty()) return android.graphics.Color.BLACK
        fun orta(l: ArrayList<Int>) = l.sorted()[l.size / 2]
        return android.graphics.Color.rgb(orta(rs), orta(gs), orta(bs))
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
            val o = JSONObject().put("text", ceviriler[i]).put("tek", b.tekSatir).put("aci", b.aci.toDouble())
            if (b.arka != null) o.put("arka", b.arka)
            if (b.yazi != null) o.put("yazi", b.yazi)
            dizi.put(o.put("kutu", JSONArray()
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

    /** Görünüm/hizalama değişti: TV'deki blokları yeni stille yeniden çiz. */
    fun yenidenGonder() { isciH?.post { if (yayinda.isNotEmpty()) yayinla(yayinda) } }

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
