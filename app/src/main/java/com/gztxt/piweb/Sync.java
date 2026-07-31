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
 * 全部 best-effort、后台线程、失败静默。
 * 注意：本类绝不调用 AppLog（AppLog.write 会回调 queueLog，会递归），只用 android.util.Log。
 */
public final class Sync {
    private static final String TAG = "PiWebSync";
    private static final int FLUSH_THRESHOLD = 20;

    public interface Callback { void onResult(boolean ok, String body); }

    private static final List<String> PENDING = new ArrayList<>();
    private static String syncUrl = "";

    private Sync() {}

    public static void setSyncUrl(String url) {
        syncUrl = (url == null) ? "" : url.trim();
        while (syncUrl.endsWith("/")) syncUrl = syncUrl.substring(0, syncUrl.length() - 1);
    }

    public static boolean enabled() { return !syncUrl.isEmpty(); }

    /** AppLog.write 每行回调；攒够阈值自动 flush。 */
    public static synchronized void queueLog(String line) {
        if (!enabled()) return;
        PENDING.add(line);
        if (PENDING.size() >= FLUSH_THRESHOLD) flushLogs();
    }

    /** 上传待发日志到 <sync>/api/app-log/piweb/<今天>。onPause 与达阈值时调用。 */
    public static synchronized void flushLogs() {
        if (!enabled() || PENDING.isEmpty()) return;
        final List<String> batch = new ArrayList<>(PENDING);
        PENDING.clear();
        final String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(jsonStr(batch.get(i)));
        }
        sb.append("]");
        post(syncUrl + "/api/app-log/piweb/" + date, "{\"lines\":" + sb + "}", null);
    }

    /** 地址簿备份：bookJsonArr 是 [{name,url},...] 的 JSONArray 字符串。 */
    public static void pushBook(String bookJsonArr) {
        if (!enabled()) return;
        post(syncUrl + "/api/kv/piweb-book", "{\"book\":" + bookJsonArr + "}", null);
    }

    /** 地址簿恢复：回调原始响应体（含 data.book）。 */
    public static void pullBook(Callback cb) {
        if (!enabled()) { if (cb != null) cb.onResult(false, null); return; }
        get(syncUrl + "/api/kv/piweb-book", cb);
    }

    // ── HTTP（后台线程）──────────────────────────────
    private static void post(final String url, final String body, final Callback cb) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] b = body.getBytes("UTF-8");
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.flush();
                os.close();
                int code = c.getResponseCode();
                Log.i(TAG, "POST " + url + " -> " + code);
                if (cb != null) cb.onResult(code >= 200 && code < 300, null);
            } catch (Exception e) {
                Log.w(TAG, "POST 失败 " + url + ": " + e);
                if (cb != null) cb.onResult(false, null);
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private static void get(final String url, final Callback cb) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                int code = c.getResponseCode();
                java.io.InputStream is = (code >= 200 && code < 300)
                    ? c.getInputStream() : c.getErrorStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while (is != null && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
                String body = bo.toString("UTF-8");
                Log.i(TAG, "GET " + url + " -> " + code);
                if (cb != null) cb.onResult(code >= 200 && code < 300, body);
            } catch (Exception e) {
                Log.w(TAG, "GET 失败 " + url + ": " + e);
                if (cb != null) cb.onResult(false, null);
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

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