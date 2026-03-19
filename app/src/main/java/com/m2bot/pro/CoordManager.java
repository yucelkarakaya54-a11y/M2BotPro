package com.m2bot.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * Screen-coordinate manager.
 * <p>
 * All UI element positions are authored against a 1920x1080 reference
 * resolution and scaled to the actual display at construction time.
 * User-calibrated overrides (stored in SharedPreferences) take priority
 * when present.
 */
public final class CoordManager {

    /* ---- Screen dimensions ---- */
    public int screenW, screenH;
    public float scaleX, scaleY;

    /* ---- Action buttons ---- */
    public int atkX, atkY;
    public int potHpX, potHpY;
    public int potMpX, potMpY;
    public int pickupX, pickupY;

    /* ---- Joystick ---- */
    public int joyCX, joyCY, joyR;

    /* ---- Skill buttons ---- */
    public int skill1X, skill1Y;
    public int skill2X, skill2Y;
    public int skill3X, skill3Y;
    public int skill4X, skill4Y;
    public int skill86X, skill86Y;

    /* ---- HP / MP bar regions ---- */
    public int hpBarX1, hpBarY1, hpBarX2, hpBarY2;
    public int mpBarX1, mpBarY1, mpBarX2, mpBarY2;

    /* ---- Search / item regions ---- */
    public int searchX1, searchY1, searchX2, searchY2;
    public int itemX1, itemY1, itemX2, itemY2;

    /* ---- Character centre ---- */
    public int charCenterX, charCenterY;

    /* ---- Mini-map ---- */
    public int miniMapX1, miniMapY1, miniMapX2, miniMapY2;

    /* ================================================================== */
    /*  Construction                                                      */
    /* ================================================================== */

