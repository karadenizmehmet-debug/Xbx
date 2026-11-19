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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

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

    private var hat: AltyaziHatti? = null

    @Volatile private var duraklat = false

    private var kontrolBar: View? = null
    private var ceviriKutusu: TextView? = null
    private var ceviriKap: FrameLayout? = null
    private var maske: View? = null
    private var maskeCizim: View? = null

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

        hat = AltyaziHatti(this, ayar, ::ceviriyiGoster, ::durum) { tvHedef() }.also { it.baslat() }
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
                if (!duraklat && hat?.hazir == true) kareIsle()
                else okuyucu?.acquireLatestImage()?.close()   // kuyruğu boşalt
            } catch (e: Exception) {
                durum("Hata: ${e.message}")
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
            rect0X = rect.left; rect0Y = rect.top
            kirpik = Bitmap.createBitmap(tam, rect.left, rect.top, rect.width(), rect.height())
            if (kirpik !== tam) tam.recycle()
        } finally {
            img.close()
        }

        val h = hat ?: run { kirpik.recycle(); return }
        val kx = rect0X; val ky = rect0Y
        val kutu = kutuAlani; val bar = barAlani
        h.isle(kirpik) { x, y ->
            // Kendi kaplamalarımızın üstündeki yazıyı okuma
            kutu?.contains(x + kx, y + ky) == true || bar?.contains(x + kx, y + ky) == true
        }
    }

    private var rect0X = 0
    private var rect0Y = 0
    @Volatile private var kutuAlani: Rect? = null     // çeviri kutusunun ekrandaki yeri
    @Volatile private var barAlani: Rect? = null      // kontrol çubuğunun ekrandaki yeri
    private var ornekGosteriliyor = false

    /** Hattan gelen çeviriyi telefon kaplamasına yazar (ana iş parçacığı). */
    @Suppress("UNUSED_PARAMETER")
    private fun ceviriyiGoster(tr: String, orijinal: String) {
        ceviriKutusu?.let {
            ornekGosteriliyor = false
            it.text = tr
            sigdir()
            if (tr.isBlank()) { it.visibility = View.GONE; kutuAlani = null }
            else it.visibility = View.VISIBLE
        }
    }

    // ---------- Telefon kaplamaları ----------

    private fun kaplamaParam(w: Int, h: Int, dokunulabilir: Boolean): WindowManager.LayoutParams {
        // FLAG_SECURE yok: ekran görüntüsü alınabilsin. Kendi yazımızı okumamak için
        // kaplamaların üstüne düşen OCR satırları AltyaziHatti.isle(atla) ile atılır.
        var bayrak = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!dokunulabilir) bayrak = bayrak or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        bayrak = bayrak or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, bayrak, PixelFormat.TRANSLUCENT
        ).apply {
            // Koordinatlar tam ekrana göre olsun (çentik alanı dahil)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun kaplamalariGoster() {
        // Sadece altyazı modu: okuma bölgesi dışında her yeri karart.
        // Dokunuşları da yutar; telefon TV'nin altında dururken oyuna yanlışlıkla basılmaz.
        if (ayar.sadeceAltyazi) {
            val m = object : View(this) {
                private val boya = Paint().apply { color = Color.BLACK }
                override fun onDraw(c: Canvas) {
                    val o = ayar.bolge
                    val w = width.toFloat(); val h = height.toFloat()
                    val l = o.left * w; val t = o.top * h; val r = o.right * w; val b = o.bottom * h
                    c.drawRect(0f, 0f, w, t, boya)
                    c.drawRect(0f, b, w, h, boya)
                    c.drawRect(0f, t, l, b, boya)
                    c.drawRect(r, t, w, b, boya)
                }
            }
            val mlp = kaplamaParam(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true
            )
            mlp.flags = mlp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            val mk = FrameLayout(this)
            mk.addView(m, FrameLayout.LayoutParams(-1, -1))
            // Her zaman erişilebilir çıkış düğmesi
            mk.addView(TextView(this).apply {
                text = "✕  Çık"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(34, 20, 34, 20)
                background = GradientDrawable().apply { cornerRadius = 40f; setColor(Color.rgb(160, 30, 30)) }
                setOnClickListener { durdur() }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                topMargin = durumCubugu() + 16; leftMargin = 24
            })
            wm.addView(mk, mlp)
            maske = mk
            maskeCizim = m
        }
        // Küçük kontrol çubuğu: Bölge seç / Duraklat / Durdur
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 40f; setColor(Color.argb(170, 20, 20, 20))
            }
            setPadding(10, 4, 10, 4)
        }
        fun dugme(yazi: String, tik: (TextView) -> Unit) = TextView(this).apply {
            text = yazi; textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(26, 14, 26, 14)
            setOnClickListener { tik(this) }
        }
        bar.addView(dugme("Bölge") { bolgeSec() })
        bar.addView(dugme("Kutu") { kutuSec() })
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
        lp.x = 16; lp.y = 16 + durumCubugu()
        wm.addView(bar, lp)
        kontrolBar = bar

        if (ayar.telefondaGoster || ayar.sadeceAltyazi) {
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
            klp.gravity = Gravity.TOP or Gravity.START
            kutu.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                kutuAlani = if (v.visibility == View.VISIBLE && v.width > 0) ekranAlani(v) else null
            }
            // Sabit alan (kullanıcının çizdiği kutu); yazı içinde ortalanır
            val kap = FrameLayout(this)
            kap.addView(kutu, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            wm.addView(kap, klp)
            ceviriKap = kap
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
        // Ayar değişince TV'ye de hemen yansıt (altyazı yoksa 2,5 sn'lik örnek)
        if (ornekGoster) hat?.stiliTvyeGonder()
        val k = ceviriKutusu ?: return
        k.textSize = etkinBoyut().toFloat()
        k.setTextColor(ayar.yaziRengi)
        val parlak = Color.luminance(ayar.yaziRengi) > 0.5f
        k.setShadowLayer(4f, 0f, 0f, if (parlak) Color.BLACK else Color.WHITE)
        (k.background as GradientDrawable).setColor(ayar.arkaArgb())
        k.typeface = YaziTipleri.getir(this, ayar.yaziTipi)
        val kap = ceviriKap ?: return
        val o = etkinKutu()
        val lp = kap.layoutParams as WindowManager.LayoutParams
        lp.x = (o.left * genislik).toInt()
        lp.y = (o.top * yukseklik).toInt()
        lp.width = ((o.right - o.left) * genislik).toInt().coerceAtLeast(80)
        lp.height = ((o.bottom - o.top) * yukseklik).toInt().coerceAtLeast(40)
        try { wm.updateViewLayout(kap, lp) } catch (_: Exception) {}
        if (ornekGoster && (k.visibility != View.VISIBLE || ornekGosteriliyor)) {
            ornekGosteriliyor = true
            k.text = "Örnek altyazı metni"
            k.visibility = View.VISIBLE
            ana.removeCallbacks(ornekGizle)
            ana.postDelayed(ornekGizle, 2500)
        }
        sigdir()
    }

    /** Yazı kutuya sığmıyorsa sığana kadar küçültür (en çok ayarlanan boyut, en az 8). */
    private fun sigdir() {
        val k = ceviriKutusu ?: return
        val o = etkinKutu()
        val w = ((o.right - o.left) * genislik).toInt().coerceAtLeast(80) - k.paddingLeft - k.paddingRight
        val h = ((o.bottom - o.top) * yukseklik).toInt().coerceAtLeast(40) - k.paddingTop - k.paddingBottom
        val metin = k.text
        var boyut = etkinBoyut()
        if (w > 0 && h > 0 && metin.isNotEmpty()) {
            val boya = TextPaint(k.paint)
            while (boyut > 8) {
                boya.textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, boyut.toFloat(), resources.displayMetrics
                )
                val yerlesim = StaticLayout.Builder.obtain(metin, 0, metin.length, boya, w).build()
                if (yerlesim.height <= h) break
                boyut--
            }
        }
        k.maxWidth = w + k.paddingLeft + k.paddingRight
        k.textSize = boyut.toFloat()
    }

    /** Sadece altyazı modunda kutu = okuma bölgesinin üstündeki (ya da altındaki) tüm boş alan. */
    private fun etkinKutu(): RectF {
        if (!ayar.sadeceAltyazi) return ayar.kutu
        val o = ayar.bolge
        return if (o.top >= 1f - o.bottom) RectF(0.04f, 0.04f, 0.96f, (o.top - 0.02f).coerceAtLeast(0.2f))
        else RectF(0.04f, (o.bottom + 0.02f).coerceAtMost(0.8f), 0.96f, 0.96f)
    }

    /** Tam ekran modunda "yalnızca telefon" seçiliyse TV'ye gönderme. */
    private fun tvHedef(): String =
        if (ayar.sadeceAltyazi && !ayar.sadeceTvGonder) "" else ayar.tvAdresi

    /** Durum çubuğu yüksekliği: düğmeler altında kalıp dokunulmaz olmasın. */
    private fun durumCubugu(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun etkinBoyut(): Int = if (ayar.sadeceAltyazi) 64 else ayar.yaziBoyutu

    private fun kutuSec() {
        duraklat = true
        kontrolBar?.visibility = View.GONE
        ceviriKap?.visibility = View.GONE
        maske?.visibility = View.GONE
        BolgeSecici(this, wm, ayar.kutu, "Türkçe altyazının görüneceği alanı çiz") { yeni ->
            if (yeni != null) ayar.kutu = yeni
            ceviriKap?.visibility = View.VISIBLE
            maske?.visibility = View.VISIBLE
            kontrolBar?.visibility = View.VISIBLE
            duraklat = false
            stilUygula(true)
        }.goster()
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
        maske?.visibility = View.GONE
        BolgeSecici(this, wm, ayar.bolge, "İngilizce altyazının çıktığı alanı çiz") { yeni ->
            if (yeni != null) {
                ayar.bolge = yeni
                sonGonderilen = ""; aday = ""; adaySayac = 0
                durum("Bölge kaydedildi.")
            }
            maske?.visibility = View.VISIBLE
            maskeCizim?.invalidate()
            kontrolBar?.visibility = View.VISIBLE
            duraklat = false
            stilUygula(false)
        }.goster()
    }

    // ---------- Kapatma ----------

    private fun durum(s: String) = ana.post { Durum.dinleyici?.invoke(s) }

    private fun durdur() {
        if (!Durum.calisiyor && projeksiyon == null) { stopSelf(); return }
        Durum.calisiyor = false
        isciH?.removeCallbacksAndMessages(null)
        try { sanalEkran?.release() } catch (_: Exception) {}
        try { okuyucu?.close() } catch (_: Exception) {}
        try { projeksiyon?.stop() } catch (_: Exception) {}
        sanalEkran = null; okuyucu = null; projeksiyon = null
        hat?.kapat(); hat = null
        kontrolBar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        ceviriKap?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        ceviriKap = null
        maske?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        maske = null; maskeCizim = null
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
        super.onDestroy()
    }
}
