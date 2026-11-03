package com.example.animastor.ui

import com.example.animastor.repository.Chapter
import com.example.animastor.repository.FailedSceneRef
import com.google.gson.JsonParser

// ═══════════════════════════════════════════════════════════════
//  Task status helpers — Stage 155 «генерация только по команде»
// ═══════════════════════════════════════════════════════════════
//  Backend Stage 155 (animastor-docs architecture/COMMAND_ONLY_GENERATION.md):
//  GET /book/{id}/progress-panel rows carry the registry task status
//    status          active | completed | cancelled | blocked | interrupted |
//                    failed
//    blocked         no live worker of the type (blocked_reason no_worker:<type>)
//    interrupted     backend restart
//    failed          terminally failed command (+ error_class / error_code /
//                    error_message / failed_scene) — GPU retry policy 2026-11-02
//    retrying        an active row waiting out a server-side retry pause
//                    (+ retry_no / retry_max / retry_at)
//  blocked / interrupted / failed rows are visible but NOT running: no spinner,
//  no timer, no auto-retry — the user sends a NEW command (same type + scope).
//  A retrying row keeps the spinner: the SERVER retries.
//  POST /regenerate answers 409 {error:"layer_disabled", disabled_worker_types}
//  when every requested layer is off.
//  Pure Kotlin (no Android framework) — mirror of web
//  @animastor/web-generator taskStatus.ts + app features/generation/commandErrors.ts
//  (ANDROID_WEB_PARITY rule 3).
// ═══════════════════════════════════════════════════════════════

/** Display state of one progress row. */
enum class TaskRowState { ACTIVE, DONE, CANCELLED, BLOCKED, INTERRUPTED, FAILED }

object TaskStatusHelpers {

    private val GPU_TYPES = setOf("audio", "image", "video")
    private val NO_WORKER = Regex("^no_worker:(audio|image|video)$")
    private val FAIL_SUFFIX = Regex("\\s*—\\s*(?:\\{0\\}|%1\\\$s)\\s*\$")

    /** Precedence: cancelled > blocked > interrupted > failed > done > active. */
    fun taskRowState(
        status: String?,
        done: Boolean,
        cancelled: Boolean,
        blocked: Boolean,
        interrupted: Boolean,
        failed: Boolean = false
    ): TaskRowState = when {
        cancelled || status == "cancelled" -> TaskRowState.CANCELLED
        blocked || status == "blocked" -> TaskRowState.BLOCKED
        interrupted || status == "interrupted" -> TaskRowState.INTERRUPTED
        failed || status == "failed" -> TaskRowState.FAILED
        done || status == "completed" -> TaskRowState.DONE
        else -> TaskRowState.ACTIVE
    }

    /** blocked / interrupted / failed: the command stopped and waits for a new one. */
    fun isStopped(state: TaskRowState): Boolean =
        state == TaskRowState.BLOCKED || state == TaskRowState.INTERRUPTED ||
            state == TaskRowState.FAILED

    /** The /regenerate worker type for a row type (the cover is an image task). */
    fun commandWorkerType(rowType: String): String? = when {
        rowType == "cover" -> "image"
        rowType in GPU_TYPES -> rowType
        else -> null
    }

    /** Missing worker type of a blocked row: "no_worker:image" → "image";
     *  falls back to the row type when the reason is absent/malformed. */
    fun blockedWorkerType(reason: String?, rowType: String): String? =
        NO_WORKER.matchEntire(reason?.trim() ?: "")?.groupValues?.get(1) ?: commandWorkerType(rowType)

    /** A new explicit command repeating a stopped row (same type + scope). */
    data class RetryCommand(
        val workerTypes: List<String>,
        val scope: String,
        val chapterId: String?,
        val sceneId: String?
    )

