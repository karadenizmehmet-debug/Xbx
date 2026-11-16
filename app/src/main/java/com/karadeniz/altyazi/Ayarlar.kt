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
            .put("kutu", JSONArray().put(k.left.toDouble()).put(k.top.toDouble())
                .put(k.right.toDouble()).put(k.bottom.toDouble()))
    }

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
