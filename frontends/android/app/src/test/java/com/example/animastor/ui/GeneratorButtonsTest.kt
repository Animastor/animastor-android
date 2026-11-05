package com.example.animastor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every Generator button against the click guard — integration E §6
 * (reports/2026-11-04/integration-A-B-C.md). Web parity:
 * frontends/app/src/pages/GeneratePage.commands.test.tsx (same cases).
 *
 * The fragment's click handlers are pinned by a source guard (the fragment
 * needs a device to run); the command bodies by the pure GeneratorCommands /
 * TaskStatusHelpers that the handlers and GenerateViewModel use.
 */
class GeneratorButtonsTest {

    private val ch = "ch-5e5a8c76"
    private val sc = "sc-4bb85e02"

    private fun mainSource(rel: String): String {
        val candidates = listOf(
            File("src/main/java/com/example/animastor/$rel"),
            File("app/src/main/java/com/example/animastor/$rel"),
        )
        val f = candidates.firstOrNull { it.isFile } ?: error("source not found: $rel (cwd=${File(".").absolutePath})")
        return f.readText()
    }

    private fun mainSources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    /** Body of `private fun <name>(` up to the next top-level member of the class. */
    private fun funBody(src: String, name: String): String {
        val start = src.indexOf("private fun $name(")
        assertTrue("function $name not found", start >= 0)
        val next = Regex("\n    (private |override |internal |public )?fun ").find(src, start + 10)
        return src.substring(start, next?.range?.first ?: src.length)
    }

    // ── command bodies ──────────────────────────────────────────

    @Test fun videoFromCurrentSceneSendsChapterAndScene() {
        val (chId, scId) = GeneratorCommands.scopeTargets("from_current_scene", ch, sc)
        val r = GeneratorCommands.request(listOf("video"), "from_current_scene", chId, scId,
            GeneratorCommands.userAction(GeneratorCommands.SOURCE_SCOPE_DIALOG))
        assertEquals(listOf("video"), r.worker_types)
        assertEquals("from_current_scene", r.scope)
        assertEquals(ch, r.chapter_id)
        assertEquals(sc, r.scene_id)
        assertEquals(true, r.rebuild_all)
        assertEquals("generator.scope_dialog", r.client_action?.source)
    }

    @Test fun imageWholeBookIsRebuildAllWithoutTargets() {
        val (chId, scId) = GeneratorCommands.scopeTargets("whole_book", ch, sc)
        val r = GeneratorCommands.request(listOf("image"), "whole_book", chId, scId,
            GeneratorCommands.userAction(GeneratorCommands.SOURCE_SCOPE_DIALOG))
        assertEquals(listOf("image"), r.worker_types)
        assertEquals("whole_book", r.scope)
        assertEquals(true, r.rebuild_all)
        assertNull(r.chapter_id)
        assertNull(r.scene_id)
    }

    @Test fun everyStoppedRowRepeatsItsOwnCommand() {
        for (state in listOf(TaskRowState.FAILED, TaskRowState.BLOCKED, TaskRowState.INTERRUPTED)) {
            val cmd = TaskStatusHelpers.retryCommandFor(state, "audio", "current_chapter", ch, null)
            assertNotNull("$state must be repeatable", cmd)
            assertEquals(listOf("audio"), cmd!!.workerTypes)
            assertEquals("current_chapter", cmd.scope)
            assertEquals(ch, cmd.chapterId)
            assertNull(cmd.sceneId)
        }
        // the cover row repeats as an image command
        assertEquals(listOf("image"), TaskStatusHelpers.retryCommandFor(TaskRowState.FAILED, "cover", "whole_book", ch, sc)!!.workerTypes)
        // running / done / cancelled rows have no «Повторить» command
        for (state in listOf(TaskRowState.ACTIVE, TaskRowState.DONE, TaskRowState.CANCELLED)) {
            assertNull(TaskStatusHelpers.retryCommandFor(state, "audio", "current_chapter", ch, null))
        }
    }

    // ── click handlers (source guard) ───────────────────────────

    @Test fun eachLayerButtonSendsOneCommandWithAScopeDialogClick() {
        val src = mainSource("ui/GenerateFragment.kt")
        for ((fn, type) in listOf(
            "onGenerateAudioClicked" to "audio",
            "onGenerateImageClicked" to "image",
            "onGenerateVideoClicked" to "video",
        )) {
            val body = funBody(src, fn)
            assertTrue("$fn opens the scope dialog", body.contains("showScopeDialog {"))
            assertEquals("$fn: exactly one startGeneration", 1, Regex("viewModel\\.startGeneration\\(").findAll(body).count())
            assertTrue("$fn: worker type $type", body.contains("workerTypes = listOf(\"$type\")"))
            assertTrue("$fn: UserAction of the dialog click",
                body.contains("GeneratorCommands.userAction(GeneratorCommands.SOURCE_SCOPE_DIALOG)"))
        }
        // nowhere else in the fragment
        assertEquals(3, Regex("viewModel\\.startGeneration\\(").findAll(src).count())
    }

