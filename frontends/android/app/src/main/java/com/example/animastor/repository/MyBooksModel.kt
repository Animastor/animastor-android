package com.example.animastor.repository

// «Мои книги» — pure model of the list (no I/O, no Android). Web parity:
// frontends/app/src/features/myBooks/myBooksModel.ts (the same rules).

/** «Мои книги» asks for the whole workspace list; the server caps one call at 500. */
const val MY_BOOKS_LIMIT = 500

/** The two tabs of the Library screen (web: /library?tab=mine|public). */
enum class LibraryTab(val key: String) {
    MINE("mine"), PUBLIC("public");

    companion object {
        /** A saved/received tab key → the tab; anything else → «Мои книги». */
        fun from(key: String?): LibraryTab = if (key == PUBLIC.key) PUBLIC else MINE
    }
}

enum class MyBookAction { DELETE_CONTENT, DELETE_BOOK }

data class BookMoment(val opened: Boolean, val at: Long)

object MyBooksModel {
    /** A book of an unsupported format (VBook 3.1 or no version): only «Удалить книгу». */
    fun isLegacy(b: RecentBook): Boolean {
        if (b.unsupported) return true
        val v = b.vbook_version?.trim().orEmpty()
        if (v.isEmpty()) return false // an old server without the field — treat as current
        return v.substringBefore('.').toIntOrNull() != 4
    }

    /** «VBook 4», «VBook 3.1». */
    fun formatLabel(b: RecentBook): String {
        val v = b.vbook_version?.trim().orEmpty()
        // Gson reads the number 4 as "4"; a JSON 4.0 would read as "4.0" — keep it as sent.
        return if (v.isEmpty()) "VBook" else "VBook $v"
    }

    /** Last opened, else last updated (epoch ms), or null. */
    fun moment(b: RecentBook): BookMoment? {
        val opened = b.last_opened_at
        if (opened != null && opened > 0) return BookMoment(true, opened)
        val updated = b.updated_at_ms
        if (updated != null && updated > 0) return BookMoment(false, updated)
        return null
    }

    /** Recently opened first; a never-opened book by its last update (server rule). */
    fun sort(books: List<RecentBook>): List<RecentBook> =
        books.sortedByDescending { it.last_opened_at ?: it.updated_at_ms ?: 0L }

    /** Menu actions, in order. A legacy book: deletion only. */
    fun actions(b: RecentBook): List<MyBookAction> =
        if (isLegacy(b)) listOf(MyBookAction.DELETE_BOOK)
        else listOf(MyBookAction.DELETE_CONTENT, MyBookAction.DELETE_BOOK)

    /** «Удалить сгенерированный контент» is off only when the server says there is none. */
    fun contentActionEnabled(b: RecentBook): Boolean = b.has_generated_content != false

    /** The title shown everywhere (the confirmation included). */
    fun title(bookId: String, title: String?): String = title?.trim()?.takeIf { it.isNotEmpty() } ?: bookId
}
