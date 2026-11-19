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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
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
        private const val ADRES = "https://www.xbox.com/tr-TR/play"
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
    private lateinit var durumNokta: View
    private lateinit var durumYazi: TextView
    private lateinit var ceviri: TextView
    private lateinit var orijinal: TextView
    private lateinit var sesTelefon: TextView
    private lateinit var sesKapali: TextView
    private val ana = Handler(Looper.getMainLooper())
    private var hat: AltyaziHatti? = null
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
    private var sonKareB64 = ""
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
        durum("Microsoft'un sayfası açılıyor…")
        web.loadUrl(ADRES)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    karartma != null -> karartmayiKaldir()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
    }

    // ---------- Arayüz ----------

    private fun arayuz(): View {
        val kok = LinearLayout(this).apply {
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
        val kart = LinearLayout(this).apply {
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
        ses.addView(sesKapali, LinearLayout.LayoutParams(0, dp(44), 1f))
        kok.addView(ses)
        sesBoya()

        // Düğmeler
        val dugmeler = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun ekle(v: View, son: Boolean = false) = dugmeler.addView(
            v, LinearLayout.LayoutParams(0, dp(52), 1f).apply { if (!son) marginEnd = dp(8) }
        )
        ekle(dugme("☾ Karart", YAZI) { karart() })
        ekle(dugme("Bölge", YAZI) { bolgeSec() })
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
            "kare" -> kareGeldi(m.optString("d"))
            "video" -> {
                val a = m.optJSONArray("a") ?: return
                if (a.length() == 4) videoAlani = Oran(
                    a.optDouble(0).toFloat(), a.optDouble(1).toFloat(),
                    a.optDouble(2).toFloat(), a.optDouble(3).toFloat()
                )
            }
            "durum" -> { jsDurum = m; jsDurumZamani = SystemClock.elapsedRealtime() }
            "temizle" -> hat?.temizle()
            "hata" -> { sonHata = m.optString("m"); durum("Görüntü okunamıyor: $sonHata", hata = true) }
            "yuklendi" -> {
                kopruVar = true
                jsAyarGonder()
                durum("Bağlandı. Konsolunu seçip uzaktan oynatmayı başlat.")
            }
        }
    }

    private fun kareGeldi(base64Jpeg: String) {
        kareSayisi++
        sonKareZamani = SystemClock.elapsedRealtime()
        if (base64Jpeg.isEmpty() || base64Jpeg.length > EN_COK_BASE64) return
        sonKareB64 = base64Jpeg
        val h = hat ?: return
        if (!h.hazir) return
        val bayt = try { Base64.decode(base64Jpeg, Base64.DEFAULT) } catch (_: Exception) { return }
        val sinir = Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bayt, 0, bayt.size, sinir)
        if (sinir.outWidth !in 1..EN_COK_KENAR || sinir.outHeight !in 1..EN_COK_KENAR) return
        val bmp = BitmapFactory.decodeByteArray(bayt, 0, bayt.size) ?: return
        islenenKare++
        h.isle(bmp)
    }

    private fun jsAyarGonder() {
        if (webKapandi) return
        val b = ayar.webBolge
        web.evaluateJavascript(
            "window.__altyazi && (__altyazi.bolge(${b.sol},${b.ust},${b.sag},${b.alt}), " +
                "__altyazi.ses(${ayar.webSes}), __altyazi.aralik(${ayar.aralikMs}))",
            null
        )
    }

    // ---------- Bölge ----------

    /**
     * Bölge seçici tüm ekranı kaplar (oranlar ekranın tamamına göre).
     * Videonun WebView içindeki alanını ekran koordinatına çevirip oradan dönüştürüyoruz.
     */
    private fun bolgeSec() {
        val ekran = windowManager.maximumWindowMetrics.bounds
        val ew = ekran.width().toFloat(); val eh = ekran.height().toFloat()
        val konum = IntArray(2).also { web.getLocationOnScreen(it) }
        val wx = konum[0].toFloat(); val wy = konum[1].toFloat()
        val v = videoAlani.takeIf { it.sag > it.sol }
            ?: Oran(0f, 0f, web.width.toFloat(), web.height.toFloat())
        val videoEkranda = Oran(v.sol + wx, v.ust + wy, v.sag + wx, v.alt + wy)
        val baslangicPx = MetinIsleme.videodanEkrana(ayar.webBolge, videoEkranda)
        val baslangic = RectF(baslangicPx.sol / ew, baslangicPx.ust / eh, baslangicPx.sag / ew, baslangicPx.alt / eh)
        BolgeSecici(
            this, windowManager, baslangic, "İngilizce altyazının çıktığı alanı çiz",
            WindowManager.LayoutParams.TYPE_APPLICATION
        ) { yeni ->
            if (yeni != null) {
                val secim = Oran(yeni.left * ew, yeni.top * eh, yeni.right * ew, yeni.bottom * eh)
                ayar.webBolge = MetinIsleme.ekrandanVideoya(secim, videoEkranda)
                hat?.sifirla()
                jsAyarGonder()
                durum("Bölge kaydedildi.")
            }
        }.goster()
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
        val kare = kareResmi()
        icerik.addView(yazi(if (kare != null) "Uygulamanın okuduğu bölge:" else "Henüz kare yok (bölge boş olabilir).", 13f, SOLUK).apply {
            setPadding(0, dp(12), 0, dp(6))
        })
        if (kare != null) icerik.addView(ImageView(this).apply {
            setImageBitmap(kare)
            adjustViewBounds = true
            background = yuvarlak(Color.BLACK, 8)
        }, LinearLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(this)
            .setTitle("Kontrol")
            .setView(ScrollView(this).apply { addView(icerik) })
            .setPositiveButton("Tamam", null)
            .setNeutralButton("Bölge") { _, _ -> bolgeSec() }
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

    private fun kareResmi(): Bitmap? {
        if (sonKareB64.isEmpty()) return null
        return try {
            val b = Base64.decode(sonKareB64, Base64.DEFAULT)
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
                hata || s.contains("indirilemedi") || s.contains("hata", true) -> KIRMIZI
                s.startsWith("Hazır") || s.startsWith("Bağlandı") || s.startsWith("Bölge") -> YESIL
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
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        ana.removeCallbacksAndMessages(null)
        hat?.kapat(); hat = null
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
