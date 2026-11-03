package com.example.animastor.ui

import com.example.animastor.repository.Chapter
import com.example.animastor.repository.FailedSceneRef
import com.example.animastor.repository.ProgressPanelResponse
import com.example.animastor.repository.ProgressTask
import com.example.animastor.repository.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 155 (command-only generation) — same fixtures as web
 *  @animastor/web-generator test/taskStatus.test.ts and app commandErrors.test.ts. */
class TaskStatusHelpersTest {

    private fun st(status: String? = null, done: Boolean = false, cancelled: Boolean = false,
                   blocked: Boolean = false, interrupted: Boolean = false, failed: Boolean = false) =
        TaskStatusHelpers.taskRowState(status, done, cancelled, blocked, interrupted, failed)

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
        assertEquals(TaskRowState.FAILED, st("failed", done = true))
        assertTrue(TaskStatusHelpers.isStopped(TaskRowState.BLOCKED))
        assertTrue(TaskStatusHelpers.isStopped(TaskRowState.INTERRUPTED))
        assertTrue(TaskStatusHelpers.isStopped(TaskRowState.FAILED))
        assertFalse(TaskStatusHelpers.isStopped(TaskRowState.CANCELLED))
        assertFalse(TaskStatusHelpers.isStopped(TaskRowState.ACTIVE))
    }

    @Test fun failedTakesPrecedenceOverDoneAndActive() {
        assertEquals(TaskRowState.FAILED,
            TaskStatusHelpers.taskRowState(null, done = true, cancelled = false, blocked = false, interrupted = false, failed = true))
        assertEquals(TaskRowState.FAILED,
            TaskStatusHelpers.taskRowState(null, done = false, cancelled = false, blocked = false, interrupted = false, failed = true))
        assertEquals(TaskRowState.FAILED,
            TaskStatusHelpers.taskRowState("active", done = false, cancelled = false, blocked = false, interrupted = false, failed = true))
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

    @Test fun retryCommandWorksForFailed() {
        val r = TaskStatusHelpers::retryCommandFor
        assertEquals(TaskStatusHelpers.RetryCommand(listOf("audio"), "current_scene", "ch-1", "sc-1"),
            r(TaskRowState.FAILED, "audio", "current_scene", "ch-1", "sc-1"))
        assertNull(r(TaskRowState.FAILED, "vbook", "whole_book", null, null))
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
        assertFalse(t.failed)
        assertNull(t.blocked_reason)
        assertNull(t.error_class)
        assertNull(t.error_code)
        assertNull(t.error_message)
        assertNull(t.failed_scene)
        assertFalse(t.retrying)
        assertNull(t.retry_no)
        assertNull(t.retry_max)
        assertNull(t.retry_at)
        val panel = ProgressPanelResponse(tasks = listOf(t))
        assertEquals(1, panel.tasks.size)
    }

    @Test fun taskRowDefaultStateFollowsDoneAndCancelled() {
        assertEquals(TaskRowState.ACTIVE, TaskRow(type = "audio", label = "A", ready = 0, total = 1, percent = 0, done = false).state)
        assertEquals(TaskRowState.DONE, TaskRow(type = "audio", label = "A", ready = 1, total = 1, percent = 100, done = true).state)
        assertEquals(TaskRowState.CANCELLED, TaskRow(type = "audio", label = "A", ready = 0, total = 1, percent = 0, done = false, cancelled = true).state)
        assertEquals(TaskRowState.FAILED, TaskRow(type = "audio", label = "A", ready = 0, total = 1, percent = 0, done = false, state = TaskRowState.FAILED).state)
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU retry policy — failureText / formatFailureText
    // ═══════════════════════════════════════════════════════════════

    @Test fun failureTextClassifiesByCode() {
        assertEquals("task_failed_comfy_nodes",
            TaskStatusHelpers.failureText(null, "comfy_missing_node", null, "audio").key)
        assertEquals("task_failed_comfy_model",
            TaskStatusHelpers.failureText(null, "comfy_model_missing", null, "image").key)
    }

    @Test fun failureTextClassifiesByClass() {
        assertEquals("task_failed_comfy_rejected",
            TaskStatusHelpers.failureText("deterministic", null, null, "video").key)
        assertEquals("task_failed_transient",
            TaskStatusHelpers.failureText("transient", null, null, "audio").key)
        assertEquals("task_failed_oom",
            TaskStatusHelpers.failureText("oom", null, null, "image").key)
    }

    @Test fun failureTextFallsBackToUnknown() {
        assertEquals("task_failed_unknown",
            TaskStatusHelpers.failureText(null, null, null, "audio").key)
        assertEquals("task_failed_unknown",
            TaskStatusHelpers.failureText("weird", "weird_code", null, "video").key)
    }

    @Test fun failureTextArgIsFirstLineOfMessage() {
        assertEquals("boom",
            TaskStatusHelpers.failureText(null, null, "boom", "audio").arg)
        assertEquals("first line…",
            TaskStatusHelpers.failureText(null, null, "first line\nsecond line", "image").arg)
        assertNull(TaskStatusHelpers.failureText(null, null, null, "video").arg)
        assertNull(TaskStatusHelpers.failureText(null, null, "", "audio").arg)
        assertNull(TaskStatusHelpers.failureText(null, null, "   ", "audio").arg)
    }

    @Test fun failureTextTypeDoesNotAffectKey() {
        // type is accepted for signature parity but does not influence key selection
        assertEquals("task_failed_oom",
            TaskStatusHelpers.failureText("oom", null, null, "audio").key)
        assertEquals("task_failed_oom",
            TaskStatusHelpers.failureText("oom", null, null, "video").key)
        assertEquals("task_failed_oom",
            TaskStatusHelpers.failureText("oom", null, null, "cover").key)
    }

    @Test fun formatFailureTextReplacesPlaceholder() {
        assertEquals("ComfyUI: required nodes missing — boom",
            TaskStatusHelpers.formatFailureText("ComfyUI: required nodes missing — %1\$s", "boom"))
        assertEquals("ComfyUI: required nodes missing — boom",
            TaskStatusHelpers.formatFailureText("ComfyUI: required nodes missing — {0}", "boom"))
    }

    @Test fun formatFailureTextDropsSuffixWhenArgNull() {
        assertEquals("ComfyUI: required nodes missing",
            TaskStatusHelpers.formatFailureText("ComfyUI: required nodes missing — %1\$s", null))
        assertEquals("ComfyUI: required nodes missing",
            TaskStatusHelpers.formatFailureText("ComfyUI: required nodes missing — {0}", null))
        assertEquals("GPU out of memory",
            TaskStatusHelpers.formatFailureText("GPU out of memory", null))
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU retry policy — retryLine / formatRetryCountdown
    // ═══════════════════════════════════════════════════════════════

    @Test fun retryLineReturnsNullWhenNotRetrying() {
        val now = System.currentTimeMillis()
        assertNull(TaskStatusHelpers.retryLine(false, 1, 3, now + 60_000, now))
    }

    @Test fun retryLinePastDueReturnsNow() {
        val now = System.currentTimeMillis()
        val rl = TaskStatusHelpers.retryLine(true, 2, 5, now - 1000, now)!!
        assertEquals("task_retrying_now", rl.key)
        assertTrue(rl.args.isEmpty())
    }

    @Test fun retryLineFutureReturnsCountdown() {
        val now = System.currentTimeMillis()
        val rl = TaskStatusHelpers.retryLine(true, 2, 5, now + 90_000, now)!!
        assertEquals("task_retrying", rl.key)
        assertEquals(listOf(2, 5, "01:30"), rl.args)
    }

    @Test fun retryLineUsesDefaultsWhenNull() {
        val now = System.currentTimeMillis()
        val rl = TaskStatusHelpers.retryLine(true, null, null, now + 60_000, now)!!
        assertEquals("task_retrying", rl.key)
        assertEquals(listOf(0, 0, "01:00"), rl.args)
    }

    @Test fun formatRetryCountdownNeverBelowZero() {
        assertEquals("00:00", TaskStatusHelpers.formatRetryCountdown(-5000))
        assertEquals("00:00", TaskStatusHelpers.formatRetryCountdown(0))
        assertEquals("00:05", TaskStatusHelpers.formatRetryCountdown(5000))
        assertEquals("01:30", TaskStatusHelpers.formatRetryCountdown(90_000))
        assertEquals("10:00", TaskStatusHelpers.formatRetryCountdown(600_000))
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU retry policy — failedSceneCaption
    // ═══════════════════════════════════════════════════════════════

    private fun chapter(id: String, title: String?, scenes: List<Scene> = emptyList()) =
        Chapter(chapter_id = id, chapter_title = title, scenes = scenes)

    private fun scene(id: String, title: String?, idx: Int, type: String? = null) =
        Scene(scene_id = id, scene_title = title, type = type, display_index = idx)

    @Test fun failedSceneCaptionResolvesScene() {
        val ch = chapter("ch-1", "Chapter 1", listOf(scene("sc-1", "The Forest", 1)))
        val fs = FailedSceneRef(chapter_id = "ch-1", scene_id = "sc-1")
        assertEquals("Scene 1 — The Forest",
            TaskStatusHelpers.failedSceneCaption(listOf(ch), fs))
    }

    @Test fun failedSceneCaptionSceneWithoutTitle() {
        val ch = chapter("ch-1", "Chapter 1", listOf(scene("sc-1", null, 1)))
        val fs = FailedSceneRef(chapter_id = "ch-1", scene_id = "sc-1")
        assertEquals("Scene 1", TaskStatusHelpers.failedSceneCaption(listOf(ch), fs))
    }

    @Test fun failedSceneCaptionCover() {
        val ch = chapter("ch-1", "Chapter 1", listOf(scene("sc-0", null, 0, "cover")))
        val fs = FailedSceneRef(chapter_id = "ch-1", scene_id = "sc-0")
        assertEquals("Cover", TaskStatusHelpers.failedSceneCaption(listOf(ch), fs))
    }

    @Test fun failedSceneCaptionFallsBackToIdsWhenSceneMissing() {
        val ch = chapter("ch-1", "Chapter 1", listOf(scene("sc-1", "The Forest", 1)))
        val fs = FailedSceneRef(chapter_id = "ch-1", scene_id = "sc-99")
        assertEquals("ch-1/sc-99", TaskStatusHelpers.failedSceneCaption(listOf(ch), fs))
    }

    @Test fun failedSceneCaptionFallsBackToIdsWhenChapterMissing() {
        val fs = FailedSceneRef(chapter_id = "ch-99", scene_id = "sc-1")
        assertEquals("ch-99/sc-1", TaskStatusHelpers.failedSceneCaption(emptyList(), fs))
    }

    @Test fun failedSceneCaptionNullFailedScene() {
        assertNull(TaskStatusHelpers.failedSceneCaption(emptyList(), null))
        assertNull(TaskStatusHelpers.failedSceneCaption(null, null))
    }

    @Test fun failedSceneCaptionMissingChapterOrSceneIdReturnsNull() {
        assertNull(TaskStatusHelpers.failedSceneCaption(emptyList(),
            FailedSceneRef(chapter_id = "ch-1", scene_id = null)))
        assertNull(TaskStatusHelpers.failedSceneCaption(emptyList(),
            FailedSceneRef(chapter_id = null, scene_id = "sc-1")))
    }
}
