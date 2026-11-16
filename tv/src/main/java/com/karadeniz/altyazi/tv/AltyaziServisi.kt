package com.karadeniz.altyazi.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
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

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(KANAL, "Altyazı TV", NotificationManager.IMPORTANCE_LOW))
        val b: Notification = Notification.Builder(this, KANAL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Altyazı TV çalışıyor")
            .setContentText("Telefondan altyazı bekleniyor")
            .setOngoing(true)
            .build()
        startForeground(1, b)

        sunucu = Sunucu(PORT) { yol, govde -> istek(yol, govde) }.also { it.start() }
        sunucuCalisiyor = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
        sunucuCalisiyor = false
        ana.removeCallbacksAndMessages(null)
        kok?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        kok = null; kap = null; yazi = null
        super.onDestroy()
    }
}
