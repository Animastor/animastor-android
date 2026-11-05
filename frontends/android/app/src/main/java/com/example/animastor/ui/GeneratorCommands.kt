package com.example.animastor.ui

import com.example.animastor.repository.ClientAction
import com.example.animastor.repository.RegenerateRequest
import java.security.SecureRandom

/**
 * Generator GPU commands are sent ONLY by explicit clicks
 * (reports/2026-11-04/regenerate-sweep-fix.md; web parity:
 * frontends/app/src/state/generateStore.ts `userAction` / `startGeneration`).
 *
 *  - A command (POST /regenerate) needs a [UserAction] built in the click
 *    handler («Старт» of the scope dialog, «Повторить» of a stopped row).
 *    Observers, timers, polls, SSE and restore paths have none and cannot
 *    send a command ([mayStart] is false → nothing is requested).
 *  - The action travels to the backend as the additive `client_action`
 *    (ignored by older backends) so a command can be traced to its button.
 *  - «Старт» ignores activations during the first [SCOPE_DIALOG_ARM_MS] after
 *    the dialog appeared: the tap that opened it (or a double tap on
 *    «Генерировать …») never also confirms it.
 *
 * Pure (no Android types) — covered by GeneratorCommandsTest.
 */
object GeneratorCommands {
    const val SCOPE_DIALOG_ARM_MS = 400L
    /** Задача K — ▶ / ■ of a stopped row: the same 400 ms as the dialog. */
    const val STOPPED_ROW_ARM_MS = 400L
    const val SOURCE_SCOPE_DIALOG = "generator.scope_dialog"
    const val SOURCE_RETRY_ROW = "generator.retry_row"

    data class UserAction(
        val source: String,
        val actionId: String,
        val trusted: Boolean,
        val at: Long,
    )

    private val random = SecureRandom()

    /** Build the action of a click — call ONLY inside a click handler. */
    fun userAction(source: String, now: Long = System.currentTimeMillis()): UserAction {
        val bytes = ByteArray(8).also { random.nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        // A real View click on Android — there is no synthetic-event flag.
        return UserAction(source = source, actionId = "ua-$hex", trusted = true, at = now)
    }

    /** Fail-closed gate of startGeneration: no click → no command. */
    fun mayStart(action: UserAction?): Boolean =
        action != null && action.source.isNotBlank() && action.actionId.isNotBlank()

    /** Is «Старт» armed (outside the tap that opened the dialog)? */
    fun armed(openedAt: Long, now: Long): Boolean = now - openedAt >= SCOPE_DIALOG_ARM_MS

    /** Targets of a scope choice: whole_book → none, current_chapter → chapter,
     *  current_scene / from_current_scene → chapter + scene (web ScopeDialog). */
    fun scopeTargets(scope: String, posChapterId: String?, posSceneId: String?): Pair<String?, String?> {
        val chId = if (scope != "whole_book") posChapterId else null
        val scId = if (scope == "current_scene" || scope == "from_current_scene") posSceneId else null
        return chId to scId
    }

    fun clientAction(action: UserAction): ClientAction =
        ClientAction(kind = "user_click", source = action.source, action_id = action.actionId, trusted = action.trusted)

    /** The exact /regenerate body of a Generator command. */
    fun request(
        workerTypes: List<String>,
        scope: String,
        chapterId: String?,
        sceneId: String?,
        action: UserAction,
    ): RegenerateRequest = RegenerateRequest(
        new_book = null,
        rebuild_all = true,
        worker_types = workerTypes,
        scope = scope,
        chapter_id = chapterId,
        scene_id = sceneId,
        client_action = clientAction(action),
    )

    /** Задача K — «■ Снять» of a stopped row succeeded only when the backend
     *  listed that task in `task_ids` (an older backend answers `[]` for a
     *  non-active task: nothing was cancelled, the row stays). */
    fun dismissSucceeded(taskId: String?, taskIds: List<String>?): Boolean =
        taskId != null && taskIds?.contains(taskId) == true

    /**
     * Задача K (2026-11-05) — double-tap guard of the ▶ «Продолжить» /
     * ■ «Снять» icons of a stopped row (web parity: StoppedRow in
     * GeneratePage.tsx). Rows are re-inflated on every poll, so the state
     * lives here, keyed by row (task id + state):
     *  - taps during the first [armMs] after the stopped row first appeared
     *    are ignored (the tap on the active ■, a double tap);
     *  - after an accepted tap the row ignores taps for [armMs];
     *  - while the row's request is in flight every tap is ignored.
     */
    class StoppedRowGuard(private val armMs: Long = STOPPED_ROW_ARM_MS) {
        private val firstSeen = HashMap<String, Long>()
        private val nextAllowed = HashMap<String, Long>()
        private val inFlight = HashSet<String>()

        /** The row is on screen now (call on every render). */
        fun onRendered(key: String, now: Long) { firstSeen.putIfAbsent(key, now) }

        /** Forget rows no longer on screen. */
        fun retain(keys: Set<String>) {
            firstSeen.keys.retainAll(keys)
            nextAllowed.keys.retainAll(keys)
        }

        fun accept(key: String, now: Long): Boolean {
            if (key in inFlight) return false
            val seen = firstSeen.getOrPut(key) { now }
            if (now - seen < armMs) return false
            if (now < (nextAllowed[key] ?: 0L)) return false
            nextAllowed[key] = now + armMs
            return true
        }

        fun begin(key: String) { inFlight.add(key) }
        fun end(key: String) { inFlight.remove(key) }
    }

    fun stoppedRowKey(taskId: String?, type: String, chapterId: String?, sceneId: String?, state: TaskRowState): String =
        "${taskId ?: type}|${chapterId ?: ""}|${sceneId ?: ""}|$state"
}
