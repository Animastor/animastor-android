package com.example.animastor.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sign-in required (web parity: frontends/app api/client.ts setLoginRequiredHandler).
 *
 * The server answers 401 on book data, import and export for callers that are
 * not signed in. Any such 401 outside /api/v1/auth/ notifies [onLoginRequired]
 * (AuthStore.requestLogin → MainActivity opens the login dialog once). The
 * response itself is passed through unchanged.
 */
class LoginRequiredInterceptor(private val onLoginRequired: () -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (isLoginRequired(chain.request().url.encodedPath, response.code)) {
            try { onLoginRequired() } catch (_: Exception) { /* UI hook must never break a request */ }
        }
        return response
    }

    companion object {
        /** Pure — exported for tests. */
        fun isLoginRequired(path: String, code: Int): Boolean =
            code == 401 && !path.startsWith("/api/v1/auth/")
    }
}
