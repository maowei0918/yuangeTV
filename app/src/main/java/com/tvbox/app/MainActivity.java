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

        // === 核心：拦截 fetch 请求 ===
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

            // 页面加载完成后，注入 fetch 拦截器
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectFetchInterceptor();
            }
        });

        // 控制台日志
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                android.util.Log.d("TVBoxJS", msg.message() + " -- line " + msg.lineNumber());
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

        webView.addJavascriptInterface(new JsBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
    }

    /**
     * 注入 JS 拦截器：覆盖原生 fetch，让 API 请求走 Bridge
     */
    private void injectFetchInterceptor() {
        String js = "(function() {" +
            "if (window._fetchPatched) return;" +
            "window._fetchPatched = true;" +
            "const _origFetch = window.fetch;" +
            "window.fetch = function(input, opts) {" +
            "  const url = typeof input === 'string' ? input : input.url;" +
            "  console.log('fetch拦截: ' + url);" +
            "  // 只拦截 API 请求" +
            "  if (url && url.includes('/api.php/provide/vod/')) {" +
            "    console.log('走Bridge: ' + url);" +
            "    return new Promise(function(resolve, reject) {" +
            "      const cbId = 'cb_' + Date.now() + '_' + Math.random().toString(36).substr(2,5);" +
            "      window._httpCallbacks = window._httpCallbacks || {};" +
            "      window._httpCallbacks[cbId] = function(result) {" +
            "        delete window._httpCallbacks[cbId];" +
            "        if (result.error) {" +
            "          reject(new Error(result.error));" +
            "        } else {" +
            "          const text = atob(result.data);" +
            "          console.log('Bridge响应前50: ' + text.substring(0,50));" +
            "          resolve(new Response(text, {status: result.status, headers: {'Content-Type': 'application/json'}}));" +
            "        }" +
            "      };" +
            "      setTimeout(function() {" +
            "        if (window._httpCallbacks[cbId]) {" +
            "          delete window._httpCallbacks[cbId];" +
            "          reject(new Error('请求超时'));" +
            "        }" +
            "      }, 15000);" +
            "      Android.httpGet(url, cbId);" +
            "    });" +
            "  }" +
            // 非 API 请求走原始 fetch
            "  return _origFetch.apply(this, arguments);" +
            "};" +
            "console.log('fetch拦截器已注入');" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    public class JsBridge {
        @JavascriptInterface
        public void toast(String msg) {
            mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void openPlayer(String url) {
            mainHandler.post(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "未找到视频播放器", Toast.LENGTH_SHORT).show(); }
            });
        }

        /**
         * JS 调用此方法发起 HTTP 请求，结果通过 evaluateJavascript 回调
         */
        @JavascriptInterface
        public void httpGet(final String url, final String callbackId) {
            httpExecutor.execute(() -> {
                String b64Data = null;
                String errorMsg = null;
                int httpCode = 0;

                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");

                    httpCode = conn.getResponseCode();
                    InputStream is = (httpCode >= 200 && httpCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                    is.close();
                    conn.disconnect();

                    String raw = new String(baos.toByteArray(), "UTF-8");
                    android.util.Log.d("TVBox", "HTTP " + httpCode + " [" + url + "] 前100: " + raw.substring(0, Math.min(100, raw.length())));
                    b64Data = Base64.encodeToString(raw.getBytes("UTF-8"), Base64.NO_WRAP);

                } catch (Exception e) {
                    errorMsg = e.getMessage();
                    if (errorMsg == null) errorMsg = "Unknown";
                    android.util.Log.e("TVBox", "HTTP 失败: " + errorMsg);
                }

                // 回调到 JS
                final String data = b64Data;
                final String err = errorMsg;
                final int code = httpCode;

                mainHandler.post(() -> {
                    StringBuilder js = new StringBuilder();
                    js.append("if(window._httpCallbacks['").append(callbackId).append("']){");
                    if (err != null) {
                        js.append("window._httpCallbacks['").append(callbackId).append("'](")
                          .append("{error:'").append(err.replace("'","\\'")).append("',status:").append(code).append("});");
                    } else {
                        js.append("window._httpCallbacks['").append(callbackId).append("'](")
                          .append("{data:'").append(data).append("',status:").append(code).append("});");
                    }
                    js.append("delete window._httpCallbacks['").append(callbackId).append("'];}");
                    webView.evaluateJavascript(js.toString(), null);
                });
            });
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
    @Override protected void onDestroy() { super.onDestroy(); httpExecutor.shutdown(); webView.destroy(); }
}
// Tue Jun  2 13:32:22 CST 2026
