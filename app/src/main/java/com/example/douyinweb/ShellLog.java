/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 waitlex
 */
package com.example.douyinweb;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 把运行日志落到手机的「下载」目录，方便拔线/传文件时直接看。
 *
 * 目标位置：
 *   Android 10+ : Download/DouyinShell/douyin-shell.txt   （MediaStore，无需权限）
 *   Android 8/9 : 同上，但需要 WRITE_EXTERNAL_STORAGE 运行时权限
 *   权限被拒时  : Android/data/<包名>/files/Download/DouyinShell/douyin-shell.txt
 *
 * 为什么是 .txt 而不是 .log：MediaStore 会按 MIME 类型补扩展名，
 * DISPLAY_NAME 写 douyin-shell.log 会被存成 douyin-shell.log.txt，
 * 于是下一次按原名字查不到、又新建一个 —— 每启动一次多一个「(1)」。
 * 直接把名字对齐成 .txt，查找才稳定。
 *
 * 三个设计取舍：
 * 1. 只保留尾部 256KB。日志是环形缓冲，超出上限丢最旧的行——调试日志不该把手机写满。
 * 2. 跨会话累积。启动时把上次的内容读回内存，再写「会话开始」分隔线，
 *    这样 App 崩了、被杀了，重启后还能看到上一轮的尾巴。
 * 3. MediaStore 不支持可靠追加，所以每次整体重写（最多 256KB，1.5s 一次，代价可忽略）。
 *
 * 线程安全：@JavascriptInterface 回调、shouldInterceptRequest、UI 线程都会写，
 * 所有共享状态都在 LOCK 内。
 */
public final class ShellLog {

    public static final String LOGCAT_TAG = "DyShell";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String FOLDER = "DouyinShell";
    private static final String FILE_NAME = "douyin-shell.txt";
    /** 前缀，用来兼容「douyin-shell.log.txt」「douyin-shell (3).txt」这些历史命名。 */
    private static final String FILE_STEM = "douyin-shell";
    /** 目录里最多留几个日志文件（含当前在写的这个）。 */
    private static final int KEEP_LOG_COPIES = 2;

    /** 文件上限，超出后丢弃最旧的行。 */
    private static final int MAX_BYTES = 256 * 1024;
    /** 单行上限，防止某个异常把整段 HTML 塞进来。 */
    private static final int MAX_LINE_CHARS = 3000;
    private static final long FLUSH_INTERVAL_MS = 1500L;
    /** 连续写失败这么多次就停止重试，避免坏存储上一直空转。 */
    private static final int MAX_FAILURES = 8;

    private static final Object LOCK = new Object();
    /** 同一时刻只允许一个线程刷盘，否则定时线程和 onPause 会拿同一段 pending 各写一次。 */
    private static final Object FLUSH_LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat CLOCK =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static Context appContext;
    private static Target target;
    private static boolean initialized;
    private static boolean fallback;
    private static boolean dirty;
    private static int bytes;
    private static int failures;
    private static String location = "(未初始化)";
    private static Thread flusher;

    /** 累计写入过多少行，用来算「这次刷盘该追加哪一段」。 */
    private static long totalEmitted;
    /** 已经落到文件里的行号上界。 */
    private static long flushedUpTo;
    /** 需要整体重写（首次、裁剪后、追加失败）。 */
    private static boolean needRewrite = true;
    /** 追加模式是否可用；某些 provider 不支持 "wa"，失败一次就永久退回整写。 */
    private static boolean appendAllowed = true;
    /** 裁剪次数，用来判断重写期间有没有又裁过。 */
    private static int trimCount;

    private ShellLog() {
    }

    // ------------------------------------------------------------------ 生命周期

    /** 幂等。第一次调用时决定落盘位置并回读历史日志。 */
    public static void init(Context context) {
        synchronized (LOCK) {
            if (initialized) return;
            initialized = true;
            appContext = context.getApplicationContext();
        }

        Target t = openTarget(appContext);
        synchronized (LOCK) {
            target = t;
            fallback = (t != null && t.isFallback());
            location = (t == null) ? "(不可写，仅 logcat)" : t.describe();
        }

        if (t != null) {
            String old = readAll(t);
            if (old != null && !old.isEmpty()) {
                synchronized (LOCK) {
                    for (String line : old.split("\n")) addRawLocked(line);
                }
            }
        }
        synchronized (LOCK) {
            // 文件里已经有旧内容了，第一次刷盘必须整写，否则会和追加的内容重起来
            flushedUpTo = totalEmitted;
            needRewrite = true;
        }
        startFlusher();
        // 历史版本每启动一次会留下一个「douyin-shell (n).txt」，越攒越多。
        // 复用逻辑只保证「以后不再新增」，存量得单独扫一次。
        pruneOldCopies();
    }

