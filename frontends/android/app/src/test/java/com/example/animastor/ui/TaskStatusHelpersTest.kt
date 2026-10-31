package com.example.animastor.ui

import com.example.animastor.repository.ProgressPanelResponse
import com.example.animastor.repository.ProgressTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 155 (command-only generation) — same fixtures as web
 *  @animastor/web-generator test/taskStatus.test.ts and app commandErrors.test.ts. */
class TaskStatusHelpersTest {

    private fun st(status: String? = null, done: Boolean = false, cancelled: Boolean = false,
                   blocked: Boolean = false, interrupted: Boolean = false) =
        TaskStatusHelpers.taskRowState(status, done, cancelled, blocked, interrupted)

    @Test fun classifiesStage155Statuses() {
        assertEquals(TaskRowState.ACTIVE, st("active"))
        assertEquals(TaskRowState.DONE, st("completed"))
        assertEquals(TaskRowState.CANCELLED, st("cancelled", cancelled = true))
        assertEquals(TaskRowState.BLOCKED, st("blocked", blocked = true))
        assertEquals(TaskRowState.INTERRUPTED, st("interrupted", interrupted = true))
    }

    @Test fun preStage155BackendWithoutStatus() {
        assertEquals(TaskRowState.ACTIVE, st())
        assertEquals(TaskRowState.DONE, st(done = true))
        assertEquals(TaskRowState.CANCELLED, st(cancelled = true))
    }

    @Test fun stoppedCommandIsNeverDoneOrRunning() {
        assertEquals(TaskRowState.BLOCKED, st("blocked", done = true))
        assertEquals(TaskRowState.INTERRUPTED, st(interrupted = true, done = true))
        assertTrue(TaskStatusHelpers.isStopped(TaskRowState.BLOCKED))
        assertTrue(TaskStatusHelpers.isStopped(TaskRowState.INTERRUPTED))
        assertFalse(TaskStatusHelpers.isStopped(TaskRowState.CANCELLED))
        assertFalse(TaskStatusHelpers.isStopped(TaskRowState.ACTIVE))
    }

    @Test fun blockedWorkerTypeParsesReason() {
        assertEquals("audio", TaskStatusHelpers.blockedWorkerType("no_worker:audio", "audio"))
        assertEquals("image", TaskStatusHelpers.blockedWorkerType("no_worker:image", "cover"))
        assertEquals("video", TaskStatusHelpers.blockedWorkerType("no_worker:video", "video"))
        assertEquals("image", TaskStatusHelpers.blockedWorkerType(null, "cover"))
        assertEquals("audio", TaskStatusHelpers.blockedWorkerType("weird", "audio"))
        assertNull(TaskStatusHelpers.blockedWorkerType(null, "vbook"))
        assertEquals("image", TaskStatusHelpers.commandWorkerType("cover"))
        assertNull(TaskStatusHelpers.commandWorkerType("vbook"))
    }

    @Test fun retryCommandRepeatsTypeAndScope() {
        val r = TaskStatusHelpers::retryCommandFor
        assertEquals(TaskStatusHelpers.RetryCommand(listOf("audio"), "current_scene", "ch-1", "sc-1"),
            r(TaskRowState.BLOCKED, "audio", "current_scene", "ch-1", "sc-1"))
        assertEquals(TaskStatusHelpers.RetryCommand(listOf("audio"), "current_chapter", "ch-1", null),
            r(TaskRowState.INTERRUPTED, "audio", "current_chapter", "ch-1", "sc-1"))
        assertEquals(TaskStatusHelpers.RetryCommand(listOf("audio"), "whole_book", null, null),
            r(TaskRowState.BLOCKED, "audio", "whole_book", "ch-1", "sc-1"))
        assertEquals(TaskStatusHelpers.RetryCommand(listOf("audio"), "from_current_scene", "ch-1", "sc-1"),
            r(TaskRowState.BLOCKED, "audio", "from_current_scene", "ch-1", "sc-1"))
        assertEquals(listOf("image"), r(TaskRowState.BLOCKED, "cover", "whole_book", null, null)?.workerTypes)
    }

    @Test fun nothingToRepeatForOtherStates() {
        for (s in listOf(TaskRowState.ACTIVE, TaskRowState.DONE, TaskRowState.CANCELLED)) {
            assertNull(TaskStatusHelpers.retryCommandFor(s, "audio", "whole_book", null, null))
        }
        assertNull(TaskStatusHelpers.retryCommandFor(TaskRowState.BLOCKED, "vbook", "whole_book", null, null))
    }

    @Test fun layerDisabled409UsesBodyTypes() {
        val e = TaskStatusHelpers.classifyStartError(409,
            """{"error":"layer_disabled","message":"All requested layers are disabled in layer-config","disabled_worker_types":["image"]}""",
            "HTTP 409", listOf("image"))
        assertEquals(TaskStatusHelpers.StartError.LayerDisabled(listOf("image")), e)
    }

    @Test fun layerDisabled409WithoutListFallsBackToRequested() {
        val e = TaskStatusHelpers.classifyStartError(409, """{"error":"layer_disabled"}""", "HTTP 409", listOf("video"))
        assertEquals(TaskStatusHelpers.StartError.LayerDisabled(listOf("video")), e)
    }

    @Test fun otherErrorsKeepGenericPath() {
        assertEquals(TaskStatusHelpers.StartError.Other("HTTP 409"),
            TaskStatusHelpers.classifyStartError(409, """{"error":"Regeneration already in progress for this book"}""", "HTTP 409", listOf("audio")))
        assertEquals(TaskStatusHelpers.StartError.Other("boom"),
            TaskStatusHelpers.classifyStartError(null, null, "boom", listOf("audio")))
        assertEquals(TaskStatusHelpers.StartError.Other("HTTP 500"),
            TaskStatusHelpers.classifyStartError(500, """{"error":"layer_disabled"}""", "HTTP 500", listOf("audio")))
        assertEquals(TaskStatusHelpers.StartError.Other("HTTP 409"),
            TaskStatusHelpers.classifyStartError(409, "not json", "HTTP 409", listOf("audio")))
    }

    @Test fun progressTaskDefaultsKeepOldBackendsWorking() {
        val t = ProgressTask(type = "audio", total = 5)
        assertNull(t.status)
        assertFalse(t.blocked)
        assertFalse(t.interrupted)
        assertNull(t.blocked_reason)
        val panel = ProgressPanelResponse(tasks = listOf(t))
        assertEquals(1, panel.tasks.size)
    }

    @Test fun taskRowDefaultStateFollowsDoneAndCancelled() {
        assertEquals(TaskRowState.ACTIVE, TaskRow(type = "audio", label = "A", ready = 0, total = 1, percent = 0, done = false).state)
        assertEquals(TaskRowState.DONE, TaskRow(type = "audio", label = "A", ready = 1, total = 1, percent = 100, done = true).state)
        assertEquals(TaskRowState.CANCELLED, TaskRow(type = "audio", label = "A", ready = 0, total = 1, percent = 0, done = false, cancelled = true).state)
    }
}
