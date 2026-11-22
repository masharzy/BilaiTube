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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
private fun directSignIn() =
    Uri.parse("https://accounts.google.com/ServiceLogin").buildUpon()
        .appendQueryParameter("service", "youtube")
        .appendQueryParameter("passive", "true")
        .appendQueryParameter("continue", RETURN_URL)
        .build().toString()

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
    // Straight to Google's own sign-in page: email + password (or passkey), nothing in between.
    var url by remember { mutableStateOf<String?>(if (fixture) null else directSignIn()) }
    var cookies by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    var reason by remember { mutableStateOf<String?>(null) }

    fun select(email: String) {
        selectedEmail = email
        loading = true
        failed = false
        reason = null
        if (fixture) { loading = false; return }
        val manager = AccountManager.get(activity)
        val account = Account(email, "com.google")
        val tokenType = "weblogin:service=youtube&continue=" + Uri.encode(RETURN_URL)
        fun fallback(why: String) {
            android.util.Log.w("BilaiLogin", "one-tap sign-in unavailable: $why")
            reason = why
            url = hintedSignIn(email)
            loading = false
        }
        // attempt 0: may return a cached (already used) token -> invalidate and fetch a fresh one.
        // attempt 1/2: fresh token, or a retry right after the user tapped "Allow".
        fun request(attempt: Int) {
            try {
                manager.getAuthToken(account, tokenType, null, activity, { response ->
                    val bundle = runCatching { response.result }.getOrNull()
                    val token = bundle?.getString(AccountManager.KEY_AUTHTOKEN)
                    when {
                        token != null && attempt == 0 -> {
                            manager.invalidateAuthToken("com.google", token)
                            request(1)
                        }
                        token != null && trustedLoginUrl(token) -> {
                            manager.invalidateAuthToken("com.google", token)
                            url = token
                            loading = false
                        }
                        token != null -> fallback("unexpected token format")
                        attempt < 2 -> request(attempt + 1)
                        else -> fallback("Google did not return a sign-in token")
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                fallback(e.javaClass.simpleName)
            }
        }
        request(0)
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
                    Row(Modifier.fillMaxWidth().clickable { select(email) }
                        .semantics { contentDescription = "Continue as $email" }
                        .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).background(p.accentSoft, CircleShape),
                            contentAlignment = Alignment.Center) {
                            FeedText(email.take(1).uppercase(), 17, p.accent, FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            FeedText(email, 13, p.text, FontWeight.Medium)
                            FeedText("Continue as $email", 11, p.accent, FontWeight.SemiBold)
                        }
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                        FeedText(selectedEmail?.take(1)?.uppercase() ?: "G", 15, p.accent, FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        FeedText(selectedEmail ?: "Sign in with Google", 13, p.text, FontWeight.SemiBold)
                        FeedText("Your password goes only to Google. BilaiTube keeps the session encrypted on this phone.", 11, p.muted)
                    }
                }
                if (failed) FeedText("Sign-in could not be completed. No account was saved.", 12, p.muted)
                key(target) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.userAgentString = settings.userAgentString
                                .replace("; wv", "").replace(Regex("Version/\\S+ "), "")
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    request.isForMainFrame && !trustedLoginUrl(request.url.toString())

                                override fun onPageFinished(view: WebView, location: String) {
                                    val email = selectedEmail
                                    if (email != null && Uri.parse(location).host.orEmpty().startsWith("accounts.google.")) {
                                        view.evaluateJavascript(autofillScript(email), null)
                                    }
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

private val googleHost = Regex("^([a-z0-9-]+\\.)*google\\.[a-z]{2,3}(\\.[a-z]{2})?$")

private fun trustedLoginUrl(url: String): Boolean {
    val uri = Uri.parse(url)
    val host = uri.host.orEmpty().lowercase()
    return uri.scheme == "https" && (googleHost.matches(host) || host == "youtube.com" ||
        host.endsWith(".youtube.com") || host == "gstatic.com" || host.endsWith(".gstatic.com"))
}

/** Fills Google's identifier step with the chosen device account and presses Next. */
private fun autofillScript(email: String): String {
    val quoted = org.json.JSONObject.quote(email)
    return "(function(e){var i=document.querySelector('input[type=email]');" +
        "if(i&&!i.value){i.focus();i.value=e;i.dispatchEvent(new Event('input',{bubbles:true}));" +
        "i.dispatchEvent(new Event('change',{bubbles:true}));" +
        "setTimeout(function(){var n=document.querySelector('#identifierNext button')||" +
        "document.querySelector('#identifierNext');if(n)n.click();},350);}})(" + quoted + ")"
}
