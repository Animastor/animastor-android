package com.example.animastor.repository

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.util.Locale

// ======================================================
// Hero model — VBook v4 «файл на героя»
// ======================================================
// animastor-docs architecture/VBOOK_FORMAT_V4.md, 05-frontend/HERO_CARD.md §8:
// the pure half of the hero card, one to one with web
// frontends/app/src/features/heroes/heroModel.ts — the same function names,
// the same cases, the same fixtures (HeroModelTest / HeroLooksTest). No I/O,
// no views.
//
// A hero is ONE object everywhere: characters/<id>.json on disk,
// book.heroes[] in memory, heroes[] in GET /book. The raw JSON arrays of the
// book (heroes, hero_issues) are parsed entry by entry: one odd entry never
// breaks the book (web: `unknown` + filters).

data class HeroPassport(
    val appearance: String? = null,
    val clothes: String? = null,
    /** An array of 1-4 short visual features (a legacy string is tolerated). */
    val video_tokens: Any? = null,
)

data class VoiceReference(
    val file: String? = null,
    val sha256: String? = null,
    val ref_text: String? = null,
    /** "designed" | "uploaded" */
    val source: String? = null,
    val model: String? = null,
    val seed: Long? = null,
    val verification: String? = null,
    val locked: Boolean? = null,
)

data class HeroVoice(
    val timbre: String? = null,
    val manner: String? = null,
    val reference: VoiceReference? = null,
    /** Computed by the server, never stored: "ok" | "missing" | "mismatch". */
    val reference_state: String? = null,
    /** Computed by the server: the pinned DESIGNED reference was made from another timbre (owner 18.11). */
    val reference_outdated: Boolean? = null,
)

data class HeroReaction(
    val trigger: String? = null,
    val reaction: String? = null,
)

data class HeroBehaviourProfile(
    val baseline: String? = null,
    val quirks: List<String>? = null,
    val reactions: List<HeroReaction>? = null,
)

data class RuleWhen(val emotion: List<String>? = null, val unit_type: List<String>? = null)
data class RuleThen(val action: String? = null)

/** Behaviour rule of a hero — no scope / character_id (implied by the file). */
data class HeroRule(
    val id: String,
    @SerializedName("when") val whenCond: RuleWhen? = null,
    @SerializedName("then") val thenDo: RuleThen? = null,
    val strength: Double? = null,
    val probability: Double? = null,
    val cooldown_iu: Int? = null,
    val priority: Int? = null,
    val enabled: Boolean? = null,
)

data class HeroBehaviour(
    val profile: HeroBehaviourProfile? = null,
    val rules: List<HeroRule>? = null,
)

data class HeroImageRef(
    val file: String? = null,
    val sha256: String? = null,
    /** "portrait" | "full_body" | "other" */
    val kind: String? = null,
    val source: String? = null,
    val locked: Boolean? = null,
    val note: String? = null,
    /** Computed by the server, never stored. */
    val state: String? = null,
)

/** Fields a look replaces — each one REPLACES the base passport field as a whole. */
data class LookFields(
    val appearance: String? = null,
    val clothes: String? = null,
    val video_tokens: List<String>? = null,
)

/** Named look of a hero — a passport override (VBOOK_FORMAT_V4.md §4a). */
data class HeroLook(
    val id: String,
    val label: String? = null,
    val fields: LookFields? = null,
    val image_ref: HeroImageRef? = null,
    val notes: String? = null,
)

data class Hero(
    val schema: String? = null,
    val id: String,
    val name: String? = null,
    val role: String? = null,
    val aliases: List<String>? = null,
    val passport: HeroPassport? = null,
    val looks: List<HeroLook>? = null,
    val voice: HeroVoice? = null,
    val behaviour: HeroBehaviour? = null,
    val image_refs: List<HeroImageRef>? = null,
    val notes: String? = null,
    /** Computed by the server (optimistic lock), never stored. */
    val rev: String? = null,
)

data class HeroSceneRef(val chapter_id: String, val scene_id: String)

/** A hero file problem reported by the loader (VBOOK_FORMAT_V4.md §10.3). */
data class HeroIssue(
    val file: String? = null,
    val hero_id: String? = null,
    val code: String,
    val message: String? = null,
    val blocking: Boolean? = null,
    /** possible_duplicate: the hero this one may be the same person as. */
    val of: String? = null,
    /** possible_duplicate: the quotes the analysis saw. */
    val evidence: List<String>? = null,
    /** unknown_participant: the id a scene names that is no hero. */
    val participant: String? = null,
    /** unknown_participant: where it is named. */
    val scenes: List<HeroSceneRef>? = null,
)

