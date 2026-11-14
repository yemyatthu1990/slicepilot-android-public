package world.yemyat.slicepilot.cloud.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The account API hands a session back in more than one shape — the `{"code":0,"data":{…}}` envelope,
 * the social answer nested one level deeper under `loginData`, `accessToken` where the older code
 * read `token`, `accessTokenExpiresin` misspelled — and every one of them has to land in the same
 * fields, because the app treats a password login, a Google login and a token refresh as one
 * session. This parser is the only place that knowledge lives, so it is tested instead of trusted.
 */
class CloudLoginDataTest {
    @Test
    fun loginEnvelopeIsUnwrapped() {
        val data =
            requireNotNull(
                CloudLoginData.from(
                    JSONObject(
                        """
                        {"code":0,"data":{"accessToken":"AT","refreshToken":"RT",
                        "accessTokenExpireTime":1893456000000,"nickname":"Yemyat","email":"y@x.com"}}
                        
                        """.trimIndent(),
                    ),
                ),
            )
        assertEquals("AT", data.token)
        assertEquals("RT", data.refreshToken)
        assertEquals(1893456000000L, data.accessTokenExpireTime)
        assertEquals("Yemyat", data.nickname)
        assertEquals("y@x.com", data.email)
        assertNull("a complete profile has nothing to finish", data.setupNote)
    }

    @Test
    fun socialAnswerIsReadFromTheNestedLoginData() {
        val nested =
            requireNotNull(
                CloudLoginData.from(JSONObject("""{"code":0,"data":{"loginData":{"token":"AT","refreshToken":"RT"}}}""")),
            )
        assertEquals("AT", nested.token)
        assertEquals("RT", nested.refreshToken)
        assertEquals("AT", requireNotNull(CloudLoginData.from(JSONObject("""{"token":"AT"}"""))).token)
    }

    @Test
    fun expiryFallsBackToTheNamesTheApiActuallyUses() {
        val data =
            requireNotNull(
                CloudLoginData.from(
                    JSONObject("""{"data":{"accessToken":"AT","accessTokenExpiresin":"1893456000000"}}"""),
                ),
            )
        assertEquals("the misspelled spelling still counts", 1893456000000L, data.accessTokenExpireTime)
        assertEquals("an absent expiry means never expires", 0L, data.refreshTokenExpireTime)
    }

    @Test
    fun noTokenIsNoSession() {
        assertNull("an error envelope carries no session", CloudLoginData.from(JSONObject("""{"code":401,"msg":"访问令牌不存在"}""")))
        assertNull("an empty data object carries no session", CloudLoginData.from(JSONObject("""{"code":0,"data":{}}""")))
        assertNull("not even an empty answer does", CloudLoginData.from(JSONObject()))
    }

    @Test
    fun anIncompleteAccountSaysWhatTheUserHasToDo() {
        val phone =
            requireNotNull(
                CloudLoginData.from(
                    JSONObject("""{"data":{"token":"AT","accessToken":"AT","needBindPhone":true,"needBindEmail":false}}"""),
                ),
            )
        assertEquals("Elegoo wants a phone number on this account — finish it in the Elegoo Matrix app.", phone.setupNote)
        val email =
            requireNotNull(
                CloudLoginData.from(JSONObject("""{"data":{"token":"AT","needBindEmail":true}}""")),
            )
        assertEquals(
            "Elegoo wants the email on this account confirmed — finish it in the Elegoo Matrix app.",
            email.setupNote,
        )
    }
}
