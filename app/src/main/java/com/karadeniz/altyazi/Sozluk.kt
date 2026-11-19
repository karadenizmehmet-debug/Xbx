package com.karadeniz.altyazi

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Sabit çeviri sözlüğü: oyundaki hep aynı yazılar ("Health", "Stones"…) bir kez çevrilir, sonra
 * çevirmene sorulmadan buradan gelir. Kullanıcı Ayarlar > Sözlük'ten düzeltebilir.
 */
data class SozlukGiris(
    val anahtar: String,
    /** Listede görünen İngilizce; sayılar '#' (örn. "Stones: #"). */
    val orijinal: String,
    /** Türkçe şablon; '#' = orijinaldeki sayının yeri. */
    val tr: String,
    /** true = kullanıcı yazdı/düzeltti; otomatik temizlikte silinmez. */
    val elle: Boolean,
    val sayac: Int,
    val son: Long
)

/** Android'den bağımsız sözlük kuralları (birim testli). */
object SozlukMantik {
    private val sayiKalibi = Regex("\\d+")

    /** Eşleşme anahtarı: küçük harf, noktalama yok, sayılar '#'. "STONES: 12" ve "Stones: 7" aynı anahtar. */
    fun anahtar(metin: String): String =
        metin.lowercase(Locale.ENGLISH)
            .replace(sayiKalibi, "#")
            .replace(Regex("[^\\p{L}#]+"), " ")
            .trim()

    fun sayilar(metin: String): List<String> = sayiKalibi.findAll(metin).map { it.value }.toList()

    fun gosterimMetni(orijinal: String): String = orijinal.replace(sayiKalibi, "#").replace(Regex("\\s+"), " ").trim()

    /** Otomatik kaydedilecek kadar kısa mı? (Uzun diyalog satırları sözlüğe girmez.) */
    fun otomatikUygunMu(metin: String): Boolean {
        val kelime = Regex("\\p{L}{2,}").findAll(metin).count()
        val harf = metin.count { it.isLetter() }
        return kelime in 1..5 && metin.length <= 40 && harf >= 3
    }

    /** Çeviriyi şablona çevir: içindeki sayılar '#' olur. Sayı adedi orijinalle tutmazsa null (kaydetme). */
    fun sablonYap(orijinal: String, tr: String): String? {
        val n = sayilar(orijinal).size
        if (n == 0) return tr
        if (sayilar(tr).size != n) return null
        return tr.replace(sayiKalibi, "#")
    }

    /** Şablondaki '#' yerlerine orijinaldeki sayıları sırayla koy. Yetmezse null (sözlük kullanılmaz). */
    fun uygula(sablon: String, orijinal: String): String? {
        val yer = sablon.count { it == '#' }
        if (yer == 0) return sablon
        val s = sayilar(orijinal)
        if (s.size < yer) return null
        var i = 0
        return sablon.map { if (it == '#') s[i++] else it.toString() }.joinToString("")
    }
}

class Sozluk private constructor(ctx: Context) {
    companion object {
        private const val EN_FAZLA_OTOMATIK = 500
        @Volatile private var ornek: Sozluk? = null

        fun al(ctx: Context): Sozluk =
            ornek ?: synchronized(this) { ornek ?: Sozluk(ctx.applicationContext).also { ornek = it } }
    }

    private val dosya = File(ctx.filesDir, "sozluk.json")
    private val harita = LinkedHashMap<String, SozlukGiris>()
    private val ana = Handler(Looper.getMainLooper())
    private val yazGorevi = Runnable { yazsimdi() }
    private var kirli = false

    /** Kullanıcı düzenlemesinde artar; çağıranlar kendi önbelleğini boşaltsın diye. */
    @Volatile var surum = 0
        private set

    init { yukle() }

