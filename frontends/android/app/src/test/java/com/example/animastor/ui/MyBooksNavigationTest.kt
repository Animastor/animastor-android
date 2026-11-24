package com.example.animastor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Мои книги» — ONE screen for every shortcut (owner, 2026-11-22): the File
 * «Библиотека» card, the Settings «Мои книги» row and the account menu all
 * lead to the Library on its «Мои книги» tab through MainActivity — no second
 * screen. Web parity: features/myBooks/myBooks.test.tsx.
 */
class MyBooksNavigationTest {
    private val ui = "src/main/java/com/example/animastor/ui"
    private fun src(name: String) = File("$ui/$name").readText()
    private val allUi: Map<String, String> by lazy {
        File(ui).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.associate { it.name to it.readText() }
    }

    @Test fun `a LibraryFragment is created in exactly one place - MainActivity openLibrary`() {
        // the class's own factory (newInstance) does not count
        val creators = allUi.filter { (name, s) -> name != "LibraryFragment.kt" && Regex("""LibraryFragment(\.newInstance)?\(""").containsMatchIn(s) }.keys
        assertEquals(setOf("MainActivity.kt"), creators)
        val main = src("MainActivity.kt")
        assertTrue(main.contains("fun openLibrary(tab: LibraryTab = LibraryTab.MINE)"))
        assertTrue(main.contains("fun openMyBooks() = openLibrary(LibraryTab.MINE)"))
    }

    @Test fun `Library - the File card opens the Library, default tab is My books`() {
        val file = src("FileFragment.kt")
        val card = file.substringAfter("binding?.libraryCard?.setOnClickListener").substringBefore("}")
        assertTrue(card.contains("(requireActivity() as MainActivity).openLibrary()"))
        assertEquals("MINE", Regex("""fun openLibrary\(tab: LibraryTab = LibraryTab\.(\w+)\)""").find(src("MainActivity.kt"))!!.groupValues[1])
    }

    @Test fun `Settings - the My books row opens My books, the Clear block is gone`() {
        val settings = src("SettingsFragment.kt")
        val row = settings.substringAfter("b.myBooksButton.setOnClickListener").substringBefore("}")
        assertTrue(row.contains("(requireActivity() as MainActivity).openMyBooks()"))
        assertFalse(settings.contains("clearCacheButton"))
        assertFalse(settings.contains("deleteVbookButton"))
        val layout = File("src/main/res/layout/fragment_settings.xml").readText()
        assertTrue(layout.contains("@+id/myBooksButton"))
        assertFalse(layout.contains("clearCacheButton"))
        assertFalse(layout.contains("deleteVbookButton"))
    }

    @Test fun `Account menu - My books item opens My books`() {
        val main = src("MainActivity.kt")
        assertTrue(main.contains("AuthDialog.show(this@MainActivity, onMyBooks = { openMyBooks() })"))
        val dialog = src("AuthDialog.kt")
        val item = dialog.substringAfter("text = activity.getString(R.string.my_books)").substringBefore("val logout")
        assertTrue(item.contains("dialog.dismiss()"))
        assertTrue(item.contains("onMyBooks()"))
    }

    @Test fun `3_1 screens offer Delete book with the shared confirmation`() {
        assertTrue(src("FileFragment.kt").contains("MyBookActions.confirm(requireActivity(), info.bookId"))
        assertTrue(src("EditFragment.kt").contains("MyBookActions.confirm(requireActivity(), bookId"))
        // the confirmation names the book
        val actions = src("MyBookActions.kt")
        assertTrue(actions.contains("getString(R.string.my_books_delete_content_confirm, title)"))
        assertTrue(actions.contains("getString(R.string.my_books_delete_book_confirm, title)"))
        for (res in listOf("values", "values-ru")) {
            val strings = File("src/main/res/$res/strings.xml").readText()
            for (key in listOf("my_books_delete_content_confirm", "my_books_delete_book_confirm")) {
                val line = strings.lines().first { it.contains("name=\"$key\"") }
                assertTrue("$res/$key names the book", line.contains("%1\$s"))
            }
        }
    }
}
