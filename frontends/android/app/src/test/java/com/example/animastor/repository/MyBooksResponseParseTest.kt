package com.example.animastor.repository

import com.example.animastor.network.RetrofitClient
import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GET /api/v1/books as the backend sends it, parsed by the converter Retrofit
 * uses ([RetrofitClient.gson]). `updated_at` keeps its source shape — an ISO
 * string (manifest), epoch seconds as a string (PG BIGINT) or a number — so a
 * `Long` field would fail the WHOLE response, and the callers' runCatching
 * would turn that into a silently empty list. [RecentBook.updated_at] is text;
 * order and dates use `updated_at_ms` / `last_opened_at`.
 */
class MyBooksResponseParseTest {

    private val body = """
        {"books":[
          {"vbook_version":4,"book_id":"iso","build_id":"manual_1","title":"ISO","state":"READY","source_type":"txt","file_hash":null,
           "updated_at":"2026-11-20T00:00:00.000Z","updated_at_ms":1795132800000,"last_opened_at":null,
           "parsed_chapters":1,"total_scenes":2,"workspace_id":"w","has_generated_content":true},
          {"vbook_version":"4","book_id":"pg","build_id":"build_pg","title":"PG","updated_at":"1795107927","updated_at_ms":1795107927000,
           "last_opened_at":1795300000000,"has_generated_content":false},
          {"unsupported":true,"vbook_version":"3.1","book_id":"legacy","updated_at":1795000000,"updated_at_ms":1795000000000,
           "last_opened_at":null,"has_generated_content":null},
          {"vbook_version":4,"book_id":"never","updated_at":0,"updated_at_ms":null,"last_opened_at":null,"has_generated_content":null}
        ]}
    """.trimIndent()

    @Test fun `GET books with ISO updated_at parses - the list is not empty`() {
        val books = RetrofitClient.gson.fromJson(body, RecentBooksResponse::class.java).books
        assertEquals(4, books.size)
        assertEquals("2026-11-20T00:00:00.000Z", books[0].updated_at)
        assertEquals("1795107927", books[1].updated_at)
        assertEquals(1795107927000L, books[1].updated_at_ms)
        assertTrue(MyBooksModel.isLegacy(books[2]))
        // order and dates come from last_opened_at / updated_at_ms, never from updated_at
        assertEquals(listOf("pg", "iso", "legacy", "never"), MyBooksModel.sort(books).map { it.book_id })
        assertEquals(BookMoment(false, 1795132800000L), MyBooksModel.moment(books[0]))
        assertEquals(null, MyBooksModel.moment(books[3]))
    }

    /** The shape before «Мои книги» (updated_at: Long) — what the String field avoids. */
    private data class LongUpdatedAt(val book_id: String = "", val updated_at: Long = 0)
    private data class LongResponse(val books: List<LongUpdatedAt> = emptyList())

    @Test fun `a Long updated_at would fail the whole response on an ISO string`() {
        assertThrows(JsonSyntaxException::class.java) {
            RetrofitClient.gson.fromJson(body, LongResponse::class.java)
        }
    }
}
