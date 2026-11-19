package com.karadeniz.altyazi

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Çeviri'nin ücretsiz web uç noktası (resmi olmayan, anahtar gerektirmez).
 * Cihazdaki ML Kit modelinden daha doğal Türkçe verir. Hata/zaman aşımında null döner,
 * çağıran taraf ML Kit'e düşer.
 */
class CevrimiciCeviri {
    /**
     * Aynı ekrandaki mesajları tek istekte, sırayla ve satır satır çevirir: Google her satırı
     * komşularının bağlamıyla görür (konuşma akışı korunur). Satır sayısı tutmazsa null.
     */
    fun cevirToplu(metinler: List<String>): List<String>? {
        if (metinler.isEmpty()) return emptyList()
        if (metinler.size == 1) return cevir(metinler[0])?.let { listOf(it) }
        val girdi = metinler.joinToString("\n") { it.replace('\n', ' ') }
        val sonuc = cevir(girdi, satirlariKoru = true) ?: return null
        return CeviriBicim.satirlaraBol(sonuc, metinler.size)
    }

    fun cevir(metin: String, satirlariKoru: Boolean = false): String? {
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t&q=" +
                URLEncoder.encode(metin, "UTF-8")
        )
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2000
            c.readTimeout = 2500
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) return null
            val govde = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parcalar = JSONArray(govde).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parcalar.length()) sb.append(parcalar.getJSONArray(i).optString(0, ""))
            val s = sb.toString()
            return (if (satirlariKoru) s else s.trim()).ifBlank { null }
        } finally {
            c.disconnect()
        }
    }
}

/** Çeviri öncesi/sonrası biçim düzeltmeleri (Android'den bağımsız, birim testli). */
object CeviriBicim {
    private val tr = java.util.Locale("tr")

    /** TAMAMI BÜYÜK HARF metin Google'da kötü çevrilir: cümle düzenine çevir ("MOVE ARM" → "Move arm"). */
    fun duzelt(metin: String): String {
        val harfler = metin.filter { it.isLetter() }
        if (harfler.length < 3 || harfler.any { it.isLowerCase() }) return metin
        val k = metin.lowercase(java.util.Locale.ENGLISH)
        return k.replaceFirstChar { it.titlecase(java.util.Locale.ENGLISH) }
    }

    /** Orijinal tamamen büyük harfse çeviriyi de büyük harfe çevir (oyundaki görünüm korunsun). */
    fun geriBuyut(orijinal: String, ceviri: String): String {
        val harfler = orijinal.filter { it.isLetter() }
        return if (harfler.length >= 3 && harfler.none { it.isLowerCase() }) ceviri.uppercase(tr) else ceviri
    }

    /** Toplu çeviriyi satırlara böl; sayı tutmazsa null (çağıran tek tek çevirir). */
    fun satirlaraBol(sonuc: String, adet: Int): List<String>? {
        val satirlar = sonuc.split('\n').map { it.trim() }
        return if (satirlar.size == adet && satirlar.none { it.isEmpty() }) satirlar else null
    }
}
