package com.example.animastor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A refused generation start leaves no ticking timer and no open progress
 * stream (stage171). Web parity: frontends/app/src/state/generateStore.refusal.test.ts.
 * The fake session keeps the timer exactly like GenerateViewModel
 * (startTimer / stopTimer / elapsed) on a controlled clock.
 */
class GenerationStartArmingTest {

    private class FakeSession : GenerationStartPort {
        var now = 1_000_000L
        override var timerStartedAt = 0L
        override var finalElapsedSeconds = 0L
        override var isRegenerating = false
        override var newGenerationPending = false
        override var generationStatus = GenerationStatus.IDLE
        var streamOpen = false
        var streamStarts = 0

        override fun startTimer() { timerStartedAt = now; finalElapsedSeconds = 0L }
        fun stopTimer() {
            if (timerStartedAt > 0L) finalElapsedSeconds = (now - timerStartedAt) / 1000L
            timerStartedAt = -1L
        }
        /** GenerateViewModel's timer text source: live while running, else the final value. */
        fun elapsed(): Long = if (timerStartedAt > 0L) (now - timerStartedAt) / 1000L else finalElapsedSeconds
        override fun startProgressStream(bookId: String) { streamOpen = true; streamStarts++ }
        override fun stopProgressStream() { streamOpen = false }
    }

    @Test fun `refused start - the timer does not tick and the stream is closed`() {
        val s = FakeSession()
        val armed = GenerationStartArming.arm(s, "book")
        s.isRegenerating = true
        assertTrue(s.streamOpen)
        assertTrue(s.timerStartedAt > 0L)

        GenerationStartArming.settleRefused(s, armed)
        s.isRegenerating = false
        s.now += 7_000
        assertTrue(s.timerStartedAt <= 0L)
        assertEquals(0L, s.elapsed())
        assertFalse(s.streamOpen)
    }

    @Test fun `refused start keeps the previous final time - not a fresh 0 00`() {
        val s = FakeSession()
        s.startTimer(); s.now += 42_000; s.stopTimer() // an earlier generation: 42 s
        val armed = GenerationStartArming.arm(s, "book")
        s.now += 3_000
        GenerationStartArming.settleRefused(s, armed)
        s.now += 5_000
        assertEquals(42L, s.elapsed())
        assertEquals(-1L, s.timerStartedAt)
    }

    @Test fun `a running generation keeps its timer and stream when a second start is refused`() {
        val s = FakeSession()
        GenerationStartArming.arm(s, "book")
        s.isRegenerating = true
        val startedAt = s.timerStartedAt
        s.now += 3_000

        val second = GenerationStartArming.arm(s, "book")
        assertFalse(second.startedTimer)
        GenerationStartArming.settleRefused(s, second)
        s.now += 2_000
        assertEquals(startedAt, s.timerStartedAt)
        assertEquals(5L, s.elapsed())
        assertTrue(s.streamOpen)
    }

    @Test fun `no generation running - a refusal leaves IDLE, flags false (web parity)`() {
        val s = FakeSession()
        val armed = GenerationStartArming.arm(s, "book")
        assertEquals(GenerationStatus.RUNNING, s.generationStatus)
        assertTrue(s.isRegenerating && s.newGenerationPending)
        GenerationStartArming.settleRefused(s, armed)
        assertEquals(GenerationStatus.IDLE, s.generationStatus)
        assertFalse(s.isRegenerating)
        assertFalse(s.newGenerationPending)
    }

    @Test fun `a running generation keeps its flags and status when a second start is refused`() {
        val s = FakeSession()
        GenerationStartArming.arm(s, "book")
        s.newGenerationPending = false // the running generation already reported progress
        val before = Triple(s.isRegenerating, s.newGenerationPending, s.generationStatus)
        assertEquals(Triple(true, false, GenerationStatus.RUNNING), before)

        val second = GenerationStartArming.arm(s, "book")
        assertTrue(second.sessionWasRunning)
        GenerationStartArming.settleRefused(s, second)
        assertEquals(before, Triple(s.isRegenerating, s.newGenerationPending, s.generationStatus))
        assertTrue(s.streamOpen)
    }

    // ── Wiring in GenerateViewModel (source guards) ──
    private val vm by lazy { File("src/main/java/com/example/animastor/ui/GenerateViewModel.kt").readText() }

    private fun branch(name: String): String {
        val start = vm.indexOf("is TaskStatusHelpers.StartError.$name ->")
        assertTrue("branch $name", start >= 0)
        val next = vm.indexOf("is TaskStatusHelpers.StartError.", start + 10).let { if (it < 0) vm.length else it }
        val end = minOf(next, vm.indexOf("\n                    }\n", start).let { if (it < 0) vm.length else it })
        return vm.substring(start, end)
    }

    @Test fun `LayerDisabled and BookHasNoText settle the refused start, the generic failure does not`() {
        assertTrue(branch("LayerDisabled").contains("settleRefusedStart(armed)"))
        assertTrue(branch("BookHasNoText").contains("settleRefusedStart(armed)"))
        // network / 429 / anything else: unchanged (does not prove that nothing runs)
        assertFalse(branch("Other").contains("settleRefusedStart"))
        assertEquals(2, Regex("""settleRefusedStart\(armed\)""").findAll(vm).count())
    }

    @Test fun `the ViewModel port writes the real session state`() {
        assertTrue(vm.contains("set(v) { _isRegenerating.value = v }"))
        assertTrue(vm.contains("set(v) { _newGenerationPending = v }"))
        assertTrue(vm.contains("set(v) { _generationStatus.value = v }"))
    }

    @Test fun `startGeneration arms through GenerationStartArming only`() {
        val start = vm.indexOf("val clickAction = action!!")
        val launch = vm.indexOf("viewModelScope.launch {", start)
        assertTrue(start >= 0 && launch > start)
        val region = vm.substring(start, launch)
        assertTrue(region.contains("val armed = GenerationStartArming.arm(startPort, bookId)"))
        // RUNNING, the flags, the timer and the stream are set by arm() — after it recorded the session
        for (direct in listOf("_generationStatus.value = GenerationStatus.RUNNING", "_isRegenerating.value = true",
                "_newGenerationPending = true", "startTimer()", "startProgressStream(bookId)")) {
            assertFalse("direct '$direct' in startGeneration", region.contains(direct))
        }
    }
}
