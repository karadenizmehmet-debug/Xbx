package com.karadeniz.altyazi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FotoCeviriTest {
    @Test fun ornekleme_kucukResimdeBir() = assertEquals(1, FotoCeviri.ornekleme(4000, 3000, 4096))
    @Test fun ornekleme_buyukResimdeIkiKuvveti() = assertEquals(2, FotoCeviri.ornekleme(8160, 6120, 4096))
    @Test fun ornekleme_dikResim() = assertEquals(2, FotoCeviri.ornekleme(3000, 8000, 4096))

    @Test fun uygunMu_normalYazi() = assertTrue(FotoCeviri.uygunMu("Press A to continue"))
    @Test fun uygunMu_tekHarf() = assertFalse(FotoCeviri.uygunMu("A"))
    @Test fun uygunMu_sayiYigini() = assertFalse(FotoCeviri.uygunMu("100 / 250 | 33 | 7 x"))
    @Test fun uygunMu_bos() = assertFalse(FotoCeviri.uygunMu("   "))

    @Test fun grupla_bos() = assertEquals(emptyList<List<Int>>(), FotoCeviri.grupla(emptyList(), 100))
    @Test fun grupla_sigarsaTekGrup() = assertEquals(listOf(listOf(0, 1, 2)), FotoCeviri.grupla(listOf(10, 20, 30), 100))
    @Test fun grupla_tasarsaBoler() =
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), FotoCeviri.grupla(listOf(40, 50, 60, 30), 100))
    @Test fun grupla_uzunMetinKendiGrubunda() =
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), FotoCeviri.grupla(listOf(10, 500, 10), 100))
}
