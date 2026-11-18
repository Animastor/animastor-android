package com.example.animastor.ui.heroes

import com.example.animastor.repository.HeroApi
import com.example.animastor.repository.HeroApiError
import com.example.animastor.repository.HeroFixtures
import com.example.animastor.repository.HeroModel
import com.example.animastor.repository.HeroMutationResult
import com.example.animastor.repository.HeroDirtyScene
import com.example.animastor.repository.HeroRepository
import com.example.animastor.repository.LookAssignment
import com.example.animastor.repository.HeroSceneRef
import com.example.animastor.repository.VoiceCandidate
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.io.File

/**
 * The hero card: generation and writes ONLY by an explicit action
 * (HERO_CARD.md §6, §10; web HeroesPanel.test.tsx «opening the grid calls no
 * API at all», «opening a card only READS candidates»). Every request of the
 * card goes through [HeroCardController]; MockWebServer counts them.
 */
class HeroCardControllerTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: HeroRepository
    private var changed = 0

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient()).build().create(HeroApi::class.java)
        repo = HeroRepository(api)
        changed = 0
    }

    @After fun tearDown() { server.shutdown() }

    private fun controller(hero: com.example.animastor.repository.Hero = HeroFixtures.yura) =
        HeroCardController(repo, "b1", hero) { changed++ }

    private fun answer(code: Int = 200, body: String = "{}") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test fun `item 15 - opening a card only READS the candidate list - one GET, no command`() = runBlocking {
        answer(body = """{"candidates":[],"pending":false}""")
        val c = controller()
        c.loadCandidates()
        assertEquals(1, server.requestCount)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/api/v1/book/b1/heroes/yura/voice/candidates", r.path)
        assertEquals(0, changed)
    }

    @Test fun `a hero without a voice does not even read`() = runBlocking {
        controller(HeroFixtures.hero("""{"id":"mute","name":"М","passport":{}}""")).loadCandidates()
        assertEquals(0, server.requestCount)
    }

    @Test fun `item 12 - 409 book_without_build, 404 and 503 orch_v2_disabled degrade the block, not an error`() = runBlocking {
        for ((code, body) in listOf(409 to """{"error":"book_without_build","message":"no build"}""", 404 to """{"error":"hero_not_found"}""", 503 to """{"error":"orch_v2_disabled","message":"off"}""")) {
            answer(code, body)
            val c = controller()
            c.loadCandidates()
            assertTrue("$code", c.unavailable)
            assertNull("$code", c.error)
        }
        answer(500, """{"error":"boom","message":"Internal"}""")
        val c = controller()
        c.loadCandidates()
        assertFalse(c.unavailable)
        assertEquals(UiText.Raw("Internal"), c.error)
    }

    @Test fun `item 3 - pending candidates are re-read every 3 s (only the list)`() = runBlocking {
        assertEquals(3000L, HeroCardLogic.CANDIDATES_POLL_MS)
        answer(body = """{"candidates":[{"name":"c1.wav","status":"pending"}],"pending":true}""")
        val c = controller()
        c.loadCandidates()
        assertTrue(c.pending)
        // The dialog's poll loop calls loadCandidates() only — a GET, never a command.
        val src = File("src/main/java/com/example/animastor/ui/heroes/HeroCardDialog.kt").readText()
        val poll = src.substringAfter("private fun maybePoll()").substringBefore("private fun stopPolling()")
        assertTrue(poll.contains("delay(HeroCardLogic.CANDIDATES_POLL_MS)"))
        assertTrue(poll.contains("controller.loadCandidates()"))
        assertFalse(poll.contains("design(") || poll.contains("upload(") || poll.contains("act {"))
    }

    @Test fun `save - one PATCH with only the changed fields and if_rev, then GET book is re-read`() = runBlocking {
        answer(body = """{"rev":"r2","dirty_scenes":[{"chapter_id":"c1","scene_id":"s1","dirty_layers":["image","video"]}]}""")
        val c = controller()
        c.draft = c.draft.copy(manner = "Fast.")
        assertTrue(c.save())
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals(JsonParser.parseString("""{"fields":{"voice.manner":"Fast."},"if_rev":"0123456789abcdef"}"""), JsonParser.parseString(r.body.readUtf8()))
        assertEquals(1, changed)
        assertEquals(UiText.Key("hero_dirty_scenes", listOf(1, "image, video")), c.notice)
    }

    @Test fun `nothing changed or an invalid draft - no request at all (item 9 passport null with looks)`() = runBlocking {
        val c = controller()
        assertFalse(c.save())
        c.draft = c.draft.copy(hasPassport = false)
        assertTrue(c.draftErrors.contains("hero_err_passport_has_looks"))
        assertFalse(c.save())
        assertEquals(0, server.requestCount)
    }

    @Test fun `item 7 - rev_conflict gets its own text, other errors show the server message`() = runBlocking {
        answer(409, """{"error":"rev_conflict","message":"changed elsewhere"}""")
        val c = controller()
        c.draft = c.draft.copy(notes = "x")
        assertFalse(c.save())
        assertEquals(UiText.Key("hero_rev_conflict"), c.error)
        assertEquals(UiText.Key("hero_err_passport_has_looks"),
            HeroCardLogic.errorText(HeroApiError(400, "passport: null with looks", "invalid_character", "invalid_character", null)))
        assertEquals(UiText.Raw("Name required"), HeroCardLogic.errorText(HeroApiError(400, "Name required", "name_required", "name_required", null)))
        assertEquals(0, changed)
    }

    @Test fun `design and upload are commands with the click proof, nothing else starts them`() = runBlocking {
        answer(); answer(body = """{"candidates":[],"pending":true}""")
        val c = controller(HeroFixtures.shopWomen)
        assertTrue(c.design(HeroCardLogic.clickAction(HeroCardLogic.SOURCE_DESIGN)))
        val post = server.takeRequest()
        assertEquals("/api/v1/book/b1/heroes/shop_women/voice/candidates/design", post.path)
        val body = JsonParser.parseString(post.body.readUtf8()).asJsonObject
        assertEquals("user_click", body.getAsJsonObject("client_action").get("kind").asString)
        assertEquals("hero_card.voice_design", body.getAsJsonObject("client_action").get("source").asString)
        assertTrue(body.getAsJsonObject("client_action").get("trusted").asBoolean)
        assertEquals("GET", server.takeRequest().method) // the list is re-read after the command

        // Source guard: the dialog calls design / upload only from their click listeners.
        val src = File("src/main/java/com/example/animastor/ui/heroes/HeroCardDialog.kt").readText()
        assertEquals(1, Regex("controller\\.design\\(").findAll(src).count())
        assertEquals(1, Regex("controller\\.upload\\(").findAll(src).count())
        val designAt = src.indexOf("controller.design(")
        assertTrue(src.lastIndexOf("HeroCardLogic.clickAction(HeroCardLogic.SOURCE_DESIGN)", designAt) in (designAt - 300) until designAt)
        val uploadAt = src.indexOf("controller.upload(")
        assertTrue(src.lastIndexOf("HeroCardLogic.clickAction(HeroCardLogic.SOURCE_UPLOAD)", uploadAt) in (uploadAt - 600) until uploadAt)
        // show() / refresh() only read.
        val show = src.substringAfter("fun show()").substringBefore("fun dismiss()")
        assertFalse(show.contains("design(") || show.contains("upload(") || show.contains("save("))
    }

    @Test fun `item 15 - the grid builder calls no API`() {
        val src = File("src/main/java/com/example/animastor/ui/heroes/HeroesPanel.kt").readText()
        for (forbidden in listOf("HeroRepository", "repository.heroes", "RetrofitClient", "launch", "HeroCardController")) {
            assertFalse(forbidden, src.contains(forbidden))
        }
    }

    @Test fun `items 4 and 5 - a pinned reference hides the commands, outdated is shown`() {
        val yura = HeroFixtures.yura
        val pinned = HeroCardLogic.voiceBlock(yura, emptyList(), pending = false, listOutdated = true, busy = false, timbreDirty = false)
        assertTrue(pinned.pinned); assertFalse(pinned.showCommands); assertTrue(pinned.outdated)
        assertEquals("hero_ref_pinned_hint", pinned.hint)
        val outdatedByHero = HeroCardLogic.voiceBlock(yura.copy(voice = yura.voice!!.copy(reference_outdated = true)), null, false, false, false, false)
        assertTrue(outdatedByHero.outdated)

        val shop = HeroFixtures.shopWomen
        val free = HeroCardLogic.voiceBlock(shop, emptyList(), false, false, false, false)
        assertTrue(free.showCommands); assertTrue(free.designEnabled); assertTrue(free.uploadEnabled); assertTrue(free.showNoCandidates)
        assertFalse(HeroCardLogic.voiceBlock(shop, emptyList(), pending = true, listOutdated = false, busy = false, timbreDirty = false).designEnabled)
        val dirty = HeroCardLogic.voiceBlock(shop, emptyList(), false, false, false, timbreDirty = true)
        assertFalse(dirty.designEnabled); assertEquals("hero_ref_save_first", dirty.hint)
        val noTimbre = HeroCardLogic.voiceBlock(shop.copy(voice = shop.voice!!.copy(timbre = " ")), emptyList(), false, false, false, false)
        assertFalse(noTimbre.designEnabled); assertTrue(noTimbre.uploadEnabled); assertEquals("hero_ref_need_timbre", noTimbre.hint)
    }

    @Test fun `item 3 - pin only ready + passed and never with a pinned reference, delete except pinned and pending`() {
        val ready = VoiceCandidate(name = "a.wav", status = "ready", verification = "passed")
        assertTrue(HeroCardLogic.canPin(ready, pinnedRef = false))
        assertFalse(HeroCardLogic.canPin(ready, pinnedRef = true))
        assertFalse(HeroCardLogic.canPin(ready.copy(verification = "failed"), false))
        assertFalse(HeroCardLogic.canDelete(ready.copy(status = "pending")))
        assertFalse(HeroCardLogic.canDelete(ready.copy(pinned = true)))
        assertTrue(HeroCardLogic.canDelete(ready))
    }

    @Test fun `pin of an uploaded candidate sends the corrected ref_text, a designed one never does`() = runBlocking {
        answer(); answer()
        val c = controller(HeroFixtures.shopWomen)
        c.pin(VoiceCandidate(name = "u.wav", source = "uploaded", status = "ready", verification = "passed"), "Исправлено.")
        assertEquals(JsonParser.parseString("""{"name":"u.wav","locked":true,"ref_text":"Исправлено."}"""), JsonParser.parseString(server.takeRequest().body.readUtf8()))
        c.pin(VoiceCandidate(name = "d.wav", source = "designed", status = "ready", verification = "passed"), "ignored")
        assertEquals(JsonParser.parseString("""{"name":"d.wav","locked":true}"""), JsonParser.parseString(server.takeRequest().body.readUtf8()))
    }

    @Test fun `assignment - release first, then assign (two POST looks assign)`() = runBlocking {
        answer(); answer()
        val c = controller()
        c.applyAssignment("coat", LookAssignment(listOf("ch-3"), emptyList()), LookAssignment(listOf("ch-2"), listOf(HeroSceneRef("ch-1", "s-1"))))
        val first = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        val second = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertTrue(first.get("look_id").isJsonNull); assertEquals("ch-2", first.getAsJsonArray("chapters")[0].asString)
        assertEquals("coat", second.get("look_id").asString); assertEquals("ch-3", second.getAsJsonArray("chapters")[0].asString)
        assertEquals(1, changed)
    }

    @Test fun `item 8 - dirty notice from dirty_scenes, none for an empty list`() {
        assertEquals(UiText.Key("hero_dirty_scenes", listOf(2, "image")), HeroCardLogic.dirtyNotice(HeroMutationResult(dirty_scenes = listOf(
            HeroDirtyScene("c1", "s1", dirty_layers = listOf("image")), HeroDirtyScene("c1", "s2", dirty_layers = listOf("image"))))))
        assertEquals(UiText.Key("hero_dirty_scenes", listOf(1, "—")), HeroCardLogic.dirtyNotice(HeroMutationResult(dirty_scenes = listOf(HeroDirtyScene("c1", "s1")))))
        assertNull(HeroCardLogic.dirtyNotice(HeroMutationResult(dirty_scenes = emptyList())))
        assertNull(HeroCardLogic.dirtyNotice(null))
    }

    @Test fun `a new habit - probability clamped, default 0_35, id free in the book`() {
        val r = HeroCardLogic.newRule(HeroFixtures.yura, listOf(HeroFixtures.yura), HeroFixtures.globals, "Looks away", listOf("sad"), "1,7")
        assertEquals(1.0, r.probability!!, 0.0); assertEquals("yura.looks_away", r.id); assertEquals(true, r.enabled)
        assertEquals(0.35, HeroCardLogic.newRule(HeroFixtures.yura, emptyList(), null, "x", listOf("sad"), "abc").probability!!, 0.0)
    }

    @Test fun `fresh data replaces the draft unless the user has pending edits`() {
        val c = controller()
        c.updateHero(HeroFixtures.yura.copy(notes = "server", rev = "r2"))
        assertEquals("server", c.draft.notes)
        c.draft = c.draft.copy(manner = "mine")
        c.updateHero(HeroFixtures.yura.copy(notes = "server2", rev = "r3"))
        assertEquals("mine", c.draft.manner)
        assertEquals(HeroModel.draftFromHero(HeroFixtures.yura).name, c.draft.name)
    }
}
