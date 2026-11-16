package com.karadeniz.altyazi

import android.content.Context
import android.graphics.Color
import android.graphics.RectF

/** Uygulama ayarları (SharedPreferences). Bölge, ekranın oranı olarak (0..1) saklanır. */
class Ayarlar(context: Context) {
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

    /** Dikey konum: 0 = en üst, 100 = en alt. */
    var konumYuzde: Int
        get() = p.getInt("konum", 6)
        set(v) = p.edit().putInt("konum", v).apply()

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
