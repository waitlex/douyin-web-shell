/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 waitlex
 */
package com.example.douyinweb;

import android.content.Context;
import android.os.StatFs;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 视频磁盘缓存 —— 按「响应单元」缓存，不是按整个文件。
 *
 * <h3>为什么需要它</h3>
 * WebView 自带的 HTTP 缓存对视频基本不起作用：抖音 CDN 的 mp4 不带可缓存的
 * Cache-Control，WebView 也不愿意把几十兆媒体塞进自己的缓存目录。
 * 实测「滑到下一条 → 再滑回来」，同一条视频会从头重新下一遍（3.6MB / 5s）。
 * 反复上下滑就是纯烧流量。
 *
 * <h3>为什么按响应单元、而不是按整个视频</h3>
 * 抖音播放器是 fetch + MSE：把片子切成 200KB 左右的分片，一段段要
 * （日志里能看到 {@code Range: bytes=7-204807}、{@code bytes=0-204800} 这种）。
 * 如果只在「一次拿到整个文件」时才缓存，那几乎永远存不下来。
 *
 * 所以缓存的最小单位改成**一次响应**：请求的 URL + Range 完全相同的下一次请求，
 * 直接把上次那份字节原样吐回去。分片边界通常固定，回看同一视频时命中率很高。
 *
 * <h3>几个必须守住的点</h3>
 * <ul>
 *   <li><b>必须转发 CORS 头</b>。少了 {@code Access-Control-Allow-Origin}，
 *       播放器的 fetch 会被判跨域失败，然后换个 range 从头再要 ——
 *       实测 1 秒内 7 次 {@code bytes=0-}，缓存直接变成流量放大器。</li>
 *   <li><b>只有整段读完才落盘</b>。播放器 seek / 用户划走都会提前 close，
 *       半截文件存下来会让下次播放拿到残缺数据。</li>
 *   <li>任何异常都 {@code return null}，退回 WebView 原生加载 —— 缓存可以失效，
 *       视频不能不能播。</li>
 *   <li>总量封顶 {@link #MAX_BYTES}，超了按最久未使用删。</li>
 * </ul>
 */
public final class MediaCache {

    /**
     * 总盘子的**硬上限**：可用空间再大也不超过这个数。
     *
     * 原来是 512MB，配合「放在 {@code getExternalFilesDir()}」就成了这台机器上
     * 实测 375MB 的常驻占用。问题不只是大 —— files 目录在系统眼里是「应用数据」
     * 而不是「缓存」，设置里的『清除缓存』、各家管家、系统低存储驱逐**全都碰不到它**，
     * 用户看到的就是「装完越用越胖，还清不掉」。所以上限和位置一起改：
     * 目录挪到 {@code getExternalCacheDir()}，上限按可用空间自适应（见 {@link #refreshLimit}）。
     */
    private static final long MAX_BYTES = 192L * 1024 * 1024;
    /** 打底下限。再小就等于没有缓存，回看照样费流量。 */
    private static final long MIN_BYTES = 16L * 1024 * 1024;
    /** 剩余空间低于 1GB 时切「应急档」：缓存让位给拍照/更新。 */
    private static final long TIGHT_AVAIL = 1024L * 1024 * 1024;
    private static final long TIGHT_BYTES = 32L * 1024 * 1024;
    /** 单个响应单元的上限：一条长视频整段 100MB+，整段存下来太占地方。 */
    private static final long MAX_UNIT_BYTES = 24L * 1024 * 1024;
    /** 单个静态资源（JS/CSS/字体/图片）的上限，超了就交给 WebView 自己处理。 */
    private static final long MAX_ASSET_BYTES = 6L * 1024 * 1024;
    /** 静态资源的有效期。文件名带 hash，过期重下一次也不会有什么代价。 */
    private static final long ASSET_TTL_MS = 3L * 24 * 60 * 60 * 1000;
    /** 缓存单元的两种类型，混在一个目录里靠 key 前缀区分。 */
    private static final String VIDEO_PREFIX = "V|";
    private static final String ASSET_PREFIX = "A|";

    private static final String META_SUFFIX = ".meta";

    private final File dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-cache");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong savedBytes = new AtomicLong();
    private final AtomicLong spentBytes = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong assetHits = new AtomicLong();
    private final AtomicLong assetMisses = new AtomicLong();

    private volatile long trimmedAt;
    /** 当前生效的上限，由 {@link #refreshLimit()} 按可用空间算出来。 */
    private volatile long limitBytes = MAX_BYTES;

    public MediaCache(Context ctx) {
        File base = null;
        try {
            // 用 cache 目录而不是 files：只有 cache 才会被系统当成「缓存」，
            // 用户在设置里点『清除缓存』、系统在低存储时驱逐，都能真正释放掉。
            base = ctx.getExternalCacheDir();
        } catch (Exception ignored) {
            // 外部存储没挂载就退回内部缓存目录
        }
        if (base == null) base = ctx.getCacheDir();
        dir = new File(base, "media-cache");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        dropLegacyDir(ctx);
        refreshLimit();
        io.execute(this::trim);
        ShellLog.i("CACHE", "视频缓存目录 " + dir.getAbsolutePath()
                + "，上限 " + mb(limitBytes));
    }

    /**
     * 老版本把缓存写在 {@code getExternalFilesDir()/media-cache}，那块地方不会被任何
     * 系统清理机制回收，会一直躺在那里占着。升级后第一次启动直接整棵删掉 ——
     * 里面全是可再生的视频分片和静态资源，删了只会多花一次流量。
     */
    private void dropLegacyDir(Context ctx) {
        try {
            File old = new File(ctx.getExternalFilesDir(null), "media-cache");
            if (!old.isDirectory()) return;
            long freed = dirSize(old);
            deleteTree(old);
            ShellLog.i("CACHE", "已清掉旧缓存目录（files/media-cache），释放 " + mb(freed));
        } catch (Throwable t) {
            ShellLog.e("CACHE", "清旧缓存目录失败 " + t);
        }
    }

    /**
     * 上限按可用空间自适应：{@code min(192MB, 可用/16)}，可用不足 1GB 时切 32MB。
     *
     * 固定值的问题在低存储机器上最明显 —— 手机只剩 1.5GB，缓存还敢吃 512MB，
     * 而且用户根本不知道这 512MB 在哪、怎么清。
     */
    private void refreshLimit() {
        long avail = Long.MAX_VALUE;
        try {
            avail = new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Throwable ignored) {
            // 拿不到就按硬上限走
        }
        long lim = avail < TIGHT_AVAIL ? TIGHT_BYTES : Math.min(MAX_BYTES, avail / 16);
        limitBytes = Math.max(MIN_BYTES, lim);
    }

    public String stats() {
        return "视频命中 " + hits.get() + " / 未命中 " + misses.get()
                + "；资源命中 " + assetHits.get() + " / 未命中 " + assetMisses.get()
                + "；累计省 " + mb(savedBytes.get()) + " 下 " + mb(spentBytes.get());
    }

    /** 一行报账：实际占用 / 当前上限。会遍历目录，别在主线程调。 */
    public String footprint() {
        return String.format(Locale.ROOT, "视频缓存 %.1fMB / 上限 %.1fMB",
                sizeBytes() / 1048576.0, limitBytes / 1048576.0);
    }

    // ---------------------------------------------------------------- 拦截入口

    /** @return null 表示「不插手，交给 WebView 原生加载」 */
    public WebResourceResponse intercept(WebResourceRequest request) {
        try {
            if (request == null) return null;
            String method = request.getMethod();
            if (method == null || !"GET".equalsIgnoreCase(method)) return null;
            String url = request.getUrl() == null ? null : request.getUrl().toString();
            if (url == null) return null;

            Map<String, String> reqHeaders = request.getRequestHeaders();
            if (looksLikeVideo(url)) return interceptVideo(url, reqHeaders);
            if (looksLikeAsset(url)) return interceptAsset(url, reqHeaders);
            return null;
        } catch (Throwable t) {
            ShellLog.e("CACHE", "拦截失败，退回原生加载: " + t);
        }
        return null;
    }

    private WebResourceResponse interceptVideo(String url, Map<String, String> reqHeaders) {
        try {
            String range = header(reqHeaders, "Range");
            String origin = header(reqHeaders, "Origin");

            String key = keyOf(VIDEO_PREFIX + unitKey(url, range));
            File data = new File(dir, key + ".bin");
            Meta meta = readMeta(key);

            if (meta != null && meta.length > 0
                    && data.isFile() && data.length() == meta.length) {
                //noinspection ResultOfMethodCallIgnored
                data.setLastModified(System.currentTimeMillis()); // LRU 用
                InputStream body = new FileInputStream(data);
                WebResourceResponse hit = build(meta, body, origin);
                hits.incrementAndGet();
                savedBytes.addAndGet(meta.length);
                ShellLog.i("CACHE", "命中 " + shortUrl(url) + " " + mb(meta.length)
                        + " [" + (range == null ? "whole" : range) + "]（" + stats() + "）");
                return hit;
            }

            WebResourceResponse fresh = fetchAndTee(url, reqHeaders, range, key, origin);
            if (fresh != null) {
                misses.incrementAndGet();
                return fresh;
            }
        } catch (Throwable t) {
            ShellLog.e("CACHE", "拦截失败，退回原生加载: " + t);
        }
        return null;
    }

    /**
     * 只有抖音视频本体走缓存：头像、封面图、接口一律不碰。
     *
     * 注意 {@code /aweme/v1/play/} 那个跳转入口**不拦** —— 它是个 302，
     * 拦下来会打断播放器自己的重定向处理，让真正吃流量的 CDN 直链拿不到正确 URL。
     */
    private static boolean looksLikeVideo(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (!u.startsWith("http")) return false;
        if (u.contains("/aweme/v1/play/")) return false;
        return u.contains(".mp4")
                || u.contains("/video/tos/")
                || u.contains("douyinvod.com")
                || u.contains("bytecdntp.com");
    }

    /**
     * 网页的「壳」：JS / CSS / 字体 / 图片。这些文件几乎都是带 hash 的不可变文件，
     * 每次冷启动重拉一遍纯属浪费。
     *
     * 故意不缓存：
     * - 主文档 HTML（登录态、推荐流都要拿最新的）
     * - /aweme/、/api/ 接口（返回的是用户相关数据）
     * - 带 msToken / verify / captcha / login 的地址
     */
    private static boolean looksLikeAsset(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (!u.startsWith("http")) return false;
        if (u.contains("/aweme/") || u.contains("/api/")
                || u.contains("mstoken") || u.contains("verify")
                || u.contains("captcha") || u.contains("login")
                || u.contains("logout") || u.contains("passport")
                || u.contains("/log/")) return false;
        String path = u;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        String[] ext = {".js", ".css", ".woff", ".woff2", ".ttf", ".otf",
                ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico"};
        for (String e : ext) {
            if (path.endsWith(e)) return true;
        }
        return false;
    }

    private WebResourceResponse interceptAsset(String url, Map<String, String> reqHeaders) {
        try {
            String key = keyOf(ASSET_PREFIX + url);
            File data = new File(dir, key + ".bin");
            Meta meta = readMeta(key);
            long now = System.currentTimeMillis();
            if (meta != null && meta.length > 0
                    && (meta.savedAt <= 0 || now - meta.savedAt < ASSET_TTL_MS)
                    && data.isFile() && data.length() == meta.length) {
                //noinspection ResultOfMethodCallIgnored
                data.setLastModified(now); // LRU 用
                assetHits.incrementAndGet();
                savedBytes.addAndGet(meta.length);
                ShellLog.i("CACHE", "资源命中 " + shortUrl(url) + " " + mb(meta.length));
                return buildAsset(meta, new FileInputStream(data));
            }
            WebResourceResponse fresh = fetchAsset(url, reqHeaders, key);
            if (fresh != null) assetMisses.incrementAndGet();
            return fresh;
        } catch (Throwable t) {
            ShellLog.e("CACHE", "资源读取失败 " + shortUrl(url) + " : " + t);
            return null;
        }
    }

    private static WebResourceResponse buildAsset(Meta meta, InputStream body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", meta.mime);
        headers.put("Content-Length", String.valueOf(meta.length));
        headers.put("Cache-Control", "public, max-age=2592000");
        headers.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse(meta.mime, null, 200, "OK", headers, body);
    }

    /** 静态资源一次性读进内存再落盘：比 TeeStream 可靠，几 MB 的开销可以忽略。 */
    private WebResourceResponse fetchAsset(String url, Map<String, String> reqHeaders, String key) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            if (reqHeaders != null) {
                for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                    String k = e.getKey();
                    if (k == null) continue;
                    String lk = k.toLowerCase(Locale.ROOT);
                    // 条件头会让服务器回 304（没有 body），压缩头会让字节数和声明长度不符
                    if (lk.equals("accept-encoding") || lk.equals("host")
                            || lk.equals("connection") || lk.equals("content-length")
                            || lk.equals("if-none-match") || lk.equals("if-modified-since")
                            || lk.equals("range")) continue;
                    try {
                        conn.setRequestProperty(k, e.getValue());
                    } catch (Exception ignored) {
                    }
                }
            }
            int code = conn.getResponseCode();
            long len = conn.getContentLengthLong();
            if (code != 200 || len > MAX_ASSET_BYTES) {
                conn.disconnect();
                return null;
            }
            InputStream body = conn.getInputStream();
            // 抖音的很多 JS chunk 是 chunked 传输，Content-Length = -1。
            // 按声明长度读会把它们全跳过去，等于布局的大头一个都没缓存。
            byte[] bytes = len > 0
                    ? readFully(body, (int) len)
                    : readUpTo(body, (int) MAX_ASSET_BYTES);
            if (bytes == null) {
                conn.disconnect();
                return null;
            }
            String mime = conn.getContentType();
            if (mime == null) mime = guessMime(url);
            int semi = mime.indexOf(';');
            if (semi > 0) mime = mime.substring(0, semi).trim();

            Meta meta = new Meta();
            meta.status = 200;
            meta.length = bytes.length;
            meta.mime = mime;
            meta.savedAt = System.currentTimeMillis();

            ensureDir();
            File tmp = new File(dir, key + "-" + System.nanoTime() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            File dst = new File(dir, key + ".bin");
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            if (!tmp.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            writeMeta(key, meta);
            spentBytes.addAndGet(bytes.length);
            if (System.currentTimeMillis() - trimmedAt > 20000) {
                trimmedAt = System.currentTimeMillis();
                io.execute(this::trim);
            }
            ShellLog.i("CACHE", "资源回源 " + shortUrl(url) + " " + mb(bytes.length));
            return buildAsset(meta, new ByteArrayInputStream(bytes));
        } catch (Throwable t) {
            ShellLog.e("CACHE", "资源回源失败 " + shortUrl(url) + " : " + t);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    private static byte[] readFully(InputStream in, int expect) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(expect);
            byte[] buf = new byte[8192];
            int n, total = 0;
            while (total < expect) {
                n = in.read(buf, 0, Math.min(buf.length, expect - total));
                if (n < 0) break;
                out.write(buf, 0, n);
                total += n;
            }
            return total == expect ? out.toByteArray() : null;
        } catch (IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** chunked 响应用：读到 EOF 为止，超过上限就放弃整份（不落半截文件）。 */
    private static byte[] readUpTo(InputStream in, int cap) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, 8192));
            byte[] buf = new byte[8192];
            int n, total = 0;
            while (total < cap) {
                n = in.read(buf, 0, Math.min(buf.length, cap - total));
                if (n < 0) break;
                out.write(buf, 0, n);
                total += n;
            }
            if (total >= cap && in.read() >= 0) return null; // 比上限还大
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String guessMime(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        if (u.endsWith(".js")) return "application/javascript";
        if (u.endsWith(".css")) return "text/css";
        if (u.endsWith(".woff2")) return "font/woff2";
        if (u.endsWith(".woff")) return "font/woff";
        if (u.endsWith(".svg")) return "image/svg+xml";
        if (u.endsWith(".png")) return "image/png";
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
        if (u.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        String v = headers.get(name);
        if (v != null) return v;
        String lower = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 同一个 URL + 同一个 Range = 同一个缓存单元。{@code bytes=0-} 等价于整段。 */
    private static String unitKey(String url, String range) {
        String r = range == null ? "" : range.trim();
        if (r.equals("bytes=0-")) r = "";
        return url + "|" + r;
    }

    // ---------------------------------------------------------------- 组装响应

    private static WebResourceResponse build(Meta meta, InputStream body, String origin) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", meta.mime);
        headers.put("Accept-Ranges", "bytes");
        headers.put("Content-Length", String.valueOf(meta.length));
        if (meta.contentRange != null && meta.contentRange.length() > 0) {
            headers.put("Content-Range", meta.contentRange);
        }
        cors(headers, origin);
        String reason = meta.status == 206 ? "Partial Content" : "OK";
        return new WebResourceResponse(meta.mime, null, meta.status, reason, headers, body);
    }

    /**
     * CORS 头一个都不能丢。
     *
     * 抖音播放器是 fetch + MSE 拉分片，响应里没有 ACAO 就会被判跨域失败，
     * 它会换个 range 从头再要一遍（实测 1 秒 7 次 bytes=0-）。
     * 页面是直接以 www.douyin.com 的身份发起的，回它自己的 Origin 最稳。
     */
    private static void cors(Map<String, String> headers, String origin) {
        boolean hasOrigin = origin != null && origin.length() > 0;
        headers.put("Access-Control-Allow-Origin", hasOrigin ? origin : "*");
        headers.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        if (hasOrigin) headers.put("Access-Control-Allow-Credentials", "true");
        headers.put("Access-Control-Expose-Headers",
                "Content-Length, Content-Range, Accept-Ranges, Content-Type");
    }

    // ---------------------------------------------------------------- 回源 + 边下边写

    private WebResourceResponse fetchAndTee(String url, Map<String, String> reqHeaders,
                                            String range, String key, String origin) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            // 原样带回页面发出的头（UA / Referer / Cookie 都可能被 CDN 校验）
            if (reqHeaders != null) {
                for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                    String k = e.getKey();
                    if (k == null) continue;
                    String lk = k.toLowerCase(Locale.ROOT);
                    if (lk.equals("accept-encoding") || lk.equals("host")
                            || lk.equals("connection") || lk.equals("content-length")
                            || lk.equals("origin")) {
                        continue;
                    }
                    try {
                        conn.setRequestProperty(k, e.getValue());
                    } catch (Exception ignored) {
                        // 某些头不允许手动设置
                    }
                }
            }

            int code = conn.getResponseCode();
            String mime = conn.getContentType();
            if (mime == null) mime = "video/mp4";
            int semi = mime.indexOf(';');
            if (semi > 0) mime = mime.substring(0, semi).trim();

            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                conn.disconnect();
                return null;
            }

            long len = conn.getContentLengthLong();
            String contentRange = conn.getHeaderField("Content-Range");

            Meta meta = new Meta();
            meta.status = (code == 206) ? 206 : 200;
            meta.mime = mime;
            meta.contentRange = contentRange;
            meta.length = len;

            boolean cacheable = (code == 200 || code == 206) && len > 0 && len <= MAX_UNIT_BYTES;
            if (cacheable) {
                spentBytes.addAndGet(len);
                ensureDir();
                File tmp = new File(dir, key + "-" + System.nanoTime() + ".part");
                File dst = new File(dir, key + ".bin");
                OutputStream out = new FileOutputStream(tmp);
                in = new TeeStream(in, out, tmp, dst, len, meta, this, key, url);
                ShellLog.i("CACHE", "回源 " + shortUrl(url) + " " + mb(len)
                        + " code=" + code + " [" + (range == null ? "whole" : range) + "]");
            } else {
                ShellLog.i("CACHE", "直通 " + shortUrl(url) + " code=" + code
                        + " len=" + len + " [" + (range == null ? "whole" : range) + "]");
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", mime);
            headers.put("Accept-Ranges", "bytes");
            if (len > 0) headers.put("Content-Length", String.valueOf(len));
            if (contentRange != null) headers.put("Content-Range", contentRange);
            cors(headers, origin);

            String reason = meta.status == 206 ? "Partial Content" : "OK";
            return new WebResourceResponse(mime, null, meta.status, reason, headers, in);
        } catch (Throwable t) {
            ShellLog.e("CACHE", "回源失败 " + shortUrl(url) + " : " + t);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- 元信息 / 落盘

    private static String keyOf(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * 写之前补一次目录。缓存挪到 {@code getExternalCacheDir()} 之后，
     * 系统在低存储时会**整个删掉 cache 目录**（这是 cache 该有的待遇），
     * 下一次写入就会因为父目录不存在而失败。这里补一手，代价是一次 exists()。
     */
    private void ensureDir() {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    private void writeMeta(String key, Meta meta) {
        ensureDir();
        try (FileOutputStream out = new FileOutputStream(new File(dir, key + META_SUFFIX))) {
            String cr = meta.contentRange == null ? "" : meta.contentRange;
            out.write((meta.status + "\n" + meta.length + "\n" + meta.mime + "\n" + cr
                    + "\n" + meta.savedAt + "\n").getBytes("UTF-8"));
        } catch (Exception e) {
            ShellLog.e("CACHE", "写元信息失败 " + e);
        }
    }

    private Meta readMeta(String key) {
        File f = new File(dir, key + META_SUFFIX);
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) return null;
            String[] parts = new String(buf, 0, n, "UTF-8").split("\n", -1);
            if (parts.length < 4) return null;
            Meta m = new Meta();
            m.status = Integer.parseInt(parts[0].trim());
            m.length = Long.parseLong(parts[1].trim());
            m.mime = parts[2].trim().isEmpty() ? "video/mp4" : parts[2].trim();
            m.contentRange = parts[3].trim().isEmpty() ? null : parts[3].trim();
            // 第五行是静态资源的写入时间；老版本的 meta 没有这一行，留 0 表示不过期
            if (parts.length > 4 && !parts[4].trim().isEmpty()) {
                try {
                    m.savedAt = Long.parseLong(parts[4].trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private void onCached(String key, Meta meta) {
        writeMeta(key, meta);
        long now = System.currentTimeMillis();
        if (now - trimmedAt > 20000) {
            trimmedAt = now;
            io.execute(this::trim);
        }
    }

    /** 超上限就按「最久没被用过」删。data / meta 成对删。 */
    private void trim() {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            List<File> data = new ArrayList<>();
            long total = 0;
            for (File f : files) {
                if (f.getName().endsWith(".bin")) {
                    data.add(f);
                    total += f.length();
                } else if (f.getName().endsWith(".part")) {
                    // 上次进程被杀留下的半截
                    if (System.currentTimeMillis() - f.lastModified() > 3600_000L) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            if (total <= limitBytes) return;
            Collections.sort(data, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : data) {
                if (total <= limitBytes) break;
                long len = f.length();
                String base = f.getName().substring(0, f.getName().length() - 4);
                if (f.delete()) total -= len;
                //noinspection ResultOfMethodCallIgnored
                new File(dir, base + META_SUFFIX).delete();
            }
            ShellLog.i("CACHE", "清盘完成，当前占用 " + mb(total));
        } catch (Throwable t) {
            ShellLog.e("CACHE", "清盘失败 " + t);
        }
    }

    // ---------------------------------------------------------------- 外部治理

    /**
     * 让缓存立刻按当前可用空间收敛一次。切后台、系统报内存紧张时调。
     *
     * trim 本身是幂等的（没超上限就什么都不做），所以这里可以放心高频调用。
     */
    public void trimNow() {
        refreshLimit();
        io.execute(this::trim);
    }

    /** 当前磁盘占用（含 meta），用来在日志里报账。 */
    public long sizeBytes() {
        return dirSize(dir);
    }

    /** 递归统计目录大小。拿不到就返回 0，绝不抛。 */
    public static long dirSize(File f) {
        try {
            if (f == null || !f.exists()) return 0;
            if (f.isFile()) return f.length();
            File[] kids = f.listFiles();
            if (kids == null) return 0;
            long sum = 0;
            for (File k : kids) sum += dirSize(k);
            return sum;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 整棵删掉。缓存目录里只会有普通文件，不需要处理符号链接。 */
    public static void deleteTree(File f) {
        try {
            if (f == null || !f.exists()) return;
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) deleteTree(k);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static String mb(long bytes) {
        if (bytes < 0) return "?";
        return String.format(Locale.ROOT, "%.2fMB", bytes / 1048576.0);
    }

    private static String shortUrl(String url) {
        try {
            int slash = url.lastIndexOf('/');
            String tail = slash > 0 ? url.substring(slash + 1) : url;
            int q = tail.indexOf('?');
            if (q > 0) tail = tail.substring(0, q);
            return tail.length() > 22 ? tail.substring(0, 22) : tail;
        } catch (Exception e) {
            return "?";
        }
    }

    private static final class Meta {
        int status;
        long length;
        String mime = "video/mp4";
        String contentRange;
        long savedAt;
    }

    /**
     * 边读边写。
     *
     * 只有「读满预期字节数」才算成功落盘 —— 播放器 seek / 用户划走都会提前 close，
     * 那种半截文件存下来，下次播放会拿到残缺数据。
     */
    private static final class TeeStream extends FilterInputStream {
        private final OutputStream out;
        private final File tmp;
        private final File dst;
        private final long expect;
        private final Meta meta;
        private final MediaCache owner;
        private final String key;
        private final String url;

        private long written;
        private boolean closed;
        private boolean done;

        TeeStream(InputStream in, OutputStream out, File tmp, File dst,
                  long expect, Meta meta, MediaCache owner, String key, String url) {
            super(in);
            this.out = out;
            this.tmp = tmp;
            this.dst = dst;
            this.expect = expect;
            this.meta = meta;
            this.owner = owner;
            this.key = key;
            this.url = url;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) writeBuf(new byte[]{(byte) b}, 0, 1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) writeBuf(b, off, n);
            return n;
        }

        private void writeBuf(byte[] b, int off, int len) {
            if (done) return;
            try {
                out.write(b, off, len);
                written += len;
            } catch (IOException e) {
                // 磁盘满之类：放弃缓存，但不影响这次播放
                done = true;
                closeQuietly();
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            boolean complete = !done && expect > 0 && written >= expect;
            closeQuietly();
            try {
                super.close();
            } catch (Exception ignored) {
            }
            if (complete) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                if (tmp.renameTo(dst)) {
                    owner.onCached(key, meta);
                    ShellLog.i("CACHE", "已缓存 " + shortUrl(url) + " " + mb(expect)
                            + "（" + owner.stats() + "）");
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            } else {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }

        private void closeQuietly() {
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }
}
