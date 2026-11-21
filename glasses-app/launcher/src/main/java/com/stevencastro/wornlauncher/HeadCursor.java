package com.stevencastro.wornlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.Surface;

/**
 * Turns gyroscope rates into a screen cursor. The M6 glasses have a raw
 * gyro but no rotation-vector sensor, so this integrates angular velocity:
 * turning the head left/right moves the cursor horizontally, looking
 * up/down moves it vertically.
 *
 * Gyro rates are first remapped from the device frame into the screen frame
 * using the display rotation, then a per-device calibration picks which
 * screen axis (and which sign) drives each cursor direction. Calibration is
 * a short guided flow: hold still, turn left, look up. It's saved, so it only
 * runs once per device.
 *
 * The cursor is clamped to the screen, which doubles as drift correction:
 * if it drifts, turn past the edge and come back.
 */
public class HeadCursor implements SensorEventListener {
    public interface Listener {
        void onCursorMoved(float x, float y);

        /** Calibration prompt and 0..1 progress for the current step. */
        void onCalibrationStep(String prompt, float progress);

        void onCalibrationDone();
    }

    private static final String TAG = "HeadCursor";
    private static final String PREFS = "head_cal";

    // Pixels of cursor travel per radian of head rotation. 900 px/rad means
    // about 40 degrees of head turn crosses the 640 px screen.
    private static final float GAIN_X = 900f;
    // Vertical is 20% more sensitive than horizontal.
    private static final float GAIN_Y = 1080f;

    // Rates below this (rad/s) are treated as stillness, which hides gyro
    // noise and slow bias drift.
    private static final float DEADZONE = 0.03f;
    // While still, the bias estimate creeps toward the measured rate.
    private static final float BIAS_RATE = 0.02f;
    // The M6 gyro reads ~0.09 rad/s on one axis at rest, so "still" must allow that.
    private static final float STILL_LIMIT = 0.2f;
    // Samples averaged at startup to seed the bias before the cursor moves.
    private static final int SEED_SAMPLES = 25;
    // Low-pass factor on rates (1 = no smoothing).
    private static final float SMOOTH = 0.5f;

    // Calibration: how long to hold still, how far a turn must go (~17 deg),
    // and how long to pause between the left turn and looking up.
    private static final float CAL_STILL_S = 1.0f;
    private static final float CAL_TURN_RAD = 0.3f;
    private static final float CAL_PAUSE_S = 1.5f;


    private static final float NS_TO_S = 1e-9f;

    private enum Cal { OFF, STILL, LEFT, PAUSE, UP }

    private final SensorManager sensors;
    private final Sensor gyro;
    private final Listener listener;
    private final SharedPreferences prefs;

    // Which screen-frame axis (0 = about screen X, 1 = about screen Y) and
    // sign drive each cursor direction. Defaults suit a landscape-native
    // device with the gyro aligned to the screen.
    private int yawAxis = 1;
    private float yawSign = -1f;
    private int pitchAxis = 0;
    private float pitchSign = -1f;

    private int rotation = Surface.ROTATION_0;
    private float width;
    private float height;
    private float x;
    private float y;
    private final float[] bias = new float[2];
    private final float[] rate = new float[2];
    private final float[] s = new float[2];
    private long lastNs;
    private int seeded;
    private final float[] seed = new float[2];
    private boolean logRaw;
    private long lastLogNs;


    private Cal cal = Cal.OFF;
    private float calTime;
    private int calSamples;
    private final float[] calSum = new float[2];
    private String calPrompt;

