package com.dapiao.pillbar;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PillService extends AccessibilityService {

    private static PillService sInstance;

    private static final int TOUCH_PAD_DP = 12;
    private static final int MENU_TIMEOUT_MS = 4500;

    private WindowManager wm;
    private PillBarView bar;
    private WindowManager.LayoutParams lp;
    private boolean attached;

    private View menu;
    private final Runnable menuTimeout = new Runnable() {
        @Override
        public void run() {
            hideMenu();
        }
    };

    private int imeHeightPx;
    private int sampledColor;
    /** 当前前台包名（判定时顺带更新），用于按包缓存取色 */
    private String fgPkg;
    /** 每个 App 上次取到的横条颜色：进 App 时直接用，避免先显示默认色再跳变 */
    private final Map<String, Integer> pkgColorCache = new HashMap<>();
    /** 自有横条模式下条底边距屏幕底部的像素，取色带贴着它上方 */
    private int lastBarBottomPx;

    /** 桌面判定：桌面上整体隐藏横条，系统手势零干扰 */
    private boolean onLauncher;
    private long lastLauncherCheckAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sampler = Executors.newSingleThreadExecutor();
    private long lastSampleAt;
    private int sampleFails;

    private final Runnable sampleTask = new Runnable() {
        @Override
        public void run() {
            doSample();
        }
    };

    public static void refreshIfRunning() {
        final PillService s = sInstance;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                if (sInstance != null) sInstance.applyPrefs();
            }
        });
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        applyPrefs();
        main.postDelayed(sampleTask, 900);
        // 判定服务启动时是否已在桌面
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                detectLauncherState();
            }
        }, 800);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) hideMenu();
            // 即时挂条：事件报的是可启动的 App 且不是桌面时立刻挂，
            // 不等转场动画放完才被检测到。误挂由快速复查 + 后续窗口检测兜底。
            CharSequence ep = event.getPackageName();
            if (ep != null && onLauncher) {
                String p = ep.toString();
                if (!getPackageName().equals(p) && !isHomePkg(p) && isLaunchable(p)) {
                    // 立即写入前台包名：挂条那一刻就能用上该 App 缓存的颜色，
                    // 避免「先出默认色、采样回来再变色」的跳变
                    fgPkg = p;
                    setLauncher(false);
                    // 尽快安排一次取色（缓存色已是正确色，采样用于刷新微调）
                    main.removeCallbacks(sampleTask);
                    main.postDelayed(sampleTask, 80);
                    // 立即快速复查：若实为桌面（迟到的旧事件）马上摘掉，避免闪动
                    main.removeCallbacks(recheckAfterAttach);
                    main.postDelayed(recheckAfterAttach, 100);
                }
            }
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // 判定前台是否桌面，只信「当前活动窗口」的真实查询结果，
            // 不再使用事件包名（迟到的事件会把状态翻回去造成横条常驻桌面）
            maybeDetectLauncher();
            updateIme();
            updateVisibility();
            if (Prefs.colorMode(this) == Prefs.COLOR_AUTO && sampleFails < 3) {
                main.removeCallbacks(sampleTask);
                main.postDelayed(sampleTask, 120);
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyPrefs();
                main.removeCallbacks(sampleTask);
                main.postDelayed(sampleTask, 500);
            }
        }, 300);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(sampleTask);
        hideMenu();
        detach();
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    // ---------------- 横条窗口 ----------------

    /** 挂条后自愈复查：若误挂（其实在桌面），短时间内摘掉 */
    private final Runnable recheckAfterAttach = new Runnable() {
        @Override
        public void run() {
            detectLauncherState();
        }
    };

    private void applyPrefs() {
        if (wm == null) return;
        // 桌面 / 键盘弹出时一律隐藏：窗口必须整个移除（仅 GONE 视图仍会挡触摸）。
        // 桌面不需要点按返回，键盘弹出时不挡输入，两者都是系统手势/输入优先。
        if (!Prefs.enabled(this) || onLauncher || imeHeightPx > 0) {
            detach();
            return;
        }
        if (!attached) attach();
        if (bar == null || lp == null) return;
        // 挂上后 300ms 主动复查一次桌面态，误挂能自愈
        main.removeCallbacks(recheckAfterAttach);
        main.postDelayed(recheckAfterAttach, 300);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int thickPx = dp(Prefs.thickDp(this));

        int y;
        if (Prefs.avoidGestureZone(this)) {
            int zone = systemGestureZonePx();
            y = Math.max(0, zone / 2 - thickPx / 2);
            // 用户手动把「距底部」调大了就尊重手动值
            int manual = (int) (Prefs.bottomDp(this) * dm.density);
            if (manual > y) y = manual;
        } else {
            y = (int) (Prefs.bottomDp(this) * dm.density);
        }
        // v1.14：条下方空间重新纳入触控（点按=返回，上滑=桌面，上滑停顿=多任务）。
        // 窗口从条底边一直铺到屏幕最底边；键盘弹出时整体隐藏（上方已处理）。
        int topPad = dp(TOUCH_PAD_DP);
        int belowPx = 0;
        if (Prefs.extendDown(this)) {
            belowPx = Math.max(0, y);
        }

        lp.width = (int) (Prefs.widthDp(this) * dm.density);
        lp.height = thickPx + topPad + belowPx;
        lp.y = y - belowPx;
        bar.setBarPos(thickPx, belowPx);
        // 条底边距屏幕底部的真实距离，取色带贴着它上方
        lastBarBottomPx = y;
        try {
            wm.updateViewLayout(bar, lp);
        } catch (Throwable ignored) {
        }
        bar.setDoubleTapEnabled(Prefs.doubleTapAction(this) != Prefs.DP_NONE);
        bar.setDoubleTapWindow(Prefs.doubleTapWindow(this));
        bar.setBarColor(currentColor());
        updateVisibility();
    }

    private void attach() {
        if (attached || wm == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int thickPx = dp(Prefs.thickDp(this));
        bar = new PillBarView(this, currentColor(), thickPx);
        bar.setCallbacks(new PillBarView.Callbacks() {
            @Override
            public void onTap() {
                int act = Prefs.tapAction(PillService.this);
                if (act == Prefs.ACT_HOME) {
                    performGlobalAction(GLOBAL_ACTION_HOME);
                } else if (act == Prefs.ACT_RECENTS) {
                    performGlobalAction(GLOBAL_ACTION_RECENTS);
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            }

            @Override
            public void onMenu() {
                runLongPressAction();
            }

            @Override
            public void onReplayStart(float[] xs, float[] ys, long[] ts) {
                startReplay(xs, ys, ts);
            }

            @Override
            public void onReplayMove(float x, float y, long t) {
                if (!replayOn) return;
                replayBuf.add(new float[]{x, y, t});
                // 平滑更新速度估计
                if (prevMoveT > 0) {
                    long dt = Math.max(1, t - prevMoveT);
                    float nvx = (x - prevMoveX) / dt;
                    float nvy = (y - prevMoveY) / dt;
                    replayVelX = 0.5f * replayVelX + 0.5f * nvx;
                    replayVelY = 0.5f * replayVelY + 0.5f * nvy;
                }
                prevMoveX = x;
                prevMoveY = y;
                prevMoveT = t;
                flushReplay(false);
            }

            @Override
            public void onReplayEnd(float x, float y, long t) {
                if (!replayOn) return;
                replayBuf.add(new float[]{x, y, t});
                // 快速滑动顺抛一段：帮系统稳定识别「回桌面/多任务」的 fling
                float sp = (float) Math.sqrt(replayVelX * replayVelX
                        + replayVelY * replayVelY);
                if (sp > 0.8f) {
                    float k = Math.min(50f, 160f / sp);
                    replayBuf.add(new float[]{x + replayVelX * k,
                            y + replayVelY * k, t + (long) k});
                }
                replayFinalQueued = true;
                flushReplay(false);
            }

            @Override
            public void onReplayCancel() {
                finishReplay();
            }

            @Override
            public void onDoubleTap() {
                runDoubleTapAction();
            }
        });

        lp = new WindowManager.LayoutParams(
                (int) (Prefs.widthDp(this) * dm.density),
                thickPx + dp(TOUCH_PAD_DP) + dp(Prefs.bottomDp(this)),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (Prefs.bottomDp(this) * dm.density);

        try {
            wm.addView(bar, lp);
            attached = true;
        } catch (Throwable t) {
            attached = false;
            bar = null;
        }
    }

    // ---------------- 滑动手势回放 ----------------
    // 窗口罩住手势区时，系统收不到从这片区域起始的滑动手势。
    // 解决：检测到滑动后把窗口临时设为不可触摸，用 dispatchGesture 把
    // 手指轨迹实时回放给系统，由系统原生处理（桌面/多任务/切应用）。

    private boolean replayOn;
    private boolean replayInFlight;
    private boolean replayFinalQueued;
    private boolean replayFinalSent;
    private GestureDescription.StrokeDescription replayStroke;
    private float replayLastX, replayLastY;
    private float replayVelX, replayVelY;
    private float prevMoveX, prevMoveY;
    private long prevMoveT;
    private final List<float[]> replayBuf = new ArrayList<>();

    private final Runnable replaySafety = new Runnable() {
        @Override
        public void run() {
            finishReplay();
        }
    };

    private final AccessibilityService.GestureResultCallback replayCb =
            new AccessibilityService.GestureResultCallback() {
        @Override
        public void onCompleted(GestureDescription gestureDescription) {
            replayInFlight = false;
            if (replayFinalSent) {
                finishReplay();
            } else {
                flushReplay(false);
            }
        }

        @Override
        public void onCancelled(GestureDescription gestureDescription) {
            replayInFlight = false;
            finishReplay();
        }
    };

    private void setBarTouchable(boolean touchable) {
        if (lp == null || wm == null || bar == null) return;
        int flags = lp.flags;
        if (touchable) {
            flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (flags != lp.flags) {
            lp.flags = flags;
            try {
                wm.updateViewLayout(bar, lp);
            } catch (Throwable ignored) {
            }
        }
    }

    private void startReplay(float[] xs, float[] ys, long[] ts) {
        if (Build.VERSION.SDK_INT < 26 || xs.length < 2) return;
        if (replayOn) return; // 防重入：注入事件若误入本窗口，绝不叠加新一轮
        main.removeCallbacks(replaySafety);
        main.postDelayed(replaySafety, 4000);
        replayOn = true;
        replayFinalQueued = false;
        replayFinalSent = false;
        replayStroke = null;
        replayBuf.clear();
        replayLastX = xs[xs.length - 1];
        replayLastY = ys[ys.length - 1];
        // 初始速度估计（px/ms），用于抬手顺抛
        if (ts.length >= 2) {
            long dt = Math.max(1, ts[ts.length - 1] - ts[ts.length - 2]);
            replayVelX = (xs[xs.length - 1] - xs[xs.length - 2]) / dt;
            replayVelY = (ys[ys.length - 1] - ys[ys.length - 2]) / dt;
        } else {
            replayVelX = 0;
            replayVelY = 0;
        }
        prevMoveX = replayLastX;
        prevMoveY = replayLastY;
        prevMoveT = ts[ts.length - 1];
        setBarTouchable(false);
        // 延迟 40ms 注入第一段：等窗口「不可触摸」真正生效，
        // 否则注入事件会被自己的窗口吃掉并引发重放竞争（50% 失败的根源）
        final float[] fx = xs;
        final float[] fy = ys;
        final long[] ft = ts;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                dispatchReplayStart(fx, fy, ft);
            }
        }, 40);
    }

    private void dispatchReplayStart(float[] xs, float[] ys, long[] ts) {
        if (!replayOn) return;
        Path p = new Path();
        p.moveTo(xs[0], ys[0]);
        for (int i = 1; i < xs.length; i++) p.lineTo(xs[i], ys[i]);
        long dur = Math.max(12, ts[ts.length - 1] - ts[0]);
        replayStroke = new GestureDescription.StrokeDescription(p, 0, dur, true);
        replayInFlight = dispatchGesture(
                new GestureDescription.Builder().addStroke(replayStroke).build(),
                replayCb, null);
        if (!replayInFlight) finishReplay();
    }

    /** 把缓冲里的轨迹点接续到上一段后面回放出去 */
    private void flushReplay(boolean forceEnd) {
        if (!replayOn || replayInFlight || replayStroke == null) return;
        if (!forceEnd && !replayFinalQueued && replayBuf.isEmpty()) return;
        if (replayBuf.isEmpty() && replayFinalQueued) {
            // 结束时没有新点：补一段原地小位移收尾
            replayBuf.add(new float[]{replayLastX, replayLastY, SystemClock.uptimeMillis()});
        }
        if (replayBuf.isEmpty()) return;

        float[] first = replayBuf.get(0);
        float[] last = replayBuf.get(replayBuf.size() - 1);
        Path p = new Path();
        p.moveTo(replayLastX, replayLastY);
        for (float[] pt : replayBuf) p.lineTo(pt[0], pt[1]);
        replayLastX = last[0];
        replayLastY = last[1];
        long dur = Math.max(8, (long) (last[2] - first[2]));

        boolean willContinue = !replayFinalQueued;
        GestureDescription.StrokeDescription next =
                replayStroke.continueStroke(p, 0, dur, willContinue);
        replayBuf.clear();
        if (!willContinue) replayFinalSent = true;
        replayInFlight = dispatchGesture(
                new GestureDescription.Builder().addStroke(next).build(), replayCb, null);
        replayStroke = next;
        if (!replayInFlight) finishReplay();
    }

    private void finishReplay() {
        main.removeCallbacks(replaySafety);
        replayOn = false;
        replayInFlight = false;
        replayFinalQueued = false;
        replayFinalSent = false;
        replayBuf.clear();
        setBarTouchable(true);
    }

    private void detach() {
        if (!attached || bar == null || wm == null) {
            attached = false;
            bar = null;
            return;
        }
        try {
            wm.removeView(bar);
        } catch (Throwable ignored) {
        }
        attached = false;
        bar = null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 系统手势区（官方小白条所在区域）的高度，手势导航下就是 navigation_bar_height */
    private int systemGestureZonePx() {
        try {
            int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) {
                int v = getResources().getDimensionPixelSize(id);
                if (v > 0 && v < dp(120)) return v;
            }
        } catch (Throwable ignored) {
        }
        return dp(28);
    }

    private boolean isNight() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // ---------------- 桌面判定 ----------------

    /** 全局解析「默认桌面」包名（缓存）。只认它，避免 FallbackHome 这类
     *  负优先级 HOME 占位 Activity（MIUI 设置包里有）把设置等包误判成桌面 */
    private String defaultHomePkg;

    /** 可启动 App 缓存（getLaunchIntentForPackage 结果），供即时挂条用 */
    private final Map<String, Boolean> launchableCache = new HashMap<>();

    private boolean isLaunchable(String pkg) {
        if (pkg == null) return false;
        Boolean c = launchableCache.get(pkg);
        if (c != null) return c;
        boolean v = false;
        try {
            v = getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Throwable ignored) {
        }
        if (launchableCache.size() > 64) launchableCache.clear();
        launchableCache.put(pkg, v);
        return v;
    }

    private String defaultHomePkg() {
        if (defaultHomePkg != null) return defaultHomePkg;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(i, 0);
            ResolveInfo best = null;
            for (ResolveInfo ri : list) {
                if (ri == null || ri.activityInfo == null) continue;
                if (ri.priority < 0) continue; // 排除 FallbackHome（priority=-1000）
                if (best == null || ri.priority > best.priority) best = ri;
            }
            if (best != null) defaultHomePkg = best.activityInfo.packageName;
        } catch (Throwable ignored) {
        }
        return defaultHomePkg;
    }

    /** 该包是否就是默认桌面 */
    private boolean isHomePkg(String pkg) {
        if (pkg == null) return false;
        String home = defaultHomePkg();
        return home != null && home.equals(pkg);
    }

    private void setLauncher(boolean now) {
        if (now == onLauncher) return;
        onLauncher = now;
        applyPrefs();
    }

    /** 用当前活动窗口的包名判定是否在桌面（节流 120ms，转场动画期间事件频繁） */
    private void maybeDetectLauncher() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLauncherCheckAt < 120) return;
        lastLauncherCheckAt = now;
        detectLauncherState();
    }

    private void detectLauncherState() {
        String found = null;
        try {
            // 遍历窗口列表找「前台应用窗口」：跳过自己的悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）
            // 和输入法。悬浮条挂着的时点，getRootInActiveWindow 可能返回我们自己的窗口，
            // 造成「在桌面也判定为非桌面」的自锁，所以不能依赖它。
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                String fallback = null;
                StringBuilder dbg = new StringBuilder("wins=");
                for (AccessibilityWindowInfo wi : wins) {
                    if (wi == null) continue;
                    int t = wi.getType();
                    AccessibilityNodeInfo root = wi.getRoot();
                    String pkg = (root != null && root.getPackageName() != null)
                            ? root.getPackageName().toString() : "?";
                    boolean foc = wi.isFocused();
                    boolean active = wi.isActive();
                    dbg.append('[').append(t).append(' ').append(pkg)
                            .append(foc ? " F" : "").append(active ? " A" : "").append("] ");
                    if (t != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    if (getPackageName().equals(pkg)) continue;
                    if (foc) {
                        fallback = pkg;
                        break;
                    }
                    if (fallback == null) fallback = pkg;
                }
                Log.d("PillBar", dbg.toString());
                found = fallback;
            }
            if (found == null) {
                AccessibilityNodeInfo n = getRootInActiveWindow();
                if (n != null && n.getPackageName() != null
                        && !getPackageName().equals(n.getPackageName().toString())) {
                    found = n.getPackageName().toString();
                }
            }
        } catch (Throwable ignored) {
        }
        boolean now = isHomePkg(found);
        if (found != null && !found.equals(fgPkg)) {
            fgPkg = found;
            // 前台变了：有缓存色就立刻刷上去，并安排一次取色刷新
            final Integer cached = pkgColorCache.get(fgPkg);
            if (cached != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bar != null) bar.setBarColor(cached);
                    }
                });
            }
            main.removeCallbacks(sampleTask);
            main.postDelayed(sampleTask, 80);
        }
        Log.d("PillBar", "detect pkg=" + found + " onLauncher=" + now);
        setLauncher(now);
    }

    private int currentColor() {
        int op = Prefs.opacity(this);
        int a = (int) (255 * Math.max(20, Math.min(100, op)) / 100f);
        switch (Prefs.colorMode(this)) {
            case Prefs.COLOR_LIGHT:
                return Color.argb(a, 255, 255, 255);
            case Prefs.COLOR_DARK:
                return Color.argb((int) (a * 0.6f), 0, 0, 0);
            case Prefs.COLOR_SYSTEM:
                return isNight() ? Color.argb(a, 255, 255, 255) : Color.argb((int) (a * 0.5f), 0, 0, 0);
            default:
                Integer cached = pkgColorCache.get(fgPkg);
                if (cached != null) return cached;
                if (sampledColor != 0) return sampledColor;
                return isNight() ? Color.argb(a, 255, 255, 255) : Color.argb((int) (a * 0.5f), 0, 0, 0);
        }
    }

    private void updateVisibility() {
        boolean locked = false;
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            locked = km != null && km.isKeyguardLocked();
        } catch (Throwable ignored) {
        }
        int v = (locked || !Prefs.enabled(this)) ? View.GONE : View.VISIBLE;
        if (bar != null && bar.getVisibility() != v) bar.setVisibility(v);
    }

    private void updateIme() {
        int prev = imeHeightPx;
        imeHeightPx = 0;
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                int screenH = getResources().getDisplayMetrics().heightPixels;
                for (AccessibilityWindowInfo wi : wins) {
                    if (wi == null) continue;
                    if (wi.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        Rect r = new Rect();
                        wi.getBoundsInScreen(r);
                        if (r.height() > 0 && r.height() < screenH) imeHeightPx = r.height();
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (prev != imeHeightPx) applyPrefs();
    }

    // ---------------- 双击动作 ----------------

    private void runDoubleTapAction() {
        switch (Prefs.doubleTapAction(this)) {
            case Prefs.DP_LOCK:
                lockScreen();
                break;
            case Prefs.DP_XIAOAI:
                openXiaoai();
                break;
            case Prefs.DP_PAY_ALI:
                openPay("com.eg.android.AlipayGphone",
                        "alipayqr://platformapi/startapp?saId=20000056");
                break;
            case Prefs.DP_PAY_WECHAT:
                openPay("com.tencent.mm", "weixin://dl/scan");
                break;
            default:
                break;
        }
    }

    // ---------------- 长按动作 ----------------

    /** 长按直接执行设置里选定的动作，不做中间确认 */
    private void runLongPressAction() {
        switch (Prefs.longPressAction(this)) {
            case Prefs.LP_XIAOAI:
                openXiaoai();
                break;
            case Prefs.LP_LOCK:
                lockScreen();
                break;
            case Prefs.LP_RECENTS:
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case Prefs.LP_PAY_ALI:
                openPay("com.eg.android.AlipayGphone",
                        "alipayqr://platformapi/startapp?saId=20000056");
                break;
            case Prefs.LP_PAY_WECHAT:
                openPay("com.tencent.mm", "weixin://dl/scan");
                break;
            case Prefs.LP_NONE:
                break;
            default:
                showMenu(false);
                break;
        }
    }

    private interface Action {
        void run();
    }

    private void showMenu(boolean payLevel) {
        hideMenu();
        if (wm == null || lp == null) return;

        LinearLayout card = new LinearLayout(this) {
            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    hideMenu();
                    return true;
                }
                return super.onTouchEvent(e);
            }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21C1F24);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));

        if (payLevel) {
            addItem(card, "支付宝付款码", new Action() {
                @Override
                public void run() {
                    openPay("com.eg.android.AlipayGphone",
                            "alipayqr://platformapi/startapp?saId=20000056");
                }
            });
            addItem(card, "微信扫一扫", new Action() {
                @Override
                public void run() {
                    openPay("com.tencent.mm", "weixin://dl/scan");
                }
            });
        } else {
            addItem(card, "多任务", new Action() {
                @Override
                public void run() {
                    performGlobalAction(GLOBAL_ACTION_RECENTS);
                }
            });
            addItem(card, "锁屏", new Action() {
                @Override
                public void run() {
                    lockScreen();
                }
            });
            addItem(card, "小爱", new Action() {
                @Override
                public void run() {
                    openXiaoai();
                }
            });
            addItem(card, "支付", new Action() {
                @Override
                public void run() {
                    showMenu(true);
                }
            });
        }

        WindowManager.LayoutParams mp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        mp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mp.y = lp.y + lp.height + dp(10);

        try {
            wm.addView(card, mp);
            menu = card;
            main.removeCallbacks(menuTimeout);
            main.postDelayed(menuTimeout, MENU_TIMEOUT_MS);
        } catch (Throwable ignored) {
            menu = null;
        }
    }

    private void addItem(LinearLayout parent, String text, final Action action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(0xFFFFFFFF);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideMenu();
                try {
                    action.run();
                } catch (Throwable ignored) {
                }
            }
        });
        parent.addView(t);
    }

    private void hideMenu() {
        main.removeCallbacks(menuTimeout);
        if (menu != null && wm != null) {
            try {
                wm.removeView(menu);
            } catch (Throwable ignored) {
            }
        }
        menu = null;
    }

    // ---------------- 动作 ----------------

    private void lockScreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
    }

    private void openXiaoai() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName("com.miui.voiceassist",
                    "com.xiaomi.voiceassistant.TabHostMainActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_VOICE_COMMAND);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        openApp("com.miui.voiceassist");
    }

    private void openPay(String pkg, String uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        openApp(pkg);
    }

    private void openApp(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 取色 ----------------

    private void doSample() {
        if (Build.VERSION.SDK_INT < 30 || !Prefs.enabled(this)) return;
        if (Prefs.colorMode(this) != Prefs.COLOR_AUTO) return;
        long now = System.currentTimeMillis();
        if (now - lastSampleAt < 300) return;
        lastSampleAt = now;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        // 取色带贴着横条上沿，用对比色
        int bottomPx = lastBarBottomPx > 0 ? lastBarBottomPx
                : (lp != null ? lp.y : (int) (Prefs.bottomDp(this) * dm.density));
        int thickPx = (int) (Prefs.thickDp(this) * dm.density);

        ColorSampler.sample(this, sampler, bottomPx, thickPx, dm.density, Prefs.opacity(this),
                new ColorSampler.Callback() {
                    @Override
                    public void onColor(final int pillColor) {
                        sampleFails = 0;
                        sampledColor = pillColor;
                        if (pkgColorCache.size() > 32) pkgColorCache.clear();
                        pkgColorCache.put(fgPkg, pillColor);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (bar != null) bar.setBarColor(pillColor);
                            }
                        });
                    }

                    @Override
                    public void onFail() {
                        sampleFails++;
                    }
                });
    }
}
