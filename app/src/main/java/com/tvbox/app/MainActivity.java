package com.tvbox.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
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
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.endsWith(".m3u8") || url.endsWith(".mp4") || url.endsWith(".flv")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(url), "video/*");
                    try { startActivity(intent); } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "未找到视频播放器", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                if (!url.startsWith("http")) return false;
                if (url.contains("jiexi") || url.contains("jx.") || url.contains("parse")) return false;
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    view.loadUrl(url);
                }
                return true;
            }

            // 拦截 API 请求，用 Java 端 HTTP 请求替代
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // 只拦截 maccms API 请求
                if (url.contains("/api.php/provide/vod/")) {
                    return interceptApiRequest(url);
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            private View customView;
            private WebChromeClient.CustomViewCallback customViewCallback;

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
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

        webView.addJavascriptInterface(new JsBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
    }

    /**
     * 拦截 API 请求：在 Java 端发起 HTTP 请求，绕过 CORS 限制
     */
    private WebResourceResponse interceptApiRequest(String url) {
        try {
            URL reqUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) reqUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();

            // 读取全部响应
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = stream.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            stream.close();
            conn.disconnect();

            byte[] responseBytes = baos.toByteArray();
            String responseStr = new String(responseBytes, "UTF-8");

            // 调试日志
            android.util.Log.d("TVBox", "拦截 API: " + url + " -> HTTP " + code + ", 长度=" + responseBytes.length);
            if (responseStr.length() > 0) {
                android.util.Log.d("TVBox", "响应前100: " + responseStr.substring(0, Math.min(100, responseStr.length())));
            }

            // 返回 WebResourceResponse，强制 Content-Type 为 application/json
            InputStream responseStream = new ByteArrayInputStream(responseBytes);
            return new WebResourceResponse(
                "application/json",
                "UTF-8",
                code,
                code >= 200 && code < 300 ? "OK" : "Error",
                null,
                responseStream
            );

        } catch (Exception e) {
            android.util.Log.e("TVBox", "拦截 API 失败: " + e.getMessage());
            // 返回空响应
            try {
                String errorJson = "{\"code\":0,\"msg\":\"" + e.getMessage() + "\",\"list\":[]}";
                return new WebResourceResponse(
                    "application/json", "UTF-8",
                    500, "Internal Error", null,
                    new ByteArrayInputStream(errorJson.getBytes("UTF-8"))
                );
            } catch (Exception ex) {
                return null;
            }
        }
    }

    // JS Bridge 类
    public class JsBridge {
        @android.webkit.JavascriptInterface
        public void toast(String msg) {
            mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @android.webkit.JavascriptInterface
        public void openPlayer(String url) {
            mainHandler.post(() -> {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse(url), "video/*");
                try { startActivity(intent); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "未找到视频播放器", Toast.LENGTH_SHORT).show(); }
            });
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) { webView.goBack(); return true; }
            long now = System.currentTimeMillis();
            if (now - lastBackTime < 2000) { finish(); }
            else { lastBackTime = now; Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show(); }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() { super.onPause(); webView.onPause(); }

    @Override
    protected void onResume() { super.onResume(); webView.onResume(); }

    @Override
    protected void onDestroy() { super.onDestroy(); httpExecutor.shutdown(); webView.destroy(); }
}
