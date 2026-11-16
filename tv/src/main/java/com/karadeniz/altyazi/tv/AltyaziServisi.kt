package com.karadeniz.altyazi.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.HandlerThread
import android.widget.ImageView
import android.widget.LinearLayout
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import org.json.JSONObject

/**
 * Telefondan gelen Türkçe altyazıyı, o an ekranda ne varsa (HDMI/Xbox dahil) üstüne çizer.
 * Kendi ayarı yoktur: boyut, yer, renk ve yazı tipi her mesajla telefondan gelir.
 */
class AltyaziServisi : Service() {

    companion object {
        const val PORT = 8765
        private const val KANAL = "altyazi_tv"
        private const val GUVENLIK_GIZLE_MS = 20_000L
        @Volatile var sonMesaj = "Henüz altyazı gelmedi."
        @Volatile var sunucuCalisiyor = false
        const val EYLEM_TEST = "ekran_testi"
        const val EK_KOD = "kod"
        const val EK_VERI = "veri"
        private const val GERI_SAYIM = 15
        @Volatile var testSonucu = "Henüz yapılmadı."
        @Volatile var testResmi: Bitmap? = null

        fun baslat(ctx: Context) {
            val i = Intent(ctx, AltyaziServisi::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val ana = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var sunucu: Sunucu? = null
    private var kok: FrameLayout? = null
    private var kap: FrameLayout? = null
    private var yazi: TextView? = null
    private val gizle = Runnable { yazi?.visibility = View.GONE }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun bildirim(): Notification = Notification.Builder(this, KANAL)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("Altyazı TV çalışıyor")
        .setContentText("Telefondan altyazı bekleniyor")
        .setOngoing(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(KANAL, "Altyazı TV", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, bildirim())

        sunucu = Sunucu(PORT) { yol, govde -> istek(yol, govde) }.also { it.start() }
        sunucuCalisiyor = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == EYLEM_TEST) {
            val veri: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EK_VERI, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EK_VERI)
            if (veri != null) ekranTesti(intent.getIntExtra(EK_KOD, 0), veri)
        }
        return START_STICKY
    }

    // ---------- Ekran okuma testi ----------
    // Soru: TV, HDMI'dan gelen Xbox görüntüsünü bir uygulamaya okutuyor mu, yoksa siyah mı veriyor?

    private var testProj: MediaProjection? = null
    private var testEkran: VirtualDisplay? = null
    private var testOkuyucu: ImageReader? = null
    private var testIs: HandlerThread? = null
    private var sonucKutusu: View? = null
    private val kareKilidi = Any()
    private var sonKare: Bitmap? = null

    private fun ekranTesti(kod: Int, veri: Intent) {
        if (testProj != null) return
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, bildirim(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(kod, veri)
            if (mp == null) { testSonucu = "Ekran yakalama başlatılamadı."; return }
            testProj = mp
            mp.registerCallback(object : MediaProjection.Callback() {}, ana)
            val m = resources.displayMetrics
            val w = m.widthPixels / 2; val h = m.heightPixels / 2
            val is_ = HandlerThread("test").also { it.start() }
            testIs = is_
            val okuyucu = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            testOkuyucu = okuyucu
            // Gelen her kareyi sakla; en sonuncusu sınanır
            okuyucu.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val pl = img.planes[0]
                    val tam = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                    tam.copyPixelsFromBuffer(pl.buffer)
                    synchronized(kareKilidi) { sonKare?.recycle(); sonKare = tam }
                } catch (_: Exception) {
                } finally { img.close() }
            }, Handler(is_.looper))
            testEkran = mp.createVirtualDisplay(
                "altyazi-test", w, h, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, okuyucu.surface, null, null
            )
            testSonucu = "Test sürüyor…"
            geriSay(GERI_SAYIM)
        } catch (e: Exception) {
            testSonucu = "Bu TV ekran yakalamaya izin vermedi: ${e.javaClass.simpleName}"
            testiBitir()
        }
    }

    private fun geriSay(n: Int) {
        if (n > 0) {
            goster("Ekran testi: şimdi Xbox'a (HDMI girişine) geç.  $n", null, 1500)
            ana.postDelayed({ geriSay(n - 1) }, 1000)
        } else {
            // Kendi yazımızı kaldır; bu, ekranın yeniden çizilmesini ve yeni kare gelmesini de sağlar
            ana.removeCallbacks(gizle)
            yazi?.visibility = View.GONE
            ana.postDelayed({ testiDegerlendir() }, 900)
        }
    }

    private fun testiDegerlendir() {
        val kare = synchronized(kareKilidi) { sonKare?.copy(Bitmap.Config.ARGB_8888, false) }
        testiBitir()
        if (kare == null) {
            testSonucu = "Hiç kare gelmedi ✗ — bu TV ekranı uygulamaya vermiyor."
            sonucuGoster(null, testSonucu)
            return
        }
        // 64x36 noktadan örnekle: kaçında gerçekten görüntü var?
        var dolu = 0; var toplam = 0
        for (y in 0 until 36) for (x in 0 until 64) {
            val px = kare.getPixel(x * (kare.width - 1) / 63, y * (kare.height - 1) / 35)
            val parlaklik = (Color.red(px) * 3 + Color.green(px) * 6 + Color.blue(px)) / 10
            if (parlaklik > 28) dolu++
            toplam++
        }
        val yuzde = dolu * 100 / toplam
        testSonucu = if (yuzde >= 8)
            "Görüntü okunabiliyor olabilir ✓ (%$yuzde dolu). Küçük resimde oyun görünüyorsa Remote Play'siz çalışır."
        else
            "Siyah geldi ✗ (%$yuzde dolu). TV, HDMI görüntüsünü uygulamaya vermiyor; Remote Play gerekli."
        testResmi = kare
        sonucuGoster(kare, testSonucu)
    }

    private fun sonucuGoster(resim: Bitmap?, metin: String) {
        if (!katmaniKur()) return
        val k = kok ?: return
        sonucKutusu?.let { k.removeView(it) }
        val h = resources.displayMetrics.heightPixels
        val kutu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val d = (h * 0.02f).toInt()
            setPadding(d, d, d, d)
            background = GradientDrawable().apply { cornerRadius = h * 0.02f; setColor(Color.argb(235, 20, 20, 20)) }
        }
        if (resim != null) kutu.addView(ImageView(this).apply {
            setImageBitmap(resim)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams((h * 0.62f).toInt(), -2))
        kutu.addView(TextView(this).apply {
            text = "Uygulamanın gördüğü ekran (25 sn):\n$metin"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.03f)
            gravity = Gravity.CENTER
            maxWidth = (h * 0.9f).toInt()
        })
        k.addView(kutu, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        sonucKutusu = kutu
        ana.postDelayed({ if (sonucKutusu === kutu) { k.removeView(kutu); sonucKutusu = null } }, 25_000)
    }

    private fun testiBitir() {
        try { testEkran?.release() } catch (_: Exception) {}
        try { testOkuyucu?.close() } catch (_: Exception) {}
        try { testProj?.stop() } catch (_: Exception) {}
        testEkran = null; testOkuyucu = null; testProj = null
        testIs?.quitSafely(); testIs = null
        synchronized(kareKilidi) { sonKare?.recycle(); sonKare = null }
    }

    private fun istek(yol: String, govde: String): Pair<Int, String> {
        if (yol.startsWith("/ping")) return 200 to "{\"ok\":true,\"surum\":\"${BuildConfig.VERSION_NAME}\"}"
        if (!yol.startsWith("/sub")) return 404 to "{\"ok\":false}"
        if (!Settings.canDrawOverlays(this)) {
            sonMesaj = "Altyazı geldi ama katman izni yok."
            return 503 to "{\"ok\":false,\"hata\":\"katman izni yok\"}"
        }
        val j = JSONObject(if (govde.isBlank()) "{}" else govde)
        val metin = j.optString("text", "")
        val stil = j.optJSONObject("stil")
        val sure = j.optLong("sure", 0L)
        sonMesaj = if (metin.isBlank()) "(altyazı temizlendi)" else metin
        ana.post { goster(metin, stil, sure) }
        return 200 to "{\"ok\":true}"
    }

    private fun katmaniKur(): Boolean {
        if (kok != null) return true
        if (!Settings.canDrawOverlays(this)) return false
        val k = FrameLayout(this)
        val kp = FrameLayout(this)
        val t = TextView(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable()
            visibility = View.GONE
        }
        kp.addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        k.addView(kp, FrameLayout.LayoutParams(100, 100))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        return try {
            wm.addView(k, lp)
            kok = k; kap = kp; yazi = t
            true
        } catch (_: Exception) { false }
    }

    private fun yaziTipi(id: String): Typeface = try {
        when (id) {
            "kalin" -> Typeface.DEFAULT_BOLD
            "dar" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            "serif" -> Typeface.SERIF
            "esaralik" -> Typeface.MONOSPACE
            "nunito" -> ResourcesCompat.getFont(this, R.font.nunito)
            "oswald" -> ResourcesCompat.getFont(this, R.font.oswald)
            "comfortaa" -> ResourcesCompat.getFont(this, R.font.comfortaa)
            "lora" -> ResourcesCompat.getFont(this, R.font.lora)
            else -> Typeface.DEFAULT
        } ?: Typeface.DEFAULT
    } catch (_: Exception) { Typeface.DEFAULT }

    private fun goster(metin: String, stil: JSONObject?, sure: Long) {
        ana.removeCallbacks(gizle)
        if (!katmaniKur()) return
        val t = yazi ?: return
        val kp = kap ?: return
        if (metin.isBlank()) { t.visibility = View.GONE; return }

        val m = resources.displayMetrics
        val w = m.widthPixels; val h = m.heightPixels

        // Telefondan gelen stil; yoksa makul varsayılanlar
        val kutu = stil?.optJSONArray("kutu")
        val l = kutu?.optDouble(0, 0.15) ?: 0.15
        val u = kutu?.optDouble(1, 0.72) ?: 0.72
        val r = kutu?.optDouble(2, 0.85) ?: 0.85
        val a = kutu?.optDouble(3, 0.96) ?: 0.96
        val boyutOran = stil?.optDouble("boyutOran", 0.05) ?: 0.05
        val yaziRengi = stil?.optInt("yaziRengi", Color.WHITE) ?: Color.WHITE
        val arkaRengi = stil?.optInt("arkaRengi", Color.BLACK) ?: Color.BLACK
        val opaklik = (stil?.optInt("arkaOpaklik", 60) ?: 60).coerceIn(0, 100)

        val kw = ((r - l) * w).toInt().coerceAtLeast(120)
        val kh = ((a - u) * h).toInt().coerceAtLeast(60)
        val klp = kp.layoutParams as FrameLayout.LayoutParams
        klp.width = kw; klp.height = kh
        klp.leftMargin = (l * w).toInt(); klp.topMargin = (u * h).toInt()
        kp.layoutParams = klp

        val dolgu = (h * 0.012f).toInt()
        t.setPadding(dolgu * 2, dolgu, dolgu * 2, dolgu)
        t.typeface = yaziTipi(stil?.optString("tip", "varsayilan") ?: "varsayilan")
        t.setTextColor(yaziRengi)
        val parlak = Color.luminance(yaziRengi) > 0.5f
        t.setShadowLayer(h * 0.004f, 0f, 0f, if (parlak) Color.BLACK else Color.WHITE)
        (t.background as GradientDrawable).apply {
            cornerRadius = h * 0.014f
            setColor(Color.argb(opaklik * 255 / 100, Color.red(arkaRengi), Color.green(arkaRengi), Color.blue(arkaRengi)))
        }

        // Kutuya sığana kadar küçült
        val ic = kw - dolgu * 4
        val icY = kh - dolgu * 2
        var boyut = (boyutOran * h).toFloat()
        val enAz = h * 0.018f
        val adim = (h / 540f).coerceAtLeast(1f)
        val boya = TextPaint(t.paint)
        while (boyut > enAz && ic > 0) {
            boya.textSize = boyut
            if (StaticLayout.Builder.obtain(metin, 0, metin.length, boya, ic).build().height <= icY) break
            boyut -= adim
        }
        t.maxWidth = kw
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, boyut)
        t.text = metin
        t.visibility = View.VISIBLE
        ana.postDelayed(gizle, if (sure > 0) sure else GUVENLIK_GIZLE_MS)
    }

    override fun onDestroy() {
        sunucu?.durdur(); sunucu = null
        testiBitir()
        sunucuCalisiyor = false
        ana.removeCallbacksAndMessages(null)
        kok?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        kok = null; kap = null; yazi = null
        super.onDestroy()
    }
}
