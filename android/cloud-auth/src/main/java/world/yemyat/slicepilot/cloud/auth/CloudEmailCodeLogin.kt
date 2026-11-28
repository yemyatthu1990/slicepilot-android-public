package world.yemyat.slicepilot.cloud.auth

import org.json.JSONObject

/**
 * Elegoo / Nexprint passwordless sign-in with an emailed 6-digit verification code.
 *
 * The account-center service handles both steps without RSA/AES payload encryption on both the
 * global (`matrix.elegoo.com`) and China (`matrix.elegoo.com.cn`) gateways:
 *
 * ```text
 * POST {gateway}/api/v1/account-center-server/verify-code/email
 *        {"email": <email>, "useType": 2, "clientId": <clientId>}
 *      → {"code": 0, "data": true, "msg": ""}
 *
 * POST {gateway}/api/v1/account-center-server/account-auth/login/email/code
 *        {"clientId": <clientId>, "email": <email>, "code": <6-digit code>}
 *      → the same loginData envelope a password or social login returns
 * ```
 *
 * `useType` is Elegoo's scene enum from `account.elegoo.com`:
 * `1 = Register`, `2 = Login`, `3 = ForgotPassword`, `4 = ModifyAccount`, `5 = Cancel`.
 */
object CloudEmailCodeLogin {
    /** `…/api/v1/account-center-server/verify-code/email`. */
    const val SEND_CODE_PATH = "/verify-code/email"

    /** `…/api/v1/account-center-server/account-auth/login/email/code`. */
    const val LOGIN_PATH = "/account-auth/login/email/code"

    /** Elegoo account-center scene id for login verification codes. */
    const val USE_TYPE_LOGIN = 2

    const val CODE_LENGTH = 6
    const val DEFAULT_COOLDOWN_SECONDS = 60

    const val ERR_VERIFY_CODE_INVALID = 10002
    const val ERR_ACCOUNT_LOCKED = 10004
    const val ERR_VERIFY_CODE_EXPIRED = 10006
    const val ERR_USER_NOT_EXISTS = 10012
    const val ERR_VERIFY_CODE_SEND_TOO_FREQUENT = 10013
    const val ERR_LOGIN_LOCKED = 1002000006
    const val ERR_SMS_EXCEEDED_DAILY_LIMIT = 1002014004
    const val ERR_SMS_TOO_FREQUENT = 1002014005
    const val ERR_INVALID_EMAIL_FORMAT = 1003000004
    const val ERR_INVALID_EMAIL_VERIFY_CODE = 1003000007
    const val ERR_EMAIL_NOT_REGISTERED = 1003000016

    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    fun normalizeEmail(raw: String): String = raw.trim()

    fun isValidEmail(raw: String): Boolean {
        val email = normalizeEmail(raw)
        if (email.isEmpty()) return false
        if (email.contains("..") || email.contains(".@") || email.contains("@.")) return false
        return EMAIL_REGEX.matches(email)
    }

    /** Strips whitespace/punctuation pasted alongside a code and caps at [CODE_LENGTH] digits. */
    fun sanitizeCode(raw: String): String = raw.filter { it.isDigit() }.take(CODE_LENGTH)

    fun isCompleteCode(code: String): Boolean = code.length == CODE_LENGTH && code.all { it.isDigit() }

    fun sendCodeBody(
        email: String,
        clientId: String,
    ): JSONObject =
        JSONObject()
            .put("email", normalizeEmail(email))
            .put("useType", USE_TYPE_LOGIN)
            .put("clientId", clientId)

    fun loginBody(
        email: String,
        code: String,
        clientId: String,
    ): JSONObject =
        JSONObject()
            .put("clientId", clientId)
            .put("email", normalizeEmail(email))
            .put("code", sanitizeCode(code))

    /**
     * Reads `data.nextSendTime` (seconds) when Elegoo returns a cooldown hint on rate-limit
     * errors; returns null when `data` is absent, boolean (`true`), or has no positive wait time.
     */
    fun nextSendTimeSeconds(response: JSONObject): Int? {
        val data = response.optJSONObject("data") ?: return null
        val secs = data.optInt("nextSendTime", 0)
        return secs.takeIf { it > 0 }
    }

    /**
     * Maps Elegoo verification-code and account-center error codes to user-friendly copy.
     * Returns null when the error does not match a known verification-code failure so the caller
     * can fall through to generic login error mapping.
     */
    fun friendlyError(
        msg: String,
        code: Int,
        nextSendSeconds: Int? = null,
    ): String? =
        when {
            code == ERR_VERIFY_CODE_INVALID ||
                code == ERR_INVALID_EMAIL_VERIFY_CODE ||
                msg.contains("verify code invalid", ignoreCase = true) ||
                msg.contains("验证码错误") -> {
                "Incorrect verification code. Please try again."
            }

            code == ERR_VERIFY_CODE_EXPIRED ||
                msg.contains("verify code expired", ignoreCase = true) ||
                msg.contains("验证码已失效") ||
                msg.contains("验证码过期") -> {
                "Verification code expired. Please request a new one."
            }

            code == ERR_VERIFY_CODE_SEND_TOO_FREQUENT ||
                code == ERR_SMS_TOO_FREQUENT ||
                code == ERR_SMS_EXCEEDED_DAILY_LIMIT ||
                msg.contains("too frequent", ignoreCase = true) ||
                msg.contains("过于频繁") -> {
                if (nextSendSeconds != null && nextSendSeconds > 0) {
                    "Too many code requests. Please try again in ${nextSendSeconds}s."
                } else {
                    "Too many code requests. Please wait a moment and try again."
                }
            }

            code == ERR_INVALID_EMAIL_FORMAT ||
                msg.contains("邮箱格式") -> {
                "Please enter a valid email address."
            }

            code == ERR_USER_NOT_EXISTS ||
                code == ERR_EMAIL_NOT_REGISTERED ||
                msg.contains("user not exist", ignoreCase = true) ||
                msg.contains("未注册") -> {
                "No account found for this email. Please sign up first."
            }

            code == ERR_ACCOUNT_LOCKED ||
                code == ERR_LOGIN_LOCKED -> {
                "Account temporarily locked after too many attempts. Wait a few minutes and try again."
            }

            else -> {
                null
            }
        }
}
