package com.karadeniz.altyazi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Ekranı (Remote Play görüntüsünü) yakalar, seçili bölgeden altyazıyı okur,
 * İngilizceden Türkçeye çevirir ve TV'ye / telefon kaplamasına gönderir.
 */
class CaptureService : Service() {

    companion object {
        const val EYLEM_BASLAT = "baslat"
        const val EYLEM_DURDUR = "durdur"
        const val EK_KOD = "kod"
        const val EK_VERI = "veri"
        private const val KANAL = "altyazi_kanal"
        private const val BILDIRIM_ID = 7
        private const val BOS_TEMIZLEME_MS = 1300L
    }

    private lateinit var ayar: Ayarlar
    private lateinit var wm: WindowManager
    private val ana = Handler(Looper.getMainLooper())
    private var isci: HandlerThread? = null
    private var isciH: Handler? = null

    private var projeksiyon: MediaProjection? = null
    private var sanalEkran: VirtualDisplay? = null
    private var okuyucu: ImageReader? = null
    private var genislik = 0
    private var yukseklik = 0
    private var dpi = 0

    private var tanima: TextRecognizer? = null
    private var cevirmen: Translator? = null
    private var modelHazir = false
    private val tv = TvGonderici()
    private val cevrimici = CevrimiciCeviri()
    private val agIsi = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Volatile private var mesgul = false
    @Volatile private var duraklat = false
    private var sonGonderilen = ""      // normalize edilmiş, en son çevrilen metin
    private var aday = ""               // kararlılık bekleyen metin
    private var adaySayac = 0
    private var bosSure = 0L
    private var ekrandaMetinVar = false

