package com.m2bot.pro;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Computer-vision layer that analyses captured frames to extract game state:
 * HP / MP percentages, monster / metin-stone positions, item drops and GM
 * presence.
 * <p>
 * <b>Fixes over the original v5:</b>
 * <ul>
 *   <li>Removed dead-code variables ({@code ox}, {@code oy}) in bar analysis.</li>
 *   <li>Sorting comparators use {@code long} arithmetic to prevent int overflow.</li>
 *   <li>Clamp helpers are used consistently to avoid out-of-bounds pixel reads.</li>
 * </ul>
 */
public final class GameVision {

    private static final String TAG = Constants.APP_TAG + ".Vision";

    private GameVision() { /* utility class */ }

    /* ================================================================== */
    /*  Entity data class                                                 */
    /* ================================================================== */

    /** A detected on-screen entity (monster, metin stone, or item drop). */
    public static final class Entity {
        public final int clickX, clickY;
        public final int width, height;
        public final int confidence;
        public final String type;

        public Entity(int x, int y, int w, int h, int confidence, String type) {
            this.clickX     = x;
            this.clickY     = y;
            this.width      = w;
            this.height     = h;
            this.confidence = confidence;
            this.type       = type;
        }
    }

    /* ================================================================== */
    /*  HP / MP analysis                                                  */
    /* ================================================================== */

    /**
     * Analyses the HP bar region and returns a percentage [0..100], or -1
     * when the frame is unavailable.
     */
    public static int analyzeHp(ScreenCaptureService cap, CoordManager k,
                                 AdaptiveColorLearner learner) {
        return analyzeBar(cap, k, learner, true);
    }

    /** Same as {@link #analyzeHp} but for the MP bar. */
    public static int analyzeMp(ScreenCaptureService cap, CoordManager k,
                                 AdaptiveColorLearner learner) {
        return analyzeBar(cap, k, learner, false);
    }

    private static int analyzeBar(ScreenCaptureService cap, CoordManager k,
                                   AdaptiveColorLearner learner, boolean isHp) {
        try {
            Bitmap bmp = cap.getLatestBitmap();
            if (bmp == null || bmp.isRecycled()) return -1;

            int bw = bmp.getWidth(), bh = bmp.getHeight();
            int scale = cap.getCaptureScale();

            int barX1, barY1, barX2, barY2;
            if (isHp) {
                barX1 = k.hpBarX1; barY1 = k.hpBarY1;
                barX2 = k.hpBarX2; barY2 = k.hpBarY2;
            } else {
                barX1 = k.mpBarX1; barY1 = k.mpBarY1;
                barX2 = k.mpBarX2; barY2 = k.mpBarY2;
            }

            int x1   = clamp(barX1 / scale, 0, bw - 1);
            int x2   = clamp(barX2 / scale, 0, bw - 1);
            int yMid = clamp(((barY1 + barY2) / 2) / scale, 0, bh - 1);
            if (x2 <= x1) return -1;

            int halfH = Math.max(1, Math.abs(barY2 - barY1) / scale / 2);
            int filled = 0, total = 0;

            for (int dy = -halfH; dy <= halfH; dy += Math.max(1, halfH)) {
                int sy = clamp(yMid + dy, 0, bh - 1);
                for (int x = x1; x < x2; x++) {
                    if (x >= bw) break;
                    int pixel = bmp.getPixel(x, sy);
                    total++;
                    if (learner != null && learner.isLearned()) {
                        if (isHp ? learner.isHpBarPixel(pixel) : learner.isMpBarPixel(pixel))
                            filled++;
                    } else {
                        if (ColorUtils.isBarColor(pixel, isHp)) filled++;
                    }
                }
            }
            if (total == 0) return -1;
            return clamp(filled * 100 / total, 0, 100);
        } catch (Exception e) {
            return -1;
        }
    }

    /* ================================================================== */
    /*  Monster detection                                                 */
    /* ================================================================== */

    public static List<Entity> findMonsters(ScreenCaptureService cap,
                                             CoordManager k,
                                             AdaptiveColorLearner learner) {
        return findNamedEntities(cap, k, learner, false);
    }

    /* ================================================================== */
    /*  Metin-stone detection                                             */
    /* ================================================================== */

    public static List<Entity> findMetinStones(ScreenCaptureService cap,
                                                CoordManager k,
                                                AdaptiveColorLearner learner) {
        return findNamedEntities(cap, k, learner, true);
    }

