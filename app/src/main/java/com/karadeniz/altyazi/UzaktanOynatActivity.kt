package com.karadeniz.altyazi

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapFactory.Options
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Uygulama içi Remote Play: play.xbox.com telefonda küçük bir pencerede açılır, Microsoft girişi
 * Microsoft'un kendi sayfasında yapılır (uygulama şifreyi görmez, yalnızca çerezler saklanır).
 * Altyazı bölgesi video karesinden JS ile kırpılıp ortak AltyaziHatti'na verilir.
 * Pencerenin altında Türkçe çeviri, durum ve düğmeler durur.
 */
class UzaktanOynatActivity : AppCompatActivity() {

    companion object {
        private const val ADRES = "https://www.xbox.com/tr-TR/play/consoles"
        /** Köprüyü yalnızca Xbox kökenleri kullanabilir (Microsoft giriş sayfaları ve yabancı iframe'ler değil). */
        private val IZINLI_KOKEN = setOf("https://xbox.com", "https://*.xbox.com")
        /** Kırpık kare için üst sınırlar (bozuk/kötü niyetli veriyle bellek taşmasın). */
        private const val EN_COK_BASE64 = 3_000_000
        private const val EN_COK_KENAR = 4096

        private val ARKA = Color.rgb(11, 14, 20)
        private val KART = Color.rgb(22, 27, 36)
        private val CERCEVE = Color.rgb(44, 52, 66)
        private val YAZI = Color.rgb(236, 239, 244)
        private val SOLUK = Color.rgb(140, 150, 166)
        private val VURGU = Color.rgb(34, 211, 238)
        private val KIRMIZI = Color.rgb(248, 113, 113)
        private val YESIL = Color.rgb(52, 211, 153)
        private val SARI = Color.rgb(250, 204, 21)
    }

    private lateinit var ayar: Ayarlar
    private lateinit var web: WebView
    private lateinit var webKap: FrameLayout
    private lateinit var kart: LinearLayout
    /** Oyun akışı açıkken pencere tam 16:9 olur (siyah boşluk kalmaz); giriş/konsol sayfalarında uzun kalır. */
    private var oyunda = false
    private lateinit var kokGorunum: LinearLayout
    /** Bölge seçerken oyun penceresi yatay tam ekran olur. */
    private var tamEkranda = false
    private lateinit var durumNokta: View
    private lateinit var durumYazi: TextView
    private lateinit var ceviri: TextView
    private lateinit var orijinal: TextView
    private lateinit var sesTelefon: TextView
    private lateinit var sesKapali: TextView
    private val ana = Handler(Looper.getMainLooper())
    private var hat: AltyaziHatti? = null
    /** Yerinde çeviri hatları (Telefon, Bildirim). */
    private val yerinde = mutableMapOf<Alan, YerindeHatti>()
    private val alanDugmeleri = mutableMapOf<Alan, TextView>()
    private lateinit var digerAlanlar: TextView
    private val alanCevirileri = linkedMapOf<Alan, List<String>>()
    private var webKapandi = false
    private var karartma: View? = null

    /** Videonun WebView içindeki içerik alanı (fiziksel piksel), JS bildirir. */
    @Volatile private var videoAlani = Oran(0f, 0f, 0f, 0f)

    // ---- Kontrol et (tanı) bilgileri ----
    private var kopruVar = false
    private var kopruKontrol = ""
    private var jsDurum = JSONObject()
    private var jsDurumZamani = 0L
    private var kareSayisi = 0
    private var islenenKare = 0
    private var sonKareZamani = 0L
    private val sonKareB64 = mutableMapOf<Alan, String>()
    private var sonHata = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ayar = Ayarlar(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = ARKA
        window.navigationBarColor = ARKA

        web = WebView(this)
        setContentView(arayuz())

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Toast.makeText(this, "Android System WebView güncel değil. Play Store'dan güncelle.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        webAyarla()
        hat = AltyaziHatti(this, ayar, ::ceviriGeldi, ::durum).also { it.baslat() }
        listOf(Alan.TELEFON, Alan.BILDIRIM).forEach { a ->
            yerinde[a] = YerindeHatti(this, ayar, a, ::yerindeGeldi).also { it.baslat() }
        }
        durum("Microsoft'un sayfası açılıyor…")
        web.loadUrl(ADRES)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    karartma != null -> karartmayiKaldir()
                    tamEkranda -> tamEkrandanCik()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
    }

    // ---------- Arayüz ----------

    private fun arayuz(): View {
        val kok = LinearLayout(this).also { kokGorunum = it }.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ARKA)
            fitsSystemWindows = true
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }

