package com.m2bot.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

/**
 * Runtime colour-learning system.
 * <p>
 * Learns the actual on-screen colours of HP / MP bars, monster names, metin
 * stone names and item glows via exponential moving average.  Learned values
 * are persisted in SharedPreferences so they survive restarts.
 * <p>
 * <b>Bug-fix vs. original:</b> {@code learnSamples} is now incremented by
 * every {@code learn*()} call, not just {@link #learnHpBarColor}.
 */
public final class AdaptiveColorLearner {

    private static final String TAG = Constants.APP_TAG + ".ColorLearn";

    // Blending ratio: 70 % old, 30 % new
    private static final double BLEND_OLD = 0.7;
    private static final double BLEND_NEW = 0.3;

    /* ---- Learned colours (RGB) ---- */
    private int hpBarR, hpBarG, hpBarB;
    private int hpEmptyR, hpEmptyG, hpEmptyB;
    private int mpBarR, mpBarG, mpBarB;
    private int mpEmptyR, mpEmptyG, mpEmptyB;
    private int mobNameR, mobNameG, mobNameB;
    private int metinNameR, metinNameG, metinNameB;
    private int itemGlowR, itemGlowG, itemGlowB;

    /* ---- Tolerances ---- */
    private double hpTolerance;
    private double mpTolerance;
    private double mobTolerance;
    private double metinTolerance;
    private double itemTolerance;

    /* ---- State ---- */
    private boolean learned;
    private int learnSamples;
    private final Context ctx;

    /* ================================================================== */
    /*  Construction / persistence                                        */
    /* ================================================================== */

    public AdaptiveColorLearner(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        resetToDefaults();
        load();
    }

    /** Resets all learned values to compiled-in defaults. */
    public void resetToDefaults() {
        hpBarR   = Constants.DEF_HP_BAR_R;  hpBarG   = Constants.DEF_HP_BAR_G;  hpBarB   = Constants.DEF_HP_BAR_B;
        hpEmptyR = Constants.DEF_HP_EMP_R;  hpEmptyG = Constants.DEF_HP_EMP_G;  hpEmptyB = Constants.DEF_HP_EMP_B;
        mpBarR   = Constants.DEF_MP_BAR_R;  mpBarG   = Constants.DEF_MP_BAR_G;  mpBarB   = Constants.DEF_MP_BAR_B;
        mpEmptyR = Constants.DEF_MP_EMP_R;  mpEmptyG = Constants.DEF_MP_EMP_G;  mpEmptyB = Constants.DEF_MP_EMP_B;
        mobNameR = Constants.DEF_MOB_R;     mobNameG = Constants.DEF_MOB_G;      mobNameB = Constants.DEF_MOB_B;
        metinNameR = Constants.DEF_METIN_R; metinNameG = Constants.DEF_METIN_G;  metinNameB = Constants.DEF_METIN_B;
        itemGlowR  = Constants.DEF_ITEM_R;  itemGlowG = Constants.DEF_ITEM_G;   itemGlowB  = Constants.DEF_ITEM_B;

        hpTolerance    = Constants.DEF_HP_TOLERANCE;
        mpTolerance    = Constants.DEF_MP_TOLERANCE;
        mobTolerance   = Constants.DEF_MOB_TOLERANCE;
        metinTolerance = Constants.DEF_METIN_TOLERANCE;
        itemTolerance  = Constants.DEF_ITEM_TOLERANCE;

        learned = false;
        learnSamples = 0;
    }

    /** Loads persisted colour data. */
    public void load() {
        SharedPreferences sp = ctx.getSharedPreferences(Constants.PREFS_COLORS, Context.MODE_PRIVATE);
        if (!sp.getBoolean("hasColors", false)) return;

        hpBarR   = sp.getInt("hpBarR",   hpBarR);   hpBarG   = sp.getInt("hpBarG",   hpBarG);   hpBarB   = sp.getInt("hpBarB",   hpBarB);
        hpEmptyR = sp.getInt("hpEmptyR", hpEmptyR); hpEmptyG = sp.getInt("hpEmptyG", hpEmptyG); hpEmptyB = sp.getInt("hpEmptyB", hpEmptyB);
        mpBarR   = sp.getInt("mpBarR",   mpBarR);   mpBarG   = sp.getInt("mpBarG",   mpBarG);   mpBarB   = sp.getInt("mpBarB",   mpBarB);
        mpEmptyR = sp.getInt("mpEmptyR", mpEmptyR); mpEmptyG = sp.getInt("mpEmptyG", mpEmptyG); mpEmptyB = sp.getInt("mpEmptyB", mpEmptyB);
        mobNameR = sp.getInt("mobNameR", mobNameR);  mobNameG = sp.getInt("mobNameG", mobNameG);  mobNameB = sp.getInt("mobNameB", mobNameB);

        hpTolerance    = sp.getFloat("hpTol",    (float) hpTolerance);
        mpTolerance    = sp.getFloat("mpTol",    (float) mpTolerance);
        mobTolerance   = sp.getFloat("mobTol",   (float) mobTolerance);
        metinTolerance = sp.getFloat("metinTol", (float) metinTolerance);
        itemTolerance  = sp.getFloat("itemTol",  (float) itemTolerance);

        learned      = sp.getBoolean("learned", false);
        learnSamples = sp.getInt("samples", 0);
        Log.d(TAG, "Colours loaded.  Samples=" + learnSamples + "  learned=" + learned);
    }

