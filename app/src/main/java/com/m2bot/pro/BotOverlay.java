package com.m2bot.pro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Foreground service that hosts the floating overlay control panel.
 * <p>
 * The panel lets the user toggle bot features, adjust speed sliders,
 * calibrate coordinates, start / stop the bot, and monitor real-time stats.
 */
public class BotOverlay extends Service {

    private static volatile BotOverlay sInstance;

    private WindowManager wm;
    private Handler handler;
    private SharedPreferences prefs;

    /* ---- Core components ---- */
    private BotBrain             brain;
    private CoordManager         coords;
    private AdaptiveColorLearner colorLearner;
    private KalibrasyonOverlay   calibration;

    /* ---- Overlay views ---- */
    private View panelView, miniView;
    private WindowManager.LayoutParams panelParams, miniParams;
    private TextView statusTv, statsTv, logTv, startBtn;
    private boolean transparent;

    /* ---- Persisted settings (mirrors BotBrain.Settings) ---- */
    private boolean mountAttack, autoAttack, autoPot;
    private boolean farmBot, searchMetin, autoCollect, gmAlert;
    private int attackSpeed, moveSpeed;

    /* ================================================================== */
    /*  Static accessor                                                   */
    /* ================================================================== */

    public static BotOverlay get() { return sInstance; }

    /* ================================================================== */
    /*  Lifecycle                                                         */
    /* ================================================================== */

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        wm      = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        prefs   = getSharedPreferences(Constants.PREFS_SETTINGS, MODE_PRIVATE);

        loadSettings();
        coords       = new CoordManager(this);
        colorLearner = new AdaptiveColorLearner(this);

        showForegroundNotification();
        createPanel();
        createMiniButton();

