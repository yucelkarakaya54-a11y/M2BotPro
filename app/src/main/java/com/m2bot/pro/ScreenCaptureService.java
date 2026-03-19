package com.m2bot.pro;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Foreground service that captures the screen via {@link MediaProjection} and
 * exposes the latest frame to the rest of the app through
 * {@link #getLatestBitmap()}.
 * <p>
 * <b>Improvements over the original v5 code:</b>
 * <ul>
 *   <li>Proper synchronisation on the frame double-buffer.</li>
 *   <li>Bitmap cropping only when the row-stride differs from the logical width
 *       (avoids unnecessary allocations).</li>
 *   <li>Scene-change detection uses {@code long} arithmetic to prevent int overflow
 *       on large screens.</li>
 * </ul>
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = Constants.APP_TAG + ".Capture";

    // Singleton reference -- safe because only one instance is ever alive.
    private static volatile ScreenCaptureService sInstance;

    /* ---- Projection plumbing ---- */
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    /* ---- Frame double-buffer ---- */
    private final Object frameLock = new Object();
    private Bitmap latestFrame;
    private Bitmap previousFrame;

    /* ---- Background capture thread ---- */
    private HandlerThread captureThread;
    private Handler captureHandler;

    /* ---- Metrics ---- */
    private volatile boolean active;
    private int screenW, screenH, density;
    private int captureScale = 1;
    private long lastFrameTime;
    private volatile long frameCount;

    /* ================================================================== */
    /*  Lifecycle                                                         */
    /* ================================================================== */

    public static ScreenCaptureService getInstance() { return sInstance; }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        captureThread = new HandlerThread("M2CaptureThread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        density = dm.densityDpi;
        if (screenW < screenH) { int t = screenW; screenW = screenH; screenH = t; }

        if (screenW > Constants.CAPTURE_DOWNSCALE_THRESHOLD) {
            captureScale = 2;
        }

        Log.d(TAG, "Screen " + screenW + "x" + screenH + "  scale=" + captureScale);
        showForegroundNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");

        if (resultCode == Activity.RESULT_OK && data != null) {
            MediaProjectionManager mgr =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mgr != null) {
                projection = mgr.getMediaProjection(resultCode, data);
                if (projection != null) startCapture();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        active = false;
        releaseProjection();
        synchronized (frameLock) {
            recycleBitmap(latestFrame);   latestFrame = null;
            recycleBitmap(previousFrame); previousFrame = null;
        }
        if (captureThread != null) captureThread.quitSafely();
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /* ================================================================== */
    /*  Public API                                                        */
    /* ================================================================== */

    /** Returns the most recent captured frame, or {@code null}. */
    public Bitmap getLatestBitmap() {
        synchronized (frameLock) {
            return (latestFrame != null && !latestFrame.isRecycled()) ? latestFrame : null;
        }
    }

    /** Returns the frame before the latest, or {@code null}. */
    public Bitmap getPreviousBitmap() {
        synchronized (frameLock) {
            return (previousFrame != null && !previousFrame.isRecycled()) ? previousFrame : null;
        }
    }

    public boolean isActive()       { return active; }
    public long    getFrameCount()  { return frameCount; }
    public int     getCaptureScale(){ return captureScale; }

    /**
     * Compares the latest and previous frames via sparse sampling.
     * Returns {@code true} when the scene has changed significantly.
     */
    public boolean hasSceneChanged() {
        Bitmap cur, prev;
        synchronized (frameLock) {
            cur  = latestFrame;
            prev = previousFrame;
        }
        if (cur == null || prev == null || cur.isRecycled() || prev.isRecycled()) return true;
        if (cur.getWidth() != prev.getWidth() || cur.getHeight() != prev.getHeight()) return true;

        int w = cur.getWidth(), h = cur.getHeight();
        int stepX = Math.max(1, w / Constants.SCENE_SAMPLE_COLS);
        int stepY = Math.max(1, h / Constants.SCENE_SAMPLE_ROWS);
        int diffCount = 0, sampleCount = 0;

        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int p1 = cur.getPixel(x, y);
                int p2 = prev.getPixel(x, y);
                sampleCount++;
                if (Math.abs(((p1 >> 16) & 0xFF) - ((p2 >> 16) & 0xFF)) > Constants.SCENE_CHANNEL_THRESHOLD
                 || Math.abs(((p1 >>  8) & 0xFF) - ((p2 >>  8) & 0xFF)) > Constants.SCENE_CHANNEL_THRESHOLD
                 || Math.abs(( p1        & 0xFF) - ( p2        & 0xFF)) > Constants.SCENE_CHANNEL_THRESHOLD) {
                    diffCount++;
                }
            }
        }
        return sampleCount > 0 && (diffCount * 100 / sampleCount) > Constants.SCENE_CHANGE_PERCENT;
    }

    /* ================================================================== */
    /*  Capture internals                                                 */
    /* ================================================================== */

    private void startCapture() {
        try {
            int cw = screenW / captureScale;
            int ch = screenH / captureScale;
            imageReader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888,
                    Constants.CAPTURE_BUFFER_SIZE);

            virtualDisplay = projection.createVirtualDisplay(
                    "M2Bot", cw, ch, density / captureScale,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, captureHandler);

            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    processFrame(reader);
                }
            }, captureHandler);

            Log.d(TAG, "Capture started " + cw + "x" + ch);
        } catch (Exception e) {
            Log.e(TAG, "startCapture failed", e);
        }
    }

    private void processFrame(ImageReader reader) {
        long now = System.currentTimeMillis();
        // Throttle to avoid unnecessary work
        if (now - lastFrameTime < Constants.CAPTURE_MIN_INTERVAL_MS) {
            Image img = null;
            try { img = reader.acquireLatestImage(); } catch (Exception ignored) {}
            if (img != null) img.close();
            return;
        }
        lastFrameTime = now;

        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int rowStride   = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();
            int bmpWidth    = rowStride / pixelStride;
            int cw = screenW / captureScale;
            int ch = screenH / captureScale;

            Bitmap bmp = Bitmap.createBitmap(bmpWidth, ch, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buffer);

            // Crop padding when row-stride exceeds logical width
            if (bmp.getWidth() > cw) {
                Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, cw, ch);
                bmp.recycle();
                bmp = cropped;
            }

            synchronized (frameLock) {
                recycleBitmap(previousFrame);
                previousFrame = latestFrame;
                latestFrame = bmp;
            }
            active = true;
            frameCount++;
        } catch (Exception e) {
            Log.e(TAG, "processFrame", e);
        } finally {
            if (image != null) {
                try { image.close(); } catch (Exception ignored) {}
            }
        }
    }

    /* ================================================================== */
    /*  Notification                                                      */
    /* ================================================================== */

    @SuppressWarnings("deprecation")
    private void showForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    Constants.CHANNEL_CAPTURE,
                    getString(R.string.channel_capture_name),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, Constants.CHANNEL_CAPTURE);
        } else {
            nb = new Notification.Builder(this);
        }

        Notification n = nb
                .setContentTitle("M2 Bot - Screen Capture")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        startForeground(Constants.NOTIF_CAPTURE_ID, n);
    }

    /* ================================================================== */
    /*  Cleanup helpers                                                   */
    /* ================================================================== */

    private void releaseProjection() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); }          catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); }       catch (Exception ignored) {}
    }

    private static void recycleBitmap(Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
    }
}
