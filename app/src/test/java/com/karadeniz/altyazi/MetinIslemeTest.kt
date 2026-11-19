package com.karadeniz.altyazi

import org.junit.Assert.assertEquals
import org.junit.Test

class MetinIslemeTest {

    @Test fun normalize_harfDisiniAtar() {
        assertEquals("helloworld", MetinIsleme.normalize("Hello, World!"))
    }

    @Test fun benzerlik_sinirlar() {
        assertEquals(1.0, MetinIsleme.benzerlik("abc", "abc"), 0.0)
        assertEquals(0.0, MetinIsleme.benzerlik("", "x"), 0.0)
    }

    @Test fun parcalariTemizle_dugmeHarfleriniAtar() {
        assertEquals("Where are you going?", MetinIsleme.parcalariTemizle(listOf("B Where are you going? A")))
        assertEquals("Run!", MetinIsleme.parcalariTemizle(listOf("X Run!")))
        assertEquals("", MetinIsleme.parcalariTemizle(listOf("I")))
    }

    @Test fun kararlilik_ikinciKaredeCevirir() {
        val t = KararlilikTakip()
        assertEquals(Karar.Bekle, t.gelen("Hello there", 400))
        assertEquals(Karar.Cevir("Hello there"), t.gelen("Hello there", 400))
        t.ceviriGosterildi()
        assertEquals(Karar.Bekle, t.gelen("Hello there", 400))
    }

    @Test fun kararlilik_bosKaldigindaBirKezTemizler() {
        val t = KararlilikTakip()
        t.gelen("Hello there", 400); t.gelen("Hello there", 400)
        t.ceviriGosterildi()
        assertEquals(Karar.Bekle, t.gelen("", 400))
        assertEquals(Karar.Bekle, t.gelen("", 400))
        assertEquals(Karar.Bekle, t.gelen("", 400))
        assertEquals(Karar.Temizle, t.gelen("", 400))
        assertEquals(Karar.Bekle, t.gelen("", 400))
    }

    @Test fun kararlilik_gosterimYoksaTemizlemez() {
        val t = KararlilikTakip()
        repeat(10) { assertEquals(Karar.Bekle, t.gelen("", 400)) }
    }
}
