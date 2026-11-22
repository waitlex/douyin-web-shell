package com.example.douyinweb;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 原生控制层。分成两半，职责完全不同：
 *
 * 1) overlay —— 顶栏，浮在页面之上（登录态、推荐、完整/精简、网页/沉浸）
 * 2) dock    —— 底部 Dock，**真实占位**，由外层 LinearLayout 排在 WebView 下面，
 *               不叠在画面上，也不遮住页面的作者信息和右侧按钮列。
 *
 * 布局（2026-11 四次改版：整列互动按钮搬进来，进度条压到交界处）：
 *
 *   ┌──────────────────────────────┐
 *   │ ●未登录        推荐 完整 网页 │  ← overlay，浮层，4s 淡出（全屏时多一个「退出全屏」）
 *   │                              │
 *   │        WebView（播放区域）     │  ← 高度 = 屏幕 - Dock
 *   │                              │
 *   ├──────────────────────────────┤  ← 这条就是进度条本身（不再单独画分隔线）
 *   │ (头) 关注      ♥ 💬 ★ ↗   ⛶  │  ← 互动行：作者 / 关注 / 赞 / 评论 / 收藏 / 转发 / 全屏
 *   │ 🔊 ────────  弹幕 720P 1x 合集 │  ← 音量 + 工具行
 *   └──────────────────────────────┘
 *
 * 几个刻意的选择：
 *
 * - **进度条不占一行**。改版前它是 Dock 最下面单独一行，线和画面之间隔着两条按钮，
 *   看片时视线要在画面底边和那条线之间来回跳。现在 SeekStrip 直接是 Dock 的第一个
 *   View，线画在它的顶边 —— 看上去就是「视频底边那条进度」，和画面融为一体。
 * - **不再画 1px 分隔线**。进度条的轨道本身就是最自然的分界线。
 * - **整列互动按钮都搬进来了**。页面右侧那一竖列（头像 / 关注 / 赞 / 评论 / 收藏 /
 *   转发）在注入层里是 opacity:0，但位置还在，所以点击仍然走「页面报坐标 →
 *   原生合成触摸」那条路（见 MainActivity.tapElement），坐标不会因为换版式而歪。
 * - **时间只在拖的时候出现**。常驻一行 "01:24 / 04:03" 要占掉 30dp 高，
 *   为了紧凑把它画成拖动时的浮字，松手就消失。
 */
public final class FeedChrome {

    public interface Listener {
        void onLike();

        void onComment();

        void onStatusClick();

        void onModeToggle();

        /** 回到「推荐」单视频流（网格页里点开第一条）。 */
        void onFeed();

        /** 精简 / 完整切换：隐藏或恢复左侧导航、搜索框。 */
        void onFocusToggle();

        /** 用户拖 / 点了进度条，fraction ∈ [0,1]。 */
        void onSeek(float fraction);

        /** 弹幕开关（页面那排控件里原本有一个，现在归 Dock）。 */
        void onDanmuToggle();

        /** 清晰度切换：点一下换下一档。 */
        void onQualityCycle();

        /** 倍速切换：0.5 → 0.75 → 1 → 1.25 → 1.5 → 2 → 回到 0.5。 */
        void onRateCycle();

        /** 打开合集 / 选集（当前视频没有合集就提示）。 */
        void onCollection();

        /** 音量条拖动，level ∈ [0,1]。 */
        void onVolume(float level);

        /** 喇叭图标：有声则静音，静音则恢复上次音量。 */
        void onVolumeToggle();

        /** 全屏：收起 Dock + 隐藏系统栏，再点一次退出。 */
        void onFullscreen();

        /** 收藏（页面右侧那颗星）。 */
        void onCollect();

        /** 转发（页面右侧那个箭头）。 */
        void onShare();

        /** 关注作者（头像底下那颗 +）。 */
        void onFollow();
    }

    private static final int ACCENT = 0xFFFE2C55;
    private static final int STAR = 0xFFF3C24B;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0x99FFFFFF;
    private static final int PILL_BG = 0xBF15151A;
    private static final int PILL_STROKE = 0x1FFFFFFF;

    /** Dock 底色：比纯黑亮一点，视频（纯黑边）和 Dock 才不会糊成一片。 */
    private static final int DOCK_BG = 0xFF0B0B0F;

    /** 工具行胶囊：常态半透明，激活（弹幕开 / 非 1 倍速）时染成强调色。 */
    private static final int TOOL_BG = 0x14FFFFFF;
    private static final int TOOL_BG_ON = 0x33FE2C55;

    private static final int DOT_ONLINE = 0xFF2BD97C;
    private static final int DOT_OFFLINE = 0xFF8A8A8E;

    private static final long AUTO_HIDE_MS = 4000;

    // ---------------------------------------------------------------- Dock 几何
    //
    // 这三块高度是「视觉重量」的直接来源，改之前先看这段：
    //
    //   SEEK_H   —— 进度条那一块。线只占顶边 2dp，剩下的高度全是给手指抓的。
    //               原来 26dp，那 24dp 的空白正好夹在进度线和图标之间，
    //               截图里看就是一条明显的「黑带」。压到 16dp 既够按，也不显空。
    //   ACTION_H —— 互动行。图标从 42dp 放大到 56dp 的触摸区、27dp 的图形，
    //               高度 42 → 54 才装得下，同一屏里也显得更「敢做」。
    //   TOOL_H   —— 工具行（音量 + 四颗胶囊）。
    private static final int SEEK_H = 16;
    private static final int ACTION_H = 54;
    private static final int TOOL_H = 38;

