package com.dapiao.pillbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * 自绘的小横条。v1.15：
 * - 点按、长按：本地处理（点按=返回，长按=长按动作）；
 * - 滑动：不再吃掉，而是把手指轨迹上报给 Service，由 Service 通过
 *   dispatchGesture「回放」给系统，让系统原生处理官方手势
 *   （上滑=桌面、上滑停顿=多任务、上滑后左右滑=切应用）。
 *   这样窗口无论罩多大都不挡系统手势。
 */
public class PillBarView extends View {

    public interface Callbacks {
        void onTap();

        void onMenu();

        /** 检测到滑动（超过点按容差），带上已记录的轨迹点（屏幕坐标） */
        void onReplayStart(float[] xs, float[] ys, long[] ts);

        void onReplayMove(float x, float y, long t);

        void onReplayEnd(float x, float y, long t);

        void onReplayCancel();

        /** 双击（需在设置里选定双击动作后才启用） */
        void onDoubleTap();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Callbacks callbacks;
    private int thickPx;
    /** 条底边距窗口底部的像素（扩展触控区在条下方时 > 0） */
    private int bottomOffsetPx;

    private final float density;

    private float downX, downY;
    private long downAt;
    private boolean holdFired;
    private boolean replaying;

    private final ArrayList<Float> rxs = new ArrayList<>();
    private final ArrayList<Float> rys = new ArrayList<>();
    private final ArrayList<Long> rts = new ArrayList<>();

    private static final long HOLD_MS = 460;
    /** 超过这个位移就判定为滑动、转给系统回放 */
    private static final float REPLAY_SLOP_DP = 10f;
    /** 双击判定窗口：两次点按间隔小于该值算双击（可调，默认 220ms） */
    private long doubleMs = 220;

    private boolean doubleTapEnabled;
    private long lastTapAt;

    public void setDoubleTapWindow(long ms) {
        doubleMs = Math.max(120, ms);
    }

    private final Runnable pendingTapRunnable = new Runnable() {
        @Override
        public void run() {
            lastTapAt = 0;
            haptic();
            if (callbacks != null) callbacks.onTap();
        }
    };

    public void setDoubleTapEnabled(boolean enabled) {
        this.doubleTapEnabled = enabled;
        if (!enabled) {
            handler.removeCallbacks(pendingTapRunnable);
            lastTapAt = 0;
        }
    }

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!replaying) {
                holdFired = true;
                haptic();
                if (callbacks != null) callbacks.onMenu();
            }
        }
    };

    public PillBarView(Context context, int barColor, int thickPx) {
        super(context);
        this.thickPx = thickPx;
        density = getResources().getDisplayMetrics().density;
        paint.setColor(barColor);
        setClickable(true);
    }

    public void setThickPx(int px) {
        this.thickPx = px;
        invalidate();
    }

    /** bottomFromWindowBottom：条底边距窗口底部的像素，0 = 条贴窗口底 */
    public void setBarPos(int thickPx, int bottomFromWindowBottom) {
        this.thickPx = thickPx;
        this.bottomOffsetPx = Math.max(0, bottomFromWindowBottom);
        invalidate();
    }

    /** 条顶边在窗口内的 y 坐标 */
    private int barTopPx() {
        return getHeight() - bottomOffsetPx - thickPx;
    }

    public void setCallbacks(Callbacks c) {
        this.callbacks = c;
    }

    public void setBarColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    private void haptic() {
        try {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int h = getHeight();
        int t = Math.max(2, Math.min(thickPx, h));
        float bottom = h - bottomOffsetPx;
        float top = bottom - t;
        float r = t / 2f;
        canvas.drawRoundRect(0, top, getWidth(), bottom, r, r, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                downAt = SystemClock.uptimeMillis();
                holdFired = false;
                replaying = false;
                rxs.clear();
                rys.clear();
                rts.clear();
                rxs.add(downX);
                rys.add(downY);
                rts.add(downAt);
                handler.removeCallbacks(longPressRunnable);
                handler.postDelayed(longPressRunnable, HOLD_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float x = e.getRawX();
                float y = e.getRawY();
                long t = SystemClock.uptimeMillis();
                float dx = x - downX;
                float dy = y - downY;
                rxs.add(x);
                rys.add(y);
                rts.add(t);
                if (Math.abs(dx) > REPLAY_SLOP_DP * density
                        || Math.abs(dy) > REPLAY_SLOP_DP * density) {
                    handler.removeCallbacks(longPressRunnable);
                    if (!replaying) {
                        // 滑起来了 = 第二次手势不是点按，挂起的单击立刻放行
                        flushPendingSingle();
                        replaying = true;
                        if (callbacks != null) callbacks.onReplayStart(
                                toArray(rxs), toArray(rys), toLongArray(rts));
                    } else {
                        if (callbacks != null) callbacks.onReplayMove(x, y, t);
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPressRunnable);
                float x = e.getRawX();
                float y = e.getRawY();
                long t = SystemClock.uptimeMillis();
                if (replaying) {
                    replaying = false;
                    if (callbacks != null) {
                        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            callbacks.onReplayCancel();
                        } else {
                            callbacks.onReplayEnd(x, y, t);
                        }
                    }
                    return true;
                }
                if (holdFired || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    return true;
                }
                // 点按：位移小、时间短
                if (t - downAt < HOLD_MS
                        && Math.abs(x - downX) < 14f * density
                        && Math.abs(y - downY) < 14f * density) {
                    handleTap();
                }
                return true;
            }

            default:
                return super.onTouchEvent(e);
        }
    }

    private void handleTap() {
        long now = SystemClock.uptimeMillis();
        if (!doubleTapEnabled) {
            // 未启用双击：单击零延迟直接执行
            haptic();
            if (callbacks != null) callbacks.onTap();
            return;
        }
        if (now - lastTapAt < doubleMs) {
            // 双击
            handler.removeCallbacks(pendingTapRunnable);
            lastTapAt = 0;
            haptic();
            if (callbacks != null) callbacks.onDoubleTap();
            return;
        }
        // 第一次点按：挂起一个判定窗口，等第二次点按来判定双击
        lastTapAt = now;
        handler.removeCallbacks(pendingTapRunnable);
        handler.postDelayed(pendingTapRunnable, doubleMs);
    }

    /** 第二次手势已证明不是点按（滑起来了）时，立刻放行挂起的单击，不再干等窗口 */
    private void flushPendingSingle() {
        if (lastTapAt == 0) return;
        lastTapAt = 0;
        handler.removeCallbacks(pendingTapRunnable);
        pendingTapRunnable.run();
    }

    private static float[] toArray(ArrayList<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private static long[] toLongArray(ArrayList<Long> list) {
        long[] a = new long[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }
}
