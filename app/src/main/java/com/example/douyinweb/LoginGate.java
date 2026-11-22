package com.example.douyinweb;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 首次进入时的登录引导。
 *
 * 定位很关键：本应用**不做**账号体系，不采集手机号 / 验证码 / 密码，
 * 只负责把用户送到抖音官方页面的登录入口，登录完成后回读 cookie 判断状态。
 * 所以这里没有任何输入框——有输入框就等于要处理凭据，那是另一个性质的东西。
 */
public final class LoginGate {

    public interface Listener {
        /** 用户选择去登录。 */
        void onLogin();

        /** 用户选择先看看。 */
        void onSkip();
    }

    private static final int ACCENT = 0xFFFE2C55;
    private static final int CARD_BG = 0xFF1C1C20;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0x99FFFFFF;
    private static final int SCRIM = 0xE6000000;

    private final Activity host;
    private final FrameLayout rootView;
    private boolean visible;

    public LoginGate(Activity host, final Listener listener) {
        this.host = host;

        rootView = new FrameLayout(host);
        rootView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootView.setBackgroundColor(SCRIM);
        rootView.setClickable(true); // 挡住下面的一切，避免误触到页面

        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD_BG, 22));
        card.setPadding(dp(24), dp(26), dp(24), dp(18));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp(28);
        cardLp.rightMargin = dp(28);
        card.setLayoutParams(cardLp);

        TextView title = text("登录抖音", 21, TEXT, true);
        card.addView(title);

        TextView desc = text(
                "登录在抖音官方页面内完成，本应用不接触你的账号密码，也不会保存登录凭据。\n"
                        + "登录后就能点赞、看评论、关注作者。",
                13, TEXT_DIM, false);
        desc.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(10);
        desc.setLayoutParams(descLp);
        card.addView(desc);

        TextView primary = text("在页面内登录", 15, TEXT, true);
        primary.setGravity(Gravity.CENTER);
        primary.setBackground(rounded(ACCENT, 24));
        LinearLayout.LayoutParams primaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        primaryLp.topMargin = dp(22);
        primary.setLayoutParams(primaryLp);
        primary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onLogin();
            }
        });
        card.addView(primary);

        TextView secondary = text("先随便看看", 13, TEXT_DIM, false);
        secondary.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams secondaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        secondaryLp.topMargin = dp(6);
        secondary.setLayoutParams(secondaryLp);
        secondary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onSkip();
            }
        });
        card.addView(secondary);

        rootView.addView(card);
        rootView.setVisibility(View.GONE);
    }

    public View getRootView() {
        return rootView;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean show) {
        visible = show;
        rootView.animate().cancel();
        if (show) {
            rootView.setVisibility(View.VISIBLE);
            rootView.setAlpha(0f);
            rootView.animate().alpha(1f).setDuration(180).start();
        } else {
            rootView.animate().alpha(0f).setDuration(160)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (!visible) rootView.setVisibility(View.GONE);
                        }
                    }).start();
        }
    }

    // ------------------------------------------------------------------ 构件

    private TextView text(String content, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(host);
        tv.setText(content);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float value) {
        return Math.round(value * host.getResources().getDisplayMetrics().density);
    }
}
