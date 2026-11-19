package com.example.animastor.ui.heroes

import com.example.animastor.repository.ClientAction
import com.example.animastor.repository.Hero
import com.example.animastor.repository.HeroApiError
import com.example.animastor.repository.HeroDraft
import com.example.animastor.repository.HeroModel
import com.example.animastor.repository.HeroMutationResult
import com.example.animastor.repository.HeroRepository
import com.example.animastor.repository.HeroRule
import com.example.animastor.repository.LookAssignment
import com.example.animastor.repository.LookFields
import com.example.animastor.repository.RuleThen
import com.example.animastor.repository.RuleWhen
import com.example.animastor.repository.VoiceCandidate
import com.example.animastor.ui.GeneratorCommands

// ======================================================
// Hero card — logic without views (HERO_CARD.md §3–§7, §10)
// ======================================================
// Web twin: features/heroes/HeroCard.tsx (isUnavailable, errorText,
// dirtyNotice, CANDIDATES_POLL_MS, VoiceReferenceBlock rules). The dialog
// (HeroCardDialog) only binds views to [HeroCardController]; every request of
// the card goes through it, so the rule «generation and writes only by an
// explicit action» is a JVM test (HeroCardControllerTest).

/** A text for the user: a string resource name + args, or the server's own message. */
sealed class UiText {
    data class Key(val name: String, val args: List<Any> = emptyList()) : UiText()
    data class Raw(val text: String) : UiText()
}

object HeroCardLogic {
    /** The candidate list is re-read this often while a command runs (HERO_CARD.md §6). */
    const val CANDIDATES_POLL_MS = 3000L

    /** Sources of client_action of the card's two commands (web: hero_card.voice_*). */
    const val SOURCE_DESIGN = "hero_card.voice_design"
    const val SOURCE_UPLOAD = "hero_card.voice_upload"

    /**
     * Not deployed (404/405/501) or no references possible for this book (409
     * book_without_build / book_not_v2, 503 orch_v2_disabled) — the block says
     * «unavailable», never a red error on a plain card open.
     */
    fun isUnavailable(e: Throwable): Boolean {
        if (e !is HeroApiError) return false
        if (e.status == 404 || e.status == 405 || e.status == 501) return true
        val code = e.error ?: e.code
        return code == "book_without_build" || code == "book_not_v2" || code == "orch_v2_disabled"
    }

    /** VBook 4 errors are {error: <code>, message}: the message is shown, the code picks a clearer text. */
    fun errorText(e: Throwable): UiText {
        if (e is HeroApiError) {
            if (e.status == 409 && e.error == "rev_conflict") return UiText.Key("hero_rev_conflict")
            if (e.status == 400 && e.error == "invalid_character" && (e.message ?: "").contains("look", ignoreCase = true)) {
                return UiText.Key("hero_err_passport_has_looks")
            }
            return UiText.Raw(e.message?.takeIf { it.isNotEmpty() } ?: e.status.toString())
        }
        return UiText.Raw(e.message?.takeIf { it.isNotEmpty() } ?: "unknown")
    }

    /** «N scenes will be redrawn (image, video)» after a mutation — or null. */
    fun dirtyNotice(result: HeroMutationResult?): UiText? {
        val sum = HeroModel.dirtySummary(result?.dirty_scenes)
        if (sum.scenes == 0) return null
        return UiText.Key("hero_dirty_scenes", listOf(sum.scenes, sum.layers.joinToString(", ").ifEmpty { "—" }))
    }

    /** What the voice reference block shows (web VoiceReferenceBlock). */
    data class VoiceBlock(
        val status: HeroModel.ReferenceStatus,
        val pinned: Boolean,
        val outdated: Boolean,
        /** Candidates other than the pinned one. */
        val others: List<VoiceCandidate>,
        /** «Create candidates» / «Upload your own» exist only without a pinned reference (409 reference_locked). */
        val showCommands: Boolean,
        val designEnabled: Boolean,
        val uploadEnabled: Boolean,
        /** hero_ref_need_timbre / hero_ref_save_first / hero_ref_pinned_hint — or null. */
        val hint: String?,
        val showNoCandidates: Boolean,
    )

