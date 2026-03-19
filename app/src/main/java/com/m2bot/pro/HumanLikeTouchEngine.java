package com.m2bot.pro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Random;

/**
 * Dispatches taps and swipes through the {@link AccessibilityService} gesture
 * API, adding human-like jitter, micro-pauses and Bezier-curve motion so the
 * input stream does not look machine-generated.
 * <p>
 * <b>Key improvements over the original:</b>
 * <ul>
 *   <li>All coordinate values are clamped to >= 1 before being sent to the
 *       gesture builder (Android rejects 0-coordinates).</li>
 *   <li>Duration is always >= 1 ms to avoid {@code IllegalArgumentException}.</li>
 *   <li>{@link #rapidTap} posts to the main-thread handler so it works from
 *       any calling thread.</li>
 * </ul>
 */
public final class HumanLikeTouchEngine {

    private static final String TAG = Constants.APP_TAG + ".Touch";

    private final AccessibilityService service;
    private final Handler handler;
    private final Random rng;

    /* ---- Metrics ---- */
    private int successCount;
    private int failCount;
    private long lastTapTime;

    /* ================================================================== */
    /*  Construction                                                      */
    /* ================================================================== */

    public HumanLikeTouchEngine(AccessibilityService svc) {
        this.service = svc;
        this.handler = new Handler(Looper.getMainLooper());
        this.rng     = new Random();
    }

    public String getStats() {
        return "OK:" + successCount + " FAIL:" + failCount;
    }

    /* ================================================================== */
    /*  Single tap                                                        */
    /* ================================================================== */

    public void tap(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        if (x <= 0 || y <= 0) return;

        enforceMinInterval();

        int rx = jitter(x, Constants.TOUCH_JITTER_PX);
        int ry = jitter(y, Constants.TOUCH_JITTER_PX);
        int duration = Constants.TOUCH_DURATION_BASE_MS
                + rng.nextInt(Constants.TOUCH_DURATION_RANGE_MS);

        try {
            Path path = new Path();
            float sx = rx + (rng.nextFloat() - 0.5f) * 2;
            float sy = ry + (rng.nextFloat() - 0.5f) * 2;
            path.moveTo(Math.max(1, sx), Math.max(1, sy));

            // Occasional micro-drag to mimic finger slide
            if (rng.nextInt(100) < 30) {
                path.lineTo(
                        Math.max(1, rx + (rng.nextFloat() - 0.5f) * 3),
                        Math.max(1, ry + (rng.nextFloat() - 0.5f) * 3));
            }

            GestureDescription.Builder builder = new GestureDescription.Builder();
            long startDelay = rng.nextInt(8);
            builder.addStroke(new GestureDescription.StrokeDescription(
                    path, startDelay, Math.max(1, duration)));

            boolean dispatched = service.dispatchGesture(
                    builder.build(), gestureCallback, null);
            if (!dispatched) failCount++;

            lastTapTime = System.currentTimeMillis();
        } catch (Exception e) {
            failCount++;
            Log.e(TAG, "tap", e);
        }
    }

    /** Tap with a custom spatial variance. */
    public void tapWithVariance(int x, int y, int variance) {
        tap(jitter(x, variance), jitter(y, variance));
    }

    /* ================================================================== */
    /*  Rapid (burst) tap                                                 */
    /* ================================================================== */

