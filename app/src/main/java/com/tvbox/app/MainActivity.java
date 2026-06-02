package com.tvbox.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.Toast;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private WebView webView;
    private long lastBackTime = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // === WebViewClient：拦截所有 API 请求 ===
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.endsWith(".m3u8") || url.endsWith(".mp4") || url.endsWith(".flv")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")); }
                    catch (Exception e) { toast("未找到视频播放器"); }
                    return true;
                }
                if (!url.startsWith("http")) return false;
                if (url.contains("jiexi") || url.contains("jx.") || url.contains("parse")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception e) { view.loadUrl(url); }
                return true;
            }

            // 拦截 API 请求（包括 fetch、XHR）
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.d("TVBox", "shouldInterceptRequest: " + url);
                if (url.contains("/api.php/")) {
                    Log.d("TVBox", "拦截到 API 请求: " + url);
                    return doHttpRequest(url);
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        // === WebChromeClient ===
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.d("TVBoxJS", msg.message());
                return true;
            }

            private View customView;
            private CustomViewCallback customViewCallback;

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view; customViewCallback = callback;
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setContentView(view);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setContentView(webView);
                customViewCallback.onCustomViewHidden();
                customView = null;
            }
        });

        // 加载本地页面
        webView.loadUrl("file:///android_asset/index.html");
    }

    /**
     * 在后台线程发起 HTTP 请求，返回 WebResourceResponse
     */
    private WebResourceResponse doHttpRequest(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            is.close();
            conn.disconnect();

            byte[] bytes = baos.toByteArray();
            String raw = new String(bytes, "UTF-8");
            Log.d("TVBox", "API " + code + " 前100: " + raw.substring(0, Math.min(100, raw.length())));

            // 强制返回 application/json
            return new WebResourceResponse("application/json", "UTF-8", code,
                code >= 200 && code < 300 ? "OK" : "Error", null, new ByteArrayInputStream(bytes));

        } catch (Exception e) {
            Log.e("TVBox", "API 请求失败: " + e.getMessage());
            try {
                String err = "{\"code\":0,\"msg\":\"" + e.getMessage() + "\"}";
                return new WebResourceResponse("application/json", "UTF-8", 500, "Error", null, new ByteArrayInputStream(err.getBytes("UTF-8")));
            } catch (Exception ex) { return null; }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) { webView.goBack(); return true; }
            long now = System.currentTimeMillis();
            if (now - lastBackTime < 2000) finish();
            else { lastBackTime = now; Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show(); }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onPause() { super.onPause(); webView.onPause(); }
    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override protected void onDestroy() { super.onDestroy(); pool.shutdown(); webView.destroy(); }
}
