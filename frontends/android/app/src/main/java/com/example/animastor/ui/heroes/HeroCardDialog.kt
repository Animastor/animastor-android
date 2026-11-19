package com.example.animastor.ui.heroes

import android.app.Dialog
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.example.animastor.repository.BehaviourGlobals
import com.example.animastor.repository.Hero
import com.example.animastor.repository.HeroIssue
import com.example.animastor.repository.HeroLook
import com.example.animastor.repository.HeroModel
import com.example.animastor.repository.LookChapter
import com.example.animastor.repository.VoiceCandidate
import com.example.animastor.repository.refFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

// ======================================================
// Hero card — the RPG-style sheet of one hero (HERO_CARD.md §3–§6)
// ======================================================
// Web twin: features/heroes/HeroCard.tsx + HeroLooks.tsx. Full-screen dialog
// over the editor:
//   identity (name, role, aliases, notes) · appearance · looks · voice
//   (timbre, manner, reference + candidates) · habits (profile + rules)
//   · reference images · delete.
// Text fields edit a draft saved with ONE PATCH (only changed fields + if_rev).
// Rule switches, pins, uploads, looks are immediate actions. The only
// generation commands are «Create candidates» and «Upload your own», and
// their client_action is built inside the click listener.

class HeroCardDialog(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val controller: HeroCardController,
    private val data: () -> Data,
    private val onNavigate: (chapterId: String, sceneId: String?) -> Unit,
    /** Ask the host fragment for a file; the callback gets (uri, name, mime) or nothing. */
    private val pickFile: (mime: String, cb: (Uri) -> Unit) -> Unit,
    private val readUri: (Uri) -> Triple<String, String, ByteArray>?,
    private val onClosed: () -> Unit,
) {
    /** The current book data the card needs (re-read after every action). */
    data class Data(
        val heroes: List<Hero>,
        val issues: List<HeroIssue>,
        val chapters: List<LookChapter>,
        val globals: BehaviourGlobals?,
    )

    private val dialog = Dialog(ctx, android.R.style.Theme_DeviceDefault_DayNight)
    private val content = column(ctx)
    private val messages = column(ctx)
    private val voiceBox = column(ctx)
    private val saveButton = button(ctx, ctx.heroStr("hero_save"), outlined = false) { save() }
    private var pollJob: Job? = null
    private var player: MediaPlayer? = null
    private val refTexts = mutableMapOf<String, String>()
    private var uploadText = ""
    private var showInstruct = false

    val heroId: String get() = controller.hero.id

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = column(ctx).apply { setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8)) }
        val bar = row(ctx)
        bar.addView(textButton(ctx, "‹ " + ctx.heroStr("hero_back")) { requestClose() })
        bar.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        bar.addView(saveButton)
        root.addView(bar)
        root.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(column(ctx).apply { addView(messages); addView(content) })
        })
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) { requestClose(); true } else false
        }
        dialog.setOnDismissListener { stopPolling(); releasePlayer(); onClosed() }
        render()
        dialog.show()
        // Opening the card only READS the candidate list (HERO_CARD.md §6).
        scope.launch { controller.loadCandidates(); renderVoice(); renderMessages(); maybePoll() }
    }

    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    /** Fresh GET /book data arrived: the hero is re-read (the draft stays while edited). */
    fun refresh(fresh: Hero?) {
        if (fresh == null) { dismiss(); return }
        val revChanged = fresh.rev != controller.hero.rev
        controller.updateHero(fresh)
        render()
        if (revChanged) scope.launch { controller.loadCandidates(); renderVoice(); maybePoll() }
    }

    // ── Rendering ────────────────────────────────────────────────────────

    private fun render() {
        content.removeAllViews()
        renderMessages()
        val hero = controller.hero
        val d = controller.draft
        val narrator = HeroModel.isNarrator(hero)
        val kind = HeroModel.heroKind(hero)

        // Identity
        val title = d.name.trim().ifEmpty { if (narrator) ctx.heroStr("hero_kind_narrator") else hero.id }
        content.addView(label(ctx, title, 20f, bold = true))
        val chips = row(ctx).apply { setPadding(0, ctx.dp(2), 0, ctx.dp(8)) }
        chips.addView(chip(ctx, ctx.heroStr(kind.key), on = true))
        if (d.role.isNotBlank() && !narrator) chips.addView(chip(ctx, d.role.trim()))
        chips.addView(chip(ctx, hero.id))
        content.addView(chips)
        content.addView(field(ctx, ctx.heroStr("hero_field_name"), d.name) { v -> setDraft { it.copy(name = v) } })
        if (!narrator) {
            content.addView(field(ctx, ctx.heroStr("hero_field_role"), d.role) { v -> setDraft { it.copy(role = v) } })
            content.addView(field(ctx, ctx.heroStr("hero_field_aliases"), d.aliases, multiline = true, hintText = ctx.heroStr("hero_field_aliases_hint")) { v -> setDraft { it.copy(aliases = v) } })
        }
        content.addView(field(ctx, ctx.heroStr("hero_field_notes"), d.notes, multiline = true, hintText = ctx.heroStr("hero_field_notes_hint")) { v -> setDraft { it.copy(notes = v) } })

        // Appearance
        if (!narrator) {
            val toggle = if (d.hasPassport) textButton(ctx, ctx.heroStr("hero_block_remove"), danger = true) { setDraft(true) { it.copy(hasPassport = false) } }
            else textButton(ctx, ctx.heroStr("hero_passport_add")) { setDraft(true) { it.copy(hasPassport = true) } }
            val (sec, body) = section(ctx, ctx.heroStr("hero_section_passport"), toggle)
            if (d.hasPassport) {
                body.addView(field(ctx, ctx.heroStr("field_appearance"), d.appearance, multiline = true) { v -> setDraft { it.copy(appearance = v) } })
                body.addView(field(ctx, ctx.heroStr("field_clothes"), d.clothes, multiline = true) { v -> setDraft { it.copy(clothes = v) } })
                body.addView(field(ctx, ctx.heroStr("field_video_tokens"), d.videoTokens, hintText = ctx.heroStr("hero_field_tokens_hint")) { v -> setDraft { it.copy(videoTokens = v) } })
            } else body.addView(hint(ctx, ctx.heroStr("hero_passport_empty")))
            content.addView(sec)
        }

        // Looks
        if (!narrator && hero.passport != null) content.addView(looksSection(hero))

        // Voice
        val voiceToggle = if (narrator) null else if (d.hasVoice) textButton(ctx, ctx.heroStr("hero_block_remove"), danger = true) { setDraft(true) { it.copy(hasVoice = false) } }
        else textButton(ctx, ctx.heroStr("hero_voice_add")) { setDraft(true) { it.copy(hasVoice = true) } }
        val (vsec, vbody) = section(ctx, ctx.heroStr("hero_section_voice"), voiceToggle)
        if (d.hasVoice) {
            vbody.addView(field(ctx, ctx.heroStr("hero_field_timbre"), d.timbre, multiline = true, hintText = ctx.heroStr("hero_field_timbre_hint")) { v -> setDraft { it.copy(timbre = v) }; renderVoice() })
            val mannerHint = if (HeroModel.referenceStatus(hero.voice) == HeroModel.ReferenceStatus.PINNED) "hero_field_manner_pinned_hint" else "hero_field_manner_hint"
            vbody.addView(field(ctx, ctx.heroStr("hero_field_manner"), d.manner, multiline = true, hintText = ctx.heroStr(mannerHint)) { v -> setDraft { it.copy(manner = v) } })
            vbody.addView(textButton(ctx, ctx.heroStr("hero_voice_instruct_preview")) { showInstruct = !showInstruct; render() })
            if (showInstruct) vbody.addView(hint(ctx, HeroModel.voiceInstruct(com.example.animastor.repository.HeroVoice(timbre = controller.draft.timbre, manner = controller.draft.manner)).ifEmpty { "—" }))
            if (hero.voice != null) vbody.addView(voiceBox.also { (it.parent as? ViewGroup)?.removeView(it) })
            else vbody.addView(hint(ctx, ctx.heroStr("hero_ref_save_first")))
        } else vbody.addView(hint(ctx, ctx.heroStr("hero_voice_empty")))
        content.addView(vsec)
        renderVoice()

        // Habits
        if (!narrator) content.addView(habitsSection(hero, d))

        // Reference images
        if (!narrator) content.addView(imagesSection(hero))

        // Delete
        if (!narrator) content.addView(button(ctx, ctx.heroStr("hero_delete")) { confirmDelete(title) }.apply {
            setTextColor(com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorError, 0xFFB00020.toInt()))
        })
        updateSave()
    }

    /** Typing updates only the draft, the save button and the messages (no rebuild — the focus stays). */
    private fun setDraft(rebuild: Boolean = false, f: (com.example.animastor.repository.HeroDraft) -> com.example.animastor.repository.HeroDraft) {
        controller.draft = f(controller.draft)
        if (rebuild) render() else { updateSave(); renderMessages() }
    }

    private fun updateSave() {
        saveButton.isEnabled = !controller.busy && controller.dirty && controller.draftErrors.isEmpty()
        saveButton.text = when {
            controller.busy && controller.dirty -> ctx.heroStr("hero_saving")
            controller.dirty -> ctx.heroStr("hero_save") + " *"
            else -> ctx.heroStr("hero_save")
        }
    }

    private fun renderMessages() {
        messages.removeAllViews()
        controller.error?.let { messages.addView(banner(ctx, ctx.uiText(it), "error")) }
        controller.draftErrors.forEach { messages.addView(banner(ctx, ctx.heroStr(it, HeroModel.VIDEO_TOKENS_MAX, HeroModel.ALIASES_MAX), "error")) }
        val issues = data().issues
        HeroModel.heroWarnings(issues, controller.hero.id).forEach { messages.addView(banner(ctx, it.message ?: it.code, "warn")) }
        HeroModel.duplicateWarnings(issues, controller.hero.id).forEach { dup ->
            val other = HeroModel.findHero(data().heroes, dup.of)
            val evidence = if (dup.evidence.isEmpty()) "" else " " + dup.evidence.joinToString(" · ") { "«$it»" }
            messages.addView(banner(ctx, ctx.heroStr("hero_possible_duplicate", (other?.name ?: "").trim().ifEmpty { dup.of }) + evidence, "warn"))
        }
        controller.notice?.let { messages.addView(banner(ctx, ctx.uiText(it), "info")) }
    }

    // ── Voice reference block ────────────────────────────────────────────

    private fun renderVoice() {
        voiceBox.removeAllViews()
        val hero = controller.hero
        if (hero.voice == null) return
        val timbreDirty = controller.draft.timbre != (hero.voice.timbre ?: "")
        val vb = HeroCardLogic.voiceBlock(hero, controller.candidates, controller.pending, controller.listOutdated, controller.busy, timbreDirty)
        val head = row(ctx).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(4)) }
        head.addView(label(ctx, ctx.heroStr("hero_section_reference"), 14f, bold = true).apply { setPadding(0, 0, ctx.dp(8), 0) })
        head.addView(chip(ctx, ctx.heroStr(vb.status.key), on = vb.pinned))
        hero.voice.reference?.verification?.let { head.addView(chip(ctx, ctx.heroStr("hero_ref_verification", it))) }
        if (vb.outdated) head.addView(chip(ctx, ctx.heroStr("hero_ref_outdated"), on = true).apply { tag = "hero-ref-outdated" })
        voiceBox.addView(head)
        if (vb.outdated) voiceBox.addView(hint(ctx, ctx.heroStr("hero_ref_outdated_hint")))

        hero.voice.reference?.let { ref ->
            if (vb.status == HeroModel.ReferenceStatus.MISSING || vb.status == HeroModel.ReferenceStatus.MISMATCH) {
                voiceBox.addView(banner(ctx, ctx.heroStr(if (vb.status == HeroModel.ReferenceStatus.MISSING) "hero_ref_missing_hint" else "hero_ref_mismatch_hint"), "warn"))
            } else ref.file?.let { f -> voiceBox.addView(textButton(ctx, "▶ " + ctx.heroStr("hero_voice_play")) { play(refFileName(f)) }) }
            ref.ref_text?.let { voiceBox.addView(label(ctx, "«$it»", 13f)) }
            voiceBox.addView(hint(ctx, listOfNotNull(ref.source, ref.model, ref.seed?.let { "seed $it" }, ref.file?.let(::refFileName)).joinToString(" · ")))
            voiceBox.addView(textButton(ctx, ctx.heroStr("hero_ref_unpin"), danger = true) { launchAction { controller.unpin() } }.apply { isEnabled = !controller.busy })
        }

        if (controller.unavailable) { voiceBox.addView(hint(ctx, ctx.heroStr("hero_ref_unavailable"))); return }

        vb.others.forEach { c -> voiceBox.addView(candidateRow(c, vb.pinned)) }
        if (vb.showNoCandidates) voiceBox.addView(hint(ctx, ctx.heroStr("hero_ref_no_candidates")))
        if (controller.pending) voiceBox.addView(hint(ctx, ctx.heroStr("hero_ref_pending")).apply { tag = "hero-ref-pending" })

        if (vb.showCommands) {
            val actions = row(ctx).apply { setPadding(0, ctx.dp(4), 0, 0) }
            // The ONLY generation command of the card — fired by this click, nowhere else.
            actions.addView(button(ctx, "✨ " + ctx.heroStr("hero_ref_design")) {
                val action = HeroCardLogic.clickAction(HeroCardLogic.SOURCE_DESIGN)
                launchAction { controller.design(action) }
            }.apply { isEnabled = vb.designEnabled; tag = "hero-ref-design" })
            actions.addView(button(ctx, "+ " + ctx.heroStr("hero_ref_upload")) {
                // Uploading is a command too (GPU transcription): the proof comes from THIS click.
                val action = HeroCardLogic.clickAction(HeroCardLogic.SOURCE_UPLOAD)
                pickFile("audio/*") { uri ->
                    val file = readUri(uri) ?: return@pickFile
                    val text = uploadText
                    launchAction { controller.upload(file.first, file.second, file.third, text, action).also { if (it) uploadText = "" } }
                }
            }.apply { isEnabled = vb.uploadEnabled })
            voiceBox.addView(actions)
            vb.hint?.let { voiceBox.addView(hint(ctx, ctx.heroStr(it))) }
            voiceBox.addView(field(ctx, ctx.heroStr("hero_ref_upload_text"), uploadText) { uploadText = it })
        } else vb.hint?.let { voiceBox.addView(hint(ctx, ctx.heroStr(it))) }
    }

    private fun candidateRow(c: VoiceCandidate, pinnedRef: Boolean): View {
        val box = column(ctx).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)); tag = "candidate:${c.name}" }
        val line = HeroModel.candidateLine(c.status, c.verification, c.verification_problems, c.error)
        val state = ctx.heroStr(line.key) + if (line.detail.isNotEmpty()) ": ${line.detail}" else ""
        val duration = c.duration_ms?.let { String.format(java.util.Locale.ROOT, "%.1f s", it / 1000.0) }
        box.addView(hint(ctx, listOfNotNull(c.name, c.source, state, duration).joinToString(" · ")))
        val uploaded = c.source == "uploaded"
        if (!c.ref_text.isNullOrEmpty() && !uploaded) box.addView(label(ctx, "«${c.ref_text}»", 13f))
        if (uploaded && c.status == "ready") {
            box.addView(field(ctx, ctx.heroStr("hero_ref_text"), refTexts[c.name] ?: c.ref_text ?: "") { refTexts[c.name] = it })
        }
        val actions = row(ctx)
        if (c.status == "ready") actions.addView(textButton(ctx, "▶ " + ctx.heroStr("hero_voice_play")) { play(c.name) })
        if (HeroCardLogic.canPin(c, pinnedRef)) actions.addView(button(ctx, "✓ " + ctx.heroStr("hero_ref_pin")) {
            launchAction { controller.pin(c, refTexts[c.name]) }
        }.apply { isEnabled = !controller.busy })
        if (HeroCardLogic.canDelete(c)) actions.addView(textButton(ctx, ctx.heroStr("hero_ref_delete_candidate"), danger = true) {
            launchAction { controller.deleteCandidate(c) }
        }.apply { isEnabled = !controller.busy })
        box.addView(actions)
        return box
    }

    /** While a design / upload command runs, re-read the list every 3 s (only the list — nothing is started). */
    private fun maybePoll() {
        if (!controller.pending || pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && controller.pending && dialog.isShowing) {
                delay(HeroCardLogic.CANDIDATES_POLL_MS)
                controller.loadCandidates()
                renderVoice()
            }
        }
    }

    private fun stopPolling() { pollJob?.cancel(); pollJob = null }

    private fun play(name: String) {
        scope.launch {
            try {
                val bytes = controller.voiceBytes(name)
                val f = File(ctx.cacheDir, "hero_voice_play.wav").apply { writeBytes(bytes) }
                releasePlayer()
                player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
            } catch (e: Exception) {
                controller.error = HeroCardLogic.errorText(e)
                renderMessages()
            }
        }
    }

    private fun releasePlayer() { runCatching { player?.release() }; player = null }

    // ── Looks ────────────────────────────────────────────────────────────

    private fun looksSection(hero: Hero): View {
        val (sec, body) = section(ctx, ctx.heroStr("hero_section_looks"), textButton(ctx, "+ " + ctx.heroStr("look_add")) { lookDialog(hero, null) })
        val usage = HeroModel.lookUsage(data().chapters, hero.id)
        body.addView(label(ctx, ctx.heroStr("look_base"), 14f, bold = true))
        body.addView(hint(ctx, ctx.heroStr("look_base_meta") + " · " + ctx.heroStr("look_places_base")))
        (hero.looks ?: emptyList()).forEach { look ->
            val places = usage[look.id] ?: emptyList()
            val preview = HeroModel.previewLook(hero, look.id)
            val box = column(ctx).apply { setPadding(0, ctx.dp(8), 0, 0) }
            box.addView(label(ctx, look.label?.ifBlank { null } ?: look.id, 14f, bold = true))
            val changed = preview?.changed?.joinToString(", ") { ctx.heroStr("field_$it") } ?: ""
            box.addView(hint(ctx, listOf(
                if (changed.isNotEmpty()) "${ctx.heroStr("look_changed")}: $changed" else null,
                if (places.isEmpty()) ctx.heroStr("look_slot_unused") else ctx.heroStr("look_slot_places", places.size),
            ).filterNotNull().joinToString(" · ")))
            places.forEach { p ->
                val text = ctx.heroStr("look_place_chapter", p.chapterNumber ?: 0) +
                    (if (p.chapterTitle.isNotEmpty()) " · ${p.chapterTitle}" else "") +
                    (p.sceneId?.let { " · " + ctx.heroStr("look_place_scene", p.sceneNumber ?: 0) + if (p.sceneTitle.isNotEmpty()) " · ${p.sceneTitle}" else "" } ?: "")
                box.addView(textButton(ctx, "→ $text") { dismiss(); onNavigate(p.chapterId, p.sceneId) }.apply { contentDescription = ctx.heroStr("look_place_go") })
            }
            val actions = row(ctx)
            actions.addView(textButton(ctx, ctx.heroStr("hero_look_edit")) { lookDialog(hero, look) })
            actions.addView(textButton(ctx, ctx.heroStr("look_assign")) { assignDialog(hero, look, places) })
            actions.addView(textButton(ctx, ctx.heroStr("look_delete"), danger = true) {
                confirm(ctx.heroStr("look_delete_title"), ctx.heroStr("look_delete_confirm", look.label ?: look.id, places.size)) { launchAction { controller.deleteLook(look.id) } }
            })
            box.addView(actions)
            body.addView(box)
        }
        return sec
    }

    /** New look (look == null) or edit: label, notes, the three passport fields. */
    private fun lookDialog(hero: Hero, look: HeroLook?) {
        var draft = HeroModel.draftFromLook(look)
        var newId = ""
        val form = column(ctx).apply { setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0) }
        val errors = column(ctx)
        fun refreshErrors() {
            errors.removeAllViews()
            val list = HeroModel.validateLookDraft(draft) + listOfNotNull(if (look == null) HeroModel.validateNewLookId(newId, hero) else null)
            list.forEach { errors.addView(hint(ctx, ctx.heroStr(it, HeroModel.VIDEO_TOKENS_MAX))) }
        }
        form.addView(errors)
        if (look == null) form.addView(field(ctx, ctx.heroStr("hero_field_id"), "", hintText = ctx.heroStr("look_add_id_hint")) { newId = it; refreshErrors() })
        form.addView(field(ctx, ctx.heroStr("look_field_label"), draft.label) { draft = draft.copy(label = it); refreshErrors() })
        form.addView(hint(ctx, ctx.heroStr("look_fields_hint")))
        val base = HeroModel.previewLook(hero, HeroModel.BASE_LOOK)
        form.addView(field(ctx, ctx.heroStr("field_appearance"), draft.appearance, multiline = true, hintText = base?.appearance?.takeIf { it.isNotEmpty() }?.let { ctx.heroStr("look_base_value", it) }) { draft = draft.copy(appearance = it); refreshErrors() })
        form.addView(field(ctx, ctx.heroStr("field_clothes"), draft.clothes, multiline = true, hintText = base?.clothes?.takeIf { it.isNotEmpty() }?.let { ctx.heroStr("look_base_value", it) }) { draft = draft.copy(clothes = it); refreshErrors() })
        form.addView(field(ctx, ctx.heroStr("field_video_tokens"), draft.videoTokens, hintText = ctx.heroStr("hero_field_tokens_hint")) { draft = draft.copy(videoTokens = it); refreshErrors() })
        form.addView(field(ctx, ctx.heroStr("hero_field_notes"), draft.notes, multiline = true) { draft = draft.copy(notes = it) })
        refreshErrors()
        AlertDialog.Builder(ctx)
            .setTitle(if (look == null) ctx.heroStr("look_add_title") else (look.label ?: look.id))
            .setView(ScrollView(ctx).apply { addView(form) })
            .setNegativeButton(ctx.heroStr("hero_cancel"), null)
            .setPositiveButton(ctx.heroStr("look_save")) { _, _ ->
                val problems = HeroModel.validateLookDraft(draft) + listOfNotNull(if (look == null) HeroModel.validateNewLookId(newId, hero) else null)
                if (problems.isNotEmpty()) { controller.error = UiText.Key(problems.first(), listOf(HeroModel.VIDEO_TOKENS_MAX)); renderMessages(); return@setPositiveButton }
                if (look == null) launchAction { controller.createLook(newId.trim().ifEmpty { null }, draft.label.trim(), HeroModel.lookFieldsFromDraft(draft), draft.notes.ifBlank { null }) }
                else {
                    val fields = HeroModel.diffLook(look, draft)
                    if (fields.isNotEmpty()) launchAction { controller.patchLook(look.id, fields) }
                }
            }
            .show()
    }

    /** Where the look is in effect: chapters and scenes as checkboxes → release + assign. */
    private fun assignDialog(hero: Hero, look: HeroLook, current: List<com.example.animastor.repository.LookPlace>) {
        val checked = current.map { HeroModel.lookPlaceKey(it.chapterId, it.sceneId) }.toMutableSet()
        val list = column(ctx).apply { setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0) }
        list.addView(hint(ctx, ctx.heroStr("look_assign_hint")))
        data().chapters.forEachIndexed { ci, ch ->
            val chId = ch.chapter_id ?: return@forEachIndexed
            fun box(key: String, text: String, indent: Int) = CheckBox(ctx).apply {
                this.text = text
                isChecked = key in checked
                setPadding(ctx.dp(indent), 0, 0, 0)
                setOnCheckedChangeListener { _, on -> if (on) checked += key else checked -= key }
            }
            val chText = ctx.heroStr("look_place_chapter", ch.display_number ?: (ci + 1)) + (ch.chapter_title?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
            list.addView(box(HeroModel.lookPlaceKey(chId, null), chText, 0))
            (ch.scenes ?: emptyList()).forEachIndexed { si, sc ->
                val scId = sc.scene_id ?: return@forEachIndexed
                if (hero.id !in (sc.participants ?: emptyList()) && HeroModel.lookPlaceKey(chId, scId) !in checked) return@forEachIndexed
                val scText = ctx.heroStr("look_place_scene", sc.display_index ?: (si + 1)) + (sc.scene_title?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                list.addView(box(HeroModel.lookPlaceKey(chId, scId), scText, 24))
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(ctx.heroStr("look_assign_title", look.label ?: look.id))
            .setView(ScrollView(ctx).apply { addView(list) })
            .setNegativeButton(ctx.heroStr("hero_cancel"), null)
            .setPositiveButton(ctx.heroStr("look_assign_apply")) { _, _ ->
                val diff = HeroModel.diffAssignment(current, checked)
                if (!HeroModel.isEmptyAssignment(diff.assign) || !HeroModel.isEmptyAssignment(diff.release)) {
                    launchAction { controller.applyAssignment(look.id, diff.assign, diff.release) }
                }
            }
            .show()
    }

    // ── Habits ───────────────────────────────────────────────────────────

    private fun habitsSection(hero: Hero, d: com.example.animastor.repository.HeroDraft): View {
        val (sec, body) = section(ctx, ctx.heroStr("hero_section_behaviour"))
        body.addView(field(ctx, ctx.heroStr("field_baseline"), d.baseline, multiline = true) { v -> setDraft { it.copy(baseline = v) } })
        body.addView(field(ctx, ctx.heroStr("field_quirks"), d.quirks, multiline = true) { v -> setDraft { it.copy(quirks = v) } })
        body.addView(field(ctx, ctx.heroStr("field_reactions"), d.reactions, multiline = true) { v -> setDraft { it.copy(reactions = v) } })
        val head = row(ctx)
        head.addView(label(ctx, ctx.heroStr("hero_rules_title"), 14f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        head.addView(textButton(ctx, ctx.heroStr("hero_rule_add")) { ruleDialog(hero) })
        body.addView(head)
        val rules = hero.behaviour?.rules ?: emptyList()
        if (rules.isEmpty()) body.addView(hint(ctx, ctx.heroStr("hero_rules_empty")))
        rules.forEach { r ->
            val line = row(ctx)
            line.addView(SwitchCompat(ctx).apply {
                isChecked = r.enabled != false
                contentDescription = ctx.heroStr("hero_rule_on")
                isEnabled = !controller.busy
                setOnCheckedChangeListener { _, on -> launchAction { controller.setRuleEnabled(r, on) } }
            })
            val main = column(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            main.addView(label(ctx, r.thenDo?.action?.ifBlank { null } ?: r.id, 14f))
            val meta = (r.whenCond?.emotion ?: emptyList()).joinToString(", ") + (r.probability?.let { " · ${Math.round(it * 100)}%" } ?: "")
            main.addView(hint(ctx, meta))
            line.addView(main)
            line.addView(textButton(ctx, "✕", danger = true) { launchAction { controller.deleteRule(r) } }.apply { contentDescription = ctx.heroStr("hero_rule_delete"); isEnabled = !controller.busy })
            body.addView(line)
        }
        return sec
    }

    private fun ruleDialog(hero: Hero) {
        var action = ""
        var probability = "0.35"
        val emotions = HeroModel.BEHAVIOUR_EMOTIONS
        val on = BooleanArray(emotions.size)
        val form = column(ctx).apply { setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0) }
        form.addView(field(ctx, ctx.heroStr("hero_rule_action"), "", hintText = ctx.heroStr("hero_rule_action_hint")) { action = it })
        form.addView(label(ctx, ctx.heroStr("hero_rule_emotions"), 13f))
        emotions.forEachIndexed { i, e -> form.addView(CheckBox(ctx).apply { text = e; setOnCheckedChangeListener { _, v -> on[i] = v } }) }
        form.addView(field(ctx, ctx.heroStr("hero_rule_probability"), probability) { probability = it })
        AlertDialog.Builder(ctx)
            .setTitle(ctx.heroStr("hero_rule_add"))
            .setView(ScrollView(ctx).apply { addView(form) })
            .setNegativeButton(ctx.heroStr("hero_cancel"), null)
            .setPositiveButton(ctx.heroStr("hero_rule_add")) { _, _ ->
                val picked = emotions.filterIndexed { i, _ -> on[i] }
                if (action.isBlank() || picked.isEmpty()) return@setPositiveButton
                val d = data()
                launchAction { controller.addRule(HeroCardLogic.newRule(hero, d.heroes, d.globals, action, picked, probability)) }
            }
            .show()
    }

    // ── Reference images (backend step 7 — until then the block degrades) ─

    private fun imagesSection(hero: Hero): View {
        val upload = if (controller.imagesUnavailable) null else textButton(ctx, ctx.heroStr("hero_img_upload")) {
            pickFile("image/*") { uri -> readUri(uri)?.let { f -> launchAction { controller.uploadImage(f.first, f.second, f.third) } } }
        }
        val (sec, body) = section(ctx, ctx.heroStr("hero_section_images"), upload)
        val refs = hero.image_refs ?: emptyList()
        if (controller.imagesUnavailable) body.addView(hint(ctx, ctx.heroStr("hero_img_unavailable")))
        else if (refs.isEmpty()) body.addView(hint(ctx, ctx.heroStr("hero_img_empty")))
        val portrait = HeroModel.portraitIndex(hero)
        refs.forEachIndexed { i, r ->
            val state = when (r.state) { "missing" -> ctx.heroStr("hero_ref_missing"); "mismatch" -> ctx.heroStr("hero_ref_mismatch"); else -> null }
            body.addView(hint(ctx, listOfNotNull(r.file?.let(::refFileName), if (i == portrait) ctx.heroStr("hero_img_portrait") else r.kind, state).joinToString(" · ")))
        }
        return sec
    }

    // ── Actions ──────────────────────────────────────────────────────────

    /** Run a card action: busy state, then the fresh data re-renders the card. */
    private fun launchAction(block: suspend () -> Boolean) {
        scope.launch {
            updateSave(); renderVoice()
            block()
            render()
            maybePoll()
        }
    }

    private fun save() = launchAction { controller.save() }

    private fun requestClose() {
        if (!controller.dirty) { dismiss(); return }
        AlertDialog.Builder(ctx)
            .setTitle(ctx.heroStr("hero_unsaved_title"))
            .setMessage(ctx.heroStr("hero_unsaved_desc"))
            .setNegativeButton(ctx.heroStr("hero_unsaved_discard")) { _, _ -> dismiss() }
            .setPositiveButton(ctx.heroStr("hero_unsaved_stay"), null)
            .show()
    }

    private fun confirmDelete(title: String) {
        confirm(ctx.heroStr("hero_delete_title"), ctx.heroStr("hero_delete_confirm", title)) {
            scope.launch { if (controller.deleteHero()) dismiss() else renderMessages() }
        }
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(ctx.heroStr("hero_cancel"), null)
            .setPositiveButton(ctx.heroStr("entity_delete")) { _, _ -> onYes() }
            .show()
    }
}
