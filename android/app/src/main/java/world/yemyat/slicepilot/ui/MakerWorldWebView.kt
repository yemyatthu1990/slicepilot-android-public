package world.yemyat.slicepilot.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.annotation.Keep
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import world.yemyat.slicepilot.repo.BambuLoopbackServer
import world.yemyat.slicepilot.repo.BambuSocialLogin
import world.yemyat.slicepilot.repo.MakerworldCustomizer
import world.yemyat.slicepilot.repo.ScadCustomizer
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCyan
import world.yemyat.slicepilot.ui.theme.ButtonRole
import world.yemyat.slicepilot.ui.theme.ButtonSize
import world.yemyat.slicepilot.ui.theme.Space

private const val TAG = "MakerWorldWeb"

/**
 * Extracts OpenSCAD source from MakerWorld's Next.js `__NEXT_DATA__` payload when present.
 */
private const val EXTRACT_SOURCE_JS =
    """
(function () {
  var hits = [];
  function scan(o, depth) {
    if (!o || depth > 7) return;
    var keys = Object.keys(o);
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i], v = o[k];
      if (typeof v === 'string' && v.length > 200 &&
          (k === 'protectedScadContent' || k === 'scadContent' || k === 'code' ||
           (v.indexOf('/* [') >= 0 && v.indexOf('=') > 0))) hits.push(v);
      else if (v && typeof v === 'object') scan(v, depth + 1);
    }
  }
  try {
    var el = document.getElementById('__NEXT_DATA__');
    if (el) scan(JSON.parse(el.textContent), 0);
  } catch (e) {}
  if (!hits.length) return '';
  hits.sort(function (a, b) { return b.length - a.length; });
  for (var j = 0; j < hits.length; j++) {
    if (hits[j].indexOf('module ') >= 0 || hits[j].indexOf('linear_extrude') >= 0 ||
        hits[j].indexOf('translate(') >= 0) return hits[j];
  }
  return hits[0];
})();
"""

/**
 * Reads Bambu tokens minted by a web sign-in back out of `localStorage` so one
 * sign-in covers both the WebView and native API calls.
 */
private const val READ_SESSION_JS =
    """
(function () {
  try {
    var uid = '';
    try { uid = JSON.parse(localStorage.getItem('bbl-app-user-profile') || '{}').uidStr || ''; } catch (e) {}
    return JSON.stringify({
      access: localStorage.getItem('bbl-app-access-token') || '',
      refresh: localStorage.getItem('bbl-app-refresh-token') || '',
      uid: uid,
    });
  } catch (e) { return ''; }
})();
"""

/**
 * Hooks `fetch` and `XMLHttpRequest` on MakerWorld's customizer page to:
 * 1. Capture `scad/modelv2` generation request payloads (carrying the private template for
 *    protected designs so future edits can run natively).
 * 2. Capture finished `scad/modelv2` responses (`status === "success"`) so SlicePilot can
 *    offer a one-tap "Load into SlicePilot" button right after the user taps Generate.
 */
private const val HOOK_JS =
    """
try {
  if (!window.__slicepilotHooked) {
    window.__slicepilotHooked = true;
    var postWx = function (obj) {
      try {
        if (window.wx && window.wx.postMessage) {
          window.wx.postMessage(JSON.stringify(obj));
        }
      } catch (e) {}
    };
    var noteReq = function (url, body) {
      try {
        var u = String(url || '');
        if (/modelv2/.test(u) && body) {
          postWx({ command: 'pmm_request', body: String(body) });
        }
      } catch (e) {}
    };
    var noteResp = function (url, text) {
      try {
        var u = String(url || '');
        if (!/modelv2/.test(u) || !text) return;
        if (text.indexOf('"success"') >= 0 &&
            (text.indexOf('stlUrl') >= 0 || text.indexOf('objUrl') >= 0)) {
          postWx({ command: 'pmm_result', body: String(text) });
        }
      } catch (e) {}
    };
    var origFetch = window.fetch;
    window.fetch = function (u, o) {
      var urlStr = (typeof u === 'string') ? u : (u && u.url ? u.url : '');
      noteReq(urlStr, o && o.body);
      var p = origFetch.apply(this, arguments);
      if (/modelv2/.test(urlStr)) {
        p.then(function (resp) {
          try {
            resp.clone().text().then(function (t) { noteResp(urlStr, t); }).catch(function () {});
          } catch (e) {}
        }).catch(function () {});
      }
      return p;
    };
    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) {
      this.__spUrl = u;
      return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (b) {
      noteReq(this.__spUrl, b);
      var xhr = this;
      if (/modelv2/.test(String(this.__spUrl || ''))) {
        xhr.addEventListener('load', function () {
          try { noteResp(xhr.__spUrl, xhr.responseText); } catch (e) {}
        });
      }
      return origSend.apply(this, arguments);
    };
  }
} catch (e) {}
"""

