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
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

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
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_URL = "http://100.117.232.62:30141";
    private static final String PREFS = "piweb_prefs";
    private static final String PREF_URL = "server_url";
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
    private SwipeRefreshLayout swipeLayout;
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

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (!inError) return;
            countdown--;
            if (countdown <= 0) {
                loadPiWeb();
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
            if (inError && isNetworkAvailable()) {
                toast("网络已恢复,重新连接…");
                loadPiWeb();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl = prefs.getString(PREF_URL, DEFAULT_URL);
        desktopMode = prefs.getBoolean(PREF_DESKTOP, false);

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

        loadPiWeb();
    }

    // ---------------------------------------------------------------- 布局

    private void buildLayout() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(C_BG);

        // 下拉刷新 + WebView
        swipeLayout = new SwipeRefreshLayout(this);
        swipeLayout.setColorSchemeColors(C_ACCENT);
        swipeLayout.setProgressBackgroundColorSchemeColor(C_PANEL);
        swipeLayout.setOnRefreshListener(() -> {
            if (inError) loadPiWeb(); else webView.reload();
        });

        webView = new WebView(this);
        webView.setBackgroundColor(C_BG);
        swipeLayout.addView(webView,
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(swipeLayout,
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
        defaultUserAgent = s.getUserAgentString();
        applyUserAgent();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null) return false;
                switch (scheme) {
                    case "piweb":
                        String host = uri.getHost();
                        if ("retry".equals(host)) loadPiWeb();
                        else if ("settings".equals(host)) openUrlDialog();
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
                swipeLayout.setRefreshing(false);
                // 关键修复: 只有真实页面成功加载才切换状态,
                // 错误页(piweb.error)与中间页不会覆盖错误状态
                if (!pageError && isRealPage(url)) {
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
        });
    }

    private void applyUserAgent() {
        WebSettings s = webView.getSettings();
        if (desktopMode) {
            String ua = defaultUserAgent
                .replace(" Mobile", "")
                .replace(" wv) ", ") ")
                .replace("Android ", "");
            s.setUserAgentString(ua);
        } else {
            s.setUserAgentString(defaultUserAgent);
        }
        s.setLoadWithOverviewMode(desktopMode);
        s.setUseWideViewPort(true);
    }

    // ---------------------------------------------------------------- 加载

    private void loadPiWeb() {
        handler.removeCallbacksAndMessages(null);
        inError = false;
        pageError = false;
        swipeLayout.setRefreshing(false);
        if (!firstLoadDone) {
            splashStatus.setText("正在连接 " + briefHost(serverUrl) + " …");
            splashView.setVisibility(View.VISIBLE);
        }
        webView.loadUrl(serverUrl);
    }

    private void showErrorPage(String detail) {
        inError = true;
        handler.removeCallbacksAndMessages(null);
        progressBar.setVisibility(View.GONE);
        swipeLayout.setRefreshing(false);
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
            + "<a class=\"btn primary\" href=\"piweb://retry\">立即重试</a>"
            + "<a class=\"btn ghost\" href=\"piweb://settings\">修改地址</a>"
            + "<div class=\"cd\"><span id=\"cd\">" + AUTO_RETRY_SECONDS + "</span> 秒后自动重试…</div>"
            + "</div></body></html>";
    }

    // ---------------------------------------------------------------- 菜单

    private void openMenu() {
        String desktopLabel = desktopMode ? "桌面模式: 开 → 关" : "桌面模式: 关 → 开";
        String[] items = {"刷新页面", "修改服务器地址", desktopLabel, "在浏览器打开", "关于"};
        new AlertDialog.Builder(this)
            .setTitle("Pi Web")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0:
                        loadPiWeb();
                        break;
                    case 1:
                        openUrlDialog();
                        break;
                    case 2:
                        desktopMode = !desktopMode;
                        prefs.edit().putBoolean(PREF_DESKTOP, desktopMode).apply();
                        applyUserAgent();
                        loadPiWeb();
                        toast(desktopMode ? "已切换到桌面版页面" : "已切换到移动版页面");
                        break;
                    case 3:
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(serverUrl)));
                        } catch (Exception e) {
                            toast("没有可用的浏览器");
                        }
                        break;
                    case 4:
                        showAbout();
                        break;
                }
            })
            .show();
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
                serverUrl = normalized;
                prefs.edit().putString(PREF_URL, serverUrl).apply();
                firstLoadDone = false;
                loadPiWeb();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage("Pi Web 安卓客户端 v2.0\n"
                + "基于 github.com/agegr/pi-web 最新源码重构\n\n"
                + "当前地址: " + serverUrl + "\n"
                + "页面模式: " + (desktopMode ? "桌面版" : "移动版"))
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

    // -------------------------------------------------------------- 生命周期

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
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
