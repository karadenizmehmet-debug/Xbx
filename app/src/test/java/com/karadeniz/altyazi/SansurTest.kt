package com.karadeniz.altyazi

import org.junit.Assert.assertEquals
import org.junit.Test

class SansurTest {
    @Test fun turkceKufurleriYildizlar() {
        assertEquals("S***** git!", Sansur.temizle("Siktir git!"))
        assertEquals("a**** koyayım", Sansur.temizle("amına koyayım"))
        assertEquals("Seni o***** çocuğu", Sansur.temizle("Seni orospu çocuğu"))
    }

    @Test fun masumKelimelerDegismez() {
        val s = "Sikke götürmek amaç boks ananas dikkat"
        assertEquals(s, Sansur.temizle(s))
    }

    @Test fun ingilizceKufurleriYildizlar() {
        assertEquals("F****** hell, s***", Sansur.temizle("Fucking hell, shit"))
    }
}
