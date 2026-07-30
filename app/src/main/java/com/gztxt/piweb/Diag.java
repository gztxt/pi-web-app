package com.gztxt.piweb;

import android.net.Uri;
import android.util.Patterns;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * 网络分层诊断(v2.4): 网络状态 → DNS → TCP 端口 → HTTP 逐层探测,
 * 每层带耗时,失败即停并给出"哪一层 + 什么原因 + 查什么"的人话结论。
 * 探测在子线程执行,结果经 Callback 回调(调用方负责切主线程)。
 */
public final class Diag {

    /** 探测结果回调(在诊断线程调用,UI 操作需调用方 post 主线程)。 */
    public interface Callback {
        void onLine(String line);

        void onDone(String verdict);
    }

    private static final int TCP_TIMEOUT = 5000;
    private static final int HTTP_CONNECT_TIMEOUT = 5000;
    private static final int HTTP_READ_TIMEOUT = 8000;

    private Diag() {
    }

    public static void run(final String url, final boolean networkUp, final Callback cb) {
        new Thread(() -> probe(url, networkUp, cb), "piweb-diag").start();
    }

    private static void probe(String url, boolean networkUp, Callback cb) {
        // ── [1/4] 网络状态 ──
        if (!networkUp) {
            cb.onLine("[1/4] 网络状态: ✗ 无连接");
            cb.onDone("诊断结论: 网络层失败 — 手机当前无网络(WiFi/移动数据均断)。先恢复网络再试。");
            return;
        }
        cb.onLine("[1/4] 网络状态: ✓ 已连接");

        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        if (host == null || host.isEmpty()) {
            cb.onDone("诊断结论: 地址无效 — 缺少主机名,请在 π 菜单修改地址。");
            return;
        }
        cb.onLine("目标: " + scheme + "://" + host + ":" + port);

        // ── [2/4] DNS 解析 ──
        InetAddress[] addrs;
        if (isIpLiteral(host)) {
            cb.onLine("[2/4] DNS: IP 直连,跳过解析");
            try {
                addrs = new InetAddress[]{InetAddress.getByName(stripBrackets(host))};
            } catch (Exception e) {
                cb.onDone("诊断结论: 地址无效 — " + e.getMessage());
                return;
            }
        } else {
            long t0 = now();
            try {
                addrs = InetAddress.getAllByName(host);
                long cost = now() - t0;
                StringBuilder ips = new StringBuilder();
                for (InetAddress a : addrs) {
                    if (ips.length() > 0) ips.append(", ");
                    ips.append(a.getHostAddress());
                }
                cb.onLine("[2/4] DNS: ✓ " + ips + " (cost=" + cost + "ms)");
            } catch (UnknownHostException e) {
                long cost = now() - t0;
                cb.onLine("[2/4] DNS: ✗ 解析失败 (cost=" + cost + "ms)");
                cb.onDone("诊断结论: DNS 层失败 — 「" + host + "」无法解析。"
                    + "域名不存在或 DDNS 未生效;单标签名(如 fnos)需先连 Tailscale;"
                    + "也可换公网 DNS(如 223.5.5.5)对比测试。");
                return;
            }
        }

        // ── [3/4] TCP 端口(多 IP 优先 IPv4,移动网络下 IPv6 常不通)──
        InetAddress target = pickAddress(addrs);
        long t1 = now();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(target, port), TCP_TIMEOUT);
            long cost = now() - t1;
            cb.onLine("[3/4] TCP: ✓ " + target.getHostAddress() + ":" + port
                + " 开放 (cost=" + cost + "ms)");
        } catch (SocketTimeoutException e) {
            long cost = now() - t1;
            cb.onLine("[3/4] TCP: ✗ 超时 (cost=" + cost + "ms)");
            cb.onDone("诊断结论: TCP 层失败 — 端口 " + port + " 超时无响应。"
                + "主机不在线,或防火墙静默丢包(不是拒绝);"
                + "检查服务器开机状态、防火墙与端口转发规则。");
            return;
        } catch (ConnectException e) {
            long cost = now() - t1;
            cb.onLine("[3/4] TCP: ✗ 连接被拒 (cost=" + cost + "ms)");
            cb.onDone("诊断结论: TCP 层失败 — 主机 " + target.getHostAddress() + " 在线,"
                + "但端口 " + port + " 拒绝连接 = pi-web 服务没运行(或监听在其他端口)。"
                + "去服务器启动 pi-web。");
            return;
        } catch (IOException e) {
            long cost = now() - t1;
            cb.onLine("[3/4] TCP: ✗ " + e.getClass().getSimpleName() + " (cost=" + cost + "ms)");
            cb.onDone("诊断结论: TCP 层失败 — " + e.getMessage());
            return;
        }

        // ── [4/4] HTTP ──
        HttpURLConnection conn = null;
        try {
            long t2 = now();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
            conn.setReadTimeout(HTTP_READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "PiWebApp-Diag/2.4");
            int code = conn.getResponseCode();
            long cost = now() - t2;
            if (code >= 200 && code < 400) {
                cb.onLine("[4/4] HTTP: ✓ " + code + " (cost=" + cost + "ms)");
                cb.onDone("诊断结论: 全链路正常 — 网络/DNS/TCP/HTTP 均通。"
                    + "若页面仍打不开:点重试,或 π 菜单切桌面模式、清应用缓存。");
            } else {
                cb.onLine("[4/4] HTTP: ✗ " + code + " (cost=" + cost + "ms)");
                cb.onDone("诊断结论: HTTP 层失败 — 端口通但返回 " + code + "。"
                    + "pi-web 服务内部异常,去服务器查 pi-web 日志。");
            }
        } catch (SSLException e) {
            cb.onLine("[4/4] HTTP: ✗ SSL/TLS 错误");
            cb.onDone("诊断结论: HTTP 层失败 — TLS 握手错误。"
                + "证书无效或 https 配置问题;内网建议改用 http://。");
        } catch (SocketTimeoutException e) {
            cb.onLine("[4/4] HTTP: ✗ 响应超时");
            cb.onDone("诊断结论: HTTP 层失败 — TCP 已连通但 HTTP 无响应。"
                + "服务卡死或被中间设备拦截。");
        } catch (IOException e) {
            cb.onLine("[4/4] HTTP: ✗ " + e.getClass().getSimpleName());
            cb.onDone("诊断结论: HTTP 层失败 — " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isIpLiteral(String host) {
        String h = stripBrackets(host);
        if (h.contains(":")) return true; // IPv6 字面量
        return Patterns.IP_ADDRESS.matcher(h).matches();
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /** 多 IP 时优先 IPv4。 */
    private static InetAddress pickAddress(InetAddress[] addrs) {
        for (InetAddress a : addrs) {
            if (a instanceof Inet4Address) return a;
        }
        return addrs[0];
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
