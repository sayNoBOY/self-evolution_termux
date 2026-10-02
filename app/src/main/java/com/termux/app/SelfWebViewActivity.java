package com.termux.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智衍 SelfEvolution —— 应用首屏内嵌浏览器。
 *
 * 流程：冷启动由 TermuxActivity 自动拉起 → 加载 assets/se_loading.html
 * （品牌加载动画）→ 后台每秒探测后端:3000 与前端:5173 → 双服务均响应后
 * 自动跳转 http://localhost:5173。返回键关闭本页回到终端。
 *
 * 同时修复 WebView 两大经典问题：
 *  - 上传（&lt;input type=file&gt;）点击无反应 → 实现 WebChromeClient#onShowFileChooser
 *    + startActivityForResult 拉起系统文件选择器；
 *  - 下载无响应 → setDownloadListener 走系统 DownloadManager 存到「下载」目录。
 */
public class SelfWebViewActivity extends Activity {

    public static final String FRONTEND_URL = "http://localhost:5173";
    private static final String BACKEND_PROBE = "http://localhost:3000/";
    private static final String FRONTEND_PROBE = "http://localhost:5173/";
    private static final String LOADING_URL = "file:///android_asset/se_loading.html";
    private static final int FILE_CHOOSER_REQUEST = 0x51CE;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean navigated = false;
    private volatile boolean checking = false;

    /** 轮询：两个服务都返回任意 HTTP 响应即视为就绪（404 也算服务在）。 */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollHandler.postDelayed(this, 1000);
            if (navigated || checking) return;
            checking = true;
            io.execute(() -> {
                boolean front = probe(FRONTEND_PROBE);
                boolean back = probe(BACKEND_PROBE);
                checking = false;
                if (front && back && !navigated) {
                    navigated = true;
                    runOnUiThread(() -> webView.loadUrl(FRONTEND_URL));
                }
            });
        }
    };

    private boolean probe(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(700);
            c.setReadTimeout(700);
            c.setRequestMethod("GET");
            return c.getResponseCode() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient());

        // ---- 修复①：上传（文件选择） ----
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    if (params != null && params.getAcceptTypes() != null
                            && params.getAcceptTypes().length > 0
                            && params.getAcceptTypes()[0] != null
                            && !params.getAcceptTypes()[0].isEmpty()) {
                        intent.setType(params.getAcceptTypes()[0]);
                    }
                } catch (Exception ignored) { }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(SelfWebViewActivity.this,
                            "无法打开文件选择器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        // ---- 修复②：下载（系统 DownloadManager → 「下载」目录） ----
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                String fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                if (mimeType != null && !mimeType.isEmpty()) req.setMimeType(mimeType);
                if (userAgent != null && !userAgent.isEmpty()) req.addRequestHeader("User-Agent", userAgent);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(req);
                Toast.makeText(this, "已开始下载: " + fileName, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        setContentView(webView);

        // 显式 URL 直达（intent data / extra "url"），否则进加载动画轮询流
        Intent intent = getIntent();
        String url = null;
        if (intent != null) {
            if (intent.getDataString() != null) url = intent.getDataString();
            else if (intent.getStringExtra("url") != null) url = intent.getStringExtra("url");
        }
        if (url != null) {
            navigated = true;
            webView.loadUrl(url);
        } else {
            webView.loadUrl(LOADING_URL);
            pollHandler.post(pollRunnable);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        pollHandler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
