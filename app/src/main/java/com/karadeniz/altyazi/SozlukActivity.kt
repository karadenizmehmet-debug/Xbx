package com.karadeniz.altyazi

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

/**
 * Sözlük: oyundaki hep aynı yazıların ("Health", "Stones"…) kayıtlı çevirileri.
 * Yanlış olanı düzelt, gereksizi sil, kendin ekle. Düzeltilenler otomatik temizlikte silinmez.
 */
class SozlukActivity : AppCompatActivity() {

    private lateinit var sozluk: Sozluk
    private lateinit var ayar: Ayarlar
    private lateinit var liste: LinearLayout
    private lateinit var bilgi: TextView
    private var arama = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ayar = Ayarlar(this)
        sozluk = Sozluk.al(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(arayuz())
        yenile()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun renk(attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

    private fun yazi(s: String, sp: Float = 14f, kalin: Boolean = false, soluk: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(renk(if (soluk) MR.attr.colorOnSurfaceVariant else MR.attr.colorOnSurface))
            if (kalin) setTypeface(typeface, Typeface.BOLD)
        }

    private fun arayuz(): View {
        val kok = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val ust = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ust.addView(MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = "‹ Geri"
            setOnClickListener { finish() }
        })
        ust.addView(yazi("Sözlük", 24f, kalin = true).apply { setPadding(dp(14), 0, 0, 0) })
        kok.addView(ust)
        kok.addView(
            yazi(
                "Oyundaki hep aynı yazılar (Health, Stones…) bir kez çevrilir, sonra çevirmene sorulmadan buradan gelir. " +
                    "Yanlış olanı dokunup düzelt. Sayı yerine # yaz (Stones: # → Taşlar: #).", 13f, soluk = true
            ).apply { setPadding(dp(2), dp(8), dp(2), dp(10)) }
        )
        val ara = EditText(this).apply {
            hint = "Ara…"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { arama = s?.toString().orEmpty().trim(); yenile() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        kok.addView(ara)
        val dugmeler = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        dugmeler.addView(MaterialButton(this).apply {
            text = "+ Ekle"
            setOnClickListener { duzenle(null) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        dugmeler.addView(MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = "Otomatikleri temizle"
            setOnClickListener { temizleSor() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        kok.addView(dugmeler)
        bilgi = yazi("", 12f, soluk = true).apply { setPadding(dp(2), 0, 0, dp(6)) }
        kok.addView(bilgi)
        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        kok.addView(liste)
        kok.addView(
            yazi("© Mehmet Karadeniz, 2026-2027", 12f, soluk = true).apply {
                gravity = Gravity.CENTER; alpha = 0.7f; setPadding(0, dp(12), 0, 0)
            }
        )
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setBackgroundColor(renk(android.R.attr.colorBackground))
            addView(kok)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, ins ->
                val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                ins
            }
        }
    }

    private fun yenile() {
        liste.removeAllViews()
        val hepsi = sozluk.liste()
        val q = arama.lowercase()
        val gorunen = if (q.isEmpty()) hepsi else hepsi.filter { it.orijinal.lowercase().contains(q) || it.tr.lowercase().contains(q) }
        val elle = hepsi.count { it.elle }
        bilgi.text = "${hepsi.size} kayıt (${elle} düzeltilen, ${hepsi.size - elle} otomatik)"
        if (gorunen.isEmpty()) {
            liste.addView(
                yazi(
                    if (hepsi.isEmpty()) "Henüz kayıt yok. Telefon/Bildirim alanları çalışınca kısa yazılar burada birikir."
                    else "Eşleşen kayıt yok.", 14f, soluk = true
                ).apply { setPadding(dp(4), dp(16), dp(4), dp(16)) }
            )
            return
        }
        gorunen.forEach { liste.addView(satir(it)) }
    }

    private fun satir(g: SozlukGiris): View {
        val metin = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(yazi(g.orijinal, 13f, soluk = true))
            addView(yazi((if (g.elle) "✎ " else "") + g.tr, 16f, kalin = g.elle).apply { setPadding(0, dp(2), 0, 0) })
        }
        val sil = TextView(this).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(renk(MR.attr.colorOnSurfaceVariant))
            contentDescription = "Sil"
            setOnClickListener { sozluk.sil(g.anahtar); yenile() }
        }
        val ic = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(6), dp(10))
            addView(metin, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sil, LinearLayout.LayoutParams(dp(44), dp(44)))
            setOnClickListener { duzenle(g) }
        }
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(renk(MR.attr.colorSurface))
            strokeWidth = dp(1)
            strokeColor = renk(MR.attr.colorOutlineVariant)
            addView(ic)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
    }

    /** [g] null ise yeni kayıt. */
    private fun duzenle(g: SozlukGiris?) {
        val kutu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val en = EditText(this).apply {
            hint = "İngilizce (örn. Stones: #)"
            setText(g?.orijinal.orEmpty())
            isEnabled = g == null   // anahtar değişmez; yanlışsa sil, yenisini ekle
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val tr = EditText(this).apply {
            hint = "Türkçe (örn. Taşlar: #)"
            setText(g?.tr.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        kutu.addView(en); kutu.addView(tr)
        AlertDialog.Builder(this)
            .setTitle(if (g == null) "Yeni kayıt" else "Çeviriyi düzelt")
            .setView(kutu)
            .setPositiveButton("Kaydet") { _, _ ->
                val o = en.text.toString().trim(); val t = tr.text.toString().trim()
                if (SozlukMantik.anahtar(o).isEmpty() || t.isEmpty()) return@setPositiveButton
                sozluk.elleKaydet(o, t)
                yenile()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun temizleSor() {
        AlertDialog.Builder(this)
            .setTitle("Otomatik kayıtlar silinsin mi?")
            .setMessage("Kendi düzelttiklerin ve eklediklerin kalır. Silinenler gerekirse yeniden kaydedilir.")
            .setPositiveButton("Sil") { _, _ -> sozluk.otomatikleriTemizle(); yenile() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }
}
