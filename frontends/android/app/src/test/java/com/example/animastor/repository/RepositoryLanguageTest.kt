package com.example.animastor.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryLanguageTest {

    @Test
    fun resolveLanguage_ru() {
        assertEquals("ru", resolveLanguage("ru"))
        assertEquals("ru", resolveLanguage("ru-RU"))
        assertEquals("ru", resolveLanguage("ru_RU"))
    }

    @Test
    fun resolveLanguage_notRu() {
        assertEquals("en", resolveLanguage("en"))
        assertEquals("en", resolveLanguage("en-US"))
        assertEquals("en", resolveLanguage("de"))
        assertEquals("en", resolveLanguage("de-DE"))
    }

    @Test
    fun resolveLanguage_blankDefaultsToEn() {
        assertEquals("en", resolveLanguage(""))
        assertEquals("en", resolveLanguage("   "))
    }
}
