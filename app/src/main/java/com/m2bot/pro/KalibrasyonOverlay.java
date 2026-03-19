package com.m2bot.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen transparent overlay that guides the user through a multi-step
 * calibration process.  Each step asks the user to tap a specific UI element
 * in the game; the raw screen coordinates are recorded in SharedPreferences
 * so that {@link CoordManager} picks them up on its next construction.
 */
public final class KalibrasyonOverlay {

    /* ---- Calibration step metadata ---- */
    private static final int[] INSTRUCTION_RES = {
            R.string.cal_step_attack,
            R.string.cal_step_hp_pot,
            R.string.cal_step_mp_pot,
            R.string.cal_step_joystick,
            R.string.cal_step_pickup,
            R.string.cal_step_skill1,
            R.string.cal_step_skill2,
            R.string.cal_step_hp_bar_left,
            R.string.cal_step_hp_bar_right,
            R.string.cal_step_mob_name,
    };

    private static final String[][] PREF_KEYS = {
            {"atkX",    "atkY"},
            {"potHpX",  "potHpY"},
            {"potMpX",  "potMpY"},
            {"joyCX",   "joyCY"},
            {"pickupX", "pickupY"},
            {"skill1X", "skill1Y"},
            {"skill2X", "skill2Y"},
            {"hpBarX1", "hpBarY1"},
            {"hpBarX2", "hpBarY2"},
            {"mobSampleX", "mobSampleY"},
    };

    private static final int STEP_JOYSTICK      = 3;
    private static final int STEP_HP_BAR_RIGHT  = 8;
    private static final int STEP_MOB_NAME      = 9;
    private static final int TOTAL_STEPS        = INSTRUCTION_RES.length;

    /* ---- UI references ---- */
    private final WindowManager wm;
    private final Context ctx;
    private final CalibrationDone callback;
    private final SharedPreferences sp;

    private View touchLayer;
    private View infoPanel;
    private TextView instructionTv;
    private TextView progressTv;
    private int step;

    /* ================================================================== */
    /*  Callback interface                                                */
    /* ================================================================== */

    public interface CalibrationDone {
        void onDone();
    }

    /* ================================================================== */
    /*  Construction                                                      */
    /* ================================================================== */

    public KalibrasyonOverlay(Context ctx, WindowManager wm, CalibrationDone cb) {
        this.ctx      = ctx;
        this.wm       = wm;
        this.callback = cb;
        this.sp       = ctx.getSharedPreferences(Constants.PREFS_COORDS, Context.MODE_PRIVATE);
    }

    /* ================================================================== */
    /*  Public API                                                        */
    /* ================================================================== */

