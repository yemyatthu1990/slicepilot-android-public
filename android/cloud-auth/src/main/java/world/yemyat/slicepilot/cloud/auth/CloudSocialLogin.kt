package world.yemyat.slicepilot.cloud.auth

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Elegoo's side of "Sign in with Google" (the same endpoint also answers for Facebook).
 *
 * Elegoo does not accept a Google credential that our app minted itself — there is no route that
 * takes a Google ID token (`account-auth/third-party/google` and `oauth/token` both answer the
 * generic "账号未登录"). Instead the official web login page drives a *federated auth centre* (FAC):
 *
 * ```text
 * GET  {gateway}/api/v1/account-center-server/fac/api/auth/redirect/{provider}
 *        ?return_to=…&callback=…&source={clientId}
 *      → 302 accounts.google.com/…?client_id=<Elegoo's own Google client>&redirect_uri=<Elegoo's server>
 *      → …and the provider token lands on return_to?token=…
 *
 * POST {gateway}/api/v1/account-center-server/account-auth/third-party/fac
 *        {"token": <provider token>, "clientId": …}  → the same loginData a password login returns
 * ```
 *
 * Because Google's redirect_uri is fixed to Elegoo's server and `return_to` is ours to choose, a
 * third-party app needs no Google Cloud setup and no new secret to open the same door: it just has
 * to render that first URL in a real browser tab and read the token back off the deep link.
 *
 * Google is configured for the international region only — `matrix.elegoo.com.cn` answers the
 * redirect with `…/fac/api/auth/null` — so [CloudAuthClient.socialSignInSupported] gates the UI.
 */
object CloudSocialLogin {
    const val PROVIDER_GOOGLE = "google"
    const val PROVIDER_FACEBOOK = "facebook"

    /** `…/api/v1/account-center-server/fac/api/auth/redirect/{provider}`. */
    const val REDIRECT_PATH = "/fac/api/auth/redirect"

    /** `…/api/v1/account-center-server/account-auth/third-party/fac`. */
    const val TOKEN_PATH = "/account-auth/third-party/fac"

    /** `world.yemyat.slicepilot://social/{provider}` — where the provider token is handed back. */
    const val SOCIAL_HOST = "social"

    val providers: Set<String> = setOf(PROVIDER_GOOGLE, PROVIDER_FACEBOOK)

    fun normalizeProvider(provider: String): String = provider.trim().lowercase()

    fun isSupported(provider: String): Boolean = normalizeProvider(provider) in providers

    /** The app-side address the provider token comes back on, one per provider. */
    fun callbackUrl(provider: String): String = "$DEFAULT_CLOUD_AUTH_REDIRECT_SCHEME://$SOCIAL_HOST/${normalizeProvider(provider)}"

    /**
     * The URL to open in a browser tab to start a sign-in.
     *
     * @param apiBase the account service, e.g. `https://matrix.elegoo.com/api/v1/account-center-server`
     *   (the China host has no Google configured — see the class docs).
     */
    fun redirectUrl(
        apiBase: String,
        clientId: String,
        provider: String,
    ): String {
        val returnTo = URLEncoder.encode(callbackUrl(provider), "UTF-8")
        return "$apiBase$REDIRECT_PATH/${normalizeProvider(provider)}" +
            "?return_to=$returnTo&callback=$returnTo&source=$clientId"
    }

    /** The provider a callback URL belongs to, or null when the URL is not one of ours. */
    fun providerOf(url: String): String? {
        if (url.substringBefore("://", "") != DEFAULT_CLOUD_AUTH_REDIRECT_SCHEME) return null
        val rest = url.substringAfter("://")
        if (rest.substringBefore('/').substringBefore('?') != SOCIAL_HOST) return null
        return rest
            .substringAfter('/', "")
            .substringBefore('?')
            .trim('/')
            .takeIf { isSupported(it) }
    }

    /** The one-shot provider token a callback carries (the aliases the web client also accepts). */
    fun tokenOf(url: String): String? = queryValue(url, "token", "facToken", "accessToken", "access_token")

    /** Set instead of [tokenOf] when the provider refused: suspended, needs a new password, … */
    fun errorOf(url: String): String? = queryValue(url, "errorDescription", "error_description", "error", "msg")

    private fun queryValue(
        url: String,
        vararg keys: String,
    ): String? {
        val wanted = keys.map { it.lowercase() }
        return url
            .substringAfter('?', "")
            .split('&')
            .asSequence()
            .map { it.split('=', limit = 2) }
            .filter { it.size == 2 }
            .firstOrNull { it[0].lowercase() in wanted }
            ?.let { URLDecoder.decode(it[1], "UTF-8") }
            ?.takeIf { it.isNotBlank() }
    }
}