/** One stale scene in the answer of a hero / look mutation (VBOOK_FORMAT_V4.md §8.2). */
data class HeroDirtyScene(
    val chapter_id: String? = null,
    val scene_id: String? = null,
    val reason: String? = null,
    val dirty_layers: List<String>? = null,
)

/** Global behaviour document — behaviour.json. */
data class BehaviourGlobals(
    val schema: String? = null,
    val rules: List<HeroRule>? = null,
)

/** Editable copy of a hero: every field is text, blocks are toggled by flags. */
data class HeroDraft(
    val name: String,
    val role: String,
    val aliases: String,
    val notes: String,
    val hasPassport: Boolean,
    val appearance: String,
    val clothes: String,
    val videoTokens: String,
    val hasVoice: Boolean,
    val timbre: String,
    val manner: String,
    val baseline: String,
    val quirks: String,
    val reactions: String,
)

data class HeroStats(
    /** Filled passport fields out of 3; null without a passport. */
    val passport: Int?,
    /** Filled voice fields out of 2; null without a voice. */
    val voice: Int?,
    val reference: HeroModel.ReferenceStatus,
    val rulesOn: Int,
    val rulesTotal: Int,
    val aliases: Int,
    val images: Int,
)

data class DuplicateWarning(val of: String, val evidence: List<String>)

data class UnknownParticipant(val participant: String, val message: String, val scenes: List<HeroSceneRef>)

sealed class BrokenFileTarget {
    data class ByHero(val heroId: String) : BrokenFileTarget()
    data class ByFile(val file: String) : BrokenFileTarget()
}

data class DirtySummary(val scenes: Int, val layers: List<String>)

data class CandidateLine(val key: String, val detail: String)

data class UnsupportedVbook(val version: String, val supported: Int)

/** The parts of a chapter / scene the look resolver reads (Chapter / Scene of GET /book). */
data class LookChapter(
    val chapter_id: String? = null,
    val chapter_title: String? = null,
    val display_number: Int? = null,
    val looks: Map<String, String>? = null,
    val scenes: List<LookScene>? = null,
)

data class LookScene(
    val scene_id: String? = null,
    val scene_title: String? = null,
    val display_index: Int? = null,
    val participants: List<String>? = null,
    val looks: Map<String, String>? = null,
    val look_patch: Map<String, LookPatch?>? = null,
)

/** One-off scene patch as it arrives from the API (nulls tolerated; video_tokens may be a string). */
data class LookPatch(
    val appearance: String? = null,
    val clothes: String? = null,
    val video_tokens: Any? = null,
)

data class LookValues(val appearance: String, val clothes: String, val video_tokens: List<String>)

data class ResolvedLook(
    val hero_id: String,
    /** Look id, or "base". */
    val look_id: String,
    /** "scene" | "chapter" | "base" */
    val source: String,
    val appearance: String,
    val clothes: String,
    val video_tokens: List<String>,
    val base: LookValues,
    /** Fields that differ from the base passport. */
    val changed: List<String>,
    /** Fields that came from the scene's one-off patch. */
    val patched: List<String>,
    val image_ref: HeroImageRef?,
    val warnings: List<String>,
)

data class LookPlace(
    val chapterId: String,
    /** null — the whole chapter. */
    val sceneId: String?,
    val chapterNumber: Int?,
    val chapterTitle: String,
    val sceneNumber: Int?,
    val sceneTitle: String,
)

data class LookDraft(
    val label: String,
    val notes: String,
    val appearance: String,
    val clothes: String,
    val videoTokens: String,
)

data class LookAssignment(val chapters: List<String>, val scenes: List<HeroSceneRef>)

data class AssignmentDiff(val assign: LookAssignment, val release: LookAssignment)

object HeroModel {
    const val NARRATOR_ID = "narrator"
    val HERO_ID_RE = Regex("^[a-z0-9]+(?:_[a-z0-9]+)*$")
    const val HERO_ID_MAX = 64
    val RESERVED_HERO_IDS = listOf(
        "manifest", "book", "bible", "locations", "voices", "characters", "behavior", "behaviour", "mentions", "index",
    )
    const val VIDEO_TOKENS_MAX = 4
    const val ALIASES_MAX = 64