    private final Activity host;
    private final Listener listener;

    /** 浮层：只有顶栏，整层不可点击，触摸全部透给 WebView。 */
    private final FrameLayout overlay;
    /** 底部 Dock：真实占位，加进外层的纵向 LinearLayout 里。 */
    private final LinearLayout dock;

    private final LinearLayout topBar;
    private final LinearLayout actionRow;
    private final LinearLayout toolRow;
    private final SeekStrip seekStrip;
    private final AvatarView avatarView;
    private final TextView followChip;

    private final TextView danmuChip;
    private final TextView qualityChip;
    private final TextView rateChip;
    private final TextView collectionChip;

    private final TextView statusText;
    private final View statusDot;
    private final TextView feedButton;
    private final TextView focusButton;
    private final TextView modeButton;
    private final TextView exitFullButton;
    private final ImageView likeIcon;
    private final ImageView collectIcon;
    private final ImageView volumeIcon;
    private final VolumeStrip volumeStrip;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable hideTask = new Runnable() {
        @Override
        public void run() {
            setChromeVisible(false);
        }
    };

    private boolean chromeVisible = true;
    private boolean immersive = true;
    private boolean collectionAvailable = false;
    private boolean fullscreen = false;
    private boolean following = false;
    private String authorName = "";

    public FeedChrome(Activity host, Listener listener) {
        this.host = host;
        this.listener = listener;

        // ---------------------------------------------------------- 浮层（顶栏）
        overlay = new FrameLayout(host);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 容器不可点击，否则会挡住整个 WebView
        overlay.setClickable(false);
        overlay.setFocusable(false);

        topBar = new LinearLayout(host);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), 0, dp(14), 0);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        topLp.gravity = Gravity.TOP;
        // 顶部沉浸之后页面会顶到状态栏底下，这排胶囊要自己让开状态栏
        topLp.topMargin = dp(8);
        topBar.setLayoutParams(topLp);

        LinearLayout statusChip = new LinearLayout(host);
        statusChip.setOrientation(LinearLayout.HORIZONTAL);
        statusChip.setGravity(Gravity.CENTER_VERTICAL);
        statusChip.setPadding(dp(12), dp(7), dp(14), dp(7));
        withRipple(statusChip, pill(PILL_BG, 16, dp(1), PILL_STROKE));
        statusChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onStatusClick();
            }
        });

        statusDot = new View(host);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(7);
        statusDot.setLayoutParams(dotLp);
        statusDot.setBackground(oval(DOT_OFFLINE));
        statusChip.addView(statusDot);

        statusText = new TextView(host);
        statusText.setText("未登录");
        statusText.setTextColor(TEXT);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusText.setIncludeFontPadding(false);
        statusChip.addView(statusText);

        topBar.addView(statusChip);
        topBar.addView(space(0, 1, 1f));

        feedButton = pill("推荐", new Runnable() {
            @Override
            public void run() {
                listener.onFeed();
            }
        });
        topBar.addView(feedButton, pillLp(dp(6)));

        focusButton = pill("完整", new Runnable() {
            @Override
            public void run() {
                listener.onFocusToggle();
            }
        });
        topBar.addView(focusButton, pillLp(dp(6)));

        modeButton = pill("网页", new Runnable() {
            @Override
            public void run() {
                listener.onModeToggle();
            }
        });
        topBar.addView(modeButton, pillLp(0));

        // 全屏时 Dock 收起来了，退出全屏的入口只能放在顶栏上。
        // 顶栏本身 4 秒淡出，手指一碰屏幕又会回来（WebView 的 ACTION_DOWN → peek）。
        exitFullButton = pill("退出全屏", new Runnable() {
            @Override
            public void run() {
                listener.onFullscreen();
            }
        });
        exitFullButton.setTextColor(ACCENT);
        exitFullButton.setVisibility(View.GONE);
        topBar.addView(exitFullButton, pillLp(0));

        overlay.addView(topBar);

        // ---------------------------------------------------------- Dock
        dock = new LinearLayout(host);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setBackgroundColor(DOCK_BG);
        dock.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 第一块就是进度条：线画在它的顶边，等于「视频底边那条进度」。
        // 这里不留左右 padding —— 要的是贯穿整块屏幕的分界感。
        seekStrip = new SeekStrip(host);
        seekStrip.setOnSeekListener(new SeekStrip.OnSeekListener() {
            @Override
            public void onSeekStart() {
                peek();
            }

            @Override
            public void onSeek(float fraction) {
                listener.onSeek(fraction);
            }
        });
        dock.addView(seekStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SEEK_H)));

        // ---------------------------------------------------------- 互动行
        actionRow = new LinearLayout(host);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(dp(10), 0, dp(6), 0);
        dock.addView(actionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ACTION_H)));

        avatarView = new AvatarView(host);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        avLp.rightMargin = dp(8);
        avatarView.setLayoutParams(avLp);
        actionRow.addView(avatarView);

        followChip = new TextView(host);
        followChip.setText("关注");
        followChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        followChip.setIncludeFontPadding(false);
        followChip.setSingleLine(true);
        followChip.setGravity(Gravity.CENTER);
        followChip.setPadding(dp(12), dp(4), dp(12), dp(4));
        // 实心红底 + 白字：抖音原生的「关注」就是这个样子，
        // 之前用半透明红底 + 红字，在深色 Dock 上几乎看不见
        withRipple(followChip, pill(ACCENT, 12, 0, 0));
        followChip.setTextColor(Color.WHITE);
        followChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                peek();
                listener.onFollow();
            }
        });
        actionRow.addView(followChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));

        // 中间这段留空：把「作者」和「互动」分开，也顺手把两边顶到各自边上
        actionRow.addView(space(0, 1, 1f));

        likeIcon = action(R.drawable.ic_heart, dp(56), 27, new Runnable() {
            @Override
            public void run() {
                listener.onLike();
            }
        });
        actionRow.addView(likeIcon);

        actionRow.addView(action(R.drawable.ic_comment, dp(56), 25, new Runnable() {
            @Override
            public void run() {
                listener.onComment();
            }
        }));

        collectIcon = action(R.drawable.ic_star, dp(56), 27, new Runnable() {
            @Override
            public void run() {
                listener.onCollect();
            }
        });
        actionRow.addView(collectIcon);

        actionRow.addView(action(R.drawable.ic_share, dp(56), 27, new Runnable() {
            @Override
            public void run() {
                listener.onShare();
            }
        }));

        actionRow.addView(action(R.drawable.ic_fullscreen, dp(52), 26, new Runnable() {
            @Override
            public void run() {
                listener.onFullscreen();
            }
        }));

        // ---------------------------------------------------------- 工具行
        // 页面底部那排控件（弹幕 / 倍速 / 清晰度 / 合集）被整排 display:none 掉了，
        // 功能不能跟着一起消失，所以在这里补一条同样能力的工具行。
        // 文案由页面回传的实时值填充：弹幕开关状态、当前清晰度、当前倍速。
        toolRow = new LinearLayout(host);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setGravity(Gravity.CENTER_VERTICAL);
        toolRow.setPadding(dp(10), 0, dp(10), dp(6));
        dock.addView(toolRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(TOOL_H)));

        volumeIcon = new ImageView(host);
        volumeIcon.setImageResource(R.drawable.ic_volume_off);
        volumeIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        volumeIcon.setPadding(dp(2), dp(6), dp(2), dp(6));
        applyRipple(volumeIcon);
        volumeIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                peek();
                listener.onVolumeToggle();
            }
        });
        LinearLayout.LayoutParams volIconLp = new LinearLayout.LayoutParams(dp(27), dp(27));
        volIconLp.rightMargin = dp(6);
        toolRow.addView(volumeIcon, volIconLp);

        volumeStrip = new VolumeStrip(host);
        LinearLayout.LayoutParams volLp = new LinearLayout.LayoutParams(0, dp(22), 1f);
        volLp.rightMargin = dp(12);
        volumeStrip.setLayoutParams(volLp);
        volumeStrip.setOnVolumeListener(new VolumeStrip.OnVolumeListener() {
            @Override
            public void onVolume(float level) {
                listener.onVolume(level);
            }
        });
        toolRow.addView(volumeStrip);

        danmuChip = toolChip("弹幕关", new Runnable() {
            @Override
            public void run() {
                listener.onDanmuToggle();
            }
        });
        toolRow.addView(danmuChip, toolLp());

        qualityChip = toolChip("智能", new Runnable() {
            @Override
            public void run() {
                listener.onQualityCycle();
            }
        });
        toolRow.addView(qualityChip, toolLp());

        rateChip = toolChip("1.0x", new Runnable() {
            @Override
            public void run() {
                listener.onRateCycle();
            }
        });
        toolRow.addView(rateChip, toolLp());

        collectionChip = toolChip("合集", new Runnable() {
            @Override
            public void run() {
                listener.onCollection();
            }
        });
        toolRow.addView(collectionChip, toolLp());
        // 多数视频没有合集，先按「不可用」画；页面回传有合集再点亮
        setCollection(false);

        // 启动默认静音：图标、滑条都从 0 开始（真正的 muted 由页面侧的 setVolume 落）
        setVolume(0f);
        setChromeVisible(true);

        // 调试用：Dock 是纯代码布局，真机上拿不到坐标就只能靠猜。
        // 布局稳定后打一条，`adb logcat | grep Dock` 就能拿到每个控件的中心点。
        dock.postDelayed(new Runnable() {
            @Override
            public void run() {
                logBounds();
            }
        }, 1200);
    }

    private void logBounds() {
        StringBuilder sb = new StringBuilder();
        appendBounds(sb, "seek", seekStrip);
        appendBounds(sb, "action", actionRow);
        appendBounds(sb, "tool", toolRow);
        appendBounds(sb, "avatar", avatarView);
        appendBounds(sb, "follow", followChip);
        appendBounds(sb, "like", likeIcon);
        appendBounds(sb, "collect", collectIcon);
        appendBounds(sb, "volume", volumeStrip);
        appendBounds(sb, "danmu", danmuChip);
        appendBounds(sb, "quality", qualityChip);
        appendBounds(sb, "rate", rateChip);
        appendBounds(sb, "coll", collectionChip);
        ShellLog.i("UI", "Dock 坐标 " + sb);
    }

    private void appendBounds(StringBuilder sb, String name, View v) {
        if (v == null) return;
        int[] p = new int[2];
        v.getLocationOnScreen(p);
        sb.append(name).append('=').append(p[0] + v.getWidth() / 2)
                .append(',').append(p[1] + v.getHeight() / 2).append(' ');
    }

    // ------------------------------------------------------------------ 对外

    /** 浮层（顶栏），加进 root 的 FrameLayout，尺寸 MATCH_PARENT。 */
    public View getOverlayView() {
        return overlay;
    }

    /** 底部 Dock，加进纵向 LinearLayout，插在 WebView 下面，WRAP_CONTENT 高。 */
    public View getDockView() {
        return dock;
    }

    public void setLoggedIn(boolean loggedIn) {
        statusText.setText(loggedIn ? "已登录" : "未登录");
        statusDot.setBackground(oval(loggedIn ? DOT_ONLINE : DOT_OFFLINE));
    }

    /**
     * 状态栏高度。顶部沉浸之后 WebView 铺到 y=0，顶栏这排胶囊必须自己往下让，
     * 否则「未登录 / 推荐 / 完整 / 网页」正好压在时间和信号上。
     */
    public void setTopInset(int insetPx) {
        ViewGroup.LayoutParams lp = topBar.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) return;
        int target = insetPx + dp(8);
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
        if (flp.topMargin == target) return;
        flp.topMargin = target;
        topBar.setLayoutParams(flp);
    }

    public void setImmersive(boolean immersiveMode) {
        immersive = immersiveMode;
        modeButton.setText(immersiveMode ? "网页" : "沉浸");
    }

    public boolean isImmersive() {
        return immersive;
    }

    /**
     * 精简模式 = 页面只留视频，隐藏左侧导航和搜索框。
     * 按钮显示的是「点下去会变成什么」，和 modeButton 一个约定。
     */
    public void setFocus(boolean focus) {
        focusButton.setText(focus ? "完整" : "精简");
    }

    /**
     * 页面每 250ms 推一次播放进度（1x/1.5x/2x 都按真实倍速外推）。
     * duration 为 0 表示当前没有可播放的视频（网格页 / 换片中），把进度条收起来。
     *
     * 250ms 一次直接画仍会「一顿一顿」，所以 playing 现在有正事：
     * 交给 SeekStrip 自己去两次真值之间外推，见 SeekStrip.setProgress。
     */
    public void setProgress(double current, double duration, boolean playing, double speed) {
        if (duration <= 0 || !isFinite(duration)) {
            seekStrip.setAvailable(false);
            return;
        }
        seekStrip.setAvailable(true);
        seekStrip.setTimes(current, duration);
        // 手指正按着进度条时不要跟用户抢
        if (!seekStrip.isDragging()) {
            double f = current / duration;
            seekStrip.setProgress((float) Math.max(0, Math.min(1, f)), playing, duration, speed);
        }
    }

    /** 音量条 + 喇叭图标同步。0 就是静音。 */
    public void setVolume(float level) {
        float v = Math.max(0f, Math.min(1f, level));
        volumeStrip.setLevel(v);
        volumeIcon.setImageResource(v > 0.001f
                ? R.drawable.ic_volume_on : R.drawable.ic_volume_off);
        volumeIcon.setAlpha(v > 0.001f ? 1f : 0.75f);
    }

    /**
     * 作者信息：头像 + 昵称。
     *
     * 头像走 HTTP 直接下（CDN 不带 cookie），下载在后台线程，回来了再 invalidate。
     * 关注状态不在这里改 —— 页面那颗 + 徽标分不出已关注/未关注，见注入层 authorInfo()。
     */
    public void setAuthor(String avatarUrl, String nick) {
        authorName = nick == null ? "" : nick;
        avatarView.setUrl(avatarUrl);
    }

    /** 关注状态：页面那头还有「+」就是没关注，没了就是已关注。 */
    public void setFollowing(boolean on) {
        following = on;
        followChip.setText(on ? "已关注" : "关注");
        followChip.setTextColor(on ? TEXT_DIM : Color.WHITE);
        withRipple(followChip, pill(on ? 0x1AFFFFFF : ACCENT, 12, 0, 0));
        followChip.setAlpha(on ? 0.7f : 1f);
    }

    public boolean isFollowing() {
        return following;
    }

    /**
     * 点赞 / 收藏状态，数据来自页面按钮上的 data-e2e-state。
     *
     * 注意不能写成 setColorFilter(on ? ACCENT : null)：
     * 三元里有 null，编译器会挑 setColorFilter(int) 这个重载再自动拆箱，
     * 传 false 时直接 NullPointerException 把整个 App 带走。
     */
    public void setLiked(boolean on) {
        if (on) likeIcon.setColorFilter(ACCENT);
        else likeIcon.clearColorFilter();
    }

    public void setCollected(boolean on) {
        // 收藏是描边星，光靠染色不够明显，已收藏换成实心那颗
        collectIcon.setImageResource(on ? R.drawable.ic_star_on : R.drawable.ic_star);
        if (on) collectIcon.setColorFilter(STAR);
        else collectIcon.clearColorFilter();
    }

    /**
     * 双击点赞的反馈。
     *
     * 页面上那颗心被我们 opacity:0 了（点的是它，看不见），
     * 如果原生这边不再动一下，用户会觉得「双击没反应」——
     * 哪怕视频其实已经点赞成功。这里让 Dock 上的心弹一下。
     */
    public void pulseLike() {
        pulse(likeIcon, ACCENT);
    }

    public void pulseCollected() {
        pulse(collectIcon, STAR);
    }

    private void pulse(final ImageView icon, final int color) {
        icon.animate().cancel();
        icon.setColorFilter(color);
        icon.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        icon.animate().scaleX(1f).scaleY(1f).setDuration(180)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        // 颜色留给 setLiked/setCollected 决定，这里只收动画
                                        icon.animate().setDuration(0);
                                    }
                                }).start();
                    }
                }).start();
    }

    /**
     * 全屏开 / 关。
     *
     * 全屏时 Dock 必须收起：它是真实占位，留着就等于画面高度少一截。
     * 系统栏的隐藏由 Activity 做（见 enterFullscreen），这里只管自己这一层。
     */
    public void setFullscreen(boolean on) {
        fullscreen = on;
        dock.setVisibility(on ? View.GONE : View.VISIBLE);
        exitFullButton.setVisibility(on ? View.VISIBLE : View.GONE);
        // 进全屏先把顶栏亮一下（上面有「退出全屏」），4 秒后自动淡出；
        // 否则用户找不到出口，只能去按返回键。
        setChromeVisible(true);
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    /** 让顶栏重新露出来。Dock 是常驻的，不受影响。 */
    public void peek() {
        setChromeVisible(true);
    }

    /**
     * 工具行的实时状态，跟着播放进度一起从页面推上来。
     *
     * @param quality 当前清晰度短标签，如 720P / 智能
     * @param rate    当前倍速，如 1.0 / 1.25
     * @param danmu   1 = 弹幕开
     */
    public void setTools(String quality, double rate, int danmu) {
        if (quality != null && quality.length() > 0 && !"null".equals(quality)) {
            setQuality(quality);
        }
        setRate(rate);
        setDanmuOn(danmu > 0);
    }

    public void setDanmuOn(boolean on) {
        danmuChip.setText(on ? "弹幕开" : "弹幕关");
        paintToolChip(danmuChip, on);
    }

    public void setQuality(String label) {
        qualityChip.setText(label);
        paintToolChip(qualityChip, false);
    }

    public void setRate(double rate) {
        if (!isFinite(rate) || rate <= 0) rate = 1;
        boolean whole = Math.abs(rate - Math.round(rate)) < 0.001;
        rateChip.setText(String.format(java.util.Locale.ROOT,
                whole ? "%.1fx" : "%.2fx", rate));
        paintToolChip(rateChip, Math.abs(rate - 1) > 0.001);
    }

    /** 当前视频有没有合集入口：没有就把胶囊压暗、点不动，免得点了没反应。 */
    public void setCollection(boolean has) {
        collectionAvailable = has;
        collectionChip.setAlpha(has ? 1f : 0.4f);
        collectionChip.setEnabled(has);
        paintToolChip(collectionChip, false);
    }

    public boolean hasCollection() {
        return collectionAvailable;
    }

    /**
     * 顶栏显隐。Dock 常驻，不参与淡出 —— 它是布局的一部分，不是浮层。
     * 淡出之后必须设成 INVISIBLE —— alpha=0 的按钮虽然看不见，
     * 但仍然会吃掉触摸，等于给页面上盖了 3 个隐形按钮。
     */
    public void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        handler.removeCallbacks(hideTask);
        fade(topBar, visible);
        // 全屏时顶栏不自动淡出：Dock 已经收了，「退出全屏」是唯一出口，
        // 淡掉之后用户得先点一下唤回来、再点一下才退得出去，很难解释。
        if (visible && !fullscreen) handler.postDelayed(hideTask, AUTO_HIDE_MS);
    }

    public boolean isChromeVisible() {
        return chromeVisible;
    }

    /** 登录引导是全屏卡片：整层（含 Dock）让开，用 INVISIBLE 保住布局。 */
    public void setRootVisible(boolean visible) {
        if (visible) {
            overlay.setVisibility(View.VISIBLE);
            // 全屏状态下 Dock 本来就该是 GONE，别在这里把它放出来
            dock.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
            setChromeVisible(true);
        } else {
            handler.removeCallbacks(hideTask);
            overlay.setVisibility(View.INVISIBLE);
            dock.setVisibility(View.INVISIBLE);
        }
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        avatarView.release();
    }

    // ------------------------------------------------------------------ 构件

    private void fade(final View view, boolean visible) {
        view.animate().cancel();
        if (visible) {
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).setDuration(200).start();
            return;
        }
        view.animate().alpha(0f).setDuration(200)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!chromeVisible) view.setVisibility(View.INVISIBLE);
                    }
                }).start();
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static String clock(double seconds) {
        if (!isFinite(seconds) || seconds < 0) seconds = 0;
        int total = (int) Math.round(seconds);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
    }

    /**
     * Dock 里的图标按钮：固定触摸区 + 指定图形尺寸。
     *
     * 图形不再靠 CENTER_INSIDE —— 那个只缩小不放大，24dp 的 vector 会永远是 24dp。
     * 改成 FIT_CENTER + 用 padding 把内容区压到 iconDp，图形才会被真正放大。
     */
    private ImageView action(int iconRes, int widthPx, int iconDp, final Runnable onClick) {
        ImageView icon = new ImageView(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                widthPx, ViewGroup.LayoutParams.MATCH_PARENT);
        icon.setLayoutParams(lp);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageResource(iconRes);
        int padX = Math.max(0, (widthPx - dp(iconDp)) / 2);
        icon.setPadding(padX, 0, padX, 0);
        applyRipple(icon);
        icon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                peek();
                onClick.run();
            }
        });
        return icon;
    }

    /** 工具行的小胶囊（弹幕 / 清晰度 / 倍速 / 合集）。 */
    private TextView toolChip(String text, final Runnable onClick) {
        TextView v = new TextView(host);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(4), dp(4), dp(4), dp(4));
        v.setTextColor(TEXT_DIM);
        withRipple(v, pill(TOOL_BG, 10, 0, 0));
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                peek();
                onClick.run();
            }
        });
        return v;
    }

    private LinearLayout.LayoutParams toolLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1f);
        lp.leftMargin = dp(4);
        return lp;
    }

    /** 重新画胶囊底色 —— 不能只改文字色，底色是 RippleDrawable，得整层换掉。 */
    private void paintToolChip(TextView v, boolean on) {
        withRipple(v, pill(on ? TOOL_BG_ON : TOOL_BG, 10, 0, 0));
        v.setTextColor(on ? ACCENT : TEXT_DIM);
    }

    private View space(int w, int h, float weight) {
        View v = new View(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
        v.setLayoutParams(lp);
        return v;
    }

    /** 顶栏胶囊按钮。 */
    private TextView pill(String text, final Runnable onClick) {
        TextView v = new TextView(host);
        v.setText(text);
        v.setTextColor(TEXT_DIM);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        v.setIncludeFontPadding(false);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14), dp(7), dp(14), dp(7));
        withRipple(v, pill(PILL_BG, 16, dp(1), PILL_STROKE));
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                peek();
                onClick.run();
            }
        });
        return v;
    }

    private LinearLayout.LayoutParams pillLp(int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = rightMargin;
        return lp;
    }

    private GradientDrawable pill(int color, int radiusDp, int strokeWidth, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        // setCornerRadius 收的是 px，这里统一从 dp 换算，否则高密度屏会变成直角
        d.setCornerRadius(dp(radiusDp));
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /**
     * 给胶囊按钮加一层水波纹。
     * 注意不能用 setBackgroundResource 覆盖——那样会把胶囊底色一起冲掉，
     * 所以把底色作为 ripple 的 content 传进去。
     */
    private void withRipple(View view, Drawable content) {
        // RippleDrawable 会用同一个 shape 当 mask，把水波纹裁在圆角里
        Drawable mask = content.getConstantState() != null
                ? content.getConstantState().newDrawable().mutate() : null;
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), content, mask));
    }

    /** 底部图标按钮没有底色，用系统自带的 borderless ripple 就够了。 */
    private void applyRipple(View view) {
        TypedValue out = new TypedValue();
        if (host.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, out, true)) {
            view.setBackgroundResource(out.resourceId);
        }
    }

    private int dp(float value) {
        return Math.round(value * host.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ 作者头像

    /**
     * Dock 最左边的作者头像。
     *
     * 不用 Glide/Picasso：整个工程零依赖，为了一张 30dp 的圆图引一个图片库不划算。
     * 这里用 BitmapShader 直接画圆，下载扔到后台线程，回来再 invalidate。
     */
    public static final class AvatarView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap bitmap;
        private Shader shader;
        private String url = "";
        private volatile boolean alive = true;

        AvatarView(Activity host) {
            super(host);
            bgPaint.setColor(0x22FFFFFF);
            paint.setFilterBitmap(true);
            paint.setAntiAlias(true);
        }

        void setUrl(String next) {
            if (next == null) next = "";
            if (next.startsWith("//")) next = "https:" + next;
            if (next.equals(url)) return;
            url = next;
            bitmap = null;
            shader = null;
            invalidate();
            if (next.length() == 0) return;
            final String target = next;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Bitmap bmp = download(target);
                    if (bmp == null || !alive) return;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive || !target.equals(url)) return;
                            bitmap = bmp;
                            shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                            invalidate();
                        }
                    });
                }
            }, "dy-avatar").start();
        }

        void release() {
            alive = false;
        }

        private static Bitmap download(String url) {
            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setDoInput(true);
                conn.connect();
                if (conn.getResponseCode() != 200) return null;
                return android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
            } catch (Throwable t) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w, h) / 2f;
            float cx = w / 2f;
            float cy = h / 2f;
            if (bitmap == null || shader == null) {
                canvas.drawCircle(cx, cy, r, bgPaint);
                return;
            }
            // 让图片按「短边铺满」缩放后居中 —— 头像基本是方的，这一手是防畸形图
            int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
            float scale = (r * 2f) / size;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(scale, scale);
            m.postTranslate(cx - size * scale / 2f, cy - size * scale / 2f);
            shader.setLocalMatrix(m);
            paint.setShader(shader);
            canvas.drawCircle(cx, cy, r, paint);
        }
    }

    // ------------------------------------------------------------------ 音量条

    /**
     * Dock 上的音量条。和 SeekStrip 长得像，但用途完全不同：
     * 它拖的不是进度，是 <video>.volume；点一下直接跳到那个位置。
     *
     * 不显示百分比数字 —— 400dp 宽的机器上这一条只剩 150dp，再塞文字就挤了。
     */
    public static final class VolumeStrip extends View {

        public interface OnVolumeListener {
            void onVolume(float level);
        }

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final float lineH;
        private final float knobR;

        private float level;
        private boolean dragging;
        private OnVolumeListener listener;

        VolumeStrip(Activity host) {
            super(host);
            float density = host.getResources().getDisplayMetrics().density;
            lineH = 2.5f * density;
            knobR = 4f * density;

            trackPaint.setColor(0x33FFFFFF);
            trackPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(Color.WHITE);
            fillPaint.setStyle(Paint.Style.FILL);
            knobPaint.setColor(Color.WHITE);
            knobPaint.setStyle(Paint.Style.FILL);

            setClickable(true);
            setFocusable(false);
        }

        void setOnVolumeListener(OnVolumeListener l) {
            listener = l;
        }

        void setLevel(float v) {
            if (dragging) return;   // 手指在外面拖着的时候别被后台值拽回去
            level = Math.max(0f, Math.min(1f, v));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float cy = getHeight() / 2f;
            float half = lineH / 2f;
            // 两端各留出旋钮半径，拖到 0 / 1 时圆点不会被裁掉
            float usable = Math.max(1f, w - knobR * 2);
            float x = knobR + usable * level;

            canvas.drawRoundRect(0, cy - half, w, cy + half, half, half, trackPaint);
            if (level > 0.001f) {
                canvas.drawRoundRect(0, cy - half, x, cy + half, half, half, fillPaint);
            }
            canvas.drawCircle(x, cy, dragging ? knobR * 1.2f : knobR, knobPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    apply(e.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) apply(e.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        apply(e.getX());
                        dragging = false;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        /** 手指位置 → 0..1，边拖边回报，声音跟着手指走。 */
        private void apply(float x) {
            float usable = Math.max(1f, getWidth() - knobR * 2);
            level = Math.max(0f, Math.min(1f, (x - knobR) / usable));
            invalidate();
            if (listener != null) listener.onVolume(level);
        }
    }
    // ------------------------------------------------------------------ 进度条

    /**
     * 进度条，贴在 Dock 最上沿。
     *
     * 三个要点：
     *
     * 1) **线画在 View 的顶边**。这个 View 是 Dock 的第一块，线在哪里，
     *    视频和 Dock 的分界就在哪里 —— 所以它看上去是「视频底边那条进度」，
     *    而不是 Dock 里的一个控件。
     *
     * 2) **流动，不跳格**。页面每 250ms 才推一次 currentTime，直接 setFraction
     *    就是四下一跳的「顿感」。这里不靠无限提高推送频率（那是跨进程调用），
     *    而是在两次真值之间按 speed/duration 的速率自己外推，每帧重绘。
     *    真值回来时只做「跟上」，绝不回退 —— 回退才是肉眼看到的抖。
     *
     * 3) **2dp 细线**。粗细和抖音原生那条一致，太粗会抢画面。
     */
    public static final class SeekStrip extends View {

        public interface OnSeekListener {
            void onSeekStart();

            void onSeek(float fraction);
        }

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint timeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final float density;
        private final float lineH;
        private final float knobR;
        private final float lineCy;

        /** 当前显示用的比例（外推之后的值）。 */
        private float fraction;
        /** 外推锚点：真值 + 时刻 + 速率。 */
        private float anchorFrac;
        private long anchorAt;
        private float ratePerMs;
        /** 最多往前外推这么多，防止缓冲/卡顿时提前跑到前面太多。 */
        private float maxAheadFrac;
        private boolean animating;

        private boolean dragging;
        private boolean available = true;
        private String timeLabel = "";
        private OnSeekListener listener;

        SeekStrip(Activity host) {
            super(host);
            density = host.getResources().getDisplayMetrics().density;
            lineH = 2f * density;
            knobR = 3.2f * density;
            // 圆心贴着顶边：留 1dp 余量，圆点刚好不被裁掉
            lineCy = Math.max(knobR, lineH / 2f + 1f);

            trackPaint.setColor(0x40FFFFFF);
            trackPaint.setStyle(Paint.Style.FILL);
            playedPaint.setColor(ACCENT);
            playedPaint.setStyle(Paint.Style.FILL);
            knobPaint.setColor(Color.WHITE);
            knobPaint.setStyle(Paint.Style.FILL);

            // 系统默认字体 —— 不给 Typeface.MONOSPACE，等宽字和视频信息区的字不搭
            timePaint.setColor(Color.WHITE);
            timePaint.setTextSize(9f * density);
            timePaint.setAntiAlias(true);
            timePaint.setTextAlign(Paint.Align.CENTER);
            timeBgPaint.setColor(0xE6101014);

            setClickable(true);
            setFocusable(false);
        }

        void setOnSeekListener(OnSeekListener l) {
            listener = l;
        }

        boolean isDragging() {
            return dragging;
        }

        /** 没片子的时候整条收掉（连轨道一起），否则底边会挂着一条空线。 */
        void setAvailable(boolean on) {
            if (available == on) return;
            available = on;
            if (!on) animating = false;
            invalidate();
        }

        void setTimes(double current, double duration) {
            String next = clock(current) + " / " + clock(duration);
            if (next.equals(timeLabel)) return;
            timeLabel = next;
            if (dragging) invalidate();
        }

        /**
         * 收到一次真值。
         *
         * @param f        进度比例
         * @param playing  是否在播（暂停/缓冲时不动，否则条会一直往前爬）
         * @param durSec   总时长，用来算外推速率
         * @param speed    播放倍速（1.5x 时 currentTime 走得比墙钟快，外推必须同步）
         */
        void setProgress(float f, boolean playing, double durSec, double speed) {
            if (dragging) return;
            long now = SystemClock.elapsedRealtime();
            float shown = fractionAt(now);
            float diff = f - shown;
            // 用「秒」判跳变，而不是比例：478s 的长视频 1% 就是 4.8s，
            // 拿比例当阈值会把一次正常的后退 seek 当成抖动吃掉。
            float diffSec = (float) (diff * durSec);

            // 真值只做「校正」，不做「倒带」：
            //  - 差得远 → 换片 / seek，直接对位；
            //  - 略快于外推 → 跟上（最多几十毫秒，肉眼看不到）；
            //  - 略慢于外推 → 停在当前显示位置等它追上来。
            // 老逻辑在最后一档会把 anchor 留在旧值、却又把 anchorAt 刷新到现在，
            // 等于每 250ms 把线往回拽一次，这才是「不丝滑」的主因。
            if (Math.abs(diffSec) > 0.8f) {
                anchorFrac = f;
            } else if (diff >= 0f) {
                anchorFrac = f;
            } else {
                anchorFrac = playing ? shown : f;
            }

            anchorFrac = clamp(anchorFrac);
            anchorAt = now;
            boolean canRun = playing && durSec > 0.05 && isFinite(durSec)
                    && speed > 0.05 && isFinite(speed);
            ratePerMs = canRun ? (float) (speed / (durSec * 1000.0)) : 0f;
            // 最多允许外推 0.6s 的量：页面 250ms 一推，正常永远撞不到；
            // 真缓冲时让它停在画面前面一点点，而不是一路跑飞。
            maxAheadFrac = canRun ? (float) (0.6 * speed / durSec) : 0f;
            animating = canRun && anchorFrac < 1f;
            if (!canRun) fraction = anchorFrac;
            invalidate();
        }

        /** 拖动时用：立即定位，不做外推。 */
        void setFraction(float f) {
            anchorFrac = clamp(f);
            fraction = anchorFrac;
            anchorAt = SystemClock.elapsedRealtime();
            ratePerMs = 0f;
            animating = false;
            invalidate();
        }

        private static float clamp(float f) {
            return Math.max(0f, Math.min(1f, f));
        }

        private float fractionAt(long now) {
            if (ratePerMs <= 0f) return anchorFrac;
            float adv = ratePerMs * (now - anchorAt);
            if (maxAheadFrac > 0f && adv > maxAheadFrac) adv = maxAheadFrac;
            return clamp(anchorFrac + adv);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!available) return;
            long now = SystemClock.elapsedRealtime();
            if (!dragging) fraction = fractionAt(now);

            float w = getWidth();
            float half = lineH / 2f;
            float r = half;

            canvas.drawRoundRect(0, lineCy - half, w, lineCy + half, r, r, trackPaint);
            float x = Math.max(half, Math.min(w - half, w * fraction));
            if (fraction > 0f) {
                canvas.drawRoundRect(0, lineCy - half, x, lineCy + half, r, r, playedPaint);
            }
            canvas.drawCircle(x, lineCy, dragging ? knobR * 1.25f : knobR, knobPaint);

            if (dragging && timeLabel.length() > 0) {
                float padX = 6f * density;
                float padY = 2.5f * density;
                float tw = timePaint.measureText(timeLabel);
                float boxW = tw + padX * 2;
                float boxH = timePaint.getTextSize() + padY * 2;
                float cy = Math.min(getHeight() - boxH / 2f - 0.5f, lineCy + knobR + boxH / 2f + 2f);
                float cx = Math.max(boxW / 2f, Math.min(w - boxW / 2f, x));
                canvas.drawRoundRect(cx - boxW / 2f, cy - boxH / 2f,
                        cx + boxW / 2f, cy + boxH / 2f, boxH / 2f, boxH / 2f, timeBgPaint);
                float baseline = cy - (timePaint.descent() + timePaint.ascent()) / 2f;
                canvas.drawText(timeLabel, cx, baseline, timePaint);
            }

            // 外推需要每帧重绘；只在「在播 + 没在拖」时挂帧循环
            if (animating && !dragging) postInvalidateOnAnimation();
        }

        @Override
        protected void onDetachedFromWindow() {
            animating = false;
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled() || !available) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    animating = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    if (listener != null) listener.onSeekStart();
                    setFraction(e.getX() / Math.max(1f, getWidth()));
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) setFraction(e.getX() / Math.max(1f, getWidth()));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        setFraction(e.getX() / Math.max(1f, getWidth()));
                        dragging = false;
                        invalidate();
                        if (listener != null) listener.onSeek(fraction);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }
    }
}
