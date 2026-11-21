package com.stevencastro.wornlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Home screen for the M6 glasses that you drive with your head.
 *
 * Head movement moves the cursor; the empty middle of the grid is a rest
 * zone. Tap the temple touchpad (DPAD center) to open the highlighted app.
 * Looking at an app only highlights it; nothing opens without a tap.
 * The temple touchpad only knows left, right and tap: swipe left (back,
 * toward the ear) recenters the cursor, swipe right (forward, toward the
 * lens) jumps to the next page, wrapping after the last. Long-press the
 * touchpad to recalibrate head directions.
 *
 * The touchpad reports each gesture twice, as a key (DPAD left/right/
 * center) and as touch events. Keys win; a touch gesture only acts if no
 * key arrived around it, and each gesture fires once.
 *
 * From adb: `am start -n com.stevencastro.wornlauncher/.LauncherActivity
 * --ez calibrate true` starts calibration.
 */
public class LauncherActivity extends Activity
        implements HeadCursor.Listener, LauncherView.Callbacks {
    public static final String EXTRA_CALIBRATE = "calibrate";

    private static final float SWIPE_MIN_PX = 60;
    private static final long LONG_TOUCH_MS = 800;
    private static final long DONE_MESSAGE_MS = 1000;
    // Touch gestures wait this long for the matching key before acting.
    private static final long TOUCH_DEFER_MS = 150;
    // A second gesture within this window is the same swipe/tap reported twice.
    private static final long DUPLICATE_MS = 400;

    private enum Gesture { BACK, FORWARD, TAP, LONG }

    private LauncherView view;
    private HeadCursor cursor;
    private float downX;
    private float downY;
    private long downAt;
    private boolean calibrateOnResume;
    private long lastGestureAt;
    private long lastKeyAt;
    private Gesture pendingTouch;
    private final Runnable runPendingTouch = () -> {
        Gesture g = pendingTouch;
        pendingTouch = null;
        if (g != null) gesture(g, android.os.SystemClock.uptimeMillis());
    };

    private final Runnable clearCalibration = () -> {
        view.setCalibration(null, 0f);
        cursor.recenter();
        view.refreshHover();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        view = new LauncherView(this, this);
        setContentView(view);

        cursor = new HeadCursor(this, (SensorManager) getSystemService(SENSOR_SERVICE), this);
        // `adb shell setprop log.tag.HeadCursor DEBUG` turns on raw gyro logging.
        cursor.setLogRaw(Log.isLoggable("HeadCursor", Log.DEBUG));
        if (!cursor.available()) view.setStatus("No gyro: use touchpad");

        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            cursor.setBounds(r - l, b - t);
            view.refreshHover();
        });

        calibrateOnResume = !cursor.isCalibrated() || wantsCalibration(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (wantsCalibration(intent)) calibrateOnResume = true;
    }

    private static boolean wantsCalibration(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_CALIBRATE, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cursor.setRotation(getWindowManager().getDefaultDisplay().getRotation());
        view.setApps(loadApps());
        // Start (and come back from apps) in the rest zone.
        cursor.recenter();
        cursor.start();
        if (calibrateOnResume) {
            calibrateOnResume = false;
            startCalibration();
        }
    }

    @Override
    protected void onPause() {
        // Leaving mid-calibration keeps the old mapping.
        if (cursor.isCalibrating()) {
            cursor.cancelCalibration();
        }
        view.removeCallbacks(clearCalibration);
        view.removeCallbacks(runPendingTouch);
        pendingTouch = null;
        view.setCalibration(null, 0f);
        cursor.stop();
        super.onPause();
    }

    private void startCalibration() {
        if (!cursor.available()) return;
        view.removeCallbacks(clearCalibration);
        cursor.startCalibration();
    }

    private List<LauncherView.AppEntry> loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        List<LauncherView.AppEntry> apps = new ArrayList<>();
        for (ResolveInfo info : found) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            apps.add(new LauncherView.AppEntry(
                    String.valueOf(info.loadLabel(pm)),
                    pkg,
                    info.activityInfo.name,
                    info.loadIcon(pm)));
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    @Override
    public void onCursorMoved(float x, float y) {
        view.setCursor(x, y);
    }

    @Override
    public void onCalibrationStep(String prompt, float progress) {
        view.setCalibration(prompt, progress);
    }

    @Override
    public void onCalibrationDone() {
        view.setCalibration("Calibrated ✓", 1f);
        view.removeCallbacks(clearCalibration);
        view.postDelayed(clearCalibration, DONE_MESSAGE_MS);
    }

    /** Tap while calibrating skips it and keeps the previous mapping. */
    private void skipCalibration() {
        cursor.cancelCalibration();
        view.removeCallbacks(clearCalibration);
        clearCalibration.run();
    }

    @Override
    public void onLaunch(LauncherView.AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(app.packageName, app.activityName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            view.setStatus("Can't open " + app.label);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Act on key-up so a long press can mean "calibrate" instead.
                event.startTracking();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                keyGesture(Gesture.FORWARD, event);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                keyGesture(Gesture.BACK, event);
                return true;
            case KeyEvent.KEYCODE_BACK:
                // This is the home screen; there's nothing behind it.
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            keyGesture(Gesture.LONG, event);
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            boolean wasLong = (event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) != 0;
            if (!wasLong && !event.isCanceled()) keyGesture(Gesture.TAP, event);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** A key always acts (unless it repeats a gesture that just fired) and cancels any pending touch. */
    private void keyGesture(Gesture g, KeyEvent event) {
        view.removeCallbacks(runPendingTouch);
        pendingTouch = null;
        lastKeyAt = event.getEventTime();
        gesture(g, lastKeyAt);
    }

    private void gesture(Gesture g, long at) {
        if (at - lastGestureAt < DUPLICATE_MS) return;
        lastGestureAt = at;
        if (view.isCalibrating()) {
            // Any gesture other than a swipe skips calibration.
            if (g == Gesture.TAP || g == Gesture.LONG) skipCalibration();
            return;
        }
        switch (g) {
            case BACK:
                cursor.recenter();
                view.refreshHover();
                break;
            case FORWARD:
                view.flipPage(1);
                break;
            case TAP:
                view.selectHovered();
                break;
            case LONG:
                startCalibration();
                break;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downAt = event.getEventTime();
                return true;
            case MotionEvent.ACTION_UP: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                Gesture g;
                if (Math.abs(dx) >= SWIPE_MIN_PX && Math.abs(dx) >= Math.abs(dy)) {
                    g = dx < 0 ? Gesture.BACK : Gesture.FORWARD;
                } else if (Math.abs(dx) < SWIPE_MIN_PX && Math.abs(dy) < SWIPE_MIN_PX) {
                    g = event.getEventTime() - downAt >= LONG_TOUCH_MS ? Gesture.LONG : Gesture.TAP;
                } else {
                    return true;
                }
                // This touch already came through as a key, or just fired.
                if (lastKeyAt >= downAt || event.getEventTime() - lastGestureAt < DUPLICATE_MS) return true;
                // Give the touchpad's key for this gesture a moment to arrive first.
                pendingTouch = g;
                view.removeCallbacks(runPendingTouch);
                view.postDelayed(runPendingTouch, TOUCH_DEFER_MS);
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }
}
