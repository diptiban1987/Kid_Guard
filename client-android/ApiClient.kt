package com.anonchat.kidguard.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Central HTTP client for the KidGuard child agent.
 *
 * Two deliberate choices fix the "sometimes fails to fetch" behavior seen with
 * the installed build:
 *
 * 1. BASE_URL ends with /api/v1 so we hit the versioned blueprint routes
 *    directly instead of relying on the server's 308 legacy-compat redirect.
 *    Some Android HTTP stacks drop the request body or the Authorization
 *    header when re-issuing a request across a 308, which made POST reports
 *    intermittently fail while GETs looked fine.
 *
 * 2. [AuthInterceptor] attaches the current access token on every request and,
 *    on a single 401, refreshes the token *synchronously* and retries once.
 *    Without that, the first expired-token response surfaced to callers as an
 *    empty/failed fetch until the next scheduled sync.
 */
object ApiClient {

    /** Set once at app start (e.g. from BuildConfig or your pairing flow). */
    @Volatile
    var baseUrl: String = "https://diptiban2021.pythonanywhere.com/api/v1"
        set(value) {
            field = value.trimEnd('/')
        }

    @Volatile
    var tokenStore: TokenStore? = null

    @Volatile
    var deviceIdProvider: (() -> String)? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Explicit timeouts: PythonAnywhere free tier can be slow to answer.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor())
            .build()
    }

    fun client(): OkHttpClient = client

    fun url(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return baseUrl + p
    }

    /** Minimal token holder — back this with EncryptedSharedPreferences. */
    interface TokenStore {
        fun accessToken(): String?
        fun refreshToken(): String?
        fun update(access: String, refresh: String?)
        fun clear()
    }

    /**
     * Adds Authorization and, once per request, refreshes on 401 and retries.
     * Never follows a redirect for an authenticated call: our client builds the
     * final /api/v1 URL itself, so a 30x means something is misconfigured —
     * fail fast instead of silently re-issuing without a body.
     */
    private class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val store = tokenStore
            val original = chain.request()
            val req = original.newBuilder().apply {
                store?.accessToken()?.let { header("Authorization", "Bearer $it") }
                deviceIdProvider?.invoke()?.let { header("X-Device-ID", it) }
            }.build()

            var response = chain.proceed(req)
            if (response.code != 401 || store == null) return response

            // One refresh attempt. synchronized so parallel 401s don't stampede.
            synchronized(this) {
                // Someone else may have refreshed while we waited.
                if (store.accessToken().isNullOrBlank()) return response
                val refreshed = refreshToken(store)
                if (!refreshed) {
                    store.clear()
                    return response
                }
                response.close()
                val retried = original.newBuilder()
                    .header("Authorization", "Bearer ${store.accessToken()}")
                    .build()
                response = chain.proceed(retried)
            }
            return response
        }

        private fun refreshToken(store: TokenStore): Boolean {
            val refresh = store.refreshToken() ?: return false
            val body = okhttp3.RequestBody.create(
                okhttp3.MediaType.get("application/json"), "{}"
            )
            val req = okhttp3.Request.Builder()
                .url(url("/auth/refresh"))
                .header("Authorization", "Bearer $refresh")
                .post(body)
                .build()
            return try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return false
                    val json = org.json.JSONObject(resp.body()?.string() ?: return false)
                    val access = json.optString("token")
                    if (access.isBlank()) return false
                    store.update(access, json.optString("refresh_token").ifBlank { null })
                    true
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
