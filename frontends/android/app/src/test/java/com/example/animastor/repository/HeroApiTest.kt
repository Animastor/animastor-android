package com.example.animastor.repository

import com.example.animastor.network.PollRequests
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * The hero API of the app against the step-4 contract table
 * (animastor-docs VBOOK_FORMAT_V4.md §12е) — every route: method, path,
 * query, body. A live run of the Android client against the backend branch
 * needs a device; MockWebServer replaces it (owner, step 6 п.2).
 */
class HeroApiTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: HeroRepository

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient()).build().create(HeroApi::class.java)
        repo = HeroRepository(api)
    }

    @After fun tearDown() { server.shutdown() }

    private fun answer(code: Int = 200, body: String = "{}") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    private fun taken(): RecordedRequest = server.takeRequest()
    private fun json(r: RecordedRequest) = JsonParser.parseString(r.body.readUtf8())
    private val click = ClientAction(kind = "user_click", source = "hero_card.voice_design", action_id = "ua-1", trusted = true)

    @Test fun `POST heroes - 201 hero_id is a success (item 10), only the given keys are sent`() = runBlocking {
        answer(201, """{"hero_id":"ivan","hero":{"id":"ivan"},"rev":"r0"}""")
        val res = repo.createHero("b1", linkedMapOf("name" to "Иван", "passport" to emptyMap<String, Any?>()))
        assertEquals("ivan", res.hero_id)
        val r = taken()
        assertEquals("POST", r.method); assertEquals("/api/v1/book/b1/heroes", r.path)
        assertEquals(JsonParser.parseString("""{"name":"Иван","passport":{}}"""), json(r))
    }

    @Test fun `PATCH hero - fields with nulls kept and if_rev, dirty_scenes read`() = runBlocking {
        answer(body = """{"hero":{"id":"yura"},"rev":"r2","dirty_scenes":[{"chapter_id":"c1","scene_id":"s1","dirty_layers":["image"]}]}""")
        val res = repo.patchHero("b1", "yura", linkedMapOf("passport" to null, "voice.manner" to "Fast."), "r1")
        assertEquals(1, res.dirty_scenes!!.size)
        val r = taken()
        assertEquals("PATCH", r.method); assertEquals("/api/v1/book/b1/heroes/yura", r.path)
        assertEquals(JsonParser.parseString("""{"fields":{"passport":null,"voice.manner":"Fast."},"if_rev":"r1"}"""), json(r))
    }

    @Test fun `item 7 - an error body {error, message} shows the message, the code drives branches`() = runBlocking {
        answer(409, """{"error":"rev_conflict","message":"The hero changed","rev":"r9"}""")
        try { repo.patchHero("b1", "yura", mapOf("name" to "x"), "r1"); fail() } catch (e: HeroApiError) {
            assertEquals(409, e.status); assertEquals("rev_conflict", e.error); assertEquals("rev_conflict", e.code); assertEquals("The hero changed", e.message)
        }
        taken()
        answer(404, """{"error":"Book not found"}""")
        try { repo.deleteHero("b1", "x"); fail() } catch (e: HeroApiError) {
            assertEquals("Book not found", e.message); assertNull(e.code)
        }
        taken()
        Unit
    }

    @Test fun `item 6 - DELETE heroes broken=1 by id, DELETE hero-files by the plain name`() = runBlocking {
        answer(); repo.deleteHero("b1", "anna", broken = true)
        taken().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/book/b1/heroes/anna?broken=1", it.path) }
        answer(); repo.deleteHero("b1", "anna")
        assertEquals("/api/v1/book/b1/heroes/anna", taken().path)
        answer(body = """{"deleted":true,"file":"Юра.json"}"""); repo.deleteBrokenHeroFile("b1", "Юра.json")
        taken().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/book/b1/hero-files?file=%D0%AE%D1%80%D0%B0.json", it.path) }
    }

    @Test fun `rules - POST rules, PATCH rules fields, DELETE rules`() = runBlocking {
        answer(201, """{"rule_id":"yura.x"}""")
        repo.createRule("b1", "yura", HeroRule(id = "yura.x", whenCond = RuleWhen(emotion = listOf("sad")), thenDo = RuleThen("sighs"), probability = 0.35, enabled = true))
        taken().let {
            assertEquals("/api/v1/book/b1/heroes/yura/rules", it.path)
            assertEquals(JsonParser.parseString("""{"id":"yura.x","when":{"emotion":["sad"]},"then":{"action":"sighs"},"probability":0.35,"enabled":true}"""), json(it))
        }
        answer(); repo.patchRule("b1", "yura", "yura.x", mapOf("enabled" to false))
        taken().let { assertEquals("PATCH", it.method); assertEquals("/api/v1/book/b1/heroes/yura/rules/yura.x", it.path); assertEquals(JsonParser.parseString("""{"fields":{"enabled":false}}"""), json(it)) }
        answer(); repo.deleteRule("b1", "yura", "yura.x")
        taken().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/book/b1/heroes/yura/rules/yura.x", it.path) }
    }

    @Test fun `looks - POST looks (201), PATCH looks fields null removes, DELETE looks, POST looks assign`() = runBlocking {
        answer(201, """{"look_id":"coat","dirty_scenes":[]}""")
        assertEquals("coat", repo.createLook("b1", "yura", null, "В пальто", LookFields(clothes = "coat"), null).look_id)
        taken().let { assertEquals("/api/v1/book/b1/heroes/yura/looks", it.path); assertEquals(JsonParser.parseString("""{"label":"В пальто","fields":{"clothes":"coat"}}"""), json(it)) }
        answer(); repo.patchLook("b1", "yura", "coat", linkedMapOf("fields.clothes" to null))
        taken().let { assertEquals("PATCH", it.method); assertEquals("/api/v1/book/b1/heroes/yura/looks/coat", it.path); assertEquals(JsonParser.parseString("""{"fields":{"fields.clothes":null}}"""), json(it)) }
        answer(); repo.deleteLook("b1", "yura", "coat")
        taken().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/book/b1/heroes/yura/looks/coat", it.path) }
        answer(); repo.assignLook("b1", "yura", null, LookAssignment(listOf("ch-1"), listOf(HeroSceneRef("ch-2", "sc-1"))))
        taken().let {
            assertEquals("POST", it.method); assertEquals("/api/v1/book/b1/looks/assign", it.path)
            assertEquals(JsonParser.parseString("""{"hero_id":"yura","look_id":null,"chapters":["ch-1"],"scenes":[{"chapter_id":"ch-2","scene_id":"sc-1"}]}"""), json(it))
        }
    }

    @Test fun `voice - GET candidates is a no-store poll path, statuses parsed`() = runBlocking {
        answer(body = """{"candidates":[{"name":"c1.wav","status":"pending","pinned":false},{"name":"c2.wav","status":"ready","verification":"passed"}],"pending":true,"reference_outdated":true}""")
        val res = repo.listVoiceCandidates("b1", "yura")
        assertEquals(true, res.pending); assertEquals(true, res.reference_outdated); assertEquals(listOf("pending", "ready"), res.candidates!!.map { it.status })
        val r = taken()
        assertEquals("GET", r.method); assertEquals("/api/v1/book/b1/heroes/yura/voice/candidates", r.path)
        assertTrue(PollRequests.isNoStorePath(r.path!!))
    }

    @Test fun `voice - design carries count and client_action user_click`() = runBlocking {
        answer(); repo.designVoiceCandidates("b1", "yura", click)
        taken().let {
            assertEquals("/api/v1/book/b1/heroes/yura/voice/candidates/design", it.path)
            assertEquals(JsonParser.parseString("""{"count":3,"client_action":{"kind":"user_click","source":"hero_card.voice_design","action_id":"ua-1","trusted":true}}"""), json(it))
        }
    }

    @Test fun `item 2 - upload is multipart file, ref_text and client_action as a JSON string`() = runBlocking {
        answer(); repo.uploadVoiceCandidate("b1", "yura", "me.wav", "audio/wav", byteArrayOf(1, 2, 3), " Привет. ", click.copy(source = "hero_card.voice_upload"))
        val r = taken()
        assertEquals("/api/v1/book/b1/heroes/yura/voice/candidates/upload", r.path)
        assertTrue(r.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = r.body.readUtf8()
        assertTrue(body.contains("name=\"file\"; filename=\"me.wav\""))
        assertTrue(body.contains("name=\"ref_text\"") && body.contains("Привет."))
        assertTrue(body.contains("name=\"client_action\""))
        assertTrue(body.contains("""{"kind":"user_click","source":"hero_card.voice_upload","action_id":"ua-1","trusted":true}"""))
    }

    @Test fun `voice - delete candidate, pin with locked true (and ref_text), unpin`() = runBlocking {
        answer(); repo.deleteVoiceCandidate("b1", "yura", "c1.wav")
        taken().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/book/b1/heroes/yura/voice/candidates/c1.wav", it.path) }
        answer(); repo.pinVoiceReference("b1", "yura", "c2.wav")
        taken().let { assertEquals("/api/v1/book/b1/heroes/yura/voice/reference", it.path); assertEquals(JsonParser.parseString("""{"name":"c2.wav","locked":true}"""), json(it)) }
        answer(); repo.pinVoiceReference("b1", "yura", "c3.wav", "Мой текст.")
        assertEquals(JsonParser.parseString("""{"name":"c3.wav","locked":true,"ref_text":"Мой текст."}"""), json(taken()))
        answer(); repo.clearVoiceReference("b1", "yura")
        taken().let { assertEquals("DELETE", it.method); assertEquals("/api/v1/book/b1/heroes/yura/voice/reference", it.path) }
    }

    @Test fun `the hero API knows none of the removed 3_1 routes`() {
        val src = java.io.File("src/main/java/com/example/animastor/repository/BackendApi.kt").readText() +
            java.io.File("src/main/java/com/example/animastor/repository/HeroApi.kt").readText()
        for (gone in listOf("/characters", "/voices", "/behaviors", "voices/references")) {
            assertTrue(gone, !src.contains("\"/api/v1/book/{bookId}$gone"))
        }
    }
}
