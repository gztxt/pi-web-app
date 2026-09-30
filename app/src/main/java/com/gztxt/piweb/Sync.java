package com.gztxt.piweb;

import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v2.7 云同步：地址簿 KV 备份/恢复 + 运行日志按日期上传。
 *
 * v2.8.1 修复「离开局域网后日志静默为零」：
 *  - 回传地址由「单一硬编码 IP」改为「有序候选清单」，由 MainActivity 按当前服务器派生注入。
 *  - 预算硬上限：每个候选最多 1 次、总共最多 3 个候选、connect/read 各 ≤5s；
 *    失败不轮询（两次自动 flush 之间强制间隔 15s，且同一时刻只允许 1 个上传线程在飞）。
 *  - 不再静默：失败批次回插待发队列（上限 400 行，丢最旧），并通过 StatusListener
 *    把「用了哪个候选 / 失败原因 / 耗时」上报给界面（MainActivity 写进 AppLog + toast）。
 *
 * 注意：本类绝不调用 AppLog（AppLog.write 会回调 queueLog，会递归），只用 android.util.Log
 *       与 StatusListener（监听方负责切主线程并自带递归保护）。
 */
public final class Sync {
    private static final String TAG = "PiWebSync";

    private static final int FLUSH_THRESHOLD = 20;     // 攒够这么多行自动上传
    private static final int MAX_CANDIDATES = 3;       // 候选预算（硬上限）
    private static final int MAX_PENDING = 400;        // 待发队列上限（与 AppLog 环形缓冲同量级）
    private static final int CONNECT_TIMEOUT = 5000;   // 单次连接超时 ≤5s
    private static final int READ_TIMEOUT = 5000;      // 单次读取超时 ≤5s
    private static final long MIN_FLUSH_GAP = 15000;   // 失败后冷却，禁止轮询

    public interface Callback { void onResult(boolean ok, String body); }

    /** 回传状态回调（在后台线程调用，监听方自行 runOnUiThread）。 */
    public interface StatusListener { void onStatus(String line, boolean ok); }

    /** 一轮候选尝试的结果。 */
    private static final class Result {
        boolean ok;
        int code = -1;
        int usedIndex = -1;
        int tried = 0;
        int total = 0;
        long cost;
        String host = "";
        String body;
        final StringBuilder errs = new StringBuilder();
    }

    private static final List<String> PENDING = new ArrayList<>();
    private static final List<String> CANDIDATES = new ArrayList<>();
    private static String status = "尚未尝试";
    private static StatusListener listener;
    private static long lastFlushAt = 0;
    private static boolean flushing = false;

    private Sync() {}

    // ── 配置 ───────────────────────────────────────────

    /** 兼容旧签名：等价于「只有一个候选」。 */
    public static void setSyncUrl(String url) {
        List<String> one = new ArrayList<>();
        one.add(url);
        setSyncCandidates(one);
    }

    /** 注入有序候选清单：去空、去重、截到 MAX_CANDIDATES。空清单 = 关闭同步。 */
    public static synchronized void setSyncCandidates(List<String> urls) {
        CANDIDATES.clear();
        if (urls != null) {
            for (String u : urls) {
                String s = normalize(u);
                if (s.isEmpty() || CANDIDATES.contains(s)) continue;
                CANDIDATES.add(s);
                if (CANDIDATES.size() >= MAX_CANDIDATES) break;
            }
        }
        if (CANDIDATES.isEmpty()) {
            status = "已关闭（无候选地址）";
            Log.i(TAG, "候选清单为空：同步关闭");
        } else {
            Log.i(TAG, "候选清单(" + CANDIDATES.size() + "): " + CANDIDATES);
        }
    }

    private static String normalize(String url) {
        if (url == null) return "";
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static synchronized boolean enabled() { return !CANDIDATES.isEmpty(); }

    /** 当前候选清单副本（供界面展示）。 */
    public static synchronized List<String> candidates() { return new ArrayList<>(CANDIDATES); }

    /** 最近一次回传状态（人话，含用了哪个候选/失败原因）。 */
    public static synchronized String status() { return status; }

    public static void setStatusListener(StatusListener l) { listener = l; }

    // ── 日志上传 ───────────────────────────────────────

    /** AppLog.write 每行回调；攒够阈值自动 flush（受冷却约束）。 */
    public static synchronized void queueLog(String line) {
        if (!enabled()) return;
        PENDING.add(line);
        trimPending();
        if (PENDING.size() >= FLUSH_THRESHOLD) flushLogs(false);
    }

    /** 常规 flush（受 15s 冷却约束）。 */
    public static void flushLogs() { flushLogs(false); }

    /** 生命周期 flush（onPause）：忽略冷却，尽量把日志送达。 */
    public static void flushLogsNow() { flushLogs(true); }

    private static synchronized void flushLogs(boolean force) {
        if (!enabled() || PENDING.isEmpty() || flushing) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastFlushAt < MIN_FLUSH_GAP) return;
        lastFlushAt = now;
        flushing = true;

        final List<String> batch = new ArrayList<>(PENDING);
        PENDING.clear();
        final List<String> cands = new ArrayList<>(CANDIDATES);
        final String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        final String payload = logPayload(batch);
        final int count = batch.size();

        new Thread(() -> {
            Result r;
            try {
                r = attempt(cands, "POST", "/api/app-log/piweb/" + date, payload);
                if (!r.ok) requeue(batch);           // 失败不丢数据
            } finally {
                finishingFlush();
            }
            publish(r, "日志 " + count + " 行");
        }, "piweb-sync-log").start();
    }

