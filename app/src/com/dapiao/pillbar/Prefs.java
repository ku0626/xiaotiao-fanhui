package com.dapiao.pillbar;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有设置集中在这里，读写 SharedPreferences */
public final class Prefs {

    private static final String FILE = "pill_prefs";

    public static final int COLOR_AUTO = 0;
    public static final int COLOR_LIGHT = 1;
    public static final int COLOR_DARK = 2;
    public static final int COLOR_SYSTEM = 3;

    public static final int ACT_BACK = 0;
    public static final int ACT_HOME = 1;
    public static final int ACT_RECENTS = 2;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }

    /** 小条宽度，单位 dp */
    public static int widthDp(Context c) {
        return sp(c).getInt("width", 108);
    }

    public static void setWidthDp(Context c, int v) {
        sp(c).edit().putInt("width", v).apply();
    }

    /** 小条粗细（高度），单位 dp */
    public static int thickDp(Context c) {
        return sp(c).getInt("thick", 5);
    }

    public static void setThickDp(Context c, int v) {
        sp(c).edit().putInt("thick", v).apply();
    }

    /** 距离屏幕底部的距离，单位 dp */
    public static int bottomDp(Context c) {
        return sp(c).getInt("bottom", 10);
    }

    public static void setBottomDp(Context c, int v) {
        sp(c).edit().putInt("bottom", v).apply();
    }

    /** 不透明度 20~100 */
    public static int opacity(Context c) {
        return sp(c).getInt("opacity", 72);
    }

    public static void setOpacity(Context c, int v) {
        sp(c).edit().putInt("opacity", v).apply();
    }

    public static int colorMode(Context c) {
        return sp(c).getInt("color_mode", COLOR_AUTO);
    }

    public static void setColorMode(Context c, int v) {
        sp(c).edit().putInt("color_mode", v).apply();
    }

    public static int tapAction(Context c) {
        return sp(c).getInt("tap_action", ACT_BACK);
    }

    public static void setTapAction(Context c, int v) {
        sp(c).edit().putInt("tap_action", v).apply();
    }

    /** 双击判定窗口毫秒数：启用双击后单击要等这么久确认不是双击 */
    public static int doubleTapWindow(Context c) {
        return sp(c).getInt("dt_window", 220);
    }

    public static void setDoubleTapWindow(Context c, int v) {
        sp(c).edit().putInt("dt_window", v).apply();
    }

    /** 让「小条返回」自己不出现在最近任务列表里 */
    public static boolean hideFromRecents(Context c) {
        return sp(c).getBoolean("hide_recents", true);
    }

    public static void setHideFromRecents(Context c, boolean v) {
        sp(c).edit().putBoolean("hide_recents", v).apply();
    }

    // ---- 长按动作 ----
    public static final int LP_MENU = 0;
    public static final int LP_XIAOAI = 1;
    public static final int LP_LOCK = 2;
    public static final int LP_RECENTS = 3;
    public static final int LP_PAY_ALI = 4;
    public static final int LP_PAY_WECHAT = 5;
    public static final int LP_NONE = 6;

    /** 长按横条执行的动作，默认呼出小爱（和官方小白条一致） */
    public static int longPressAction(Context c) {
        return sp(c).getInt("lp_action", LP_XIAOAI);
    }

    public static void setLongPressAction(Context c, int v) {
        sp(c).edit().putInt("lp_action", v).apply();
    }

    /** 把横条放进系统手势区（官方小白条所在区域），不遮挡 App 底部内容 */
    public static boolean avoidGestureZone(Context c) {
        return sp(c).getBoolean("avoid_zone", true);
    }

    public static void setAvoidGestureZone(Context c, boolean v) {
        sp(c).edit().putBoolean("avoid_zone", v).apply();
    }

    /** 条下方空间也纳入触控：点按=返回，上滑=桌面，上滑停顿=多任务（键盘弹出时自动收起） */
    public static boolean extendDown(Context c) {
        return sp(c).getBoolean("extend_down", true);
    }

    public static void setExtendDown(Context c, boolean v) {
        sp(c).edit().putBoolean("extend_down", v).apply();
    }

    /** 双击动作 */
    public static final int DP_NONE = 0;
    public static final int DP_LOCK = 1;
    public static final int DP_XIAOAI = 2;
    public static final int DP_PAY_ALI = 3;
    public static final int DP_PAY_WECHAT = 4;

    public static int doubleTapAction(Context c) {
        return sp(c).getInt("dp_action", DP_NONE);
    }

    public static void setDoubleTapAction(Context c, int v) {
        sp(c).edit().putInt("dp_action", v).apply();
    }

}