/**
 * Aliases desktop WebView bridge entrypoints (`webkit.messageHandlers.wx` on macOS/Linux and
 * `chrome.webview` on Edge WebView2) onto our Android `window.wx.postMessage` bridge so
 * `bambulab.com/en/sign-in` finds the slicer bridge regardless of which desktop API it probes.
 */
private const val SLICER_BRIDGE_SHIM_JS =
    """
(function () {
  try {
    if (!window.wx || !window.wx.postMessage) return;
    var forward = function (msg) {
      try {
        window.wx.postMessage(typeof msg === 'string' ? msg : JSON.stringify(msg));
      } catch (e) {}
    };
    if (!window.webkit) window.webkit = {};
    if (!window.webkit.messageHandlers) window.webkit.messageHandlers = {};
    if (!window.webkit.messageHandlers.wx) {
      window.webkit.messageHandlers.wx = { postMessage: forward };
    }
    if (!window.chrome) window.chrome = {};
    if (!window.chrome.webview) {
      window.chrome.webview = { postMessage: forward };
    }
  } catch (e) {}
})();
"""

/**
 * Named JS bridge exposed as `window.wx` inside MakerWorld's WebView.
 * Kept explicitly from R8 in `proguard-rules.pro` and via `@Keep`.
 */
@Keep
internal class MakerWorldJsBridge(
    private val webViewProvider: () -> WebView?,
    private val tokenPayload: () -> String?,
    private val onPmmRequest: (String) -> Unit,
    private val onPmmResult: (String) -> Unit,
    private val onGetLocalhostUrl: () -> String? = { null },
    private val onThirdPartyLogin: (String) -> Unit = {},
    private val onTicketLogin: (String) -> Unit = {},
    private val onDirectLogin: (access: String, refresh: String, uid: String) -> Unit = { _, _, _ -> },
    private val onOpenExternalUrl: (String) -> Unit = {},
) {
    @JavascriptInterface
    fun postMessage(raw: String) {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (obj.optString("command")) {
            "getSupportedCommands" -> {
                val reply =
                    JSONObject()
                        .put("sequence_id", obj.opt("sequence_id"))
                        .put(
                            "response",
                            JSONArray()
                                .put("getSupportedCommands")
                                .put("request_user_token_from_app")
                                .put("get_localhost_url")
                                .put("thirdparty_login")
                                .put("user_ticket_login")
                                .put("user_login"),
                        )
                Log.i(TAG, "token bridge: replied to getSupportedCommands")
                val w = webViewProvider() ?: return
                w.post {
                    w.evaluateJavascript(
                        "window.postMessage(" +
                            JSONObject.quote(reply.toString()) +
                            ", window.location.origin)",
                        null,
                    )
                }
            }

            "request_user_token_from_app" -> {
                val payload = tokenPayload()?.takeIf { it.isNotBlank() } ?: return
                val reply =
                    JSONObject()
                        .put("sequence_id", obj.opt("sequence_id"))
                        .put("response", JSONObject(payload))
                Log.i(TAG, "token bridge: handed app token to MakerWorld page")
                val w = webViewProvider() ?: return
                w.post {
                    w.evaluateJavascript(
                        "window.postMessage(" +
                            JSONObject.quote(reply.toString()) +
                            ", window.location.origin)",
                        null,
                    )
                }
            }

            "get_localhost_url" -> {
                val baseUrl = onGetLocalhostUrl()?.takeIf { it.isNotBlank() } ?: return
                val reply =
                    JSONObject()
                        .put("command", "get_localhost_url")
                        .put("sequence_id", obj.opt("sequence_id"))
                        .put(
                            "response",
                            JSONObject()
                                .put("base_url", baseUrl)
                                .put("result", "success"),
                        )
                Log.i(TAG, "slicer bridge: get_localhost_url -> $baseUrl")
                val w = webViewProvider() ?: return
                w.post {
                    w.evaluateJavascript("window.postMessage(${reply}, '*')", null)
                }
            }

            "thirdparty_login" -> {
                val url = obj.optJSONObject("data")?.optString("url").orEmpty()
                if (url.isNotBlank()) {
                    Log.i(TAG, "slicer bridge: thirdparty_login url=$url")
                    onThirdPartyLogin(url)
                }
            }

            "user_ticket_login" -> {
                val ticket = obj.optJSONObject("data")?.optString("ticket").orEmpty()
                if (ticket.isNotBlank()) {
                    Log.i(TAG, "slicer bridge: user_ticket_login")
                    onTicketLogin(ticket)
                }
            }

            "user_login" -> {
                val data = obj.optJSONObject("data")
                val access =
                    data?.optString("token").orEmpty().ifBlank {
                        data?.optString("access_token").orEmpty()
                    }
                if (access.isNotBlank()) {
                    val refresh = data?.optString("refresh_token").orEmpty()
                    val uid =
                        data
                            ?.optJSONObject("user")
                            ?.optString("uid")
                            .orEmpty()
                            .ifBlank { data?.optString("uid").orEmpty() }
                    Log.i(TAG, "slicer bridge: user_login (uid=$uid)")
                    onDirectLogin(access, refresh, uid)
                }
            }

            "new_webpage" -> {
                val url = obj.optJSONObject("data")?.optString("url").orEmpty()
                if (url.startsWith("http")) {
                    Log.i(TAG, "slicer bridge: new_webpage url=$url")
                    onOpenExternalUrl(url)
                }
            }

            "pmm_request" -> {
                val body = obj.optString("body")
                if (body.isNotBlank()) {
                    Log.i(TAG, "harvested MakerWorld generation request (${body.length} bytes)")
                    onPmmRequest(body)
                }
            }

            "pmm_result" -> {
                val body = obj.optString("body")
                if (body.isNotBlank()) {
                    Log.i(TAG, "captured MakerWorld generation result (${body.length} bytes)")
                    onPmmResult(body)
                }
            }

            else -> {
                Log.d(TAG, "web command=${obj.optString("command")}")
            }
        }
    }
}

