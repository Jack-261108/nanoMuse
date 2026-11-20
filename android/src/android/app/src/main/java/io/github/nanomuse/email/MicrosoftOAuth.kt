package io.github.nanomuse.email

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import io.github.nanomuse.oauth.LoopbackCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * Microsoft identity for Outlook.com and Microsoft 365. The app is a public client: the
 * person can use nanoMuse's default application ID or register an application in the Azure
 * portal, add [REDIRECT_URI] under "Mobile and desktop applications", and paste its
 * Application (client) ID. The flow is PKCE with a loopback redirect on 127.0.0.1, and the
 * tokens feed IMAP/SMTP through XOAUTH2.
 */
object MicrosoftOAuth {
    const val AUTHORIZE = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    const val TOKEN = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    const val REDIRECT_URI = "http://localhost:38765"
    const val PORT = 38765
    val LOOPBACK_TARGET = LoopbackCallback.Target(PORT, "/")
    const val SCOPES =
        "offline_access https://outlook.office.com/IMAP.AccessAsUser.All " +
            "https://outlook.office.com/SMTP.Send openid email"

    /** nanoMuse's public Azure application. A client ID is not a secret. */
    const val DEFAULT_CLIENT_ID = "9e5f94bc-e8a4-4e73-b8be-63364c29d753"

    private const val TIMEOUT_MS = 5 * 60_000L

    data class Result(
        val ok: Boolean,
        val message: String,
        val tokens: EmailTokens? = null,
        val preferredAddress: String? = null,
    )

    internal sealed class CallbackOutcome {
        data class Code(val code: String, val state: String?) : CallbackOutcome()
        data class Error(val error: String, val description: String?) : CallbackOutcome()
        data object Cancelled : CallbackOutcome()
    }

    internal data class Pkce(val verifier: String, val challenge: String, val state: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var active: LoopbackServer? = null

    /** Stops a sign-in that is waiting for the browser, e.g. when the person cancels. */
    fun cancel() {
        active?.stop(externalCancel = true)
        active = null
    }

    /** The authorization page for [clientId]. Pure, so the query can be unit-tested. */
    internal fun authorizeUrl(clientId: String, pkce: Pkce = newPkce()): String = buildString {
        append(AUTHORIZE)
        append("?client_id=").append(enc(clientId))
        append("&response_type=code")
        append("&redirect_uri=").append(enc(REDIRECT_URI))
        append("&response_mode=query")
        append("&scope=").append(enc(SCOPES))
        append("&state=").append(enc(pkce.state))
        append("&code_challenge=").append(enc(pkce.challenge))
        append("&code_challenge_method=S256")
        append("&prompt=select_account")
    }

    suspend fun authorize(context: Context, clientId: String): Result {
        val id = clientId.trim().ifBlank { DEFAULT_CLIENT_ID }
        if (id.isEmpty()) return Result(false, "The Application (client) ID is missing.")

        active?.stop(externalCancel = true)
        active = null

        val pkce = newPkce()
        val url = authorizeUrl(id, pkce)

        val serverSocket = withContext(Dispatchers.IO) {
            try {
                ServerSocket(PORT, 8, InetAddress.getLoopbackAddress())
            } catch (_: BindException) {
                null
            } catch (_: Exception) {
                null
            }
        } ?: return Result(false, "Port $PORT is busy. Close the other sign-in and try again.")

        val outcome = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<CallbackOutcome> { cont ->
                    val server = LoopbackServer(serverSocket) { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                    active = server
                    server.start()
                    cont.invokeOnCancellation {
                        server.stop(externalCancel = false)
                        if (active === server) active = null
                    }
                    if (!openBrowser(context, url)) {
                        server.stop(externalCancel = false)
                        if (active === server) active = null
                        if (cont.isActive) {
                            cont.resume(
                                CallbackOutcome.Error("no_browser", "No browser is available on this device."),
                            )
                        }
                    }
                }
            }
        } finally {
            active?.stop(externalCancel = false)
            active = null
            runCatching { serverSocket.close() }
        }

        return when (outcome) {
            null, CallbackOutcome.Cancelled -> Result(false, "Microsoft sign-in did not complete.")
            is CallbackOutcome.Error -> {
                if (outcome.error == "no_browser") {
                    Result(false, outcome.description ?: "No browser is available on this device.")
                } else {
                    Result(false, "Microsoft sign-in was refused (${outcome.error.take(60)}).")
                }
            }
            is CallbackOutcome.Code -> {
                if (outcome.state != null && outcome.state != pkce.state) {
                    Result(false, "Sign-in state mismatch.")
                } else {
                    withContext(Dispatchers.IO) { exchange(id, outcome.code, pkce.verifier) }
                }
            }
        }
    }

