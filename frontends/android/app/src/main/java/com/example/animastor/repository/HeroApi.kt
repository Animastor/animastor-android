package com.example.animastor.repository

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

// ======================================================
// Hero API — the write side of the hero card
// ======================================================
// animastor-docs VBOOK_FORMAT_V4.md §12е (step 4 contract), HERO_CARD.md §6.
// Web twin: frontends/app/src/features/heroes/heroApi.ts — the same routes and
// bodies (HeroApiTest checks every row against MockWebServer).
//
//  - Every call returns the raw Response: 201 is a success like 200; an error
//    body {error: <code>, message} becomes [HeroApiError] (message shown,
//    code for branches — rev_conflict etc.).
//  - Bodies are serialized with nulls kept: `passport: null`, `voice: null`,
//    `fields.clothes: null` and `look_id: null` are meaningful (the app-wide
//    Gson drops nulls).
//  - Generation commands (design, upload) carry `client_action` built from a
//    real click (GeneratorCommands.userAction) — never synthesized.
//  - After a successful mutation the caller re-reads GET /book; this layer
//    never patches local state.

interface HeroApi {
    @POST("/api/v1/book/{bookId}/heroes")
    suspend fun createHero(@Path("bookId") bookId: String, @Body body: RequestBody): Response<ResponseBody>

    @PATCH("/api/v1/book/{bookId}/heroes/{heroId}")
    suspend fun patchHero(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Body body: RequestBody): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "/api/v1/book/{bookId}/heroes/{heroId}", hasBody = false)
    suspend fun deleteHero(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Query("broken") broken: Int?): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "/api/v1/book/{bookId}/hero-files", hasBody = false)
    suspend fun deleteBrokenHeroFile(@Path("bookId") bookId: String, @Query("file") file: String): Response<ResponseBody>

    @POST("/api/v1/book/{bookId}/heroes/{heroId}/rules")
    suspend fun createRule(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Body body: RequestBody): Response<ResponseBody>

    @PATCH("/api/v1/book/{bookId}/heroes/{heroId}/rules/{ruleId}")
    suspend fun patchRule(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Path("ruleId") ruleId: String, @Body body: RequestBody): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "/api/v1/book/{bookId}/heroes/{heroId}/rules/{ruleId}", hasBody = false)
    suspend fun deleteRule(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Path("ruleId") ruleId: String): Response<ResponseBody>

    @POST("/api/v1/book/{bookId}/heroes/{heroId}/looks")
    suspend fun createLook(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Body body: RequestBody): Response<ResponseBody>

    @PATCH("/api/v1/book/{bookId}/heroes/{heroId}/looks/{lookId}")
    suspend fun patchLook(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Path("lookId") lookId: String, @Body body: RequestBody): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "/api/v1/book/{bookId}/heroes/{heroId}/looks/{lookId}", hasBody = false)
    suspend fun deleteLook(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Path("lookId") lookId: String): Response<ResponseBody>

    @POST("/api/v1/book/{bookId}/looks/assign")
    suspend fun assignLook(@Path("bookId") bookId: String, @Body body: RequestBody): Response<ResponseBody>

    @GET("/api/v1/book/{bookId}/heroes/{heroId}/voice/candidates")
    suspend fun listVoiceCandidates(@Path("bookId") bookId: String, @Path("heroId") heroId: String): Response<ResponseBody>

    @POST("/api/v1/book/{bookId}/heroes/{heroId}/voice/candidates/design")
    suspend fun designVoiceCandidates(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Body body: RequestBody): Response<ResponseBody>

    @Multipart
    @POST("/api/v1/book/{bookId}/heroes/{heroId}/voice/candidates/upload")
    suspend fun uploadVoiceCandidate(
        @Path("bookId") bookId: String,
        @Path("heroId") heroId: String,
        @Part file: MultipartBody.Part,
        @Part refText: MultipartBody.Part?,
        @Part clientAction: MultipartBody.Part,
    ): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "/api/v1/book/{bookId}/heroes/{heroId}/voice/candidates/{name}", hasBody = false)
    suspend fun deleteVoiceCandidate(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Path("name") name: String): Response<ResponseBody>

    @POST("/api/v1/book/{bookId}/heroes/{heroId}/voice/reference")
    suspend fun pinVoiceReference(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Body body: RequestBody): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "/api/v1/book/{bookId}/heroes/{heroId}/voice/reference", hasBody = false)
    suspend fun clearVoiceReference(@Path("bookId") bookId: String, @Path("heroId") heroId: String): Response<ResponseBody>

    /** Image references — backend step 7; until then 404 and the card degrades the block. */
    @Multipart
    @POST("/api/v1/book/{bookId}/heroes/{heroId}/image-refs")
    suspend fun uploadImageRef(
        @Path("bookId") bookId: String,
        @Path("heroId") heroId: String,
        @Part file: MultipartBody.Part,
        @Part kind: MultipartBody.Part,
    ): Response<ResponseBody>

    @Streaming
    @GET("/api/v1/book/{bookId}/heroes/{heroId}/voice/file/{name}")
    suspend fun voiceFile(@Path("bookId") bookId: String, @Path("heroId") heroId: String, @Path("name") name: String): Response<ResponseBody>
}

