package com.karadeniz.altyazi

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Uygulama içi Remote Play: play.xbox.com bir WebView'da açılır, Microsoft girişi
 * Microsoft'un kendi sayfasında yapılır (uygulama şifreyi görmez, yalnızca çerezler saklanır).
 * Altyazı bölgesi video karesinden JS ile kırpılıp ortak AltyaziHatti'na verilir.
 */
class UzaktanOynatActivity : AppCompatActivity() {

    companion object {
        private const val ADRES = "https://www.xbox.com/tr-TR/play"
        private const val CUBUK_SURE_MS = 4000L
    }

    private lateinit var ayar: Ayarlar
    private lateinit var web: WebView
    private lateinit var kok: FrameLayout
    private lateinit var cubuk: LinearLayout
    private lateinit var tutamac: TextView
    private lateinit var durumYazi: TextView
    private lateinit var sesDugme: TextView
    private lateinit var ceviri: TextView
    private val ana = Handler(Looper.getMainLooper())
    private var hat: AltyaziHatti? = null

    /** Videonun ekrandaki içerik alanı (fiziksel piksel), JS bildirir. */
    @Volatile private var videoAlani = Oran(0f, 0f, 0f, 0f)

    private val cubuguGizle = Runnable { cubuk.visibility = View.GONE; tutamac.visibility = View.VISIBLE }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ayar = Ayarlar(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        kok = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this)
        kok.addView(web, FrameLayout.LayoutParams(-1, -1))
        ceviri = ceviriYazisi()
        kok.addView(ceviri, FrameLayout.LayoutParams(-2, -2))
        cubuk = ustCubuk()
        kok.addView(cubuk, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        tutamac = TextView(this).apply {
            text = "☰"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = yuvarlak(Color.argb(120, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { cubuguGoster() }
        }
        kok.addView(tutamac, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })
        kok.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> ceviriYerlestir() }
        setContentView(kok)

        webAyarla()
        hat = AltyaziHatti(this, ayar, ::ceviriGeldi, ::durum).also { it.baslat() }
        web.loadUrl(ADRES)
        cubuguGoster()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun webAyarla() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
            // "; wv" ve "Version/4.0" WebView'ı ele verir; Chrome mobil gibi görün
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        }
        web.addJavascriptInterface(Kopru(), "AltyaziKopru")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Yalnızca Xbox sayfalarına enjekte et; Microsoft giriş sayfalarına asla
                val host = Uri.parse(url).host ?: return
                if (host == "xbox.com" || host.endsWith(".xbox.com")) enjekteEt()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // Mikrofon/kamera isteklerini reddet (gerek yok)
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
    }

    private fun enjekteEt() {
        val js = assets.open("kare.js").bufferedReader().use { it.readText() }
        web.evaluateJavascript(js, null)
    }

    /** JS → Kotlin köprüsü. Metotlar WebView'ın arka plan iş parçacığında çalışır. */
    private inner class Kopru {
        @JavascriptInterface fun aralik(): Int = ayar.aralikMs.toInt()

        @JavascriptInterface fun hazir(): Boolean = hat?.hazir == true

        @JavascriptInterface fun video(sol: Float, ust: Float, sag: Float, alt: Float) {
            videoAlani = Oran(sol, ust, sag, alt)
        }

        @JavascriptInterface fun kare(base64Jpeg: String) {
            val h = hat ?: return
            val bayt = try { Base64.decode(base64Jpeg, Base64.DEFAULT) } catch (_: Exception) { return }
            val bmp = BitmapFactory.decodeByteArray(bayt, 0, bayt.size) ?: return
            h.isle(bmp)
        }

        @JavascriptInterface fun yuklendi() {
            ana.post { jsAyarGonder(); durum("Bağlandı. Konsolunu seçip uzaktan oynatmayı başlat.") }
        }
    }

    private fun jsAyarGonder() {
        val b = ayar.webBolge
        web.evaluateJavascript(
            "window.__altyazi && (__altyazi.bolge(${b.sol},${b.ust},${b.sag},${b.alt}), __altyazi.ses(${ayar.webSes}))",
            null
        )
    }

    // ---------- Üst çubuk ----------

    private fun ustCubuk(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = yuvarlak(Color.argb(170, 0, 0, 0))
        }
        c.addView(dugme("Bölge") { bolgeSec() })
        sesDugme = dugme(sesYazisi()) {
            ayar.webSes = !ayar.webSes
            sesDugme.text = sesYazisi()
            jsAyarGonder()
        }
        c.addView(sesDugme)
        c.addView(dugme("Durdur") { finish() })
        durumYazi = TextView(this).apply {
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 12f
            maxWidth = dp(260)
            setPadding(dp(8), 0, dp(4), 0)
        }
        c.addView(durumYazi)
        return c
    }

    private fun sesYazisi() = if (ayar.webSes) "Ses telefondan" else "Ses kapalı"

    private fun dugme(yazi: String, tik: () -> Unit) = TextView(this).apply {
        text = yazi
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { tik(); cubuguGoster() }
    }

    private fun cubuguGoster() {
        cubuk.visibility = View.VISIBLE
        tutamac.visibility = View.GONE
        ana.removeCallbacks(cubuguGizle)
        ana.postDelayed(cubuguGizle, CUBUK_SURE_MS)
    }

    private fun bolgeSec() {
        ana.removeCallbacks(cubuguGizle)
        cubuk.visibility = View.GONE
        val w = kok.width.toFloat().coerceAtLeast(1f); val h = kok.height.toFloat().coerceAtLeast(1f)
        val v = videoAlani.takeIf { it.sag > it.sol } ?: Oran(0f, 0f, w, h)
        val ekran = MetinIsleme.videodanEkrana(ayar.webBolge, v)
        val baslangic = RectF(ekran.sol / w, ekran.ust / h, ekran.sag / w, ekran.alt / h)
        BolgeSecici(
            this, windowManager, baslangic, "İngilizce altyazının çıktığı alanı çiz",
            WindowManager.LayoutParams.TYPE_APPLICATION
        ) { yeni ->
            if (yeni != null) {
                val secim = Oran(yeni.left * w, yeni.top * h, yeni.right * w, yeni.bottom * h)
                ayar.webBolge = MetinIsleme.ekrandanVideoya(secim, v)
                hat?.sifirla()
                jsAyarGonder()
                durum("Bölge kaydedildi.")
            }
            cubuguGoster()
        }.goster()
    }

    // ---------- Telefonda çeviri ----------

    private fun ceviriYazisi() = TextView(this).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        visibility = View.GONE
        textSize = ayar.yaziBoyutu.toFloat()
        setTextColor(ayar.yaziRengi)
        typeface = YaziTipleri.getir(this@UzaktanOynatActivity, ayar.yaziTipi)
        val parlak = Color.luminance(ayar.yaziRengi) > 0.5f
        setShadowLayer(4f, 0f, 0f, if (parlak) Color.BLACK else Color.WHITE)
        background = yuvarlak(ayar.arkaArgb())
    }

    private fun ceviriYerlestir() {
        val k = ayar.kutu
        val w = kok.width; val h = kok.height
        if (w == 0 || h == 0) return
        val lp = ceviri.layoutParams as FrameLayout.LayoutParams
        val yeniSol = (k.left * w).toInt(); val yeniUst = (k.top * h).toInt()
        val yeniGen = ((k.right - k.left) * w).toInt().coerceAtLeast(dp(80))
        if (lp.leftMargin == yeniSol && lp.topMargin == yeniUst && lp.width == yeniGen) return
        lp.leftMargin = yeniSol; lp.topMargin = yeniUst; lp.width = yeniGen
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        ceviri.post { ceviri.layoutParams = lp }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun ceviriGeldi(tr: String, orijinal: String) {
        if (!ayar.telefondaGoster || tr.isBlank()) { ceviri.visibility = View.GONE; return }
        ceviri.text = tr
        ceviri.visibility = View.VISIBLE
    }

    private fun durum(s: String) = ana.post { durumYazi.text = s }

    // ---------- Yaşam döngüsü ----------

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.evaluateJavascript("window.__altyazi && __altyazi.devam()", null)
    }

    override fun onPause() {
        web.evaluateJavascript("window.__altyazi && __altyazi.dur()", null)
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        ana.removeCallbacksAndMessages(null)
        hat?.kapat(); hat = null
        web.removeJavascriptInterface("AltyaziKopru")
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun yuvarlak(renk: Int) = GradientDrawable().apply {
        setColor(renk); cornerRadius = dp(14).toFloat()
    }
}
