package com.example.animastor.repository

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Мои книги» — the list model (web parity: features/myBooks/myBooksModel.test.ts). */
class MyBooksModelTest {
    private val gson = Gson()
    private fun b(json: String): RecentBook = gson.fromJson(json, RecentBook::class.java)

    @Test fun `GET books entry parses - numeric or string version, ISO or epoch updated_at`() {
        val v4 = b("""{"book_id":"a","vbook_version":4,"updated_at":"2026-11-20T00:00:00Z","updated_at_ms":1795132800000,"last_opened_at":1795200000000,"has_generated_content":true}""")
        assertEquals("4", v4.vbook_version)
        assertEquals("2026-11-20T00:00:00Z", v4.updated_at)
        assertEquals(1795200000000L, v4.last_opened_at)
        assertEquals(true, v4.has_generated_content)
        val legacy = b("""{"book_id":"l","unsupported":true,"vbook_version":"3.1","updated_at":1795107927}""")
        assertTrue(legacy.unsupported)
        assertEquals("3.1", legacy.vbook_version)
        assertEquals("1795107927", legacy.updated_at)
        // an old server without the new fields
        val old = b("""{"book_id":"o","updated_at":0}""")
        assertNull(old.last_opened_at)
        assertNull(old.has_generated_content)
    }

    @Test fun `legacy - 3_1 and unsupported, 4 and 4_1 current, missing version current`() {
        assertTrue(MyBooksModel.isLegacy(RecentBook(book_id = "x", unsupported = true, vbook_version = "3.1")))
        assertTrue(MyBooksModel.isLegacy(RecentBook(book_id = "x", vbook_version = "3.1")))
        assertFalse(MyBooksModel.isLegacy(RecentBook(book_id = "x", vbook_version = "4")))
        assertFalse(MyBooksModel.isLegacy(RecentBook(book_id = "x", vbook_version = "4.1")))
        assertFalse(MyBooksModel.isLegacy(RecentBook(book_id = "x")))
    }

    @Test fun `actions - legacy delete only, current content and book`() {
        assertEquals(listOf(MyBookAction.DELETE_BOOK), MyBooksModel.actions(RecentBook(book_id = "x", unsupported = true)))
        assertEquals(listOf(MyBookAction.DELETE_CONTENT, MyBookAction.DELETE_BOOK), MyBooksModel.actions(RecentBook(book_id = "x", vbook_version = "4")))
        assertFalse(MyBooksModel.contentActionEnabled(RecentBook(book_id = "x", has_generated_content = false)))
        assertTrue(MyBooksModel.contentActionEnabled(RecentBook(book_id = "x", has_generated_content = null)))
    }

    @Test fun `order - recently opened first, never opened by last update`() {
        val sorted = MyBooksModel.sort(listOf(
            RecentBook(book_id = "old", last_opened_at = 100, updated_at_ms = 1),
            RecentBook(book_id = "never", updated_at_ms = 150),
            RecentBook(book_id = "fresh", last_opened_at = 200),
        ))
        assertEquals(listOf("fresh", "never", "old"), sorted.map { it.book_id })
    }

    @Test fun `moment, format label, title fallback, tab keys`() {
        assertEquals(BookMoment(true, 5), MyBooksModel.moment(RecentBook(book_id = "x", last_opened_at = 5, updated_at_ms = 9)))
        assertEquals(BookMoment(false, 9), MyBooksModel.moment(RecentBook(book_id = "x", updated_at_ms = 9)))
        assertNull(MyBooksModel.moment(RecentBook(book_id = "x")))
        assertEquals("VBook 3.1", MyBooksModel.formatLabel(RecentBook(book_id = "x", vbook_version = "3.1")))
        assertEquals("id1", MyBooksModel.title("id1", "  "))
        assertEquals("Мастер", MyBooksModel.title("id1", "Мастер"))
        assertEquals(LibraryTab.MINE, LibraryTab.from(null))
        assertEquals(LibraryTab.MINE, LibraryTab.from("zzz"))
        assertEquals(LibraryTab.PUBLIC, LibraryTab.from("public"))
    }
}
