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

    /** Akış kesildi: ekranda çeviri varsa silinmeli (true). Durum sıfırlanır. */
    fun zorlaTemizle(): Boolean {
        val vardi = ekrandaMetinVar
        ekrandaMetinVar = false
        sifirla()
        return vardi
    }

    /** Bölge değişti: aynı altyazı yeniden çevrilebilsin. */
    fun sifirla() { sonGonderilen = ""; aday = ""; adaySayac = 0 }

    /** Çeviri ekrana yazıldı: boş kalırsa sonra silinecek. */
    fun ceviriGosterildi() { ekrandaMetinVar = true }
}

/** Android'den bağımsız dikdörtgen (piksel ya da 0..1 oran). */
data class Oran(val sol: Float, val ust: Float, val sag: Float, val alt: Float)

/** Ekranda seçilen dikdörtgeni (piksel) video karesinin oranına (0..1) çevirir. Video yoksa tüm kare. */
fun MetinIsleme.ekrandanVideoya(secim: Oran, video: Oran): Oran {
    val w = video.sag - video.sol; val h = video.alt - video.ust
    if (w <= 0f || h <= 0f) return Oran(0f, 0f, 1f, 1f)
    fun x(v: Float) = ((v - video.sol) / w).coerceIn(0f, 1f)
    fun y(v: Float) = ((v - video.ust) / h).coerceIn(0f, 1f)
    return Oran(x(secim.sol), y(secim.ust), x(secim.sag), y(secim.alt))
}

/** Video oranındaki bölgeyi ekran pikseline çevirir (bölge seçicinin başlangıç çerçevesi için). */
fun MetinIsleme.videodanEkrana(bolge: Oran, video: Oran): Oran {
    val w = video.sag - video.sol; val h = video.alt - video.ust
    return Oran(video.sol + bolge.sol * w, video.ust + bolge.ust * h, video.sol + bolge.sag * w, video.ust + bolge.alt * h)
}

/**
 * Küfür sansürü: kelimenin ilk harfi kalır, gerisi yıldız olur ("siktir" → "s*****").
 * Kalıplar tam kelimeye uyar; "götürmek", "sikke", "amaç" gibi masum kelimeler değişmez.
 */
object Sansur {
    private val tr = java.util.Locale("tr")

    private val kaliplar = listOf(
        // Türkçe
        "s[iı]k", "s[iı]kt[iı]r\\p{L}*", "s[iı]k[iı]ş\\p{L}*", "s[iı]keyim", "s[iı]ker[iı]m", "s[iı]kim\\p{L}*",
        "s[iı]kt[iı]ğ\\p{L}*", "s[iı]ks[iı]n", "s[iı]kik\\p{L}*",
        "am[iı]na", "am[iı]n[iı]", "amk", "aq", "amc[iı]k\\p{L}*",
        "orospu\\p{L}*", "piç\\p{L}*", "yarra\\p{L}*", "yarak\\p{L}*", "dalyarak\\p{L}*",
        "göt", "götü", "götün", "götüne", "götünü", "götler\\p{L}*", "götveren\\p{L}*", "götlek\\p{L}*",
        "kahpe\\p{L}*", "pezevenk\\p{L}*", "gavat\\p{L}*", "ibne\\p{L}*", "puşt\\p{L}*", "kaltak\\p{L}*",
        "sürtük\\p{L}*", "yavşak\\p{L}*", "şerefsiz\\p{L}*", "anan[iı]", "anan[iı]n", "bok", "boktan", "boku", "bokun",
        // İngilizce (telefonda görünen orijinal satır için)
        "fuck\\p{L}*", "motherfuck\\p{L}*", "shit\\p{L}*", "bullshit\\p{L}*", "bitch\\p{L}*", "asshole\\p{L}*",
        "bastard\\p{L}*", "cunt\\p{L}*", "dick", "dickhead\\p{L}*", "cocksucker\\p{L}*", "whore\\p{L}*",
        "slut\\p{L}*", "pussy", "wanker\\p{L}*"
    ).map { Regex(it) }

    private fun kufurMu(kelime: String): Boolean {
        val k = kelime.lowercase(tr)
        return kaliplar.any { it.matches(k) }
    }

    fun temizle(metin: String): String =
        Regex("\\p{L}+").replace(metin) { m ->
            val w = m.value
            if (kufurMu(w)) w.first() + "*".repeat(w.length - 1) else w
        }
}
