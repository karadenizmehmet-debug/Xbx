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
    fun cevir(metin: String): String? {
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=tr&dt=t&q=" +
                URLEncoder.encode(metin, "UTF-8")
        )
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 1500
            c.readTimeout = 1500
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) return null
            val govde = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parcalar = JSONArray(govde).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parcalar.length()) sb.append(parcalar.getJSONArray(i).optString(0, ""))
            return sb.toString().trim().ifBlank { null }
        } finally {
            c.disconnect()
        }
    }
}