    fun voiceBlock(
        hero: Hero,
        candidates: List<VoiceCandidate>?,
        pending: Boolean,
        listOutdated: Boolean,
        busy: Boolean,
        timbreDirty: Boolean,
    ): VoiceBlock {
        val status = HeroModel.referenceStatus(hero.voice)
        val pinned = status == HeroModel.ReferenceStatus.PINNED
        val hasTimbre = (hero.voice?.timbre ?: "").isNotBlank()
        val others = (candidates ?: emptyList()).filter { it.pinned != true }
        return VoiceBlock(
            status = status,
            pinned = pinned,
            outdated = hero.voice?.reference_outdated == true || (pinned && listOutdated),
            others = others,
            showCommands = !pinned,
            designEnabled = !pinned && !busy && !pending && hasTimbre && !timbreDirty,
            uploadEnabled = !pinned && !busy && !pending,
            hint = when {
                pinned -> "hero_ref_pinned_hint"
                !hasTimbre -> "hero_ref_need_timbre"
                timbreDirty -> "hero_ref_save_first"
                else -> null
            },
            showNoCandidates = candidates != null && others.isEmpty() && !pending,
        )
    }

    fun canPin(c: VoiceCandidate, pinnedRef: Boolean): Boolean = !pinnedRef && HeroModel.canPinCandidate(c.status, c.verification, c.pinned)
    fun canDelete(c: VoiceCandidate): Boolean = HeroModel.canDeleteCandidate(c.status, c.pinned)

    /** The rule of a new habit (web RulesBlock.add): probability clamped to 0..1, default 0.35. */
    fun newRule(hero: Hero, heroes: List<Hero>, globals: com.example.animastor.repository.BehaviourGlobals?, action: String, emotions: List<String>, probability: String): HeroRule {
        val p = probability.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.35
        return HeroRule(
            id = HeroModel.newRuleId(hero.id, action, HeroModel.allRuleIds(heroes, globals)),
            whenCond = RuleWhen(emotion = emotions),
            thenDo = RuleThen(action = action.trim()),
            probability = p,
            enabled = true,
        )
    }

    /** client_action of a card command — built ONLY inside a click listener. */
    fun clickAction(source: String): ClientAction {
        val a = GeneratorCommands.userAction(source)
        return ClientAction(kind = "user_click", source = a.source, action_id = a.actionId, trusted = a.trusted)
    }
}

/**
 * Every request of one opened hero card. Opening = [loadCandidates] (a READ);
 * nothing else runs until the user acts. After a successful action the
 * caller re-reads GET /book ([onChanged]) — the backend is the source of truth.
 */
