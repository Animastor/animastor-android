package com.example.animastor.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Item 17: the backend mints no guest any more (owner, step 5) — the old
 * `animastor_gid` cookie is never stored or sent by PersistentCookieJar.
 */
class GuestCookieTest {
    private val url = "https://animastor.org/api/v1/book/b1".toHttpUrl()
    private fun cookie(name: String) = Cookie.parse(url, "$name=v; Path=/; Max-Age=3600")!!

    @Test fun `the guest cookie is dropped, the session cookie stays`() {
        val kept = PersistentCookieJar.withoutGuestCookie(listOf(cookie("animastor_sid"), cookie("animastor_gid"), cookie("other")))
        assertEquals(listOf("animastor_sid", "other"), kept.map { it.name })
        assertEquals("animastor_gid", PersistentCookieJar.GUEST_COOKIE)
    }

    @Test fun `the jar filters on load, on save and on send`() {
        val src = java.io.File("src/main/java/com/example/animastor/network/PersistentCookieJar.kt").readText()
        assertEquals(3, Regex("withoutGuestCookie\\(").findAll(src.substringBefore("companion object")).count())
    }
}
