package com.karadeniz.altyazi

/** Android'e bağlı olmayan metin işlemleri (birim testli). */
object MetinIsleme {

    fun normalize(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** 0..1 arası benzerlik (Levenshtein). */
    fun benzerlik(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return if (a == b) 1.0 else 0.0
        if (a == b) return 1.0
        val n = a.length; val m = b.length
        var onceki = IntArray(m + 1) { it }
        var simdi = IntArray(m + 1)
        for (i in 1..n) {
            simdi[0] = i
            for (j in 1..m) {
                val k = if (a[i - 1] == b[j - 1]) 0 else 1
                simdi[j] = minOf(onceki[j] + 1, simdi[j - 1] + 1, onceki[j - 1] + k)
            }
            val t = onceki; onceki = simdi; simdi = t
        }
        return 1.0 - onceki[m].toDouble() / maxOf(n, m)
    }

    /**
     * OCR satırlarını tek altyazıya çevirir: altyazı olmayan tek harflik parçaları atar.
     * (Remote Play'in dokunmatik A/B/X/Y düğmeleri "A" okunup "bir" diye çevriliyordu.)
     */
    fun parcalariTemizle(satirlar: List<String>): String {
        val parcalar = satirlar
            .filter { s -> s.count { it.isLetter() } >= 2 }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ").trim()
            .split(" ").filter { it.isNotEmpty() }.toMutableList()
        fun artik(t: String) = t.length == 1 && t[0].isLetterOrDigit() && t != "I"
        while (parcalar.isNotEmpty() && artik(parcalar.last())) parcalar.removeAt(parcalar.size - 1)
        while (parcalar.isNotEmpty() && parcalar.first().length == 1 && parcalar.first()[0] in "BXYbxy")
            parcalar.removeAt(0)
        return parcalar.joinToString(" ")
    }
}

sealed class Karar {
    object Bekle : Karar()
    object Temizle : Karar()
    data class Cevir(val metin: String) : Karar()
}

/** Ekrandaki altyazının değişip değişmediğini izler; ne zaman çevrilip ne zaman silineceğine karar verir. */
class KararlilikTakip(private val bosTemizlemeMs: Long = 1300L) {
    private var sonGonderilen = ""      // normalize edilmiş, en son çevrilen metin
    private var aday = ""               // kararlılık bekleyen metin
    private var adaySayac = 0
    private var bosSure = 0L
    private var ekrandaMetinVar = false

    fun gelen(metin: String, aralikMs: Long): Karar {
        val n = MetinIsleme.normalize(metin)
        if (n.length < 3) {
            bosSure += aralikMs
            aday = ""; adaySayac = 0
            if (ekrandaMetinVar && bosSure >= bosTemizlemeMs) {
                ekrandaMetinVar = false
                sonGonderilen = ""
                return Karar.Temizle
            }
            return Karar.Bekle
        }
        bosSure = 0
        if (MetinIsleme.benzerlik(n, sonGonderilen) >= 0.82) return Karar.Bekle   // aynı altyazı hâlâ ekranda

        // Yazı beliriyor/değişiyorsa bir kare daha bekle (kararlı olsun)
        if (MetinIsleme.benzerlik(n, aday) >= 0.9) adaySayac++ else { aday = n; adaySayac = 1 }
        if (adaySayac < 2) return Karar.Bekle

        sonGonderilen = n
        aday = ""; adaySayac = 0
        return Karar.Cevir(metin)
    }

    /** Çeviri ekrana yazıldı: boş kalırsa sonra silinecek. */
    fun ceviriGosterildi() { ekrandaMetinVar = true }
}