/** A failed hero call: [message] is shown; [error] / [code] pick a branch (rev_conflict …). */
class HeroApiError(
    val status: Int,
    message: String,
    /** `error` of the body (a machine code in VBook 4 bodies). */
    val error: String?,
    /** errorBodyCode of the web client: `code`, else `error` when `message` carries the text. */
    val code: String?,
    val body: JsonObject?,
) : Exception(message)

/** One candidate of GET …/voice/candidates (VBOOK_FORMAT_V4.md §12е). */
data class VoiceCandidate(
    /** File name `<candidate_id>.wav` — the candidate's address. */
    val name: String = "",
    val candidate_id: String? = null,
    /** pending | ready | failed */
    val status: String? = null,
    val source: String? = null,
    val file: String? = null,
    val sha256: String? = null,
    val ref_text: String? = null,
    val ref_text_source: String? = null,
    val model: String? = null,
    val seed: Long? = null,
    val verification: String? = null,
    val verification_problems: List<String>? = null,
    val duration_ms: Long? = null,
    val pinned: Boolean? = null,
    val error: String? = null,
)

data class VoiceCandidatesResponse(
    val candidates: List<VoiceCandidate>? = null,
    /** A design / upload command is running — the card re-reads the list every 3 s. */
    val pending: Boolean? = null,
    val reference_status: String? = null,
    /** The pinned reference was made from another timbre (designed only). */
    val reference_outdated: Boolean? = null,
)

/** 200 of a hero / look mutation — the scenes whose image / video became stale. */
data class HeroMutationResult(
    val hero_id: String? = null,
    val look_id: String? = null,
    val rev: String? = null,
    val dirty_scenes: List<HeroDirtyScene>? = null,
)

class HeroRepository(private val api: HeroApi) {
    private val json = "application/json; charset=utf-8".toMediaType()

