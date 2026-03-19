package com.m2bot.pro;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Flood-fill based pixel clustering engine.
 * <p>
 * Given a binary mask (built from a {@link PixelMatcher}), this class performs
 * 8-connected BFS to find contiguous blobs, computes their bounding boxes,
 * average colour, and density, then provides sorting helpers so callers can
 * pick the "best" blob by distance or size.
 */
public final class ImageCluster {

    private ImageCluster() { /* utility class */ }

    // 8-connected neighbourhood offsets
    private static final int[][] DIRS = {
        {-1, 0}, {1, 0}, {0, -1}, {0, 1},
        {-1, -1}, {1, -1}, {-1, 1}, {1, 1}
    };

    /* ================================================================== */
    /*  Blob data class                                                   */
    /* ================================================================== */

    /** Represents a connected region of matching pixels. */
    public static final class Blob {
        public final int x1, y1, x2, y2;
        public final int pixelCount;
        public final int centerX, centerY;
        public final int avgR, avgG, avgB;
        public final float density;

        public Blob(int x1, int y1, int x2, int y2,
                    int count, long sumR, long sumG, long sumB) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.pixelCount = count;
            this.centerX = (x1 + x2) / 2;
            this.centerY = (y1 + y2) / 2;
            this.avgR = count > 0 ? (int) (sumR / count) : 0;
            this.avgG = count > 0 ? (int) (sumG / count) : 0;
            this.avgB = count > 0 ? (int) (sumB / count) : 0;
            int w = x2 - x1 + 1;
            int h = y2 - y1 + 1;
            this.density = (w * h > 0) ? (float) count / (w * h) : 0.0f;
        }

        public int width()  { return x2 - x1 + 1; }
        public int height() { return y2 - y1 + 1; }

        public float aspectRatio() {
            int h = height();
            return h > 0 ? (float) width() / h : 0.0f;
        }
    }

    /* ================================================================== */
    /*  Pixel matcher interface                                           */
    /* ================================================================== */

    /** Predicate deciding whether an RGB pixel belongs to the target set. */
    public interface PixelMatcher {
        boolean matches(int r, int g, int b);
    }

    /* ================================================================== */
    /*  Mask creation                                                     */
    /* ================================================================== */

    /**
     * Builds a downsampled boolean mask for a rectangular region of the bitmap.
     *
     * @param bmp     source bitmap (never modified)
     * @param region  rectangle in bitmap coordinates
     * @param matcher pixel predicate
     * @param step    sampling stride in pixels (1 = every pixel)
     * @return 2-D boolean array sized {@code [rows][cols]}
     */
    public static boolean[][] createMask(Bitmap bmp, Rect region,
                                          PixelMatcher matcher, int step) {
        int rw = (region.width()  + step - 1) / step;
        int rh = (region.height() + step - 1) / step;
        boolean[][] mask = new boolean[rh][rw];
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();

        for (int my = 0; my < rh; my++) {
            int py = region.top + my * step;
            if (py >= bh) continue;
            for (int mx = 0; mx < rw; mx++) {
                int px = region.left + mx * step;
                if (px >= bw) continue;
                int pixel = bmp.getPixel(px, py);
                mask[my][mx] = matcher.matches(
                        Color.red(pixel), Color.green(pixel), Color.blue(pixel));
            }
        }
        return mask;
    }

    /* ================================================================== */
    /*  Blob detection via BFS                                            */
    /* ================================================================== */

    /**
     * Finds all connected blobs in the mask whose pixel count is within
     * [{@code minPixels}, {@code maxPixels}].
     *
     * @param bmp       source bitmap (used for average-colour computation)
     * @param region    the region that was used to build the mask
     * @param mask      binary mask from {@link #createMask}
     * @param step      same stride that was used for mask creation
     * @param minPixels minimum blob size (inclusive)
     * @param maxPixels maximum blob size (inclusive, 0 = unlimited)
     */
    public static List<Blob> findBlobs(Bitmap bmp, Rect region,
                                        boolean[][] mask, int step,
                                        int minPixels, int maxPixels) {
        List<Blob> blobs = new ArrayList<>();
        int rh = mask.length;
        if (rh == 0) return blobs;
        int rw = mask[0].length;
        boolean[][] visited = new boolean[rh][rw];

        for (int sy = 0; sy < rh; sy++) {
            for (int sx = 0; sx < rw; sx++) {
                if (!mask[sy][sx] || visited[sy][sx]) continue;

                // BFS flood-fill from (sx, sy)
                int minX = sx, minY = sy, maxX = sx, maxY = sy;
                int count = 0;
                long sumR = 0, sumG = 0, sumB = 0;

                Queue<int[]> queue = new LinkedList<>();
                queue.add(new int[]{sx, sy});
                visited[sy][sx] = true;

                while (!queue.isEmpty()) {
                    int[] pos = queue.poll();
                    int cx = pos[0];
                    int cy = pos[1];
                    count++;

                    if (cx < minX) minX = cx;
                    if (cy < minY) minY = cy;
                    if (cx > maxX) maxX = cx;
                    if (cy > maxY) maxY = cy;

                    // Accumulate colour from the original bitmap
                    int px = region.left + cx * step;
                    int py = region.top  + cy * step;
                    if (px < bmp.getWidth() && py < bmp.getHeight()) {
                        int pixel = bmp.getPixel(px, py);
                        sumR += Color.red(pixel);
                        sumG += Color.green(pixel);
                        sumB += Color.blue(pixel);
                    }

                    // Explore 8-connected neighbours
                    for (int[] d : DIRS) {
                        int nx = cx + d[0];
                        int ny = cy + d[1];
                        if (nx >= 0 && nx < rw && ny >= 0 && ny < rh
                                && !visited[ny][nx] && mask[ny][nx]) {
                            visited[ny][nx] = true;
                            queue.add(new int[]{nx, ny});
                        }
                    }
                }

                if (count >= minPixels && (maxPixels <= 0 || count <= maxPixels)) {
                    blobs.add(new Blob(
                            region.left + minX * step,
                            region.top  + minY * step,
                            region.left + maxX * step,
                            region.top  + maxY * step,
                            count, sumR, sumG, sumB));
                }
            }
        }
        return blobs;
    }

    /* ================================================================== */
    /*  Sorting helpers                                                   */
    /* ================================================================== */

    /** Sorts blobs by Euclidean distance from ({@code cx}, {@code cy}), ascending. */
    public static void sortByDistance(List<Blob> blobs, final int cx, final int cy) {
        Collections.sort(blobs, new Comparator<Blob>() {
            @Override
            public int compare(Blob a, Blob b) {
                long da = (long)(a.centerX - cx) * (a.centerX - cx)
                        + (long)(a.centerY - cy) * (a.centerY - cy);
                long db = (long)(b.centerX - cx) * (b.centerX - cx)
                        + (long)(b.centerY - cy) * (b.centerY - cy);
                return Long.compare(da, db);
            }
        });
    }

    /** Sorts blobs by pixel count, descending (largest first). */
    public static void sortBySize(List<Blob> blobs) {
        Collections.sort(blobs, new Comparator<Blob>() {
            @Override
            public int compare(Blob a, Blob b) {
                return Integer.compare(b.pixelCount, a.pixelCount);
            }
        });
    }
}
