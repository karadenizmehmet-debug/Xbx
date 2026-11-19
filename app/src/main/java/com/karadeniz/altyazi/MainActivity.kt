package com.karadeniz.altyazi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MR

class MainActivity : AppCompatActivity() {

    private lateinit var ayar: Ayarlar
    private lateinit var durumYazi: TextView
    private lateinit var durumNokta: View
    private lateinit var sonYazi: TextView
    private lateinit var tvSonuc: TextView
    private lateinit var baslatDugme: MaterialButton
    private lateinit var tvAlan: EditText
    private val tv = TvGonderici()
    private lateinit var tvListesi: LinearLayout
    private lateinit var tvBilgi: TextView
    private val bulunanTvler = linkedMapOf<String, TvBulucu.TvCihaz>()
    private var bulucu: TvBulucu? = null

    private val ekranIzni = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { sonuc ->
        val veri = sonuc.data
        if (sonuc.resultCode == RESULT_OK && veri != null) {
            val i = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.EYLEM_BASLAT)
                .putExtra(CaptureService.EK_KOD, sonuc.resultCode)
                .putExtra(CaptureService.EK_VERI, veri)
            ContextCompat.startForegroundService(this, i)
            durumYazi.text = "Başlatılıyor… Şimdi Xbox uygulamasında Remote Play'i aç."
        } else {
            durumYazi.text = "Ekran kaydı izni verilmedi."
        }
    }

    private val bildirimIzni = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ekranIzniIste() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ayar = Ayarlar(this)
        AppCompatDelegate.setDefaultNightMode(
            if (ayar.geceModu) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(arayuz())
    }

    override fun onResume() {
        super.onResume()
        Durum.dinleyici = { s -> durumYazi.text = s; dugmeyiGuncelle() }
        Durum.sonCeviriDinleyici = { org, tr ->
            sonYazi.text = if (tr.isBlank()) "(altyazı yok)" else "EN  $org\nTR  $tr"
            sonYazi.visibility = View.VISIBLE
        }
        dugmeyiGuncelle()
        bulunanTvler.clear()
        bulucu = TvBulucu(this, ::tvBulundu, ::tvKayboldu).also { it.baslat() }
        // Birkaç saniye içinde hiçbir şey bulunmazsa kullanıcıya söyle
        tvListesi.postDelayed({ if (bulunanTvler.isEmpty()) tvListesiniCiz() }, 5000)
    }

    override fun onPause() {
        super.onPause()
        bulucu?.durdur(); bulucu = null
        Durum.dinleyici = null
        Durum.sonCeviriDinleyici = null
    }

    override fun onDestroy() {
        tv.kapat()
        super.onDestroy()
    }

    // ---------- Küçük yardımcılar ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun renk(attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

    private fun yuvarlak(renk: Int, yaricap: Int): GradientDrawable =
        GradientDrawable().apply { cornerRadius = dp(yaricap).toFloat(); setColor(renk) }

    private fun yazi(s: String, sp: Float = 14f, kalin: Boolean = false, soluk: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(renk(if (soluk) MR.attr.colorOnSurfaceVariant else MR.attr.colorOnSurface))
            if (kalin) setTypeface(typeface, Typeface.BOLD)
        }

    private fun ust(v: View, dpUst: Int): View {
        v.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(dpUst) }
        return v
    }

    /** Yuvarlak köşeli, ince çerçeveli kart. */
    private fun kart(arka: Int, vararg icerik: View): View {
        val ic = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        icerik.forEach { ic.addView(it) }
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(arka)
            strokeWidth = dp(1)
            strokeColor = renk(MR.attr.colorOutlineVariant)
            addView(ic)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
    }

    private fun yuzey() = renk(MR.attr.colorSurface)

    private fun baslik(ad: String, alt: String? = null): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(yazi(ad, 17f, kalin = true))
            if (alt != null) addView(yazi(alt, 13f, soluk = true).apply { setPadding(0, dp(2), 0, 0) })
            setPadding(0, 0, 0, dp(10))
        }

    private fun etiket(s: String) = yazi(s, 13f, soluk = true).apply { setPadding(0, dp(14), 0, dp(6)) }

    // ---------- Ekran ----------

    private fun arayuz(): View {
        val kok = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        kok.addView(ustBaslik())
        kok.addView(
            yazi("Xbox Remote Play görüntüsündeki İngilizce altyazıyı okur, Türkçeye çevirir ve TV'ye gönderir.", 14f, soluk = true)
                .apply { setPadding(dp(4), dp(2), dp(4), dp(16)) }
        )
        kok.addView(durumKarti())
        kok.addView(tvKarti())
        kok.addView(secenekKarti())
        kok.addView(gorunumKarti())
        kok.addView(aralikKarti())
        kok.addView(kullanimKarti())
        kok.addView(
            yazi("© Mehmet Karadeniz, 2026-2027", 12f, soluk = true).apply {
                gravity = Gravity.CENTER
                alpha = 0.7f
                setPadding(0, dp(8), 0, 0)
            }
        )
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setBackgroundColor(renk(android.R.attr.colorBackground))
            addView(kok)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, ins ->
                val b = ins.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                v.setPadding(b.left, b.top, b.right, b.bottom)
                ins
            }
        }
    }

    /** Üst başlık: ad + sürüm, gece/gündüz, paylaş. */
    private fun ustBaslik(): View {
        val satir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), 0, dp(4))
        }
        satir.addView(yazi("Altyazı", 28f, kalin = true))
        satir.addView(
            yazi("v${BuildConfig.VERSION_NAME}", 12f).apply {
                background = yuvarlak(renk(MR.attr.colorSecondaryContainer), 12)
                setTextColor(renk(MR.attr.colorOnSecondaryContainer))
                setPadding(dp(10), dp(3), dp(10), dp(3))
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) }
        )
        satir.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        satir.addView(
            ikonDugme(
                if (ayar.geceModu) R.drawable.ic_gunduz else R.drawable.ic_gece,
                if (ayar.geceModu) "Gündüz moduna geç" else "Gece moduna geç"
            ) {
                ayar.geceModu = !ayar.geceModu
                AppCompatDelegate.setDefaultNightMode(
                    if (ayar.geceModu) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }
        )
        satir.addView(
            ikonDugme(R.drawable.ic_paylas, "Paylaş") { paylas() },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )
        return satir
    }

    private fun ikonDugme(ikon: Int, aciklama: String, tik: () -> Unit) = ImageButton(this).apply {
        setImageResource(ikon)
        imageTintList = ColorStateList.valueOf(renk(MR.attr.colorOnSurfaceVariant))
        contentDescription = aciklama
        scaleType = android.widget.ImageView.ScaleType.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(50, 128, 128, 128)),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(renk(MR.attr.colorSurfaceVariant)) },
            null
        )
        setOnClickListener { tik() }
    }

    /** Büyük başlat/durdur düğmesi, durum satırı ve son çeviri. */
    private fun durumKarti(): View {
        val yaziRengi = renk(MR.attr.colorOnPrimaryContainer)
        durumNokta = View(this)
        durumYazi = yazi("Hazır.", 15f, kalin = true).apply { setTextColor(yaziRengi) }
        val durumSatiri = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(durumNokta, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
            addView(durumYazi, LinearLayout.LayoutParams(0, -2, 1f))
        }
        baslatDugme = MaterialButton(this).apply {
            textSize = 17f
            isAllCaps = false
            cornerRadius = dp(18)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { baslatDurdur() }
        }
        baslatDugme.layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(14) }
        val modSecimi = pillGrubu(
            listOf("web" to "Uygulama içinde bağlan", "ekran" to "Xbox uygulamasıyla (ekran kaydı)"),
            ayar.mod, esit = true, tipli = false
        ) { ayar.mod = it }
        sonYazi = yazi("", 14f).apply {
            setTextColor(yaziRengi)
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = yuvarlak(ColorUtils.setAlphaComponent(renk(MR.attr.colorSurface), 170), 14)
        }
        return kart(renk(MR.attr.colorPrimaryContainer), durumSatiri, ust(modSecimi, 14), baslatDugme, ust(sonYazi, 12))
    }

    private fun tvKarti(): View {
        val giris = TextInputLayout(this).apply {
            hint = "TV adresi (IP veya IP:port)"
            helperText = "Boş bırakırsan yalnızca telefonda gösterilir"
            val r = dp(14).toFloat()
            setBoxCornerRadii(r, r, r, r)
        }
        tvAlan = TextInputEditText(giris.context).apply {
            setText(ayar.tvAdresi)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        giris.addView(tvAlan)

        val test = MaterialButton(this).apply {
            text = "TV'ye test altyazısı gönder"
            isAllCaps = false
            cornerRadius = dp(14)
            backgroundTintList = ColorStateList.valueOf(renk(MR.attr.colorSecondaryContainer))
            setTextColor(renk(MR.attr.colorOnSecondaryContainer))
            setOnClickListener {
                ayar.tvAdresi = tvAlan.text.toString()
                if (ayar.tvAdresi.isBlank()) { kisa("Önce TV adresini yaz."); return@setOnClickListener }
                tvSonuc.text = "Test gönderiliyor…"
                tvSonuc.visibility = View.VISIBLE
                tv.gonder(
                    ayar.tvAdresi, "Test altyazı: Merhaba! (Altyazı v${BuildConfig.VERSION_NAME})", "Test",
                    ayar.stilJson(), 5000
                ) { ok, bilgi ->
                    runOnUiThread {
                        tvSonuc.text = when {
                            ok -> "TV yanıt verdi ✓  (5 sn test yazısı görünmeli)"
                            bilgi == "HTTP 503" -> "TV'ye ulaşıldı ama TV'de katman izni yok."
                            else -> "TV'ye ulaşılamadı: $bilgi"
                        }
                    }
                }
            }
        }
        tvSonuc = yazi("", 13f, soluk = true).apply {
            visibility = View.GONE
            setPadding(dp(4), dp(8), 0, 0)
        }
        tvBilgi = yazi("Ağdaki TV'ler aranıyor…", 13f, soluk = true).apply { setPadding(dp(4), 0, 0, dp(6)) }
        tvListesi = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val kaydir = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tvListesi)
        }
        return kart(
            yuzey(),
            baslik("Android TV", "TV'deki Altyazı uygulaması açık olmalı"),
            tvBilgi, kaydir, ust(giris, 10), ust(test, 10), tvSonuc
        )
    }

    // ---------- TV bulma (IP yazmadan) ----------

    private fun tvBulundu(c: TvBulucu.TvCihaz) {
        bulunanTvler[c.ad] = c
        // Kayıtlı TV'nin IP'si değiştiyse sessizce güncelle; hiç TV seçilmemişse ilk bulunanı seç
        if (c.ad == ayar.tvAdi && c.adres != ayar.tvAdresi) tvSec(c, bildir = false)
        else if (ayar.tvAdresi.isBlank()) tvSec(c, bildir = true)
        tvListesiniCiz()
    }

    private fun tvSec(c: TvBulucu.TvCihaz, bildir: Boolean) {
        ayar.tvAdi = c.ad
        ayar.tvAdresi = c.adres
        tvAlan.setText(c.adres)
        if (bildir) kisa("TV seçildi: ${c.ad}")
        tvListesiniCiz()
    }

    private fun tvListesiniCiz() {
        tvListesi.removeAllViews()
        tvBilgi.text = if (bulunanTvler.isEmpty())
            "Ağda TV bulunamadı. TV'de Altyazı açık mı, aynı Wi-Fi'da mı?"
        else "Ağdaki TV'ler (dokunup seç):"
        bulunanTvler.values.forEach { c ->
            val secili = c.adres == ayar.tvAdresi
            val t = TextView(this).apply {
                text = (if (secili) "✓ " else "📺 ") + c.ad
                textSize = 14f
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = yuvarlak(renk(if (secili) MR.attr.colorPrimary else MR.attr.colorSurfaceVariant), 22)
                setTextColor(renk(if (secili) MR.attr.colorOnPrimary else MR.attr.colorOnSurfaceVariant))
                setOnClickListener { tvSec(c, bildir = true) }
            }
            tvListesi.addView(t, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    private fun tvKayboldu(ad: String) {
        if (bulunanTvler.remove(ad) != null) tvListesiniCiz()
    }

    private fun secenekKarti(): View {
        val telefonda = anahtar("Telefonda da göster", "Çeviri telefon ekranında da görünür", ayar.telefondaGoster) {
            ayar.telefondaGoster = it
        }
        val tvSecenek = anahtar(
            "Tam ekranda TV'ye de gönder", "Tam ekran modundayken çeviriyi TV'ye de yollar",
            ayar.sadeceTvGonder, ayar.sadeceAltyazi
        ) { ayar.sadeceTvGonder = it }
        val tamEkran = anahtar(
            "Tam ekran altyazı modu", "Telefon kararır, büyük Türkçe yazı görünür", ayar.sadeceAltyazi
        ) { b ->
            ayar.sadeceAltyazi = b
            tvSecenek.alpha = if (b) 1f else 0.45f
            (tvSecenek.tag as MaterialSwitch).isEnabled = b
            if (Durum.calisiyor) kisa("Durdurup yeniden başlatınca geçerli olur.")
        }
        val cevrimici = anahtar(
            "Çevrimiçi çeviri", "Daha doğal çeviri; internet gerekir", ayar.cevrimici
        ) { ayar.cevrimici = it }
        val sansur = anahtar(
            "Küfür sansürü", "Küfürler yıldızlanır (s*****)", ayar.sansur
        ) { ayar.sansur = it }
        return kart(yuzey(), baslik("Seçenekler"), telefonda, tamEkran, tvSecenek, cevrimici, sansur)
    }

    /** Başlık + açıklama solda, anahtar sağda. Anahtar satırın `tag`'inde. */
    private fun anahtar(
        ad: String, aciklama: String, acik: Boolean, etkin: Boolean = true, degisti: (Boolean) -> Unit
    ): View {
        val sw = MaterialSwitch(this).apply {
            isChecked = acik
            isEnabled = etkin
            setOnCheckedChangeListener { _, b -> degisti(b) }
        }
        val metin = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(yazi(ad, 15f))
            addView(yazi(aciklama, 12f, soluk = true).apply { setPadding(0, dp(1), 0, 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            alpha = if (etkin) 1f else 0.45f
            tag = sw
            addView(metin, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sw)
            setOnClickListener { if (sw.isEnabled) sw.toggle() }
        }
    }

    private val yaziRenkleri = intArrayOf(
        Color.WHITE, Color.rgb(255, 235, 59), Color.rgb(129, 212, 250),
        Color.rgb(165, 214, 167), Color.rgb(255, 183, 77), Color.rgb(244, 143, 177), Color.BLACK
    )
    private val arkaRenkleri = intArrayOf(
        Color.BLACK, Color.rgb(66, 66, 66), Color.rgb(13, 30, 80),
        Color.rgb(90, 20, 20), Color.rgb(20, 70, 40), Color.WHITE
    )

    /** Altyazı görünümü: önizleme, boyut, yazı tipi, renkler, şeffaflık. */
    private fun gorunumKarti(): View {
        val onizleme = TextView(this).apply {
            text = "Örnek altyazı metni"
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        }
        val sahne = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(84, 110, 122), Color.rgb(28, 38, 43))
            ).apply { cornerRadius = dp(16).toFloat() }
            minimumHeight = dp(120)
            addView(onizleme, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
                bottomMargin = dp(16)
            })
        }
        fun yenile(bildir: Boolean = true) {
            onizleme.textSize = ayar.yaziBoyutu.toFloat()
            onizleme.setTextColor(ayar.yaziRengi)
            onizleme.typeface = YaziTipleri.getir(this, ayar.yaziTipi)
            (onizleme.background as GradientDrawable).setColor(ayar.arkaArgb())
            if (bildir) {
                if (Durum.calisiyor) Durum.stilDinleyici?.invoke()
                else {
                    // Servis kapalıyken de ayar TV'de görülsün
                    ayar.tvAdresi = tvAlan.text.toString()
                    if (ayar.tvAdresi.isNotBlank())
                        tv.gonder(ayar.tvAdresi, "Örnek altyazı metni", "", ayar.stilJson(), 2500)
                }
            }
        }
        yenile(false)

        val tipler = pillGrubu(YaziTipleri.liste, ayar.yaziTipi, esit = false, tipli = true) {
            ayar.yaziTipi = it; yenile()
        }
        val not = yazi(
            "Yer ve genişlik: uygulama çalışırken sağ üstteki \"Kutu\" ile Türkçe altyazının görüneceği alanı çiz. " +
                "Yazı sığmazsa kendiliğinden küçülür; boyut en büyük değerdir.",
            12f, soluk = true
        ).apply { setPadding(0, dp(12), 0, 0) }

        return kart(
            yuzey(),
            baslik("Altyazı görünümü", "Telefonda ve TV'de görünen yazı"),
            sahne,
            kaydirici("Yazı boyutu", 12, 44, ayar.yaziBoyutu) { ayar.yaziBoyutu = it; yenile() },
            etiket("Yazı tipi"), tipler,
            renkSatiri("Yazı rengi", yaziRenkleri, ayar.yaziRengi) { ayar.yaziRengi = it; yenile() },
            renkSatiri("Arka plan rengi", arkaRenkleri, ayar.arkaRengi) { ayar.arkaRengi = it; yenile() },
            kaydirici("Arka plan opaklığı", 0, 100, ayar.arkaOpaklik, "%") { ayar.arkaOpaklik = it; yenile() },
            not
        )
    }

    private fun aralikKarti(): View = kart(
        yuzey(),
        baslik("Okuma aralığı", "Hızlı seçenek altyazıyı daha çabuk yakalar, pili daha çok harcar"),
        pillGrubu(
            listOf("300" to "Hızlı", "450" to "Normal", "800" to "Pil dostu"),
            ayar.aralikMs.toString(), esit = true, tipli = false
        ) { ayar.aralikMs = it.toLong() }
    )

    private fun kullanimKarti(): View {
        val adimlar = listOf(
            "TV adresini yaz (TV uygulaması açık olmalı).",
            "Başlat'a bas ve ekran kaydı iznini ver.",
            "Xbox uygulamasında Remote Play'i aç.",
            "Sağ üstte \"Bölge\" ile İngilizce altyazı alanını, \"Kutu\" ile Türkçe altyazının yerini çiz."
        )
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        adimlar.forEachIndexed { i, s ->
            val no = yazi("${i + 1}", 13f, kalin = true).apply {
                gravity = Gravity.CENTER
                setTextColor(renk(MR.attr.colorOnPrimaryContainer))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(renk(MR.attr.colorPrimaryContainer))
                }
            }
            liste.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                    addView(no, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
                    addView(yazi(s, 14f), LinearLayout.LayoutParams(0, -2, 1f))
                }
            )
        }
        return kart(
            yuzey(),
            baslik("Nasıl kullanılır"),
            liste,
            yazi("İlk çalıştırmada çeviri modeli (~30 MB) iner; sonra internetsiz de çalışır.", 12f, soluk = true)
                .apply { setPadding(0, dp(10), 0, 0) }
        )
    }

    // ---------- Bileşenler ----------

    /** Yuvarlak seçim düğmeleri. `esit` = satırı eşit paylaş, aksi halde yana kayar. */
    private fun pillGrubu(
        secenekler: List<Pair<String, String>>, secili: String, esit: Boolean, tipli: Boolean,
        sec: (String) -> Unit
    ): View {
        val satir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pillar = mutableListOf<Pair<String, TextView>>()
        fun boya(t: TextView, acik: Boolean) {
            t.background = yuvarlak(renk(if (acik) MR.attr.colorPrimary else MR.attr.colorSurfaceVariant), 22)
            t.setTextColor(renk(if (acik) MR.attr.colorOnPrimary else MR.attr.colorOnSurfaceVariant))
        }
        secenekler.forEach { (id, ad) ->
            val t = TextView(this).apply {
                text = ad
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                if (tipli) typeface = YaziTipleri.getir(this@MainActivity, id)
            }
            boya(t, id == secili)
            t.setOnClickListener {
                pillar.forEach { (k, v) -> boya(v, k == id) }
                sec(id)
            }
            pillar.add(id to t)
            val p = if (esit) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-2, -2)
            p.marginEnd = dp(8)
            satir.addView(t, p)
        }
        return if (esit) satir else HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(satir)
        }
    }

    private fun kaydirici(
        ad: String, enAz: Int, enCok: Int, deger: Int, birim: String = "", degisti: (Int) -> Unit
    ): View {
        val sayi = yazi("$deger$birim", 14f, kalin = true).apply { setTextColor(renk(MR.attr.colorPrimary)) }
        val ustSatir = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(yazi(ad, 13f, soluk = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(sayi)
            setPadding(0, dp(14), 0, 0)
        }
        val s = Slider(this).apply {
            stepSize = 1f
            valueFrom = enAz.toFloat()
            valueTo = enCok.toFloat()
            value = deger.toFloat()
            addOnChangeListener { _, v, kullanici ->
                sayi.text = "${v.toInt()}$birim"
                if (kullanici) degisti(v.toInt())
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ustSatir)
            addView(s)
        }
    }

    private fun renkSatiri(ad: String, renkler: IntArray, secili: Int, degisti: (Int) -> Unit): View {
        val kutu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        kutu.addView(etiket(ad))
        val satir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun ciz(v: View, dolgu: Int, sec: Boolean) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dolgu)
                setStroke(
                    dp(if (sec) 3 else 1),
                    if (sec) renk(MR.attr.colorPrimary) else renk(MR.attr.colorOutline)
                )
            }
        }
        renkler.forEach { r ->
            val nokta = View(this)
            ciz(nokta, r, r == secili)
            nokta.setOnClickListener {
                for (i in 0 until satir.childCount) ciz(satir.getChildAt(i), renkler[i], false)
                ciz(nokta, r, true)
                degisti(r)
            }
            satir.addView(nokta, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
        }
        kutu.addView(satir)
        return kutu
    }

    private fun dugmeyiGuncelle() {
        val calisiyor = Durum.calisiyor
        baslatDugme.text = if (calisiyor) "Durdur" else "Başlat"
        baslatDugme.backgroundTintList =
            ColorStateList.valueOf(renk(if (calisiyor) MR.attr.colorError else MR.attr.colorPrimary))
        baslatDugme.setTextColor(renk(if (calisiyor) MR.attr.colorOnError else MR.attr.colorOnPrimary))
        durumNokta.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (calisiyor) Color.rgb(46, 160, 67) else renk(MR.attr.colorOutline))
        }
    }

    // ---------- Eylemler ----------

    private fun baslatDurdur() {
        ayar.tvAdresi = tvAlan.text.toString()
        if (Durum.calisiyor) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.EYLEM_DURDUR))
            return
        }
        if (ayar.mod == "web") {
            // Uygulama içi Remote Play: ekran kaydı ve üstte gösterme izni gerekmez
            startActivity(Intent(this, UzaktanOynatActivity::class.java))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            kisa("\"Diğer uygulamaların üzerinde göster\" iznini aç, sonra geri dön.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            bildirimIzni.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        ekranIzniIste()
    }

    private fun ekranIzniIste() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Yapılandırma yok: sistem "tek uygulama / tüm ekran" seçimini kullanıcıya bırakır
        ekranIzni.launch(mpm.createScreenCaptureIntent())
    }

    private fun paylas() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Altyazı – Xbox altyazılarını Türkçeye çeviren uygulama:\nhttps://github.com/karadenizmehmet-debug/Xbx/releases"
            )
        }
        startActivity(Intent.createChooser(i, "Paylaş"))
    }

    private fun kisa(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
