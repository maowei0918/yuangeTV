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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private long lastBackTime = 0;
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 全屏沉浸式
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

        // 硬件加速
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // WebViewClient
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // 视频链接用外部播放器
                if (url.endsWith(".m3u8") || url.endsWith(".mp4") || url.endsWith(".flv")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(url), "video/*");
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "未找到视频播放器", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                // 解析页面在 WebView 内打开
                if (url.contains("jiexi") || url.contains("jx.") || url.contains("parse")) {
                    return false;
                }
                // 其他链接外部浏览器
                if (!url.startsWith("http")) return false;
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    view.loadUrl(url);
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
            }
        });

        // WebChromeClient - 支持全屏
        webView.setWebChromeClient(new WebChromeClient() {
            private View customView;
            private WebChromeClient.CustomViewCallback customViewCallback;

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
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

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // 可以在这里更新加载进度
            }
        });

        // JS Bridge
        webView.addJavascriptInterface(new JsBridge(), "Android");

        // 加载本地页面
        webView.loadUrl("file:///android_asset/index.html");
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
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "未找到视频播放器", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void httpGet(final String url, final String callbackId) {
            httpExecutor.execute(() -> {
                String responseData = null;
                String errorMsg = null;
                int httpCode = 0;

                try {
                    URL reqUrl = new URL(url);
                    HttpURLConnection conn = (HttpURLConnection) reqUrl.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");

                    httpCode = conn.getResponseCode();
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                            httpCode >= 200 && httpCode < 300 ? conn.getInputStream() : conn.getErrorStream(),
                            "UTF-8"
                        )
                    );
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    reader.close();
                    conn.disconnect();

                    responseData = Base64.encodeToString(sb.toString().getBytes("UTF-8"), Base64.NO_WRAP);
                } catch (Exception e) {
                    errorMsg = e.getMessage();
                    if (errorMsg == null) errorMsg = "Unknown error";
                }

                final String b64Data = responseData;
                final String err = errorMsg;
                final int code = httpCode;

                mainHandler.post(() -> {
                    StringBuilder js = new StringBuilder();
                    js.append("if(window._httpCallbacks['").append(callbackId).append("']){");
                    if (err != null) {
                        js.append("window._httpCallbacks['").append(callbackId).append("']({error:'").append(err.replace("'", "\\'")).append("',status:").append(code).append("});");
                    } else {
                        js.append("window._httpCallbacks['").append(callbackId).append("']({data:'").append(b64Data).append("',status:").append(code).append("});");
                    }
                    js.append("delete window._httpCallbacks['").append(callbackId).append("'];");
                    js.append("}");
                    webView.evaluateJavascript(js.toString(), null);
                });
            });
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 如果 WebView 在全屏视频模式，先退出全屏
            // 如果 WebView 可以后退，先后退
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            // 双击退出
            long now = System.currentTimeMillis();
            if (now - lastBackTime < 2000) {
                finish();
            } else {
                lastBackTime = now;
                Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        httpExecutor.shutdown();
        webView.destroy();
    }
}