/**
 * MakerWorld's own mobile customizer or sign-in page, hosted inside SlicePilot.
 *
 * Passes the user's Bambu token via the `BBL-App/<n>` UA + `window.wx` bridge,
 * captures web sign-in tokens back into the app, harvests OpenSCAD parameters,
 * and intercepts generated models / downloads so they load straight into SlicePilot.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MakerWorldWebViewScreen(
    url: String,
    onBack: () -> Unit,
    onSource: (String) -> Unit = {},
    onCookies: (String) -> Unit = {},
    onSession: (access: String, refresh: String, uid: String) -> Unit = { _, _, _ -> },
    onRequest: (String) -> Unit = {},
    onGeneratedJob: ((MakerworldCustomizer.Job) -> Unit)? = null,
    onDownloadUrl: ((url: String, filename: String) -> Unit)? = null,
    onSwitchToNative: (() -> Unit)? = null,
    accessToken: String = "",
    tokenPayload: () -> String? = { null },
    bannerHint: String = "Customize below and tap Generate, then load your model into SlicePilot.",
    importing: Boolean = false,
    /**
     * Enables Bambu Studio's slicer UA + loopback Custom Tabs handoff so Google/Apple/Facebook
     * sign-in on `bambulab.com/en/sign-in` completes in Chrome instead of being rejected by an
     * embedded WebView.
     */
    signInMode: Boolean = false,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var foundSource by remember { mutableStateOf(false) }
    var latestJob by remember { mutableStateOf<MakerworldCustomizer.Job?>(null) }
    var authStatus by remember { mutableStateOf<String?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }

    val sourceRef = rememberUpdatedState(onSource)
    val payloadRef = rememberUpdatedState(tokenPayload)
    val cookiesRef = rememberUpdatedState(onCookies)
    val sessionRef = rememberUpdatedState(onSession)
    val pmmReqRef = rememberUpdatedState(onRequest)
    val downloadUrlRef = rememberUpdatedState(onDownloadUrl)
    val viewRef = remember { mutableStateOf<WebView?>(null) }
    val loopbackRef = remember { mutableStateOf<BambuLoopbackServer?>(null) }

    fun finishTicketSignIn(ticket: String) {
        coroutineScope.launch {
            authError = null
            authStatus = "Completing MakerWorld sign-in…"
            try {
                val session = withContext(Dispatchers.IO) { BambuSocialLogin.exchangeTicket(ticket) }
                authStatus = null
                sessionRef.value(session.accessToken, session.refreshToken, session.uid)
            } catch (e: Exception) {
                Log.w(TAG, "ticket exchange failed", e)
                authStatus = null
                authError = e.message ?: "MakerWorld sign-in failed. Please try again."
            }
        }
    }

    fun finishDirectTokenSignIn(
        access: String,
        refresh: String,
    ) {
        coroutineScope.launch {
            authError = null
            authStatus = "Completing MakerWorld sign-in…"
            val uid = withContext(Dispatchers.IO) { BambuSocialLogin.fetchProfileUid(access) }
            authStatus = null
            sessionRef.value(access, refresh, uid)
        }
    }

    fun ensureLoopbackServer(): BambuLoopbackServer {
        loopbackRef.value?.let { return it }
        val server =
            BambuLoopbackServer { result ->
                loopbackRef.value = null
                when (result) {
                    is BambuSocialLogin.CallbackResult.Ticket -> finishTicketSignIn(result.ticket)
                    is BambuSocialLogin.CallbackResult.DirectToken ->
                        finishDirectTokenSignIn(result.accessToken, result.refreshToken)
                    is BambuSocialLogin.CallbackResult.Error -> {
                        coroutineScope.launch {
                            authStatus = null
                            authError = result.message
                        }
                    }
                }
            }
        loopbackRef.value = server
        return server
    }

    fun launchCustomTab(targetUrl: String) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(targetUrl))
        } catch (_: ActivityNotFoundException) {
            authError = "No browser on this device to finish third-party sign-in."
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            loopbackRef.value?.close()
            loopbackRef.value = null
        }
    }

    BackHandler {
        val w = viewRef.value
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text =
                            if (title.contains("moment", ignoreCase = true)) {
                                "Verifying with MakerWorld…"
                            } else {
                                title.ifBlank { "MakerWorld" }
                            },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = {
            val job = latestJob
            val showLoad = job != null && onGeneratedJob != null
            val showNative = foundSource && onSwitchToNative != null
            if (showLoad || showNative) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = Space.md, vertical = Space.sm),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showNative) {
                            BrandButton(
                                text = "Native form",
                                onClick = { onSwitchToNative?.invoke() },
                                role = if (showLoad) ButtonRole.Secondary else ButtonRole.Primary,
                                enabled = !importing,
                                icon = { Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)) },
                                modifier = if (showLoad) Modifier else Modifier.weight(1f),
                                height = ButtonSize.Medium,
                            )
                        }
                        if (showLoad) {
                            BrandButton(
                                text = if (importing) "Loading…" else "Load into SlicePilot",
                                onClick = { job?.let { onGeneratedJob?.invoke(it) } },
                                role = ButtonRole.Primary,
                                loading = importing,
                                icon = { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)) },
                                modifier = Modifier.weight(1f),
                                height = ButtonSize.Medium,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = BrandCyan,
                )
            }
            Text(
                text =
                    when {
                        authError != null -> authError!!
                        authStatus != null -> authStatus!!
                        latestJob != null -> "Custom model ready — tap Load into SlicePilot below."
                        foundSource -> "Parameters detected — tap Generate or switch to the native form."
                        else -> bannerHint
                    },
                fontSize = 12.sp,
                color =
                    if (authError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.padding(horizontal = Space.md, vertical = 6.dp),
            )
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        val baseUa = settings.userAgentString.replace("; wv", "")
                        settings.userAgentString =
                            if (signInMode) {
                                // Bambu Studio's slicer UA token unlocks the get_localhost_url +
                                // thirdparty_login Chrome Custom Tab handoff on bambulab.com/en/sign-in.
                                "$baseUa ${BambuSocialLogin.SLICER_USER_AGENT_TOKEN}"
                            } else {
                                // BBL-App enables MakerWorld's mobile customizer token bridge.
                                "$baseUa BBL-App/${versionCode(ctx)} SlicePilot"
                            }
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        if (accessToken.isNotBlank()) {
                            cm.setCookie(
                                "https://makerworld.com",
                                "token=$accessToken; Path=/; Domain=.makerworld.com; Secure",
                            )
                            cm.flush()
                        }

                        val bridge =
                            MakerWorldJsBridge(
                                webViewProvider = { viewRef.value },
                                tokenPayload = { payloadRef.value() },
                                onPmmRequest = { body ->
                                    post {
                                        foundSource = true
                                        pmmReqRef.value(body)
                                    }
                                },
                                onPmmResult = { body ->
                                    MakerworldCustomizer.parseJobFromJson(body)?.let { parsed ->
                                        post { latestJob = parsed }
                                    }
                                },
                                onGetLocalhostUrl = {
                                    runCatching { ensureLoopbackServer().baseUrl }.getOrNull()
                                },
                                onThirdPartyLogin = { jumpUrl ->
                                    post {
                                        val server =
                                            runCatching { ensureLoopbackServer() }.getOrElse {
                                                authError = "Could not start local sign-in callback listener."
                                                return@post
                                            }
                                        val rewritten = BambuSocialLogin.rewriteLoopbackUrl(jumpUrl, server.port)
                                        authError = null
                                        authStatus = "Finish sign-in in your browser…"
                                        launchCustomTab(rewritten)
                                    }
                                },
                                onTicketLogin = { ticket -> finishTicketSignIn(ticket) },
                                onDirectLogin = { access, refresh, uid ->
                                    post { sessionRef.value(access, refresh, uid) }
                                },
                                onOpenExternalUrl = { extUrl -> post { launchCustomTab(extUrl) } },
                            )
                        addJavascriptInterface(bridge, "wx")

                        setDownloadListener { dlUrl, _, contentDisposition, mimeType, _ ->
                            val cb = downloadUrlRef.value ?: return@setDownloadListener
                            if (!dlUrl.startsWith("http")) return@setDownloadListener
                            val guessed =
                                android.webkit.URLUtil.guessFileName(dlUrl, contentDisposition, mimeType)
                            Log.i(TAG, "intercepted download: $guessed ($mimeType)")
                            cookiesRef.value(CookieManager.getInstance().getCookie(dlUrl).orEmpty())
                            cb(dlUrl, guessed)
                        }

                        webChromeClient =
                            object : WebChromeClient() {
                                override fun onProgressChanged(
                                    view: WebView,
                                    newProgress: Int,
                                ) {
                                    progress = newProgress
                                }
                            }
                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    Log.i(TAG, "page started: ${url.orEmpty()}")
                                    title = view.title.orEmpty()
                                    if (signInMode) {
                                        view.evaluateJavascript(SLICER_BRIDGE_SHIM_JS, null)
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    val target = request.url?.toString().orEmpty()
                                    if (signInMode && target.isNotBlank()) {
                                        Log.i(TAG, "signIn nav: $target")
                                    }
                                    return false
                                }

                                override fun onPageFinished(
                                    view: WebView,
                                    url: String?,
                                ) {
                                    title = view.title.orEmpty()
                                    cookiesRef.value(CookieManager.getInstance().getCookie(url).orEmpty())
                                    if (signInMode) {
                                        view.evaluateJavascript(SLICER_BRIDGE_SHIM_JS, null)
                                    } else {
                                        view.evaluateJavascript(HOOK_JS, null)
                                    }
                                }
                            }
                        viewRef.value = this
                        loadUrl(url)
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // Poll asynchronously for both the embedded source and any web sign-in session.
    LaunchedEffect(foundSource) {
        repeat(60) { tick ->
            delay(2000)
            val w = viewRef.value ?: return@LaunchedEffect
            if (!foundSource) {
                w.evaluateJavascript(EXTRACT_SOURCE_JS) { raw ->
                    val text = raw?.takeIf { it.length > 4 }?.let { unquoteJsString(it) }
                    if (!text.isNullOrBlank() && text.contains("=")) {
                        val complete =
                            text.length > 2500 ||
                                text.contains("module ") ||
                                text.contains("linear_extrude") ||
                                ScadCustomizer.parse(text).any { it !is ScadCustomizer.Param.Raw }
                        if (complete) {
                            Log.i(TAG, "source found in page: ${text.length} chars")
                            foundSource = true
                            sourceRef.value(text)
                        }
                    }
                }
            }
            if (tick % 3 == 0) {
                w.evaluateJavascript(READ_SESSION_JS) { raw ->
                    val json = raw?.takeIf { it.length > 4 }?.let { unquoteJsString(it) } ?: return@evaluateJavascript
                    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return@evaluateJavascript
                    val access = obj.optString("access")
                    if (access.isNotBlank()) {
                        Log.i(TAG, "web session picked up (uid=${obj.optString("uid")})")
                        sessionRef.value(access, obj.optString("refresh"), obj.optString("uid"))
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun versionCode(context: Context): Int =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (_: PackageManager.NameNotFoundException) {
        1
    }

/** `evaluateJavascript` hands back a JSON string literal. */
private fun unquoteJsString(raw: String): String? =
    runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrNull()
