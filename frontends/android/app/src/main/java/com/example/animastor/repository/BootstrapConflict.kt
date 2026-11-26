package com.example.animastor.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/**
 * Bootstrap idempotency (animastor-docs architecture/BOOTSTRAP_IDEMPOTENCY.md):
 * the server holds one bootstrap run per book and answers a parallel
 * POST /bootstrap | /bootstrap-next-window with
 * `409 { error: "bootstrap_in_progress", session_id? }`. That is not a failure:
 * another run of this book (other device / tab / earlier tap) is alive — the
 * client follows its progress. Web parity: web-generator-vbook
 * isBootstrapInProgressError.
 */
class BootstrapInProgressException(val sessionId: String?) :
    IOException("bootstrap_in_progress")

/** Pure helpers (no Android framework; Gson, not org.json — JVM-tested). */
object BootstrapConflict {
    const val CODE = "bootstrap_in_progress"

    private fun parse(body: String?): JsonObject? = try {
        if (body.isNullOrBlank()) null else JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** `true` for the server's 409 bootstrap_in_progress body (`error` or `code`). */
    fun isBootstrapInProgress(httpCode: Int, errorBody: String?): Boolean {
        if (httpCode != 409) return false
        val o = parse(errorBody) ?: return false
        return o.str("error") == CODE || o.str("code") == CODE
    }

    /** session_id of the running window, when the server named it. */
    fun sessionId(errorBody: String?): String? = parse(errorBody)?.str("session_id")?.ifBlank { null }
}
