package com.karadeniz.altyazi

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.res.ResourcesCompat

/** Uygulama ayarları (SharedPreferences). Bölge, ekranın oranı olarak (0..1) saklanır. */
class Ayarlar(private val context: Context) {
    private val p = context.getSharedPreferences("altyazi", Context.MODE_PRIVATE)

    var tvAdresi: String
        get() = p.getString("tv", "") ?: ""
        set(v) = p.edit().putString("tv", v.trim()).apply()

    /** Seçilen TV'nin ağdaki adı; IP değişirse bu adla yeniden bulunur. */
    var tvAdi: String
        get() = p.getString("tvAdi", "") ?: ""
        set(v) = p.edit().putString("tvAdi", v).apply()

    /** Çeviride küfürleri yıldızla (varsayılan açık). */
    /** Sabit çeviri sözlüğü açık mı (kayıtlı yazılar çevirmene sorulmadan çevrilir). */
    var sozlukAcik: Boolean
        get() = p.getBoolean("sozlukAcik", true)
        set(v) = p.edit().putBoolean("sozlukAcik", v).apply()

    var sansur: Boolean
        get() = p.getBoolean("sansur", true)
        set(v) = p.edit().putBoolean("sansur", v).apply()

    var telefondaGoster: Boolean
        get() = p.getBoolean("telefonda", true)
        set(v) = p.edit().putBoolean("telefonda", v).apply()

    /** Çevrimiçi Google çevirisi (daha doğal). Kapalıysa cihazdaki ML Kit kullanılır. */
    var cevrimici: Boolean
        get() = p.getBoolean("cevrimici", true)
        set(v) = p.edit().putBoolean("cevrimici", v).apply()

    // --- Altyazı görünümü ---
    var yaziBoyutu: Int
        get() = p.getInt("yaziBoyutu", 18)
        set(v) = p.edit().putInt("yaziBoyutu", v).apply()

    var yaziTipi: String
        get() = p.getString("yaziTipi", "varsayilan") ?: "varsayilan"
        set(v) = p.edit().putString("yaziTipi", v).apply()

    /** Türkçe altyazının gösterileceği alan (ekran oranı). Yazı bu alana sığdırılır. */
    var kutu: RectF
        get() = RectF(
            p.getFloat("kl", 0.15f), p.getFloat("kt", 0.04f),
            p.getFloat("kr", 0.85f), p.getFloat("kb", 0.28f)
        )
        set(r) = p.edit()
            .putFloat("kl", r.left).putFloat("kt", r.top)
            .putFloat("kr", r.right).putFloat("kb", r.bottom)
            .apply()

    var yaziRengi: Int
        get() = p.getInt("yaziRengi", Color.WHITE)
        set(v) = p.edit().putInt("yaziRengi", v).apply()

    var arkaRengi: Int
        get() = p.getInt("arkaRengi", Color.BLACK)
        set(v) = p.edit().putInt("arkaRengi", v).apply()

    /** Arka plan opaklığı: 0 = tam şeffaf, 100 = tam dolu. */
    var arkaOpaklik: Int
        get() = p.getInt("arkaOpaklik", 60)
        set(v) = p.edit().putInt("arkaOpaklik", v).apply()

    fun arkaArgb(): Int = Color.argb(
        arkaOpaklik * 255 / 100, Color.red(arkaRengi), Color.green(arkaRengi), Color.blue(arkaRengi)
    )

