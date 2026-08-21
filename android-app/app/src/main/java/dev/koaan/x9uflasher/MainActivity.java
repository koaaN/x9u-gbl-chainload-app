package dev.koaan.x9uflasher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebView webView;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#080B12"));
        getWindow().setNavigationBarColor(Color.parseColor("#080B12"));

        webView = new WebView(this);
        WebView.setWebContentsDebuggingEnabled(false);
        webView.setBackgroundColor(Color.parseColor("#080B12"));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.removeJavascriptInterface("searchBoxJavaBridge_");
        webView.removeJavascriptInterface("accessibility");
        webView.removeJavascriptInterface("accessibilityTraversal");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        webView.addJavascriptInterface(new Bridge(), "X9U");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("X9U");
            webView.destroy();
        }
        super.onDestroy();
    }

    private void deliver(String callback, String result) {
        if (callback == null || !callback.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            return;
        }
        runOnUiThread(() -> webView.evaluateJavascript(
                callback + "(" + JSONObject.quote(result) + ")", null));
    }

    public final class Bridge {
        @JavascriptInterface
        public String deviceInfo() {
            return RootOps.deviceInfo(MainActivity.this);
        }

        @JavascriptInterface
        public void status(String callback) {
            worker.execute(() -> deliver(callback, RootOps.status(MainActivity.this)));
        }

        @JavascriptInterface
        public void enableRoot(String callback) {
            worker.execute(() -> deliver(callback, RootOps.enableRoot(MainActivity.this)));
        }

        @JavascriptInterface
        public void flash(String confirmation, String callback) {
            worker.execute(() -> deliver(callback,
                    RootOps.flash(MainActivity.this, confirmation)));
        }

        @JavascriptInterface
        public void uninstall(String confirmation, String callback) {
            worker.execute(() -> deliver(callback,
                    RootOps.uninstall(MainActivity.this, confirmation)));
        }

        @JavascriptInterface
        public void rebootFastboot(String callback) {
            worker.execute(() -> deliver(callback,
                    RootOps.rebootFastboot(MainActivity.this)));
        }

        @JavascriptInterface
        public void copy(String value) {
            runOnUiThread(() -> {
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("X9 Ultra flash log", value));
                Toast.makeText(MainActivity.this, "Log copied", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void openUrl(String value) {
            if (!("https://github.com/koaaN".equals(value)
                    || "https://github.com/koaaN/x9u-preload-builder".equals(value))) {
                return;
            }
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value)));
                } catch (Throwable ignored) {
                    Toast.makeText(MainActivity.this, "No browser is available", Toast.LENGTH_SHORT)
                            .show();
                }
            });
        }

        @JavascriptInterface
        public void setLightMode(boolean enabled) {
            runOnUiThread(() -> {
                int color = Color.parseColor(enabled ? "#F3F5FA" : "#080B12");
                getWindow().setStatusBarColor(color);
                getWindow().setNavigationBarColor(color);
                getWindow().getDecorView().setSystemUiVisibility(enabled
                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        : 0);
                if (webView != null) {
                    webView.setBackgroundColor(color);
                }
            });
        }
    }
}