    /** Emotion dictionary of unit.state (VBOOK_BEHAVIOUR.md §3) — rule `when.emotion`. */
    val BEHAVIOUR_EMOTIONS = listOf(
        "neutral", "joyful", "sad", "angry", "afraid", "surprised", "disgusted", "embarrassed",
        "anxious", "proud", "confused", "determined", "thoughtful", "tender", "tense",
    )

    enum class HeroKind(val key: String) { NARRATOR("hero_kind_narrator"), CHARACTER("hero_kind_character"), VOICE_ONLY("hero_kind_voice_only") }

    enum class ReferenceStatus(val key: String) {
        NONE("hero_ref_none"), CANDIDATE("hero_ref_candidate"), PINNED("hero_ref_pinned"),
        MISSING("hero_ref_missing"), MISMATCH("hero_ref_mismatch"),
    }

    private val gson = Gson()

    fun isNarrator(hero: Hero): Boolean = hero.id == NARRATOR_ID

    /** character = has a passport (a real on-screen person); otherwise voice only. */
    fun heroKind(hero: Hero): HeroKind = when {
        isNarrator(hero) -> HeroKind.NARRATOR
        hero.passport != null -> HeroKind.CHARACTER
        else -> HeroKind.VOICE_ONLY
    }

    /** One hero of the raw array, or null when the entry cannot be addressed / parsed. */
    fun parseHero(raw: JsonElement?): Hero? {
        if (raw == null || !raw.isJsonObject) return null
        val idEl = raw.asJsonObject.get("id")
        if (idEl == null || !idEl.isJsonPrimitive || !idEl.asJsonPrimitive.isString || idEl.asString.isEmpty()) return null
        return runCatching { gson.fromJson(raw, Hero::class.java) }.getOrNull()
    }

    /**
     * Heroes of a book in display order: the narrator first, then the server
     * order. Entries without a string id are dropped — a card must be addressable.
     */
    fun orderHeroes(raw: JsonElement?): List<Hero> {
        if (raw == null || !raw.isJsonArray) return emptyList()
        return orderHeroes(raw.asJsonArray.mapNotNull { parseHero(it) })
    }

    fun orderHeroes(heroes: List<Hero>): List<Hero> {
        val valid = heroes.filter { it.id.isNotEmpty() }
        return valid.filter(::isNarrator).take(1) + valid.filterNot(::isNarrator)
    }

    fun findHero(heroes: List<Hero>, id: String?): Hero? = if (id.isNullOrEmpty()) null else heroes.firstOrNull { it.id == id }

    /** Card portrait: the first image ref of kind "portrait", else the first ref. */
    fun portraitIndex(hero: Hero): Int {
        val refs = hero.image_refs ?: emptyList()
        if (refs.isEmpty()) return -1
        val i = refs.indexOfFirst { it.kind == "portrait" }
        return if (i >= 0) i else 0
    }

    /** Monogram for the empty portrait frame: up to two initials of name, else id. */
    fun heroMonogram(hero: Hero): String {
        val source = (hero.name ?: "").trim().ifEmpty { hero.id }
        val words = source.split(Regex("[\\s_\\-]+")).filter { it.isNotEmpty() }
        val letters = words.take(2).joinToString("") { String(Character.toChars(it.codePointAt(0))) }
        return letters.uppercase(Locale.ROOT).ifEmpty { "?" }
    }

