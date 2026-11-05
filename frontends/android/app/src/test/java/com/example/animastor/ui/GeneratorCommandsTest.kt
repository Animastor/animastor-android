package com.example.animastor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generator commands only by explicit clicks — reports/2026-11-04/
 * regenerate-sweep-fix.md. Same cases as web
 * frontends/app/src/pages/GeneratePage.commands.test.tsx.
 */
class GeneratorCommandsTest {

    private val ch = "ch-5e5a8c76"
    private val sc = "sc-4bb85e02"

    @Test fun noUserActionNoCommand() {
        // timers / observers / restore paths have no action → refused
        assertFalse(GeneratorCommands.mayStart(null))
        assertFalse(GeneratorCommands.mayStart(GeneratorCommands.UserAction("", "ua-1", true, 0)))
        assertFalse(GeneratorCommands.mayStart(GeneratorCommands.UserAction("generator.scope_dialog", "", true, 0)))
        assertTrue(GeneratorCommands.mayStart(GeneratorCommands.userAction(GeneratorCommands.SOURCE_SCOPE_DIALOG)))
    }

    @Test fun userActionIdsAreUnique() {
        val a = GeneratorCommands.userAction(GeneratorCommands.SOURCE_SCOPE_DIALOG, now = 1)
        val b = GeneratorCommands.userAction(GeneratorCommands.SOURCE_SCOPE_DIALOG, now = 1)
        assertTrue(a.actionId.matches(Regex("^ua-[0-9a-f]{16}$")))
        assertNotEquals(a.actionId, b.actionId)
    }

    @Test fun startIsNotArmedRightAfterTheDialogOpened() {
        assertFalse(GeneratorCommands.armed(openedAt = 1_000, now = 1_000))
        assertFalse(GeneratorCommands.armed(openedAt = 1_000, now = 1_000 + GeneratorCommands.SCOPE_DIALOG_ARM_MS - 1))
        assertTrue(GeneratorCommands.armed(openedAt = 1_000, now = 1_000 + GeneratorCommands.SCOPE_DIALOG_ARM_MS))
    }

    @Test fun scopeTargetsPerChoice() {
        assertEquals(null to null, GeneratorCommands.scopeTargets("whole_book", ch, sc))
        assertEquals(ch to null, GeneratorCommands.scopeTargets("current_chapter", ch, sc))
        assertEquals(ch to sc, GeneratorCommands.scopeTargets("current_scene", ch, sc))
        assertEquals(ch to sc, GeneratorCommands.scopeTargets("from_current_scene", ch, sc))
    }

    @Test fun audioButtonCurrentSceneSendsOnlyItsCommand() {
        val action = GeneratorCommands.userAction(GeneratorCommands.SOURCE_SCOPE_DIALOG)
        val (chId, scId) = GeneratorCommands.scopeTargets("current_scene", ch, sc)
        val r = GeneratorCommands.request(listOf("audio"), "current_scene", chId, scId, action)
        assertEquals(true, r.rebuild_all)
        assertEquals(listOf("audio"), r.worker_types)
        assertEquals("current_scene", r.scope)
        assertEquals(ch, r.chapter_id)
        assertEquals(sc, r.scene_id)
        assertNull(r.new_book)
        assertEquals("user_click", r.client_action?.kind)
        assertEquals("generator.scope_dialog", r.client_action?.source)
        assertEquals(action.actionId, r.client_action?.action_id)
    }

    @Test fun imageButtonCurrentChapterSendsChapterOnly() {
        val (chId, scId) = GeneratorCommands.scopeTargets("current_chapter", ch, sc)
        val r = GeneratorCommands.request(listOf("image"), "current_chapter", chId, scId,
            GeneratorCommands.userAction(GeneratorCommands.SOURCE_SCOPE_DIALOG))
        assertEquals(listOf("image"), r.worker_types)
        assertEquals("current_chapter", r.scope)
        assertEquals(ch, r.chapter_id)
        assertNull(r.scene_id)
    }

