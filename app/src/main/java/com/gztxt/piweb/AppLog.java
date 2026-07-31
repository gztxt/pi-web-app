package com.gztxt.piweb;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 简易运行日志(v2.2):
 *  - 内存环形缓冲(最近 400 行),应用内 π 菜单 → 运行日志 可查看/分享/清空
 *  - 文件持久化 files/piweb.log,超过 256KB 自动轮转保留尾部 128KB
 *  - 镜像 logcat: `adb logcat -s PiWeb` 可实时看
 *  - installCrashHandler(): 未捕获崩溃先落盘再交给系统,黑屏/闪退事后可查
 */
public final class AppLog {

    private static final String TAG = "PiWeb";
    private static final int MAX_LINES = 400;
    private static final long MAX_FILE_BYTES = 256 * 1024;
    private static final long KEEP_BYTES = 128 * 1024;

    private static final Deque<String> BUFFER = new ArrayDeque<>();
    private static final SimpleDateFormat FMT =
        new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File logFile;

    private AppLog() {
    }

    public static synchronized void init(Context context) {
        logFile = new File(context.getFilesDir(), "piweb.log");
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        write("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        write("E", tag, msg);
    }

    private static synchronized void write(String level, String tag, String msg) {
        String line = FMT.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        BUFFER.addLast(line);
        while (BUFFER.size() > MAX_LINES) {
            BUFFER.removeFirst();
        }
        int prio = "E".equals(level) ? Log.ERROR : ("W".equals(level) ? Log.WARN : Log.INFO);
        Log.println(prio, TAG, tag + ": " + msg);
        appendToFile(line);
        Sync.queueLog(line);
    }

    private static void appendToFile(String line) {
        if (logFile == null) return;
        try {
            if (logFile.length() > MAX_FILE_BYTES) {
                rotate();
            }
            FileWriter fw = new FileWriter(logFile, true);
            try {
                fw.write(line);
                fw.write('\n');
            } finally {
                fw.close();
            }
        } catch (IOException ignored) {
            // 日志写失败不应影响主流程
        }
    }

    /** 文件超限时只保留尾部 KEEP_BYTES,防止无限增长。 */
    private static void rotate() {
        try {
            long len = logFile.length();
            long skip = len - KEEP_BYTES;
            byte[] tail;
            FileInputStream in = new FileInputStream(logFile);
            try {
                long skipped = 0;
                while (skipped < skip) {
                    long s = in.skip(skip - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
                tail = new byte[(int) (len - skipped)];
                int off = 0;
                while (off < tail.length) {
                    int r = in.read(tail, off, tail.length - off);
                    if (r < 0) break;
                    off += r;
                }
            } finally {
                in.close();
            }
            FileOutputStream out = new FileOutputStream(logFile, false);
            try {
                out.write(("[rotate " + FMT.format(new Date()) + " keep tail]\n")
                    .getBytes("UTF-8"));
                out.write(tail);
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
        }
    }

    /** 最近 MAX_LINES 行文本(应用内查看/分享)。 */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : BUFFER) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized int size() {
        return BUFFER.size();
    }

    public static synchronized void clear() {
        BUFFER.clear();
        if (logFile != null && logFile.exists() && !logFile.delete()) {
            w("Log", "日志文件删除失败: " + logFile.getAbsolutePath());
        }
    }

    public static String filePath() {
        return logFile == null ? "(未初始化)" : logFile.getAbsolutePath();
    }

    /** 全局崩溃捕获: 先落盘堆栈,再交回系统默认处理(闪退/黑屏事后可查)。 */
    public static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler def =
            Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            e("Crash", "未捕获异常 thread=" + thread.getName()
                + "\n" + Log.getStackTraceString(ex));
            if (def != null) {
                def.uncaughtException(thread, ex);
            }
        });
    }
}