    /** The text TTS receives while there is no reference: join(timbre, manner). */
    fun voiceInstruct(voice: HeroVoice?): String {
        if (voice == null) return ""
        return listOf(voice.timbre, voice.manner).map { (it ?: "").trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun referenceStatus(voice: HeroVoice?): ReferenceStatus {
        val ref = voice?.reference ?: return ReferenceStatus.NONE
        return when (voice.reference_state) {
            "missing" -> ReferenceStatus.MISSING
            "mismatch" -> ReferenceStatus.MISMATCH
            else -> if (ref.locked == true) ReferenceStatus.PINNED else ReferenceStatus.CANDIDATE
        }
    }

    private fun filled(v: Any?): Boolean = when (v) {
        is List<*> -> v.any { it is String && it.isNotBlank() }
        is String -> v.isNotBlank()
        else -> false
    }

    /** The "characteristics" row of a grid card. */
    fun heroStats(hero: Hero): HeroStats {
        val p = hero.passport
        val v = hero.voice
        val rules = hero.behaviour?.rules ?: emptyList()
        return HeroStats(
            passport = p?.let { listOf(it.appearance, it.clothes, it.video_tokens).count(::filled) },
            voice = v?.let { listOf(it.timbre, it.manner).count(::filled) },
            reference = referenceStatus(v),
            rulesOn = rules.count { it.enabled != false },
            rulesTotal = rules.size,
            aliases = (hero.aliases ?: emptyList()).size,
            images = (hero.image_refs ?: emptyList()).size,
        )
    }

    // ── New hero id ──────────────────────────────────────────────────────

    /** "hero_id_invalid" | "hero_id_reserved" | "hero_id_taken" | null. Empty is fine (the server derives it). */
    fun validateNewHeroId(id: String, existing: Iterable<String>): String? {
        val value = id.trim()
        if (value.isEmpty()) return null
        if (!HERO_ID_RE.matches(value) || value.length > HERO_ID_MAX) return "hero_id_invalid"
        if (value in RESERVED_HERO_IDS) return "hero_id_reserved"
        if (existing.any { it == value }) return "hero_id_taken"
        return null
    }

    // ── Line-based text ⇄ arrays ─────────────────────────────────────────

    fun linesToText(items: List<String?>?): String = (items ?: emptyList()).filterNotNull().joinToString("\n")

    fun textToLines(text: String): List<String> = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    /** Unique lines; uniqueness ignores case and surrounding spaces, first spelling wins. */
    fun textToUniqueLines(text: String): List<String> {
        val seen = HashSet<String>()
        return textToLines(text).filter { seen.add(it.lowercase(Locale.ROOT)) }
    }

    fun tokensOf(tokens: Any?): List<String> = when (tokens) {
        is List<*> -> tokens.filterIsInstance<String>()
        else -> emptyList()
    }

    fun tokensToText(tokens: Any?): String = when (tokens) {
        is String -> tokens
        is List<*> -> tokens.filterIsInstance<String>().joinToString(", ")
        else -> ""
    }

    fun textToTokens(text: String): List<String> = text.split(Regex("[,\\n]")).map { it.trim() }.filter { it.isNotEmpty() }

    fun reactionsToText(reactions: List<HeroReaction>?): String = (reactions ?: emptyList()).joinToString("\n") { r ->
        val trigger = (r.trigger ?: "").trim()
        val reaction = (r.reaction ?: "").trim()
        if (reaction.isNotEmpty()) "$trigger → $reaction" else trigger
    }

    /** "trigger → reaction" per line ("→" or "->", the earliest wins); no separator → whole line is the trigger. */
    fun textToReactions(text: String): List<HeroReaction> = textToLines(text).map { line ->
        val seps = listOf(line.indexOf('→'), line.indexOf("->")).filter { it >= 0 }
        if (seps.isEmpty()) return@map HeroReaction(trigger = line, reaction = null)
        val at = seps.min()
        val sepLen = if (line[at] == '→') 1 else 2
        HeroReaction(trigger = line.substring(0, at).trim(), reaction = line.substring(at + sepLen).trim().ifEmpty { null })
    }

    // ── Draft ────────────────────────────────────────────────────────────

    fun draftFromHero(hero: Hero): HeroDraft {
        val p = hero.passport
        val v = hero.voice
        val profile = hero.behaviour?.profile
        return HeroDraft(
            name = hero.name ?: "",
            role = hero.role ?: "",
            aliases = linesToText(hero.aliases),
            notes = hero.notes ?: "",
            hasPassport = p != null,
            appearance = p?.appearance ?: "",
            clothes = p?.clothes ?: "",
            videoTokens = tokensToText(p?.video_tokens),
            hasVoice = v != null,
            timbre = v?.timbre ?: "",
            manner = v?.manner ?: "",
            baseline = profile?.baseline ?: "",
            quirks = linesToText(profile?.quirks),
            reactions = reactionsToText(profile?.reactions),
        )
    }

    /** Error keys: hero_err_name_required / tokens_max / aliases_max / alias_is_name / passport_has_looks. */
    fun validateDraft(hero: Hero, draft: HeroDraft): List<String> {
        val errors = mutableListOf<String>()
        if (draft.hasPassport && draft.name.trim().isEmpty() && !isNarrator(hero)) errors += "hero_err_name_required"
        if (draft.hasPassport && textToTokens(draft.videoTokens).size > VIDEO_TOKENS_MAX) errors += "hero_err_tokens_max"
        val aliases = textToUniqueLines(draft.aliases)
        if (aliases.size > ALIASES_MAX) errors += "hero_err_aliases_max"
        val name = draft.name.trim().lowercase(Locale.ROOT)
        if (name.isNotEmpty() && aliases.any { it.lowercase(Locale.ROOT) == name }) errors += "hero_err_alias_is_name"
        // passport: null of a hero with looks is 400 invalid_character — the looks go first.
        if (!draft.hasPassport && hero.passport != null && (hero.looks ?: emptyList()).isNotEmpty()) errors += "hero_err_passport_has_looks"
        return errors
    }

    /**
     * Draft → PATCH /heroes/:id `fields` — ONLY what the user changed. Values:
     * String, null (clears), List<String>, List<HeroReaction>, or an empty map
     * (creates an empty block). Insertion order = web order.
     */
    fun diffDraft(hero: Hero, draft: HeroDraft): Map<String, Any?> {
        val orig = draftFromHero(hero)
        val fields = LinkedHashMap<String, Any?>()
        fun text(key: String, next: String, prev: String) {
            if (next != prev) fields[key] = if (next.trim().isEmpty()) null else next
        }
        text("name", draft.name, orig.name)
        text("role", draft.role, orig.role)
        text("notes", draft.notes, orig.notes)
        if (draft.aliases != orig.aliases) {
            val next = textToUniqueLines(draft.aliases)
            if (next != (hero.aliases ?: emptyList<String>())) fields["aliases"] = next
        }
        if (!draft.hasPassport) {
            if (orig.hasPassport) fields["passport"] = null
        } else {
            text("passport.appearance", draft.appearance, orig.appearance)
            text("passport.clothes", draft.clothes, orig.clothes)
            if (draft.videoTokens != orig.videoTokens) fields["passport.video_tokens"] = textToTokens(draft.videoTokens)
            if (!orig.hasPassport && fields.keys.none { it.startsWith("passport.") }) fields["passport"] = emptyMap<String, Any?>()
        }
        if (!draft.hasVoice) {
            if (orig.hasVoice) fields["voice"] = null
        } else {
            text("voice.timbre", draft.timbre, orig.timbre)
            text("voice.manner", draft.manner, orig.manner)
            if (!orig.hasVoice && fields.keys.none { it.startsWith("voice.") }) fields["voice"] = emptyMap<String, Any?>()
        }
        text("behaviour.profile.baseline", draft.baseline, orig.baseline)
        if (draft.quirks != orig.quirks) fields["behaviour.profile.quirks"] = textToLines(draft.quirks)
        if (draft.reactions != orig.reactions) fields["behaviour.profile.reactions"] = textToReactions(draft.reactions)
        return fields
    }

    fun isDraftDirty(hero: Hero, draft: HeroDraft): Boolean = diffDraft(hero, draft).isNotEmpty()

    // ── Rules ────────────────────────────────────────────────────────────

    /** A unique rule id "<hero>.<slug>" that is free among the book's rule ids. */
    fun newRuleId(heroId: String, action: String, taken: Iterable<String>): String {
        val used = taken.toHashSet()
        val slug = action.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .split("_").take(3).joinToString("_").ifEmpty { "rule" }
        val base = "$heroId.$slug"
        if (base !in used) return base
        for (i in 2 until 1000) if ("${base}_$i" !in used) return "${base}_$i"
        return "${base}_${System.currentTimeMillis()}"
    }

    /** Every rule id of the book: global rules + rules of all heroes. */
    fun allRuleIds(heroes: List<Hero>, globals: BehaviourGlobals?): List<String> =
        (globals?.rules ?: emptyList()).map { it.id }.filter { it.isNotEmpty() } +
            heroes.flatMap { h -> (h.behaviour?.rules ?: emptyList()).map { it.id }.filter { it.isNotEmpty() } }

    // ── Issues ───────────────────────────────────────────────────────────

    /** The raw hero_issues array → typed issues (entries without a string code are dropped). */
    fun parseIssues(raw: JsonElement?): List<HeroIssue> {
        if (raw == null || !raw.isJsonArray) return emptyList()
        return raw.asJsonArray.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val code = o.str("code") ?: return@mapNotNull null
            HeroIssue(
                file = o.str("file"),
                hero_id = o.str("hero_id"),
                code = code,
                message = o.str("message"),
                blocking = o.get("blocking")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean,
                of = o.str("of"),
                evidence = o.get("evidence")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.map { it.asString },
                participant = o.str("participant"),
                scenes = o.get("scenes")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { s ->
                    if (!s.isJsonObject) return@mapNotNull null
                    val ch = s.asJsonObject.str("chapter_id")
                    val sc = s.asJsonObject.str("scene_id")
                    if (ch != null && sc != null) HeroSceneRef(ch, sc) else null
                },
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    fun blockingIssues(issues: List<HeroIssue>): List<HeroIssue> = issues.filter { it.blocking != false }

    /** Non-blocking warnings of one hero — the possible duplicates go to their own line. */
    fun heroWarnings(issues: List<HeroIssue>, heroId: String): List<HeroIssue> =
        issues.filter { it.blocking == false && it.hero_id == heroId && it.code != "possible_duplicate" }

    /** `possible_duplicate` of this hero: a yellow line on the card of `hero_id`; nothing is merged. */
    fun duplicateWarnings(issues: List<HeroIssue>, heroId: String): List<DuplicateWarning> =
        issues.filter { it.code == "possible_duplicate" && it.hero_id == heroId && it.of != null }
            .map { DuplicateWarning(it.of!!, (it.evidence ?: emptyList()).filter { e -> e.isNotBlank() }) }

    fun unknownParticipants(issues: List<HeroIssue>): List<UnknownParticipant> =
        issues.filter { it.code == "unknown_participant" && it.participant != null }
            .map { UnknownParticipant(it.participant!!, it.message ?: "", it.scenes ?: emptyList()) }

    /** How a broken tile is deleted: by hero id or by the plain file name; null — nothing addressable. */
    fun brokenFileTarget(issue: HeroIssue): BrokenFileTarget? {
        if (!issue.hero_id.isNullOrEmpty()) return BrokenFileTarget.ByHero(issue.hero_id)
        val file = (issue.file ?: "").removePrefix("characters/")
        if (file.isEmpty() || file.contains('/')) return null
        return BrokenFileTarget.ByFile(file)
    }

    /** The stale scenes of a mutation answer, summed up for one info line. */
    fun dirtySummary(dirty: List<HeroDirtyScene>?): DirtySummary {
        val keys = HashSet<String>()
        val layers = sortedSetOf<String>()
        for (d in dirty ?: emptyList()) {
            if (d.chapter_id == null || d.scene_id == null) continue
            keys += "${d.chapter_id}/${d.scene_id}"
            d.dirty_layers?.let { layers.addAll(it) }
        }
        return DirtySummary(keys.size, layers.toList())
    }

    // ── Voice reference candidates (HERO_CARD.md §6) ─────────────────────

    /** Pin only a ready candidate whose automatic check passed (else 409 candidate_not_verified). */
    fun canPinCandidate(status: String?, verification: String?, pinned: Boolean?): Boolean =
        pinned != true && status == "ready" && verification == "passed"

    /** A candidate may be deleted unless it is pinned or still being made. */
    fun canDeleteCandidate(status: String?, pinned: Boolean?): Boolean = pinned != true && status != "pending"

    /** The state line of a candidate: a string key + details (problems / error). */
    fun candidateLine(status: String?, verification: String?, problems: List<String>?, error: String?): CandidateLine = when {
        status == "pending" -> CandidateLine("hero_ref_cand_pending", "")
        status == "failed" -> CandidateLine("hero_ref_cand_failed", error ?: "")
        verification == "passed" -> CandidateLine("hero_ref_cand_passed", "")
        else -> CandidateLine("hero_ref_cand_rejected", (problems ?: emptyList()).joinToString(", "))
    }

    // ── Unsupported book version (VBOOK_FORMAT_V4.md §9) ─────────────────

    /** The typed "book is VBook 3.1" error body of GET /book and import, or null. */
    fun unsupportedVbook(body: JsonObject?): UnsupportedVbook? {
        if (body == null || body.str("error") != "unsupported_vbook_version") return null
        val v = body.get("vbook_version")
        val version = if (v != null && v.isJsonPrimitive && (v.asJsonPrimitive.isString || v.asJsonPrimitive.isNumber)) v.asString else "?"
        val s = body.get("supported")
        val supported = if (s != null && s.isJsonPrimitive && s.asJsonPrimitive.isNumber) s.asInt else 4
        return UnsupportedVbook(version, supported)
    }

    // ── Looks (VBOOK_FORMAT_V4.md §4a) ───────────────────────────────────

    const val BASE_LOOK = "base"
    val LOOK_FIELDS = listOf("appearance", "clothes", "video_tokens")

    private fun baseValues(hero: Hero): LookValues {
        val p = hero.passport ?: HeroPassport()
        return LookValues(p.appearance ?: "", p.clothes ?: "", tokensOf(p.video_tokens))
    }

    private fun applyFields(target: LookValues, appearance: String?, clothes: String?, tokens: List<String>?): Pair<LookValues, List<String>> {
        var t = target
        val applied = mutableListOf<String>()
        if (!appearance.isNullOrEmpty()) { t = t.copy(appearance = appearance); applied += "appearance" }
        if (!clothes.isNullOrEmpty()) { t = t.copy(clothes = clothes); applied += "clothes" }
        if (!tokens.isNullOrEmpty()) { t = t.copy(video_tokens = tokens.toList()); applied += "video_tokens" }
        return t to applied
    }

    private fun changedFields(a: LookValues, b: LookValues): List<String> = LOOK_FIELDS.filter { f ->
        when (f) {
            "appearance" -> a.appearance != b.appearance
            "clothes" -> a.clothes != b.clothes
            else -> a.video_tokens != b.video_tokens
        }
    }

    fun findLook(hero: Hero, lookId: String?): HeroLook? {
        if (lookId.isNullOrEmpty() || lookId == BASE_LOOK) return null
        return (hero.looks ?: emptyList()).firstOrNull { it.id == lookId }
    }

    /**
     * The final look of a hero in a scene — client copy of vbook-runtime
     * resolveHeroLook: scene.looks[id] → chapter.looks[id] → base; the look's
     * fields over the passport; scene.look_patch[id] on top. Unknown look id —
     * skipped with a warning. null — no passport, no look.
     */
    fun resolveLook(hero: Hero, chapter: LookChapter?, scene: LookScene?): ResolvedLook? {
        if (hero.passport == null) return null
        val base = baseValues(hero)
        val warnings = mutableListOf<String>()
        var lookId = BASE_LOOK
        var source = "base"
        var look: HeroLook? = null
        for ((from, id) in listOf("scene" to scene?.looks?.get(hero.id), "chapter" to chapter?.looks?.get(hero.id))) {
            if (id.isNullOrEmpty()) continue
            if (id == BASE_LOOK) { source = from; break }
            val found = findLook(hero, id)
            if (found != null) { look = found; lookId = id; source = from; break }
            warnings += "unknown_look:$id"
        }
        val (afterLook, _) = applyFields(base, look?.fields?.appearance, look?.fields?.clothes, look?.fields?.video_tokens)
        val patch = scene?.look_patch?.get(hero.id)
        val (values, patched) = applyFields(afterLook, patch?.appearance, patch?.clothes, tokensOf(patch?.video_tokens))
        return ResolvedLook(
            hero_id = hero.id, look_id = lookId, source = source,
            appearance = values.appearance, clothes = values.clothes, video_tokens = values.video_tokens,
            base = base, changed = changedFields(values, base), patched = patched,
            image_ref = look?.image_ref, warnings = warnings,
        )
    }

    /** A look as the card shows it: its fields over the base passport, no scene context. */
    fun previewLook(hero: Hero, lookId: String): ResolvedLook? =
        resolveLook(hero, null, if (lookId == BASE_LOOK) null else LookScene(looks = mapOf(hero.id to lookId)))

    /**
     * Where each look of a hero is assigned: look id → chapters and scenes that
     * REFERENCE it. "base" lists scenes that force the base passport against their chapter.
     */
    fun lookUsage(chapters: List<LookChapter>?, heroId: String): Map<String, List<LookPlace>> {
        val out = LinkedHashMap<String, MutableList<LookPlace>>()
        (chapters ?: emptyList()).forEachIndexed { ci, ch ->
            val chapterId = ch.chapter_id ?: return@forEachIndexed
            val number = ch.display_number ?: (ci + 1)
            val title = ch.chapter_title ?: ""
            ch.looks?.get(heroId)?.takeIf { it.isNotEmpty() }?.let {
                out.getOrPut(it) { mutableListOf() } += LookPlace(chapterId, null, number, title, null, "")
            }
            (ch.scenes ?: emptyList()).forEachIndexed { si, sc ->
                val id = sc.looks?.get(heroId)
                if (id.isNullOrEmpty() || sc.scene_id == null) return@forEachIndexed
                out.getOrPut(id) { mutableListOf() } += LookPlace(chapterId, sc.scene_id, number, title, sc.display_index ?: (si + 1), sc.scene_title ?: "")
            }
        }
        return out
    }

    /** "look_id_invalid" | "look_id_reserved" | "look_id_taken" | null. */
    fun validateNewLookId(id: String, hero: Hero): String? {
        val value = id.trim()
        if (value.isEmpty()) return null
        if (!HERO_ID_RE.matches(value) || value.length > HERO_ID_MAX) return "look_id_invalid"
        if (value == BASE_LOOK) return "look_id_reserved"
        if ((hero.looks ?: emptyList()).any { it.id == value }) return "look_id_taken"
        return null
    }

    fun draftFromLook(look: HeroLook?): LookDraft = LookDraft(
        label = look?.label ?: "",
        notes = look?.notes ?: "",
        appearance = look?.fields?.appearance ?: "",
        clothes = look?.fields?.clothes ?: "",
        videoTokens = tokensToText(look?.fields?.video_tokens),
    )

    /** Draft → `fields` of a look (only what the look really overrides). */
    fun lookFieldsFromDraft(draft: LookDraft): LookFields {
        val tokens = textToTokens(draft.videoTokens)
        return LookFields(
            appearance = draft.appearance.trim().ifEmpty { null },
            clothes = draft.clothes.trim().ifEmpty { null },
            video_tokens = tokens.ifEmpty { null },
        )
    }

    private fun LookFields.isEmpty() = appearance == null && clothes == null && video_tokens == null

    /** look_err_label_required / look_err_empty / hero_err_tokens_max. */
    fun validateLookDraft(draft: LookDraft): List<String> {
        val errors = mutableListOf<String>()
        if (draft.label.trim().isEmpty()) errors += "look_err_label_required"
        val fields = lookFieldsFromDraft(draft)
        if (fields.isEmpty()) errors += "look_err_empty"
        if ((fields.video_tokens?.size ?: 0) > VIDEO_TOKENS_MAX) errors += "hero_err_tokens_max"
        return errors
    }

    /** Draft → PATCH /looks/:id `fields`: only changes; null removes a field from the look. */
    fun diffLook(look: HeroLook, draft: LookDraft): Map<String, Any?> {
        val orig = draftFromLook(look)
        val out = LinkedHashMap<String, Any?>()
        if (draft.label.trim() != orig.label) out["label"] = draft.label.trim()
        if (draft.notes != orig.notes) out["notes"] = draft.notes
        if (draft.appearance.trim() != orig.appearance) out["fields.appearance"] = draft.appearance.trim().ifEmpty { null }
        if (draft.clothes.trim() != orig.clothes) out["fields.clothes"] = draft.clothes.trim().ifEmpty { null }
        if (draft.videoTokens != orig.videoTokens) {
            val tokens = textToTokens(draft.videoTokens)
            if (tokens != (look.fields?.video_tokens ?: emptyList<String>())) out["fields.video_tokens"] = tokens.ifEmpty { null }
        }
        return out
    }

    fun lookPlaceKey(chapterId: String, sceneId: String?): String = "$chapterId/${sceneId ?: ""}"

    /** The assign dialog: places checked now vs. places that reference the look → assign / release. */
    fun diffAssignment(current: List<LookPlace>, checked: Set<String>): AssignmentDiff {
        val had = current.map { lookPlaceKey(it.chapterId, it.sceneId) }.toCollection(LinkedHashSet())
        fun split(keys: List<String>): LookAssignment {
            val chapters = mutableListOf<String>()
            val scenes = mutableListOf<HeroSceneRef>()
            for (key in keys) {
                val at = key.indexOf('/')
                val ch = key.substring(0, at)
                val sc = key.substring(at + 1)
                if (sc.isNotEmpty()) scenes += HeroSceneRef(ch, sc) else chapters += ch
            }
            return LookAssignment(chapters, scenes)
        }
        return AssignmentDiff(
            assign = split(checked.filter { it !in had }),
            release = split(had.filter { it !in checked }),
        )
    }

    fun isEmptyAssignment(a: LookAssignment): Boolean = a.chapters.isEmpty() && a.scenes.isEmpty()
}
