package world.yemyat.slicepilot.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BambuSocialLoginTest {
    @Test
    fun `parses Bambu studio ticket callback`() {
        val result =
            BambuSocialLogin.parseCallbackPath(
                "/?ticket=AM2PQ2&redirect_url=https%3A%2F%2Fbambulab.com%2Fen%2Fsign-in%2Fstudio-callback",
            )
        assertEquals(BambuSocialLogin.CallbackResult.Ticket("AM2PQ2"), result)
    }

    @Test
    fun `parses legacy access token callback`() {
        val result =
            BambuSocialLogin.parseCallbackPath(
                "/?access_token=AQB123&refresh_token=AQB456&redirect_url=https%3A%2F%2Fbambulab.com",
            )
        assertEquals(BambuSocialLogin.CallbackResult.DirectToken("AQB123", "AQB456"), result)
    }

    @Test
    fun `parses error callback`() {
        val result =
            BambuSocialLogin.parseCallbackPath(
                "/?error=access_denied&error_description=User+cancelled+sign-in",
            )
        assertEquals(BambuSocialLogin.CallbackResult.Error("User cancelled sign-in"), result)
    }

    @Test
    fun `ignores favicon and empty probes`() {
        assertNull(BambuSocialLogin.parseCallbackPath("/favicon.ico"))
        assertNull(BambuSocialLogin.parseCallbackPath("/favicon.ico?ticket=should_ignore"))
        assertNull(BambuSocialLogin.parseCallbackPath("/"))
    }

    @Test
    fun `rewrites loopback port in both plain and percent-encoded URLs`() {
        val raw =
            "https://bambulab.com/api/sign-in/google?redirect=http%3A%2F%2Flocalhost%3A13618%2F&alt=http://127.0.0.1:13618/"
        val rewritten = BambuSocialLogin.rewriteLoopbackUrl(raw, 49152)
        assertEquals(
            "https://bambulab.com/api/sign-in/google?redirect=http%3A%2F%2Flocalhost%3A49152%2F&alt=http://127.0.0.1:49152/",
            rewritten,
        )
        assertEquals(raw, BambuSocialLogin.rewriteLoopbackUrl(raw, BambuSocialLogin.DEFAULT_LOOPBACK_PORT))
    }

    @Test
    fun `recognizes Bambu app callback deep link without matching Elegoo links`() {
        assertTrue(BambuSocialLogin.isAppCallback("world.yemyat.slicepilot://bambu/callback?status=ok"))
        assertFalse(BambuSocialLogin.isAppCallback("world.yemyat.slicepilot://social/google?token=abc"))
        assertFalse(BambuSocialLogin.isAppCallback("https://bambulab.com/en/sign-in"))
    }

    @Test
    fun `builds direct Google thirdparty sign-in URL matching Bambu Studio`() {
        val url = BambuSocialLogin.thirdPartySignInUrl("http://localhost:13618")
        assertTrue(url.startsWith("https://bambulab.com/api/v1/user-service/user/tplogin?source=google&from=slicer&callback="))
        assertTrue(url.contains("slicerLoginType%3Dticket"))
        assertTrue(url.contains("redirect_url%3Dhttp%253A%252F%252Flocalhost%253A13618"))
        assertTrue(url.contains("&consentBody="))
    }

    @Test
    fun `loopback server ignores favicon then captures ticket and redirects to app`() {
        val latch = CountDownLatch(1)
        val captured = AtomicReference<BambuSocialLogin.CallbackResult?>()
        BambuLoopbackServer(preferredPort = 0) { res ->
            captured.set(res)
            latch.countDown()
        }.use { server ->
            val favConn = URL("${server.baseUrl}/favicon.ico").openConnection() as HttpURLConnection
            favConn.instanceFollowRedirects = false
            assertEquals(204, favConn.responseCode)
            favConn.disconnect()

            val cbConn =
                URL("${server.baseUrl}/?ticket=TKT999&redirect_url=https%3A%2F%2Fbambulab.com")
                    .openConnection() as HttpURLConnection
            cbConn.instanceFollowRedirects = false
            assertEquals(302, cbConn.responseCode)
            assertEquals("world.yemyat.slicepilot://bambu/callback?status=ok", cbConn.getHeaderField("Location"))
            cbConn.disconnect()

            assertTrue("Expected ticket callback within 2s", latch.await(2, TimeUnit.SECONDS))
            assertEquals(BambuSocialLogin.CallbackResult.Ticket("TKT999"), captured.get())
        }
    }
}
