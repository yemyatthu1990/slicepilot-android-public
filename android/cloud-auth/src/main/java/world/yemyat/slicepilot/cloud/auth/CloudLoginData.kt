package world.yemyat.slicepilot.cloud.auth

import org.json.JSONObject

/**
 * The session the account API hands back after any successful sign-in, read from either shape it
 * answers in: the login endpoints wrap it in `{"code":…,"data":{…}}`, and the social (FAC) answer
 * sometimes nests it one level deeper under `loginData`.
 *
 * Names are read loosely for the same reason the official web client does — the API has answered
 * `token` where the app expects `accessToken`, and `accessTokenExpiresin` (yes, one "g") where it
 * expects `accessTokenExpireTime` — so one tolerant parser serves the password, the social and the
 * refresh responses instead of three half-duplicated `optString` chains.
 */
data class CloudLoginData(
    val token: String,
    val refreshToken: String = "",
    val accessTokenExpireTime: Long = 0L,
    val refreshTokenExpireTime: Long = 0L,
    val nickname: String = "",
    val email: String = "",
    val accountId: String = "",
    val needBindEmail: Boolean = false,
    val needBindPhone: Boolean = false,
) {
    /**
     * Set on a brand-new social account: the token works, but Elegoo wants the profile completed
     * (a phone number, or a confirmed email) before the account is fully usable. The web client
     * blocks on a binding page; here it is reported instead, because binding needs the Matrix app.
     */
    val setupNote: String?
        get() =
            when {
                needBindPhone -> "Elegoo wants a phone number on this account — finish it in the Elegoo Matrix app."
                needBindEmail -> "Elegoo wants the email on this account confirmed — finish it in the Elegoo Matrix app."
                else -> null
            }

    companion object {
        /** Null when the answer carries no access token, which is how "no session" looks in JSON. */
        fun from(response: JSONObject): CloudLoginData? {
            val data = response.optJSONObject("data") ?: response
            val login = data.optJSONObject("loginData") ?: data
            val token = firstString(login, "token", "accessToken") ?: return null
            return CloudLoginData(
                token = token,
                refreshToken = firstString(login, "refreshToken").orEmpty(),
                accessTokenExpireTime =
                    firstLong(login, "accessTokenExpireTime", "accessTokenExpiresin", "expiresTime"),
                refreshTokenExpireTime =
                    firstLong(login, "refreshTokenExpireTime", "refreshTokenExpiresin", "refreshExpiresTime"),
                nickname = firstString(login, "nickname", "nickName", "userName", "username").orEmpty(),
                email = firstString(login, "email", "mail", "userEmail").orEmpty(),
                accountId = firstString(login, "accountId", "userId", "uuid").orEmpty(),
                needBindEmail = login.optBoolean("needBindEmail", false),
                needBindPhone = login.optBoolean("needBindPhone", false),
            )
        }

        private fun firstString(
            json: JSONObject,
            vararg keys: String,
        ): String? =
            keys
                .asSequence()
                .map { json.optString(it, "").trim() }
                .firstOrNull { it.isNotEmpty() }

        /** The first key that carries a non-zero epoch millis; the API omits the ones it doesn't know. */
        private fun firstLong(
            json: JSONObject,
            vararg keys: String,
        ): Long =
            keys
                .asSequence()
                .filter { json.has(it) }
                .map { json.optLong(it, 0L) }
                .firstOrNull { it != 0L }
                ?: 0L
    }
}
