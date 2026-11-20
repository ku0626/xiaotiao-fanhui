package com.dapiao.pillbar;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.HardwareBuffer;
import android.view.Display;

import java.util.concurrent.Executor;

/**
 * 用小条上方一小块画面取平均色，判断深浅，决定横条该用黑还是白。
 * 这样不管什么 App，横条颜色都和背景有对比，不会再出现白条贴在白底上的情况。
 */
class ColorSampler {

    interface Callback {
        /** pillColor 是给横条用的对比色 */
        void onColor(int pillColor);

        void onFail();
    }

    static void sample(final AccessibilityService service, Executor executor,
                       final int bottomPx, final int thickPx, final float density,
                       final int opacityPct, final Callback cb) {
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            HardwareBuffer buffer = null;
                            Bitmap soft = null;
                            try {
                                buffer = result.getHardwareBuffer();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer,
                                        android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
                                if (hw == null) {
                                    cb.onFail();
                                    return;
                                }
                                soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                                if (soft == null) {
                                    cb.onFail();
                                    return;
                                }
                                int pillColor = compute(soft, bottomPx, thickPx, density, opacityPct);
                                cb.onColor(pillColor);
                            } catch (Throwable t) {
                                cb.onFail();
                            } finally {
                                if (soft != null) soft.recycle();
                                if (buffer != null) buffer.close();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            cb.onFail();
                        }
                    });
        } catch (Throwable t) {
            cb.onFail();
        }
    }

    private static int compute(Bitmap bmp, int bottomPx, int thickPx, float density, int opacityPct) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int pad = (int) (4 * density);
        int stripH = (int) (26 * density);
        int y2 = h - bottomPx - thickPx - pad;
        if (y2 > h - 1) y2 = h - 1;
        int y1 = y2 - stripH;
        if (y1 < 0) y1 = 0;
        if (y2 <= y1) y2 = Math.min(h - 1, y1 + 1);

        int cx = w / 2;
        int halfW = (int) Math.min(w * 0.30f, 130 * density);
        int x1 = Math.max(0, cx - halfW);
        int x2 = Math.min(w - 1, cx + halfW);

        long sumR = 0, sumG = 0, sumB = 0, n = 0;
        int step = 6;
        for (int y = y1; y <= y2; y += step) {
            for (int x = x1; x <= x2; x += step) {
                int c = bmp.getPixel(x, y);
                sumR += Color.red(c);
                sumG += Color.green(c);
                sumB += Color.blue(c);
                n++;
            }
        }
        if (n == 0) return Color.argb(0xB0, 255, 255, 255);

        int r = (int) (sumR / n);
        int g = (int) (sumG / n);
        int b = (int) (sumB / n);
        double luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;

        float base = Math.max(0.2f, Math.min(1f, opacityPct / 100f));
        if (luma > 0.55) {
            // 浅色背景 → 黑条，透明度压一点，避免太抢眼
            return Color.argb((int) (255 * base * 0.45f), 0, 0, 0);
        }
        // 深色背景 → 白条
        return Color.argb((int) (255 * base), 255, 255, 255);
    }
}
