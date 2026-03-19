package com.m2bot.pro;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Launcher activity.
 * <p>
 * Requests the overlay and screen-capture permissions, starts both
 * foreground services ({@link ScreenCaptureService} and {@link BotOverlay}),
 * then finishes itself so the game can run in the foreground.
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_CAPTURE = 1002;

    /* ================================================================== */
    /*  Lifecycle                                                         */
    /* ================================================================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } else {
            requestScreenCapture();
        }
    }

    /* ================================================================== */
    /*  Permission results                                                */
    /* ================================================================== */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this)) {
                requestScreenCapture();
            } else {
                Toast.makeText(this, R.string.toast_overlay_required,
                        Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }

        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                launchServices(resultCode, data);
            } else {
                Toast.makeText(this, R.string.toast_capture_required,
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /* ================================================================== */
    /*  Internal helpers                                                  */
    /* ================================================================== */

    private void requestScreenCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        }
    }

    /**
     * Starts both foreground services and finishes the activity so the game
     * is back in the foreground.
     */
    private void launchServices(int resultCode, Intent data) {
        // 1. Screen-capture service
        Intent captureIntent = new Intent(this, ScreenCaptureService.class);
        captureIntent.putExtra("resultCode", resultCode);
        captureIntent.putExtra("data", data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(captureIntent);
        } else {
            startService(captureIntent);
        }

        // 2. Overlay service
        Intent overlayIntent = new Intent(this, BotOverlay.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent);
        } else {
            startService(overlayIntent);
        }

        Toast.makeText(this, R.string.toast_bot_started, Toast.LENGTH_SHORT).show();
        finish();
    }
}