    @Test fun retryRowSendsOneCommandWithARetryClick() {
        val body = funBody(mainSource("ui/GenerateFragment.kt"), "onRetryRow")
        assertEquals(1, Regex("viewModel\\.retryStoppedTask\\(").findAll(body).count())
        assertTrue(body.contains("GeneratorCommands.userAction(GeneratorCommands.SOURCE_RETRY_ROW)"))
    }

    @Test fun generateAllAndVBookAreTheVBookWindowNotAGpuCommand() {
        val src = mainSource("ui/GenerateFragment.kt")
        val all = funBody(src, "onGenerateAllClicked")
        assertTrue(all.contains("showScopeDialog {"))
        assertTrue("«Генерировать всё» → VBook", all.contains("onGenerateVBookClicked()"))
        assertFalse(all.contains("startGeneration("))
        val vbook = funBody(src, "onGenerateVBookClicked")
        assertEquals(1, Regex("viewModel\\.startVBookGeneration\\(\\)").findAll(vbook).count())
        assertFalse(vbook.contains("startGeneration("))
        // the VBook path calls bootstrap / bootstrap-next-window, never /regenerate
        val vm = mainSource("ui/GenerateViewModel.kt")
        val start = vm.indexOf("fun startVBookGeneration(")
        assertTrue(start >= 0)
        val vbookVm = vm.substring(start, vm.indexOf("\n    fun ", start + 10).takeIf { it > 0 } ?: vm.length)
        assertTrue(vbookVm.contains("bootstrapBook(") && vbookVm.contains("bootstrapNextWindow("))
        assertFalse(vbookVm.contains("regenerateBook"))
    }

    @Test fun stopButtonsUseTheirOwnEndpoints() {
        val src = mainSource("ui/GenerateFragment.kt")
        val stopAll = funBody(src, "onStopAllClicked")
        assertTrue(stopAll.contains("viewModel.cancelGeneration()"))
        val stop = funBody(src, "onStopClicked")
        assertTrue(stop.contains("viewModel.cancelTask(type)"))
        for (body in listOf(stopAll, stop)) {
            assertFalse(body.contains("startGeneration(") || body.contains("retryStoppedTask(") || body.contains("regenerate"))
        }
    }

    @Test fun onlyTheGuardedViewModelPathReachesRegenerate() {
        // regenerateBookScoped: only GenerateViewModel.startGeneration (behind mayStart);
        // the unscoped regenerateBook (no client_action) has no caller in the app.
        val scopedCallers = mutableListOf<String>()
        val unscopedCallers = mutableListOf<String>()
        for (f in mainSources()) {
            if (f.name == "Repository.kt" || f.name == "BackendApi.kt") continue
            val text = f.readText()
            if (text.contains(".regenerateBookScoped(")) scopedCallers += f.name
            if (Regex("\\.regenerateBook\\(").containsMatchIn(text)) unscopedCallers += f.name
        }
        assertEquals(listOf("GenerateViewModel.kt"), scopedCallers)
        assertEquals(emptyList<String>(), unscopedCallers)
        val vm = mainSource("ui/GenerateViewModel.kt")
        val guard = vm.indexOf("if (!GeneratorCommands.mayStart(action))")
        val call = vm.indexOf("_repository.regenerateBookScoped(")
        assertTrue("mayStart guard precedes the request", guard in 0 until call)
        assertEquals(1, Regex("_repository\\.regenerateBookScoped\\(").findAll(vm).count())
    }

    // ── Задача K (2026-11-05): stopped row = progress row + ▶ / ■ icons ──

    private fun resSource(rel: String): String {
        val f = listOf(File("src/main/res/$rel"), File("app/src/main/res/$rel")).firstOrNull { it.isFile }
            ?: error("resource not found: $rel")
        return f.readText()
    }

    /** The stopped-row branch of renderTaskRowsToSections. */
    private fun stoppedBranch(): String {
        val src = mainSource("ui/GenerateFragment.kt")
        val start = src.indexOf("} else if (TaskStatusHelpers.isStopped(taskRow.state)) {")
        assertTrue("one stopped branch for failed / blocked / interrupted", start >= 0)
        val end = src.indexOf("} else if (taskRow.indeterminate) {", start)
        assertTrue(end > start)
        return src.substring(start, end)
    }