    /** Orijinal için kayıtlı çeviri (sayılar yerine konmuş), yoksa null. */
    @Synchronized fun bul(orijinal: String): String? {
        val k = SozlukMantik.anahtar(orijinal)
        val g = harita[k] ?: return null
        val sonuc = SozlukMantik.uygula(g.tr, orijinal) ?: return null
        harita[k] = g.copy(sayac = g.sayac + 1, son = System.currentTimeMillis())
        return sonuc
    }

    /** Hattın ürettiği çeviriyi sakla (kısa yazılar). Kullanıcının düzelttiğini ezmez. */
    @Synchronized fun otomatikKaydet(orijinal: String, tr: String) {
        if (!SozlukMantik.otomatikUygunMu(orijinal)) return
        val k = SozlukMantik.anahtar(orijinal)
        if (k.isEmpty() || harita.containsKey(k)) return
        val sablon = SozlukMantik.sablonYap(orijinal, tr) ?: return
        harita[k] = SozlukGiris(k, SozlukMantik.gosterimMetni(orijinal), sablon, false, 1, System.currentTimeMillis())
        budaOtomatik()
        planla()
    }

    /** Kullanıcı ekler/düzeltir. */
    @Synchronized fun elleKaydet(orijinal: String, tr: String) {
        val k = SozlukMantik.anahtar(orijinal)
        if (k.isEmpty() || tr.isBlank()) return
        val eski = harita[k]
        harita[k] = SozlukGiris(
            k, SozlukMantik.gosterimMetni(orijinal), tr.trim(), true, eski?.sayac ?: 0, System.currentTimeMillis()
        )
        degisti()
    }

    @Synchronized fun sil(anahtar: String) { if (harita.remove(anahtar) != null) degisti() }

    /** Otomatik kaydedilenleri sil; elle yazılanlar kalır. */
    @Synchronized fun otomatikleriTemizle() {
        if (harita.values.removeAll { !it.elle }) degisti()
    }

    /** Elle yazılanlar üstte, sonra sık kullanılanlar. */
    @Synchronized fun liste(): List<SozlukGiris> =
        harita.values.sortedWith(compareByDescending<SozlukGiris> { it.elle }.thenByDescending { it.sayac }.thenBy { it.orijinal })

    @Synchronized fun boyut(): Int = harita.size

    private fun budaOtomatik() {
        val otomatik = harita.values.filter { !it.elle }
        if (otomatik.size <= EN_FAZLA_OTOMATIK) return
        otomatik.sortedWith(compareBy<SozlukGiris> { it.sayac }.thenBy { it.son })
            .take(otomatik.size - EN_FAZLA_OTOMATIK).forEach { harita.remove(it.anahtar) }
    }

    private fun degisti() { surum++; planla() }

    private fun planla() {
        kirli = true
        ana.removeCallbacks(yazGorevi)
        ana.postDelayed(yazGorevi, 1500)
    }

    @Synchronized private fun yazsimdi() {
        if (!kirli) return
        kirli = false
        try {
            val dizi = JSONArray()
            harita.values.forEach {
                dizi.put(
                    JSONObject().put("k", it.anahtar).put("o", it.orijinal).put("t", it.tr)
                        .put("e", it.elle).put("n", it.sayac).put("s", it.son)
                )
            }
            val gecici = File(dosya.parentFile, "sozluk.json.tmp")
            gecici.writeText(dizi.toString(), Charsets.UTF_8)
            if (!gecici.renameTo(dosya)) { dosya.writeText(dizi.toString(), Charsets.UTF_8); gecici.delete() }
        } catch (_: Exception) { }
    }

    private fun yukle() {
        try {
            if (!dosya.exists()) return
            val dizi = JSONArray(dosya.readText(Charsets.UTF_8))
            for (i in 0 until dizi.length()) {
                val o = dizi.optJSONObject(i) ?: continue
                val k = o.optString("k"); val t = o.optString("t")
                if (k.isEmpty() || t.isBlank()) continue
                harita[k] = SozlukGiris(k, o.optString("o", k), t, o.optBoolean("e"), o.optInt("n", 1), o.optLong("s"))
            }
        } catch (_: Exception) { harita.clear() }
    }
}
