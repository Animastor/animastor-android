package com.example.animastor.repository

import com.example.animastor.repository.HeroFixtures.array
import com.example.animastor.repository.HeroFixtures.hero
import com.example.animastor.repository.HeroFixtures.narrator
import com.example.animastor.repository.HeroFixtures.shopWomen
import com.example.animastor.repository.HeroFixtures.yura
import com.example.animastor.repository.HeroModel.ReferenceStatus
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hero model — the pure logic of the hero card (HERO_CARD.md §4, §5, §8).
 * One to one with web features/heroes/heroModel.test.ts: the same cases,
 * the same fixtures (HeroFixtures = heroFixtures.ts).
 */
class HeroModelTest {

    private fun issues(json: String) = HeroModel.parseIssues(JsonParser.parseString(json))

    // ── order and kind ──

    @Test fun `puts the narrator first and keeps the server order of the rest`() {
        val list = HeroModel.orderHeroes(array(HeroFixtures.yuraJson, HeroFixtures.shopWomenJson, HeroFixtures.narratorJson))
        assertEquals(listOf("narrator", "yura", "shop_women"), list.map { it.id })
    }

    @Test fun `drops entries that cannot be addressed and survives a non-array`() {
        val raw = JsonParser.parseString("[${HeroFixtures.yuraJson}, null, {\"name\":\"x\"}, {\"id\":\"\"}, \"yura\"]")
        assertEquals(listOf("yura"), HeroModel.orderHeroes(raw).map { it.id })
        assertEquals(emptyList<Hero>(), HeroModel.orderHeroes(null as com.google.gson.JsonElement?))
        assertEquals(emptyList<Hero>(), HeroModel.orderHeroes(JsonParser.parseString("{\"yura\":{}}")))
    }

    @Test fun `kind follows the blocks - passport = character, no passport = voice only`() {
        assertEquals(HeroModel.HeroKind.CHARACTER, HeroModel.heroKind(yura))
        assertEquals(HeroModel.HeroKind.VOICE_ONLY, HeroModel.heroKind(shopWomen))
        assertEquals(HeroModel.HeroKind.NARRATOR, HeroModel.heroKind(narrator))
        assertEquals(HeroModel.HeroKind.CHARACTER, HeroModel.heroKind(hero("""{"id":"mute","passport":{}}""")))
    }

    // ── portrait and stats ──

    @Test fun `portrait is the first ref of kind portrait, else the first ref`() {
        assertEquals(1, HeroModel.portraitIndex(yura))
        assertEquals(0, HeroModel.portraitIndex(hero("""{"id":"a","image_refs":[{"file":"refs/images/a/x.png","sha256":"${"c".repeat(64)}"}]}""")))
        assertEquals(-1, HeroModel.portraitIndex(narrator))
    }

    @Test fun `monogram - two initials of the name, else of the id`() {
        assertEquals("Ю", HeroModel.heroMonogram(yura))
        assertEquals("ЖВ", HeroModel.heroMonogram(shopWomen))
        assertEquals("PI", HeroModel.heroMonogram(hero("""{"id":"ponyrev_ivan"}""")))
    }

    @Test fun `stats count filled fields, enabled rules and the reference state`() {
        assertEquals(HeroStats(passport = 3, voice = 2, reference = ReferenceStatus.PINNED, rulesOn = 1, rulesTotal = 2, aliases = 2, images = 2), HeroModel.heroStats(yura))
        val n = HeroModel.heroStats(narrator)
        assertNull(n.passport); assertEquals(2, n.voice); assertEquals(ReferenceStatus.NONE, n.reference); assertEquals(0, n.rulesTotal)
        assertEquals(0, HeroModel.heroStats(hero("""{"id":"a","passport":{"appearance":" ","video_tokens":[]}}""")).passport)
    }

    @Test fun `reference status - missing and mismatch win over locked`() {
        val ref = yura.voice!!.reference!!
        assertEquals(ReferenceStatus.MISSING, HeroModel.referenceStatus(HeroVoice(reference = ref, reference_state = "missing")))
        assertEquals(ReferenceStatus.MISMATCH, HeroModel.referenceStatus(HeroVoice(reference = ref, reference_state = "mismatch")))
        assertEquals(ReferenceStatus.CANDIDATE, HeroModel.referenceStatus(HeroVoice(reference = ref.copy(locked = false), reference_state = "ok")))
        assertEquals(ReferenceStatus.NONE, HeroModel.referenceStatus(HeroVoice(timbre = "x")))
        assertEquals(ReferenceStatus.NONE, HeroModel.referenceStatus(null))
    }