    public HeadCursor(Context context, SensorManager sensors, Listener listener) {
        this.sensors = sensors;
        this.gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        this.listener = listener;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean("calibrated", false)) {
            yawAxis = prefs.getInt("yawAxis", yawAxis);
            yawSign = prefs.getFloat("yawSign", yawSign);
            pitchAxis = prefs.getInt("pitchAxis", pitchAxis);
            pitchSign = prefs.getFloat("pitchSign", pitchSign);
        }
    }

    public boolean available() {
        return gyro != null;
    }

    public boolean isCalibrated() {
        return prefs.getBoolean("calibrated", false);
    }

    public boolean isCalibrating() {
        return cal != Cal.OFF;
    }

    /** Logs raw and remapped gyro values to logcat (tag HeadCursor). */
    public void setLogRaw(boolean logRaw) {
        this.logRaw = logRaw;
    }

    /** Display rotation (Surface.ROTATION_*), used to remap gyro axes. */
    public void setRotation(int rotation) {
        this.rotation = rotation;
    }

    public void setBounds(float width, float height) {
        boolean first = this.width == 0;
        this.width = width;
        this.height = height;
        if (first) {
            recenter();
        } else {
            clamp();
        }
    }

    public void recenter() {
        x = width / 2f;
        y = height / 2f;
        rate[0] = 0;
        rate[1] = 0;
        listener.onCursorMoved(x, y);
    }

    public void start() {
        if (gyro == null) return;
        lastNs = 0;
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        sensors.unregisterListener(this);
    }

    public void startCalibration() {
        if (gyro == null) return;
        enterCal(Cal.STILL, "Hold still");
    }

    /** Abandons calibration, keeping whatever mapping was in use before. */
    public void cancelCalibration() {
        if (cal == Cal.OFF) return;
        cal = Cal.OFF;
        listener.onCalibrationDone();
    }

    private void enterCal(Cal next, String prompt) {
        cal = next;
        calTime = 0;
        calSamples = 0;
        calSum[0] = 0;
        calSum[1] = 0;
        calPrompt = prompt;
        listener.onCalibrationStep(prompt, 0f);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float gx = event.values[0];
        float gy = event.values[1];
        long now = event.timestamp;

        // Device frame -> screen frame for the current display rotation.
        switch (rotation) {
            case Surface.ROTATION_90:
                s[0] = -gy;
                s[1] = gx;
                break;
            case Surface.ROTATION_180:
                s[0] = -gx;
                s[1] = -gy;
                break;
            case Surface.ROTATION_270:
                s[0] = gy;
                s[1] = -gx;
                break;
            default:
                s[0] = gx;
                s[1] = gy;
        }

        if (logRaw && now - lastLogNs > 200_000_000L) {
            lastLogNs = now;
            Log.d(TAG, String.format("raw gx=%.3f gy=%.3f gz=%.3f rot=%d screen sx=%.3f sy=%.3f",
                    gx, gy, event.values[2], rotation, s[0], s[1]));
        }

        if (lastNs == 0) {
            lastNs = now;
            return;
        }
        float dt = (now - lastNs) * NS_TO_S;
        lastNs = now;
        // Skip gaps (sensor paused, app resumed) so the cursor doesn't jump.
        if (dt <= 0 || dt > 0.1f) return;

        if (cal != Cal.OFF) {
            calibrate(dt);
            return;
        }

        // First run: average a burst of samples to seed the bias. Kept across
        // pause/resume since the bias barely changes.
        if (seeded < SEED_SAMPLES) {
            seed[0] += s[0];
            seed[1] += s[1];
            if (++seeded == SEED_SAMPLES) {
                bias[0] = seed[0] / SEED_SAMPLES;
                bias[1] = seed[1] / SEED_SAMPLES;
            }
            return;
        }

        // Track gyro bias while the head is roughly still.
        if (Math.abs(s[0] - bias[0]) < STILL_LIMIT && Math.abs(s[1] - bias[1]) < STILL_LIMIT) {
            bias[0] += (s[0] - bias[0]) * BIAS_RATE;
            bias[1] += (s[1] - bias[1]) * BIAS_RATE;
        }

        rate[0] += (deadzone(s[0] - bias[0]) - rate[0]) * SMOOTH;
        rate[1] += (deadzone(s[1] - bias[1]) - rate[1]) * SMOOTH;


        float nx = x + yawSign * rate[yawAxis] * GAIN_X * dt;
        float ny = y + pitchSign * rate[pitchAxis] * GAIN_Y * dt;
        if (nx != x || ny != y) {
            x = nx;
            y = ny;
            clamp();
            listener.onCursorMoved(x, y);
        }
    }

    private void calibrate(float dt) {
        calTime += dt;
        switch (cal) {
            case STILL: {
                calSum[0] += s[0];
                calSum[1] += s[1];
                calSamples++;
                if (calTime >= CAL_STILL_S) {
                    bias[0] = calSum[0] / calSamples;
                    bias[1] = calSum[1] / calSamples;
                    seeded = SEED_SAMPLES;
                    enterCal(Cal.LEFT, "Turn your head LEFT");
                } else {
                    listener.onCalibrationStep(calPrompt, calTime / CAL_STILL_S);
                }
                break;
            }
            case LEFT: {
                // Integrate angle turned on both screen axes.
                calSum[0] += (s[0] - bias[0]) * dt;
                calSum[1] += (s[1] - bias[1]) * dt;
                int axis = Math.abs(calSum[0]) > Math.abs(calSum[1]) ? 0 : 1;
                float turned = Math.abs(calSum[axis]);
                if (turned >= CAL_TURN_RAD) {
                    yawAxis = axis;
                    // A left turn must move the cursor left (negative x).
                    yawSign = calSum[axis] > 0 ? -1f : 1f;
                    Log.i(TAG, "calibrated yaw: axis=" + yawAxis + " sign=" + yawSign);
                    enterCal(Cal.PAUSE, "Back to center");
                } else {
                    listener.onCalibrationStep(calPrompt, turned / CAL_TURN_RAD);
                }
                break;
            }
            case PAUSE:
                if (calTime >= CAL_PAUSE_S) {
                    enterCal(Cal.UP, "Look UP");
                } else {
                    listener.onCalibrationStep(calPrompt, calTime / CAL_PAUSE_S);
                }
                break;
            case UP: {
                calSum[0] += (s[0] - bias[0]) * dt;
                calSum[1] += (s[1] - bias[1]) * dt;
                int axis = Math.abs(calSum[0]) > Math.abs(calSum[1]) ? 0 : 1;
                float turned = Math.abs(calSum[axis]);
                if (turned >= CAL_TURN_RAD) {
                    if (axis == yawAxis) {
                        // Looked sideways instead of up; try this step again.
                        Log.i(TAG, "pitch step moved the yaw axis; retrying");
                        enterCal(Cal.PAUSE, "Back to center, then look UP");
                        return;
                    }
                    pitchAxis = axis;
                    // Looking up must move the cursor up (negative y).
                    pitchSign = calSum[axis] > 0 ? -1f : 1f;
                    Log.i(TAG, "calibrated pitch: axis=" + pitchAxis + " sign=" + pitchSign);
                    finishCalibration();
                } else {
                    listener.onCalibrationStep(calPrompt, turned / CAL_TURN_RAD);
                }
                break;
            }
            default:
                break;
        }
    }

    private void finishCalibration() {
        prefs.edit()
                .putBoolean("calibrated", true)
                .putInt("yawAxis", yawAxis)
                .putFloat("yawSign", yawSign)
                .putInt("pitchAxis", pitchAxis)
                .putFloat("pitchSign", pitchSign)
                .apply();
        cal = Cal.OFF;
        rate[0] = 0;
        rate[1] = 0;
        listener.onCalibrationDone();
    }

    private static float deadzone(float v) {
        if (v > DEADZONE) return v - DEADZONE;
        if (v < -DEADZONE) return v + DEADZONE;
        return 0f;
    }

    private void clamp() {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > width - 1) x = width - 1;
        if (y > height - 1) y = height - 1;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