    public void start() {
        step = 0;
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // --- Full-screen touch receiver ---
        View area = new View(ctx);
        area.setBackgroundColor(Color.argb(60, 0, 0, 0));
        area.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    savePoint((int) e.getRawX(), (int) e.getRawY());
                    return true;
                }
                return false;
            }
        });
        touchLayer = area;

        WindowManager.LayoutParams tp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT);
        tp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(touchLayer, tp);

        // --- Info panel ---
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(15), dp(10), dp(15), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.argb(220, 10, 10, 30));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(2), Color.rgb(0, 230, 118));
        panel.setBackground(bg);

        TextView title = new TextView(ctx);
        title.setText("CALIBRATION");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(Color.rgb(0, 230, 118));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);

        instructionTv = new TextView(ctx);
        instructionTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        instructionTv.setTextColor(Color.WHITE);
        instructionTv.setGravity(Gravity.CENTER);
        instructionTv.setPadding(0, dp(8), 0, dp(4));
        panel.addView(instructionTv);

        progressTv = new TextView(ctx);
        progressTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        progressTv.setTextColor(Color.rgb(255, 234, 0));
        progressTv.setGravity(Gravity.CENTER);
        panel.addView(progressTv);

        // Skip button
        TextView skipBtn = new TextView(ctx);
        skipBtn.setText("SKIP");
        skipBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        skipBtn.setTextColor(Color.rgb(255, 152, 0));
        skipBtn.setGravity(Gravity.CENTER);
        skipBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        skipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step++;
                if (step >= TOTAL_STEPS) finish();
                else updateUI();
            }
        });
        panel.addView(skipBtn);

        // Cancel button
        TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText("CANCEL");
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        cancelBtn.setTextColor(Color.rgb(255, 23, 68));
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { remove(); }
        });
        panel.addView(cancelBtn);

        infoPanel = panel;

        WindowManager.LayoutParams ip = new WindowManager.LayoutParams(
                dp(260),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        ip.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        ip.y = dp(20);
        wm.addView(infoPanel, ip);

        updateUI();
    }

    public void remove() {
        safeRemoveView(touchLayer);
        safeRemoveView(infoPanel);
    }

    /* ================================================================== */
    /*  Internal                                                          */
    /* ================================================================== */

    private void savePoint(int x, int y) {
        if (step >= PREF_KEYS.length) return;

        SharedPreferences.Editor e = sp.edit();
        e.putInt(PREF_KEYS[step][0], x);
        e.putInt(PREF_KEYS[step][1], y);

        // Store a default joystick radius when the centre is calibrated
        if (step == STEP_JOYSTICK) {
            e.putInt("joyR", dp(60));
        }
        e.apply();

        // If user tapped on a mob name, sample colours for the learner
        if (step == STEP_MOB_NAME) {
            learnMobColorAt(x, y);
        }

        step++;
        if (step >= TOTAL_STEPS) finish();
        else updateUI();
    }

    /**
     * Samples a small region around the calibration tap to seed the
     * {@link AdaptiveColorLearner} with mob-name colours.
     */
    private void learnMobColorAt(int screenX, int screenY) {
        ScreenCaptureService cap = ScreenCaptureService.getInstance();
        if (cap == null || !cap.isActive()) return;

        Bitmap bmp = cap.getLatestBitmap();
        if (bmp == null || bmp.isRecycled()) return;

        int scale = cap.getCaptureScale();
        int bx = screenX / scale;
        int by = screenY / scale;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        if (bx < 0 || by < 0 || bx >= bw || by >= bh) return;

        AdaptiveColorLearner learner = new AdaptiveColorLearner(ctx);
        for (int dx = -5; dx <= 5; dx += 2) {
            for (int dy = -3; dy <= 3; dy += 2) {
                int px = Math.max(0, Math.min(bx + dx, bw - 1));
                int py = Math.max(0, Math.min(by + dy, bh - 1));
                learner.learnMobNameColor(bmp, px, py);
            }
        }
        learner.save();
    }

    private void updateUI() {
        if (instructionTv != null && step < INSTRUCTION_RES.length) {
            instructionTv.setText(INSTRUCTION_RES[step]);
        }
        if (progressTv != null) {
            progressTv.setText((step + 1) + "/" + TOTAL_STEPS);
        }
    }

    /**
     * Derives MP bar coordinates from the calibrated HP bar, then marks
     * calibration as complete.
     */
    private void finish() {
        int h1 = sp.getInt("hpBarY1", 0);
        int h2 = sp.getInt("hpBarY2", 0);
        int barHeight = Math.max(10, h2 - h1);

        sp.edit()
                .putInt("mpBarX1", sp.getInt("hpBarX1", 0))
                .putInt("mpBarY1", h2 + 5)
                .putInt("mpBarX2", sp.getInt("hpBarX2", 0))
                .putInt("mpBarY2", h2 + 5 + barHeight)
                .putInt("hpBarY2", h1 + barHeight)
                .putBoolean("kalibreEdildi", true)
                .apply();

        remove();
        if (callback != null) callback.onDone();
    }

    private void safeRemoveView(View v) {
        try {
            if (v != null && v.isAttachedToWindow()) wm.removeView(v);
        } catch (Exception ignored) { }
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