    @Test fun `TTS text is join(timbre, manner)`() {
        assertEquals("Deep. Slow.", HeroModel.voiceInstruct(HeroVoice(timbre = " Deep. ", manner = "Slow.")))
        assertEquals("Slow.", HeroModel.voiceInstruct(HeroVoice(timbre = "", manner = "Slow.")))
        assertEquals("", HeroModel.voiceInstruct(null))
    }

    // ── new id ──

    @Test fun `new hero id - empty and canonical pass, bad, reserved and taken are refused`() {
        val existing = listOf("narrator", "yura")
        assertNull(HeroModel.validateNewHeroId("  ", existing))
        assertNull(HeroModel.validateNewHeroId("ponyrev_ivan", existing))
        for (id in listOf("Юра", "Ivan", "a__b", "_a", "a-b", "a b", "x".repeat(65))) {
            assertEquals(id, "hero_id_invalid", HeroModel.validateNewHeroId(id, existing))
        }
        assertEquals("hero_id_reserved", HeroModel.validateNewHeroId("manifest", existing))
        assertEquals("hero_id_reserved", HeroModel.validateNewHeroId("behaviour", existing))
        assertEquals("hero_id_taken", HeroModel.validateNewHeroId("yura", existing))
    }

    // ── text round-trips ──

    @Test fun `reactions keep the rules of the former Behavior tab`() {
        assertEquals("is praised → looks away", HeroModel.reactionsToText(yura.behaviour!!.profile!!.reactions))
        assertEquals(
            listOf(HeroReaction("a", "b"), HeroReaction("c", "d → e"), HeroReaction("lonely", null)),
            HeroModel.textToReactions("a -> b\nc → d → e\nlonely"),
        )
    }

    @Test fun `aliases unique ignoring case, tokens split on commas and new lines`() {
        assertEquals(listOf("Юрий", "Юрка"), HeroModel.textToUniqueLines("Юрий\n юрий \n\nЮрка"))
        assertEquals(listOf("man", "dark hair", "leather jacket"), HeroModel.textToTokens("man, dark hair\nleather jacket,,"))
    }

    // ── draft diff → PATCH fields ──

    @Test fun `an untouched card sends nothing`() {
        for (h in listOf(yura, narrator, shopWomen)) {
            assertEquals(h.id, emptyMap<String, Any?>(), HeroModel.diffDraft(h, HeroModel.draftFromHero(h)))
            assertFalse(HeroModel.isDraftDirty(h, HeroModel.draftFromHero(h)))
        }
    }

    @Test fun `sends only changed fields, empty text clears with null`() {
        val d = HeroModel.draftFromHero(yura).copy(name = "Юрий Петрович", clothes = "", manner = "Fast.")
        assertEquals(mapOf("name" to "Юрий Петрович", "passport.clothes" to null, "voice.manner" to "Fast."), HeroModel.diffDraft(yura, d))
    }

    @Test fun `lists are sent as arrays`() {
        val d = HeroModel.draftFromHero(yura).copy(aliases = "Юрий\nЮра-джан", videoTokens = "man, coat", quirks = "")
        assertEquals(mapOf(
            "aliases" to listOf("Юрий", "Юра-джан"),
            "passport.video_tokens" to listOf("man", "coat"),
            "behaviour.profile.quirks" to emptyList<String>(),
        ), HeroModel.diffDraft(yura, d))
    }

    @Test fun `re-typed aliases and untouched reactions are not a change`() {
        assertEquals(emptyMap<String, Any?>(), HeroModel.diffDraft(yura, HeroModel.draftFromHero(yura).copy(aliases = " Юрий \n\nЮрка\n")))
        assertEquals(mapOf("behaviour.profile.baseline" to "nervous"), HeroModel.diffDraft(yura, HeroModel.draftFromHero(yura).copy(baseline = "nervous")))
    }

    @Test fun `removing a block sends null and none of its fields`() {
        val d = HeroModel.draftFromHero(yura).copy(hasPassport = false, appearance = "changed", hasVoice = false)
        assertEquals(mapOf("passport" to null, "voice" to null), HeroModel.diffDraft(yura, d))
    }

