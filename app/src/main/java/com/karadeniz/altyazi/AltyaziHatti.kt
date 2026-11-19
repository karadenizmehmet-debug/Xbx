package com.karadeniz.altyazi

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Ortak altyazı hattı: kırpılmış kareden OCR → kararlılık → çeviri → TV + telefon.
 * Ekran kaydı modu (CaptureService) ve uygulama içi mod (UzaktanOynatActivity) aynı hattı kullanır.
 */
class AltyaziHatti(
    private val context: Context,
    private val ayar: Ayarlar,
    /** Çeviri hazır (ana iş parçacığında). Boş `tr` = altyazıyı kaldır. */
    private val gosterildi: (tr: String, orijinal: String) -> Unit,
    /** Kullanıcıya gösterilecek durum mesajı. */
    private val durum: (String) -> Unit,
    /** Çevirinin gideceği TV adresi; boşsa TV'ye gönderilmez. */
    private val tvAdresi: () -> String = { ayar.tvAdresi }
) {
    private val ana = Handler(Looper.getMainLooper())
    private var isci: HandlerThread? = null
    private var isciH: Handler? = null
    private val isciExec = Executor { komut -> isciH?.post(komut) }

    private var tanima: TextRecognizer? = null
    private var cevirmen: Translator? = null
    @Volatile private var modelHazir = false
    private val tv = TvGonderici()
    private val cevrimici = CevrimiciCeviri()
    private val agIsi = Executors.newSingleThreadExecutor()
    private val takip = KararlilikTakip()

    @Volatile private var mesgul = false
    @Volatile private var kapandi = false
    @Volatile var ekrandaMetinVar = false
        private set
    @Volatile var sonTr = ""
        private set

    /** Son OCR sonucu (Kontrol et için). */
    @Volatile var sonOkunan = ""
        private set

    val modelHazirMi: Boolean get() = modelHazir

    /** Yeni kare kabul edilebilir mi? */
    val hazir: Boolean get() = modelHazir && !mesgul

    fun baslat() {
        isci = HandlerThread("altyazi-hat").also { it.start() }
        isciH = Handler(isci!!.looper)
        tanima = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        cevirmen = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
        )
        durum("Çeviri modeli hazırlanıyor (ilk seferde ~30 MB iner)…")
        cevirmen!!.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (kapandi) return@addOnSuccessListener
                modelHazir = true
                durum("Hazır. Altyazılar okunuyor.")
            }
            .addOnFailureListener { durum("Çeviri modeli indirilemedi: ${it.message}") }
    }

    /**
     * Kırpılmış kareyi işler. Hat meşgulse kareyi atar. Bitmap'in sahibi artık hattır.
     * [atla]: kırpık koordinatındaki satır merkezi için true dönerse o satır okunmaz (kendi kaplamalarımız).
     */
    fun isle(kirpik: Bitmap, atla: (x: Int, y: Int) -> Boolean = { _, _ -> false }) {
        val t = tanima
        if (!hazir || t == null) { kirpik.recycle(); return }
        mesgul = true
        val gorev = try {
            t.process(InputImage.fromBitmap(kirpik, 0))
        } catch (e: Exception) {
            kirpik.recycle(); mesgul = false
            durum("Okuma hatası: ${e.message}")
            return
        }
        gorev
            .addOnSuccessListener(isciExec) { sonuc ->
                kirpik.recycle()
                val satirlar = sonuc.textBlocks.flatMap { it.lines }
                    .filter { s ->
                        val k = s.boundingBox ?: return@filter true
                        !atla(k.centerX(), k.centerY())
                    }
                    .map { it.text }
                val metin = MetinIsleme.parcalariTemizle(satirlar)
                sonOkunan = metin
                metinGeldi(metin)
            }
            .addOnFailureListener(isciExec) { kirpik.recycle(); mesgul = false }
    }

    private fun metinGeldi(metin: String) {
        when (val k = takip.gelen(metin, ayar.aralikMs)) {
            Karar.Bekle -> mesgul = false
            Karar.Temizle -> { yayinla("", ""); mesgul = false }
            is Karar.Cevir -> cevir(k.metin)
        }
    }

    private fun cevir(metin: String) {
        if (ayar.cevrimici && !agIsi.isShutdown) {
            agIsi.execute {
                val tr = try { cevrimici.cevir(metin) } catch (_: Exception) { null }
                isciH?.post {
                    if (tr.isNullOrBlank()) yerelCevir(metin) else ceviriBitti(tr, metin)
                } ?: run { mesgul = false }
            }
        } else yerelCevir(metin)
    }

    /** Cihazdaki ML Kit çevirisi (internetsiz; çevrimiçi başarısız olursa yedek). */
    private fun yerelCevir(metin: String) {
        val c = cevirmen
        if (c == null) { mesgul = false; return }
        c.translate(metin)
            .addOnSuccessListener(isciExec) { tr -> ceviriBitti(tr, metin) }
            .addOnFailureListener(isciExec) { mesgul = false }
    }

    private fun ceviriBitti(tr: String, orijinal: String) {
        takip.ceviriGosterildi()
        yayinla(tr, orijinal)
        mesgul = false
    }

    private fun yayinla(tr: String, orijinal: String) {
        ekrandaMetinVar = tr.isNotBlank()
        sonTr = tr
        val adres = tvAdresi()
        if (adres.isNotBlank()) tv.gonder(adres, tr, orijinal, ayar.stilJson()) { ok, bilgi -> tvSonucu(adres, ok, bilgi) }
        ana.post {
            gosterildi(tr, orijinal)
            Durum.sonCeviriDinleyici?.invoke(orijinal, tr)
        }
    }

    /** Görüntü kesildi (bağlantı koptu, uygulama arka planda): ekrandaki çeviriyi TV'den ve telefondan sil. */
    fun temizle() { isciH?.post { if (takip.zorlaTemizle()) yayinla("", "") } }

    /** Bölge değişti: aynı altyazı yeniden çevrilebilsin. */
    fun sifirla() { isciH?.post { takip.sifirla() } }

    @Volatile private var tvSonOk: Boolean? = null

    /** TV'ye gönderim durumu değişince (ulaşıldı ⇄ ulaşılamadı) kullanıcıya bir kez söyle. */
    private fun tvSonucu(adres: String, ok: Boolean, bilgi: String) {
        if (tvSonOk == ok) return
        tvSonOk = ok
        durum(when {
            ok -> "Çeviri TV'ye gidiyor ($adres)."
            bilgi == "HTTP 503" -> "TV'ye ulaşıldı ama TV'de katman (üstte gösterme) izni yok."
            else -> "TV'ye ulaşılamadı ($adres): $bilgi. TV'de Altyazı açık mı?"
        })
    }

    /** Görünüm değişti: ekrandaki çeviriyi (yoksa 2,5 sn'lik örneği) TV'ye yeniden gönder. */
    fun stiliTvyeGonder() {
        val adres = tvAdresi()
        if (adres.isBlank() || kapandi) return
        if (ekrandaMetinVar && sonTr.isNotBlank()) tv.gonder(adres, sonTr, "", ayar.stilJson())
        else tv.gonder(adres, "Örnek altyazı metni", "", ayar.stilJson(), 2500)
    }

    /**
     * Hattı kapatır. Kapatma işi hat iş parçacığına sıraya konur: o an çalışan bir
     * OCR/çeviri dinleyicisi bitmeden yürütücüler kapanmaz (RejectedExecutionException olmaz).
     */
    fun kapat() {
        kapandi = true
        modelHazir = false
        val h = isciH
        val t = isci
        isciH = null; isci = null
        val kapatIsi = Runnable {
            if (ekrandaMetinVar) tv.gonder(tvAdresi(), "", "")
            tanima?.close(); cevirmen?.close()
            tanima = null; cevirmen = null
            agIsi.shutdown()
            tv.kapat()
        }
        if (h == null || t == null) { kapatIsi.run(); return }
        h.removeCallbacksAndMessages(null)
        h.post(kapatIsi)
        t.quitSafely()
    }
}