    /** TV'ye gönderilen görünüm. Boyut, ekranın kısa kenarına oranla gider (TV'de aynı oranda görünür). */
    fun stilJson(): JSONObject {
        val m = context.resources.displayMetrics
        val kisa = minOf(m.widthPixels, m.heightPixels).coerceAtLeast(1)
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, yaziBoyutu.toFloat(), m)
        val k = kutu
        return JSONObject()
            .put("boyutOran", (px / kisa).toDouble())
            .put("tip", yaziTipi)
            .put("yaziRengi", yaziRengi)
            .put("arkaRengi", arkaRengi)
            .put("arkaOpaklik", arkaOpaklik)
            .put("hiza", JSONArray().put(hizaX.toDouble()).put(hizaY.toDouble()).put(hizaOlcek.toDouble()))
            .put("kutu", JSONArray().put(k.left.toDouble()).put(k.top.toDouble())
                .put(k.right.toDouble()).put(k.bottom.toDouble()))
    }

    /** Telefon ekranını karart, yalnızca büyük Türkçe altyazıyı göster (telefon TV'nin altında dururken). */
    var sadeceAltyazi: Boolean
        get() = p.getBoolean("sadece", false)
        set(v) = p.edit().putBoolean("sadece", v).apply()

    /** Tam ekran modunda çeviri TV'ye de gitsin mi? Kapalıysa yalnızca telefonda görünür. */
    var sadeceTvGonder: Boolean
        get() = p.getBoolean("sadeceTv", true)
        set(v) = p.edit().putBoolean("sadeceTv", v).apply()

    var geceModu: Boolean
        get() = p.getBoolean("gece", true)
        set(v) = p.edit().putBoolean("gece", v).apply()

    /** Ekran okuma aralığı (ms). */
    var aralikMs: Long
        get() = p.getLong("aralik", 450L)
        set(v) = p.edit().putLong("aralik", v).apply()

    /** Altyazı bölgesi. Varsayılan: ekranın alt %28'i, ortadaki %80. */
    var bolge: RectF
        get() = RectF(
            p.getFloat("bl", 0.10f), p.getFloat("bt", 0.70f),
            p.getFloat("br", 0.90f), p.getFloat("bb", 0.98f)
        )
        set(r) = p.edit()
            .putFloat("bl", r.left).putFloat("bt", r.top)
            .putFloat("br", r.right).putFloat("bb", r.bottom)
            .apply()

    /** Uygulama içi modda altyazı bölgesi: video karesinin oranı (0..1). */
    var webBolge: Oran
        get() = Oran(
            p.getFloat("wl", 0.10f), p.getFloat("wt", 0.72f),
            p.getFloat("wr", 0.90f), p.getFloat("wb", 0.95f)
        )
        set(r) = p.edit()
            .putFloat("wl", r.sol).putFloat("wt", r.ust)
            .putFloat("wr", r.sag).putFloat("wb", r.alt)
            .apply()

    /** Alanın okuma bölgesi (video oranı). Altyazı eski "webBolge" anahtarlarını kullanır. */
    fun alanBolge(a: Alan): Oran = when (a) {
        Alan.ALTYAZI -> webBolge
        Alan.TELEFON -> oranOku("tel", Oran(0.62f, 0.20f, 0.98f, 0.98f))
        Alan.BILDIRIM -> oranOku("bil", Oran(0.66f, 0.70f, 0.99f, 0.97f))
    }

    fun alanBolgeYaz(a: Alan, o: Oran) = when (a) {
        Alan.ALTYAZI -> webBolge = o
        Alan.TELEFON -> oranYaz("tel", o)
        Alan.BILDIRIM -> oranYaz("bil", o)
    }

    fun alanAktif(a: Alan): Boolean = p.getBoolean("aktif_${a.id}", a == Alan.ALTYAZI)
    fun alanAktifYaz(a: Alan, v: Boolean) = p.edit().putBoolean("aktif_${a.id}", v).apply()

    fun minKelime(a: Alan): Int = p.getInt("minKelime_${a.id}", a.minKelimeVarsayilan)
    fun minKelimeYaz(a: Alan, v: Int) = p.edit().putInt("minKelime_${a.id}", v).apply()

    /** Yerinde çevirinin boyutu, orijinale göre yüzde (100 = orijinal boyu). Bildirim varsayılanı uzaktan okunsun diye büyük. */
    fun alanBoyut(a: Alan): Int = p.getInt("boyut_${a.id}", if (a == Alan.BILDIRIM) 140 else 100)
    fun alanBoyutYaz(a: Alan, v: Int) = p.edit().putInt("boyut_${a.id}", v).apply()

    private fun oranOku(on: String, v: Oran) = Oran(
        p.getFloat("${on}l", v.sol), p.getFloat("${on}t", v.ust), p.getFloat("${on}r", v.sag), p.getFloat("${on}b", v.alt)
    )

    private fun oranYaz(on: String, o: Oran) = p.edit()
        .putFloat("${on}l", o.sol).putFloat("${on}t", o.ust).putFloat("${on}r", o.sag).putFloat("${on}b", o.alt).apply()

    /** TV hizalaması (yerinde çeviriler için): kaydırma (ekran oranı, ±0.05) ve ölçek (0.95–1.05). */
    var hizaX: Float
        get() = p.getFloat("hizaX", 0f)
        set(v) = p.edit().putFloat("hizaX", v).apply()
    var hizaY: Float
        get() = p.getFloat("hizaY", 0f)
        set(v) = p.edit().putFloat("hizaY", v).apply()
    var hizaOlcek: Float
        get() = p.getFloat("hizaOlcek", 1f)
        set(v) = p.edit().putFloat("hizaOlcek", v).apply()

    /** Telefon alanı yalnızca oyundaki telefon ekranı görünürken çevrilsin (harita vb. değil). */
    var telefonAlgila: Boolean
        get() = p.getBoolean("telefonAlgila", true)
        set(v) = p.edit().putBoolean("telefonAlgila", v).apply()

    /** Telefon algılama hassasiyeti: örneklerin en az bu yüzdesi açık yüzey olmalı (düşük = daha kolay algılar). */
    var telefonEsik: Int
        get() = p.getInt("telefonEsik", 8)
        set(v) = p.edit().putInt("telefonEsik", v).apply()

    /** Uygulama içi modda oyun sesi telefondan gelsin mi? */
    var webSes: Boolean
        get() = p.getBoolean("webSes", true)
        set(v) = p.edit().putBoolean("webSes", v).apply()

    /** Bağlantı modu: "web" = uygulama içinde, "ekran" = Xbox uygulaması + ekran kaydı. */
    var mod: String
        get() = p.getString("mod", "web") ?: "web"
        set(v) = p.edit().putString("mod", v).apply()
}

