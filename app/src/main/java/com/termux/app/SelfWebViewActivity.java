package com.termux.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.lang.ref.WeakReference;
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
    private static final String ERROR_URL = "file:///android_asset/se_error.html";
    private static final long POLL_TIMEOUT_MS = 30_000L;
    private static final int FILE_CHOOSER_REQUEST = 0x51CE;

    // ---- P1-① 自愈静态状态（参考报告 §四·P1）----
    /** 存活实例弱引用：TermuxActivity.onResume/onNewIntent 据此判断是否需要补拉。 */
    public static volatile WeakReference<SelfWebViewActivity> sAlive = null;
    /** 用户经返回键（或错误页「打开终端」）主动退回终端时置 true，避免 onResume 死循环拉回。 */
    public static volatile boolean sUserExited = false;
    /** 已发起 startActivity 但实例尚未 onCreate —— 消除 onResume 早于实例创建的双拉竞态。 */
    public static volatile boolean sLaunchPending = false;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean navigated = false;
    private volatile boolean checking = false;
    private long mPollStartMs = 0L;
    private boolean mErrorShown = false;

    /** 轮询：两个服务都返回任意 HTTP 响应即视为就绪（404 也算服务在）。 */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollHandler.postDelayed(this, 1000);
            if (navigated || checking) return;
            // P1-②：超过 30s 仍探测失败 → 错误页（重试/打开终端）；轮询不停，服务恢复仍自动进前端
            if (!mErrorShown && mPollStartMs > 0
                    && SystemClock.elapsedRealtime() - mPollStartMs > POLL_TIMEOUT_MS) {
                mErrorShown = true;
                runOnUiThread(() -> {
                    if (!navigated && webView != null) webView.loadUrl(ERROR_URL);
                });
            }
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
        // P1-①：登记存活实例、清除“启动中”挂起标志（供 TermuxActivity.ensureSelfWebView 判活）
        sAlive = new WeakReference<>(this);
        sLaunchPending = false;
        webView = new WebView(this);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);

        // P1-②：错误页「打开终端」（JS 桥）——置退出标记，避免退回终端后被 onResume 立即补拉
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void openTerminal() {
                sUserExited = true;
                runOnUiThread(SelfWebViewActivity.this::finish);
            }
        }, "SeBridge");

        // P1-②：替换裸 WebViewClient —— 主文档加载失败时解锁一次性导航并回加载页重试，
        //         加载页成功呈现时重置 30s 超时窗与错误页标志
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl == null || failingUrl.equals(view.getUrl())) handleMainFrameError();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) handleMainFrameError();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (LOADING_URL.equals(url)) {
                    mErrorShown = false;
                    mPollStartMs = SystemClock.elapsedRealtime();
                }
            }
        });

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

        // 显式 URL 直达（intent data / extra "url"）> 状态恢复（P1-②）> 加载动画轮询流
        Intent intent = getIntent();
        String url = null;
        if (intent != null) {
            if (intent.getDataString() != null) url = intent.getDataString();
            else if (intent.getStringExtra("url") != null) url = intent.getStringExtra("url");
        }
        if (url == null && savedInstanceState != null) {
            // P1-②：进程回收后恢复原导航目标（仅恢复已就绪的远端页；加载页/错误页走轮询流）
            String restored = savedInstanceState.getString("url");
            if (restored != null && !restored.isEmpty()
                    && !LOADING_URL.equals(restored) && !ERROR_URL.equals(restored)
                    && !"about:blank".equals(restored)) {
                url = restored;
            }
        }
        if (url != null) {
            navigated = true;
            webView.loadUrl(url);
        } else {
            mPollStartMs = SystemClock.elapsedRealtime();
            webView.loadUrl(LOADING_URL);
            pollHandler.post(pollRunnable);
        }
    }

    /** P1-②：主文档加载失败 —— 解锁一次性导航、重置超时窗，回到加载页继续轮询重试。 */
    private void handleMainFrameError() {
        navigated = false;
        mErrorShown = false;
        mPollStartMs = SystemClock.elapsedRealtime();
        if (webView != null) {
            String cur = webView.getUrl();
            if (cur == null || (!LOADING_URL.equals(cur) && !ERROR_URL.equals(cur))) {
                webView.loadUrl(LOADING_URL);
            }
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            // P1-①：用户主动退回终端 → 置标记，防止 TermuxActivity.onResume 立即补拉（防死循环）
            sUserExited = true;
            super.onBackPressed();
        }
    }

    /** P1-③：singleTop 下重复 am start 幂等到达；未导航时重置超时窗继续轮询。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!navigated) {
            mPollStartMs = SystemClock.elapsedRealtime();
            mErrorShown = false;
        }
    }

    /** P1-②：保存导航目标，进程回收后恢复（配合 onCreate 的 restored 分支）。 */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null && webView.getUrl() != null) {
            outState.putString("url", webView.getUrl());
        }
    }

    @Override
    protected void onDestroy() {
        pollHandler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (webView != null) { webView.destroy(); webView = null; }
        if (sAlive != null && sAlive.get() == this) sAlive = null;
        sLaunchPending = false;
        super.onDestroy();
    }
}