    @Test fun `adding a block - its fields create it, an empty block is created explicitly`() {
        assertEquals(mapOf("passport.appearance" to "grey shawl"), HeroModel.diffDraft(shopWomen, HeroModel.draftFromHero(shopWomen).copy(hasPassport = true, appearance = "grey shawl")))
        assertEquals(mapOf("passport" to emptyMap<String, Any?>()), HeroModel.diffDraft(shopWomen, HeroModel.draftFromHero(shopWomen).copy(hasPassport = true)))
        val mute = hero("""{"id":"mute","name":"Немой","passport":{}}""")
        assertEquals(mapOf("voice" to emptyMap<String, Any?>()), HeroModel.diffDraft(mute, HeroModel.draftFromHero(mute).copy(hasVoice = true)))
    }

    // ── validation ──

    @Test fun `draft validation`() {
        assertEquals(emptyList<String>(), HeroModel.validateDraft(yura, HeroModel.draftFromHero(yura)))
        assertEquals(listOf("hero_err_name_required"), HeroModel.validateDraft(yura, HeroModel.draftFromHero(yura).copy(name = " ")))
        assertEquals(emptyList<String>(), HeroModel.validateDraft(narrator, HeroModel.draftFromHero(narrator)))
        assertEquals(emptyList<String>(), HeroModel.validateDraft(shopWomen, HeroModel.draftFromHero(shopWomen).copy(name = "")))
        assertEquals(listOf("hero_err_tokens_max"), HeroModel.validateDraft(yura, HeroModel.draftFromHero(yura).copy(videoTokens = "a,b,c,d,e")))
        assertEquals(listOf("hero_err_alias_is_name"), HeroModel.validateDraft(yura, HeroModel.draftFromHero(yura).copy(aliases = "юра")))
    }

    @Test fun `item 9 - passport null of a hero with looks is blocked (400 invalid_character)`() {
        val h = hero("""{"id":"yura","name":"Юра","passport":{"appearance":"a"},"looks":[{"id":"coat","label":"c","fields":{"clothes":"x"}}]}""")
        val d = HeroModel.draftFromHero(h).copy(hasPassport = false)
        assertTrue(HeroModel.validateDraft(h, d).contains("hero_err_passport_has_looks"))
        assertFalse(HeroModel.validateDraft(h.copy(looks = emptyList()), d).contains("hero_err_passport_has_looks"))
    }

    // ── rules ──

    @Test fun `rule ids of the whole book and a new id never collides`() {
        assertEquals(listOf("global.joyful_smile", "yura.praised", "yura.angry"), HeroModel.allRuleIds(listOf(yura, narrator), HeroFixtures.globals))
        assertEquals("yura.rubs_the_bridge", HeroModel.newRuleId("yura", "Rubs the bridge of his nose", emptyList()))
        assertEquals("yura.goes_silent_3", HeroModel.newRuleId("yura", "goes silent", listOf("yura.goes_silent", "yura.goes_silent_2")))
        assertEquals("yura.rule", HeroModel.newRuleId("yura", "молчит", emptyList()))
    }

    // ── issues and unsupported book ──

    @Test fun `blocking issues become broken tiles, warnings go to their hero`() {
        val list = issues("""[{"file":"characters/berlioz.json","hero_id":"berlioz","code":"invalid_json","message":"Unexpected token","blocking":true},
            {"hero_id":"yura","code":"unknown_key","message":"unknown key \"voise\"","blocking":false},{"code":"schema"},"junk"]""")
        assertEquals(listOf("invalid_json", "schema"), HeroModel.blockingIssues(list).map { it.code })
        assertEquals(listOf("unknown_key"), HeroModel.heroWarnings(list, "yura").map { it.code })
        assertEquals(emptyList<HeroIssue>(), HeroModel.heroWarnings(list, "berlioz"))
        assertEquals(emptyList<HeroIssue>(), HeroModel.blockingIssues(HeroModel.parseIssues(null)))
    }

    @Test fun `item 14 - recognizes the typed VBook 3_1 error and nothing else`() {
        fun body(s: String) = JsonParser.parseString(s).asJsonObject
        assertEquals(UnsupportedVbook("3.1", 4), HeroModel.unsupportedVbook(body("""{"error":"unsupported_vbook_version","vbook_version":"3.1","supported":4}""")))
        assertEquals(UnsupportedVbook("?", 4), HeroModel.unsupportedVbook(body("""{"error":"unsupported_vbook_version"}""")))
        assertNull(HeroModel.unsupportedVbook(body("""{"error":"Book not found"}""")))
        assertNull(HeroModel.unsupportedVbook(null))
    }