    /**
     * 删掉同目录下除「最新两个」以外的旧日志副本。
     *
     * 一个日志文件自己封顶 256KB，所以这里维护的是「文件个数」不是「总大小」：
     * 只要个数收敛，就不会无限膨胀。保留 2 个是为了留一份上一次会话的记录可对比。
     *
     * 删自己创建的文件在 API 30+ 正常情况下不需要额外授权；个别 ROM 会抛
     * RecoverableSecurityException，这里直接放弃 —— 删不掉不影响功能。
     */
    private static void pruneOldCopies() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || appContext == null) return;
        try {
            ContentResolver cr = appContext.getContentResolver();
            String[] projection = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.RELATIVE_PATH
            };
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String order = MediaStore.MediaColumns.DATE_MODIFIED + " DESC";
            int kept = 0;
            int deleted = 0;
            try (Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                    selection, new String[]{FILE_STEM + "%"}, order)) {
                if (c == null) return;
                int idCol = c.getColumnIndex(MediaStore.MediaColumns._ID);
                int relCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
                if (idCol < 0 || relCol < 0) return;
                while (c.moveToNext()) {
                    String rel = c.getString(relCol);
                    if (rel == null || !rel.startsWith(
                            Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)) {
                        continue;
                    }
                    if (++kept <= KEEP_LOG_COPIES) continue;
                    Uri u = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idCol));
                    try {
                        if (cr.delete(u, null, null) > 0) deleted++;
                    } catch (Throwable ignored) {
                        // 删不掉就算了，下次再试
                    }
                }
            }
            if (deleted > 0) Log.i(LOGCAT_TAG, "清理旧日志副本 " + deleted + " 个");
        } catch (Throwable t) {
            Log.w(LOGCAT_TAG, "清理旧日志副本失败: " + t);
        }
    }

    /** 每次 Activity 启动时打一条分隔线，方便区分会话。 */
    public static void session(String note) {
        synchronized (LOCK) {
            if (!initialized) return;
            addRawLocked("");
            addRawLocked("═════════ " + STAMP.format(new Date()) + "  " + note + " ═════════");
            dirty = true;
        }
    }

    /**
     * Android 8/9 上首次启动时没有存储权限，会先退到私有目录；
     * 权限拿到后调这个把日志迁回「下载」目录。
     */
    public static void upgradeIfFallback() {
        if (!fallback || appContext == null) return;

        Target t = openTarget(appContext);
        if (t == null || t.isFallback()) return;

        synchronized (LOCK) {
            target = t;
            fallback = false;
            location = t.describe();
            // 换了文件，旧的行数基准作废，整写一遍把历史也带过去
            needRewrite = true;
            appendAllowed = true;
            addRawLocked(clock() + " [LOG] 日志已切换到 " + location);
            dirty = true;
        }
        flushNow();
    }

    // ------------------------------------------------------------------ 写入 API

    public static void i(String tag, String msg) {
        append('I', tag, msg);
    }

    public static void e(String tag, String msg) {
        append('E', tag, msg);
    }

    public static void section(String title) {
        i("TITLE", "----- " + title + " -----");
    }

    public static String location() {
        synchronized (LOCK) {
            return location;
        }
    }

    public static boolean isFallback() {
        synchronized (LOCK) {
            return fallback;
        }
    }

    private static void append(char level, String tag, String msg) {
        if (msg == null) msg = "null";
        synchronized (LOCK) {
            if (!initialized) return;
            addRawLocked(clock() + " " + level + "/" + tag + ": " + msg);
            dirty = true;
        }
        // 同时打到 logcat，USB 调试时 adb logcat -s DyShell 也能看
        if (level == 'E') {
            Log.e(LOGCAT_TAG, tag + ": " + msg);
        } else {
            Log.i(LOGCAT_TAG, tag + ": " + msg);
        }
    }

    private static String clock() {
        return CLOCK.format(new Date());
    }

    // -------------------------------------------------------------------- 刷盘

    private static void startFlusher() {
        synchronized (LOCK) {
            if (flusher != null) return;
            flusher = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(FLUSH_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                        flushNow();
                    }
                }
            }, "dy-shell-log");
            flusher.setDaemon(true);
            flusher.start();
        }
    }

    /**
     * 同步落盘。onPause / onDestroy / 崩溃回调里会调。
     *
     * 正常情况下只把「上次之后新增的那几行」追加进去，几百字节而已；
     * 只有首次写、缓冲区裁剪过（旧行要从文件里去掉）、追加失败这三种情况才整体重写。
     * 这样长时间挂着调试也不会每分钟往闪存写十几兆。
     */
    public static void flushNow() {
        synchronized (FLUSH_LOCK) {
            flushLocked();
        }
    }

    private static void flushLocked() {
        Target t;
        String payload;
        boolean rewrite;
        long emitted;
        int trims;

        synchronized (LOCK) {
            if (!dirty || target == null || LINES.isEmpty()) return;
            t = target;
            emitted = totalEmitted;
            trims = trimCount;

            long pending = totalEmitted - flushedUpTo;
            // 有行在被刷盘之前就被裁掉了，文件里对不上，只能整写
            rewrite = needRewrite || !appendAllowed || pending > LINES.size();

            StringBuilder sb = new StringBuilder(rewrite
                    ? Math.min(bytes + 512, MAX_BYTES + 8192) : (int) Math.max(pending * 80, 128));
            int skip = rewrite ? 0 : (int) (LINES.size() - pending);
            int i = 0;
            for (String line : LINES) {
                if (i++ < skip) continue;
                sb.append(line).append('\n');
            }
            payload = sb.toString();
            dirty = false;
        }

        if (payload.isEmpty()) return;
        write(t, payload, rewrite, emitted, trims);
    }

    private static void write(Target t, String payload, boolean rewrite, long emitted, int trims) {
        OutputStream out = null;
        try {
            out = t.openOutput(!rewrite);
            out.write(payload.getBytes(UTF8));
            out.flush();
            synchronized (LOCK) {
                failures = 0;
                flushedUpTo = emitted;
                // 重写期间又裁过的话，文件头还是旧的，标记留着下次再整写
                if (rewrite && trimCount == trims) needRewrite = false;
            }
        } catch (Exception e) {
            synchronized (LOCK) {
                failures++;
                // 还留在缓冲区里，下轮重试；失败太多次就放弃，别一直空转
                dirty = failures < MAX_FAILURES;
                if (!rewrite) {
                    // 追加不支持（有些 provider 只认 "wt"），下次改成整写
                    appendAllowed = false;
                    needRewrite = true;
                }
            }
            Log.w(LOGCAT_TAG, "写日志失败(" + failures + "/" + MAX_FAILURES + "): " + e);
        } finally {
            closeQuietly(out);
        }
    }

    private static void addRawLocked(String line) {
        if (line == null) return;
        if (line.length() > MAX_LINE_CHARS) {
            line = line.substring(0, MAX_LINE_CHARS) + "…(截断)";
        }
        int size = line.getBytes(UTF8).length + 1;
        LINES.addLast(line);
        totalEmitted++;
        bytes += size;
        while (bytes > MAX_BYTES && LINES.size() > 1) {
            String head = LINES.removeFirst();
            bytes -= head.getBytes(UTF8).length + 1;
            // 文件里还留着这一行，只能靠整写把它去掉
            needRewrite = true;
            trimCount++;
        }
    }

    private static String readAll(Target t) {
        InputStream in = null;
        try {
            in = t.openInput();
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES * 2) break; // 只看尾部就够了
            }
            String s = new String(out.toByteArray(), UTF8);
            return s.length() > MAX_BYTES ? s.substring(s.length() - MAX_BYTES) : s;
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    // ------------------------------------------------------------------ 目标选择

    private static Target openTarget(Context ctx) {
        // 29+ 用 MediaStore 写「下载」目录，不需要任何权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                MediaStoreTarget t = new MediaStoreTarget(ctx);
                if (t.isReady()) return t;
            } catch (Exception e) {
                Log.w(LOGCAT_TAG, "MediaStore 日志目标不可用: " + e);
            }
        } else if (ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            File dir = publicDownloadDir();
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                return new FileTarget(new File(dir, FILE_NAME), false);
            }
        }

        // 兜底：应用私有外部目录，不需要权限，拔线也能在 Android/data 下翻到
        File base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) return null;
        File dir = new File(base, FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return new FileTarget(new File(dir, FILE_NAME), true);
    }

    @SuppressWarnings("deprecation")
    private static File publicDownloadDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
    }

    private interface Target {
        boolean isFallback();

        String describe();

        InputStream openInput() throws IOException;

        /** @param append true 表示追加到末尾，false 表示整体覆盖 */
        OutputStream openOutput(boolean append) throws IOException;

        boolean isReady();
    }

    /** Android 10+ 的「下载」目录。 */
    private static final class MediaStoreTarget implements Target {

        private final Context ctx;
        private Uri uri;

        MediaStoreTarget(Context ctx) {
            this.ctx = ctx;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
            uri = find();
            if (uri == null) uri = create();
        }

        @Override
        public boolean isReady() {
            return uri != null;
        }

        @Override
        public boolean isFallback() {
            return false;
        }

        @Override
        public String describe() {
            return "Download/" + FOLDER + "/" + FILE_NAME;
        }

        /** 同名文件可能存在（上次运行建的），优先复用，避免每启动一次多一个「(1)」。 */
        private Uri find() {
            // 先按精确名找，找不到再用前缀兜底（兼容早期存成 douyin-shell.log.txt 的版本）。
            // 只查一次 LIKE 的话会优先命中历史遗留的「douyin-shell (1).txt」，
            // 新日志就永远写在那个带括号的名字里。
            Uri exact = find("=", FILE_NAME);
            if (exact != null) return exact;
            return find("LIKE", "douyin-shell%");
        }

        private Uri find(String op, String name) {
            ContentResolver cr = ctx.getContentResolver();
            String[] projection = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH
            };
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + " " + op + " ?";
            String sortOrder = MediaStore.MediaColumns._ID + " DESC";
            try (Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                    selection, new String[]{name}, sortOrder)) {
                if (c == null) return null;
                int idCol = c.getColumnIndex(MediaStore.MediaColumns._ID);
                int relCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
                if (idCol < 0) return null;
                while (c.moveToNext()) {
                    String rel = relCol >= 0 ? c.getString(relCol) : null;
                    if (rel == null || !rel.startsWith(Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)) {
                        continue; // 可能只是用户自己在别处有个同名文件
                    }
                    return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            c.getLong(idCol));
                }
            } catch (Exception e) {
                Log.w(LOGCAT_TAG, "查询已有日志文件失败: " + e);
            }
            return null;
        }

        private Uri create() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            try {
                return ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            } catch (Exception e) {
                Log.w(LOGCAT_TAG, "创建日志文件失败: " + e);
                return null;
            }
        }

        @Override
        public InputStream openInput() throws IOException {
            if (uri == null) return null;
            return ctx.getContentResolver().openInputStream(uri);
        }

        @Override
        public OutputStream openOutput(boolean append) throws IOException {
            if (uri == null) {
                uri = create();
                if (uri == null) throw new IOException("无法在 Download 下创建日志文件");
            }
            try {
                OutputStream out = ctx.getContentResolver()
                        .openOutputStream(uri, append ? "wa" : "wt");
                if (out == null) throw new IOException("openOutputStream 返回 null");
                return out;
            } catch (Exception first) {
                // 追加不支持：直接抛给上层，让它改成整写，不要在这里瞎猜
                if (append) throw new IOException(first);
                // 整写失败多半是条目被用户在文件管理器里删了，重建一个再试一次
                Uri again = create();
                if (again == null) throw new IOException(first);
                uri = again;
                OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt");
                if (out == null) throw new IOException(first);
                return out;
            }
        }
    }

    /** 直接写文件，覆盖 Android 8/9 的公共目录与私有目录兜底。 */
    private static final class FileTarget implements Target {

        private final File file;
        private final boolean fallback;

        FileTarget(File file, boolean fallback) {
            this.file = file;
            this.fallback = fallback;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public boolean isFallback() {
            return fallback;
        }

        @Override
        public String describe() {
            return file.getAbsolutePath();
        }

        @Override
        public InputStream openInput() throws IOException {
            return file.isFile() ? new FileInputStream(file) : null;
        }

        @Override
        public OutputStream openOutput(boolean append) throws IOException {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("mkdirs 失败: " + dir);
            }
            return new FileOutputStream(file, append);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // 关不掉也没别的办法
        }
    }
}
