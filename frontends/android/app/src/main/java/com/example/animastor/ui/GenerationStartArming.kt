package com.example.animastor.ui

// A generation start arms the timer and opens the progress stream BEFORE the
// POST /regenerate. When the server refuses the command up front — 409
// layer_disabled, 422 book_has_no_text — no task exists: the timer must not
// tick and the stream must be closed. Pure logic (no Android framework);
// GenerateViewModel implements the port over its own fields.
// Web parity: frontends/app/src/state/generateStore.ts — ArmedStart /
// settleRefusedStart (web d348145, stage171).

/** The generation session as startGeneration touches it. */
interface GenerationStartPort {
    /** > 0 — running since (ms); 0 — never started; -1 — stopped. */
    var timerStartedAt: Long
    var finalElapsedSeconds: Long
    /** A generation session is running. */
    var isRegenerating: Boolean
    /** A new generation was started; the panel waits for its first progress. */
    var newGenerationPending: Boolean
    /** The bottom-nav status (pulse). */
    var generationStatus: GenerationStatus
    fun startTimer()
    fun startProgressStream(bookId: String)
    fun stopProgressStream()
}

/** What one click armed before the POST, and the session as it was before it. */
data class ArmedStart(
    /** The click started the timer (no generation was being timed). */
    val startedTimer: Boolean,
    /** The timer as it was before the click (restored on a refusal). */
    val timerStartedAtBefore: Long,
    val finalElapsedSecondsBefore: Long,
    /** A generation was already running: a refusal leaves it alone. */
    val sessionWasRunning: Boolean,
    val statusBefore: GenerationStatus,
    val newGenerationPendingBefore: Boolean,
)

object GenerationStartArming {
    /**
     * Record the session as it is, then arm it: status RUNNING, isRegenerating,
     * newGenerationPending, the timer (when nothing is timed yet) and the
     * progress stream (re)opened.
     */
    fun arm(port: GenerationStartPort, bookId: String): ArmedStart {
        val armed = ArmedStart(
            startedTimer = port.timerStartedAt <= 0L,
            timerStartedAtBefore = port.timerStartedAt,
            finalElapsedSecondsBefore = port.finalElapsedSeconds,
            sessionWasRunning = port.isRegenerating,
            statusBefore = port.generationStatus,
            newGenerationPendingBefore = port.newGenerationPending,
        )
        port.generationStatus = GenerationStatus.RUNNING
        port.isRegenerating = true
        // Don't reset _generationCompleted — newGenerationPending gates the UI
        // until the server reports actual new workers (no stale green 100% flash).
        port.newGenerationPending = true
        if (armed.startedTimer) port.startTimer()
        port.startProgressStream(bookId)
        return armed
    }

    /**
     * The server refused the command up front (nothing runs). Web parity:
     * generateStore.settleRefusedStart.
     *  - No generation was running before the click: status IDLE, flags false,
     *    the stream the click opened is closed.
     *  - A generation was running (post171): its flags and status go back to
     *    what they were before the click, its stream stays.
     *  - Either way the timer the click started goes back to its previous
     *    value (a stop would show a fresh 0:00 "final" time).
     * NOT for transport errors / 429 — those do not prove that nothing runs.
     */
    fun settleRefused(port: GenerationStartPort, armed: ArmedStart) {
        if (armed.sessionWasRunning) {
            port.isRegenerating = true
            port.newGenerationPending = armed.newGenerationPendingBefore
            port.generationStatus = armed.statusBefore
        } else {
            port.isRegenerating = false
            port.newGenerationPending = false
            port.generationStatus = GenerationStatus.IDLE
            port.stopProgressStream()
        }
        if (armed.startedTimer) {
            port.timerStartedAt = armed.timerStartedAtBefore
            port.finalElapsedSeconds = armed.finalElapsedSecondsBefore
        }
    }
}
