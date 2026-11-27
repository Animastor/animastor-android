package com.example.animastor.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Locale

// ═══════════════════════════════════════════════════════════════
//  Generate API v2 (ADR ORCHESTRATION_V2 §11.1–11.2) — wire types + pure helpers
// ═══════════════════════════════════════════════════════════════
//  POST /book/{id}/generate          {scope, layers, mode, client_action_id} → {command_id, existing, summary}
//  POST /book/{id}/generate/stop     {command_id} | {layer} | {}
//  GET  /book/{id}/generate/progress ADR §9 aggregate (commands × layers × scenes)
//  GET  /book/{id}/generate/stream   SSE: {type:"gen_progress", reason:"connect"|"notify", progress}
//  build_id is not read any more (there is none in the answer).
//  The Generator rows keep their row model (ProgressPanelResponse →
//  computeProgressRows); [GenerateApiV2.aggregateToPanel] turns the NEW wire
//  format into it. Mirrors web features/generation/generateApi.ts.
// ═══════════════════════════════════════════════════════════════

/** `scope` of POST /generate. Null fields are not serialised (Gson default). */
data class GenScope(
    val level: String,
    val chapter_id: String? = null,
    val scene_id: String? = null,
    val scene_ids: List<String>? = null,
    val unit_id: String? = null,
)

data class GenerateRequestBody(
    val scope: GenScope,
    val layers: List<String>,
    /** "missing" (Сгенерировать недостающее) | "all" (Перегенерировать). */
    val mode: String,
    /** One per click (ADR §6.2: the server dedups a repeated click by it). */
    val client_action_id: String,
)

data class GenerateSummary(
    val jobs_created: Int = 0,
    val joined: Int = 0,
    val skipped_ready: Int = 0,
    val skipped_unsupported: List<String>? = null,
)

data class GenerateResponse(
    val book_id: String? = null,
    val command_id: String = "",
    val existing: Boolean = false,
    val status: String? = null,
    val mode: String? = null,
    val layers: List<String>? = null,
    val summary: GenerateSummary? = null,
    val skipped_layers: List<String>? = null,
)

/** Body of POST /generate/stop: {command_id} | {layer} | {} (both null → the whole book). */
data class GenerateStopRequest(
    val command_id: String? = null,
    val layer: String? = null,
)

data class GenerateStopResponse(
    val ok: Boolean = false,
    val book_id: String? = null,
    /** "command" | "layer" | "book" */
    val target: String? = null,
    val command_id: String? = null,
    val layer: String? = null,
    val commands_cancelled: Int = 0,
    val jobs_cancelled: Int = 0,
    val kept_shared: Int = 0,
    val vbook_cancelled: Boolean = false,
)

data class GenLayerProgress(
    val queued: Int = 0, val running: Int = 0, val done: Int = 0, val failed: Int = 0, val cancelled: Int = 0,
    val retrying: Int = 0, val joined_queued: Int = 0, val joined_running: Int = 0,
    val ready: Int = 0, val total: Int = 0, val percent: Int = 0, val scenes: Int = 0,
)

data class GenSceneProgress(
    val chapter_id: String? = null, val scene_id: String? = null, val layer: String? = null,
    val queued: Int = 0, val running: Int = 0, val done: Int = 0, val failed: Int = 0, val cancelled: Int = 0,
)

data class GenLabels(
    val chapter_label: String? = null,
    val scene_label: String? = null,
    val scene_count: Int? = null,
)

data class GenCommand(
    val command_id: String = "",
    val kind: String? = null,
    /** active | done | partial | failed | cancelled */
    val status: String = "active",
    val mode: String? = null,
    val scope: GenScope? = null,
    val layers: List<String> = emptyList(),
    val created_at: String? = null,
    val finished_at: String? = null,
    val error: String? = null,
    val summary: GenerateSummary? = null,
    val labels: GenLabels? = null,
    val by_layer: Map<String, GenLayerProgress> = emptyMap(),
    val scenes: List<GenSceneProgress> = emptyList(),
)

data class GenProgress(
    val book_id: String? = null,
    val active: Boolean = false,
    val commands: List<GenCommand> = emptyList(),
    val disabled: Boolean = false,
)

data class GenStreamEvent(
    val type: String? = null,
    val reason: String? = null,
    val command_ids: List<String>? = null,
    val progress: GenProgress? = null,
)

object GenerateApiV2 {
    const val MODE_MISSING = "missing"
    const val MODE_ALL = "all"

