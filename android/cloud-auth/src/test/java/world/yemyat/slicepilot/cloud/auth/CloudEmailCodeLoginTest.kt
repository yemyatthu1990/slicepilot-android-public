package world.yemyat.slicepilot.cloud.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for Elegoo / Nexprint email verification-code sign-in.
 *
 * The live `account-center-server` gateway expects `useType = 2` (Login) on `/verify-code/email`,
 * plain `clientId` + `email` + 6-digit `code` on `/account-auth/login/email/code`, and reports
 * invalid/expired/rate-limited codes via numeric account-center error codes.
 */
class CloudEmailCodeLoginTest {
    @Test
    fun emailValidationMatchesAccountCenterRules() {
        assertTrue(CloudEmailCodeLogin.isValidEmail("user@example.com"))
        assertTrue(CloudEmailCodeLogin.isValidEmail("  first.last+tag@sub.example.co  "))
        assertFalse(CloudEmailCodeLogin.isValidEmail(""))
        assertFalse(CloudEmailCodeLogin.isValidEmail("not-an-email"))
        assertFalse(CloudEmailCodeLogin.isValidEmail("a@b"))
        assertFalse(CloudEmailCodeLogin.isValidEmail("a..b@example.com"))
        assertFalse(CloudEmailCodeLogin.isValidEmail("a.@example.com"))
        assertFalse(CloudEmailCodeLogin.isValidEmail("a@.example.com"))
        assertFalse(CloudEmailCodeLogin.isValidEmail("a b@example.com"))
    }

    @Test
    fun sanitizeCodeKeepsSixDigitsOnly() {
        assertEquals("123456", CloudEmailCodeLogin.sanitizeCode(" 123-456 \n"))
        assertEquals("123456", CloudEmailCodeLogin.sanitizeCode("123456789"))
        assertEquals("42", CloudEmailCodeLogin.sanitizeCode("ab4c2"))
        assertTrue(CloudEmailCodeLogin.isCompleteCode("004291"))
        assertFalse(CloudEmailCodeLogin.isCompleteCode("12345"))
        assertFalse(CloudEmailCodeLogin.isCompleteCode("12345a"))
    }

    @Test
    fun requestBodiesCarryExpectedFieldsAndTrimmedValues() {
        val sendBody = CloudEmailCodeLogin.sendCodeBody("  maker@example.com ", "Graft")
        assertEquals("maker@example.com", sendBody.getString("email"))
        assertEquals(CloudEmailCodeLogin.USE_TYPE_LOGIN, sendBody.getInt("useType"))
        assertEquals("Graft", sendBody.getString("clientId"))

        val loginBody = CloudEmailCodeLogin.loginBody(" maker@example.com ", " 654 321 ", "Graft")
        assertEquals("Graft", loginBody.getString("clientId"))
        assertEquals("maker@example.com", loginBody.getString("email"))
        assertEquals("654321", loginBody.getString("code"))
    }

    @Test
    fun nextSendTimeReadsOnlyPositiveObjectValues() {
        assertEquals(
            45,
            CloudEmailCodeLogin.nextSendTimeSeconds(
                JSONObject("""{"code":10013,"data":{"nextSendTime":45}}"""),
            ),
        )
        assertNull(
            "a boolean success data field has no cooldown override",
            CloudEmailCodeLogin.nextSendTimeSeconds(JSONObject("""{"code":0,"data":true,"msg":""}""")),
        )
        assertNull(
            CloudEmailCodeLogin.nextSendTimeSeconds(JSONObject("""{"code":10013,"data":{"nextSendTime":0}}""")),
        )
    }

    @Test
    fun friendlyErrorMapsElegooVerificationCodeErrors() {
        assertEquals(
            "Incorrect verification code. Please try again.",
            CloudEmailCodeLogin.friendlyError("verify code invalid", 10002),
        )
        assertEquals(
            "Verification code expired. Please request a new one.",
            CloudEmailCodeLogin.friendlyError("", 10006),
        )
        assertEquals(
            "Too many code requests. Please try again in 37s.",
            CloudEmailCodeLogin.friendlyError("", 10013, nextSendSeconds = 37),
        )
        assertEquals(
            "Too many code requests. Please wait a moment and try again.",
            CloudEmailCodeLogin.friendlyError("", 10013),
        )
        assertEquals(
            "No account found for this email. Please sign up first.",
            CloudEmailCodeLogin.friendlyError("", 1003000016),
        )
        assertEquals(
            "Please enter a valid email address.",
            CloudEmailCodeLogin.friendlyError("请求参数不正确:邮箱格式不正确", 400),
        )
        assertNull(CloudEmailCodeLogin.friendlyError("some other error", 500))
    }
}
