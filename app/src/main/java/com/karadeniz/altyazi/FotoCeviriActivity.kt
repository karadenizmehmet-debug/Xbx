package com.karadeniz.altyazi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.material.R as MR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

/**
 * Fotoğraftan çeviri: Remote Play başlatmadan, telefon kamerasıyla TV/monitör ekranının fotoğrafı
 * çekilir; yazılar okunur (ML Kit OCR), Türkçeye çevrilir ve telefonda gösterilir.
 * Kamera sistemin kamera uygulamasıyla açılır (CAMERA izni gerekmez).
 */
class FotoCeviriActivity : AppCompatActivity() {

    private class Islem(val ciftler: List<Pair<String, String>>, val mesaj: String)

    private lateinit var ayar: Ayarlar
    private lateinit var dosya: File
    private lateinit var fotoGorunum: ImageView
    private lateinit var durumYazi: TextView
    private lateinit var liste: LinearLayout
    private lateinit var cekDugme: MaterialButton
    private lateinit var kopyaDugme: MaterialButton

    private val isci = Executors.newSingleThreadExecutor()
    private val cevrimici = CevrimiciCeviri()
    private var tanima: TextRecognizer? = null
    private var cevirmen: Translator? = null
    private var modelHazir = false

    /** Son sonuç: (orijinal, çeviri). */
    private var sonuclar: List<Pair<String, String>> = emptyList()
    private var islemNo = 0
    private var sonucVar = false

    private val kameraSonucu = registerForActivityResult(ActivityResultContracts.TakePicture()) { cekildi ->
        if (cekildi) isle() else if (!sonucVar) finish()   // ilk çekimden vazgeçildi: ana ekrana dön
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ayar = Ayarlar(this)
        dosya = File(File(cacheDir, "foto").apply { mkdirs() }, "foto.jpg")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(arayuz())
        if (savedInstanceState == null) fotografCek()
    }

    override fun onDestroy() {
        islemNo++
        // Çalışan iş bitince OCR/çeviri istemcileri kapanır
        isci.execute { tanima?.close(); cevirmen?.close(); tanima = null; cevirmen = null }
        isci.shutdown()
        super.onDestroy()
    }

    // ---------- Küçük yardımcılar ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun renk(attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

    private fun yazi(s: String, sp: Float = 14f, kalin: Boolean = false, soluk: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(renk(if (soluk) MR.attr.colorOnSurfaceVariant else MR.attr.colorOnSurface))
            if (kalin) setTypeface(typeface, Typeface.BOLD)
        }

    private fun yuvarlak(renk: Int, yaricap: Int) =
        GradientDrawable().apply { cornerRadius = dp(yaricap).toFloat(); setColor(renk) }

    // ---------- Ekran ----------

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
        ust.addView(yazi("Fotoğraf çevir", 24f, kalin = true).apply { setPadding(dp(14), 0, 0, 0) })
        kok.addView(ust)
        kok.addView(
            yazi("Ekranın fotoğrafını çek; yazılar okunup Türkçeye çevrilir. Remote Play başlatmana gerek yok.", 13f, soluk = true)
                .apply { setPadding(dp(2), dp(8), dp(2), dp(10)) }
        )

        cekDugme = MaterialButton(this).apply {
            text = "📷  Fotoğraf çek"
            textSize = 16f
            isAllCaps = false
            cornerRadius = dp(18)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { fotografCek() }
        }
        kok.addView(cekDugme, LinearLayout.LayoutParams(-1, dp(52)))

