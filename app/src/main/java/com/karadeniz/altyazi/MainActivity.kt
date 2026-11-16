package com.karadeniz.altyazi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var ayar: Ayarlar
    private lateinit var durumYazi: TextView
    private lateinit var sonYazi: TextView
    private lateinit var baslatDugme: MaterialButton
    private lateinit var tvAlan: EditText
    private val tv = TvGonderici()

    private val ekranIzni = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { sonuc ->
        val veri = sonuc.data
        if (sonuc.resultCode == RESULT_OK && veri != null) {
            val i = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.EYLEM_BASLAT)
                .putExtra(CaptureService.EK_KOD, sonuc.resultCode)
                .putExtra(CaptureService.EK_VERI, veri)
            ContextCompat.startForegroundService(this, i)
            durumYazi.text = "Başlatılıyor… Şimdi Xbox uygulamasında Remote Play'i aç."
        } else {
            durumYazi.text = "Ekran kaydı izni verilmedi."
        }
    }

    private val bildirimIzni = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ekranIzniIste() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ayar = Ayarlar(this)
        AppCompatDelegate.setDefaultNightMode(
            if (ayar.geceModu) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(arayuz())
    }

    override fun onResume() {
        super.onResume()
        Durum.dinleyici = { s -> durumYazi.text = s; dugmeyiGuncelle() }
        Durum.sonCeviriDinleyici = { org, tr ->
            sonYazi.text = if (tr.isBlank()) "(altyazı yok)" else "EN: $org\nTR: $tr"
        }
        dugmeyiGuncelle()
    }

    override fun onPause() {
        super.onPause()
        Durum.dinleyici = null
        Durum.sonCeviriDinleyici = null
    }

    override fun onDestroy() {
        tv.kapat()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun arayuz(): View {
        val kok = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
        }

        // Üst başlık: ad + sürüm, gece/gündüz, paylaş
        val baslik = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        baslik.addView(TextView(this).apply {
            text = "Altyazı"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        baslik.addView(TextView(this).apply {
            text = "  v${BuildConfig.VERSION_NAME}"
            textSize = 13f
            alpha = 0.7f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        baslik.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = if (ayar.geceModu) "☀" else "☾"
            textSize = 18f
            contentDescription = "Gece/gündüz modu"
            setOnClickListener {
                ayar.geceModu = !ayar.geceModu
                AppCompatDelegate.setDefaultNightMode(
                    if (ayar.geceModu) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        })
        baslik.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "⇪"
            textSize = 18f
            contentDescription = "Paylaş"
            setOnClickListener { paylas() }
        })
        kok.addView(baslik)

        kok.addView(TextView(this).apply {
            text = "Xbox Remote Play görüntüsündeki İngilizce altyazıyı okur, Türkçeye çevirir ve TV'ye gönderir."
            alpha = 0.8f
            setPadding(0, dp(4), 0, dp(14))
        })

        // TV adresi
        kok.addView(etiket("TV adresi (IP veya IP:port)"))
        tvAlan = EditText(this).apply {
            setText(ayar.tvAdresi)
            hint = "örn. 192.168.1.50  (boş = sadece telefonda göster)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        kok.addView(tvAlan)
        kok.addView(Button(this).apply {
            text = "TV'ye test altyazısı gönder"
            setOnClickListener {
                ayar.tvAdresi = tvAlan.text.toString()
                if (ayar.tvAdresi.isBlank()) { kisa("Önce TV adresini yaz."); return@setOnClickListener }
                durumYazi.text = "Test gönderiliyor…"
                tv.gonder(ayar.tvAdresi, "Test altyazı: Merhaba! (Altyazı v${BuildConfig.VERSION_NAME})", "Test") { ok, bilgi ->
                    runOnUiThread { durumYazi.text = if (ok) "TV yanıt verdi ✓" else "TV'ye ulaşılamadı: $bilgi" }
                }
            }
        })

        kok.addView(MaterialSwitch(this).apply {
            text = "Çeviriyi telefonda da göster"
            isChecked = ayar.telefondaGoster
            setOnCheckedChangeListener { _, b -> ayar.telefondaGoster = b }
            setPadding(0, dp(10), 0, dp(4))
        })

        kok.addView(etiket("Okuma aralığı"))
        val aralikSatir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(300L to "Hızlı", 450L to "Normal", 800L to "Pil dostu").forEach { (ms, ad) ->
            aralikSatir.addView(Button(this).apply {
                text = ad
                alpha = if (ayar.aralikMs == ms) 1f else 0.55f
                setOnClickListener {
                    ayar.aralikMs = ms
                    for (i in 0 until aralikSatir.childCount) aralikSatir.getChildAt(i).alpha = 0.55f
                    alpha = 1f
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        kok.addView(aralikSatir)

        baslatDugme = MaterialButton(this).apply {
            textSize = 17f
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { baslatDurdur() }
        }
        kok.addView(baslatDugme, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        durumYazi = TextView(this).apply {
            text = "Hazır."
            setPadding(0, dp(10), 0, dp(6))
        }
        kok.addView(durumYazi)

        sonYazi = TextView(this).apply {
            text = ""
            textSize = 15f
            setPadding(0, dp(6), 0, dp(12))
        }
        kok.addView(sonYazi)

        kok.addView(TextView(this).apply {
            text = "Kullanım:\n1) TV adresini yaz (TV uygulaması açık olmalı).\n" +
                "2) Başlat → ekran kaydı iznini ver.\n" +
                "3) Xbox uygulamasında Remote Play'i aç.\n" +
                "4) Sağ üstteki \"Bölge\" ile altyazı alanını bir kez çiz.\n" +
                "İlk çalıştırmada çeviri modeli (~30 MB) iner; sonra internetsiz çalışır."
            alpha = 0.75f
            textSize = 13f
        })

        kok.addView(TextView(this).apply {
            text = "© Mehmet Karadeniz, 2026-2027"
            alpha = 0.5f
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        return ScrollView(this).apply { addView(kok) }
    }

    private fun etiket(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        alpha = 0.7f
        setPadding(0, dp(8), 0, 0)
    }

    private fun dugmeyiGuncelle() {
        baslatDugme.text = if (Durum.calisiyor) "Durdur" else "Başlat"
    }

    private fun baslatDurdur() {
        ayar.tvAdresi = tvAlan.text.toString()
        if (Durum.calisiyor) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.EYLEM_DURDUR))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            kisa("\"Diğer uygulamaların üzerinde göster\" iznini aç, sonra geri dön.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            bildirimIzni.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        ekranIzniIste()
    }

    private fun ekranIzniIste() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val niyet = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        ekranIzni.launch(niyet)
    }

    private fun paylas() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Altyazı – Xbox altyazılarını Türkçeye çeviren uygulama:\nhttps://github.com/karadenizmehmet-debug/Xbx/releases"
            )
        }
        startActivity(Intent.createChooser(i, "Paylaş"))
    }

    private fun kisa(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