        if (!isCalibrated()) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    Toast.makeText(BotOverlay.this,
                            R.string.toast_calibrate_first, Toast.LENGTH_LONG).show();
                }
            }, 1000);
        }
    }

    @Override
    public void onDestroy() {
        if (brain != null) brain.stop();
        if (calibration != null) calibration.remove();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        safeRemove(panelView);
        safeRemove(miniView);
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /* ================================================================== */
    /*  Settings persistence                                              */
    /* ================================================================== */

    private void loadSettings() {
        mountAttack = prefs.getBoolean("mountAttack", false);
        autoAttack  = prefs.getBoolean("autoAttack",  true);
        autoPot     = prefs.getBoolean("autoPot",     true);
        farmBot     = prefs.getBoolean("farmBot",     true);
        searchMetin = prefs.getBoolean("searchMetin", false);
        autoCollect = prefs.getBoolean("autoCollect", true);
        gmAlert     = prefs.getBoolean("gmAlert",     false);
        attackSpeed = prefs.getInt("attackSpeed", 25);
        moveSpeed   = prefs.getInt("moveSpeed",   25);
    }

    private void saveBool(String key, boolean val) {
        prefs.edit().putBoolean(key, val).apply();
    }

    private void saveInt(String key, int val) {
        prefs.edit().putInt(key, val).apply();
    }

    private void applySettings() {
        if (brain == null) return;
        BotBrain.Settings s = brain.getSettings();
        s.mountAttack = mountAttack;  s.autoAttack = autoAttack;
        s.autoPot     = autoPot;      s.farmBot    = farmBot;
        s.searchMetin = searchMetin;  s.autoCollect = autoCollect;
        s.gmAlert     = gmAlert;      s.attackSpeed = attackSpeed;
        s.moveSpeed   = moveSpeed;
    }

    private boolean isCalibrated() {
        return getSharedPreferences(Constants.PREFS_COORDS, MODE_PRIVATE)
                .getBoolean("kalibreEdildi", false);
    }

    /* ================================================================== */
    /*  Notification                                                      */
    /* ================================================================== */

    @SuppressWarnings("deprecation")
    private void showForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    Constants.CHANNEL_OVERLAY,
                    getString(R.string.channel_overlay_name),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, Constants.CHANNEL_OVERLAY);
        } else {
            nb = new Notification.Builder(this);
        }

        Notification n = nb
                .setContentTitle("M2 Bot Pro " + Constants.VERSION_LABEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        startForeground(Constants.NOTIF_OVERLAY_ID, n);
    }

    /* ================================================================== */
    /*  Panel construction                                                */
    /* ================================================================== */

    private void createPanel() {
        int type = overlayType();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundRect(Color.argb(235, 10, 10, 25), dp(12)));

        // ---- Header ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackground(topRound(Color.argb(245, 15, 25, 50), dp(12)));
        header.setPadding(dp(10), dp(6), dp(10), dp(4));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("M2 BOT PRO " + Constants.VERSION_LABEL);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(Color.rgb(0, 230, 118));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusTv = new TextView(this);
        statusTv.setText("READY");
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        statusTv.setTextColor(Color.rgb(255, 234, 0));
        statusTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(statusTv);
        header.addView(titleRow);

        statsTv = new TextView(this);
        statsTv.setText("K:0  L:0  HP:--  MP:--");
        statsTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        statsTv.setTextColor(Color.argb(140, 200, 200, 200));
        header.addView(statsTv);
        root.addView(header);

        // ---- Scrollable content ----
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(2), dp(10), dp(2));

        addSlider(content, "Attack Speed", attackSpeed, new SliderCb() {
            @Override public void onChange(int v) {
                attackSpeed = v; saveInt("attackSpeed", v);
                if (brain != null) brain.getSettings().attackSpeed = v;
            }
        });
        addSlider(content, "Move Speed", moveSpeed, new SliderCb() {
            @Override public void onChange(int v) {
                moveSpeed = v; saveInt("moveSpeed", v);
                if (brain != null) brain.getSettings().moveSpeed = v;
            }
        });

        addToggle(content, "Mount Attack", mountAttack, new ToggleCb() {
            @Override public void onChange(boolean v) {
                mountAttack = v; saveBool("mountAttack", v);
                if (brain != null) brain.getSettings().mountAttack = v;
            }
        });
        addToggle(content, "Auto Attack", autoAttack, new ToggleCb() {
            @Override public void onChange(boolean v) {
                autoAttack = v; saveBool("autoAttack", v);
                if (brain != null) brain.getSettings().autoAttack = v;
            }
        });
        addToggle(content, "Auto Potion", autoPot, new ToggleCb() {
            @Override public void onChange(boolean v) {
                autoPot = v; saveBool("autoPot", v);
                if (brain != null) brain.getSettings().autoPot = v;
            }
        });
        addToggle(content, "Farm Bot", farmBot, new ToggleCb() {
            @Override public void onChange(boolean v) {
                farmBot = v; saveBool("farmBot", v);
                if (brain != null) brain.getSettings().farmBot = v;
            }
        });
        addToggle(content, "Metin Search", searchMetin, new ToggleCb() {
            @Override public void onChange(boolean v) {
                searchMetin = v; saveBool("searchMetin", v);
                if (brain != null) brain.getSettings().searchMetin = v;
            }
        });
        addToggle(content, "Auto Collect", autoCollect, new ToggleCb() {
            @Override public void onChange(boolean v) {
                autoCollect = v; saveBool("autoCollect", v);
                if (brain != null) brain.getSettings().autoCollect = v;
            }
        });
        addToggle(content, "GM Alert", gmAlert, new ToggleCb() {
            @Override public void onChange(boolean v) {
                gmAlert = v; saveBool("gmAlert", v);
                if (brain != null) brain.getSettings().gmAlert = v;
            }
        });

        logTv = new TextView(this);
        logTv.setText("Ready...");
        logTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        logTv.setTextColor(Color.argb(100, 180, 180, 180));
        logTv.setMaxLines(4);
        logTv.setPadding(0, dp(3), 0, 0);
        content.addView(logTv);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- Start / Stop button ----
        startBtn = new TextView(this);
        startBtn.setText("\u25B6  START");
        startBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        startBtn.setTextColor(Color.rgb(0, 230, 118));
        startBtn.setTypeface(Typeface.DEFAULT_BOLD);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        startBtn.setBackground(roundRect(Color.argb(50, 0, 230, 118), dp(6)));
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleBot(); }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(dp(10), dp(2), dp(10), dp(2));
        root.addView(startBtn, btnLp);

        // ---- Footer buttons ----
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setBackground(bottomRound(Color.argb(245, 8, 10, 18), dp(12)));
        footer.setPadding(dp(3), dp(4), dp(3), dp(4));

        addFooterButton(footer, "SETUP",   Color.rgb(255, 152, 0), new View.OnClickListener() {
            @Override public void onClick(View v) { calibrate(); }
        });
        addFooterButton(footer, "TRANSPARENT", Color.rgb(200, 200, 200), new View.OnClickListener() {
            @Override public void onClick(View v) { toggleTransparency(); }
        });
        addFooterButton(footer, "MINIMIZE", Color.rgb(41, 121, 255), new View.OnClickListener() {
            @Override public void onClick(View v) { minimize(); }
        });
        addFooterButton(footer, "LEARN",   Color.rgb(156, 39, 176), new View.OnClickListener() {
            @Override public void onClick(View v) { forceLearn(); }
        });
        addFooterButton(footer, "CLOSE",   Color.rgb(255, 23, 68), new View.OnClickListener() {
            @Override public void onClick(View v) { closeBot(); }
        });
        root.addView(footer);

        panelView = root;
        panelParams = new WindowManager.LayoutParams(
                dp(270), dp(380), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.CENTER;

        // Make the title row draggable
        titleRow.setOnTouchListener(new DragListener(panelParams, panelView));
        wm.addView(panelView, panelParams);
    }

    /* ================================================================== */
    /*  Mini floating button                                              */
    /* ================================================================== */

    private void createMiniButton() {
        int type = overlayType();

        TextView fab = new TextView(this);
        fab.setText("M2");
        fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        fab.setTextColor(Color.WHITE);
        fab.setTypeface(Typeface.DEFAULT_BOLD);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable dr = new GradientDrawable();
        dr.setShape(GradientDrawable.OVAL);
        dr.setColor(Color.rgb(0, 200, 100));
        fab.setBackground(dr);
        miniView = fab;

        miniParams = new WindowManager.LayoutParams(
                dp(40), dp(40), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        miniParams.gravity = Gravity.TOP | Gravity.END;
        miniParams.x = dp(6);
        miniParams.y = dp(70);

        fab.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = miniParams.x;
                        startY = miniParams.y;
                        downX  = e.getRawX();
                        downY  = e.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        miniParams.x = startX - (int) (e.getRawX() - downX);
                        miniParams.y = startY + (int) (e.getRawY() - downY);
                        if (miniView != null && miniView.isAttachedToWindow())
                            wm.updateViewLayout(miniView, miniParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis() - downTime < 250) maximize();
                        return true;
                }
                return false;
            }
        });

        wm.addView(miniView, miniParams);
        miniView.setVisibility(View.GONE);
    }

    /* ================================================================== */
    /*  Panel actions                                                     */
    /* ================================================================== */

    private void toggleBot() {
        if (brain == null) {
            brain = new BotBrain(coords, colorLearner);
            applySettings();
            brain.setListener(brainListener);
        }

        if (brain.getState() == BotBrain.State.IDLE) {
            if (!isCalibrated()) {
                Toast.makeText(this, R.string.toast_calibrate_first, Toast.LENGTH_LONG).show();
                return;
            }
            applySettings();
            brain.start();
            showStopButton();
        } else {
            brain.stop();
            showStartButton();
        }
    }

    private void calibrate() {
        if (brain != null && brain.isRunning()) {
            brain.stop();
            showStartButton();
        }
        if (panelView != null) panelView.setVisibility(View.GONE);

        calibration = new KalibrasyonOverlay(this, wm, new KalibrasyonOverlay.CalibrationDone() {
            @Override
            public void onDone() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        coords       = new CoordManager(BotOverlay.this);
                        colorLearner = new AdaptiveColorLearner(BotOverlay.this);
                        if (panelView != null) {
                            panelView.setVisibility(View.VISIBLE);
                            panelView.setAlpha(1.0f);
                            transparent = false;
                        }
                        if (logTv != null) logTv.setText("Calibration complete!");
                        Toast.makeText(BotOverlay.this,
                                R.string.toast_calibration_done, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        calibration.start();
    }

    private void toggleTransparency() {
        transparent = !transparent;
        if (panelView != null) panelView.setAlpha(transparent ? 0.12f : 1.0f);
    }

    private void minimize() {
        if (panelView != null) panelView.setVisibility(View.GONE);
        if (miniView != null)  miniView.setVisibility(View.VISIBLE);
    }

    private void maximize() {
        if (panelView != null) {
            panelView.setVisibility(View.VISIBLE);
            panelView.setAlpha(1.0f);
            transparent = false;
        }
        if (miniView != null) miniView.setVisibility(View.GONE);
    }

    private void forceLearn() {
        colorLearner = new AdaptiveColorLearner(this);
        colorLearner.resetToDefaults();
        colorLearner.save();
        Toast.makeText(this, R.string.toast_color_reset, Toast.LENGTH_LONG).show();
        if (logTv != null) logTv.setText("Colour data reset");
    }

    private void closeBot() {
        if (brain != null && brain.isRunning()) brain.stop();
        Toast.makeText(this, R.string.toast_bot_closed, Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    /* ================================================================== */
    /*  BotBrain listener                                                 */
    /* ================================================================== */

    private final BotBrain.Listener brainListener = new BotBrain.Listener() {
        @Override
        public void onStateChanged(final BotBrain.State s) {
            handler.post(new Runnable() {
                @Override public void run() {
                    if (statusTv != null) statusTv.setText(s.name());
                }
            });
        }

        @Override
        public void onLog(final String msg) {
            handler.post(new Runnable() {
                @Override public void run() {
                    if (logTv != null) logTv.setText(msg);
                }
            });
        }

        @Override
        public void onStats(final int k, final int l, final int hp, final int mp) {
            handler.post(new Runnable() {
                @Override public void run() {
                    if (statsTv != null)
                        statsTv.setText("K:" + k + "  L:" + l + "  HP:" + hp + "  MP:" + mp);
                }
            });
        }
    };

    /* ================================================================== */
    /*  Button state helpers                                              */
    /* ================================================================== */

    private void showStartButton() {
        if (startBtn == null) return;
        startBtn.setText("\u25B6  START");
        startBtn.setTextColor(Color.rgb(0, 230, 118));
        startBtn.setBackground(roundRect(Color.argb(50, 0, 230, 118), dp(6)));
    }

    private void showStopButton() {
        if (startBtn == null) return;
        startBtn.setText("\u23F8  STOP");
        startBtn.setTextColor(Color.rgb(255, 23, 68));
        startBtn.setBackground(roundRect(Color.argb(50, 255, 23, 68), dp(6)));
    }

    /* ================================================================== */
    /*  UI factory helpers                                                */
    /* ================================================================== */

    interface SliderCb  { void onChange(int v); }
    interface ToggleCb  { void onChange(boolean v); }

    private void addSlider(LinearLayout parent, String label, int initial,
                            final SliderCb cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, 0);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        lbl.setTextColor(Color.argb(180, 255, 255, 255));
        lbl.setMinWidth(dp(60));
        row.addView(lbl);

        final TextView valTv = new TextView(this);
        valTv.setText(String.valueOf(initial));
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        valTv.setTextColor(Color.WHITE);
        valTv.setTypeface(Typeface.DEFAULT_BOLD);
        valTv.setMinWidth(dp(20));
        valTv.setGravity(Gravity.END);

        SeekBar sb = new SeekBar(this);
        sb.setMax(49);
        sb.setProgress(Math.max(0, initial - 1));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = progress + 1;
                valTv.setText(String.valueOf(val));
                if (cb != null) cb.onChange(val);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar)  { }
        });
        row.addView(sb, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(valTv);
        parent.addView(row);
    }

    private void addToggle(LinearLayout parent, String label, boolean initial,
                            final ToggleCb cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(3));
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        lbl.setTextColor(Color.argb(200, 255, 255, 255));
        row.addView(lbl, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final View dot = new View(this);
        setDotColor(dot, initial);
        final boolean[] state = {initial};
        dot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[0] = !state[0];
                setDotColor(dot, state[0]);
                if (cb != null) cb.onChange(state[0]);
            }
        });
        row.addView(dot, new LinearLayout.LayoutParams(dp(20), dp(20)));
        parent.addView(row);
    }

    private void setDotColor(View dot, boolean on) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(on ? Color.rgb(0, 230, 118) : Color.rgb(255, 23, 68));
        dot.setBackground(gd);
    }

    private void addFooterButton(LinearLayout parent, String text, int color,
                                  View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7);
        btn.setTextColor(color);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(3), dp(4), dp(3), dp(4));
        btn.setBackground(roundRect(
                Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)),
                dp(5)));
        btn.setOnClickListener(listener);
        parent.addView(btn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /* ================================================================== */
    /*  Drawable helpers                                                  */
    /* ================================================================== */

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable topRound(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return g;
    }

    private GradientDrawable bottomRound(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadii(new float[]{0, 0, 0, 0, radius, radius, radius, radius});
        return g;
    }

    /* ================================================================== */
    /*  Misc helpers                                                      */
    /* ================================================================== */

    private int dp(int d) {
        return (int) (d * getResources().getDisplayMetrics().density);
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void safeRemove(View v) {
        try { if (v != null && v.isAttachedToWindow()) wm.removeView(v); }
        catch (Exception ignored) { }
    }

    /* ================================================================== */
    /*  Drag listener                                                     */
    /* ================================================================== */

    private final class DragListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams lp;
        private final View target;
        private int startX, startY;
        private float downX, downY;

        DragListener(WindowManager.LayoutParams lp, View target) {
            this.lp = lp;
            this.target = target;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;  startY = lp.y;
                    downX  = e.getRawX();  downY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = startX + (int) (e.getRawX() - downX);
                    lp.y = startY + (int) (e.getRawY() - downY);
                    if (target != null && target.isAttachedToWindow())
                        wm.updateViewLayout(target, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return false;
        }
    }
}