    @Test fun stoppedRowRendersProgressAndAShortStatus() {
        val b = stoppedBranch()
        for (v in listOf("workerCount", "workerPercent", "workerTimer", "workerProgressBar")) {
            assertTrue("$v visible", b.contains("rowBinding.$v.visibility = View.VISIBLE"))
        }
        assertTrue(b.contains("setProgressCompat(pct, false)"))
        assertTrue(b.contains("isIndeterminate = false"))
        assertTrue(b.contains("rowBinding.workerName.text = scopedTaskLabel(taskRow)"))
        assertTrue(b.contains("rowBinding.workerTimer.text = status"))
        for (r in listOf("task_status_failed", "task_status_blocked", "task_status_interrupted")) {
            assertTrue(r, b.contains("R.string.$r"))
        }
        assertTrue(b.contains("bindStoppedRowButtons(rowBinding, taskRow, now)"))
        // the timer tick never overwrites the status
        val tick = funBody(mainSource("ui/GenerateFragment.kt"), "refreshTimerDisplay")
        assertTrue(tick.contains("TaskStatusHelpers.isStopped(tag.state)) continue"))
    }

    @Test fun stoppedRowHasTwoIconButtonsWithDescriptionsAndNoBigButton() {
        val layout = resSource("layout/item_worker_progress.xml")
        assertFalse("big «Продолжить» removed", layout.contains("workerRetryButton"))
        assertFalse(mainSource("ui/GenerateFragment.kt").contains("workerRetryButton"))
        val cont = layout.substring(layout.indexOf("@+id/workerContinueButton"))
        val stop = layout.substring(layout.indexOf("@+id/workerStopButton"))
        for (attr in listOf("android:layout_width=\"32dp\"", "android:layout_height=\"32dp\"", "android:padding=\"6dp\"")) {
            assertTrue("▶ $attr", cont.substringBefore("/>").contains(attr))
            assertTrue("■ $attr", stop.substringBefore("/>").contains(attr))
        }
        assertTrue(cont.substringBefore("/>").contains("@drawable/ic_play"))
        val bind = funBody(mainSource("ui/GenerateFragment.kt"), "bindStoppedRowButtons")
        assertTrue(bind.contains("workerContinueButton.contentDescription = getString(R.string.task_continue)"))
        assertTrue(bind.contains("workerStopButton.contentDescription = getString(R.string.task_dismiss)"))
        for (lang in listOf("values", "values-ru")) {
            val strings = resSource("$lang/strings.xml")
            for (n in listOf("task_status_interrupted", "task_status_blocked", "task_status_failed", "task_dismiss", "task_dismiss_failed")) {
                assertTrue("$lang/$n", strings.contains("<string name=\"$n\">"))
            }
        }
    }

    @Test fun stoppedRowPlaySendsOneGuardedRetry() {
        val src = mainSource("ui/GenerateFragment.kt")
        val play = funBody(src, "onContinueStoppedRow")
        assertTrue(play.indexOf("stoppedRowGuard.accept(") in 0 until play.indexOf("onRetryRow("))
        assertEquals(1, Regex("onRetryRow\\(").findAll(play).count())
        assertFalse(play.contains("cancelTask(") || play.contains("dismissStoppedTask("))
    }

    @Test fun stoppedRowStopCancelsThatTaskAndNeverRegenerates() {
        val src = mainSource("ui/GenerateFragment.kt")
        val stop = funBody(src, "onDismissStoppedRow")
        assertTrue(stop.indexOf("stoppedRowGuard.accept(") in 0 until stop.indexOf("viewModel.dismissStoppedTask("))
        assertEquals(1, Regex("viewModel\\.dismissStoppedTask\\(").findAll(stop).count())
        for (bad in listOf("startGeneration(", "retryStoppedTask(", "onRetryRow(", "regenerate")) {
            assertFalse(bad, stop.contains(bad))
        }
        // success → the row disappears (dismissed ids are skipped on render)
        assertTrue(stop.contains("if (ok) renderTaskRowsToSections(lastRenderedRows)"))
        assertTrue(src.contains("if (taskRow.taskId != null && taskRow.taskId in dismissed) continue"))
        // the ViewModel: the existing cancel (cancel-worker by task_id), no /regenerate
        val vm = mainSource("ui/GenerateViewModel.kt")
        val start = vm.indexOf("fun dismissStoppedTask(")
        val body = vm.substring(start, vm.indexOf("\n    }", start))
        assertTrue(body.contains("cancelTask(row.type, taskId)"))
        assertTrue(body.contains("_dismissedTaskIds.add(taskId)"))
        assertFalse(body.contains("startGeneration(") || body.contains("regenerate"))
        val cancel = vm.substring(vm.indexOf("fun cancelTask("), vm.indexOf("fun dismissStoppedTask("))
        assertTrue(cancel.contains("_repository.cancelWorker(bookId, type, taskId)"))
        assertTrue(cancel.contains("GeneratorCommands.dismissSucceeded(taskId, res.task_ids)"))
    }
}