/** Servis ile ekran arasında basit mesajlaşma (aynı süreç). */
object Durum {
    @Volatile var calisiyor = false
    var dinleyici: ((String) -> Unit)? = null
    /** Görünüm ayarı değişti: servis kaplamayı yeniden biçimlendirir. */
    var stilDinleyici: (() -> Unit)? = null
    var sonCeviriDinleyici: ((String, String) -> Unit)? = null
}

/** Türkçe karakterleri (ğ ş ı İ ö ü ç) destekleyen yazı tipleri. */
object YaziTipleri {
    val liste = listOf(
        "varsayilan" to "Varsayılan",
        "kalin" to "Kalın",
        "dar" to "Dar",
        "nunito" to "Nunito",
        "oswald" to "Oswald",
        "comfortaa" to "Comfortaa",
        "lora" to "Lora",
        "serif" to "Serif",
        "esaralik" to "Eş aralıklı"
    )

    fun getir(ctx: Context, id: String): Typeface = try {
        when (id) {
            "kalin" -> Typeface.DEFAULT_BOLD
            "dar" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            "serif" -> Typeface.SERIF
            "esaralik" -> Typeface.MONOSPACE
            "nunito" -> ResourcesCompat.getFont(ctx, R.font.nunito)
            "oswald" -> ResourcesCompat.getFont(ctx, R.font.oswald)
            "comfortaa" -> ResourcesCompat.getFont(ctx, R.font.comfortaa)
            "lora" -> ResourcesCompat.getFont(ctx, R.font.lora)
            else -> Typeface.DEFAULT
        } ?: Typeface.DEFAULT
    } catch (_: Exception) { Typeface.DEFAULT }
}
