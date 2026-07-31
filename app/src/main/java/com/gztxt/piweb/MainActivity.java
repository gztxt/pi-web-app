package com.gztxt.piweb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;

import android.app.DownloadManager;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Message;
import android.Manifest;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pi Web 安卓客户端 v2.0
 * 基于 github.com/agegr/pi-web 最新源码的配色与图标重构。
 *
 * 修复:
 *  - v1 黑屏根因: onReceivedError 显示错误页后 loadUrl("about:blank"),
 *    about:blank 的 onPageFinished 又把错误页隐藏 → 永久黑屏。
 *    v2 改为错误页直接渲染在 WebView 内(自定义 HTML),并用 pageError 状态机隔离。
 * 新增:
 *  - 服务器地址自定义(IPv4 / IPv6 方括号 / 域名),SharedPreferences 持久化
 *  - 启动 Splash、错误页自动重试倒计时、网络恢复自动重连、下拉刷新
 *  - 图片上传(pi-web 聊天附件)、桌面模式切换、可拖动的 π 菜单按钮
 *  - v2.5 服务器地址簿: 多条目管理(添加/编辑/删除)+ π 菜单顶部一键快切,
 *    旧地址自动迁移为首条;Pi Web / CCR 控制台等多服务共用一个壳
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_URL = "http://100.117.232.62:30141";
    private static final String APP_VERSION = "2.6";
    private static final String PREFS = "piweb_prefs";
    private static final String PREF_URL = "server_url";
    private static final String PREF_BOOK = "server_book";
    private static final String PREF_DESKTOP = "desktop_mode";
    private static final String PREF_HINT = "hint_shown";
    private static final String ERROR_BASE = "https://piweb.error/";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int AUTO_RETRY_SECONDS = 8;

    // pi-web 官方配色(暗色)
    private static final int C_BG = 0xFF1A1A1A;
    private static final int C_PANEL = 0xFF242424;
    private static final int C_BORDER = 0xFF3A3A3A;
    private static final int C_TEXT = 0xFFE8E8E8;
    private static final int C_MUTED = 0xFF9CA3AF;
    private static final int C_DIM = 0xFF6B7280;
    private static final int C_ACCENT = 0xFF60A5FA;

    private WebView webView;
    private ProgressBar progressBar;
    private View splashView;
    private TextView splashStatus;
    private TextView fab;
    private FrameLayout rootLayout;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String serverUrl = DEFAULT_URL;
    private boolean desktopMode = false;
    private String defaultUserAgent;

    private boolean firstLoadDone = false;   // 首次成功加载后不再显示 Splash
    private boolean inError = false;         // 当前处于错误页
    private boolean pageError = false;       // 本次导航是否出错(防 onPageFinished 竞态)
    private int countdown = AUTO_RETRY_SECONDS;

    private ValueCallback<Uri[]> uploadCallback;

    // v2.4 诊断状态
    private boolean diagPending = false;   // 错误页渲染完成后自动诊断
    private boolean diagRunning = false;   // 防止并发诊断
    private TextView diagDialogText;       // 菜单诊断对话框实时输出

    // v2.6 下载功能
    private static final int REQ_STORAGE = 1002;
    private String pendingDownloadUrl, pendingDownloadName, pendingDownloadMime;

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (!inError) return;
            countdown--;
            if (countdown <= 0) {
                AppLog.i("Retry", "倒计时结束,自动重试");
                loadPiWeb("auto");
                return;
            }
            webView.evaluateJavascript(
                "(function(){var e=document.getElementById('cd');if(e)e.textContent='" + countdown + "';})();",
                null);
            handler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            boolean up = info != null && info.isConnected();
            AppLog.i("Net", "网络状态变化: " + (up ? "已连接(" + info.getTypeName() + ")" : "断开"));
            if (inError && up) {
                toast("网络已恢复,重新连接…");
                loadPiWeb("net");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl = prefs.getString(PREF_URL, DEFAULT_URL);
        desktopMode = prefs.getBoolean(PREF_DESKTOP, false);
        migrateBook();

        AppLog.init(getApplicationContext());
        AppLog.installCrashHandler();
        AppLog.i("App", "==== 启动 v" + APP_VERSION + " | Android " + Build.VERSION.RELEASE
            + " (API " + Build.VERSION.SDK_INT + ") | " + Build.MANUFACTURER + " " + Build.MODEL + " ====");
        AppLog.i("App", "地址: " + serverUrl + " | 桌面模式: " + desktopMode);
        AppLog.i("Book", "地址簿就绪: " + loadBook().size() + " 条");

        // 刘海屏延伸到内容区(API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        buildLayout();
        setupWebView();
        setupFab();

        // 网络恢复自动重连
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(networkReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(networkReceiver, filter);
        }

        if (!prefs.getBoolean(PREF_HINT, false)) {
            toast("提示:π 按钮可刷新/改地址,支持拖动位置");
            prefs.edit().putBoolean(PREF_HINT, true).apply();
        }

        loadPiWeb("launch");
    }

    // ---------------------------------------------------------------- 布局

    private void buildLayout() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(C_BG);

        // WebView(v2.1: 移除下拉刷新,避免与页面滚动冲突;刷新走 π 菜单)
        webView = new WebView(this);
        webView.setBackgroundColor(C_BG);
        rootLayout.addView(webView,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 顶部加载进度条(3dp)
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(3));
        progressParams.gravity = Gravity.TOP;
        rootLayout.addView(progressBar, progressParams);

        // Splash 遮罩(首次加载时显示,避免黑屏)
        splashView = buildSplash();
        rootLayout.addView(splashView,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout,
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View buildSplash() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(C_BG);
        layout.setClickable(true); // 吃掉触摸,避免误操作 WebView

        TextView logo = new TextView(this);
        logo.setText("π");
        logo.setTextColor(C_TEXT);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams logoParams =
            new LinearLayout.LayoutParams(dp(132), dp(132));
        logoParams.bottomMargin = dp(20);
        logo.setBackground(new RoundRectDrawable(C_PANEL, dp(30)));
        layout.addView(logo, logoParams);

        TextView name = new TextView(this);
        name.setText("Pi Web");
        name.setTextColor(C_TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        layout.addView(name);

        TextView sub = new TextView(this);
        sub.setText("pi coding agent · web workspace");
        sub.setTextColor(C_DIM);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(6);
        subParams.bottomMargin = dp(28);
        layout.addView(sub, subParams);

        ProgressBar spinner = new ProgressBar(this);
        layout.addView(spinner, new LinearLayout.LayoutParams(dp(30), dp(30)));

        splashStatus = new TextView(this);
        splashStatus.setText("正在连接…");
        splashStatus.setTextColor(C_MUTED);
        splashStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        splashStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(16);
        layout.addView(splashStatus, statusParams);

        return layout;
    }

    private void setupFab() {
        fab = new TextView(this);
        fab.setText("π");
        fab.setTextColor(C_TEXT);
        fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        fab.setTypeface(Typeface.DEFAULT_BOLD);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(new RoundRectDrawable(0xCC242424, dp(24)));
        fab.setAlpha(0.45f);

        FrameLayout.LayoutParams fabParams =
            new FrameLayout.LayoutParams(dp(48), dp(48));
        fabParams.gravity = Gravity.BOTTOM | Gravity.START;
        fabParams.leftMargin = dp(14);
        fabParams.bottomMargin = dp(90);
        rootLayout.addView(fab, fabParams);

        // 拖动 + 点击
        fab.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, startX, startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = v.getX();
                        startY = v.getY();
                        moved = false;
                        v.setAlpha(0.95f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true;
                        float nx = startX + dx;
                        float ny = startY + dy;
                        int maxX = rootLayout.getWidth() - v.getWidth();
                        int maxY = rootLayout.getHeight() - v.getHeight();
                        v.setX(Math.max(0, Math.min(nx, maxX)));
                        v.setY(Math.max(0, Math.min(ny, maxY)));
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setAlpha(0.45f);
                        if (!moved) openMenu();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(0.45f);
                        return true;
                }
                return false;
            }
        });
    }

    // -------------------------------------------------------------- WebView

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // pi-web 自带移动端响应式布局: 按 viewport 渲染,不缩放整页
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false); // 内网/Tailscale 私有地址,避免误拦截
        }
        // v2.6 支持 target=_blank 新窗口
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        defaultUserAgent = s.getUserAgentString();
        applyUserAgent();

        // v2.6 下载监听器
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                AppLog.i("Download", "开始 url=" + url + " mime=" + mimetype);
                final String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                           != PackageManager.PERMISSION_GRANTED) {
                    pendingDownloadUrl = url;
                    pendingDownloadName = fileName;
                    pendingDownloadMime = mimetype;
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                    return;
                }
                enqueueDownload(url, fileName, mimetype);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null) return false;
                switch (scheme) {
                    case "piweb":
                        String host = uri.getHost();
                        if ("retry".equals(host)) loadPiWeb("manual");
                        else if ("settings".equals(host)) openUrlDialog();
                        else if ("diag".equals(host)) runDiagnostics();
                        return true;
                    case "http":
                    case "https":
                    case "about":
                    case "data":
                        return false;
                    default:
                        // mailto/tel 等交给系统
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        } catch (Exception ignored) {
                        }
                        return true;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageError = false;
                if (isRealPage(url)) {
                    AppLog.i("Page", "开始加载 " + url);
                    progressBar.setVisibility(View.VISIBLE);
                    if (!firstLoadDone) {
                        splashStatus.setText("正在连接 " + briefHost(url) + " …");
                        splashView.setVisibility(View.VISIBLE);
                    }
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                // 错误页渲染完成 → 触发自动诊断(v2.4)
                if (url.startsWith(ERROR_BASE)) {
                    if (diagPending) {
                        diagPending = false;
                        runDiagnostics();
                    }
                    return;
                }
                // 关键修复: 只有真实页面成功加载才切换状态,
                // 错误页(piweb.error)与中间页不会覆盖错误状态
                if (!pageError && isRealPage(url)) {
                    AppLog.i("Page", "加载完成 " + url);
                    boolean wasFirst = !firstLoadDone;
                    firstLoadDone = true;
                    inError = false;
                    handler.removeCallbacksAndMessages(null);
                    splashView.setVisibility(View.GONE);
                    if (wasFirst) webView.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (!request.isForMainFrame()) return;
                pageError = true;
                CharSequence desc = error.getDescription();
                AppLog.e("Load", "主框架加载失败 code=" + error.getErrorCode()
                    + " desc=" + desc + " url=" + request.getUrl());
                showErrorPage("网络错误 " + error.getErrorCode()
                    + (desc != null ? " · " + desc : ""));
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (!request.isForMainFrame()) return;
                int code = errorResponse.getStatusCode();
                if (code >= 500) {
                    pageError = true;
                    AppLog.e("Load", "HTTP " + code + " url=" + request.getUrl());
                    showErrorPage("服务器错误 HTTP " + code);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress >= 100) progressBar.setVisibility(View.GONE);
            }

            // pi-web 聊天图片附件(<input type="file" accept="image/*">)
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                AppLog.i("File", "打开文件选择器");
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.setType("image/*");
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    uploadCallback = null;
                    toast("无法打开文件选择器");
                    return false;
                }
                return true;
            }

            // v2.6 支持 target="_blank" / window.open 新窗口
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                final WebView child = new WebView(view.getContext());
                child.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        String target = request.getUrl().toString();
                        AppLog.i("Window", "target=_blank → 主视图加载: " + target);
                        webView.loadUrl(target);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(child);
                resultMsg.sendToTarget();
                return true;
            }
        });
    }

    private void applyUserAgent() {
        WebSettings s = webView.getSettings();
        String ua = defaultUserAgent;
        if (desktopMode) {
            ua = ua.replace(" Mobile", "")
                .replace(" wv) ", ") ")
                .replace("Android ", "");
        }
        s.setUserAgentString(ua + " PiWeb/" + APP_VERSION);
        s.setLoadWithOverviewMode(desktopMode);
        s.setUseWideViewPort(true);
    }

    // ---------------------------------------------------------------- 加载

    private void loadPiWeb(String trigger) {
        handler.removeCallbacksAndMessages(null);
        inError = false;
        pageError = false;
        AppLog.i("Load", "加载 " + serverUrl + " (trigger=" + trigger + ")");
        if (!firstLoadDone) {
            splashStatus.setText("正在连接 " + briefHost(serverUrl) + " …");
            splashView.setVisibility(View.VISIBLE);
        }
        webView.loadUrl(serverUrl);
    }

    private void showErrorPage(String detail) {
        inError = true;
        handler.removeCallbacksAndMessages(null);
        AppLog.w("Page", "错误页: " + detail + " | url=" + serverUrl);
        diagPending = true;
        progressBar.setVisibility(View.GONE);
        splashView.setVisibility(View.GONE);

        String html = errorHtml(serverUrl, detail);
        webView.loadDataWithBaseURL(ERROR_BASE, html, "text/html", "UTF-8", null);

        // 自动重试倒计时
        countdown = AUTO_RETRY_SECONDS;
        handler.postDelayed(countdownTick, 1000);
    }

    private String errorHtml(String url, String detail) {
        String safeUrl = TextUtils.htmlEncode(url);
        String safeDetail = TextUtils.htmlEncode(detail == null ? "" : detail);
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
            + "<style>"
            + "*{box-sizing:border-box}"
            + "body{margin:0;background:#1a1a1a;color:#e8e8e8;font-family:system-ui,-apple-system,sans-serif;"
            + "display:flex;align-items:center;justify-content:center;min-height:100vh;padding:24px}"
            + ".card{width:100%;max-width:420px;text-align:center}"
            + ".logo{width:88px;height:88px;margin:0 auto 18px;border-radius:24px;background:#242424;"
            + "border:1px solid #3a3a3a;display:flex;align-items:center;justify-content:center;"
            + "font-size:52px;font-weight:700}"
            + "h1{font-size:18px;font-weight:600;margin:0 0 12px}"
            + ".url{font-size:12px;color:#60a5fa;word-break:break-all;background:#242424;"
            + "border:1px solid #3a3a3a;padding:9px 12px;border-radius:10px;margin:0 0 10px}"
            + ".err{font-size:12px;color:#9ca3af;margin:0 0 18px;word-break:break-all}"
            + "ul{text-align:left;font-size:13px;color:#9ca3af;line-height:2;padding-left:20px;margin:0 0 22px}"
            + "code{color:#e8e8e8;background:#242424;border-radius:4px;padding:1px 5px;font-size:12px}"
            + "a.btn{display:inline-block;margin:5px;padding:11px 26px;border-radius:12px;"
            + "text-decoration:none;font-size:14px;font-weight:500}"
            + ".primary{background:#2563eb;color:#fff}"
            + ".ghost{background:#242424;color:#e8e8e8;border:1px solid #3a3a3a}"
            + ".diag{text-align:left;font-size:11px;line-height:1.7;color:#9ca3af;background:#242424;"
            + "border:1px solid #3a3a3a;border-radius:10px;padding:10px 12px;margin:0 0 16px;"
            + "white-space:pre-wrap;word-break:break-all;font-family:monospace}"
            + ".cd{font-size:12px;color:#6b7280;margin-top:16px}"
            + "</style></head><body><div class=\"card\">"
            + "<div class=\"logo\">π</div>"
            + "<h1>无法连接到 Pi Web</h1>"
            + "<div class=\"url\">" + safeUrl + "</div>"
            + "<p class=\"err\">" + safeDetail + "</p>"
            + "<ul>"
            + "<li>手机与服务器需在同一局域网</li>"
            + "<li>或通过 Tailscale 组网互联</li>"
            + "<li>确认服务器正在运行 <code>pi-web</code></li>"
            + "<li>IPv6 请用方括号: <code>http://[2001:db8::1]:30141</code></li>"
            + "</ul>"
            + "<pre class=\"diag\" id=\"diag\">诊断中…\n</pre>"
            + "<a class=\"btn primary\" href=\"piweb://retry\">立即重试</a>"
            + "<a class=\"btn ghost\" href=\"piweb://settings\">修改地址</a>"
            + "<a class=\"btn ghost\" href=\"piweb://diag\">重新诊断</a>"
            + "<div class=\"cd\"><span id=\"cd\">" + AUTO_RETRY_SECONDS + "</span> 秒后自动重试…</div>"
            + "</div></body></html>";
    }

    // ---------------------------------------------------------------- 菜单

    /**
     * π 菜单(v2.5): 顶部为地址簿快切列表(当前服务 ✓ 标记),
     * 其下为地址簿管理与常规功能项。
     */
    private void openMenu() {
        final List<BookEntry> book = loadBook();
        final List<String> items = new ArrayList<>();
        for (BookEntry e : book) {
            items.add((e.url.equals(serverUrl) ? "✓ " : "     ") + e.name);
        }
        items.add("────────────");
        final int bookManageIndex = items.size();
        items.add("服务器地址簿…");
        items.add("刷新页面");
        items.add("运行日志");
        items.add("网络诊断");
        items.add(desktopMode ? "桌面模式: 开 → 关" : "桌面模式: 关 → 开");
        items.add("在浏览器打开");
        items.add("关于");

        new AlertDialog.Builder(this)
            .setTitle("Pi Web · " + currentServiceName(book))
            .setItems(items.toArray(new String[0]), (dialog, which) -> {
                if (which < book.size()) {
                    switchTo(book.get(which), "menu");
                    return;
                }
                if (which == bookManageIndex) {
                    openBookDialog();
                    return;
                }
                switch (which - bookManageIndex) {
                    case 1: // 刷新页面
                        loadPiWeb("manual");
                        break;
                    case 2:
                        openLogDialog();
                        break;
                    case 3:
                        openDiagDialog();
                        break;
                    case 4:
                        desktopMode = !desktopMode;
                        prefs.edit().putBoolean(PREF_DESKTOP, desktopMode).apply();
                        applyUserAgent();
                        AppLog.i("Mode", "桌面模式: " + (desktopMode ? "开" : "关"));
                        loadPiWeb("mode");
                        toast(desktopMode ? "已切换到桌面版页面" : "已切换到移动版页面");
                        break;
                    case 5:
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(serverUrl)));
                        } catch (Exception e) {
                            toast("没有可用的浏览器");
                        }
                        break;
                    case 6:
                        showAbout();
                        break;
                    default:
                        break; // 分隔线,忽略
                }
            })
            .show();
    }

    private String currentServiceName(List<BookEntry> book) {
        int idx = bookIndexOf(book, serverUrl);
        return idx >= 0 ? book.get(idx).name : briefHost(serverUrl);
    }

    /** 运行日志查看: 最近 400 行,支持分享(文本)与清空。 */
    private void openLogDialog() {
        AppLog.i("Log", "查看运行日志");
        TextView content = new TextView(this);
        content.setText(AppLog.dump());
        content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        content.setTextColor(C_TEXT);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        int pad = dp(12);
        content.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
            .setTitle("运行日志(" + AppLog.size() + " 行)")
            .setView(scroll)
            .setPositiveButton("分享", (dialog, which) -> shareLog())
            .setNeutralButton("清空", (dialog, which) -> {
                AppLog.clear();
                toast("日志已清空");
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private void shareLog() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Pi Web 运行日志");
        send.putExtra(Intent.EXTRA_TEXT,
            "Pi Web v" + APP_VERSION + " 运行日志 | " + serverUrl + "\n\n" + AppLog.dump());
        try {
            startActivity(Intent.createChooser(send, "分享日志"));
        } catch (Exception e) {
            AppLog.w("Log", "分享失败: " + e);
            toast("无可用分享目标");
        }
    }

    // ---------------------------------------------------------------- 诊断

    /**
     * 分层诊断入口(v2.4)。错误页自动触发(diagPending),π 菜单可手动触发。
     * 结果三路输出: 错误页实时注入 / 诊断对话框 / AppLog。
     */
    private void runDiagnostics() {
        if (diagRunning) {
            toast("诊断进行中…");
            return;
        }
        diagRunning = true;
        AppLog.i("Diag", "==== 开始诊断 " + serverUrl + " ====");
        if (inError) {
            webView.evaluateJavascript(
                "(function(){var e=document.getElementById('diag');if(e)e.textContent='';})();",
                null);
        }
        final boolean networkUp = isNetworkAvailable();
        Diag.run(serverUrl, networkUp, new Diag.Callback() {
            @Override
            public void onLine(final String line) {
                runOnUiThread(() -> onDiagLine(line));
            }

            @Override
            public void onDone(final String verdict) {
                runOnUiThread(() -> {
                    diagRunning = false;
                    onDiagDone(verdict);
                });
            }
        });
    }

    private void onDiagLine(String line) {
        AppLog.i("Diag", line);
        if (inError) injectDiag(line);
        if (diagDialogText != null) {
            diagDialogText.append(line);
            diagDialogText.append("\n");
        }
    }

    private void onDiagDone(String verdict) {
        if (verdict.contains("全链路正常")) AppLog.i("Diag", verdict);
        else AppLog.w("Diag", verdict);
        if (inError) injectDiag("── " + verdict);
        if (diagDialogText != null) {
            diagDialogText.append("\n");
            diagDialogText.append(verdict);
            diagDialogText.append("\n");
        }
    }

    private void injectDiag(String line) {
        webView.evaluateJavascript(
            "(function(){var e=document.getElementById('diag');if(e)e.textContent+='"
                + jsEscape(line) + "\\n';})();",
            null);
    }

    private static String jsEscape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
    }

    /** π 菜单 → 网络诊断: 非错误态时的实时结果对话框。 */
    private void openDiagDialog() {
        TextView content = new TextView(this);
        content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        content.setTextColor(C_TEXT);
        content.setTypeface(Typeface.MONOSPACE);
        int pad = dp(14);
        content.setPadding(pad, pad, pad, pad);
        content.setText("诊断中…\n");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        diagDialogText = content;
        new AlertDialog.Builder(this)
            .setTitle("网络诊断")
            .setView(scroll)
            .setOnDismissListener(d -> diagDialogText = null)
            .setNegativeButton("关闭", null)
            .show();
        runDiagnostics();
    }

    /**
     * 修改服务器地址。
     * 支持: IPv4(http://192.168.1.10:30141)、IPv6 方括号(http://[2001:db8::1]:30141)、
     * 域名(https://pi.example.com)、Tailscale MagicDNS(http://fnos:30141)。
     * 省略协议时自动补 http://。
     */
    private void openUrlDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(serverUrl);
        input.setSelection(input.getText().length());
        input.setSingleLine(true);

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(20);
        lp.rightMargin = dp(20);
        lp.topMargin = dp(8);
        container.addView(input, lp);

        new AlertDialog.Builder(this)
            .setTitle("服务器地址")
            .setMessage("支持 IPv4 / IPv6(方括号)/ 域名,可省略 http://\n"
                + "例: 192.168.1.10:30141\n"
                + "例: http://[2001:db8::1]:30141\n"
                + "例: https://pi.example.com")
            .setView(container)
            .setPositiveButton("连接", (dialog, which) -> {
                String normalized = normalizeUrl(input.getText().toString());
                if (normalized == null) {
                    toast("地址无效: 需要 IPv4 / [IPv6] / 域名 + 可选端口");
                    return;
                }
                String oldUrl = serverUrl;
                serverUrl = normalized;
                prefs.edit().putString(PREF_URL, serverUrl).apply();
                renameBookUrl(oldUrl, normalized);
                AppLog.i("Config", "地址变更 → " + serverUrl);
                firstLoadDone = false;
                loadPiWeb("config");
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ---------------------------------------------------------------- 地址簿 (v2.5)

    /** 地址簿条目: 名称 + 完整 URL(可含 ?ccr_web_token= 等查询参数)。 */
    private static final class BookEntry {
        final String name;
        final String url;
        BookEntry(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private List<BookEntry> loadBook() {
        List<BookEntry> list = new ArrayList<>();
        String json = prefs.getString(PREF_BOOK, "");
        if (json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.optString("name", "").trim();
                String url = o.optString("url", "").trim();
                if (!name.isEmpty() && !url.isEmpty()) {
                    list.add(new BookEntry(name, url));
                }
            }
        } catch (Exception e) {
            AppLog.w("Book", "地址簿解析失败,按空处理: " + e);
        }
        return list;
    }

    private void saveBook(List<BookEntry> list) {
        JSONArray arr = new JSONArray();
        try {
            for (BookEntry e : list) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("url", e.url);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(PREF_BOOK, arr.toString()).apply();
    }

    /** v2.5 首次启动迁移: 把已有地址存为地址簿首条 "Pi Web"。 */
    private void migrateBook() {
        if (!prefs.getString(PREF_BOOK, "").isEmpty()) return;
        List<BookEntry> list = new ArrayList<>();
        list.add(new BookEntry("Pi Web", serverUrl));
        saveBook(list);
        AppLog.i("Book", "迁移完成: 当前地址已存为地址簿首条 Pi Web");
    }

    private int bookIndexOf(List<BookEntry> list, String url) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).url.equals(url)) return i;
        }
        return -1;
    }

    /** 错误页「修改地址」改址后同步地址簿: 命中旧地址的条目跟随更新。 */
    private void renameBookUrl(String oldUrl, String newUrl) {
        List<BookEntry> list = loadBook();
        int idx = bookIndexOf(list, oldUrl);
        if (idx < 0) return;
        BookEntry old = list.get(idx);
        list.set(idx, new BookEntry(old.name, newUrl));
        saveBook(list);
        AppLog.i("Book", "条目跟随更新: " + old.name + " → " + newUrl);
    }

    /** 快切/连接到某条目(π 菜单与地址簿共用)。 */
    private void switchTo(BookEntry entry, String trigger) {
        if (entry.url.equals(serverUrl)) {
            toast("当前已是 " + entry.name + ",刷新页面");
            loadPiWeb("manual");
            return;
        }
        AppLog.i("Book", "切换服务: " + entry.name + " → " + entry.url
            + " (trigger=" + trigger + ")");
        serverUrl = entry.url;
        prefs.edit().putString(PREF_URL, serverUrl).apply();
        firstLoadDone = false;
        loadPiWeb("switch");
        toast("已切换到 " + entry.name);
    }

    /**
     * π 菜单 → 服务器地址簿。
     * 点按条目 = 连接;长按条目 = 编辑/删除;底部按钮 = 添加新条目。
     */
    private void openBookDialog() {
        final List<BookEntry> book = loadBook();
        AppLog.i("Book", "打开地址簿: " + book.size() + " 条");

        ListView listView = new ListView(this);
        String[] names = new String[book.size()];
        for (int i = 0; i < book.size(); i++) names[i] = book.get(i).name;
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_list_item_2, android.R.id.text1, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView t1 = (TextView) v.findViewById(android.R.id.text1);
                TextView t2 = (TextView) v.findViewById(android.R.id.text2);
                BookEntry e = book.get(position);
                t1.setText(e.name + (e.url.equals(serverUrl) ? " ✓" : ""));
                t1.setTextColor(C_TEXT);
                t2.setText(e.url);
                t2.setTextColor(C_MUTED);
                return v;
            }
        };
        listView.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("服务器地址簿")
            .setView(listView)
            .setPositiveButton("＋ 添加新地址", (d, w) -> openEntryDialog(null, -1))
            .setNegativeButton("关闭", null)
            .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            switchTo(book.get(position), "book");
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            openEntryDialog(book.get(position), position);
            return true;
        });
        dialog.show();
    }

    /**
     * 添加(index<0)/编辑(index≥0)地址簿条目。
     * 地址走 normalizeUrl 校验(IPv4 / [IPv6] / 域名,可含查询参数),可省略 http://。
     */
    private void openEntryDialog(final BookEntry existing, final int index) {
        final EditText nameInput = new EditText(this);
        nameInput.setHint("名称,如: Pi Web / CCR 控制台");
        nameInput.setSingleLine(true);
        final EditText urlInput = new EditText(this);
        urlInput.setHint("地址,如: http://100.117.232.62:30141");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        if (existing != null) {
            nameInput.setText(existing.name);
            urlInput.setText(existing.url);
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), 0);
        layout.addView(nameInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        urlLp.topMargin = dp(8);
        layout.addView(urlInput, urlLp);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(existing == null ? "添加地址" : "编辑地址")
            .setView(layout)
            .setNegativeButton("取消", null);

        if (existing != null) {
            builder.setNeutralButton("删除", (d, w) -> confirmDelete(existing));
        }
        builder.setPositiveButton(existing == null ? "添加" : "保存", (d, w) -> {
            String name = nameInput.getText().toString().trim();
            String url = normalizeUrl(urlInput.getText().toString());
            if (name.isEmpty()) {
                toast("名称不能为空");
                openEntryDialog(existing, index);
                return;
            }
            if (url == null) {
                toast("地址无效: 需要 IPv4 / [IPv6] / 域名 + 可选端口");
                openEntryDialog(existing, index);
                return;
            }
            List<BookEntry> book = loadBook();
            if (index >= 0 && index < book.size()) {
                book.set(index, new BookEntry(name, url));
                AppLog.i("Book", "编辑条目: " + name + " → " + url);
                toast("已保存");
            } else {
                book.add(new BookEntry(name, url));
                AppLog.i("Book", "添加条目: " + name + " → " + url);
                toast("已添加");
            }
            saveBook(book);
            // 编辑的若是当前连接条目,跟随切换地址
            if (existing != null && existing.url.equals(serverUrl) && !url.equals(serverUrl)) {
                serverUrl = url;
                prefs.edit().putString(PREF_URL, serverUrl).apply();
                firstLoadDone = false;
                loadPiWeb("config");
            }
        });
        builder.show();
    }

    private void confirmDelete(final BookEntry entry) {
        new AlertDialog.Builder(this)
            .setTitle("删除条目")
            .setMessage("确认删除 \"" + entry.name + "\"?\n" + entry.url)
            .setPositiveButton("删除", (d, w) -> {
                List<BookEntry> book = loadBook();
                int idx = bookIndexOf(book, entry.url);
                if (idx >= 0) {
                    book.remove(idx);
                    saveBook(book);
                }
                AppLog.i("Book", "删除条目: " + entry.name);
                toast("已删除 " + entry.name);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage("Pi Web 安卓客户端 v" + APP_VERSION + "\n"
                + "基于 github.com/agegr/pi-web 最新源码重构\n\n"
                + "当前地址: " + serverUrl + "\n"
                + "页面模式: " + (desktopMode ? "桌面版" : "移动版") + "\n"
                + "日志文件: " + AppLog.filePath())
            .setPositiveButton("确定", null)
            .show();
    }

    // ---------------------------------------------------------------- 工具

    /** 归一化并校验地址; 非法返回 null。支持 IPv4 / [IPv6] / 域名。 */
    static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        Uri uri = Uri.parse(s);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) return null;
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return null;
        int port = uri.getPort();
        if (port != -1 && (port <= 0 || port > 65535)) return null;
        return s;
    }

    private boolean isRealPage(String url) {
        return url != null
            && (url.startsWith("http://") || url.startsWith("https://"))
            && !url.startsWith(ERROR_BASE);
    }

    private String briefHost(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null) return url;
            return port == -1 ? host : host + ":" + port;
        } catch (Exception e) {
            return url;
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingDownloadUrl != null) {
                    enqueueDownload(pendingDownloadUrl, pendingDownloadName, pendingDownloadMime);
                    pendingDownloadUrl = null;
                }
            } else {
                toast("未授予存储权限,无法下载");
            }
        }
    }

    // -------------------------------------------------------------- 下载功能

    private void enqueueDownload(String url, String fileName, String mimetype) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mimetype != null) req.setMimeType(mimetype);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) req.addRequestHeader("Cookie", cookies);
            req.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            req.setTitle(fileName);
            req.setDescription("Pi Web 下载");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            long id = dm.enqueue(req);
            AppLog.i("Download", "已加入下载队列 id=" + id + " file=" + fileName);
            toast("开始下载: " + fileName);
        } catch (Exception e) {
            AppLog.e("Download", "下载失败: " + e);
            toast("下载失败: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- 生命周期

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            AppLog.i("File", "选择结果: " + (results == null ? 0 : results.length) + " 个文件");
            if (uploadCallback != null) {
                uploadCallback.onReceiveValue(results);
                uploadCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(networkReceiver);
        } catch (Exception ignored) {
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
