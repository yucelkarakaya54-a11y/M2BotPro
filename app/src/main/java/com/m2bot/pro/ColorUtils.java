package com.m2bot.pro;

import android.graphics.Color;

/**
 * HSV-based colour classification utilities.
 * <p>
 * Every range check converts the incoming RGB triplet to HSV first, giving
 * robust detection under varying brightness / saturation conditions that
 * plague pure-RGB distance checks.
 */
public final class ColorUtils {

    private ColorUtils() { /* utility class */ }

    /* ------------------------------------------------------------------ */
    /*  RGB -> HSV                                                        */
    /* ------------------------------------------------------------------ */

    /** Converts an RGB triplet to a float[3] {H 0..360, S 0..1, V 0..1}. */
    public static float[] rgbToHsv(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        return hsv;
    }

    /* ------------------------------------------------------------------ */
    /*  Hue-range predicates                                              */
    /* ------------------------------------------------------------------ */

    /** Red hue wraps around 0/360, so we check both ends. */
    public static boolean isRedRange(int r, int g, int b) {
        float[] hsv = rgbToHsv(r, g, b);
        return (hsv[0] < 15.0f || hsv[0] > 345.0f)
                && hsv[1] > 0.4f
                && hsv[2] > 0.35f;
    }

    public static boolean isGreenRange(int r, int g, int b) {
        float[] hsv = rgbToHsv(r, g, b);
        return hsv[0] > 80.0f && hsv[0] < 160.0f
                && hsv[1] > 0.3f
                && hsv[2] > 0.3f;
    }

    public static boolean isBlueRange(int r, int g, int b) {
        float[] hsv = rgbToHsv(r, g, b);
        return hsv[0] > 190.0f && hsv[0] < 260.0f
                && hsv[1] > 0.3f
                && hsv[2] > 0.3f;
    }

    public static boolean isYellowRange(int r, int g, int b) {
        float[] hsv = rgbToHsv(r, g, b);
        return hsv[0] > 35.0f && hsv[0] < 65.0f
                && hsv[1] > 0.4f
                && hsv[2] > 0.4f;
    }

    public static boolean isOrangeRange(int r, int g, int b) {
        float[] hsv = rgbToHsv(r, g, b);
        return hsv[0] > 15.0f && hsv[0] < 40.0f
                && hsv[1] > 0.5f
                && hsv[2] > 0.5f;
    }

    public static boolean isPurpleRange(int r, int g, int b) {
        float[] hsv = rgbToHsv(r, g, b);
        return hsv[0] > 260.0f && hsv[0] < 320.0f
                && hsv[1] > 0.3f
                && hsv[2] > 0.3f;
    }

    public static boolean isWhiteRange(int r, int g, int b) {
        float[] hsv = rgbToHsv(r, g, b);
        return hsv[1] < 0.15f && hsv[2] > 0.75f;
    }

    /* ------------------------------------------------------------------ */
    /*  Distance / luminance helpers                                      */
    /* ------------------------------------------------------------------ */

    /** Euclidean distance in RGB space. */
    public static double colorDistance(int r1, int g1, int b1,
                                       int r2, int g2, int b2) {
        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /** ITU-R BT.601 perceived luminance (0..255). */
    public static int getLuminance(int r, int g, int b) {
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    /**
     * Returns {@code true} when the ARGB pixel is within {@code tolerance}
     * Euclidean distance of the target RGB.
     */
    public static boolean matchesColor(int pixel, int targetR, int targetG, int targetB,
                                        double tolerance) {
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);
        return colorDistance(r, g, b, targetR, targetG, targetB) < tolerance;
    }

    /**
     * Broad check for HP / MP bar fill colour.
     *
     * @param pixel  ARGB pixel value
     * @param isHp   {@code true} for HP bar (red/orange/green), {@code false} for MP (blue/purple)
     */
    public static boolean isBarColor(int pixel, boolean isHp) {
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);
        if (isHp) {
            return isRedRange(r, g, b)
                    || isOrangeRange(r, g, b)
                    || (isGreenRange(r, g, b) && g > 120);
        } else {
            return isBlueRange(r, g, b)
                    || (isPurpleRange(r, g, b) && b > 100);
        }
    }
}