    @Test fun defaultWholeBookCarriesNoTargets() {
        val (chId, scId) = GeneratorCommands.scopeTargets("whole_book", ch, sc)
        val r = GeneratorCommands.request(listOf("audio"), "whole_book", chId, scId,
            GeneratorCommands.userAction(GeneratorCommands.SOURCE_SCOPE_DIALOG))
        assertNull(r.chapter_id)
        assertNull(r.scene_id)
    }

    @Test fun retryRowActionSource() {
        val a = GeneratorCommands.userAction(GeneratorCommands.SOURCE_RETRY_ROW)
        assertEquals("generator.retry_row", GeneratorCommands.clientAction(a).source)
    }

    // ── Задача K (2026-11-05): ▶ / ■ of a stopped row ──────────────

    @Test fun stoppedRowIgnoresTapsRightAfterItAppeared() {
        val g = GeneratorCommands.StoppedRowGuard()
        val k = GeneratorCommands.stoppedRowKey("t1", "audio", ch, null, TaskRowState.INTERRUPTED)
        g.onRendered(k, 1_000)
        assertFalse(g.accept(k, 1_000))
        assertFalse(g.accept(k, 1_000 + GeneratorCommands.STOPPED_ROW_ARM_MS - 1))
        assertTrue(g.accept(k, 1_000 + GeneratorCommands.STOPPED_ROW_ARM_MS))
    }

    @Test fun stoppedRowDoubleTapIsOneAction() {
        val g = GeneratorCommands.StoppedRowGuard()
        val k = GeneratorCommands.stoppedRowKey("t1", "audio", ch, null, TaskRowState.BLOCKED)
        g.onRendered(k, 0)
        assertTrue(g.accept(k, 5_000))
        assertFalse("second tap within 400 ms", g.accept(k, 5_100))
        assertTrue(g.accept(k, 5_000 + GeneratorCommands.STOPPED_ROW_ARM_MS))
    }

    @Test fun stoppedRowIgnoresTapsWhileItsRequestIsInFlight() {
        val g = GeneratorCommands.StoppedRowGuard()
        val k = GeneratorCommands.stoppedRowKey("t1", "audio", ch, null, TaskRowState.FAILED)
        g.onRendered(k, 0)
        assertTrue(g.accept(k, 1_000))
        g.begin(k)
        assertFalse(g.accept(k, 10_000))
        g.end(k)
        assertTrue(g.accept(k, 10_000))
    }

    @Test fun anActiveRowTurningStoppedIsArmedAfresh() {
        // the same task: a different state → a different key → a fresh 400 ms
        val active = GeneratorCommands.stoppedRowKey("t1", "audio", ch, null, TaskRowState.ACTIVE)
        val stopped = GeneratorCommands.stoppedRowKey("t1", "audio", ch, null, TaskRowState.INTERRUPTED)
        assertNotEquals(active, stopped)
        val g = GeneratorCommands.StoppedRowGuard()
        g.onRendered(stopped, 9_000)
        g.retain(setOf(stopped))
        assertFalse(g.accept(stopped, 9_100))
        // a row first seen by a tap (never rendered) is not armed either
        val other = GeneratorCommands.stoppedRowKey("t2", "audio", ch, null, TaskRowState.INTERRUPTED)
        assertFalse(g.accept(other, 20_000))
    }

    @Test fun dismissSucceedsOnlyWhenTheBackendCancelledThatTask() {
        assertTrue(GeneratorCommands.dismissSucceeded("t1", listOf("t1")))
        assertFalse("older backend: non-active task not cancelled", GeneratorCommands.dismissSucceeded("t1", emptyList()))
        assertFalse(GeneratorCommands.dismissSucceeded("t1", null))
        assertFalse(GeneratorCommands.dismissSucceeded(null, listOf("t1")))
    }
}
