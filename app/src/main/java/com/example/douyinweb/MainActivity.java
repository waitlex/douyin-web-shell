package com.example.douyinweb;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * 单 Activity 的 WebView 壳，只做三件事：
 * 1. 桌面 UA + 桌面视口，让 douyin.com 以为是电脑；
 * 2. 注入 assets/douyin.user.js 做事件与 DOM 适配；
 * 3. 拦掉所有非 http(s) 的 scheme，避免被拉起原生 App。
 *
 * 界面分三层：WebView（引擎）→ FeedChrome（控制条）→ LoginGate（登录引导）。
 * 页面内容、播放器、评论、登录流程全部交给网页版，自己只做控制层。
 *
 * 刻意不引 AndroidX：整个 APK 只有框架 API，方便用 build.sh 离线直编。
 */
public class MainActivity extends Activity
        implements FeedChrome.Listener, LoginGate.Listener {

    /**
     * 冷启动落地页：推荐流，不是首页。
     *
     * 首页（www.douyin.com/）是「精选」四列网格，进去还得再点一下；
     * 而精选里点开的播放器是 modal 形态，它的「下一个」只在同一作者/同一合集里循环 ——
     * 刷十条能刷出十次同一个人。?recommend=1 是真正的推荐队列。
     *
     * from_nav=1 不能省：不带它时服务端会把请求重定向回 /jingxuan。
     */
    private static final String START_URL = "https://www.douyin.com/?recommend=1&from_nav=1";
    private static final String SCRIPT_ASSET = "douyin.user.js";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;

    /** 调试期开着，方便 chrome://inspect；长期使用建议改成 false 后重新出包。 */
    private static final boolean ENABLE_WEBVIEW_DEBUG = true;

    /** 日志总开关。关掉后只剩 logcat，不再写 Download 目录。 */
    private static final boolean ENABLE_FILE_LOG = true;
    /** 每个页面最多记多少条 console（抖音控制台很吵，不设上限会淹掉有用信息）。 */
    private static final int MAX_CONSOLE_LINES = 150;
    /** 每个页面最多记几次主文档请求头，用来核对 UA / Client Hints 是否真的换了。 */
    private static final int MAX_HEADER_SAMPLES = 3;

    /** 启动时是否尝试「沉浸模式」（把播放器铺满屏幕）。失败会自动退回网页模式。 */
    private static final boolean DEFAULT_IMMERSIVE = true;
    /** 启动时是否进入精简模式：隐藏左侧导航 / 搜索框，只留视频。 */
    private static final boolean DEFAULT_FOCUS = true;
    /** 登录态轮询间隔，只在用户点了「去登录」之后才跑。 */
    private static final long LOGIN_POLL_MS = 2000;
    /** 轮询多久放弃，避免一直空转。 */
    private static final int LOGIN_POLL_MAX = 90;
    /** 等这么久还没进到推荐流就直接露出页面，免得一直对着黑屏。 */
    private static final long REVEAL_TIMEOUT_MS = 18000;
    /** 开屏页最短显示时间。到点之前就算页面已经就绪也继续盖着。 */
    private static final long SPLASH_MIN_MS = 2000;

    private FrameLayout root;
    /** 纵向容器：WebView（权重 1）+ Dock（wrap_content），两者互不重叠。 */
    private LinearLayout column;
    private WebView webView;
    private String injectedJs;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> filePathCallback;
    /** Android 13+ 的返回回调：手势返回 / 返回键都从这里走，才能统一改成「回桌面不杀进程」。 */
    private OnBackInvokedCallback backCallback;

    private int consoleLines;
    private int headerSamples;
    private int droppedConsole;
    private long pageStartedAt;

    private FeedChrome chrome;
    private LoginGate loginGate;
    private boolean immersive = DEFAULT_IMMERSIVE;
    /** 视频磁盘缓存：抖音 CDN 的 mp4 不进 WebView 缓存，重复观看全靠这层省流量。 */
    private MediaCache mediaCache;
    private boolean focusMode = DEFAULT_FOCUS;
    private boolean loggedIn;
    private boolean awaitingLogin;
    private boolean gateShownOnce;
    private int loginPolls;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 盖在 WebView 上的黑幕，进到推荐流之前挡住「精选」网格，避免一打开先闪一下列表。 */
    private View cover;
    private boolean revealed;

    /** 开屏页。和 cover 是两层：cover 负责挡内容，splash 负责「看起来像个 App」。 */
    private View splash;
    private TextView splashHint;
    private boolean splashTimeUp;
    private boolean splashGone;

    /** 全屏：收起 Dock + 隐藏系统栏，画面铺满整块屏幕。 */
    private boolean fullscreen;

    // 评论抽屉的开关由页面持有（__DY.sheetOpen()），原生只缓存一份用于
    // 侧滑手势的节流判断。真正开合走 pageCommentToggle()。
    private boolean commentOpen;
    // 侧滑收起评论：起点在下半屏（评论抽屉所在区域）、避开屏幕左缘 28dp，
    // 然后向右滑过 60dp 且横向位移大于纵向 1.8 倍。注意 DOWN 不抢事件。
    private float swipeStartX;
    private float swipeStartY;
    /** 本次手势是否「可能」是侧滑。为 true 也不抢事件，只在 MOVE 里看方向。 */
    private boolean swipeTrack;

    /** 上一个视频的作者昵称。变了就说明翻页了，点赞/收藏的乐观态要清掉。 */
    private String lastAuthor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ENABLE_FILE_LOG) {
            ShellLog.init(this);
            ShellLog.session("Activity 启动" + (savedInstanceState == null ? "（新进程）" : "（重建）"));
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(Color.BLACK);
        }

        logDeviceInfo();

        // WebView 自己的磁盘缓存（HTTP Cache / Code Cache / Service Worker）没有任何
        // 自动回收机制，抖音网页版又是重前端 + SW，会随使用一直长。趁 WebView 还没创建，
        // 超过阈值就整棵扫一次 —— 这个时机删目录最干净，不会碰到正在跑的页面。
        WebViewCache.sweep(this);

        injectedJs = readAsset(SCRIPT_ASSET);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 纵向切成两块：上面 WebView（播放区），下面 Dock（按钮 + 进度条）。
        // Dock 是真实占位，不是浮层 —— 画面到这里就结束，两者不重叠。
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        column.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 冷启动先黑屏：首页必然是「精选」四列网格，等脚本点进单视频流再露出来。
        // 不用 setVisibility(INVISIBLE) 是因为不可见的 WebView 会暂停渲染与 rAF，
        // 页面自己的首屏都渲染不出来，反而进不去。
        cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        root.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 开屏页最后加，保证它压在最上面 —— buildChrome 会把顶栏浮层加进来，
        // 先加开屏页的话，未登录/推荐/完整 那排胶囊会透在开屏页上
        setContentView(root);
        applySystemBarInsets();

        configureWebView();
        buildChrome();
        registerBackCallback();
        buildSplash();
        ensureLogPermission();
        // buildChrome 之前那次 insets 回调里 chrome 还是 null（顶栏还没建），
        // 这里补一次，让顶栏拿到状态栏高度
        root.requestApplyInsets();

        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                revealPage("等待超时");
            }
        }, REVEAL_TIMEOUT_MS);

        ui.postDelayed(splashTimeUpTask, SPLASH_MIN_MS);
        ui.postDelayed(commentStatePoll, 1500);

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            // 进程被回收后恢复历史栈，避免白屏
            ShellLog.i("APP", "已恢复历史栈");
        } else {
            webView.loadUrl(START_URL);
        }
    }

    // ------------------------------------------------------------------ 开屏页

    /**
     * 开屏页：品牌 + 一条极细的加载线。
     *
     * 只用纯原生 View 拼，不引第三方库，也不建单独的 Activity ——
     * 独立 Splash Activity 会在两个 Activity 切换时闪一帧白屏，
     * 共用同一个窗口反而更干净。
     *
     * 显示规则：最少 2 秒；2 秒到了但视频还没起来，就继续盖着（文案改成「正在连接」），
     * 一直到 revealPage 把内容露出来为止 —— 否则中间会插一段纯黑，比多等两秒还难受。
     */
    private void buildSplash() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.BLACK);
        // 吃掉触摸：否则手指会穿过开屏页按到底下的 WebView
        box.setClickable(true);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_splash_logo);
        box.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));

        TextView name = new TextView(this);
        name.setText("抖音 · 网页版");
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(20);
        box.addView(name, nameLp);

        TextView sub = new TextView(this);
        sub.setText("桌面模式 · 沉浸刷视频");
        sub.setTextColor(0x8AFFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setIncludeFontPadding(false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8);
        box.addView(sub, subLp);

        ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        if (bar.getIndeterminateDrawable() != null) {
            bar.getIndeterminateDrawable().setTint(0xFFFE2C55);
        }
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(132), dp(3));
        barLp.topMargin = dp(26);
        box.addView(bar, barLp);

        splashHint = new TextView(this);
        splashHint.setText("正在载入推荐流…");
        splashHint.setTextColor(0x66FFFFFF);
        splashHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        splashHint.setIncludeFontPadding(false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(14);
        box.addView(splashHint, hintLp);

        splash = box;
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private final Runnable splashTimeUpTask = new Runnable() {
        @Override
        public void run() {
            splashTimeUp = true;
            hideSplashIfReady();
            // 2 秒还没起来，说明在等网络 —— 给句话，别让人以为卡死了
            if (!revealed && splashHint != null) splashHint.setText("正在连接抖音…");
        }
    };

    /** 2 秒到了 + 内容也露出来了，才把开屏页淡出。 */
    private void hideSplashIfReady() {
        if (splashGone || !splashTimeUp || !revealed || splash == null) return;
        splashGone = true;
        final View view = splash;
        splash = null;
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(240).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (root != null) root.removeView(view);
            }
        }).start();
        ShellLog.i("APP", "开屏页收起");
    }

    // ---------------------------------------------------------------- 系统栏留白

    /**
     * targetSdk 35 起 Android 强制 edge-to-edge，setDecorFitsSystemWindows 已经是空操作，
     * 页面会直接顶到状态栏和底部手势条底下。
     *
     * 这里只给**底部**留白（Dock 不能被手势条压住）。
     * 顶部不留：视频要顶到状态栏底下才算沉浸，状态栏图标直接压在画面上。
     * 代价是顶栏那排胶囊会和状态栏重叠，所以把 top inset 交给 FeedChrome，
     * 让它自己把 topMargin 加上这个高度。
     */
    private void applySystemBarInsets() {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left = 0, top = 0, right = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                if (chrome != null) chrome.setTopInset(top);
                if (v.getPaddingTop() == 0 && v.getPaddingBottom() == bottom
                        && v.getPaddingLeft() == left && v.getPaddingRight() == right) {
                    return insets;
                }
                v.setPadding(left, 0, right, bottom);
                ShellLog.i("UI", "系统栏：顶部沉浸 top=" + top + "，底部让出手势条 bottom=" + bottom
                        + " left=" + left + " right=" + right);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    /** 撤掉黑幕，露出页面。只生效一次。 */
    private void revealPage(String why) {
        if (revealed) return;
        revealed = true;
        if (cover != null) {
            cover.animate().cancel();
            cover.animate().alpha(0f).setDuration(260).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (cover != null) root.removeView(cover);
                    cover = null;
                }
            }).start();
        }
        ShellLog.i("FEED", "露出页面（" + why + "）");
        // 开屏页靠这个信号收尾：2 秒还没到就继续盖着，到了就淡出
        hideSplashIfReady();
    }

    // -------------------------------------------------------------------- 日志

    private void logDeviceInfo() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }
        ShellLog.section("设备与环境");
        ShellLog.i("APP", "版本 " + version + " / 包名 " + getPackageName());
        ShellLog.i("APP", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") / "
                + Build.MANUFACTURER + " " + Build.MODEL);
        ShellLog.i("APP", "屏幕 " + dm.widthPixels + "x" + dm.heightPixels + " @"
                + dm.densityDpi + "dpi density=" + dm.density);
        ShellLog.i("LOG", "日志文件 " + ShellLog.location()
                + (ShellLog.isFallback() ? "（权限不足，已退到私有目录）" : ""));
    }

    /** Android 8/9 往公共「下载」目录写需要权限；10 及以上走 MediaStore，不需要。 */
    private void ensureLogPermission() {
        if (!ENABLE_FILE_LOG || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            ShellLog.upgradeIfFallback();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_WRITE_STORAGE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            ShellLog.i("LOG", "存储权限 " + (granted ? "已授予" : "被拒绝"));
            if (granted) {
                ShellLog.upgradeIfFallback();
                Toast.makeText(this, "日志: " + ShellLog.location(), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // -------------------------------------------------------------------- 界面

    /**
     * 控制条只做「把意图转成 JS 调用」，所有能力都在页面里：
     * 翻页复用注入层的 wheel 合成，点赞/评论是在页面上点它自己的按钮。
     */
    private void buildChrome() {
        chrome = new FeedChrome(this, this);
        chrome.setImmersive(immersive);
        chrome.setFocus(focusMode);
        chrome.setLoggedIn(loggedIn);
        // Dock 排进纵向布局，紧贴 WebView 下方；顶栏才是浮层
        column.addView(chrome.getDockView());
        root.addView(chrome.getOverlayView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 手指一碰就让控制条浮现。顺带识别「评论开着时右滑收起」——
        // 但这里有一条铁律：**DOWN 必须返回 false**。
        //
        // 评论抽屉就铺在下半屏，那一整块是网页的滚动列表。之前为了拦住侧滑，
        // DOWN 在下半屏直接 return true，等于把整条手势链从 WebView 手里抢走，
        // 表现就是「评论区滑不动、评论点不了赞、输入框点不进去」。
        //
        // 现在改成「先放行、后抢夺」：DOWN 一律交给 WebView，
        // MOVE 里只在**横向位移远大于纵向**时才认定是侧滑。纵向滑动
        // 的 dx 涨不起来，永远不会误判，列表照常滚。
        // 系统边缘手势区（x < 28dp）仍然不碰，那一带直接用返回键/系统手势。
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    if (chrome != null) chrome.peek();
                    swipeTrack = commentOpen
                            && event.getY() > webView.getHeight() * 0.5f
                            && event.getX() > dp(28);   // 避开系统边缘手势区
                    swipeStartX = event.getX();
                    swipeStartY = event.getY();
                    return false;   // 关键：让 WebView 正常接管滚动/点击
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    if (!swipeTrack) return false;
                    float dx = event.getX() - swipeStartX;
                    float dy = event.getY() - swipeStartY;
                    // 右滑 60dp，且横向是纵向的 1.8 倍以上 —— 斜着滚列表不会被误杀
                    if (dx > dp(60) && dx > Math.abs(dy) * 1.8f) {
                        swipeTrack = false;
                        closeCommentSheet("侧滑返回");
                        return true;   // 只吃这一帧，免得列表跟着抖一下
                    }
                    return false;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    swipeTrack = false;
                }
                return false;
            }
        });
    }

    private void showLoginGate() {
        if (loginGate == null) {
            loginGate = new LoginGate(this, this);
            root.addView(loginGate.getRootView(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        loginGate.setVisible(true);
        gateShownOnce = true;
        // 整层让开：登录卡片底下不该有任何控制条/进度条透出来
        if (chrome != null) chrome.setRootVisible(false);
        ShellLog.i("LOGIN", "展示登录引导");
    }

    // ------------------------------------------------------- FeedChrome.Listener

    @Override
    public void onLike() {
        if (!loggedIn) {
            // 未登录时点「赞」网页版只会弹登录框，不如直接把它弹出来
            ShellLog.i("UI", "点按：点赞（未登录，转登录）");
            toast("登录后才能点赞");
            pageLogin();
            return;
        }
        tapElement("like", "点赞");
    }

    @Override
    public void onComment() {
        // 以页面实时状态为准，不用 commentOpen 这个 1 秒轮询来的旧值。
        //
        // 轮询的代价是「按下到判定」最多差 1 秒：刚点开评论、1 秒内再点一次，
        // commentOpen 还是 false，于是又调一次 comments()，而页面那个按钮是
        // toggle —— 结果刚打开的评论区被当场关掉。多这一次往返（约十几毫秒）
        // 换来的是每次按下都做对的事。
        evalJsExpr("window.__DY && __DY.sheetOpen ? __DY.sheetOpen() : false",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if ("true".equals(value)) {
                            closeCommentSheet("Dock 评论按钮");
                            return;
                        }
                        // 评论按钮是普通 React 组件，脚本 click() 就能打开（和点赞那种
                        // 只认真触摸的 xgplayer 控件不一样）。直接走 __DY.comments()，
                        // 比合成触摸稳 —— 合成触摸会被 ghost-rail 拦截和页面动画时序影响。
                        evalJs("window.__DY && __DY.comments && __DY.comments()");
                        commentOpen = true;
                    }
                });
    }

    /**
     * 关掉评论抽屉，并把原生这边的状态一起复位。
     * 页面侧由 __DY.closeComments() 点抽屉的 × 完成，原生只负责同步标记。
     */
    private void closeCommentSheet(String from) {
        ShellLog.i("UI", "关评论区 via " + from);
        evalJs("window.__DY_WANT_SHEET__=false;"
                + "window.__DY && __DY.closeComments && __DY.closeComments()");
        commentOpen = false;
    }

    /**
     * 把页面里评论抽屉的真实开合状态同步回原生。
     * 页面自己因为翻页 / 点 × / 点背景关掉抽屉时，原生这边不会收到通知，
     * 靠这个轮询兜底，避免返回键被一个已经无效的 commentOpen 吃掉。
     */
    private final Runnable commentStatePoll = new Runnable() {
        @Override
        public void run() {
            evalJsExpr("window.__DY && __DY.sheetOpen && __DY.sheetOpen()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            commentOpen = "true".equals(value);
                        }
                    });
            ui.postDelayed(commentStatePoll, 1000);
        }
    };

    /**
     * 收藏 / 转发 / 关注：和点赞走同一条路 —— 页面报坐标、原生合成触摸。
     *
     * 这三个和点赞一样，只认真实输入事件（isTrusted），脚本 click() 无效。
     * 状态回写靠页面：收藏点亮是乐观的（先变黄），关注则等页面把那个「+」撤掉，
     * 下一次 author 回传就会告诉原生「已关注」。
     */
    @Override
    public void onCollect() {
        if (!loggedIn) {
            ShellLog.i("UI", "点按：收藏（未登录，转登录）");
            toast("登录后才能收藏");
            pageLogin();
            return;
        }
        if (chrome != null) {
            chrome.setCollected(true);
            chrome.pulseCollected();
        }
        tapElement("collect", "收藏");
    }

    @Override
    public void onShare() {
        tapElement("share", "转发");
    }

    @Override
    public void onFollow() {
        if (!loggedIn) {
            ShellLog.i("UI", "点按：关注（未登录，转登录）");
            toast("登录后才能关注");
            pageLogin();
            return;
        }
        if (chrome != null) chrome.setFollowing(true);
        tapElement("follow", "关注");
        // 不做二次校准：网页版那颗 + 徽标每个视频上都是「存在但高 0」，
        // 从 DOM 读不出真实关注状态，校准反而会把刚点亮的「已关注」擦掉。
        // 翻到下一个视频时 author 桥会把状态重置成「关注」。
    }

    // ------------------------------------------------- Dock 工具行（播放器设置）
    //
    // 页面底部那排 xgplayer 控件（弹幕 / 倍速 / 清晰度 / 合集）已经被注入层
    // display:none 掉了 —— 它们贴在屏幕最底边，被圆角切、也点不准。
    // 功能不能跟着消失，所以由 Dock 的工具行接管，页面只负责「真正干活」。

    @Override
    public void onDanmuToggle() {
        evalJsExpr("window.__DY && __DY.toggleDanmu()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String r = unquote(value);
                boolean on = "true".equals(r) || "1".equals(r);
                ShellLog.i("UI", "点按：弹幕 -> " + (on ? "开" : "关"));
                if (chrome != null) chrome.setDanmuOn(on);
            }
        });
    }

    @Override
    public void onQualityCycle() {
        evalJsExpr("window.__DY && __DY.cycleQuality()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String label = unquote(value);
                ShellLog.i("UI", "点按：清晰度 -> " + label);
                if (label == null || label.length() == 0 || "false".equals(label)) {
                    toast("这个视频没有可切换的清晰度");
                    return;
                }
                if (chrome != null) chrome.setQuality(label);
            }
        });
    }

    @Override
    public void onRateCycle() {
        evalJsExpr("window.__DY && __DY.cycleRate()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String r = unquote(value);
                ShellLog.i("UI", "点按：倍速 -> " + r);
                try {
                    if (chrome != null) chrome.setRate(Double.parseDouble(r));
                } catch (NumberFormatException e) {
                    if (chrome != null) chrome.setRate(1);
                }
            }
        });
    }

    @Override
    public void onCollection() {
        // 先用页面现算一次，别信 Dock 上那个缓存 —— 打开/关闭选集面板会
        // 让信息区重新渲染，胶囊上的状态可能晚几秒才更新。
        evalJsExpr("window.__DY && __DY.hasCollection()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"true".equals(unquote(value))) {
                    ShellLog.i("UI", "点按：合集 —— 当前视频没有入口");
                    toast("当前视频没有合集");
                    if (chrome != null) chrome.setCollection(false);
                    return;
                }
                // 合集入口和点赞/评论一样只认可信事件，el.click() 点不开，
                // 所以走同一条「页面报坐标 → 原生合成触摸」的路子。
                tapElement("collection", "合集");
            }
        });
    }

    // ----------------------------------------------------- Dock：音量 / 全屏
    //
    // 音量：不动系统媒体音量。AudioManager.setStreamVolume 改的是全局音乐音量，
    // 调一次视频音量连铃声一起变，很难向用户解释；所以直接改 <video>.volume / muted，
    // 只影响这一路播放，而且启动 muted 之后自动播放也更稳。
    //
    // 全屏：Dock 是真实占位，不收起来画面就永远少一截；系统栏也要一起隐掉。

    @Override
    public void onVolume(float level) {
        // 拖动是高频回调（每帧一次），这里不打日志，去重交给页面侧
        evalJs("window.__DY && window.__DY.setVolume("
                + String.format(Locale.ROOT, "%.3f", level) + ")");
        if (chrome != null) chrome.setVolume(level);
    }

    @Override
    public void onVolumeToggle() {
        evalJsExpr("window.__DY && __DY.toggleMute()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                double level = 0;
                try {
                    level = Double.parseDouble(unquote(value));
                } catch (Exception ignored) {
                    // 页面还没注入，按静音处理
                }
                ShellLog.i("UI", "点按：喇叭 -> " + Math.round(level * 100) + "%");
                if (chrome != null) chrome.setVolume((float) level);
            }
        });
    }

    @Override
    public void onFullscreen() {
        fullscreen = !fullscreen;
        if (chrome != null) chrome.setFullscreen(fullscreen);
        if (fullscreen) {
            enterFullscreen();
        } else {
            exitFullscreen();
        }
        // 页面那边也要知道：网页版的全屏只是把系统栏藏掉，播放器盒子还是原来的，
        // 在 20:9 的屏上底下会留一条黑边。真全屏靠注入层的 html.dy-fs 拉 100vh。
        final boolean target = fullscreen;
        evalJsExpr("window.__DY && __DY.setFullscreen && __DY.setFullscreen(" + target + ")", null);
        ShellLog.i("UI", "点按：全屏 -> " + (fullscreen ? "开（Dock 收起）" : "关"));
        // 视口高度变了：让页面重新算 viewport 和播放器铺满
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                evalJsExpr("window.__DY && __DY.setFullscreen && __DY.setFullscreen(" + target + ")", null);
                evalJs("window.__DY && window.__DY.refreshLayout()");
            }
        }, 260);
    }

    /**
     * 点赞 / 评论都靠原生在页面上「真点一下」它的按钮。
     *
     * 为什么不能直接 element.click()：这两个按钮挂在 xgplayer 上，只认真实输入事件，
     * 脚本里 dispatch 出来的 click 是 isTrusted=false，点了毫无反应（翻页箭头则相反，
     * 那个是纯 onClick，click() 有效）。所以这里让页面先报一个坐标，
     * 再由 native 合成一次触摸——和手指按下去走同一条链路。
     */
    private void tapElement(final String kind, final String label) {
        evalJsExpr("window.__DY && __DY.point('" + kind + "')", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String point = unquote(value);
                if (point == null || point.indexOf(',') <= 0) {
                    ShellLog.i("UI", "点按：" + label + " 失败 —— 页面上没找到按钮");
                    toast("没找到" + label + "按钮，可能页面结构变了");
                    return;
                }
                ShellLog.i("UI", "点按：" + label + " @" + point);
                // 原生 Dock 也是往 WebView 里合成 MotionEvent，对页面来说和真手指
                // 一样是 isTrusted=true。先开一个很短的放行窗口，否则会被
                // blockGhostRail 当成「点在旧按钮上」掐掉。
                evalJsExpr("window.__DY_NATIVE_TAP__=Date.now()+500",
                        new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String ignored) {
                                performTap(point);
                            }
                        });
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        evalJs("window.__DY_NATIVE_TAP__=0");
                    }
                }, 600);
            }
        });
    }

    /**
     * 在页面坐标处合成一次触摸。
     *
     * cssX / cssY 是页面的 CSS 像素（getBoundingClientRect 那一套），
     * 需要按「WebView 实际宽度 / window.innerWidth」换算到 View 坐标，
     * 否则沉浸模式（900 设计宽）下点歪 1.33 倍。
     */
    private void performTap(String csv) {
        if (webView == null) return;
        String[] parts = csv.split(",");
        if (parts.length < 3) return;
        final float cssX, cssY, cssW;
        try {
            cssX = Float.parseFloat(parts[0].trim());
            cssY = Float.parseFloat(parts[1].trim());
            cssW = Float.parseFloat(parts[2].trim());
        } catch (NumberFormatException e) {
            ShellLog.e("UI", "坐标解析失败 " + csv);
            return;
        }
        int viewW = webView.getWidth();
        int viewH = webView.getHeight();
        if (viewW <= 0 || viewH <= 0 || cssW <= 0) return;
        final float scale = viewW / cssW;
        final float x = Math.min(Math.max(cssX * scale, 1f), viewW - 1f);
        final float y = Math.min(Math.max(cssY * scale, 1f), viewH - 1f);

        final long downAt = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean handled;
        try {
            handled = webView.dispatchTouchEvent(down);
        } catch (Throwable t) {
            handled = false;
            ShellLog.e("UI", "合成触摸失败: " + t);
        } finally {
            down.recycle();
        }
        ShellLog.i("UI", "合成触摸 " + Math.round(x) + "," + Math.round(y)
                + " scale=" + String.format(Locale.ROOT, "%.3f", scale)
                + " 分发" + (handled ? "成功" : "未处理"));

        // 抬起来要晚一点：立刻 UP 有概率被当成「没有移动的取消」
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                long upAt = SystemClock.uptimeMillis();
                MotionEvent up = MotionEvent.obtain(upAt, upAt, MotionEvent.ACTION_UP, x, y, 0);
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    webView.dispatchTouchEvent(up);
                } catch (Throwable t) {
                    ShellLog.e("UI", "合成抬手失败: " + t);
                } finally {
                    up.recycle();
                }
            }
        }, 70);
    }

    @Override
    public void onSeek(final float fraction) {
        ShellLog.i("UI", "拖动进度条 -> " + String.format(Locale.ROOT, "%.3f", fraction));
        evalJsExpr("window.__DY && __DY.seekToFraction(" + fraction + ")", null);
    }

    @Override
    public void onStatusClick() {
        if (loggedIn) {
            toast("已登录");
            ShellLog.i("LOGIN", "状态条点击，当前已登录");
        } else {
            showLoginGate();
        }
    }

    @Override
    public void onModeToggle() {
        immersive = !immersive;
        chrome.setImmersive(immersive);
        final boolean target = immersive;
        ShellLog.i("MODE", "切换 -> " + (target ? "沉浸" : "网页"));
        evalJsExpr("window.__DY && __DY.setMode('" + (target ? "immersive" : "web") + "')",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String result = unquote(value);
                        ShellLog.i("MODE", "结果 " + result);
                        // 找不到播放器就别硬撑，老实退回网页模式
                        if (target && !"immersive".equals(result) && !"pending".equals(result)) {
                            toast("没找到播放器，先留在网页模式");
                            immersive = false;
                            chrome.setImmersive(false);
                            evalJsExpr("window.__DY && __DY.setMode('web')", null);
                        }
                    }
                });
    }

    @Override
    public void onFeed() {
        ShellLog.i("UI", "点按：回到推荐流");
        evalJsExpr("window.__DY && __DY.enterFeed()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String result = unquote(value);
                ShellLog.i("FEED", "结果 " + result);
                if ("already".equals(result)) toast("已经在推荐流");
                else if ("waiting".equals(result)) toast("没找到视频卡片，先手动点一个");
            }
        });
    }

    @Override
    public void onFocusToggle() {
        focusMode = !focusMode;
        if (chrome != null) chrome.setFocus(focusMode);
        ShellLog.i("MODE", "精简模式 -> " + (focusMode ? "开" : "关"));
        evalJsExpr("window.__DY && __DY.setFocus(" + focusMode + ")", null);
    }

    // -------------------------------------------------------- LoginGate.Listener

    @Override
    public void onLogin() {
        if (loginGate != null) loginGate.setVisible(false);
        if (chrome != null) chrome.setRootVisible(true);
        pageLogin();
    }

    /**
     * 在抖音自己的页面里把登录框弹出来。
     *
     * 沉浸层把页头的「登录」按钮整个盖住了，唯一稳定入口是播放器右侧的点赞：
     * 未登录时点它必弹「登录后即可点赞」。这里先暂停自动清理遮挡层，
     * 否则弹窗刚出来就被 sweepOverlays 关掉，用户看到的就是「点了没反应」。
     */
    private void pageLogin() {
        awaitingLogin = true;
        loginPolls = 0;
        ShellLog.i("LOGIN", "尝试打开页面登录框");
        evalJs("window.__DY && __DY.refit && __DY.refit()");
        evalJs("window.__DY && __DY.pauseOverlaySweep && __DY.pauseOverlaySweep(240000)");
        tapElement("like", "登录");
        startLoginPoll();
    }

    @Override
    public void onSkip() {
        if (loginGate != null) loginGate.setVisible(false);
        if (chrome != null) chrome.setRootVisible(true);
        ShellLog.i("LOGIN", "用户选择先不登录");
    }

    // ------------------------------------------------------------------ 登录态

    /** 只读 Cookie 判断，不发请求、不碰登录接口。 */
    private boolean cookieSaysLoggedIn() {
        try {
            String cookie = CookieManager.getInstance().getCookie("https://www.douyin.com");
            if (cookie == null || cookie.isEmpty()) return false;
            String lower = cookie.toLowerCase(Locale.ROOT);
            // 只认真正的会话 cookie。
            // uid_tt / uid_tt_ss 不能用：未登录访问一次首页抖音就会种上这两个，
            // 之前拿它当判据，结果「打开登录框又关掉」之后 App 就以为已经登录了，
            // 点赞、收藏全都不再提示登录，点了也没反应。
            return lower.contains("sessionid=") || lower.contains("sessionid_ss=")
                    || lower.contains("sid_guard=") || lower.contains("sid_tt=");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 登录态以**页面**为准，cookie 只当兜底。
     *
     * 设备上很容易留着一个过期的 sessionid（上次登录、或者退出登录时没清干净），
     * 只看 cookie 会把「早就掉线」判成已登录：界面显示已登录，点赞却什么都不发生。
     * 页面的 loginState() 看的是顶栏有没有「登录」按钮 / 有没有用户头像，掉线必然对得上。
     */
    private void refreshLoginState() {
        evalJsExpr("window.__DY && __DY.loginState && __DY.loginState()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String s = unquote(value);
                        if ("in".equals(s)) {
                            applyLoginState(true, "页面");
                        } else if ("out".equals(s)) {
                            applyLoginState(false, "页面");
                        } else {
                            // 页面还没注入（冷启动那一两秒）才退回 cookie
                            applyLoginState(cookieSaysLoggedIn(), "cookie");
                        }
                    }
                });
        // 刻意不在冷启动时弹全屏登录引导：那样一打开先看到一张卡片，看不到视频。
        // 登录入口保留在左上角的「未登录」状态条上，点一下就是同一个引导页。
    }

    private void applyLoginState(boolean in, String source) {
        if (in != loggedIn) {
            ShellLog.i("LOGIN", (in ? "检测到登录态" : "未检测到登录态") + "（来源：" + source + "）");
        }
        loggedIn = in;
        if (chrome != null) chrome.setLoggedIn(in);
    }

    private void startLoginPoll() {
        ui.removeCallbacks(loginPoll);
        ui.postDelayed(loginPoll, LOGIN_POLL_MS);
    }

    /** 登录完成后页面会渲染出用户头像，轮询到就直接收工，不需要用户点什么。 */
    private final Runnable loginPoll = new Runnable() {
        @Override
        public void run() {
            if (!awaitingLogin) return;
            if (++loginPolls > LOGIN_POLL_MAX) {
                awaitingLogin = false;
                ShellLog.i("LOGIN", "轮询超时，放弃等待");
                return;
            }
            // 不再看 cookie：设备上留着过期的 sessionid，一看就以为登录成功了
            if (loginPolls == 5) {
                // 第 5 次（约 10 秒）还没动静，给个提示——别让用户对着空白页面等
                toast("没看到登录浮层的话，点页面右上角的「登录」");
            }
            evalJsExpr("window.__DY && __DY.loginState && __DY.loginState()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (!awaitingLogin) return;
                            if ("in".equals(unquote(value))) {
                                onLoginSuccess("页面状态");
                            } else {
                                ui.postDelayed(loginPoll, LOGIN_POLL_MS);
                            }
                        }
                    });
        }
    };

    private void onLoginSuccess(String source) {
        awaitingLogin = false;
        loggedIn = true;
        if (chrome != null) {
            chrome.setLoggedIn(true);
            chrome.setChromeVisible(true);
        }
        ShellLog.i("LOGIN", "登录成功（来源：" + source + "）");
        toast("登录成功");
        // 登录后页面会重排，重新套一次模式
        evalJsExpr("window.__DY && __DY.setMode('" + (immersive ? "immersive" : "web") + "')", null);
    }

    // ------------------------------------------------------------------ JS 调用

    private void evalJs(String code) {
        if (webView == null) return;
        try {
            webView.evaluateJavascript(code, null);
        } catch (Exception e) {
            ShellLog.e("JS", "执行失败: " + e);
        }
    }

    /** 执行一个表达式并取回结果。结果按 JSON 传回，字符串会带引号。 */
    private void evalJsExpr(String expr, ValueCallback<String> callback) {
        if (webView == null) {
            if (callback != null) callback.onReceiveValue(null);
            return;
        }
        String code = "(function(){try{return (" + expr
                + ")}catch(e){return 'ERR:'+(e&&e.message||e)}})()";
        try {
            webView.evaluateJavascript(code, callback);
        } catch (Exception e) {
            ShellLog.e("JS", "执行失败 " + expr + " :: " + e);
            if (callback != null) callback.onReceiveValue(null);
        }
    }

    private static String unquote(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ WebView

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        WebView.setWebContentsDebuggingEnabled(ENABLE_WEBVIEW_DEBUG);
        mediaCache = new MediaCache(getApplicationContext());

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        // 关掉手势限制，页面才能自动播放
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(desktopUserAgent(s.getUserAgentString()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.setAlgorithmicDarkeningAllowed(false);
        }
        ShellLog.i("APP", "WebView 内核 " + webViewPackage());
        ShellLog.i("UA", "内核原始 UA: " + safeUserAgent());
        ShellLog.i("UA", "改写后 UA: " + s.getUserAgentString());

        // 页面里的日志走这个桥回传；只暴露一个 log，没有反射入口
        webView.addJavascriptInterface(new JsBridge(), "__DY_NATIVE__");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        // 网页版的「保存视频」多半走 blob:，WebView 拿不到；真 http(s) 链接交给系统
        webView.setDownloadListener((url, ua, disposition, mime, length) -> {
            ShellLog.i("DOWNLOAD", "mime=" + mime + " len=" + length + " url=" + url);
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return;
                } catch (Exception ignored) {
                    // 没有可用浏览器时继续往下提示
                }
            }
            Toast.makeText(this, "该下载需要 App 内的下载器，已忽略", Toast.LENGTH_SHORT).show();
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                consoleLines = 0;
                headerSamples = 0;
                droppedConsole = 0;
                pageStartedAt = System.currentTimeMillis();
                ShellLog.i("PAGE", "开始加载 " + url);
                view.evaluateJavascript(injectedJs, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                long cost = pageStartedAt > 0 ? System.currentTimeMillis() - pageStartedAt : -1;
                ShellLog.i("PAGE", "加载完成 " + url + " 用时 " + cost + "ms");
                if (droppedConsole > 0) {
                    ShellLog.i("CONSOLE", "另有 " + droppedConsole + " 条被丢弃（超过 " + MAX_CONSOLE_LINES + " 上限）");
                }
                // 页面重写 viewport 或延迟挂载时再补一次（脚本内部幂等）
                view.evaluateJavascript(injectedJs, null);
                // 首屏加载完后还没进到推荐流（首次安装、缓存为空时可能十几秒），
                // 8 秒后直接把页面露出来——宁可看到一眼「精选」网格，也不要一直黑着。
                if (!revealed) {
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            revealPage("页面已加载，进入推荐流超时");
                        }
                    }, 8000);
                }
                // 注入之后再谈模式和登录态：__DY 要有，页面也才渲染出可视区域
                if (immersive) evalJsExpr("window.__DY && __DY.setMode('immersive')", null);
                // 脚本每次重新注入都默认开精简，只有用户关过才需要覆盖回来
                if (!focusMode) evalJsExpr("window.__DY && __DY.setFocus(false)", null);
                refreshLoginState();
                // onPageFinished 时 __DY 往往还没跑起来（注入脚本是后挂的），
                // 那时候 loginState() 只能回 unknown、退回 cookie。等页面稳定再问一次，
                // 这两秒正好也是推荐流渲染的时间。
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshLoginState();
                    }
                }, 2500);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            /**
             * 视频走磁盘缓存，其余原样放行。
             *
             * MediaCache 只认抖音的视频本体（mp4 / /video/tos/），命中就直接读盘，
             * 省掉一次整段下载；没命中也是边下边写，下次同一条视频就免费了。
             * 任何异常它都自己吞掉返回 null，不会把播放搞挂。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (ENABLE_FILE_LOG && headerSamples < MAX_HEADER_SAMPLES) {
                    try {
                        if (request.isForMainFrame()) logRequestHeaders(request);
                    } catch (Exception e) {
                        ShellLog.e("REQ", "读取请求头失败: " + e);
                    }
                }
                if (mediaCache != null) {
                    WebResourceResponse cached = mediaCache.intercept(request);
                    if (cached != null) return cached;
                }
                return null;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    ShellLog.e("PAGE", "主文档失败 " + error.getErrorCode() + " "
                            + error.getDescription() + " " + request.getUrl());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    ShellLog.e("PAGE", "主文档 HTTP " + response.getStatusCode()
                            + " " + request.getUrl());
                }
            }

            /** 渲染进程被系统杀掉（抖音视频解码吃内存时偶发），必须自己收尾，否则整个 App 陪葬。 */
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                ShellLog.e("WEBVIEW", "渲染进程终止 didCrash=" + detail.didCrash());
                ShellLog.flushNow();
                if (view != webView) return true;
                root.removeView(view);
                view.destroy();
                webView = null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        recreate();
                    }
                });
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                ShellLog.i("VIDEO", "进入全屏 (customView)");
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                webView.setVisibility(View.GONE);
                enterFullscreen();
            }

            @Override
            public void onHideCustomView() {
                ShellLog.i("VIDEO", "退出全屏");
                if (customView != null) {
                    root.removeView(customView);
                    customView = null;
                }
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                webView.setVisibility(View.VISIBLE);
                exitFullscreen();
            }

            /**
             * 没有这个回调，网页版里的「上传视频 / 换头像 / 选文件」点下去毫无反应——
             * WebChromeClient 默认返回 false，等于静默丢弃。
             */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                ShellLog.i("FILE", "打开文件选择器 accept=" + joinArray(params.getAcceptTypes())
                        + " mode=" + params.getMode());
                try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    ShellLog.e("FILE", "没有可用的文件选择器: " + e);
                    Toast.makeText(MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // 壳不申请设备能力，避免无谓的授权弹窗
                ShellLog.i("PERM", "拒绝网页权限请求 " + joinArray(request.getResources()));
                request.deny();
            }

            /**
             * 页面的 console 只留 error/warn，并且每页封顶。
             * 抖音正常播放时会刷大量 debug 日志，全收会把关键信息挤掉。
             */
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (!ENABLE_FILE_LOG || cm == null) return false;
                ConsoleMessage.MessageLevel level = cm.messageLevel();
                boolean important = level == ConsoleMessage.MessageLevel.ERROR
                        || level == ConsoleMessage.MessageLevel.WARNING;
                if (!important) return false;

                if (consoleLines >= MAX_CONSOLE_LINES) {
                    droppedConsole++;
                    return false;
                }
                consoleLines++;
                String where = cm.sourceId() == null ? "" : " @" + tail(cm.sourceId(), 48);
                ShellLog.i("CONSOLE/" + level.name().charAt(0),
                        cm.message() + where + ":" + cm.lineNumber());
                return false; // 仍交给系统打 logcat
            }
        });
    }

    /** 只放行 http(s)；snssdk1128:// aweme:// intent:// bytedance:// 等一律拦掉。 */
    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme)) return false;

        ShellLog.i("SCHEME", "拦截 " + scheme + ":// " + tail(uri.toString(), 120));
        if ("intent".equals(scheme)) {
            Toast.makeText(this, "已拦截 intent 跳转", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /** 沿用当前内核真实的 Chrome 版本，避免版本号虚高触发站点下发不支持的特性。 */
    private String desktopUserAgent(String defaultUa) {
        String version = "131";
        if (defaultUa != null) {
            int i = defaultUa.indexOf("Chrome/");
            if (i >= 0) {
                int j = i + 7;
                StringBuilder sb = new StringBuilder();
                while (j < defaultUa.length() && Character.isDigit(defaultUa.charAt(j))) {
                    sb.append(defaultUa.charAt(j));
                    j++;
                }
                if (sb.length() > 0) version = sb.toString();
            }
        }
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + version + ".0.0.0 Safari/537.36";
    }

    // ------------------------------------------------------------- 日志采集

    /**
     * 页面里的 console / 关键事件通过这个桥回传。
     * 只暴露一个 log(String)，没有 getClass / 反射入口，远程页面即使发现了它也只能往日志里写东西。
     */
    /**
     * 发一次「可信」的滚轮事件。
     *
     * 为什么非要用原生：抖音的上下切视频组件会校验 event.isTrusted，
     * JS 里 new WheelEvent() 派发出去直接被丢弃；真实手指拖动也无效
     * （桌面版只监听 wheel）。实测只有输入子系统产生的滚轮事件才能翻页，
     * 所以这里用 ACTION_SCROLL + SOURCE_MOUSE 自己合成一个。
     *
     * dir > 0 = 下一个视频（对应 AXIS_VSCROLL 正值）。
     */
    private void injectScroll(float dir) {
        if (webView == null) return;
        int w = webView.getWidth();
        int h = webView.getHeight();
        if (w <= 0 || h <= 0) return;

        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = w / 2f;
        coords.y = h / 2f;
        coords.pressure = 1f;
        coords.size = 1f;
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, dir);

        MotionEvent event = null;
        try {
            event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                    new MotionEvent.PointerProperties[]{props},
                    new MotionEvent.PointerCoords[]{coords},
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
            boolean handled = webView.dispatchGenericMotionEvent(event);
            ShellLog.i("SCROLL", "原生滚轮 dir=" + dir + " 分发"
                    + (handled ? "成功" : "未处理"));
        } catch (Throwable t) {
            ShellLog.i("SCROLL", "原生滚轮失败: " + t);
        } finally {
            if (event != null) event.recycle();
        }
    }

    private final class JsBridge {
        @JavascriptInterface
        public void log(String payload) {
            if (!ENABLE_FILE_LOG || payload == null) return;
            // 脚本会把多条攒成一批，用换行拼接
            String[] parts = payload.split("\n");
            int limit = Math.min(parts.length, 120);
            for (int i = 0; i < limit; i++) {
                String line = parts[i].trim();
                if (!line.isEmpty()) ShellLog.i("JS", line);
            }
        }

        /** 脚本检测到上/下滑之后，请原生发一个真正的滚轮事件。 */
        @JavascriptInterface
        public void scroll(final int dir) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    injectScroll(dir >= 0 ? 1f : -1f);
                }
            });
        }

        /** 已经进到单视频流，可以把黑幕撤了。 */
        @JavascriptInterface
        public void feedReady() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    revealPage("脚本回报已进入推荐流");
                }
            });
        }

        /**
         * 双击画面点赞。
         *
         * 页面自己不做这件事：桌面版的「双击点赞」是给鼠标 dblclick 的，
         * 手指双击只会被当成两次单击（暂停 → 播放），合成 dblclick 它也不认。
         * 所以判定放在注入层（两次抬手间隔 < 300ms 且位置相近），执行放这里 ——
         * 走和 Dock 上那颗心完全一样的路径：合成真实触摸去点右侧的点赞按钮。
         *
         * 未登录时先给提示再拉起登录，否则用户只会看到一颗没反应的心。
         */
        @JavascriptInterface
        public void doubleTapLike() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (chrome != null) chrome.pulseLike();
                    if (!loggedIn) {
                        ShellLog.i("UI", "双击点赞：未登录，提示并拉起登录");
                        toast("登录后才能点赞");
                        pageLogin();
                        return;
                    }
                    tapElement("like", "双击点赞");
                }
            });
        }

        /**
         * 播放进度 + 工具行状态。页面每 500ms 推一次，只在值变了才推。
         * 底部进度条和「弹幕 / 清晰度 / 倍速」胶囊都是原生的，
         * 页面那排 xgplayer 控件已经被注入层藏掉了。
         *
         * 注意：JS 那边固定按 7 个参数调用，方法签名不能再收窄 ——
         * 参数个数对不上时 Android 的 JS 桥会直接抛错，被页面里的 try/catch 吞掉，
         * 表现就是「进度条彻底不动」，很难查。
         */
        @JavascriptInterface
        public void progress(final double current, final double duration, final int playing,
                             final String quality, final double rate, final int danmu,
                             final int hasCollection) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (chrome == null) return;
                    chrome.setProgress(current, duration, playing > 0, rate);
                    chrome.setTools(quality, rate, danmu);
                    chrome.setCollection(hasCollection > 0);
                }
            });
        }

        /**
         * 作者信息 + 点赞/收藏的真实状态。
         *
         * liked / collected 来自页面按钮上的 data-e2e-state，不是我们记的账 ——
         * 取消点赞、收藏被作者删掉、翻页，状态都会跟着回来。
         * 只有「关注」没有权威状态（那颗 + 徽标读不出已关注），仍然靠翻页重置。
         */
        @JavascriptInterface
        public void author(final String avatar, final String nick,
                           final int liked, final int collected) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (chrome == null) return;
                    String name = nick == null ? "" : nick;
                    if (name.length() > 0 && !name.equals(lastAuthor)) {
                        if (lastAuthor != null) chrome.setFollowing(false);
                        lastAuthor = name;
                        ShellLog.i("UI", "作者 -> " + name);
                    }
                    chrome.setAuthor(avatar, name);
                    chrome.setLiked(liked > 0);
                    chrome.setCollected(collected > 0);
                }
            });
        }
    }

    /** 采样主文档请求头，用来确认 setUserAgentString 有没有把 Client Hints 一起换掉。 */
    private void logRequestHeaders(WebResourceRequest request) {
        headerSamples++;
        Map<String, String> h = request.getRequestHeaders();
        StringBuilder sb = new StringBuilder();
        sb.append(request.getMethod()).append(' ').append(tail(request.getUrl().toString(), 90));
        appendHeader(sb, h, "User-Agent");
        appendHeader(sb, h, "Sec-CH-UA-Mobile");
        appendHeader(sb, h, "Sec-CH-UA-Platform");
        appendHeader(sb, h, "Sec-CH-UA");
        appendHeader(sb, h, "Accept-Language");
        String cookie = h.get("Cookie");
        sb.append(" | Cookie=").append(cookie == null ? "无" : cookie.length() + "字节");
        ShellLog.i("REQ", sb.toString());
    }

    private static void appendHeader(StringBuilder sb, Map<String, String> headers, String name) {
        String value = headers.get(name);
        if (value == null) {
            // 头名大小写不敏感，内核不一定按 Chrome 的写法发
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        sb.append(" | ").append(name).append('=').append(value == null ? "(缺失)" : value);
    }

    private static String joinArray(String[] values) {
        if (values == null || values.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    /** 截断长字符串，日志里只留尾部（域名、路径比协议头有用）。 */
    private static String tail(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : "…" + value.substring(value.length() - max);
    }

    /** WebView 内核版本，排查「是不是内核太旧导致某特性不生效」。 */
    private String webViewPackage() {
        try {
            android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
            return info == null ? "?" : info.packageName + " " + info.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private String safeUserAgent() {
        try {
            String ua = WebSettings.getDefaultUserAgent(this);
            return ua == null ? "?" : ua;
        } catch (Exception e) {
            return "?";
        }
    }

    // ----------------------------------------------------------------- 全屏

    @SuppressWarnings("deprecation")
    private void enterFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @SuppressWarnings("deprecation")
    private void exitFullscreen() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * Android 13+ 的预测性返回。targetSdk 36 上系统手势不再走 onBackPressed，
     * 必须注册这个回调，否则「侧滑返回」会绕过我们直接 finish Activity。
     */
    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        backCallback = new OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
                handleBack();
            }
        };
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        ShellLog.i("APP", "已接管系统返回手势（moveTaskToBack 保活）");
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    /**
     * 所有「返回」意图的唯一出口。
     *
     * 顺序：退全屏 → 关登录引导 → 收评论 → 退网页历史 → 回桌面。
     * 最后一档刻意 **不 finish**：用 moveTaskToBack(true) 把整个 Task 挪到后台，
     * WebView、登录 Cookie、内存缓存、当前视频都还在，重新点图标是原样恢复；
     * 以前 super.onBackPressed() 会 onDestroy → webView.destroy()，等于每次侧滑都冷启动。
     */
    private void handleBack() {
        // 全屏状态下返回键先退全屏，别一下子退回网格页
        if (fullscreen) {
            onFullscreen();
            return;
        }
        if (loginGate != null && loginGate.isVisible()) {
            loginGate.setVisible(false);
            if (chrome != null) chrome.setChromeVisible(true);
            return;
        }
        // 评论开着时返回键先收起抽屉，而不是退回上一页/退出
        if (commentOpen) {
            closeCommentSheet("返回键");
            return;
        }
        if (webView != null && customView != null) {
            WebChromeClient client = webView.getWebChromeClient();
            if (client != null) client.onHideCustomView();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        ShellLog.i("APP", "返回桌面（保留后台，不 finish）");
        moveTaskToBack(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        // 退到后台就落盘，别等下一次定时刷新——进程随时可能被回收
        ShellLog.flushNow();
        // 顺手报一次账：缓存占多少、上限是多少。出问题时看这一行就知道有没有收敛。
        final Context app = getApplicationContext();
        new Thread(() -> {
            if (mediaCache != null) ShellLog.i("CACHE", mediaCache.footprint());
            ShellLog.i("CACHE", "WebView 缓存 " + WebViewCache.stats(app));
        }, "cache-report").start();
    }

    /**
     * 系统报内存/存储压力时让缓存收敛。
     *
     * 我们自己的视频缓存以前只在「写入之后顺手 trim」这一条路上管，进程一直开着
     * 就不再收敛 —— 用户看到的「放兜里一下午，回来又涨了一截」就是这么来的。
     * 切后台/低内存这两个时机必须补上。
     *
     * 注意这里**不动 WebView 的缓存目录**：WebView 正在跑，删它的 Cache_Data
     * 会踩到 Chromium 自己的索引和文件句柄。那块交给启动时（WebView 还没创建）处理。
     *
     * 删文件是阻塞 IO，放后台线程，主线程一点都不碰。
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        boolean tight;
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                tight = true;
                break;
            default:
                tight = false;
        }
        if (!tight || mediaCache == null) return;
        mediaCache.trimNow();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        ShellLog.i("APP", "Activity 销毁");
        ShellLog.flushNow();
        ui.removeCallbacksAndMessages(null);
        if (chrome != null) {
            chrome.destroy();
            chrome = null;
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------- 工具

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }
}
