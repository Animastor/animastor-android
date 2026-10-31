package com.example.animastor.ui

import com.google.gson.JsonParser

// ═══════════════════════════════════════════════════════════════
//  Task status helpers — Stage 155 «генерация только по команде»
// ═══════════════════════════════════════════════════════════════
//  Backend Stage 155 (animastor-docs architecture/COMMAND_ONLY_GENERATION.md):
//  GET /book/{id}/progress-panel rows carry the registry task status
//    status          active | completed | cancelled | blocked | interrupted
//    blocked         no live worker of the type (blocked_reason no_worker:<type>)
//    interrupted     backend restart
//  blocked / interrupted rows are visible but NOT running: no spinner, no
//  timer, no auto-retry — the user sends a NEW command (same type + scope).
//  POST /regenerate answers 409 {error:"layer_disabled", disabled_worker_types}
//  when every requested layer is off.
//  Pure Kotlin (no Android framework) — mirror of web
//  @animastor/web-generator taskStatus.ts + app features/generation/commandErrors.ts
//  (ANDROID_WEB_PARITY rule 3).
// ═══════════════════════════════════════════════════════════════

/** Display state of one progress row. */
enum class TaskRowState { ACTIVE, DONE, CANCELLED, BLOCKED, INTERRUPTED }

object TaskStatusHelpers {

    private val GPU_TYPES = setOf("audio", "image", "video")
    private val NO_WORKER = Regex("^no_worker:(audio|image|video)$")

    /** Precedence: cancelled > blocked > interrupted > done > active. */
    fun taskRowState(
        status: String?,
        done: Boolean,
        cancelled: Boolean,
        blocked: Boolean,
        interrupted: Boolean
    ): TaskRowState = when {
        cancelled || status == "cancelled" -> TaskRowState.CANCELLED
        blocked || status == "blocked" -> TaskRowState.BLOCKED
        interrupted || status == "interrupted" -> TaskRowState.INTERRUPTED
        done || status == "completed" -> TaskRowState.DONE
        else -> TaskRowState.ACTIVE
    }

    fun isStopped(state: TaskRowState): Boolean =
        state == TaskRowState.BLOCKED || state == TaskRowState.INTERRUPTED

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