    companion object {
        /** Nulls are part of the contract here (block removal, field clearing, release). */
        val heroGson: Gson = GsonBuilder().serializeNulls().create()
        private val plainGson = Gson()

        /** {error, message} → the text to show (message first, else error). */
        fun errorBodyMessage(body: JsonObject?): String {
            val message = body?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            if (!message.isNullOrBlank()) return message
            return body?.get("error")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        }

        /** The machine code of an error body: `code`, else `error` when a `message` carries the text. */
        fun errorBodyCode(body: JsonObject?): String? {
            val code = body?.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (code != null) return code
            val error = body?.get("error")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val hasMessage = body?.get("message")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true
            return if (error != null && hasMessage && Regex("^[a-z0-9_]+$").matches(error)) error else null
        }

        fun parseObject(text: String?): JsonObject? = runCatching {
            if (text.isNullOrBlank()) null else JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()

        fun toError(status: Int, raw: String?): HeroApiError {
            val body = parseObject(raw)
            val error = body?.get("error")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val message = errorBodyMessage(body).ifEmpty { raw?.takeIf { it.isNotBlank() && body == null }?.take(200) ?: "HTTP $status" }
            return HeroApiError(status, message, error, errorBodyCode(body), body)
        }

        /** `client_action` of a generation command, from a real click. */
        fun clientActionJson(action: ClientAction): String = plainGson.toJson(action)
    }

    private fun body(value: Any): RequestBody = heroGson.toJson(value).toRequestBody(json)

    /** 2xx (incl. 201) → the parsed body; else [HeroApiError]. */
    private suspend fun call(block: suspend () -> Response<ResponseBody>): JsonObject {
        val res = block()
        if (res.isSuccessful) return parseObject(res.body()?.string()) ?: JsonObject()
        throw toError(res.code(), res.errorBody()?.string())
    }

    private fun mutation(o: JsonObject): HeroMutationResult = plainGson.fromJson(o, HeroMutationResult::class.java)

    // ── Hero ─────────────────────────────────────────────────────────────

    /** 201 `{hero_id, hero, rev}`. Body: id?, name?, role?, passport?, voice? (only what is given). */
    suspend fun createHero(bookId: String, body: Map<String, Any?>): HeroMutationResult =
        mutation(call { api.createHero(bookId, body(body)) })

    /** `ifRev` — optimistic lock: 409 rev_conflict when the hero changed elsewhere. */
    suspend fun patchHero(bookId: String, heroId: String, fields: Map<String, Any?>, ifRev: String?): HeroMutationResult {
        val payload = LinkedHashMap<String, Any?>().apply { put("fields", fields); if (!ifRev.isNullOrEmpty()) put("if_rev", ifRev) }
        return mutation(call { api.patchHero(bookId, heroId, body(payload)) })
    }

    suspend fun deleteHero(bookId: String, heroId: String, broken: Boolean = false): HeroMutationResult =
        mutation(call { api.deleteHero(bookId, heroId, if (broken) 1 else null) })

    /** A broken file whose name is not a hero id — the plain name, without "characters/" (В14). */
    suspend fun deleteBrokenHeroFile(bookId: String, file: String): JsonObject = call { api.deleteBrokenHeroFile(bookId, file) }

    // ── Rules ────────────────────────────────────────────────────────────

    /** A rule goes WITHOUT null keys (web sends only the given ones; the validator refuses null strength / unit_type). */
    suspend fun createRule(bookId: String, heroId: String, rule: HeroRule): JsonObject =
        call { api.createRule(bookId, heroId, plainGson.toJson(rule).toRequestBody(json)) }

    suspend fun patchRule(bookId: String, heroId: String, ruleId: String, fields: Map<String, Any?>): JsonObject =
        call { api.patchRule(bookId, heroId, ruleId, body(mapOf("fields" to fields))) }

    suspend fun deleteRule(bookId: String, heroId: String, ruleId: String): JsonObject = call { api.deleteRule(bookId, heroId, ruleId) }

    // ── Looks ────────────────────────────────────────────────────────────

    suspend fun createLook(bookId: String, heroId: String, id: String?, label: String, fields: LookFields, notes: String?): HeroMutationResult {
        val payload = LinkedHashMap<String, Any?>()
        if (!id.isNullOrBlank()) payload["id"] = id.trim()
        payload["label"] = label
        payload["fields"] = LinkedHashMap<String, Any>().apply {
            fields.appearance?.let { put("appearance", it) }
            fields.clothes?.let { put("clothes", it) }
            fields.video_tokens?.let { put("video_tokens", it) }
        }
        if (!notes.isNullOrBlank()) payload["notes"] = notes
        return mutation(call { api.createLook(bookId, heroId, body(payload)) })
    }

    suspend fun patchLook(bookId: String, heroId: String, lookId: String, fields: Map<String, Any?>): HeroMutationResult =
        mutation(call { api.patchLook(bookId, heroId, lookId, body(mapOf("fields" to fields))) })

    /** Deleting a look releases every chapter / scene that referenced it. */
    suspend fun deleteLook(bookId: String, heroId: String, lookId: String): HeroMutationResult =
        mutation(call { api.deleteLook(bookId, heroId, lookId) })

    /** lookId: a look id, "base" (scenes only) or null (release). */
    suspend fun assignLook(bookId: String, heroId: String, lookId: String?, places: LookAssignment): HeroMutationResult {
        val payload = linkedMapOf<String, Any?>("hero_id" to heroId, "look_id" to lookId, "chapters" to places.chapters, "scenes" to places.scenes)
        return mutation(call { api.assignLook(bookId, body(payload)) })
    }

    // ── Voice reference ──────────────────────────────────────────────────

    /** READ only — opening a card calls nothing else (HERO_CARD.md §6). no-store: PollRequests covers this path. */
    suspend fun listVoiceCandidates(bookId: String, heroId: String): VoiceCandidatesResponse =
        plainGson.fromJson(call { api.listVoiceCandidates(bookId, heroId) }, VoiceCandidatesResponse::class.java)

    /** EXPLICIT generation command: design `count` candidates from voice.timbre (a click only). */
    suspend fun designVoiceCandidates(bookId: String, heroId: String, action: ClientAction, count: Int = 3): JsonObject =
        call { api.designVoiceCandidates(bookId, heroId, body(linkedMapOf("count" to count, "client_action" to action))) }

    /**
     * EXPLICIT command too (GPU transcription): multipart `file`, `ref_text?`,
     * `client_action` as a JSON string — without it 400 no_user_action.
     */
    suspend fun uploadVoiceCandidate(bookId: String, heroId: String, fileName: String, mime: String, bytes: ByteArray, refText: String, action: ClientAction): JsonObject {
        val filePart = MultipartBody.Part.createFormData("file", fileName, bytes.toRequestBody(mime.toMediaType()))
        val textPart = refText.trim().takeIf { it.isNotEmpty() }?.let { MultipartBody.Part.createFormData("ref_text", it) }
        val actionPart = MultipartBody.Part.createFormData("client_action", clientActionJson(action))
        return call { api.uploadVoiceCandidate(bookId, heroId, filePart, textPart, actionPart) }
    }

    /** The pinned one — 409 candidate_locked, a pending one — 409 candidate_pending. */
    suspend fun deleteVoiceCandidate(bookId: String, heroId: String, name: String): JsonObject =
        call { api.deleteVoiceCandidate(bookId, heroId, name) }

    /** Pin as voice.reference with locked: true; `refText` — a corrected text of an uploaded sample. */
    suspend fun pinVoiceReference(bookId: String, heroId: String, name: String, refText: String? = null): JsonObject {
        val payload = linkedMapOf<String, Any?>("name" to name, "locked" to true)
        if (refText != null) payload["ref_text"] = refText
        return call { api.pinVoiceReference(bookId, heroId, body(payload)) }
    }

    suspend fun clearVoiceReference(bookId: String, heroId: String): JsonObject = call { api.clearVoiceReference(bookId, heroId) }

    suspend fun uploadImageRef(bookId: String, heroId: String, fileName: String, mime: String, bytes: ByteArray, kind: String): JsonObject {
        val filePart = MultipartBody.Part.createFormData("file", fileName, bytes.toRequestBody(mime.toMediaType()))
        return call { api.uploadImageRef(bookId, heroId, filePart, MultipartBody.Part.createFormData("kind", kind)) }
    }

    /** The audio of a candidate / the pinned reference (auth cookie goes with the shared client). */
    suspend fun voiceFile(bookId: String, heroId: String, name: String): ByteArray {
        val res = api.voiceFile(bookId, heroId, name)
        if (!res.isSuccessful) throw toError(res.code(), res.errorBody()?.string())
        return res.body()?.bytes() ?: ByteArray(0)
    }
}

/** File name of a reference path ("voices/yura/ref_02.wav" → "ref_02.wav"). */
fun refFileName(file: String): String = file.substringAfterLast('/')
