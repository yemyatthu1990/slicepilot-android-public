package world.yemyat.slicepilot.cloud.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address the Google flow runs on, and the address the token comes back on, are both built by
 * hand from what the live API accepts: `return_to` and `callback` have to be the same URL, encoded,
 * and `source` has to be the account client id — the server echoes all three back in its OAuth
 * `state`, so a wrong shape is a login that never returns. A provider nobody configured must not
 * produce a callback the app would fail to recognise either, so provider names are tested too.
 */
class CloudSocialLoginTest {
    private val apiBase = "https://matrix.elegoo.com/api/v1/account-center-server"

    @Test
    fun googleRedirectCarriesOurClientIdAndEncodedCallback() {
        assertEquals(
            "https://matrix.elegoo.com/api/v1/account-center-server/fac/api/auth/redirect/google" +
                "?return_to=world.yemyat.slicepilot%3A%2F%2Fsocial%2Fgoogle" +
                "&callback=world.yemyat.slicepilot%3A%2F%2Fsocial%2Fgoogle" +
                "&source=Graft",
            CloudSocialLogin.redirectUrl(apiBase, "Graft", CloudSocialLogin.PROVIDER_GOOGLE),
        )
    }

    @Test
    fun callbackUrlAndProviderAreTwoViewsOfTheSameAddress() {
        val url = CloudSocialLogin.callbackUrl(" GOOGLE ")
        assertEquals("world.yemyat.slicepilot://social/google", url)
        assertEquals("google", CloudSocialLogin.providerOf(url))
        assertEquals("facebook", CloudSocialLogin.providerOf("world.yemyat.slicepilot://social/facebook?token=x"))
    }

    @Test
    fun otherDeepLinksAreNotSocialCallbacks() {
        assertNull(
            "the legacy web handoff is not a social callback",
            CloudSocialLogin.providerOf("world.yemyat.slicepilot://oauth/callback?token=x"),
        )
        assertNull(
            "an https link is not ours",
            CloudSocialLogin.providerOf("https://np-intl-accounts.elegoo.com/google?token=x"),
        )
        assertNull(
            "a provider nobody configured is not a callback we started",
            CloudSocialLogin.providerOf("world.yemyat.slicepilot://social/kakao?token=x"),
        )
    }

    @Test
    fun tokenAndErrorAreReadFromEitherHalfOfTheAnswer() {
        assertEquals("FAC-1", CloudSocialLogin.tokenOf("world.yemyat.slicepilot://social/google?token=FAC-1"))
        assertEquals("FAC-1", CloudSocialLogin.tokenOf("world.yemyat.slicepilot://social/google?x=1&facToken=FAC-1"))
        assertNull(
            "a callback with no token did not log in",
            CloudSocialLogin.tokenOf("world.yemyat.slicepilot://social/google"),
        )
        assertEquals(
            "account suspended",
            CloudSocialLogin.errorOf("world.yemyat.slicepilot://social/google?errorCode=10038&errorDescription=account%20suspended"),
        )
    }

    @Test
    fun onlyTheProvidersElegooAnswersForAreSupported() {
        assertTrue(CloudSocialLogin.isSupported(" Facebook"))
        assertTrue(CloudSocialLogin.isSupported(CloudSocialLogin.PROVIDER_GOOGLE))
        assertFalse("Apple is iOS-only", CloudSocialLogin.isSupported("apple"))
    }
}