    /**
     * Posts {@code count} taps spaced roughly {@code intervalMs} apart.
     * Each tap has a small random time and positional offset.
     */
    public void rapidTap(final int x, final int y, int count, int intervalMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        for (int i = 0; i < count; i++) {
            final int idx = i;
            long delay = (long) i * intervalMs
                    + rng.nextInt(Math.max(1, intervalMs / 4));
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    int dx = idx > 0 ? rng.nextInt(20) - 10 : 0;
                    int dy = rng.nextInt(8) - 4;
                    tap(x + dx, y + dy);
                }
            }, delay);
        }
    }

    /* ================================================================== */
    /*  Swipe (Bezier curve)                                              */
    /* ================================================================== */

    /**
     * Dispatches a swipe along a cubic Bezier curve with randomised control
     * points so the trajectory does not look perfectly linear.
     */
    public void humanSwipe(int x1, int y1, int x2, int y2, int durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        x1 = Math.max(1, jitter(x1, 4));
        y1 = Math.max(1, jitter(y1, 4));
        x2 = Math.max(1, jitter(x2, 4));
        y2 = Math.max(1, jitter(y2, 4));

        try {
            Path path = new Path();
            path.moveTo(x1, y1);

            float cx1 = x1 + (x2 - x1) * 0.25f + (rng.nextFloat() - 0.5f) * 30;
            float cy1 = y1 + (y2 - y1) * 0.25f + (rng.nextFloat() - 0.5f) * 30;
            float cx2 = x1 + (x2 - x1) * 0.75f + (rng.nextFloat() - 0.5f) * 30;
            float cy2 = y1 + (y2 - y1) * 0.75f + (rng.nextFloat() - 0.5f) * 30;
            path.cubicTo(cx1, cy1, cx2, cy2, x2, y2);

            int dur = durationMs + rng.nextInt(Math.max(1, durationMs / 3))
                    - durationMs / 6;

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(
                    path, rng.nextInt(5), Math.max(50, dur)));

            boolean ok = service.dispatchGesture(builder.build(), gestureCallback, null);
            if (!ok) Log.w(TAG, "swipe dispatch returned false");
        } catch (Exception e) {
            Log.e(TAG, "humanSwipe", e);
        }
    }

    /* ================================================================== */
    /*  Joystick helpers                                                  */
    /* ================================================================== */

    /** Moves the joystick in a random direction. */
    public void joystickMove(int centerX, int centerY, int radius) {
        double angle = rng.nextDouble() * 2 * Math.PI;
        int dist = radius / 3 + rng.nextInt(Math.max(1, radius * 2 / 3));
        int tx = Math.max(1, centerX + (int) (Math.cos(angle) * dist));
        int ty = Math.max(1, centerY + (int) (Math.sin(angle) * dist));
        humanSwipe(centerX, centerY, tx, ty, 500 + rng.nextInt(600));
    }

    /** Moves the joystick towards a specific angle (degrees) with jitter. */
    public void joystickMoveAngle(int centerX, int centerY, int radius, double angleDeg) {
        double angleRad = Math.toRadians(angleDeg + rng.nextInt(21) - 10);
        int dist = radius / 2 + rng.nextInt(Math.max(1, radius / 2));
        int tx = Math.max(1, centerX + (int) (Math.cos(angleRad) * dist));
        int ty = Math.max(1, centerY + (int) (Math.sin(angleRad) * dist));
        humanSwipe(centerX, centerY, tx, ty, 500 + rng.nextInt(500));
    }

    /* ================================================================== */
    /*  Long press                                                        */
    /* ================================================================== */

    public void longPress(int x, int y, int durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            Path path = new Path();
            path.moveTo(Math.max(1, jitter(x, 3)), Math.max(1, jitter(y, 3)));
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(
                    path, 0, Math.max(1, durationMs + rng.nextInt(200))));
            service.dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            Log.e(TAG, "longPress", e);
        }
    }

    /* ================================================================== */
    /*  Random human-like pause                                           */
    /* ================================================================== */

    /** Occasionally sleeps for a short random duration (8 % chance). */
    public void humanPause() {
        if (rng.nextInt(100) < 8) {
            int pauseMs = 300 + rng.nextInt(2000);
            try { Thread.sleep(pauseMs); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /* ================================================================== */
    /*  Internal helpers                                                  */
    /* ================================================================== */

    /** Adds random jitter within [-range, +range] while keeping result >= 1. */
    private int jitter(int val, int range) {
        return Math.max(1, val + rng.nextInt(range * 2 + 1) - range);
    }

    /** Sleeps if the time since the last tap is below the minimum interval. */
    private void enforceMinInterval() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastTapTime;
        if (elapsed < Constants.TOUCH_MIN_INTERVAL_MS) {
            try {
                Thread.sleep(Constants.TOUCH_MIN_INTERVAL_MS - elapsed + rng.nextInt(15));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Shared callback that simply increments success/fail counters. */
    private final AccessibilityService.GestureResultCallback gestureCallback =
            new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    successCount++;
                }
                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    failCount++;
                }
            };
}