    /**
     * Shared implementation for monster and metin detection.
     * Both use the same algorithm -- only the colour matcher and shape
     * constraints differ.
     */
    private static List<Entity> findNamedEntities(ScreenCaptureService cap,
                                                   CoordManager k,
                                                   final AdaptiveColorLearner learner,
                                                   final boolean isMetin) {
        List<Entity> results = new ArrayList<>();
        try {
            Bitmap bmp = cap.getLatestBitmap();
            if (bmp == null || bmp.isRecycled()) return results;

            int bw = bmp.getWidth(), bh = bmp.getHeight();
            int scale = cap.getCaptureScale();

            Rect area = new Rect(
                    clamp(k.searchX1 / scale, 0, bw - 1),
                    clamp(k.searchY1 / scale, 0, bh - 1),
                    clamp(k.searchX2 / scale, 0, bw - 1),
                    clamp(k.searchY2 / scale, 0, bh - 1));
            if (area.width() <= 0 || area.height() <= 0) return results;

            int step = Math.max(2, Math.min(area.width(), area.height()) / (isMetin ? 160 : 150));

            // Build binary mask
            boolean[][] mask = ImageCluster.createMask(bmp, area,
                    new ImageCluster.PixelMatcher() {
                        @Override
                        public boolean matches(int r, int g, int b) {
                            if (isMetin) {
                                if (learner != null && learner.isLearned())
                                    return learner.isMetinNamePixel(r, g, b);
                                return ColorUtils.isGreenRange(r, g, b)
                                        || ColorUtils.isYellowRange(r, g, b);
                            } else {
                                if (learner != null && learner.isLearned())
                                    return learner.isMobNamePixel(r, g, b);
                                return ColorUtils.isRedRange(r, g, b)
                                        || ColorUtils.isOrangeRange(r, g, b);
                            }
                        }
                    }, step);

            int minPx  = isMetin ? Constants.BLOB_MIN_METIN_PX  : Constants.BLOB_MIN_MOB_PX;
            int maxPx  = isMetin ? Constants.BLOB_MAX_METIN_PX  : Constants.BLOB_MAX_MOB_PX;
            float minAr = isMetin ? Constants.METIN_MIN_ASPECT   : Constants.MOB_MIN_ASPECT;
            float maxAr = isMetin ? Constants.METIN_MAX_ASPECT   : Constants.MOB_MAX_ASPECT;
            int clickOff = isMetin ? Constants.METIN_CLICK_OFFSET_Y : Constants.MOB_CLICK_OFFSET_Y;
            int mergeR   = isMetin ? Constants.ENTITY_MERGE_RADIUS_METIN
                                   : Constants.ENTITY_MERGE_RADIUS_MOB;

            List<ImageCluster.Blob> blobs =
                    ImageCluster.findBlobs(bmp, area, mask, step, minPx, maxPx);

            for (ImageCluster.Blob blob : blobs) {
                if (blob.width() < 8 || blob.width() > 500) continue;
                float ar = blob.aspectRatio();
                if (ar < minAr || ar > maxAr) continue;
                if (!isMetin && blob.density < Constants.MOB_MIN_DENSITY) continue;

                int clickX = blob.centerX * scale;
                int clickY = Math.min((blob.y2 + clickOff / scale) * scale, k.screenH - 10);

                if (!isDuplicate(results, clickX, clickY, mergeR * scale)) {
                    results.add(new Entity(clickX, clickY,
                            blob.width() * scale, blob.height() * scale,
                            blob.pixelCount,
                            isMetin ? "metin" : "mob"));
                }
            }

            // Sort by distance to character centre (nearest first)
            sortByDistanceToCharacter(results, k.charCenterX, k.charCenterY);
        } catch (Exception e) {
            Log.e(TAG, isMetin ? "findMetin" : "findMonsters", e);
        }
        return results;
    }

    /* ================================================================== */
    /*  Item-drop detection                                               */
    /* ================================================================== */