    public CoordManager(Context ctx) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);

        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        // Force landscape
        if (screenW < screenH) {
            int tmp = screenW;
            screenW = screenH;
            screenH = tmp;
        }

        scaleX = (float) screenW / Constants.REF_WIDTH;
        scaleY = (float) screenH / Constants.REF_HEIGHT;

        SharedPreferences sp = ctx.getSharedPreferences(Constants.PREFS_COORDS, Context.MODE_PRIVATE);

        // Action buttons
        atkX    = sp.getInt("atkX",    scale(1720, scaleX));
        atkY    = sp.getInt("atkY",    scale(850,  scaleY));
        potHpX  = sp.getInt("potHpX",  scale(1580, scaleX));
        potHpY  = sp.getInt("potHpY",  scale(950,  scaleY));
        potMpX  = sp.getInt("potMpX",  scale(1660, scaleX));
        potMpY  = sp.getInt("potMpY",  scale(950,  scaleY));
        pickupX = sp.getInt("pickupX", scale(1720, scaleX));
        pickupY = sp.getInt("pickupY", scale(750,  scaleY));

        // Joystick
        joyCX = sp.getInt("joyCX", scale(200, scaleX));
        joyCY = sp.getInt("joyCY", scale(800, scaleY));
        joyR  = sp.getInt("joyR",  scale(120, scaleX));

        // Skills
        skill1X  = sp.getInt("skill1X",  scale(1400, scaleX));
        skill1Y  = sp.getInt("skill1Y",  scale(950,  scaleY));
        skill2X  = sp.getInt("skill2X",  scale(1480, scaleX));
        skill2Y  = sp.getInt("skill2Y",  scale(950,  scaleY));
        skill3X  = sp.getInt("skill3X",  scale(1400, scaleX));
        skill3Y  = sp.getInt("skill3Y",  scale(870,  scaleY));
        skill4X  = sp.getInt("skill4X",  scale(1480, scaleX));
        skill4Y  = sp.getInt("skill4Y",  scale(870,  scaleY));
        skill86X = sp.getInt("skill86X", scale(1560, scaleX));
        skill86Y = sp.getInt("skill86Y", scale(870,  scaleY));

        // HP bar
        hpBarX1 = sp.getInt("hpBarX1", scale(80,  scaleX));
        hpBarY1 = sp.getInt("hpBarY1", scale(45,  scaleY));
        hpBarX2 = sp.getInt("hpBarX2", scale(350, scaleX));
        hpBarY2 = sp.getInt("hpBarY2", scale(55,  scaleY));

        // MP bar
        mpBarX1 = sp.getInt("mpBarX1", scale(80,  scaleX));
        mpBarY1 = sp.getInt("mpBarY1", scale(65,  scaleY));
        mpBarX2 = sp.getInt("mpBarX2", scale(350, scaleX));
        mpBarY2 = sp.getInt("mpBarY2", scale(75,  scaleY));

        // Search area for monsters / metin stones
        searchX1 = sp.getInt("aramaX1", scale(300,  scaleX));
        searchY1 = sp.getInt("aramaY1", scale(100,  scaleY));
        searchX2 = sp.getInt("aramaX2", scale(1620, scaleX));
        searchY2 = sp.getInt("aramaY2", scale(750,  scaleY));

        // Item drop area
        itemX1 = sp.getInt("esyaX1", scale(600,  scaleX));
        itemY1 = sp.getInt("esyaY1", scale(400,  scaleY));
        itemX2 = sp.getInt("esyaX2", scale(1320, scaleX));
        itemY2 = sp.getInt("esyaY2", scale(800,  scaleY));

        // Character is always at screen centre
        charCenterX = screenW / 2;
        charCenterY = screenH / 2;

        // Mini-map
        miniMapX1 = sp.getInt("miniMapX1", scale(1700, scaleX));
        miniMapY1 = sp.getInt("miniMapY1", scale(10,   scaleY));
        miniMapX2 = sp.getInt("miniMapX2", scale(1910, scaleX));
        miniMapY2 = sp.getInt("miniMapY2", scale(220,  scaleY));
    }

    /* ================================================================== */
    /*  Helpers                                                           */
    /* ================================================================== */

    private static int scale(int refValue, float factor) {
        return (int) (refValue * factor);
    }

    /**
     * Discards all cached coordinates and re-reads from SharedPreferences
     * and the current display metrics.
     */
    public void reload(Context ctx) {
        CoordManager fresh = new CoordManager(ctx);
        copyFrom(fresh);
    }

    /** Copies every coordinate from {@code src} into this instance. */
    private void copyFrom(CoordManager src) {
        this.screenW = src.screenW;       this.screenH = src.screenH;
        this.scaleX  = src.scaleX;        this.scaleY  = src.scaleY;
        this.atkX    = src.atkX;          this.atkY    = src.atkY;
        this.potHpX  = src.potHpX;        this.potHpY  = src.potHpY;
        this.potMpX  = src.potMpX;        this.potMpY  = src.potMpY;
        this.pickupX = src.pickupX;       this.pickupY = src.pickupY;
        this.joyCX   = src.joyCX;         this.joyCY   = src.joyCY;
        this.joyR    = src.joyR;
        this.skill1X = src.skill1X;       this.skill1Y = src.skill1Y;
        this.skill2X = src.skill2X;       this.skill2Y = src.skill2Y;
        this.skill3X = src.skill3X;       this.skill3Y = src.skill3Y;
        this.skill4X = src.skill4X;       this.skill4Y = src.skill4Y;
        this.skill86X = src.skill86X;     this.skill86Y = src.skill86Y;
        this.hpBarX1 = src.hpBarX1;       this.hpBarY1 = src.hpBarY1;
        this.hpBarX2 = src.hpBarX2;       this.hpBarY2 = src.hpBarY2;
        this.mpBarX1 = src.mpBarX1;       this.mpBarY1 = src.mpBarY1;
        this.mpBarX2 = src.mpBarX2;       this.mpBarY2 = src.mpBarY2;
        this.searchX1 = src.searchX1;     this.searchY1 = src.searchY1;
        this.searchX2 = src.searchX2;     this.searchY2 = src.searchY2;
        this.itemX1  = src.itemX1;        this.itemY1  = src.itemY1;
        this.itemX2  = src.itemX2;        this.itemY2  = src.itemY2;
        this.charCenterX = src.charCenterX;
        this.charCenterY = src.charCenterY;
        this.miniMapX1 = src.miniMapX1;   this.miniMapY1 = src.miniMapY1;
        this.miniMapX2 = src.miniMapX2;   this.miniMapY2 = src.miniMapY2;
    }
}