        fotoGorunum = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = yuvarlak(renk(MR.attr.colorSurfaceVariant), 16)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            visibility = View.GONE
        }
        kok.addView(fotoGorunum, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(12) })

        durumYazi = yazi("Kamera açılıyor…", 14f, soluk = true).apply { setPadding(dp(4), dp(12), dp(4), dp(8)) }
        kok.addView(durumYazi)

        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        kok.addView(liste)

        kopyaDugme = MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = "Çevirileri kopyala"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { kopyala() }
        }
        kok.addView(kopyaDugme)

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

    private fun satirKarti(orijinal: String, tr: String): View {
        val ic = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        if (tr.isBlank()) ic.addView(yazi("(çevrilemedi)", 15f, soluk = true))
        else ic.addView(yazi(tr, 16f, kalin = true).apply { setTextIsSelectable(true) })
        ic.addView(yazi(orijinal, 12f, soluk = true).apply { setPadding(0, dp(4), 0, 0) })
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

    private fun durum(s: String) { durumYazi.text = s }

    private fun kopyala() {
        val metin = sonuclar.joinToString("\n\n") { (org, tr) -> tr.ifBlank { org } }
        val pano = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        pano.setPrimaryClip(ClipData.newPlainText("Çeviri", metin))
        Toast.makeText(this, "Kopyalandı", Toast.LENGTH_SHORT).show()
    }

    // ---------- Çekim ve işleme ----------

    private fun fotografCek() {
        try {
            kameraSonucu.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", dosya))
        } catch (e: Exception) {
            durum("Kamera açılamadı: ${e.message}")
        }
    }

    private fun isle() {
        val no = ++islemNo
        sonucVar = true
        sonuclar = emptyList()
        liste.removeAllViews()
        kopyaDugme.visibility = View.GONE
        cekDugme.isEnabled = false
        durum("Fotoğraf okunuyor…")
        isci.execute {
            val islem = try { calis(no) } catch (e: Exception) { Islem(emptyList(), "Hata: ${e.message}") }
            runOnUiThread { if (!isDestroyed && no == islemNo) bitir(islem) }
        }
    }

    private fun bitir(islem: Islem) {
        cekDugme.isEnabled = true
        durum(islem.mesaj)
        sonuclar = islem.ciftler
        liste.removeAllViews()
        islem.ciftler.forEach { (org, tr) -> liste.addView(satirKarti(org, tr)) }
        kopyaDugme.visibility = if (islem.ciftler.any { it.second.isNotBlank() }) View.VISIBLE else View.GONE
    }

    private fun arayuzde(no: Int, isi: () -> Unit) = runOnUiThread { if (!isDestroyed && no == islemNo) isi() }

    /** İşçi iş parçacığında: fotoğrafı yükle → OCR → çevir. */
    private fun calis(no: Int): Islem {
        val resim = fotoYukle() ?: return Islem(emptyList(), "Fotoğraf açılamadı. Tekrar çek.")
        arayuzde(no) { fotoGorunum.setImageBitmap(resim); fotoGorunum.visibility = View.VISIBLE }

        val okuyucu = tanima ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { tanima = it }
        val sonuc = Tasks.await(okuyucu.process(InputImage.fromBitmap(resim, 0)))
        val metinler = okumaSirasi(sonuc.textBlocks)
        if (metinler.isEmpty()) return Islem(emptyList(), "Yazı bulunamadı. Ekranı düz ve net tutup tekrar çek.")

        arayuzde(no) { durum("${metinler.size} yazı bulundu, çevriliyor…") }
        val tr = cevirTumu(metinler, no)
        val ciftler = metinler.indices.map { i ->
            val t = tr[i].orEmpty()
            if (ayar.sansur) Pair(Sansur.temizle(metinler[i]), Sansur.temizle(t)) else Pair(metinler[i], t)
        }
        val eksik = ciftler.count { it.second.isBlank() }
        val mesaj = if (eksik == 0) "${ciftler.size} yazı çevrildi."
        else "$eksik yazı çevrilemedi (internet yok ve çeviri modeli inmemiş olabilir)."
        return Islem(ciftler, mesaj)
    }

    /** Blokları yukarıdan aşağı, aynı bantta soldan sağa sıralar; çöp blokları (moiré, tek harf) eler. */
    private fun okumaSirasi(bloklar: List<Text.TextBlock>): List<String> {
        val yukseklikler = bloklar.mapNotNull { it.boundingBox?.height() }.sorted()
        val bant = (if (yukseklikler.isEmpty()) 1 else yukseklikler[yukseklikler.size / 2]).coerceAtLeast(1)
        return bloklar
            .sortedWith(
                compareBy<Text.TextBlock> { (it.boundingBox?.top ?: 0) / bant }.thenBy { it.boundingBox?.left ?: 0 }
            )
            .map { b -> b.lines.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim() }
            .filter { FotoCeviri.uygunMu(it) }
    }

    /** Sözlük → çevrimiçi → cihazdaki ML Kit sırasıyla çevirir. Çevrilemeyen yer null kalır. */
    private fun cevirTumu(metinler: List<String>, no: Int): Array<String?> {
        val sonuc = arrayOfNulls<String>(metinler.size)
        val sozluk = Sozluk.al(this)
        val sorulacak = mutableListOf<Int>()
        metinler.forEachIndexed { i, m ->
            val kayitli = if (ayar.sozlukAcik) sozluk.bul(m) else null
            if (kayitli != null) sonuc[i] = CeviriBicim.geriBuyut(m, kayitli) else sorulacak.add(i)
        }
        if (ayar.cevrimici) cevrimiciCevir(metinler, sorulacak, sonuc)
        sorulacak.filter { sonuc[it] == null }.forEach { i ->
            val t = yerelCevir(CeviriBicim.duzelt(metinler[i]), no)
            if (t != null) sonuc[i] = CeviriBicim.geriBuyut(metinler[i], t)
        }
        return sonuc
    }

    private fun cevrimiciCevir(metinler: List<String>, sorulacak: List<Int>, sonuc: Array<String?>) {
        val girdiler = sorulacak.map { CeviriBicim.duzelt(metinler[it]) }
        for (grup in FotoCeviri.grupla(girdiler.map { it.length }, 1500)) {
            val parca = grup.map { girdiler[it] }
            // Ağ hatası: kalanları boşuna bekleme, yerel çeviriye düş
            val toplu = try { cevrimici.cevirToplu(parca) } catch (_: Exception) { return }
            grup.forEachIndexed { k, g ->
                val t = toplu?.get(k)
                    ?: if (parca.size > 1) (try { cevrimici.cevir(parca[k]) } catch (_: Exception) { null }) else null
                if (t != null) {
                    val i = sorulacak[g]
                    sonuc[i] = CeviriBicim.geriBuyut(metinler[i], t)
                }
            }
        }
    }

    private fun yerelCevir(metin: String, no: Int): String? = try {
        val c = cevirmen ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
        ).also { cevirmen = it }
        if (!modelHazir) {
            arayuzde(no) { durum("Çeviri modeli hazırlanıyor (ilk seferde ~30 MB iner)…") }
            Tasks.await(c.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            modelHazir = true
        }
        Tasks.await(c.translate(metin))
    } catch (_: Exception) {
        null
    }

    /** Fotoğrafı yükler: uzun kenarı 4096'ya sığdırır, EXIF yönüne göre döndürür. */
    private fun fotoYukle(): Bitmap? {
        val sinir = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(dosya.path, sinir)
        if (sinir.outWidth <= 0 || sinir.outHeight <= 0) return null
        val secenek = BitmapFactory.Options().apply {
            inSampleSize = FotoCeviri.ornekleme(sinir.outWidth, sinir.outHeight, 4096)
        }
        val ham = BitmapFactory.decodeFile(dosya.path, secenek) ?: return null
        val derece = when (
            ExifInterface(dosya.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (derece == 0f) return ham
        val donmus = Bitmap.createBitmap(ham, 0, 0, ham.width, ham.height, Matrix().apply { postRotate(derece) }, true)
        if (donmus !== ham) ham.recycle()
        return donmus
    }
}
