package com.example.animastor.repository

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * behavior.json in the API (animastor-docs architecture/VBOOK_BEHAVIOUR.md).
 *
 * The server stores schema v2 ({schema_version, rules, characters}); GET /book
 * returns `behaviors` as the FLAT character map (v2.1 shape) plus read-only
 * `behavior_rules`. [BookData.behaviors] is a raw [JsonElement] so that even a
 * raw v2 document never breaks Gson parsing of the whole book. Defense in
 * depth: service keys never become editor cards, and a raw v2 document is
 * unwrapped to its `characters` map. Pure — no Android framework.
 * Web parity: frontends/app/src/api/behaviors.ts (same fixtures in tests).
 */
object BehaviorEntries {
    /** Top-level keys of a v2 document that are never character ids. */
    val RESERVED_KEYS: List<String> = listOf("schema_version", "rules", "characters", "defaults")

    private val gson = Gson()

    /** Character entries of `bookData.behaviors` (flat map, or v2 document). */
    fun characterEntries(raw: JsonElement?): Map<String, BehaviorEntry> {
        if (raw == null || !raw.isJsonObject) return emptyMap()
        val obj = raw.asJsonObject
        val chars = obj.get("characters")
        val source: JsonObject =
            if (obj.has("schema_version") && chars != null && chars.isJsonObject) chars.asJsonObject else obj
        val out = LinkedHashMap<String, BehaviorEntry>()
        for ((key, value) in source.entrySet()) {
            if (key in RESERVED_KEYS) continue
            if (!value.isJsonObject) continue
            val entry = try {
                gson.fromJson(value, BehaviorEntry::class.java)
            } catch (_: RuntimeException) {
                null
            } ?: continue
            out[key] = entry
        }
        return out
    }
}
