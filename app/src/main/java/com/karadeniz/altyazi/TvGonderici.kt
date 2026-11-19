package com.karadeniz.altyazi

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Çeviriyi TV uygulamasına gönderir.
 * Protokol: POST http://<adres>/sub  gövde: {"text": "...", "orijinal": "...", "stil": {...}, "sure": ms}
 * TV uygulamasının kendi ayarı yoktur; görünüm her mesajda "stil" ile gider.
 * Boş "text" = altyazıyı ekrandan kaldır.
 */
class TvGonderici {
    private val is_ = Executors.newSingleThreadExecutor()

    /**
     * Yerinde çeviri: {"id": "telefon", "bloklar": [{"text": "...", "kutu": [l,t,r,b]}], "stil": {...}}.
     * Kutular ekranın oranıdır (0..1). Boş liste = o alanı temizle.
     */
    fun gonderBloklar(adres: String, id: String, bloklar: org.json.JSONArray, stil: JSONObject?,
                      sonuc: ((Boolean, String) -> Unit)? = null) {
        val j = JSONObject().put("id", id).put("bloklar", bloklar)
        if (stil != null) j.put("stil", stil)
        gonderHam(adres, j, sonuc)
    }

    private fun gonderHam(adres: String, j: JSONObject, sonuc: ((Boolean, String) -> Unit)?) {
        if (adres.isBlank() || is_.isShutdown) return
        is_.execute {
            try {
                val hedef = if (adres.contains(":")) adres else "$adres:8765"
                val c = URL("http://$hedef/sub").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 1000
                c.readTimeout = 1000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(j.toString().toByteArray(Charsets.UTF_8)) }
                val kod = c.responseCode
                c.disconnect()
                sonuc?.invoke(kod in 200..299, "HTTP $kod")
            } catch (e: Exception) {
                sonuc?.invoke(false, e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
        }
    }

    fun gonder(
        adres: String, metin: String, orijinal: String,
        stil: JSONObject? = null, sureMs: Long = 0,
        sonuc: ((Boolean, String) -> Unit)? = null
    ) {
        if (adres.isBlank()) return
        is_.execute {
            try {
                val hedef = if (adres.contains(":")) adres else "$adres:8765"
                val url = URL("http://$hedef/sub")
                val c = url.openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 1000
                c.readTimeout = 1000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val j = JSONObject().put("text", metin).put("orijinal", orijinal)
                if (stil != null) j.put("stil", stil)
                if (sureMs > 0) j.put("sure", sureMs)   // TV bu süre sonunda kendisi gizler
                val govde = j.toString()
                c.outputStream.use { it.write(govde.toByteArray(Charsets.UTF_8)) }
                val kod = c.responseCode
                c.disconnect()
                sonuc?.invoke(kod in 200..299, "HTTP $kod")
            } catch (e: Exception) {
                sonuc?.invoke(false, e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
        }
    }

    fun kapat() = is_.shutdown()
}