    private var kontrolBar: View? = null
    private var ceviriKutusu: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ayar = Ayarlar(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            EYLEM_DURDUR -> { durdur(); return START_NOT_STICKY }
            EYLEM_BASLAT -> {
                if (Durum.calisiyor) return START_NOT_STICKY
                bildirimleBasla()
                val kod = intent.getIntExtra(EK_KOD, 0)
                val veri: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EK_VERI, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EK_VERI)
                if (veri == null) { durum("Ekran izni alınamadı."); durdur(); return START_NOT_STICKY }
                basla(kod, veri)
            }
        }
        return START_NOT_STICKY
    }

    // ---------- Başlatma ----------

    private fun bildirimleBasla() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(KANAL, "Altyazı çeviri", NotificationManager.IMPORTANCE_LOW)
        )
        val durdurPI = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(EYLEM_DURDUR),
            PendingIntent.FLAG_IMMUTABLE
        )
        val acPI = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b: Notification = Notification.Builder(this, KANAL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Altyazı çalışıyor")
            .setContentText("Ekrandaki altyazı Türkçeye çevriliyor")
            .setContentIntent(acPI)
            .addAction(Notification.Action.Builder(null, "Durdur", durdurPI).build())
            .setOngoing(true)
            .build()
        startForeground(BILDIRIM_ID, b, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun basla(kod: Int, veri: Intent) {
        Durum.calisiyor = true
        isci = HandlerThread("altyazi-isci").also { it.start() }
        isciH = Handler(isci!!.looper)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(kod, veri)
        if (mp == null) { durum("Ekran yakalama başlatılamadı."); durdur(); return }
        projeksiyon = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ana.post { durdur() } }
        }, ana)

        olculeriAl()
        okuyucu = ImageReader.newInstance(genislik, yukseklik, PixelFormat.RGBA_8888, 2)
        sanalEkran = mp.createVirtualDisplay(
            "altyazi", genislik, yukseklik, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            okuyucu!!.surface, null, isciH
        )

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
                modelHazir = true
                durum("Hazır. Remote Play'e geç; altyazılar okunuyor.")
            }
            .addOnFailureListener { durum("Çeviri modeli indirilemedi: ${it.message}") }

        kaplamalariGoster()
        isciH?.post(dongu)
    }

    private fun olculeriAl() {
        val m: DisplayMetrics = resources.displayMetrics
        val bounds = wm.maximumWindowMetrics.bounds
        genislik = bounds.width()
        yukseklik = bounds.height()
        dpi = m.densityDpi
    }

    /** Ekran döndüğünde (Remote Play yatay) sanal ekranı yeni boyuta uyarla. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = sanalEkran ?: return
        val eskiG = genislik; val eskiY = yukseklik
        olculeriAl()
        if (eskiG == genislik && eskiY == yukseklik) return
        stilUygula(false)
        isciH?.post {
            val yeni = ImageReader.newInstance(genislik, yukseklik, PixelFormat.RGBA_8888, 2)
            vd.resize(genislik, yukseklik, dpi)
            vd.surface = yeni.surface
            val eski = okuyucu
            okuyucu = yeni
            eski?.close()
        }
    }

    // ---------- Ana döngü ----------

    private val dongu = object : Runnable {
        override fun run() {
            if (!Durum.calisiyor) return
            try {
                if (!mesgul && !duraklat && modelHazir) kareIsle()
                else okuyucu?.acquireLatestImage()?.close()   // kuyruğu boşalt
            } catch (e: Exception) {
                durum("Hata: ${e.message}")
                mesgul = false
            }
            isciH?.postDelayed(this, ayar.aralikMs)
        }
    }

    private fun kareIsle() {
        val r = okuyucu ?: return
        val img = r.acquireLatestImage() ?: return
        val kirpik: Bitmap
        try {
            val plane = img.planes[0]
            val pikselAdim = plane.pixelStride
            val satirAdim = plane.rowStride
            val tamGenislik = satirAdim / pikselAdim
            val tam = Bitmap.createBitmap(tamGenislik, img.height, Bitmap.Config.ARGB_8888)
            tam.copyPixelsFromBuffer(plane.buffer)
            val o = ayar.bolge
            val w = img.width; val h = img.height
            val rect = Rect(
                (o.left * w).toInt().coerceIn(0, w - 1),
                (o.top * h).toInt().coerceIn(0, h - 1),
                (o.right * w).toInt().coerceIn(1, w),
                (o.bottom * h).toInt().coerceIn(1, h)
            )
            if (rect.width() < 8 || rect.height() < 8) { tam.recycle(); return }
            kirpX = rect.left; kirpY = rect.top
            kirpik = Bitmap.createBitmap(tam, rect.left, rect.top, rect.width(), rect.height())
            if (kirpik !== tam) tam.recycle()
        } finally {
            img.close()
        }

        mesgul = true
        tanima!!.process(InputImage.fromBitmap(kirpik, 0))
            .addOnSuccessListener(isciExec) { sonuc ->
                val metin = temizle(sonuc)
                metinGeldi(metin)
                kirpik.recycle()
                if (!cevirmeBekliyor) mesgul = false
            }
            .addOnFailureListener(isciExec) {
                kirpik.recycle(); mesgul = false
            }
    }

    private val isciExec = java.util.concurrent.Executor { komut -> isciH?.post(komut) }
    @Volatile private var cevirmeBekliyor = false
    private var kirpX = 0
    private var kirpY = 0
    @Volatile private var kutuAlani: Rect? = null     // çeviri kutusunun ekrandaki yeri
    @Volatile private var barAlani: Rect? = null      // kontrol çubuğunun ekrandaki yeri
    private var ornekGosteriliyor = false

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun metinGeldi(metin: String) {
        val n = normalize(metin)
        if (n.length < 3) {
            bosSure += ayar.aralikMs
            aday = ""; adaySayac = 0
            if (ekrandaMetinVar && bosSure >= BOS_TEMIZLEME_MS) {
                ekrandaMetinVar = false
                sonGonderilen = ""
                yayinla("", "")
            }
            return
        }
        bosSure = 0
        if (benzerlik(n, sonGonderilen) >= 0.82) return     // aynı altyazı hâlâ ekranda

        // Yazı beliriyor/değişiyorsa bir kare daha bekle (kararlı olsun)
        if (benzerlik(n, aday) >= 0.9) adaySayac++ else { aday = n; adaySayac = 1 }
        if (adaySayac < 2) return

        sonGonderilen = n
        aday = ""; adaySayac = 0
        cevirmeBekliyor = true
        if (ayar.cevrimici) {
            agIsi.execute {
                val tr = try { cevrimici.cevir(metin) } catch (_: Exception) { null }
                isciH?.post {
                    if (tr.isNullOrBlank()) yerelCevir(metin) else ceviriBitti(tr, metin)
                } ?: run { cevirmeBekliyor = false; mesgul = false }
            }
        } else yerelCevir(metin)
    }

    private fun ceviriBitti(tr: String, orijinal: String) {
        ekrandaMetinVar = true
        yayinla(tr, orijinal)
        cevirmeBekliyor = false; mesgul = false
    }

    /** Cihazdaki ML Kit çevirisi (internetsiz; çevrimiçi başarısız olursa yedek). */
    private fun yerelCevir(metin: String) {
        val c = cevirmen
        if (c == null) { cevirmeBekliyor = false; mesgul = false; return }
        c.translate(metin)
            .addOnSuccessListener(isciExec) { tr -> ceviriBitti(tr, metin) }
            .addOnFailureListener(isciExec) { cevirmeBekliyor = false; mesgul = false }
    }

    /**
     * OCR sonucunu temizler: altyazı olmayan tek harflik parçaları atar.
     * (Remote Play'in dokunmatik A/B/X/Y düğmeleri "A" okunup "bir" diye çevriliyordu.)
     */
    private fun temizle(sonuc: Text): String {
        val kutu = kutuAlani; val bar = barAlani
        val satirlar = sonuc.textBlocks.flatMap { it.lines }
            .filter { s -> s.text.count { it.isLetter() } >= 2 }
            .filter { s ->
                // Kendi kaplamalarımızın üstündeki yazıyı okuma
                val k = s.boundingBox ?: return@filter true
                val x = k.centerX() + kirpX; val y = k.centerY() + kirpY
                !(kutu?.contains(x, y) == true || bar?.contains(x, y) == true)
            }
        val parcalar = satirlar.joinToString(" ") { it.text }
            .replace(Regex("\\s+"), " ").trim()
            .split(" ").filter { it.isNotEmpty() }.toMutableList()
        fun artik(t: String) = t.length == 1 && t[0].isLetterOrDigit() && t != "I"
        while (parcalar.isNotEmpty() && artik(parcalar.last())) parcalar.removeAt(parcalar.size - 1)
        while (parcalar.isNotEmpty() && parcalar.first().length == 1 && parcalar.first()[0] in "BXYbxy")
            parcalar.removeAt(0)
        return parcalar.joinToString(" ")
    }

    private fun yayinla(tr: String, orijinal: String) {
        val adres = ayar.tvAdresi
        if (adres.isNotBlank()) tv.gonder(adres, tr, orijinal)
        ana.post {
            ceviriKutusu?.let {
                ornekGosteriliyor = false
                it.text = tr
                if (tr.isBlank()) { it.visibility = View.GONE; kutuAlani = null }
                else it.visibility = View.VISIBLE
            }
            Durum.sonCeviriDinleyici?.invoke(orijinal, tr)
        }
    }

    /** 0..1 arası benzerlik (Levenshtein). */
    private fun benzerlik(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return if (a == b) 1.0 else 0.0
        if (a == b) return 1.0
        val n = a.length; val m = b.length
        var onceki = IntArray(m + 1) { it }
        var simdi = IntArray(m + 1)
        for (i in 1..n) {
            simdi[0] = i
            for (j in 1..m) {
                val k = if (a[i - 1] == b[j - 1]) 0 else 1
                simdi[j] = minOf(onceki[j] + 1, simdi[j - 1] + 1, onceki[j - 1] + k)
            }
            val t = onceki; onceki = simdi; simdi = t
        }
        return 1.0 - onceki[m].toDouble() / maxOf(n, m)
    }

    // ---------- Telefon kaplamaları ----------

    private fun kaplamaParam(w: Int, h: Int, dokunulabilir: Boolean): WindowManager.LayoutParams {
        // FLAG_SECURE yok: ekran görüntüsü alınabilsin. Kendi yazımızı okumamak için
        // kaplamaların üstüne düşen OCR satırları temizle() içinde atılır.
        var bayrak = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!dokunulabilir) bayrak = bayrak or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, bayrak, PixelFormat.TRANSLUCENT
        )
    }

    private fun kaplamalariGoster() {
        // Küçük kontrol çubuğu: Bölge seç / Duraklat / Durdur
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 40f; setColor(Color.argb(170, 20, 20, 20))
            }
            setPadding(10, 4, 10, 4)
        }
        fun dugme(yazi: String, tik: (Button) -> Unit) = Button(this).apply {
            text = yazi; textSize = 11f; minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setPadding(22, 6, 22, 6)
            setOnClickListener { tik(this) }
        }
        bar.addView(dugme("Bölge") { bolgeSec() })
        bar.addView(dugme("Duraklat") { b ->
            duraklat = !duraklat
            b.text = if (duraklat) "Devam" else "Duraklat"
            durum(if (duraklat) "Duraklatıldı" else "Çalışıyor")
        })
        bar.addView(dugme("Durdur") { durdur() })
        val lp = kaplamaParam(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = 16; lp.y = 16
        wm.addView(bar, lp)
        kontrolBar = bar

        if (ayar.telefondaGoster) {
            val kutu = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                background = GradientDrawable().apply {
                    cornerRadius = 18f; setColor(Color.argb(150, 0, 0, 0))
                }
                setPadding(24, 10, 24, 10)
                visibility = View.GONE
            }
            val klp = kaplamaParam(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false
            )
            klp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            kutu.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                kutuAlani = if (v.visibility == View.VISIBLE && v.width > 0) ekranAlani(v) else null
            }
            wm.addView(kutu, klp)
            ceviriKutusu = kutu
            stilUygula(false)
        }
        bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            barAlani = if (v.visibility == View.VISIBLE && v.width > 0) ekranAlani(v) else null
        }
        Durum.stilDinleyici = { ana.post { stilUygula(true) } }
    }

    private fun ekranAlani(v: View): Rect {
        val k = IntArray(2); v.getLocationOnScreen(k)
        return Rect(k[0], k[1], k[0] + v.width, k[1] + v.height)
    }

    /** Boyut, konum, renk ve şeffaflık ayarlarını çeviri kutusuna uygular. */
    private fun stilUygula(ornekGoster: Boolean) {
        val k = ceviriKutusu ?: return
        k.textSize = ayar.yaziBoyutu.toFloat()
        k.setTextColor(ayar.yaziRengi)
        val parlak = Color.luminance(ayar.yaziRengi) > 0.5f
        k.setShadowLayer(4f, 0f, 0f, if (parlak) Color.BLACK else Color.WHITE)
        (k.background as GradientDrawable).setColor(ayar.arkaArgb())
        k.maxWidth = (genislik * 0.92f).toInt()
        val lp = k.layoutParams as WindowManager.LayoutParams
        lp.y = (ayar.konumYuzde / 100f * yukseklik * 0.90f).toInt()
        try { wm.updateViewLayout(k, lp) } catch (_: Exception) {}
        if (ornekGoster && (k.visibility != View.VISIBLE || ornekGosteriliyor)) {
            ornekGosteriliyor = true
            k.text = "Örnek altyazı metni"
            k.visibility = View.VISIBLE
            ana.removeCallbacks(ornekGizle)
            ana.postDelayed(ornekGizle, 2500)
        }
    }

    private val ornekGizle = Runnable {
        if (ornekGosteriliyor) {
            ornekGosteriliyor = false
            ceviriKutusu?.visibility = View.GONE
            kutuAlani = null
        }
    }

    private fun bolgeSec() {
        duraklat = true
        kontrolBar?.visibility = View.GONE
        BolgeSecici(this, wm, ayar.bolge) { yeni ->
            if (yeni != null) {
                ayar.bolge = yeni
                sonGonderilen = ""; aday = ""; adaySayac = 0
                durum("Bölge kaydedildi.")
            }
            kontrolBar?.visibility = View.VISIBLE
            duraklat = false
        }.goster()
    }

    // ---------- Kapatma ----------

    private fun durum(s: String) = ana.post { Durum.dinleyici?.invoke(s) }

    private fun durdur() {
        if (!Durum.calisiyor && projeksiyon == null) { stopSelf(); return }
        Durum.calisiyor = false
        isciH?.removeCallbacksAndMessages(null)
        if (ekrandaMetinVar) tv.gonder(ayar.tvAdresi, "", "")
        try { sanalEkran?.release() } catch (_: Exception) {}
        try { okuyucu?.close() } catch (_: Exception) {}
        try { projeksiyon?.stop() } catch (_: Exception) {}
        sanalEkran = null; okuyucu = null; projeksiyon = null
        tanima?.close(); cevirmen?.close()
        tanima = null; cevirmen = null
        kontrolBar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        ceviriKutusu?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        kontrolBar = null; ceviriKutusu = null
        kutuAlani = null; barAlani = null
        Durum.stilDinleyici = null
        ana.removeCallbacks(ornekGizle)
        isci?.quitSafely(); isci = null; isciH = null
        durum("Durduruldu.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (Durum.calisiyor) durdur()
        tv.kapat()
        agIsi.shutdown()
        super.onDestroy()
    }
}