class HeroCardController(
    private val repo: HeroRepository,
    private val bookId: String,
    hero: Hero,
    private val onChanged: suspend () -> Unit,
) {
    var hero: Hero = hero
        private set
    var draft: HeroDraft = HeroModel.draftFromHero(hero)
    var busy = false
        private set
    var error: UiText? = null
    var notice: UiText? = null
    var candidates: List<VoiceCandidate>? = null
        private set
    var pending = false
        private set
    var listOutdated = false
        private set
    var unavailable = false
        private set

    val fields: Map<String, Any?> get() = HeroModel.diffDraft(hero, draft)
    val dirty: Boolean get() = fields.isNotEmpty()
    val draftErrors: List<String> get() = HeroModel.validateDraft(hero, draft)

    /** Fresh server data: the draft follows it unless the user has pending edits of the same hero. */
    fun updateHero(fresh: Hero) {
        val sameHero = fresh.id == hero.id
        val keepDraft = sameHero && dirty
        hero = fresh
        if (!keepDraft) draft = HeroModel.draftFromHero(fresh)
    }

    /** Opening the card READS the list — never a command (HERO_CARD.md §6). */
    suspend fun loadCandidates() {
        if (hero.voice == null) return
        try {
            val res = repo.listVoiceCandidates(bookId, hero.id)
            candidates = res.candidates ?: emptyList()
            pending = res.pending == true
            listOutdated = res.reference_outdated == true
            unavailable = false
        } catch (e: Exception) {
            if (HeroCardLogic.isUnavailable(e)) { unavailable = true; candidates = emptyList() } else error = HeroCardLogic.errorText(e)
        }
    }

    /** Run an immediate action, then re-read the book. */
    suspend fun act(fn: suspend () -> Any?): Boolean {
        busy = true; error = null; notice = null
        return try {
            val r = fn()
            notice = HeroCardLogic.dirtyNotice(r as? HeroMutationResult)
            onChanged()
            true
        } catch (e: Exception) {
            error = HeroCardLogic.errorText(e)
            false
        } finally {
            busy = false
        }
    }

    /** ONE PATCH with only the changed fields + if_rev. */
    suspend fun save(): Boolean {
        if (!dirty || draftErrors.isNotEmpty()) return false
        val sent = fields
        return act { repo.patchHero(bookId, hero.id, sent, hero.rev) }.also { ok ->
            // The draft equals what was sent — let the fresh hero replace it.
            if (ok) draft = HeroModel.draftFromHero(hero)
        }
    }

    suspend fun deleteHero(): Boolean = act { repo.deleteHero(bookId, hero.id) }

    // ── Voice reference — commands come with a click proof only ──────────

    suspend fun design(action: ClientAction): Boolean =
        act { repo.designVoiceCandidates(bookId, hero.id, action) }.also { if (it) loadCandidates() }

    suspend fun upload(fileName: String, mime: String, bytes: ByteArray, refText: String, action: ClientAction): Boolean =
        act { repo.uploadVoiceCandidate(bookId, hero.id, fileName, mime, bytes, refText, action) }.also { if (it) loadCandidates() }

    suspend fun pin(c: VoiceCandidate, editedRefText: String?): Boolean =
        act { repo.pinVoiceReference(bookId, hero.id, c.name, if (c.source == "uploaded") editedRefText else null) }

    suspend fun unpin(): Boolean = act { repo.clearVoiceReference(bookId, hero.id) }

    suspend fun deleteCandidate(c: VoiceCandidate): Boolean =
        act { repo.deleteVoiceCandidate(bookId, hero.id, c.name) }.also { if (it) loadCandidates() }

    /** The audio of a candidate for «Listen» (a read). */
    suspend fun voiceBytes(name: String): ByteArray = repo.voiceFile(bookId, hero.id, name)

    /** Image reference upload — 404 until backend step 7: the block turns «unavailable», not an error. */
    var imagesUnavailable = false
        private set

    suspend fun uploadImage(fileName: String, mime: String, bytes: ByteArray): Boolean {
        var missing = false
        val kind = if ((hero.image_refs ?: emptyList()).isEmpty()) "portrait" else "other"
        val ok = act {
            try { repo.uploadImageRef(bookId, hero.id, fileName, mime, bytes, kind) } catch (e: HeroApiError) {
                if (HeroCardLogic.isUnavailable(e)) { missing = true; null } else throw e
            }
        }
        if (ok && missing) { imagesUnavailable = true; error = null }
        return ok && !missing
    }

    // ── Rules ────────────────────────────────────────────────────────────

    suspend fun setRuleEnabled(rule: HeroRule, enabled: Boolean): Boolean =
        act { repo.patchRule(bookId, hero.id, rule.id, mapOf("enabled" to enabled)) }

    suspend fun addRule(rule: HeroRule): Boolean = act { repo.createRule(bookId, hero.id, rule) }

    suspend fun deleteRule(rule: HeroRule): Boolean = act { repo.deleteRule(bookId, hero.id, rule.id) }

    // ── Looks ────────────────────────────────────────────────────────────

    suspend fun createLook(id: String?, label: String, fields: LookFields, notes: String?): Boolean =
        act { repo.createLook(bookId, hero.id, id, label, fields, notes) }

    suspend fun patchLook(lookId: String, fields: Map<String, Any?>): Boolean = act { repo.patchLook(bookId, hero.id, lookId, fields) }

    suspend fun deleteLook(lookId: String): Boolean = act { repo.deleteLook(bookId, hero.id, lookId) }

    /** The assign dialog: release first, then assign (two calls of POST /looks/assign). */
    suspend fun applyAssignment(lookId: String, assign: LookAssignment, release: LookAssignment): Boolean = act {
        var last: HeroMutationResult? = null
        if (!HeroModel.isEmptyAssignment(release)) last = repo.assignLook(bookId, hero.id, null, release)
        if (!HeroModel.isEmptyAssignment(assign)) last = repo.assignLook(bookId, hero.id, lookId, assign)
        last
    }
}
