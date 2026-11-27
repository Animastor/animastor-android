package com.example.animastor.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generate API v2 (ADR ORCHESTRATION_V2 §11.1–11.2) — mirrors web generateApi.test.ts. */
class GenerateApiV2Test {
    private val gson = Gson()

    @Test fun dialogScopesMapOntoTheGenerateScope() {
        assertEquals(GenScope(level = "book"), GenerateApiV2.toGenerateScope("whole_book", "ch", "sc"))
        assertEquals(GenScope(level = "chapter", chapter_id = "ch"), GenerateApiV2.toGenerateScope("current_chapter", "ch", "sc"))
        assertEquals(GenScope(level = "scene", chapter_id = "ch", scene_id = "sc"), GenerateApiV2.toGenerateScope("current_scene", "ch", "sc"))
        assertEquals(GenScope(level = "from_current_scene", chapter_id = "ch", scene_id = "sc"), GenerateApiV2.toGenerateScope("from_current_scene", "ch", "sc"))
        assertNull(GenerateApiV2.toGenerateScope("current_scene", "ch", null))
        assertNull(GenerateApiV2.toGenerateScope("current_chapter", null, null))
    }

    @Test fun requestBodyJsonHasNoNullFields() {
        val body = GenerateRequestBody(GenScope(level = "chapter", chapter_id = "ch"), listOf("image"), "missing", "ua-1")
        val json = JsonParser.parseString(gson.toJson(body)).asJsonObject
        assertEquals("""{"level":"chapter","chapter_id":"ch"}""", json.get("scope").toString())
        assertEquals("missing", json.get("mode").asString)
        assertEquals("ua-1", json.get("client_action_id").asString)
        assertFalse(json.has("build_id"))
        // the book stop is {} on the wire
        assertEquals("{}", gson.toJson(GenerateStopRequest()))
    }

    @Test fun regenerateIsConfirmedOnlyWiderThanAScene() {
        assertFalse(GenerateApiV2.regenerateNeedsConfirm("current_scene"))
        for (s in listOf("current_chapter", "from_current_scene", "whole_book")) assertTrue(GenerateApiV2.regenerateNeedsConfirm(s))
    }

    @Test fun threeKindsOfStop() {
        assertEquals(GenerateStopRequest(command_id = "cmd"), GenerateApiV2.stopBody("audio", "cmd"))
        assertEquals(GenerateStopRequest(layer = "image"), GenerateApiV2.stopBody("image", null))
        assertEquals(GenerateStopRequest(), GenerateApiV2.stopBody(null, null))
        assertNull("VBook keeps cancel-worker", GenerateApiV2.stopBody("vbook", null))
        assertNull(GenerateApiV2.stopBody("cover", null))
        assertTrue(GenerateApiV2.stopSucceeded("cmd", GenerateStopResponse(ok = true, command_id = "cmd")))
        assertFalse(GenerateApiV2.stopSucceeded("cmd", GenerateStopResponse(ok = false)))
        assertFalse(GenerateApiV2.stopSucceeded("cmd", GenerateStopResponse(ok = true, command_id = "other")))
        assertTrue(GenerateApiV2.stopSucceeded(null, GenerateStopResponse(ok = true)))
    }

    private val aggregateJson = """
        {"book_id":"b1","orchestration":"v2","active":true,"totals":{},
         "commands":[
          {"command_id":"cmd-a","kind":"regenerate","status":"active","mode":"missing",
           "scope":{"level":"scene","chapter_id":"ch-1","scene_id":"sc-1"},"layers":["audio","image"],
           "created_at":"2026-11-27T10:00:00.000Z","labels":{"chapter_label":"Chapter 1","scene_label":"Scene 2"},
           "by_layer":{"audio":{"queued":1,"done":1,"ready":1,"total":3,"percent":33,"scenes":1},
                       "image":{"queued":2,"retrying":1,"ready":0,"total":2,"percent":0,"scenes":1}},
           "scenes":[{"chapter_id":"ch-1","scene_id":"sc-1","layer":"audio"},{"chapter_id":"ch-1","scene_id":"sc-1","layer":"image"}]},
          {"command_id":"cmd-d","status":"done","scope":{"level":"book"},"layers":["audio"],
           "by_layer":{"audio":{"ready":2,"total":4,"percent":50}},"scenes":[]},
          {"command_id":"cmd-f","status":"partial","error":"boom","scope":{"level":"scenes","scene_ids":["sc-2","sc-3"]},"layers":["video"],
           "by_layer":{"video":{"ready":1,"total":2,"percent":50}},"scenes":[{"chapter_id":"ch-2","scene_id":"sc-2","layer":"video"}]}
         ]}
    """.trimIndent()

    @Test fun aggregateToPanelRows() {
        val p = GenerateApiV2.aggregateToPanel(gson.fromJson(aggregateJson, GenProgress::class.java))
        assertEquals(4, p.tasks.size)
        val a = p.tasks[0]
        assertEquals("cmd-a", a.task_id); assertEquals("audio", a.type); assertEquals("current_scene", a.scope)
        assertEquals("ch-1", a.chapter_id); assertEquals("sc-1", a.scene_id)
        assertEquals("Chapter 1", a.chapter_label); assertEquals("Scene 2", a.scene_label)
        assertEquals(1, a.ready); assertEquals(3, a.total); assertEquals(33, a.percent); assertEquals("active", a.status)
        assertEquals(1, a.target_count)
        assertNotNull(a.started_at)
        assertTrue(p.tasks[1].retrying)
        val d = p.tasks[2]
        assertTrue(d.done); assertEquals("completed", d.status); assertEquals(4, d.ready); assertEquals(100, d.percent)
        val f = p.tasks[3]
        assertTrue(f.failed); assertEquals("failed", f.status); assertEquals("boom", f.error_message)
        assertEquals("from_current_scene", f.scope); assertEquals("sc-2", f.scene_id); assertEquals("ch-2", f.chapter_id)
        assertTrue(p.any_incomplete)
    }

    @Test fun emptyAggregateHasNoRows() {
        val p = GenerateApiV2.aggregateToPanel(null)
        assertTrue(p.tasks.isEmpty()); assertFalse(p.any_incomplete)
    }

    @Test fun streamEventsAreParsed() {
        val e = GenerateApiV2.parseGenStreamEvent("""{"type":"gen_progress","reason":"connect","command_ids":[],"progress":$aggregateJson}""", gson)
        assertNotNull(e)
        assertEquals("connect", e!!.reason)
        assertEquals(3, e.progress!!.commands.size)
        assertNull(GenerateApiV2.parseGenStreamEvent("{bad", gson))
        assertNull(GenerateApiV2.parseGenStreamEvent("""{"type":"progress","layer":"audio"}""", gson))
        assertNull(GenerateApiV2.parseGenStreamEvent("""{"type":"gen_progress","progress":{}}""", gson))
    }
}
