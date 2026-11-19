package com.karadeniz.altyazi

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** Xbox bağlı olmadan TV'de görünümü denemek için örnek bloklar. Konumlar, alanın kayıtlı okuma bölgesine göre. */
object Deneme {
    const val ALTYAZI_METNI = "Bu bir deneme altyazısıdır. Yazı boyutu, renk ve konum burada görünür."

    private fun blok(
        metin: String, sol: Float, ust: Float, sag: Float, alt: Float, tek: Boolean,
        arka: Int, yazi: Int, olcek: Double, satirSayisi: Int
    ): JSONObject = JSONObject()
        .put("text", metin).put("tek", tek).put("aci", 0.0)
        .put("kutu", JSONArray().put(sol.toDouble()).put(ust.toDouble()).put(sag.toDouble()).put(alt.toDouble()))
        .put("arka", arka).put("yazi", yazi).put("olcek", olcek).put("kalin", true)
        .put("sy", ((alt - ust) / satirSayisi).toDouble())

    /** Telefon: iki mesaj balonu (çok satırlı, açık gri). */
    fun telefon(b: Oran, olcek: Double): JSONArray {
        val w = b.sag - b.sol; val h = b.alt - b.ust
        val arka = Color.rgb(232, 232, 238); val yazi = Color.rgb(25, 25, 30)
        return JSONArray()
            .put(blok("Merhaba, bu akşam depoda buluşabilir miyiz? Sana göstermem gereken bir şey var.",
                b.sol + 0.08f * w, b.ust + 0.28f * h, b.sag - 0.08f * w, b.ust + 0.28f * h + 0.09f, false, arka, yazi, olcek, 3))
            .put(blok("Tamam, geliyorum. Kimseye söyleme.",
                b.sol + 0.20f * w, b.ust + 0.50f * h, b.sag - 0.08f * w, b.ust + 0.50f * h + 0.06f, false, arka, yazi, olcek, 2))
    }

    /** Bildirim: sağ altta kısa HUD satırları (tek satır, koyu). */
    fun bildirim(b: Oran, olcek: Double): JSONArray {
        val w = b.sag - b.sol
        val arka = Color.rgb(28, 28, 32); val yazi = Color.WHITE
        val satir = 0.036f
        val alt2 = b.alt - 0.02f; val alt1 = alt2 - satir - 0.012f; val alt0 = alt1 - satir - 0.012f
        return JSONArray()
            .put(blok("Devam etmek için A'ya bas", b.sol + 0.30f * w, alt0 - satir, b.sag - 0.02f * w, alt0, true, arka, yazi, olcek, 1))
            .put(blok("Sağlık: 85", b.sol + 0.55f * w, alt1 - satir, b.sag - 0.02f * w, alt1, true, arka, yazi, olcek, 1))
            .put(blok("Taşlar: 12", b.sol + 0.55f * w, alt2 - satir, b.sag - 0.02f * w, alt2, true, arka, yazi, olcek, 1))
    }
}
