package org.schabi.newpipe.bilai

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.bilai.engine.BilaiAccountStore
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

private const val SIGN_IN_URL = "https://www.youtube.com/signin"
private const val RETURN_URL =
    "https://www.youtube.com/signin?action_handle_signin=true&app=desktop&next=%2F"

@Composable
internal fun AccountPage(close: () -> Unit, signedIn: () -> Unit) {
    val activity = LocalContext.current as Activity
    val p = LocalBilaiColors.current
    var url by remember { mutableStateOf<String?>(null) }
    var cookies by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
        if (result.resultCode != Activity.RESULT_OK || name == null || type != "com.google") {
            return@rememberLauncherForActivityResult
        }
        try {
            AccountManager.get(activity).getAuthToken(
                Account(name, type), "weblogin:service=youtube&continue=" + Uri.encode(RETURN_URL),
                null, activity, { response ->
                    val returned = runCatching { response.result.getString(AccountManager.KEY_AUTHTOKEN) }.getOrNull()
                    url = returned?.takeIf(::trustedLoginUrl) ?: SIGN_IN_URL
                }, Handler(Looper.getMainLooper()),
            )
        } catch (_: SecurityException) {
            url = SIGN_IN_URL
        }
    }
    LaunchedEffect(cookies) {
        val header = cookies ?: return@LaunchedEffect
        if (runCatching { YoutubeParsingHelper.getAuthorizationHeader(header) }.getOrNull().isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        if (withContext(Dispatchers.IO) { BilaiAccountStore.save(activity, header) }) signedIn()
        else failed = true
    }
    BrowseScreen("Sign in", close) {
        val target = url
        if (target == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeedText("Choose a Google account to continue.", 14, p.text)
                WatchPill("Choose account", p.onAccent, p.accent) {
                    try {
                        launcher.launch(AccountManager.newChooseAccountIntent(
                            null, null, arrayOf("com.google"), null, null, null, null))
                    } catch (_: android.content.ActivityNotFoundException) {
                        url = SIGN_IN_URL
                    }
                }
            }
        } else {
            if (failed) FeedText("Couldn't save the account session.", 12, p.muted)
            key(target) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.isForMainFrame && !trustedLoginUrl(request.url.toString())

                            override fun onPageFinished(view: WebView, location: String) {
                                if (cookies != null || !trustedLoginUrl(location)) return
                                val host = Uri.parse(location).host.orEmpty()
                                if (host != "youtube.com" && !host.endsWith(".youtube.com")) return
                                val manager = CookieManager.getInstance()
                                val header = sequenceOf("https://music.youtube.com/watch", "https://www.youtube.com/")
                                    .mapNotNull(manager::getCookie)
                                    .firstOrNull { value -> value.split(';').any { it.trim().startsWith("SID=") } }
                                if (header != null) cookies = header
                            }
                        }
                        loadUrl(target)
                    }
                }, onReset = null, onRelease = { view -> view.stopLoading(); view.destroy() })
            }
        }
    }
}

private fun trustedLoginUrl(url: String): Boolean {
    val uri = Uri.parse(url)
    val host = uri.host.orEmpty().lowercase()
    return uri.scheme == "https" && (host == "accounts.google.com" || host == "youtube.com" ||
        host.endsWith(".youtube.com"))
}
