/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 waitlex
 */
package com.example.douyinweb;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/**
 * WebView 自有磁盘缓存的治理。
 *
 * <h3>真正会膨胀的是哪一块</h3>
 * 实测这台机器上（用了两天）：
 * <pre>
 *   cache/WebView/Default/HTTP Cache/Cache_Data    181 MB   ← HTTP 响应体
 *   cache/WebView/Default/HTTP Cache/Code Cache     17 MB   ← V8 编译产物
 *   app_webview/Default                              5 MB   ← Cookie / IndexedDB / LocalStorage
 * </pre>
 * 前两项是**缓存**，涨到几百兆也不会自己退；第三项是**登录态**，一个字节都不能动。
 *
 * <h3>为什么分两条阈值</h3>
 * <ul>
 *   <li>{@code Code Cache} 清掉只费 CPU（JS 要重新编译），不费流量 —— 阈值给 40MB。</li>
 *   <li>{@code Cache_Data} 清掉要重新下载首屏 JS/CSS（十几兆流量）—— 阈值给 120MB，
 *       只有在明显超量时才清，平时留着能省流量。</li>
 * </ul>
 * 抖音网页版每次发版都会换资源 hash，旧版本的东西永远命中不了，纯占地方。
 *
 * <h3>只在启动时动手</h3>
 * WebView 跑起来之后删它的缓存目录容易踩到文件句柄（Chromium 有自己的锁和索引）。
 * 所以这里只在「WebView 还没创建」的启动窗口里做，其余时间不碰 —— 反正 Chromium
 * 自身对 HTTP 缓存也有上限，短期内不会失控。
 */
final class WebViewCache {

    /** HTTP 响应体的上限。超了整块清掉。 */
    private static final long DATA_LIMIT = 120L * 1024 * 1024;
    /** V8 编译产物的上限。清它只费 CPU，阈值可以低一些。 */
    private static final long CODE_LIMIT = 40L * 1024 * 1024;

    private WebViewCache() {
    }

    /** {@code /data/data/<pkg>/cache/WebView/Default/HTTP Cache}。 */
    private static File httpCache(Context ctx) {
        return new File(ctx.getCacheDir(), "WebView/Default/HTTP Cache");
    }

    static File dataDir(Context ctx) {
        return new File(httpCache(ctx), "Cache_Data");
    }

    static File codeDir(Context ctx) {
        return new File(httpCache(ctx), "Code Cache");
    }

    /** 两份可清理缓存（不含登录态）的合计占用。 */
    static long cachedBytes(Context ctx) {
        return MediaCache.dirSize(dataDir(ctx)) + MediaCache.dirSize(codeDir(ctx));
    }

    /**
     * 超过各自阈值就把那一块删掉。WebView 下次启动会自己重建索引。
     *
     * @return 实际释放的字节数（都没超阈值返回 0）
     */
    static long sweep(Context ctx) {
        long freed = 0;
        try {
            long code = MediaCache.dirSize(codeDir(ctx));
            if (code > CODE_LIMIT) {
                MediaCache.deleteTree(codeDir(ctx));
                freed += code;
                ShellLog.i("CACHE", "清掉 WebView 编译缓存 " + mb(code));
            }
            long data = MediaCache.dirSize(dataDir(ctx));
            if (data > DATA_LIMIT) {
                MediaCache.deleteTree(dataDir(ctx));
                freed += data;
                ShellLog.i("CACHE", "清掉 WebView 响应缓存 " + mb(data)
                        + "（首屏资源会重新下载一次）");
            }
        } catch (Throwable t) {
            ShellLog.e("CACHE", "WebView 缓存清理失败 " + t);
        }
        return freed;
    }

    /** 一行日志，用来报账。 */
    static String stats(Context ctx) {
        return mb(MediaCache.dirSize(dataDir(ctx))) + " + "
                + mb(MediaCache.dirSize(codeDir(ctx))) + "（响应 + 编译）";
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1fMB", bytes / 1048576.0);
    }
}