    fun retryCommandFor(
        state: TaskRowState,
        rowType: String,
        scope: String,
        chapterId: String?,
        sceneId: String?
    ): RetryCommand? {
        if (!isStopped(state)) return null
        val type = commandWorkerType(rowType) ?: return null
        val s = scope.ifBlank { "whole_book" }
        val ch = if (s == "whole_book") null else chapterId
        val sc = if (s == "current_scene" || s == "from_current_scene") sceneId else null
        return RetryCommand(listOf(type), s, ch, sc)
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU retry policy (reports/2026-11-02/gpu-retry-policy.md §ТЗ фронтам)
    // ═══════════════════════════════════════════════════════════════

    /** A failure reason as an i18n KEY (same key names as web i18n.ts) +
     *  argument (null → the text without the « — %1$s» suffix). */
    data class FailureText(val key: String, val arg: String?)

    /** First line only, ellipsis when the server text had more lines (the
     *  frontend never parses the message). */
    private fun firstLine(message: String?): String? {
        val s = message?.trim() ?: return null
        if (s.isEmpty()) return null
        val nl = s.indexOf('\n')
        return if (nl >= 0) s.slice(0 until nl).trimEnd() + "…" else s
    }

    /**
     * Classify a terminal failure into an i18n key + argument (the table in
     * §ТЗ фронтам п.3). `type` is the row type — accepted for signature parity
     * with web; the key selection does not depend on it.
     */
    fun failureText(
        errorClass: String?,
        errorCode: String?,
        errorMessage: String?,
        type: String
    ): FailureText {
        val arg = firstLine(errorMessage)
        val cls = errorClass?.trim()?.lowercase() ?: ""
        val code = errorCode?.trim()?.lowercase() ?: ""
        return when {
            code == "comfy_missing_node" -> FailureText("task_failed_comfy_nodes", arg)
            code == "comfy_model_missing" -> FailureText("task_failed_comfy_model", arg)
            cls == "deterministic" -> FailureText("task_failed_comfy_rejected", arg)
            cls == "transient" -> FailureText("task_failed_transient", arg)
            cls == "oom" -> FailureText("task_failed_oom", arg)
            else -> FailureText("task_failed_unknown", arg)
        }
    }

    /**
     * Render a failureText against a localized template. Understands both web
     * `{0}` and Android `%1$s` placeholders (so both fronts run the same
     * fixtures); an empty argument drops the « — …» suffix.
     */
    fun formatFailureText(template: String, arg: String?): String {
        if (arg == null) return template.replace(FAIL_SUFFIX, "")
        return template.replace("{0}", arg).replace("%1\$s", arg)
    }

    /** The retry-pause line as an i18n KEY + arguments (empty for
     *  `task_retrying_now`); mirror of web `retryLine`. */
    data class RetryLine(val key: String, val args: List<Any>)

    /**
     * «Повтор %1$d/%2$d через %3$s» while the pause runs; after `retryAt`
     * passes → «Повтор…» until the next progress-panel poll. null when the row
     * is not waiting out a pause. Driven by the CLIENT clock (`now`).
     */
    fun retryLine(
        retrying: Boolean,
        retryNo: Int?,
        retryMax: Int?,
        retryAt: Long?,
        now: Long
    ): RetryLine? {
        if (!retrying) return null
        if (retryAt == null || now >= retryAt) return RetryLine("task_retrying_now", emptyList())
        return RetryLine(
            "task_retrying",
            listOf(retryNo ?: 0, retryMax ?: 0, formatRetryCountdown(retryAt - now))
        )
    }

    /** mm:ss countdown for the retry pause, never below 00:00. */
    fun formatRetryCountdown(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0L)
        val mm = sec / 60
        val ss = sec % 60
        return "%02d:%02d".format(mm, ss)
    }

    /**
     * Caption of the scene a command failed on: the scene label computed from
     * the ALREADY LOADED book the way the backend computes `scene_label`
     * (`Scene 3 — Title` / `Cover`); `chapter_id/scene_id` when the scene is
     * not in the book; null when there is no failed_scene.
     */
    fun failedSceneCaption(chapters: List<Chapter>?, failedScene: FailedSceneRef?): String? {
        if (failedScene == null) return null
        val chapterId = failedScene.chapter_id
        val sceneId = failedScene.scene_id
        val fallback = if (!chapterId.isNullOrEmpty() && !sceneId.isNullOrEmpty()) "$chapterId/$sceneId" else null
        if (chapterId.isNullOrEmpty() || sceneId.isNullOrEmpty()) return fallback
        val ch = chapters?.firstOrNull { it.chapter_id == chapterId } ?: return fallback
        val sc = ch.scenes?.firstOrNull { it.scene_id == sceneId } ?: return fallback
        val idx = sc.display_index ?: 0
        val title = sc.scene_title?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            idx > 0 -> if (title != null) "Scene $idx — $title" else "Scene $idx"
            title != null -> title
            sc.type == "cover" -> "Cover"
            else -> fallback
        }
    }

    /** Classification of a failed /regenerate call. */
    sealed class StartError {
        data class LayerDisabled(val workerTypes: List<String>) : StartError()
        data class Other(val message: String) : StartError()
    }

    /**
     * Classify a failed /regenerate: HTTP 409 with body error "layer_disabled"
     * → [StartError.LayerDisabled] (types from disabled_worker_types, else the
     * requested ones); anything else keeps the generic path.
     */
    fun classifyStartError(httpCode: Int?, errorBody: String?, message: String, requested: List<String>): StartError {
        if (httpCode == 409 && !errorBody.isNullOrBlank()) {
            val obj = runCatching { JsonParser.parseString(errorBody).asJsonObject }.getOrNull()
            val err = obj?.get("error")?.takeIf { it.isJsonPrimitive }?.asString
            if (err == "layer_disabled") {
                val listed = obj.get("disabled_worker_types")
                    ?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
                    .orEmpty()
                return StartError.LayerDisabled(if (listed.isNotEmpty()) listed else requested)
            }
        }
        return StartError.Other(message)
    }
}
