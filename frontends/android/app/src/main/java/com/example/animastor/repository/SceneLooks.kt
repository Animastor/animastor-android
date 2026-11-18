package com.example.animastor.repository

// ======================================================
// Scene tab — the one-off look patch of a scene (VBOOK_FORMAT_V4.md §4a.4)
// ======================================================
// One to one with web features/heroes/sceneLooks.ts. The scene editor keeps
// «look in this scene» blocks {hero id, fields}; the save turns them into
// PATCH /scene/:ch/:sc fields `look_patch.<hero_id>.<appearance|clothes|video_tokens>`
// — ONLY the changed ones; "" removes a field (the backend drops empty maps).
// `passport.*` of a scene is gone in VBook 4 (400 legacy_field) and is never sent.

object SceneLooks {
    val LOOK_PATCH_FIELDS = listOf("appearance", "clothes", "video_tokens")

    data class LookPatchBlock(val charId: String, val fields: Map<String, String>)

    /** A stored patch value as the editor text (video_tokens: "a, b"). */
    fun lookPatchText(value: Any?): String = when (value) {
        is List<*> -> value.filterIsInstance<String>().joinToString(", ")
        is String -> value
        else -> ""
    }

    fun patchValue(patch: LookPatch?, field: String): Any? = when (field) {
        "appearance" -> patch?.appearance
        "clothes" -> patch?.clothes
        "video_tokens" -> patch?.video_tokens
        else -> null
    }

    /** Blocks of the scene editor → PATCH fields (diff against scene.look_patch). */
    fun lookPatchFields(blocks: List<LookPatchBlock>, existing: Map<String, LookPatch?>?): Map<String, String> {
        val desired = LinkedHashMap<String, MutableMap<String, String>>()
        for (block in blocks) {
            val id = block.charId.trim()
            if (id.isEmpty() || block.fields.values.none { it.trim().isNotEmpty() }) continue
            val m = desired.getOrPut(id) { LinkedHashMap() }
            for (f in LOOK_PATCH_FIELDS) {
                val v = block.fields[f]?.trim() ?: ""
                if (v.isNotEmpty()) m[f] = v
            }
        }
        val have = existing ?: emptyMap()
        val out = LinkedHashMap<String, String>()
        for (id in (desired.keys + have.keys).toCollection(LinkedHashSet())) {
            for (f in LOOK_PATCH_FIELDS) {
                val next = desired[id]?.get(f) ?: ""
                val prev = lookPatchText(patchValue(have[id], f))
                if (next != prev) out["look_patch.$id.$f"] = next
            }
        }
        return out
    }
}