    /** Persists current colour data. */
    public void save() {
        SharedPreferences.Editor e = ctx.getSharedPreferences(Constants.PREFS_COLORS, Context.MODE_PRIVATE).edit();
        e.putBoolean("hasColors", true);
        e.putInt("hpBarR", hpBarR); e.putInt("hpBarG", hpBarG); e.putInt("hpBarB", hpBarB);
        e.putInt("hpEmptyR", hpEmptyR); e.putInt("hpEmptyG", hpEmptyG); e.putInt("hpEmptyB", hpEmptyB);
        e.putInt("mpBarR", mpBarR); e.putInt("mpBarG", mpBarG); e.putInt("mpBarB", mpBarB);
        e.putInt("mpEmptyR", mpEmptyR); e.putInt("mpEmptyG", mpEmptyG); e.putInt("mpEmptyB", mpEmptyB);
        e.putInt("mobNameR", mobNameR); e.putInt("mobNameG", mobNameG); e.putInt("mobNameB", mobNameB);
        e.putFloat("hpTol", (float) hpTolerance);
        e.putFloat("mpTol", (float) mpTolerance);
        e.putFloat("mobTol", (float) mobTolerance);
        e.putFloat("metinTol", (float) metinTolerance);
        e.putFloat("itemTol", (float) itemTolerance);
        e.putBoolean("learned", learned);
        e.putInt("samples", learnSamples);
        e.apply();
    }

    /* ================================================================== */
    /*  Learning API                                                      */
    /* ================================================================== */

    /**
     * Safely samples a pixel from {@code bmp} at ({@code x}, {@code y}).
     * Returns 0 (transparent black) when out of bounds or bitmap is null.
     */
    private static int safePixel(Bitmap bmp, int x, int y) {
        if (bmp == null || bmp.isRecycled()) return 0;
        if (x < 0 || y < 0 || x >= bmp.getWidth() || y >= bmp.getHeight()) return 0;
        return bmp.getPixel(x, y);
    }

    public void learnHpBarColor(Bitmap bmp, int x, int y) {
        int p = safePixel(bmp, x, y);
        if (p == 0) return;
        hpBarR = blend(hpBarR, Color.red(p));
        hpBarG = blend(hpBarG, Color.green(p));
        hpBarB = blend(hpBarB, Color.blue(p));
        learnSamples++;
    }

    public void learnHpEmptyColor(Bitmap bmp, int x, int y) {
        int p = safePixel(bmp, x, y);
        if (p == 0) return;
        hpEmptyR = blend(hpEmptyR, Color.red(p));
        hpEmptyG = blend(hpEmptyG, Color.green(p));
        hpEmptyB = blend(hpEmptyB, Color.blue(p));
        learnSamples++;
    }

    public void learnMpBarColor(Bitmap bmp, int x, int y) {
        int p = safePixel(bmp, x, y);
        if (p == 0) return;
        mpBarR = blend(mpBarR, Color.red(p));
        mpBarG = blend(mpBarG, Color.green(p));
        mpBarB = blend(mpBarB, Color.blue(p));
        learnSamples++;
    }

    public void learnMobNameColor(Bitmap bmp, int x, int y) {
        int p = safePixel(bmp, x, y);
        if (p == 0) return;
        mobNameR = blend(mobNameR, Color.red(p));
        mobNameG = blend(mobNameG, Color.green(p));
        mobNameB = blend(mobNameB, Color.blue(p));
        learnSamples++;
    }

    /* ---- State transitions ---- */

    public void markLearned() {
        learned = true;
        save();
    }

    public boolean isLearned() {
        return learned && learnSamples >= Constants.MIN_LEARN_SAMPLES;
    }

    public int getLearnSamples() { return learnSamples; }

    /* ================================================================== */
    /*  Pixel classification                                              */
    /* ================================================================== */

    public boolean isHpBarPixel(int pixel) {
        int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
        return ColorUtils.colorDistance(r, g, b, hpBarR, hpBarG, hpBarB) < hpTolerance
                || ColorUtils.isBarColor(pixel, true);
    }

    public boolean isHpEmptyPixel(int pixel) {
        int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
        return ColorUtils.colorDistance(r, g, b, hpEmptyR, hpEmptyG, hpEmptyB) < hpTolerance;
    }

    public boolean isMpBarPixel(int pixel) {
        int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
        return ColorUtils.colorDistance(r, g, b, mpBarR, mpBarG, mpBarB) < mpTolerance
                || ColorUtils.isBarColor(pixel, false);
    }

    public boolean isMobNamePixel(int r, int g, int b) {
        if (ColorUtils.colorDistance(r, g, b, mobNameR, mobNameG, mobNameB) < mobTolerance) return true;
        return ColorUtils.isRedRange(r, g, b) || ColorUtils.isOrangeRange(r, g, b);
    }

    public boolean isMetinNamePixel(int r, int g, int b) {
        if (ColorUtils.colorDistance(r, g, b, metinNameR, metinNameG, metinNameB) < metinTolerance) return true;
        return ColorUtils.isGreenRange(r, g, b) || ColorUtils.isYellowRange(r, g, b);
    }

    public boolean isItemPixel(int r, int g, int b) {
        return ColorUtils.isWhiteRange(r, g, b)
                || ColorUtils.isYellowRange(r, g, b)
                || ColorUtils.colorDistance(r, g, b, itemGlowR, itemGlowG, itemGlowB) < itemTolerance;
    }

    /* ================================================================== */
    /*  Internal                                                          */
    /* ================================================================== */

    private static int blend(int old, int cur) {
        return (int) (old * BLEND_OLD + cur * BLEND_NEW);
    }
}
