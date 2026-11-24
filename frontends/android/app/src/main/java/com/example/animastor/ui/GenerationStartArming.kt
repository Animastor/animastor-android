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
    /** A generation session is already running. */
    val isRegenerating: Boolean
    fun startTimer()
    fun startProgressStream(bookId: String)
    fun stopProgressStream()
}

/** What one click armed before the POST. */
data class ArmedStart(
    /** The click started the timer (no generation was being timed). */
    val startedTimer: Boolean,
    /** The timer as it was before the click (restored on a refusal). */
    val timerStartedAtBefore: Long,
    val finalElapsedSecondsBefore: Long,
    /** A session was already running — its progress stream stays. */
    val hadSession: Boolean,
)

object GenerationStartArming {
    /**
     * Record the state, then arm: start the timer when nothing is timed yet
     * and (re)open the progress stream. Call BEFORE the session flags are set
     * (isRegenerating must still say whether a generation was running).
     */
    fun arm(port: GenerationStartPort, bookId: String): ArmedStart {
        val armed = ArmedStart(
            startedTimer = port.timerStartedAt <= 0L,
            timerStartedAtBefore = port.timerStartedAt,
            finalElapsedSecondsBefore = port.finalElapsedSeconds,
            hadSession = port.isRegenerating,
        )
        if (armed.startedTimer) port.startTimer()
        port.startProgressStream(bookId)
        return armed
    }

    /**
     * The server refused the command up front (nothing runs): the timer the
     * click started goes back to its previous value (a stop would show a fresh
     * 0:00 "final" time), the stream it opened is closed unless a generation
     * was already running. NOT for transport errors / 429 — those do not prove
     * that nothing runs.
     */
    fun settleRefused(port: GenerationStartPort, armed: ArmedStart) {
        if (armed.startedTimer) {
            port.timerStartedAt = armed.timerStartedAtBefore
            port.finalElapsedSeconds = armed.finalElapsedSecondsBefore
        }
        if (!armed.hadSession) port.stopProgressStream()
    }
}
