package io.github.nanomuse.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MicrosoftOAuthTest {
    @Test
    fun `authorize url carries the public client, pkce and mail scopes`() {
        val url = MicrosoftOAuth.authorizeUrl("client-123")
        assertTrue(url.startsWith(MicrosoftOAuth.AUTHORIZE))
        assertTrue(url.contains("client_id=client-123"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A38765"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("scope=offline_access%20https%3A%2F%2Foutlook.office.com%2FIMAP.AccessAsUser.All"))
        assertTrue(url.contains("state="))
        assertTrue(url.contains("prompt=select_account"))
    }

    @Test
    fun `the built-in client id is used by default`() {
        assertTrue(MicrosoftOAuth.DEFAULT_CLIENT_ID.isNotBlank())
        assertTrue(
            MicrosoftOAuth.authorizeUrl(MicrosoftOAuth.DEFAULT_CLIENT_ID)
                .contains("client_id=${MicrosoftOAuth.DEFAULT_CLIENT_ID}"),
        )
    }

    @Test
    fun `token response keeps the previous refresh token and extracts id token email`() {
        val first = MicrosoftOAuth.parseTokens(
            """{"access_token":"a1","refresh_token":"r1","expires_in":3600}""",
            null,
        )!!
        assertEquals("a1", first.accessToken)
        assertEquals("r1", first.refreshToken)
        assertTrue(first.expiresAtMs > System.currentTimeMillis())

        val refreshed = MicrosoftOAuth.parseTokens("""{"access_token":"a2","expires_in":3600}""", "r1")!!
        assertEquals("a2", refreshed.accessToken)
        assertEquals("r1", refreshed.refreshToken)

        assertNull(MicrosoftOAuth.parseTokens("""{"error":"invalid_grant"}""", "r1"))

        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"preferred_username":"user@outlook.com"}""".toByteArray())
        assertEquals(
            "user@outlook.com",
            MicrosoftOAuth.parseIdTokenAddress("""{"access_token":"a1","id_token":"$header.$payload."}"""),
        )
    }

    @Test(timeout = 5_000)
    fun `loopback server finishes immediately on oauth error and ignores favicon`() {
        val latch = CountDownLatch(1)
        val received = AtomicReference<MicrosoftOAuth.CallbackOutcome?>()
        val socket = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val server = MicrosoftOAuth.LoopbackServer(socket) { outcome ->
            received.set(outcome)
            latch.countDown()
        }
        server.start()
        val port = server.port

        val fav = URL("http://127.0.0.1:$port/favicon.ico").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        assertEquals(404, fav.responseCode)
        fav.disconnect()

        val err = URL("http://127.0.0.1:$port/callback?error=access_denied&error_description=Cancelled")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        assertEquals(200, err.responseCode)
        err.disconnect()

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val outcome = received.get() as MicrosoftOAuth.CallbackOutcome.Error
        assertEquals("access_denied", outcome.error)
    }
}
