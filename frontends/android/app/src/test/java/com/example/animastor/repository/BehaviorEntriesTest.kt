package com.example.animastor.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * behavior.json v2 service keys never become editor cards; the book parse
 * never breaks on them. Same fixtures as web frontends/app/src/api/behaviors.test.ts.
 */
class BehaviorEntriesTest {
    private val gson = Gson()

    private fun entries(json: String) = BehaviorEntries.characterEntries(JsonParser.parseString(json))

    @Test
    fun flatApiMapPassesThrough() {
        val e = entries("""{"hero":{"baseline":"calm"},"side":{"quirks":["q"]}}""")
        assertEquals(listOf("hero", "side"), e.keys.toList())
        assertEquals("calm", e["hero"]?.baseline)
        assertEquals(listOf("q"), e["side"]?.quirks)
    }

    @Test
    fun serviceKeysInFlatMapAreDropped() {
        val e = entries("""{"schema_version":2,"rules":[],"defaults":{},"hero":{"baseline":"x"}}""")
        assertEquals(listOf("hero"), e.keys.toList())
    }

    @Test
    fun rawV2DocumentIsUnwrappedToCharacters() {
        val e = entries("""{"schema_version":2,"rules":[{"id":"r"}],"characters":{"hero":{"baseline":"x"}}}""")
        assertEquals(listOf("hero"), e.keys.toList())
        assertEquals("x", e["hero"]?.baseline)
    }

    @Test
    fun nonObjectValuesAndEmptyInputGiveNoCards() {
        assertEquals(listOf("ok"), entries("""{"hero":"oops","side":["x"],"ok":{}}""").keys.toList())
        assertTrue(BehaviorEntries.characterEntries(null).isEmpty())
        assertTrue(entries("[]").isEmpty())
    }

    @Test
    fun reservedListMatchesBackendContract() {
        assertEquals(listOf("schema_version", "rules", "characters", "defaults"), BehaviorEntries.RESERVED_KEYS)
    }

    @Test
    fun wholeBookParsesWithServiceKeysAndRules() {
        // v2.1-era Map<String, BehaviorEntry> would throw on "schema_version": 2.
        val book = gson.fromJson(
            """{"manifest":{"book_id":"b"},"behaviors":{"schema_version":2,"rules":[{"id":"r"}],"characters":{"hero":{"baseline":"x"}}},
               "behavior_rules":[{"id":"r","then":{"action":"sighs"}}]}""",
            BookData::class.java
        )
        assertEquals(listOf("hero"), book.behaviorEntries().keys.toList())
        assertTrue(book.behavior_rules!!.isJsonArray)
        val flat = gson.fromJson("""{"behaviors":{"hero":{"baseline":"y"}}}""", BookData::class.java)
        assertEquals("y", flat.behaviorEntries()["hero"]?.baseline)
        assertTrue(gson.fromJson("{}", BookData::class.java).behaviorEntries().isEmpty())
    }
}