    private static synchronized void finishingFlush() { flushing = false; }

    private static synchronized void requeue(List<String> batch) {
        PENDING.addAll(0, batch);
        trimPending();
    }

    private static void trimPending() {
        while (PENDING.size() > MAX_PENDING) PENDING.remove(0);
    }

    private static String logPayload(List<String> batch) {
        StringBuilder sb = new StringBuilder("{\"lines\":[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(jsonStr(batch.get(i)));
        }
        return sb.append("]}").toString();
    }

    // ── 地址簿 ─────────────────────────────────────────

    /** 地址簿备份：bookJsonArr 是 [{name,url},...] 的 JSONArray 字符串。 */
    public static void pushBook(final String bookJsonArr) {
        final List<String> cands = snapshot();
        if (cands.isEmpty()) return;
        new Thread(() -> {
            Result r = attempt(cands, "POST", "/api/kv/piweb-book", "{\"book\":" + bookJsonArr + "}");
            publish(r, "地址簿备份");
        }, "piweb-sync-book").start();
    }

    /** 地址簿恢复：回调原始响应体（含 data.book）。 */
    public static void pullBook(final Callback cb) {
        final List<String> cands = snapshot();
        if (cands.isEmpty()) { if (cb != null) cb.onResult(false, null); return; }
        new Thread(() -> {
            Result r = attempt(cands, "GET", "/api/kv/piweb-book", null);
            if (cb != null) cb.onResult(r.ok, r.body);
            publish(r, "地址簿恢复");
        }, "piweb-sync-pull").start();
    }

    private static synchronized List<String> snapshot() { return new ArrayList<>(CANDIDATES); }

    // ── HTTP：按候选清单依次尝试（每个 1 次，最多 3 个）────

    private static Result attempt(List<String> cands, String method, String path, String body) {
        Result r = new Result();
        r.total = cands.size();
        for (int i = 0; i < cands.size(); i++) {
            String base = cands.get(i);
            r.tried = i + 1;
            long t0 = System.currentTimeMillis();
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + path).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(CONNECT_TIMEOUT);
                c.setReadTimeout(READ_TIMEOUT);
                if ("POST".equals(method)) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    OutputStream os = c.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }
                int code = c.getResponseCode();
                r.cost = System.currentTimeMillis() - t0;
                r.code = code;
                String text = readBody(c, code);
                if (code >= 200 && code < 300) {
                    r.ok = true;
                    r.usedIndex = i;
                    r.host = base;
                    r.body = text;
                    Log.i(TAG, method + " " + base + path + " -> " + code
                        + " (" + r.cost + "ms, 候选" + r.tried + "/" + r.total + ")");
                    return r;
                }
                Log.w(TAG, method + " " + base + path + " -> HTTP " + code);
                appendErr(r, brief(base) + " → HTTP " + code);
            } catch (Exception e) {
                r.cost = System.currentTimeMillis() - t0;
                Log.w(TAG, method + " 失败 " + base + path + ": " + e);
                appendErr(r, brief(base) + " → " + e.getClass().getSimpleName());
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return r;
    }

    private static String readBody(HttpURLConnection c, int code) {
        try {
            java.io.InputStream is = (code >= 200 && code < 300)
                ? c.getInputStream() : c.getErrorStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void appendErr(Result r, String seg) {
        // 每段最长 60 字符 + " | " 分隔符，总计不超过 200 留尾空间
        if (r.errs.length() > 0) r.errs.append(" | ");
        if (r.errs.length() + seg.length() + 1 > 200) {
            // 已超限：只加省略号不继续追加
            r.errs.append("…");
        } else {
            // 截断当前段使其不突破 200
            int remaining = 200 - r.errs.length();
            r.errs.append(seg.length() > remaining ? seg.substring(0, remaining) : seg);
        }
    }

    private static String brief(String base) {
        int idx = base.indexOf("://");
        return idx > 0 ? base.substring(idx + 3) : base;
    }

    /** 状态可见：写日志 + 通知界面。 */
    private static synchronized void publish(Result r, String what) {
        String line;
        if (r.ok) {
            line = "回传正常 · " + what + " → " + r.host + " (HTTP " + r.code
                + ", " + r.cost + "ms, 候选" + (r.usedIndex + 1) + "/" + r.total + ")";
        } else if (r.total == 0) {
            line = "回传未启用 · " + what + " · 无候选地址";
        } else {
            line = "回传失败 · " + what + " · 试了 " + r.tried + "/" + r.total + " 个候选: "
                + r.errs + " · 待发 " + pendingCount() + " 行";
        }
        status = line;
        Log.i(TAG, line);
        StatusListener l = listener;
        if (l != null) l.onStatus(line, r.ok);
    }

    private static synchronized int pendingCount() { return PENDING.size(); }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.append("\"").toString();
    }
}
