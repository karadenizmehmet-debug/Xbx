package com.karadeniz.altyazi.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Küçük HTTP sunucusu. Telefon uygulaması buraya POST /sub gönderir.
 * [isle] (yol, gövde) alır, (durumKodu, yanıtMetni) döndürür.
 */
class Sunucu(private val port: Int, private val isle: (String, String) -> Pair<Int, String>) : Thread("altyazi-sunucu") {
    @Volatile private var soket: ServerSocket? = null
    @Volatile var calisiyor = false
        private set

    override fun run() {
        try {
            ServerSocket(port).use { ss ->
                soket = ss
                calisiyor = true
                while (!isInterrupted) {
                    val s = ss.accept()
                    try { baglanti(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} }
                }
            }
        } catch (_: Exception) {
        } finally {
            calisiyor = false
        }
    }

    private fun baglanti(s: Socket) {
        s.soTimeout = 3000
        val giris = BufferedInputStream(s.getInputStream())
        // Başlıkları oku (\r\n\r\n'e kadar)
        val bas = ByteArrayOutputStream()
        var son4 = 0
        while (bas.size() < 16384) {
            val b = giris.read()
            if (b < 0) break
            bas.write(b)
            son4 = (son4 shl 8) or b
            if (son4 == 0x0D0A0D0A) break
        }
        val satirlar = bas.toString("ISO-8859-1").split("\r\n")
        val ilk = satirlar.firstOrNull()?.split(" ") ?: return
        val yol = ilk.getOrNull(1) ?: "/"
        var uzunluk = 0
        for (st in satirlar) {
            val i = st.indexOf(':')
            if (i > 0 && st.substring(0, i).trim().equals("Content-Length", true))
                uzunluk = st.substring(i + 1).trim().toIntOrNull() ?: 0
        }
        uzunluk = uzunluk.coerceIn(0, 200_000)
        val govde = ByteArray(uzunluk)
        var okunan = 0
        while (okunan < uzunluk) {
            val n = giris.read(govde, okunan, uzunluk - okunan)
            if (n < 0) break
            okunan += n
        }
        val (kod, yanit) = try {
            isle(yol, String(govde, 0, okunan, Charsets.UTF_8))
        } catch (e: Exception) {
            500 to "{\"ok\":false}"
        }
        val veri = yanit.toByteArray(Charsets.UTF_8)
        val cikis = s.getOutputStream()
        cikis.write(
            ("HTTP/1.1 $kod OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${veri.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        cikis.write(veri)
        cikis.flush()
    }

    fun durdur() {
        interrupt()
        try { soket?.close() } catch (_: Exception) {}
    }
}