    // ── step 4 contract helpers (HERO_CARD.md §7, §10) ──

    @Test fun `item 3 - pin only ready + passed, delete except pinned and pending`() {
        assertTrue(HeroModel.canPinCandidate("ready", "passed", null))
        assertFalse(HeroModel.canPinCandidate("ready", "failed", null))
        assertFalse(HeroModel.canPinCandidate("ready", "user", null))
        assertFalse(HeroModel.canPinCandidate("pending", "passed", null))
        assertFalse(HeroModel.canPinCandidate("ready", "passed", true))
        assertFalse(HeroModel.canDeleteCandidate("pending", null))
        assertFalse(HeroModel.canDeleteCandidate("ready", true))
        assertTrue(HeroModel.canDeleteCandidate("failed", null))
    }

    @Test fun `item 3 - candidateLine pending, failed (error), passed, rejected (problems)`() {
        assertEquals(CandidateLine("hero_ref_cand_pending", ""), HeroModel.candidateLine("pending", null, null, null))
        assertEquals(CandidateLine("hero_ref_cand_failed", "timeout"), HeroModel.candidateLine("failed", null, null, "timeout"))
        assertEquals(CandidateLine("hero_ref_cand_passed", ""), HeroModel.candidateLine("ready", "passed", null, null))
        assertEquals(CandidateLine("hero_ref_cand_rejected", "too_short, asr_mismatch"), HeroModel.candidateLine("ready", "failed", listOf("too_short", "asr_mismatch"), null))
    }

    @Test fun `item 11 - duplicateWarnings only possible_duplicate of this hero, evidence cleaned`() {
        val list = issues("""[{"code":"possible_duplicate","hero_id":"koroviev","of":"fagot","evidence":["клетчатый","",3],"blocking":false},
            {"code":"possible_duplicate","hero_id":"fagot","of":"koroviev","blocking":false},{"code":"unknown_key","hero_id":"koroviev","blocking":false}]""")
        assertEquals(listOf(DuplicateWarning("fagot", listOf("клетчатый"))), HeroModel.duplicateWarnings(list, "koroviev"))
        assertEquals(listOf(DuplicateWarning("koroviev", emptyList())), HeroModel.duplicateWarnings(list, "fagot"))
        assertEquals(listOf("unknown_key"), HeroModel.heroWarnings(list, "koroviev").map { it.code })
    }

    @Test fun `item 11 - unknownParticipants with its scenes`() {
        val list = issues("""[{"code":"unknown_participant","participant":"berlioz","message":"m","scenes":[{"chapter_id":"c1","scene_id":"s1"},{"bad":1}],"blocking":false}]""")
        assertEquals(listOf(UnknownParticipant("berlioz", "m", listOf(HeroSceneRef("c1", "s1")))), HeroModel.unknownParticipants(list))
    }

    @Test fun `item 6 - brokenFileTarget by hero id, else by the plain file name`() {
        assertEquals(BrokenFileTarget.ByHero("anna"), HeroModel.brokenFileTarget(HeroIssue(code = "invalid_json", hero_id = "anna", file = "characters/anna.json")))
        assertEquals(BrokenFileTarget.ByFile("Юра.json"), HeroModel.brokenFileTarget(HeroIssue(code = "invalid_file_name", file = "characters/Юра.json")))
        assertNull(HeroModel.brokenFileTarget(HeroIssue(code = "x", file = "characters/sub/a.json")))
        assertNull(HeroModel.brokenFileTarget(HeroIssue(code = "x")))
    }

    @Test fun `item 8 - dirtySummary unique scenes and layers`() {
        assertEquals(DirtySummary(2, listOf("image", "video")), HeroModel.dirtySummary(listOf(
            HeroDirtyScene("c1", "s1", dirty_layers = listOf("video", "image")),
            HeroDirtyScene("c1", "s1", dirty_layers = listOf("image")),
            HeroDirtyScene("c2", "s1"),
        )))
        assertEquals(DirtySummary(0, emptyList()), HeroModel.dirtySummary(null))
    }
}
