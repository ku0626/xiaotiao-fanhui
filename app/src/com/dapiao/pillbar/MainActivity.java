package com.dapiao.pillbar;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int BG = 0xFFF5F6F8;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF12161B;
    private static final int SUB = 0xFF5F6672;
    private static final int ACCENT = 0xFF1A6DFF;

    private LinearLayout root;
    private TextView statusText;
    private LinearLayout styleCard;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("小条返回");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("轻点 = 返回　·　上滑 = 桌面　·　上滑停顿 = 多任务　·　长按 = 所选动作　·　左右滑 = 切应用");
        sub.setTextSize(13);
        sub.setTextColor(SUB);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        buildStatusCard();
        buildSwitchCard();
        buildLongPressCard();
        buildDoubleTapCard();
        buildStyleCard();
        buildColorCard();
        buildActionCard();
        buildSystemBarCard();
        buildTips();

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        applyRecentsHidden();
        PillService.refreshIfRunning();
    }

    // ---------------- UI 构件 ----------------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(14 * d);
        c.setBackground(bg);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        root.addView(c, p);
        return c;
    }

    private TextView label(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(6));
        parent.addView(t);
        return t;
    }

    private TextView hint(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(SUB);
        t.setPadding(0, 0, 0, dp(8));
        parent.addView(t);
        return t;
    }

    private void buildStatusCard() {
        LinearLayout c = card();
        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(SUB);
        statusText.setPadding(0, 0, 0, dp(10));
        c.addView(statusText);

        Button open = new Button(this);
        open.setText("打开无障碍设置，开启「小条返回」");
        open.setAllCaps(false);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    Toast.makeText(MainActivity.this,
                            "在列表里找到「小条返回」→ 开启；找不到就点右上角「已下载的应用」",
                            Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "打不开设置，手动进：设置 → 更多设置 → 无障碍",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        c.addView(open);
    }

    private void buildSwitchCard() {
        LinearLayout c = card();
        final Switch sw = new Switch(this);
        sw.setText("启用小条");
        sw.setTextSize(15);
        sw.setTextColor(TEXT);
        sw.setChecked(Prefs.enabled(this));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setEnabled(MainActivity.this, isChecked);
                PillService.refreshIfRunning();
            }
        });
        c.addView(sw);

        final Switch avoid = new Switch(this);
        avoid.setText("对齐系统手势区（推荐，不遮挡 App 内容）");
        avoid.setTextSize(15);
        avoid.setTextColor(TEXT);
        avoid.setPadding(0, dp(8), 0, 0);
        avoid.setChecked(Prefs.avoidGestureZone(this));
        avoid.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setAvoidGestureZone(MainActivity.this, isChecked);
                PillService.refreshIfRunning();
            }
        });
        c.addView(avoid);

        final Switch ext = new Switch(this);
        ext.setText("条下方空间也触发手势（点按=返回，上滑=桌面，上滑停顿=多任务）");
        ext.setTextSize(15);
        ext.setTextColor(TEXT);
        ext.setPadding(0, dp(8), 0, 0);
        ext.setChecked(Prefs.extendDown(this));
        ext.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setExtendDown(MainActivity.this, isChecked);
                PillService.refreshIfRunning();
            }
        });
        c.addView(ext);

        final Switch hideRecents = new Switch(this);
        hideRecents.setText("在本机最近任务里隐藏「小条返回」");
        hideRecents.setTextSize(15);
        hideRecents.setTextColor(TEXT);
        hideRecents.setPadding(0, dp(8), 0, 0);
        hideRecents.setChecked(Prefs.hideFromRecents(this));
        hideRecents.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setHideFromRecents(MainActivity.this, isChecked);
                applyRecentsHidden();
            }
        });
        c.addView(hideRecents);
    }

    /** 把本应用从「最近任务」列表里摘掉，不在后台卡片里占位 */
    private void applyRecentsHidden() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            boolean hide = Prefs.hideFromRecents(this);
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                task.setExcludeFromRecents(hide);
            }
        } catch (Throwable ignored) {
        }
    }

    private void buildLongPressCard() {
        LinearLayout c = card();
        label(c, "长按动作");
        hint(c, "选定后长按横条直接执行，不用再弹菜单确认。选「弹出菜单」才恢复老行为");
        // 「多任务」已移除：呼出最近任务后会自动消失，体验不对
        final int[] actions = {Prefs.LP_MENU, Prefs.LP_XIAOAI, Prefs.LP_LOCK,
                Prefs.LP_PAY_ALI, Prefs.LP_PAY_WECHAT, Prefs.LP_NONE};
        String[] names = {"弹出菜单", "呼出小爱（同官方小白条）", "锁屏",
                "支付宝付款码", "微信扫一扫", "不响应"};
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        int cur = Prefs.longPressAction(this);
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setTextSize(14);
            rb.setTextColor(TEXT);
            rb.setId(3000 + i);
            if (cur == actions[i]) rb.setChecked(true);
            g.addView(rb);
        }
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                Prefs.setLongPressAction(MainActivity.this, actions[checkedId - 3000]);
            }
        });
        c.addView(g);
    }

    private void buildDoubleTapCard() {
        LinearLayout c = card();
        label(c, "双击动作");
        hint(c, "默认「不响应」：单击返回零延迟。选定双击动作后，单击要等一个判定窗口确认不是双击（窗口越短返回越跟手）；判定期间若有滑动/长按，单击会立即放行不等窗口");
        final int[] actions = {Prefs.DP_NONE, Prefs.DP_LOCK, Prefs.DP_XIAOAI,
                Prefs.DP_PAY_ALI, Prefs.DP_PAY_WECHAT};
        String[] names = {"不响应（默认）", "锁屏", "呼出小爱", "支付宝付款码", "微信扫一扫"};
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        int cur = Prefs.doubleTapAction(this);
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setTextSize(14);
            rb.setTextColor(TEXT);
            rb.setId(4000 + i);
            if (cur == actions[i]) rb.setChecked(true);
            g.addView(rb);
        }
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                Prefs.setDoubleTapAction(MainActivity.this, actions[checkedId - 4000]);
                PillService.refreshIfRunning();
            }
        });
        c.addView(g);

        label(c, "双击判定窗口");
        final int[] windows = {180, 220, 300};
        String[] wNames = {"快速（180 毫秒，最跟手）", "标准（220 毫秒，推荐）", "从容（300 毫秒，最稳）"};
        RadioGroup g2 = new RadioGroup(this);
        g2.setOrientation(RadioGroup.VERTICAL);
        int curW = Prefs.doubleTapWindow(this);
        int curIdx = 1;
        for (int i = 0; i < windows.length; i++) {
            if (curW == windows[i]) curIdx = i;
        }
        for (int i = 0; i < wNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(wNames[i]);
            rb.setTextSize(14);
            rb.setTextColor(TEXT);
            rb.setId(4100 + i);
            if (curIdx == i) rb.setChecked(true);
            g2.addView(rb);
        }
        g2.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                Prefs.setDoubleTapWindow(MainActivity.this, windows[checkedId - 4100]);
                PillService.refreshIfRunning();
            }
        });
        c.addView(g2);
    }

    private void buildStyleCard() {
        styleCard = card();
        label(styleCard, "外观");
        hint(styleCard, "「距底部」只在把条往上抬时生效；宽度改的是触控区+条的宽度");
        slider(styleCard, "宽度", 60, 200, Prefs.widthDp(this), " dp", new OnInt() {
            @Override
            public void on(int v) {
                Prefs.setWidthDp(MainActivity.this, v);
            }
        });
        slider(styleCard, "粗细", 3, 14, Prefs.thickDp(this), " dp", new OnInt() {
            @Override
            public void on(int v) {
                Prefs.setThickDp(MainActivity.this, v);
            }
        });
        slider(styleCard, "距底部", 0, 60, Prefs.bottomDp(this), " dp", new OnInt() {
            @Override
            public void on(int v) {
                Prefs.setBottomDp(MainActivity.this, v);
            }
        });
        slider(styleCard, "不透明度", 20, 100, Prefs.opacity(this), " %", new OnInt() {
            @Override
            public void on(int v) {
                Prefs.setOpacity(MainActivity.this, v);
            }
        });
    }

    private void buildColorCard() {
        LinearLayout c = card();
        label(c, "颜色");
        hint(c, "「自动取色」会读屏幕当前背景亮度：白底给黑条、深底给白条，App 有色差也不怕");
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        String[] names = {"自动取色（推荐）", "固定白色", "固定黑色", "跟随系统深色模式"};
        int cur = Prefs.colorMode(this);
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setTextSize(14);
            rb.setTextColor(TEXT);
            rb.setId(1000 + i);
            if (cur == i) rb.setChecked(true);
            g.addView(rb);
        }
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                Prefs.setColorMode(MainActivity.this, checkedId - 1000);
                PillService.refreshIfRunning();
            }
        });
        c.addView(g);
    }

    private void buildActionCard() {
        LinearLayout c = card();
        label(c, "单击动作");
        hint(c, "默认「返回上一级」，就是你要的那个点一下返回");
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        String[] names = {"返回上一级", "回到桌面", "最近任务"};
        int cur = Prefs.tapAction(this);
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setTextSize(14);
            rb.setTextColor(TEXT);
            rb.setId(2000 + i);
            if (cur == i) rb.setChecked(true);
            g.addView(rb);
        }
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                Prefs.setTapAction(MainActivity.this, checkedId - 2000);
                PillService.refreshIfRunning();
            }
        });
        c.addView(g);
    }

    private void buildSystemBarCard() {
        LinearLayout c = card();
        label(c, "系统手势提示线");
        hint(c, "想完全替代系统白条，可以把它也关掉。需要一条 ADB 授权命令，只需执行一次");

        Button hide = new Button(this);
        hide.setText("隐藏系统提示线（先尝试直接关）");
        hide.setAllCaps(false);
        hide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (setGestureLine(1)) {
                    Toast.makeText(MainActivity.this, "已隐藏。若没生效，用下面那两条 ADB 命令",
                            Toast.LENGTH_LONG).show();
                } else {
                    showAdbDialog();
                }
            }
        });
        c.addView(hide);

        Button show = new Button(this);
        show.setText("恢复系统提示线");
        show.setAllCaps(false);
        show.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setGestureLine(0);
                Toast.makeText(MainActivity.this, "已恢复（若未授权会失败）", Toast.LENGTH_SHORT).show();
            }
        });
        c.addView(show);
    }

    private void buildTips() {
        LinearLayout c = card();
        label(c, "使用须知");
        hint(c,
                "1. 手势：轻点=返回，上滑=桌面，上滑停顿=多任务，长按=执行「长按动作」里选定的操作。\n\n"
                        + "2. 开着「条下方空间也触发手势」时，条到屏幕最底边一整片都能触发：点按=返回、上滑=桌面、上滑停住=多任务；键盘弹出时横条自动隐藏，不挡键盘。左右滑切应用请在条上完成或用系统方式。\n\n"
                        + "3. 澎湃OS/安卓会对「侧载安装」的无障碍服务加锁：如果开启时报「受限设置」，先到 设置 → 应用管理 → 小条返回 → 右上角 ⋮ → 允许受限设置。\n\n"
                        + "4. 想让它不被系统杀掉：应用信息 → 省电策略 → 无限制，并打开「自启动」。\n\n"
                        + "5. 自动取色在极少数禁止截屏的 App（银行、支付类）里读不到屏幕，会自动退回跟随深色模式，属于正常现象。");
    }

    private void slider(LinearLayout parent, String name, final int min, final int max,
                        int value, final String unit, final OnInt cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(13);
        n.setTextColor(TEXT);
        n.setWidth(dp(64));
        row.addView(n);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = dp(4);
        row.addView(sb, sp);

        final TextView val = new TextView(this);
        val.setText(value + unit);
        val.setTextSize(13);
        val.setTextColor(SUB);
        val.setGravity(android.view.Gravity.END);
        val.setWidth(dp(62));
        row.addView(val);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                val.setText(v + unit);
                if (fromUser) {
                    cb.on(v);
                    PillService.refreshIfRunning();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        parent.addView(row);
    }

    private interface OnInt {
        void on(int v);
    }

    // ---------------- 逻辑 ----------------

    private void updateStatus() {
        if (statusText == null) return;
        boolean on = isServiceEnabled();
        statusText.setText(on ? "状态：已开启，横条应该已经出现在屏幕底部"
                : "状态：未开启　—　需要在无障碍里打开本服务");
        statusText.setTextColor(on ? 0xFF11833B : 0xFFB3261E);
    }

    private boolean isServiceEnabled() {
        try {
            AccessibilityManager am =
                    (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                String id = info.getId();
                if (id != null && id.startsWith(getPackageName())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean setGestureLine(int v) {
        try {
            Settings.Global.putInt(getContentResolver(), "hide_gesture_line", v);
            return Settings.Global.getInt(getContentResolver(), "hide_gesture_line", 0) == v;
        } catch (Throwable t) {
            return false;
        }
    }

    private void showAdbDialog() {
        final String cmd = "adb shell pm grant com.dapiao.pillbar android.permission.WRITE_SECURE_SETTINGS\n"
                + "adb shell settings put global hide_gesture_line 1";
        TextView tv = new TextView(this);
        tv.setText("没拿到「修改安全设置」权限，用 ADB 给一次就好：\n\n"
                + cmd + "\n\n"
                + "执行完回到这里点「隐藏系统提示线」即可。\n"
                + "恢复用：adb shell settings put global hide_gesture_line 0\n"
                + "（小米15：设置 → 更多设置 → 开发者选项 → USB调试 / 无线调试）");
        tv.setTextSize(13);
        tv.setTextColor(TEXT);
        tv.setPadding(dp(16), dp(12), dp(16), dp(8));
        tv.setTextIsSelectable(true);

        new AlertDialog.Builder(this)
                .setTitle("ADB 授权（一次性）")
                .setView(tv)
                .setPositiveButton("复制命令", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        ClipboardManager cm =
                                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("adb", cmd));
                        Toast.makeText(MainActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }
}