    /** The scope dialog id + the position → the /generate scope; null: the position is missing. */
    fun toGenerateScope(scope: String, chapterId: String?, sceneId: String?): GenScope? = when (scope) {
        "whole_book" -> GenScope(level = "book")
        "current_chapter" -> chapterId?.let { GenScope(level = "chapter", chapter_id = it) }
        "current_scene" -> if (chapterId != null && sceneId != null) GenScope(level = "scene", chapter_id = chapterId, scene_id = sceneId) else null
        "from_current_scene" -> if (chapterId != null && sceneId != null) GenScope(level = "from_current_scene", chapter_id = chapterId, scene_id = sceneId) else null
        else -> null
    }

    /** «Перегенерировать» (mode all) asks first when the scope is wider than one scene (ADR §11.2). */
    fun regenerateNeedsConfirm(scope: String): Boolean = scope != "current_scene"

    /** Row ■ → {command_id}; section ■ → {layer}; «Стоп всё» → {}. null: not an
     *  orchestration layer (vbook / cover) — the caller keeps cancel-worker (задача H). */
    fun stopBody(type: String?, commandId: String?): GenerateStopRequest? = when {
        !commandId.isNullOrBlank() -> GenerateStopRequest(command_id = commandId)
        type == "audio" || type == "image" || type == "video" -> GenerateStopRequest(layer = type)
        type == null || type == "book" -> GenerateStopRequest()
        else -> null
    }

    /** A row ■ succeeded only when the backend stopped exactly that command. */
    fun stopSucceeded(commandId: String?, res: GenerateStopResponse?): Boolean =
        if (commandId == null) res?.ok == true else res?.ok == true && res.command_id == commandId

    private val LEGACY_SCOPE = mapOf(
        "book" to "whole_book", "chapter" to "current_chapter", "scene" to "current_scene",
        "unit" to "current_scene", "scenes" to "from_current_scene",
    )

    /** ISO-8601 of the server (`2026-11-27T10:00:00.000Z`) → epoch ms (minSdk 24: no java.time). */
    private fun epochMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")) {
            val t = runCatching { SimpleDateFormat(pattern, Locale.US).parse(iso)?.time }.getOrNull()
            if (t != null) return t
        }
        return null
    }

    private fun taskOf(c: GenCommand, layer: String): ProgressTask {
        val t = c.by_layer[layer] ?: GenLayerProgress()
        val scenes = c.scenes.filter { it.layer == layer }
        val scope = c.scope ?: GenScope(level = "book")
        val firstScene = if (scope.level == "scenes") scope.scene_ids?.firstOrNull() else scope.scene_id
        val chapterId = scope.chapter_id ?: firstScene?.let { s -> c.scenes.firstOrNull { it.scene_id == s }?.chapter_id }
        val done = c.status == "done"
        val cancelled = c.status == "cancelled"
        val failed = c.status == "failed" || c.status == "partial"
        val total = t.total
        return ProgressTask(
            task_id = c.command_id,
            type = layer,
            scope = LEGACY_SCOPE[scope.level] ?: "whole_book",
            chapter_id = chapterId,
            scene_id = firstScene,
            scene_label = c.labels?.scene_label,
            chapter_label = c.labels?.chapter_label,
            target_count = scenes.size,
            started_at = epochMs(c.created_at),
            ready = if (done) total else t.ready,
            total = total,
            percent = if (done) 100 else t.percent,
            done = done,
            visible = true,
            indeterminate = false,
            cancelled = cancelled,
            status = when { done -> "completed"; cancelled -> "cancelled"; failed -> "failed"; else -> "active" },
            failed = failed,
            error_class = if (failed) "gen_v2" else null,
            error_message = if (failed) c.error else null,
            retrying = !failed && !done && !cancelled && t.retrying > 0,
        )
    }

    /** GET /generate/progress (or a stream event's `progress`) → the Generator row model. */
    fun aggregateToPanel(agg: GenProgress?): ProgressPanelResponse {
        val tasks = agg?.commands.orEmpty().flatMap { c -> c.layers.map { taskOf(c, it) } }
        val anyIncomplete = tasks.any { !it.done && !it.cancelled && !it.failed }
        val overall = if (tasks.isEmpty()) 0 else Math.round(tasks.sumOf { it.percent }.toDouble() / tasks.size).toInt()
        return ProgressPanelResponse(book_id = agg?.book_id ?: "", tasks = tasks, overall_percent = overall, any_incomplete = anyIncomplete)
    }

    /** A /generate/stream `data:` payload → the event, or null (malformed / other type). */
    fun parseGenStreamEvent(data: String, gson: Gson = Gson()): GenStreamEvent? {
        val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return null
        if (obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString != "gen_progress") return null
        val progress = obj.get("progress")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (progress.get("commands")?.isJsonArray != true) return null
        return runCatching { gson.fromJson(obj, GenStreamEvent::class.java) }.getOrNull()
            ?.let { it.copy(reason = if (it.reason == "connect") "connect" else "notify", command_ids = it.command_ids.orEmpty()) }
    }
}
