package com.example.animastor.repository

data class DiffRequest(
    val new_book: BookData
)

data class DiffResponse(
    val book_id: String? = null,
    val summary: DiffSummary? = null,
    val dirty_scenes: List<DirtyScene>? = null,
    val reindex_needed: Boolean = false
)

data class DiffSummary(
    val total_scenes_old: Int = 0,
    val total_scenes_new: Int = 0,
    val changed: Int = 0,
    val added: Int = 0,
    val removed: Int = 0
)

data class DirtyScene(
    val chapter_id: String? = null,
    val scene_id: String? = null,
    val dirty_layers: List<String>? = null,
    val changes: Map<String, Any>? = null
)

/** `client_action` of a click-sent command (hero voice design / upload) — which button sent it. */
data class ClientAction(
    val kind: String = "user_click",
    val source: String,
    val action_id: String,
    val trusted: Boolean = true
)

data class GenericResponse(
    val ok: Boolean = false,
    val book_id: String? = null
)

/** POST /book/blank — scaffolds a blank book with minimal base structure. */
data class BlankBookResponse(
    val saved: Boolean = false,
    val book_id: String? = null,
    val title: String? = null
)

/**
 * Structure create responses (POST chapters/scenes/units). The backend returns
 * the persisted ids (kept as proposed or regenerated) plus, for units, the
 * index of the appended unit inside its scene.
 */
data class StructureCreateResponse(
    val saved: Boolean = false,
    val book_id: String? = null,
    val chapter_id: String? = null,
    val scene_id: String? = null,
    val unit_id: String? = null,
    val unit_index: Int = 0
)

