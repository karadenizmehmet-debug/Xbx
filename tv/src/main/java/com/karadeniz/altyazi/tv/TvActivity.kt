package com.karadeniz.altyazi.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** Kurulum ekranı: IP adresini ve izin durumunu gösterir. Altyazı ayarı yoktur; telefondan gelir. */
class TvActivity : Activity() {

    private lateinit var durum: TextView
    private val ana = Handler(Looper.getMainLooper())
    private val yenile = object : Runnable {
        override fun run() { durumuYaz(); ana.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val p = getSharedPreferences("tv", Context.MODE_PRIVATE)
        val gece = p.getBoolean("gece", true)
        setTheme(
            if (gece) android.R.style.Theme_DeviceDefault_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
        AltyaziServisi.baslat(this)

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val kok = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(27), dp(48), dp(27))
        }

        val baslik = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        baslik.addView(TextView(this).apply {
            text = "Altyazı TV"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })
        baslik.addView(TextView(this).apply {
            text = "   v${BuildConfig.VERSION_NAME}"
            textSize = 16f
            alpha = 0.7f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        baslik.addView(Button(this).apply {
            text = if (gece) "☀ Gündüz" else "☾ Gece"
            setOnClickListener {
                p.edit().putBoolean("gece", !gece).apply()
                recreate()
            }
        })
        kok.addView(baslik)

        durum = TextView(this).apply {
            textSize = 20f
            setPadding(0, dp(18), 0, dp(18))
            setLineSpacing(0f, 1.25f)
        }
        kok.addView(durum)

        kok.addView(Button(this).apply {
            text = "Katman iznini aç (Diğer uygulamaların üzerinde göster)"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    durum.append("\n\nBu TV'de izin ekranı yok. Bilgisayardan adb komutunu kullan.")
                }
            }
            requestFocus()
        })

        kok.addView(TextView(this).apply {
            text = "İzin ekranı açılmıyorsa bilgisayardan bir kez:\n" +
                "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow\n\n" +
                "Bu ekranı kapatabilirsin; uygulama arka planda çalışır ve TV açılınca kendiliğinden başlar.\n" +
                "Yazı boyutu, yeri, rengi ve yazı tipi telefondaki Altyazı uygulamasından gelir."
            textSize = 15f
            alpha = 0.75f
            setPadding(0, dp(16), 0, 0)
        })

        kok.addView(TextView(this).apply {
            text = "© Mehmet Karadeniz, 2026-2027"
            textSize = 13f
            alpha = 0.5f
            setPadding(0, dp(22), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(kok) })
    }

    override fun onResume() { super.onResume(); ana.post(yenile) }
    override fun onPause() { super.onPause(); ana.removeCallbacks(yenile) }

    private fun durumuYaz() {
        val izin = Settings.canDrawOverlays(this)
        durum.text = "Telefona yazılacak TV adresi:  ${ipAdresi() ?: "ağ bağlantısı yok"}\n" +
            "Sunucu (port ${AltyaziServisi.PORT}):  ${if (AltyaziServisi.sunucuCalisiyor) "çalışıyor ✓" else "çalışmıyor ✗"}\n" +
            "Katman izni:  ${if (izin) "verildi ✓" else "YOK ✗ — altyazı görünmez"}\n" +
            "Son gelen:  ${AltyaziServisi.sonMesaj}"
    }

    private fun ipAdresi(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) { null }
}