    public static List<Entity> findItems(ScreenCaptureService cap,
                                          CoordManager k,
                                          final AdaptiveColorLearner learner) {
        List<Entity> results = new ArrayList<>();
        try {
            Bitmap bmp = cap.getLatestBitmap();
            if (bmp == null || bmp.isRecycled()) return results;

            int bw = bmp.getWidth(), bh = bmp.getHeight();
            int scale = cap.getCaptureScale();

            Rect area = new Rect(
                    clamp(k.itemX1 / scale, 0, bw - 1),
                    clamp(k.itemY1 / scale, 0, bh - 1),
                    clamp(k.itemX2 / scale, 0, bw - 1),
                    clamp(k.itemY2 / scale, 0, bh - 1));
            if (area.width() <= 0 || area.height() <= 0) return results;

            int step = Math.max(2, Math.min(area.width(), area.height()) / 100);

            boolean[][] mask = ImageCluster.createMask(bmp, area,
                    new ImageCluster.PixelMatcher() {
                        @Override
                        public boolean matches(int r, int g, int b) {
                            if (learner != null && learner.isLearned())
                                return learner.isItemPixel(r, g, b);
                            return ColorUtils.isWhiteRange(r, g, b)
                                    || ColorUtils.isYellowRange(r, g, b);
                        }
                    }, step);

            List<ImageCluster.Blob> blobs = ImageCluster.findBlobs(bmp, area, mask, step,
                    Constants.BLOB_MIN_ITEM_PX, Constants.BLOB_MAX_ITEM_PX);

            for (ImageCluster.Blob blob : blobs) {
                if (blob.width() < 5 || blob.height() < 5) continue;
                int clickX = blob.centerX * scale;
                int clickY = blob.centerY * scale;

                if (!isDuplicate(results, clickX, clickY,
                        Constants.ENTITY_MERGE_RADIUS_ITEM * scale)) {
                    results.add(new Entity(clickX, clickY,
                            blob.width() * scale, blob.height() * scale,
                            blob.pixelCount, "item"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "findItems", e);
        }
        return results;
    }

    /* ================================================================== */
    /*  GM detection                                                      */
    /* ================================================================== */

    /**
     * Scans the upper-centre of the screen for GM-specific colour patterns
     * (purple / bright yellow name tags that are wider than normal mob names).
     */
    public static boolean detectGM(ScreenCaptureService cap, CoordManager k) {
        try {
            Bitmap bmp = cap.getLatestBitmap();
            if (bmp == null || bmp.isRecycled()) return false;

            int bw = bmp.getWidth(), bh = bmp.getHeight();
            Rect area = new Rect(bw / 4, bh / 6, 3 * bw / 4, bh / 2);
            int step = Math.max(2, area.width() / 100);

            boolean[][] mask = ImageCluster.createMask(bmp, area,
                    new ImageCluster.PixelMatcher() {
                        @Override
                        public boolean matches(int r, int g, int b) {
                            return ColorUtils.isPurpleRange(r, g, b)
                                    || (r > 200 && g > 180 && b < 60
                                        && ColorUtils.isYellowRange(r, g, b));
                        }
                    }, step);

            List<ImageCluster.Blob> blobs =
                    ImageCluster.findBlobs(bmp, area, mask, step, 10, 0);
            for (ImageCluster.Blob blob : blobs) {
                if (blob.pixelCount > 15 && blob.aspectRatio() > 2.0f) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /* ================================================================== */
    /*  Target-death detection                                            */
    /* ================================================================== */

    /**
     * Checks whether the previously-targeted entity has disappeared from
     * the screen by examining the area around its last known name position.
     */
    public static boolean isTargetDead(ScreenCaptureService cap,
                                        Entity lastTarget,
                                        CoordManager k,
                                        AdaptiveColorLearner learner) {
        if (lastTarget == null) return true;
        try {
            Bitmap bmp = cap.getLatestBitmap();
            if (bmp == null || bmp.isRecycled()) return false;

            int bw = bmp.getWidth(), bh = bmp.getHeight();
            int scale = cap.getCaptureScale();

            int checkX = lastTarget.clickX / scale;
            int checkY = (lastTarget.clickY - 40) / scale;
            int range  = 30;

            int namePixels = 0, total = 0;
            for (int dy = -range; dy <= range; dy += 3) {
                for (int dx = -range; dx <= range; dx += 3) {
                    int px = clamp(checkX + dx, 0, bw - 1);
                    int py = clamp(checkY + dy, 0, bh - 1);
                    int pixel = bmp.getPixel(px, py);
                    int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
                    total++;
                    if (learner != null && learner.isLearned()) {
                        if (learner.isMobNamePixel(r, g, b)) namePixels++;
                    } else {
                        if (ColorUtils.isRedRange(r, g, b)) namePixels++;
                    }
                }
            }
            return total > 0
                    && (namePixels * 100 / total) < Constants.TARGET_DEAD_THRESHOLD_PERCENT;
        } catch (Exception e) {
            return false;
        }
    }

    /* ================================================================== */
    /*  Internal helpers                                                  */
    /* ================================================================== */

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(val, max));
    }

    private static boolean isDuplicate(List<Entity> list, int x, int y, int radius) {
        for (Entity e : list) {
            if (Math.abs(e.clickX - x) < radius && Math.abs(e.clickY - y) < radius)
                return true;
        }
        return false;
    }

    private static void sortByDistanceToCharacter(List<Entity> list,
                                                    final int cx, final int cy) {
        if (list.size() <= 1) return;
        Collections.sort(list, new Comparator<Entity>() {
            @Override
            public int compare(Entity a, Entity b) {
                long da = (long)(a.clickX - cx) * (a.clickX - cx)
                        + (long)(a.clickY - cy) * (a.clickY - cy);
                long db = (long)(b.clickX - cx) * (b.clickX - cx)
                        + (long)(b.clickY - cy) * (b.clickY - cy);
                return Long.compare(da, db);
            }
        });
    }
}
