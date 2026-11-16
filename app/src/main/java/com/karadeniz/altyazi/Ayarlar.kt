package com.karadeniz.altyazi

import android.content.Context
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
    var sonCeviriDinleyici: ((String, String) -> Unit)? = null
}
