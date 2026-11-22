package org.schabi.newpipe.views;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;

public class YouTubeLoginWebViewActivity extends BaseLoginWebViewActivity {

    private static final String TAG = "YouTubeLogin";
    private static final int REQUEST_PICK_ACCOUNT = 4711;
    private static final String SIGN_IN_URL = "https://www.youtube.com/signin";
    private static final String CONTINUE_URL =
            "https://www.youtube.com/signin?action_handle_signin=true&app=desktop&next=%2F";

    private boolean hasLoaded = false;

    @Override
    protected String getLoginUrl() {
        return SIGN_IN_URL;
    }

    /**
     * Lets the user pick one of the Google accounts already saved on the device, so no e-mail or
     * password has to be typed. If that is not possible the regular web sign-in is shown.
     */
    @Override
    protected void loadLoginUrl() {
        try {
            final Intent chooser = AccountManager.newChooseAccountIntent(
                    null, null, new String[]{"com.google"}, null, null, null, null);
            startActivityForResult(chooser, REQUEST_PICK_ACCOUNT);
        } catch (final Exception e) {
            Log.w(TAG, "Account chooser unavailable", e);
            webView.loadUrl(SIGN_IN_URL);
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
                                    final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ACCOUNT) {
            return;
        }
        final String name = data == null ? null : data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
        final String type = data == null ? null : data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
        if (resultCode != RESULT_OK || name == null || type == null) {
            webView.loadUrl(SIGN_IN_URL);
            return;
        }
        signInWithDeviceAccount(new Account(name, type));
    }

    private void signInWithDeviceAccount(final Account account) {
        final String tokenType = "weblogin:service=youtube&continue=" + Uri.encode(CONTINUE_URL);
        try {
            AccountManager.get(this).getAuthToken(account, tokenType, (Bundle) null, this,
                    (AccountManagerFuture<Bundle> future) -> {
                        String url = null;
                        try {
                            url = future.getResult().getString(AccountManager.KEY_AUTHTOKEN);
                        } catch (final Exception e) {
                            Log.w(TAG, "One-tap sign-in failed, using the web form", e);
                        }
                        if (url != null && url.startsWith("http")) {
                            webView.loadUrl(url);
                        } else {
                            webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube"
                                    + "&Email=" + Uri.encode(account.name)
                                    + "&continue=" + Uri.encode(CONTINUE_URL));
                        }
                    }, new Handler(Looper.getMainLooper()));
        } catch (final Exception e) {
            Log.w(TAG, "Could not request a sign-in token", e);
            webView.loadUrl(SIGN_IN_URL);
        }
    }

    @Override
    protected String getSuccessCookieIndicator() {
        return "SID=";
    }

    @Override
    protected void configureWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36");
    }

    @Override
    protected WebViewClient createWebViewClient() {
        return new YouTubeWebViewClient();
    }

    @Override
    protected void handleSuccessfulLogin(String cookies) {
        // YouTube handles success differently - through shouldInterceptRequest
    }

    private class YouTubeWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (!hasLoaded && cookies != null && cookies.contains(getSuccessCookieIndicator())) {
                hasLoaded = true;
                webView.loadUrl("https://www.youtube.com/watch?v=09839DpTctU");
            }
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.contains("googlevideo.com/videoplayback")) {
                Intent intent = new Intent();
                String cookies = CookieManager.getInstance().getCookie("https://music.youtube.com/watch");
                intent.putExtra("cookies", cookies);
                finishWithResult(intent);
            }
            return null;
        }
    }
}
