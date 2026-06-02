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
import android.view.MotionEvent;
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
    private float startX = 0;
    private static final float SWIPE_THRESHOLD = 80;

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
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("intent:")) return true;
                String lower = url.toLowerCase();
                if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv")
                    || lower.contains(".avi") || lower.contains(".mkv") || lower.contains(".mov")
                    || lower.contains(".webm") || lower.contains(".ts")
                    || lower.contains("m3u8?") || lower.contains("video") || lower.contains("stream")) {
                    openVideo(url, "");
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("/api.php/")) return doHttpRequest(url);
                return super.shouldInterceptRequest(view, request);
            }
        });

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

        // Bridge
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void httpGet(final String url, final String callbackId) {
                pool.execute(new Runnable() {
                    public void run() {
                        try {
                            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                            conn.setRequestMethod("GET");
                            conn.setConnectTimeout(15000);
                            conn.setReadTimeout(15000);
                            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                            int code = conn.getResponseCode();
                            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            byte[] buf = new byte[4096];
                            int len;
                            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                            is.close(); conn.disconnect();
                            String raw = new String(baos.toByteArray(), "UTF-8");
                            String escaped = raw.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n").replace("\r","\\r");
                            final String js = "if(window._httpCallbacks['"+callbackId+"']){window._httpCallbacks['"+callbackId+"']('"+escaped+"');delete window._httpCallbacks['"+callbackId+"'];}";
                            mainHandler.post(new Runnable() { public void run() { webView.evaluateJavascript(js, null); } });
                        } catch (final Exception e) {
                            final String js = "if(window._httpCallbacks['"+callbackId+"']){window._httpCallbacks['"+callbackId+"']('ERROR:"+e.getMessage().replace("'","\\'")+"');delete window._httpCallbacks['"+callbackId+"'];}";
                            mainHandler.post(new Runnable() { public void run() { webView.evaluateJavascript(js, null); } });
                        }
                    }
                });
            }

            @JavascriptInterface
            public void openVideo(final String url, final String title) {
                mainHandler.post(new Runnable() {
                    public void run() {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(Uri.parse(url), "video/*");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        } catch (Exception e) { toast("未找到视频播放器"); }
                    }
                });
            }
        }, "NativeHttp");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                openVideo(url, "");
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void openVideo(String url, String title) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), "video/*");
            startActivity(intent);
        } catch (Exception e) { toast("未找到视频播放器"); }
    }

    // 侧滑返回
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) startX = event.getX();
        else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (startX < 60 && event.getX() - startX > SWIPE_THRESHOLD) { handleBack(); return true; }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void onBackPressed() { handleBack(); }

    private void handleBack() {
        webView.evaluateJavascript(
            "(function(){" +
            "var p=document.getElementById('playerPage');if(p&&p.classList.contains('show')){closePlayer();return 'player';}" +
            "var d=document.getElementById('detailPage');if(d&&d.classList.contains('show')){closeDetail();return 'detail';}" +
            "var m=document.getElementById('domainModal');if(m&&m.classList.contains('show')){closeDomainManager();return 'modal';}" +
            "return 'none';})()",
            new ValueCallback<String>() {
                public void onReceiveValue(String v) {
                    String page = v.replace("\"","");
                    if ("none".equals(page)) {
                        long now = System.currentTimeMillis();
                        if (now - lastBackTime < 2000) finish();
                        else { lastBackTime = now; Toast.makeText(MainActivity.this, "再按一次退出", Toast.LENGTH_SHORT).show(); }
                    }
                }
            });
    }

    private WebResourceResponse doHttpRequest(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            is.close(); conn.disconnect();
            return new WebResourceResponse("application/json", "UTF-8", code,
                code >= 200 && code < 300 ? "OK" : "Error", null, new ByteArrayInputStream(baos.toByteArray()));
        } catch (Exception e) {
            try { String err="{\"code\":0,\"msg\":\""+e.getMessage()+"\"}";
                return new WebResourceResponse("application/json","UTF-8",500,"Error",null,new ByteArrayInputStream(err.getBytes("UTF-8")));
            } catch (Exception ex) { return null; }
        }
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    @Override protected void onPause() { super.onPause(); if(webView!=null) webView.onPause(); }
    @Override protected void onResume() { super.onResume(); if(webView!=null) webView.onResume(); }
    @Override protected void onDestroy() { super.onDestroy(); pool.shutdown(); if(webView!=null) webView.destroy(); }
}
