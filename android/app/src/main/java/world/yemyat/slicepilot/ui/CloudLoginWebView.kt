package world.yemyat.slicepilot.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

private const val REDIRECT_SCHEME = "world.yemyat.slicepilot"
private const val REDIRECT_URI = "$REDIRECT_SCHEME://oauth/callback"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLoginWebView(
    vm: AppViewModel,
    url: String,
    onSuccess: () -> Unit,
    onClose: () -> Unit,
) {
    var logged by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val state = remember { "st" + System.currentTimeMillis() }

    val authorizeUrl = remember(url, state) {
        Uri.parse(url).buildUpon()
            .appendQueryParameter("client_id", "6")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .appendQueryParameter("response_type", "code")
            .build()
            .toString()
    }

    LaunchedEffect(Unit) {
        while (!logged) {
            delay(1500)
            if (vm.tryLoginWithWebViewCookies()) logged = true
        }
        onSuccess()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, "Close")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                CookieManager.getInstance().flush()
                            }
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val u = request?.url?.toString() ?: return false
                                return u.startsWith(REDIRECT_SCHEME + "://")
                            }
                        }
                        webViewRef = this
                        loadUrl(authorizeUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (logged) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        "Signed in!",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
