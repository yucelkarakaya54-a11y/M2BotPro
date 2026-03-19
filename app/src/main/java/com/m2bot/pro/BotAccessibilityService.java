package com.m2bot.pro;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Accessibility service that serves as the gesture-dispatch bridge.
 * <p>
 * Android requires an {@link AccessibilityService} for programmatic gesture
 * dispatch.  This class holds a singleton reference so the rest of the app
 * can call {@link #tap}, {@link #swipe}, etc. without needing a direct
 * service binding.
 */
public class BotAccessibilityService extends AccessibilityService {

    private static final String TAG = Constants.APP_TAG + ".Accessibility";

    private static volatile BotAccessibilityService sInstance;

    private HumanLikeTouchEngine touchEngine;
    private Handler handler;

    /* ================================================================== */
    /*  Static accessors                                                  */
    /* ================================================================== */

    public static BotAccessibilityService get() { return sInstance; }

    public HumanLikeTouchEngine getTouchEngine() { return touchEngine; }

    /* ================================================================== */
    /*  Lifecycle                                                         */
    /* ================================================================== */

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        touchEngine = new HumanLikeTouchEngine(this);
        handler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Accessibility service connected");

        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(BotAccessibilityService.this,
                            R.string.toast_accessibility_active, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) { }
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used -- we only need the gesture-dispatch capability.
    }

    @Override
    public void onInterrupt() { /* required override */ }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    /* ================================================================== */
    /*  Convenience wrappers                                              */
    /* ================================================================== */

    public void tap(int x, int y) {
        if (touchEngine != null) touchEngine.tap(x, y);
    }

    public void rapidTap(int x, int y, int count, int interval) {
        if (touchEngine != null) touchEngine.rapidTap(x, y, count, interval);
    }

    public void swipe(int x1, int y1, int x2, int y2, int duration) {
        if (touchEngine != null) touchEngine.humanSwipe(x1, y1, x2, y2, duration);
    }

    public void moveRandom(int cx, int cy, int r) {
        if (touchEngine != null) touchEngine.joystickMove(cx, cy, r);
    }

    public void moveAngle(int cx, int cy, int r, double angle) {
        if (touchEngine != null) touchEngine.joystickMoveAngle(cx, cy, r, angle);
    }

    public String getStatusText() {
        return touchEngine != null ? touchEngine.getStats() : "N/A";
    }
}