        // Başlık: ad + sürüm + kapat
        val baslik = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        baslik.addView(yazi("Altyazı", 22f, YAZI, kalin = true))
        baslik.addView(yazi("v${BuildConfig.VERSION_NAME}", 12f, SOLUK).apply {
            background = yuvarlak(KART, 10)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        baslik.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        baslik.addView(yazi("✕", 18f, SOLUK).apply {
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { finish() }
        })
        kok.addView(baslik)

        // Küçük oyun penceresi (Remote Play sayfası)
        webKap = FrameLayout(this).apply {
            background = yuvarlak(Color.BLACK, 18, CERCEVE)
            clipToOutline = true
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        webKap.addView(web, FrameLayout.LayoutParams(-1, -1))
        kok.addView(webKap, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })

        // Türkçe çeviri kartı
        kart = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = yuvarlak(KART, 22, CERCEVE)
            setPadding(dp(18), dp(12), dp(18), dp(14))
        }
        kart.addView(yazi("TÜRKÇESİ", 12f, SOLUK, kalin = true).apply { letterSpacing = 0.12f })
        ceviri = yazi("Altyazı bekleniyor…", 20f, SOLUK).apply {
            typeface = YaziTipleri.getir(this@UzaktanOynatActivity, ayar.yaziTipi)
            minLines = 2
            maxLines = 4
            setPadding(0, dp(6), 0, 0)
        }
        kart.addView(ceviri)
        orijinal = yazi("", 12f, SOLUK).apply { maxLines = 2; visibility = View.GONE; setPadding(0, dp(4), 0, 0) }
        kart.addView(orijinal)
        digerAlanlar = yazi("", 14f, YAZI).apply { maxLines = 6; visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        kart.addView(digerAlanlar)
        kok.addView(kart, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // Durum satırı
        val durumSatiri = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(6))
        }
        durumNokta = View(this).apply { background = yuvarlak(SARI, 6) }
        durumSatiri.addView(durumNokta, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        durumYazi = yazi("", 13f, SOLUK).apply { maxLines = 2 }
        durumSatiri.addView(durumYazi, LinearLayout.LayoutParams(0, -2, 1f))
        kok.addView(durumSatiri)

        // Ses seçimi
        val ses = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sesTelefon = hap("Ses telefondan") { sesSec(true) }
        sesKapali = hap("Ses kapalı") { sesSec(false) }
        ses.addView(sesTelefon, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        ses.addView(sesKapali, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        ses.addView(dugme("☾ Karart", YAZI) { karart() }, LinearLayout.LayoutParams(0, dp(44), 0.8f))
        kok.addView(ses)
        sesBoya()

        // Alanlar: dokununca aç/kapat
        val alanlar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Alan.entries.forEachIndexed { i, a ->
            val d = hap("") { alanDegistir(a) }
            alanDugmeleri[a] = d
            alanlar.addView(d, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i < 2) marginEnd = dp(8) })
        }
        kok.addView(alanlar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        alanlariBoya()

        // Düğmeler
        val dugmeler = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun ekle(v: View, son: Boolean = false) = dugmeler.addView(
            v, LinearLayout.LayoutParams(0, dp(52), 1f).apply { if (!son) marginEnd = dp(8) }
        )
        ekle(dugme("Bölge", YAZI) { alanSecVeBolge() })
        ekle(dugme("Görünüm", YAZI) { gorunumPaneli() })
        ekle(dugme("Kontrol et", YAZI) { kontrolEt() })
        ekle(dugme("■ Durdur", KIRMIZI) { finish() }, son = true)
        kok.addView(dugmeler, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        kok.addView(yazi("© Mehmet Karadeniz, 2026-2027", 11f, SOLUK).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        return kok
    }

    private fun yazi(s: String, sp: Float, renk: Int, kalin: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(renk)
        if (kalin) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dugme(s: String, renk: Int, tik: () -> Unit) = yazi(s, 15f, renk, kalin = true).apply {
        gravity = Gravity.CENTER
        background = yuvarlak(KART, 18, CERCEVE)
        setOnClickListener { tik() }
    }

    private fun hap(s: String, tik: () -> Unit) = yazi(s, 14f, YAZI, kalin = true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { tik() }
    }

    private fun sesSec(acik: Boolean) {
        ayar.webSes = acik
        sesBoya()
        jsAyarGonder()
    }

    private fun sesBoya() {
        val acik = ayar.webSes
        sesTelefon.background = yuvarlak(if (acik) VURGU else KART, 22, if (acik) null else CERCEVE)
        sesTelefon.setTextColor(if (acik) ARKA else YAZI)
        sesKapali.background = yuvarlak(if (!acik) VURGU else KART, 22, if (!acik) null else CERCEVE)
        sesKapali.setTextColor(if (!acik) ARKA else YAZI)
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun webAyarla() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            // "; wv" ve "Version/4.0" WebView'ı ele verir; Chrome mobil gibi görün
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        }
        WebViewCompat.addWebMessageListener(web, "AltyaziKopru", IZINLI_KOKEN) { _, mesaj, _, anaCerceve, _ ->
            if (anaCerceve) mesajGeldi(mesaj.data ?: return@addWebMessageListener)
        }
        web.webViewClient = object : WebViewClient() {
            // Görüntü işlemi çökerse uygulamayı da çökertme
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                hat?.kapat(); hat = null
                yerinde.values.forEach { it.kapat() }; yerinde.clear()
                webKapandi = true
                webKap.removeView(view)
                view.destroy()
                Toast.makeText(this@UzaktanOynatActivity, "Oyun görüntüsü kapandı. Yeniden başlat.", Toast.LENGTH_LONG).show()
                finish()
                return true
            }

            // Ana sayfa yüklenemezse sebebi durum satırında görünsün
            override fun onReceivedError(view: WebView, istek: WebResourceRequest, hata: WebResourceError) {
                if (istek.isForMainFrame) durum("Sayfa açılamadı: ${hata.description} (${istek.url.host})", hata = true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Yalnızca Xbox sayfalarına enjekte et; Microsoft giriş sayfalarına asla
                val host = Uri.parse(url).host ?: return
                if (host == "xbox.com" || host.endsWith(".xbox.com")) enjekteEt(host)
                else durum("Giriş sayfası: $host")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // Mikrofon/kamera isteklerini reddet (gerek yok)
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
    }

    private fun enjekteEt(host: String) {
        val js = assets.open("kare.js").bufferedReader().use { it.readText() }
        web.evaluateJavascript(js, null)
        // Köprü bu sayfada var mı? (Yoksa kare gelmez; Kontrol et'te görünür)
        web.evaluateJavascript("typeof window.AltyaziKopru") { tip ->
            kopruKontrol = "$host → ${tip.trim('"')}"
            if (tip.contains("undefined")) {
                kopruVar = false
                durum("Köprü kurulamadı ($host). Kontrol et'e bas.", hata = true)
            }
        }
    }

    /** JS → Kotlin mesajları (ana iş parçacığı; yalnızca xbox.com ana çerçevesinden). */
    private fun mesajGeldi(veri: String) {
        val m = try { JSONObject(veri) } catch (_: Exception) { return }
        when (m.optString("t")) {
            "kare" -> kareGeldi(Alan.bul(m.optString("a", "altyazi")) ?: Alan.ALTYAZI, m.optString("d"))
            "video" -> {
                val a = m.optJSONArray("a") ?: return
                if (a.length() == 4) videoAlani = Oran(
                    a.optDouble(0).toFloat(), a.optDouble(1).toFloat(),
                    a.optDouble(2).toFloat(), a.optDouble(3).toFloat()
                )
            }
            "durum" -> {
                jsDurum = m; jsDurumZamani = SystemClock.elapsedRealtime()
                pencereyiAyarla(m.optInt("w", 0) > 0 && m.optInt("h", 0) > 0)
            }
            "temizle" -> { hat?.temizle(); yerinde.values.forEach { it.temizle() } }
            "hata" -> { sonHata = m.optString("m"); durum("Görüntü okunamıyor: $sonHata", hata = true) }
            "yuklendi" -> {
                kopruVar = true
                jsAyarGonder()
                durum("Bağlandı. Konsolunu seçip uzaktan oynatmayı başlat.")
            }
        }
    }

    /** Oyunda: pencere video oranında (16:9), boş kalan yeri çeviri kartı alır. Değilse pencere uzun. */
    /** WebView'ın sayfaya göre büyüklüğü / ekrandaki büyüklüğü (2 = sayfa iki kat geniş görür). */
    @Volatile private var webKat = 1

    private fun webOlcekle(kat: Int, genislik: Int = 0, yukseklik: Int = 0) {
        webKat = kat
        web.pivotX = 0f; web.pivotY = 0f
        if (kat == 1) {
            web.layoutParams = FrameLayout.LayoutParams(-1, -1)
            web.scaleX = 1f; web.scaleY = 1f
        } else {
            web.layoutParams = FrameLayout.LayoutParams(genislik * kat, yukseklik * kat)
            web.scaleX = 1f / kat; web.scaleY = 1f / kat
        }
    }

    private fun pencereyiAyarla(yeni: Boolean, zorla: Boolean = false) {
        if (tamEkranda) return
        if (yeni == oyunda && !zorla) return
        oyunda = yeni
        val vw = jsDurum.optInt("w", 16).coerceAtLeast(1); val vh = jsDurum.optInt("h", 9).coerceAtLeast(1)
        val wlp = webKap.layoutParams as LinearLayout.LayoutParams
        val klp = kart.layoutParams as LinearLayout.LayoutParams
        if (oyunda) {
            val genislik = resources.displayMetrics.widthPixels - dp(28)
            val yukseklik = genislik.coerceAtLeast(1) * vh / vw
            wlp.height = yukseklik + dp(2); wlp.weight = 0f
            // xCloud küçük pencerede "Burası biraz dar" der: sayfayı 2 kat büyük çizip yarıya küçült
            webOlcekle(2, genislik, yukseklik)
            klp.height = 0; klp.weight = 1f
            ceviri.textSize = 24f; ceviri.maxLines = 6
        } else {
            wlp.height = 0; wlp.weight = 1f
            webOlcekle(1)
            klp.height = LinearLayout.LayoutParams.WRAP_CONTENT; klp.weight = 0f
            ceviri.textSize = 20f; ceviri.maxLines = 4
        }
        webKap.layoutParams = wlp
        kart.layoutParams = klp
    }

    private fun kareGeldi(alan: Alan, base64Jpeg: String) {
        kareSayisi++
        sonKareZamani = SystemClock.elapsedRealtime()
        if (base64Jpeg.isEmpty() || base64Jpeg.length > EN_COK_BASE64) return
        sonKareB64[alan] = base64Jpeg
        sonHata = ""   // kare geliyorsa önceki geçici hata artık geçerli değil
        val hazir = if (alan == Alan.ALTYAZI) hat?.hazir == true else yerinde[alan]?.hazir == true
        if (!hazir) return
        val bayt = try { Base64.decode(base64Jpeg, Base64.DEFAULT) } catch (_: Exception) { return }
        val sinir = Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bayt, 0, bayt.size, sinir)
        if (sinir.outWidth !in 1..EN_COK_KENAR || sinir.outHeight !in 1..EN_COK_KENAR) return
        val bmp = BitmapFactory.decodeByteArray(bayt, 0, bayt.size) ?: return
        islenenKare++
        if (alan == Alan.ALTYAZI) hat?.isle(bmp) ?: bmp.recycle()
        else yerinde[alan]?.isle(bmp, ayar.alanBolge(alan)) ?: bmp.recycle()
    }

    private fun jsAyarGonder() {
        if (webKapandi) return
        val js = StringBuilder("window.__altyazi && (")
        Alan.entries.forEach { a ->
            val b = ayar.alanBolge(a)
            js.append("__altyazi.bolge('${a.id}',${b.sol},${b.ust},${b.sag},${b.alt}), ")
            js.append("__altyazi.aktif('${a.id}',${ayar.alanAktif(a)}), ")
        }
        js.append("__altyazi.ses(${ayar.webSes}), __altyazi.aralik(${ayar.aralikMs}))")
        web.evaluateJavascript(js.toString(), null)
    }

    // ---------- Bölge ----------

    /**
     * Bölge seçici tüm ekranı kaplar (oranlar ekranın tamamına göre).
     * Videonun WebView içindeki alanını ekran koordinatına çevirip oradan dönüştürüyoruz.
     */
    /** Hangi alanın bölgesi? Sonra o alan için kutu çizme ekranı. */
    private fun alanSecVeBolge() {
        val adlar = Alan.entries.map { it.ad + if (ayar.alanAktif(it)) "" else " (kapalı)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Hangi alanın yerini seçeceksin?")
            .setItems(adlar) { _, i -> bolgeSec(Alan.entries[i]) }
            .show()
    }

    /** Seçim kolay olsun: oyun penceresi yatay tam ekran; seçim bitince eski hale dön. */
    private fun bolgeSec(alan: Alan = Alan.ALTYAZI) {
        tamEkranaGec()
        // Döndürme ve video alanı bildirimi otursun
        ana.postDelayed({ bolgeSecici(alan) }, 1000)
    }

    private fun tamEkranaGec() {
        if (tamEkranda) return
        tamEkranda = true
        for (i in 0 until kokGorunum.childCount) {
            val v = kokGorunum.getChildAt(i)
            if (v !== webKap) v.visibility = View.GONE
        }
        kokGorunum.setPadding(0, 0, 0, 0)
        webOlcekle(1)
        webKap.layoutParams = (webKap.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f; topMargin = 0 }
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun tamEkrandanCik() {
        if (!tamEkranda) return
        tamEkranda = false
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        for (i in 0 until kokGorunum.childCount) {
            val v = kokGorunum.getChildAt(i)
            if (v !== webKap && v !== digerAlanlar) v.visibility = View.VISIBLE
        }
        kokGorunum.setPadding(dp(14), dp(10), dp(14), dp(12))
        webKap.layoutParams = (webKap.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(10) }
        // Pencere boyu dikeye göre yeniden hesaplansın
    }

    /** Yönelim değişti (bölge seçimi bitti): dikey düzende pencere boyunu yeniden hesapla. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!tamEkranda && newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
            kokGorunum.post { pencereyiAyarla(oyunda, zorla = true) }
    }

    private fun bolgeSecici(alan: Alan) {
        val ekran = windowManager.maximumWindowMetrics.bounds
        val ew = ekran.width().toFloat(); val eh = ekran.height().toFloat()
        val konum = IntArray(2).also { web.getLocationOnScreen(it) }
        val wx = konum[0].toFloat(); val wy = konum[1].toFloat()
        val v = videoAlani.takeIf { it.sag > it.sol }
            ?: Oran(0f, 0f, web.width.toFloat(), web.height.toFloat())
        val k = 1f / webKat   // WebView küçültülmüşse sayfa pikselleri ekranda k kadar
        val videoEkranda = Oran(v.sol * k + wx, v.ust * k + wy, v.sag * k + wx, v.alt * k + wy)
        val baslangicPx = MetinIsleme.videodanEkrana(ayar.alanBolge(alan), videoEkranda)
        val baslangic = RectF(baslangicPx.sol / ew, baslangicPx.ust / eh, baslangicPx.sag / ew, baslangicPx.alt / eh)
        BolgeSecici(
            this, windowManager, baslangic, when (alan) {
                Alan.ALTYAZI -> "İngilizce altyazının çıktığı alanı çiz"
                Alan.TELEFON -> "Oyundaki telefonun ekranını çiz"
                Alan.BILDIRIM -> "Bildirimlerin çıktığı köşeyi çiz"
            },
            WindowManager.LayoutParams.TYPE_APPLICATION
        ) { yeni ->
            if (yeni != null) {
                val secim = Oran(yeni.left * ew, yeni.top * eh, yeni.right * ew, yeni.bottom * eh)
                ayar.alanBolgeYaz(alan, MetinIsleme.ekrandanVideoya(secim, videoEkranda))
                if (alan == Alan.ALTYAZI) hat?.sifirla() else yerinde[alan]?.sifirla()
                if (!ayar.alanAktif(alan)) { ayar.alanAktifYaz(alan, true); alanlariBoya() }
                jsAyarGonder()
                durum("Bölge kaydedildi: ${alan.ad}.")
            }
            tamEkrandanCik()
        }.goster()
    }

    // ---------- Alanlar ----------

    private fun alanDegistir(a: Alan) {
        val acik = !ayar.alanAktif(a)
        ayar.alanAktifYaz(a, acik)
        alanlariBoya()
        jsAyarGonder()
        if (!acik) {
            if (a == Alan.ALTYAZI) hat?.temizle() else yerinde[a]?.temizle()
            yerindeGeldi(a, emptyList())
        }
        durum("${a.ad} " + if (acik) "açık." else "kapalı.")
    }

    private fun alanlariBoya() {
        alanDugmeleri.forEach { (a, d) ->
            val acik = ayar.alanAktif(a)
            d.text = (if (acik) "✓ " else "") + a.ad
            d.background = yuvarlak(if (acik) VURGU else KART, 20, if (acik) null else CERCEVE)
            d.setTextColor(if (acik) ARKA else SOLUK)
        }
    }

    /** Telefon/Bildirim çevirileri kartta etiketli satırlar olarak. */
    private fun yerindeGeldi(a: Alan, ceviriler: List<String>) {
        if (ceviriler.isEmpty()) alanCevirileri.remove(a) else alanCevirileri[a] = ceviriler
        val metin = alanCevirileri.entries.joinToString("\n") { (k, v) -> "${k.ad}: " + v.joinToString(" · ") }
        digerAlanlar.text = metin
        digerAlanlar.visibility = if (metin.isBlank()) View.GONE else View.VISIBLE
    }

    // ---------- Görünüm (çeviri sürerken, TV'de canlı) ----------

    private var stilGonderBekliyor = false

    /** TV'ye stil gönderimini 150 ms'de bire indir (kaydırıcı sürüklenirken ağı boğmasın). */
    private fun stiliGonder() {
        ceviri.typeface = YaziTipleri.getir(this, ayar.yaziTipi)
        if (stilGonderBekliyor) return
        stilGonderBekliyor = true
        ana.postDelayed({
            stilGonderBekliyor = false
            hat?.stiliTvyeGonder()
            yerinde.values.forEach { it.yenidenGonder() }
        }, 150)
    }

    /** TV'deki altyazının yeri, boyutu, rengi, arka planı ve yazı tipi. Değişiklik TV'de hemen görünür. */
    private fun gorunumPaneli() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KART)
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        fun etiket(s: String) = yazi(s, 13f, SOLUK, kalin = true).apply { setPadding(0, dp(14), 0, dp(4)) }
        fun kaydirici(min: Int, max: Int, deger: Int, degisti: (Int) -> Unit) = SeekBar(this).apply {
            this.max = max - min
            progress = (deger - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, kullanici: Boolean) { if (kullanici) degisti(p + min) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { hat?.stiliTvyeGonder() }
            })
        }

        panel.addView(yazi("TV'deki altyazı", 18f, YAZI, kalin = true))
        panel.addView(yazi("Değişiklikler TV'de hemen görünür.", 13f, SOLUK))

        // Konum: kutunun üst kenarı (yükseklik korunur)
        panel.addView(etiket("Konum (yukarı ↔ aşağı)"))
        val k0 = ayar.kutu
        val yukseklik = (k0.bottom - k0.top).coerceIn(0.08f, 0.5f)
        panel.addView(kaydirici(0, 100, ((k0.top / (1f - yukseklik)) * 100).toInt()) { p ->
            val ust = (1f - yukseklik) * p / 100f
            ayar.kutu = RectF(k0.left, ust, k0.right, ust + yukseklik)
            stiliGonder()
        })

        panel.addView(etiket("Yazı boyutu"))
        panel.addView(kaydirici(12, 48, ayar.yaziBoyutu) { ayar.yaziBoyutu = it; stiliGonder() })

        panel.addView(etiket("Arka plan koyuluğu"))
        panel.addView(kaydirici(0, 100, ayar.arkaOpaklik) { ayar.arkaOpaklik = it; stiliGonder() })

        panel.addView(etiket("Yazı rengi"))
        val renkler = intArrayOf(Color.WHITE, Color.rgb(255, 230, 0), VURGU, YESIL, Color.rgb(255, 160, 200))
        val renkSatiri = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun renkleriCiz() {
            for (i in 0 until renkSatiri.childCount) {
                val r = renkler[i]
                renkSatiri.getChildAt(i).background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(r)
                    if (r == ayar.yaziRengi) setStroke(dp(3), VURGU) else setStroke(dp(1), CERCEVE)
                }
            }
        }
        renkler.forEach { r ->
            renkSatiri.addView(View(this).apply {
                setOnClickListener { ayar.yaziRengi = r; renkleriCiz(); stiliGonder() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        }
        renkleriCiz()
        panel.addView(renkSatiri)

        panel.addView(etiket("TV hizalaması (telefon/bildirim çevirileri orijinalin üstüne oturmuyorsa)"))
        panel.addView(yazi("Yatay kaydır", 12f, SOLUK))
        panel.addView(kaydirici(-50, 50, (ayar.hizaX * 1000).toInt()) { ayar.hizaX = it / 1000f; stiliGonder() })
        panel.addView(yazi("Dikey kaydır", 12f, SOLUK))
        panel.addView(kaydirici(-50, 50, (ayar.hizaY * 1000).toInt()) { ayar.hizaY = it / 1000f; stiliGonder() })
        panel.addView(yazi("Ölçek", 12f, SOLUK))
        panel.addView(kaydirici(90, 110, (ayar.hizaOlcek * 100).toInt()) { ayar.hizaOlcek = it / 100f; stiliGonder() })

        listOf(Alan.TELEFON, Alan.BILDIRIM).forEach { a ->
            val baslik = etiket("")
            fun yaz(n: Int) { baslik.text = "${a.ad}: en az $n kelimelik yazılar çevrilir" }
            yaz(ayar.minKelime(a))
            panel.addView(baslik)
            panel.addView(kaydirici(1, 6, ayar.minKelime(a)) { n -> ayar.minKelimeYaz(a, n); yaz(n) })
            val boyutBaslik = yazi("", 12f, SOLUK)
            fun boyutYaz(n: Int) { boyutBaslik.text = "${a.ad} çeviri boyutu: %$n (orijinale göre)" }
            boyutYaz(ayar.alanBoyut(a))
            panel.addView(boyutBaslik)
            panel.addView(kaydirici(80, 250, ayar.alanBoyut(a)) { n -> ayar.alanBoyutYaz(a, n); boyutYaz(n); stiliGonder() })
        }

        panel.addView(etiket("Telefon alanı"))
        val algilaDugme = hap("") {}
        fun algilaCiz() {
            algilaDugme.text = if (ayar.telefonAlgila) "Yalnızca oyundaki telefon açıkken çevir" else "Bölgedeki her yazıyı çevir"
            algilaDugme.background = yuvarlak(if (ayar.telefonAlgila) VURGU else ARKA, 18, if (ayar.telefonAlgila) null else CERCEVE)
            algilaDugme.setTextColor(if (ayar.telefonAlgila) ARKA else YAZI)
        }
        algilaDugme.setOnClickListener { ayar.telefonAlgila = !ayar.telefonAlgila; algilaCiz() }
        algilaCiz()
        panel.addView(algilaDugme, LinearLayout.LayoutParams(-1, dp(44)))

        panel.addView(etiket("Küfür sansürü"))
        val sansurDugme = hap("") {}
        fun sansurCiz() {
            sansurDugme.text = if (ayar.sansur) "Açık: küfürler yıldızlanır (s*****)" else "Kapalı: çeviri olduğu gibi"
            sansurDugme.background = yuvarlak(if (ayar.sansur) VURGU else ARKA, 18, if (ayar.sansur) null else CERCEVE)
            sansurDugme.setTextColor(if (ayar.sansur) ARKA else YAZI)
        }
        sansurDugme.setOnClickListener { ayar.sansur = !ayar.sansur; sansurCiz() }
        sansurCiz()
        panel.addView(sansurDugme, LinearLayout.LayoutParams(-1, dp(44)))

        panel.addView(etiket("Yazı tipi"))
        val tipler = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tipleriCiz() {
            for (i in 0 until tipler.childCount) {
                val t = tipler.getChildAt(i) as TextView
                val secili = YaziTipleri.liste[i].first == ayar.yaziTipi
                t.background = yuvarlak(if (secili) VURGU else ARKA, 18, if (secili) null else CERCEVE)
                t.setTextColor(if (secili) ARKA else YAZI)
            }
        }
        YaziTipleri.liste.forEach { (id, ad) ->
            tipler.addView(TextView(this).apply {
                text = ad
                textSize = 14f
                typeface = YaziTipleri.getir(this@UzaktanOynatActivity, id)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { ayar.yaziTipi = id; tipleriCiz(); stiliGonder() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        tipleriCiz()
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tipler) })

        BottomSheetDialog(this).apply {
            setContentView(ScrollView(this@UzaktanOynatActivity).apply { addView(panel) })
            show()
        }
        hat?.stiliTvyeGonder()   // açılınca TV'de örnek/şimdiki altyazıyı göster
    }

    // ---------- Karart ----------

    /** Ekranı karartır (pil ve göz için); oyun ve çeviri arkada sürer. Dokununca açılır. */
    private fun karart() {
        if (karartma != null) return
        val v = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            text = "Açmak için dokun"
            setTextColor(Color.rgb(40, 40, 40))
            gravity = Gravity.CENTER
            setOnClickListener { karartmayiKaldir() }
        }
        (window.decorView as ViewGroup).addView(v, ViewGroup.LayoutParams(-1, -1))
        karartma = v
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
    }

    private fun karartmayiKaldir() {
        karartma?.let { (window.decorView as ViewGroup).removeView(it) }
        karartma = null
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    // ---------- Kontrol et ----------

    /** Hattın her halkasını gösterir: sayfa, köprü, video, kare, okuma, çeviri, TV. */
    private fun kontrolEt() {
        val simdi = SystemClock.elapsedRealtime()
        val h = hat
        fun satir(ok: Boolean?, s: String) = (when (ok) { true -> "✓ "; false -> "✗ "; null -> "• " }) + s

        val sayfa = Uri.parse(web.url ?: "").host ?: "-"
        val jsTaze = jsDurumZamani > 0 && simdi - jsDurumZamani < 5000
        val videoSayisi = jsDurum.optInt("n", 0)
        val vw = jsDurum.optInt("w", 0); val vh = jsDurum.optInt("h", 0)
        val durakli = jsDurum.optBoolean("p", false)
        val kareTaze = sonKareZamani > 0 && simdi - sonKareZamani < 3000

        val satirlar = mutableListOf(
            satir(sayfa.endsWith("xbox.com"), "Sayfa: $sayfa"),
            satir(kopruVar, "Uygulama–sayfa bağlantısı: " + if (kopruVar) "var" else "yok ($kopruKontrol)"),
            satir(if (jsTaze) videoSayisi > 0 else false,
                if (!jsTaze) "Video bilgisi gelmiyor"
                else if (videoSayisi == 0) "Sayfada oyun videosu yok (Remote Play başlamadı mı?)"
                else "Video: ${vw}×${vh}" + if (durakli) " (duraklatılmış)" else ""),
            satir(kareTaze, "Altyazı karesi: $kareSayisi alındı, $islenenKare okundu"),
            satir(h?.modelHazirMi == true, "Çeviri modeli: " + if (h?.modelHazirMi == true) "hazır" else "hazırlanıyor/yok"),
            satir(null, "Son okunan İngilizce: " + (h?.sonOkunan?.ifBlank { "(boş)" } ?: "-")),
            satir(null, "Son çeviri: " + (h?.sonTr?.ifBlank { "(yok)" } ?: "-")),
            *yerinde.map { (a, y) ->
                satir(null, "${a.ad} (" + (if (ayar.alanAktif(a)) "açık" else "kapalı") + "): " +
                    y.sonOkunan.ifBlank { "(yazı yok)" }.take(120))
            }.toTypedArray(),
        )
        if (sonHata.isNotBlank()) satirlar.add(satir(false, "Hata: $sonHata"))
        val tvSatiri = satir(null, "TV: " + ayar.tvAdresi.ifBlank { "adres girilmemiş" } + if (ayar.tvAdresi.isBlank()) "" else " (deneniyor…)")
        satirlar.add(tvSatiri)

        val metin = yazi(satirlar.joinToString("\n"), 14f, YAZI).apply { setLineSpacing(0f, 1.25f) }
        val icerik = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        icerik.addView(metin)
        Alan.entries.filter { ayar.alanAktif(it) }.forEach { a ->
            val kare = kareResmi(a)
            icerik.addView(yazi(if (kare != null) "${a.ad}: uygulamanın okuduğu bölge" else "${a.ad}: henüz kare yok", 13f, SOLUK).apply {
                setPadding(0, dp(12), 0, dp(6))
            })
            if (kare != null) icerik.addView(ImageView(this).apply {
                setImageBitmap(kare)
                adjustViewBounds = true
                maxHeight = dp(260)
                background = yuvarlak(Color.BLACK, 8)
            }, LinearLayout.LayoutParams(-1, -2))
        }

        AlertDialog.Builder(this)
            .setTitle("Kontrol")
            .setView(ScrollView(this).apply { addView(icerik) })
            .setPositiveButton("Tamam", null)
            .setNeutralButton("Bölge") { _, _ -> alanSecVeBolge() }
            .show()

        // TV bağlantısını gerçekten dene
        if (ayar.tvAdresi.isNotBlank()) {
            TvGonderici().apply {
                gonder(ayar.tvAdresi, "Altyazı bağlantı testi", "", ayar.stilJson(), 2000) { ok, ayrinti ->
                    ana.post {
                        metin.text = metin.text.toString().replace(tvSatiri,
                            satir(ok, "TV: ${ayar.tvAdresi} " + if (ok) "ulaşıldı" else "ulaşılamadı ($ayrinti)"))
                    }
                }
                kapat()
            }
        }
    }

    private fun kareResmi(a: Alan): Bitmap? {
        val b64 = sonKareB64[a] ?: return null
        return try {
            val b = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (_: Exception) { null }
    }

    // ---------- Çeviri ve durum ----------

    private fun ceviriGeldi(tr: String, org: String) {
        if (tr.isBlank()) {
            ceviri.text = "Altyazı bekleniyor…"
            ceviri.setTextColor(SOLUK)
            orijinal.visibility = View.GONE
            return
        }
        ceviri.text = tr
        ceviri.setTextColor(YAZI)
        orijinal.text = org
        orijinal.visibility = if (org.isBlank()) View.GONE else View.VISIBLE
    }

    private fun durum(s: String) = durum(s, false)

    private fun durum(s: String, hata: Boolean) {
        ana.post {
            durumYazi.text = s
            val renk = when {
                hata || s.contains("indirilemedi") || s.contains("hata", true) || s.contains("ulaşılamadı") || s.contains("izni yok") -> KIRMIZI
                s.startsWith("Hazır") || s.startsWith("Bağlandı") || s.startsWith("Bölge") || s.startsWith("Çeviri TV'ye") -> YESIL
                else -> SARI
            }
            durumNokta.background = yuvarlak(renk, 6)
        }
    }

    // ---------- Yaşam döngüsü ----------

    override fun onResume() {
        super.onResume()
        if (webKapandi) return
        web.onResume()
        web.evaluateJavascript("window.__altyazi && __altyazi.devam()", null)
    }

    override fun onPause() {
        if (!webKapandi) web.evaluateJavascript("window.__altyazi && __altyazi.dur()", null)
        hat?.temizle()
        yerinde.values.forEach { it.temizle() }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        ana.removeCallbacksAndMessages(null)
        hat?.kapat(); hat = null
        yerinde.values.forEach { it.kapat() }; yerinde.clear()
        if (!webKapandi) web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun yuvarlak(renk: Int, yaricapDp: Int, cerceve: Int? = null) = GradientDrawable().apply {
        setColor(renk)
        cornerRadius = dp(yaricapDp).toFloat()
        if (cerceve != null) setStroke(dp(1), cerceve)
    }
}
