package com.karadeniz.altyazi

/** Fotoğraftan çeviri için Android'den bağımsız yardımcılar (birim testli). */
object FotoCeviri {

    /** BitmapFactory.inSampleSize: uzun kenar [enCokKenar]'ı aşmayacak en küçük 2 kuvveti. */
    fun ornekleme(genislik: Int, yukseklik: Int, enCokKenar: Int): Int {
        var s = 1
        while (maxOf(genislik, yukseklik) / s > enCokKenar) s *= 2
        return s
    }

    /** OCR çöpünü (ekran fotoğrafındaki moiré, tek harfler, sayı yığınları) ele: yeterli harf, çoğunluk harf. */
    fun uygunMu(metin: String): Boolean {
        val bosluksuz = metin.count { !it.isWhitespace() }
        val harf = metin.count { it.isLetter() }
        return harf >= 2 && harf * 2 >= bosluksuz
    }

    /**
     * Metin indekslerini sırayla gruplar; her grubun toplam uzunluğu [enCok]'u aşmaz
     * (çevrimiçi çeviri isteği URL'de gider, çok uzarsa reddedilir). Tek başına uzun metin kendi grubunda kalır.
     */
    fun grupla(uzunluklar: List<Int>, enCok: Int): List<List<Int>> {
        val gruplar = mutableListOf<MutableList<Int>>()
        var toplam = 0
        uzunluklar.forEachIndexed { i, u ->
            if (gruplar.isEmpty() || toplam + u > enCok) { gruplar.add(mutableListOf()); toplam = 0 }
            gruplar.last().add(i)
            toplam += u
        }
        return gruplar
    }
}
