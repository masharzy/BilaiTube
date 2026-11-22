package org.schabi.newpipe.bilai

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.bilai.engine.BilaiAccountStore
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

private const val RETURN_URL = "https://www.youtube.com/signin?action_handle_signin=true&app=desktop&next=%2F"

/** Google's ServiceLogin is best-effort: Google can require identity confirmation. */
private fun hintedSignIn(email: String) =
    Uri.parse("https://accounts.google.com/ServiceLogin").buildUpon()
        .appendQueryParameter("service", "youtube")
        .appendQueryParameter("continue", "https://www.youtube.com/")
        .appendQueryParameter("Email", email)
        .appendQueryParameter("login_hint", email)
        .build().toString()

@Composable
internal fun AccountPage(close: () -> Unit, signedIn: () -> Unit) {
    val activity = LocalContext.current as Activity
    val p = LocalBilaiColors.current
    val fixture = BuildConfig.DEBUG && activity.intent.getBooleanExtra("bt.account_fixture", false)
    val accounts = remember(activity, fixture) {
        if (fixture) listOf("devforge@example.com", "frames@example.com")
        else runCatching {
            AccountManager.get(activity).getAccountsByType("com.google")
                .map { it.name }.filter { it.isNotBlank() }.distinct()
        }.getOrDefault(emptyList())
    }
    var selectedEmail by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var cookies by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    fun select(email: String) {
        selectedEmail = email
        loading = true
        failed = false
        if (fixture) { loading = false; return }
        try {
            AccountManager.get(activity).getAuthToken(
                Account(email, "com.google"),
                "weblogin:service=youtube&continue=" + Uri.encode(RETURN_URL),
                null, activity, { response ->
                    val token = runCatching {
                        response.result.getString(AccountManager.KEY_AUTHTOKEN)
                    }.getOrNull()
                    url = token?.takeIf(::trustedLoginUrl) ?: hintedSignIn(email)
                    loading = false
                }, Handler(Looper.getMainLooper()),
            )
        } catch (_: Exception) {
            url = hintedSignIn(email)
            loading = false
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
        if (result.resultCode == Activity.RESULT_OK && !name.isNullOrBlank() && type == "com.google") select(name)
    }
    LaunchedEffect(cookies) {
        val header = cookies ?: return@LaunchedEffect
        if (runCatching { YoutubeParsingHelper.getAuthorizationHeader(header) }.getOrNull().isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        if (withContext(Dispatchers.IO) { BilaiAccountStore.save(activity, header) }) {
            selectedEmail?.let {
                activity.getSharedPreferences("bilai_appearance", Context.MODE_PRIVATE)
                    .edit().putString("account_email", it).apply()
            }
            signedIn()
        } else failed = true
    }
    BrowseScreen("Sign in", close) {
        val target = url
        if (target == null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeedText("Choose a Google account", 15, p.text, FontWeight.SemiBold)
                FeedText("If silent sign-in is unavailable, Google's page will open with the selected email prefilled.", 12, p.muted)
                if (accounts.isEmpty()) FeedText("No accessible accounts. Use Android's account chooser.", 12, p.muted)
                accounts.forEach { email ->
                    Row(Modifier.fillMaxWidth().clickable { select(email) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).background(p.accentSoft, CircleShape),
                            contentAlignment = Alignment.Center) {
                            FeedText(email.take(1).uppercase(), 17, p.accent, FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            FeedText(email, 13, p.text, FontWeight.Medium)
                            FeedText("Google account", 11, p.muted)
                        }
                        FeedText("Continue ›", 12, p.accent, FontWeight.SemiBold)
                    }
                }
                if (fixture && selectedEmail != null) FeedText("Selected fixture: $selectedEmail", 12, p.muted)
                if (loading) FeedText("Opening sign-in…", 12, p.muted)
                WatchPill(if (accounts.isEmpty()) "Choose account" else "Use another account", p.text, p.elevated) {
                    try {
                        launcher.launch(AccountManager.newChooseAccountIntent(
                            null, null, arrayOf("com.google"), null, null, null, null))
                    } catch (_: Exception) {
                        failed = true
                    }
                }
                if (failed) FeedText("Android account chooser is unavailable.", 12, p.muted)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                FeedText("Continue as " + selectedEmail.orEmpty(), 13, p.text, FontWeight.SemiBold)
                if (failed) FeedText("Sign-in could not be completed. No account was saved.", 12, p.muted)
                key(target) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
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
                    }, onRelease = { view -> view.stopLoading(); view.destroy() })
                }
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
