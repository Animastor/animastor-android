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
}