    private fun openBrowser(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        val launchedCustomTab = runCatching {
            val customTabs = CustomTabsIntent.Builder().setShowTitle(true).build()
            if (context !is Activity) {
                customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTabs.launchUrl(context, uri)
            true
        }.getOrDefault(false)
        if (launchedCustomTab) return true

        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * A valid access token for [accountId], refreshing it when it is about to expire.
     * Null means the person has to sign in again.
     */
    suspend fun accessToken(store: EmailStore, accountId: String, clientId: String): String? =
        withContext(Dispatchers.IO) {
            val tokens = store.tokens(accountId) ?: return@withContext null
            val now = System.currentTimeMillis()
            if (tokens.expiresAtMs > 0 && tokens.expiresAtMs > now + 60_000) {
                return@withContext tokens.accessToken
            }
            val refresh = tokens.refreshToken ?: return@withContext tokens.accessToken.takeIf { it.isNotBlank() }
            val id = clientId.trim().ifBlank { DEFAULT_CLIENT_ID }
            if (id.isEmpty()) return@withContext null
            val body = FormBody.Builder()
                .add("client_id", id)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("scope", SCOPES)
                .build()
            runCatching {
                http.newCall(Request.Builder().url(TOKEN).post(body).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val fresh = parseTokens(response.body?.string().orEmpty(), refresh) ?: return@use null
                    store.saveTokens(accountId, fresh)
                    fresh.accessToken
                }
            }.getOrNull()
        }

    private fun exchange(clientId: String, code: String, verifier: String): Result = runCatching {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .add("scope", SCOPES)
            .build()
        http.newCall(Request.Builder().url(TOKEN).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(text).optString("error", "") }.getOrDefault("")
                return Result(false, "Microsoft sign-in was refused (${error.ifBlank { response.code.toString() }}).")
            }
            val tokens = parseTokens(text, null)
                ?: return Result(false, "Microsoft returned no access token.")
            val address = parseIdTokenAddress(text)
            Result(true, "", tokens, preferredAddress = address)
        }
    }.getOrElse { Result(false, "Microsoft sign-in could not reach the token endpoint.") }

    /** Visible for tests: reads a token response; keeps the old refresh token when absent. */
    fun parseTokens(json: String, previousRefresh: String?): EmailTokens? {
        val item = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val access = item.optString("access_token", "")
        if (access.isBlank()) return null
        val refresh = item.optString("refresh_token", "").ifBlank { previousRefresh }
        val expiresIn = item.optLong("expires_in", 0)
        return EmailTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1_000 else 0,
        )
    }

    /** Extracts the account address from an OIDC id_token JWT payload, when present. */
    internal fun parseIdTokenAddress(tokenJson: String): String? {
        val root = runCatching { JSONObject(tokenJson) }.getOrNull() ?: return null
        val idToken = root.optString("id_token", "")
        if (idToken.isBlank()) return null
        val parts = idToken.split('.')
        if (parts.size < 2) return null
        val payload = parts[1]
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val claims = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        val candidate = claims.optString("email", "")
            .ifBlank { claims.optString("preferred_username", "") }
            .trim()
        return candidate.takeIf { it.contains('@') && !it.contains(' ') }
    }

    /** Parses an HTTP request target on the loopback server. Visible for unit tests. */
    internal fun parseCallbackTarget(requestTarget: String): CallbackOutcome? {
        val uri = runCatching { URI("http://127.0.0.1$requestTarget") }.getOrNull() ?: return null
        val path = uri.path ?: ""
        if (path.isNotEmpty() && path != "/" && path != "/callback" && !path.endsWith("/callback")) return null
        val params = uri.rawQuery
            ?.split('&')
            ?.mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val kv = pair.split('=', limit = 2)
                val key = runCatching { URLDecoder.decode(kv[0], "UTF-8") }.getOrDefault(kv[0])
                val value = if (kv.size > 1) {
                    runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1])
                } else {
                    ""
                }
                key to value
            }
            ?.toMap()
            ?: emptyMap()
        val code = params["code"]?.trim().orEmpty()
        if (code.isNotEmpty()) {
            return CallbackOutcome.Code(code, params["state"])
        }
        val error = params["error"]?.trim().orEmpty()
        if (error.isNotEmpty()) {
            return CallbackOutcome.Error(error, params["error_description"])
        }
        return null
    }

    internal class LoopbackServer(
        private val serverSocket: ServerSocket,
        private val onDone: (CallbackOutcome) -> Unit,
    ) {
        @Volatile
        private var running = true

        @Volatile
        private var delivered = false

        val port: Int get() = serverSocket.localPort

        fun start() {
            thread(name = "microsoft-oauth-callback", isDaemon = true) {
                while (running) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    try {
                        socket.soTimeout = 5_000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                        val requestLine = reader.readLine() ?: continue
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                        }
                        val parts = requestLine.split(' ')
                        val outcome = if (parts.size >= 2) parseCallbackTarget(parts[1]) else null
                        if (outcome == null) {
                            val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            socket.getOutputStream().write(notFound.toByteArray(Charsets.US_ASCII))
                            socket.close()
                            continue
                        }
                        val html = when (outcome) {
                            is CallbackOutcome.Code ->
                                "<html><body><h1>Authorization complete</h1><p>You can close this tab and return to nanoMuse.</p><script>window.close()</script></body></html>"
                            else ->
                                "<html><body><h1>Sign-in did not complete</h1><p>You can close this tab and return to nanoMuse.</p></body></html>"
                        }
                        val bodyBytes = html.toByteArray(Charsets.UTF_8)
                        val headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=UTF-8\r\n" +
                            "Content-Length: ${bodyBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().write(bodyBytes)
                        socket.getOutputStream().flush()
                        socket.close()
                        finish(outcome)
                        return@thread
                    } catch (_: Exception) {
                        runCatching { socket.close() }
                    }
                }
            }
        }

        fun stop(externalCancel: Boolean) {
            running = false
            runCatching { serverSocket.close() }
            if (externalCancel) {
                finish(CallbackOutcome.Cancelled)
            }
        }

        @Synchronized
        private fun finish(outcome: CallbackOutcome) {
            if (delivered) return
            delivered = true
            running = false
            runCatching { serverSocket.close() }
            onDone(outcome)
        }
    }

    private fun newPkce(): Pkce {
        val random = SecureRandom()
        val verifier = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { random.nextBytes(it) })
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        val state = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(16).also { random.nextBytes(it) })
        return Pkce(verifier, challenge, state)
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
