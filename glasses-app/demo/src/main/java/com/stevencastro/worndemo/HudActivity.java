package com.stevencastro.worndemo;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Glanceable HUD demo for the M6 glasses (640x480, Android 9).
 * Five cards, one per smart-glasses use case. Swipe forward/back on the
 * temple touchpad (or press DPAD left/right) to change cards; tap (or
 * DPAD center / Enter) to act on the current card.
 * Black pixels are see-through, so everything is bright text on black.
 */
public class HudActivity extends Activity {
    private static final String TAG = "WornHud";
    private static final int GREEN = Color.rgb(0, 255, 140);
    private static final int CYAN = Color.rgb(0, 200, 255);
    private static final int AMBER = Color.rgb(255, 190, 0);
    private static final int DIM = Color.rgb(120, 120, 120);
    private static final float SWIPE_MIN_PX = 60;

    private static final String[] CARD_NAMES = {"GLANCE", "NAVIGATE", "CAPTIONS", "NOTES", "FOCUS"};

    // Simulated walking route to the hackathon venue.
    private static final String[][] ROUTE = {
            {"↑", "Head north on Jay St", "120 m"},
            {"→", "Turn right on Tillary St", "80 m"},
            {"←", "Turn left into City Tech", "40 m"},
            {"●", "Arrived: 285 Jay St", ""},
    };

    // Scripted conversation: what was heard, and its live translation.
    private static final String[][] CAPTIONS = {
            {"ES", "¿Dónde está la sala de demos?", "Where is the demo room?"},
            {"ES", "Está en el segundo piso.", "It's on the second floor."},
            {"FR", "Votre démo commence à 14h15.", "Your demo starts at 2:15 PM."},
            {"JA", "がんばってください！", "Good luck!"},
    };

    private static final String[] NOTES = {
            "Problem: phones pull your eyes down",
            "Idea: AI that lives at eye level",
            "Demo: glance, navigate, caption",
            "Ask: try it on — it disappears",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM d", Locale.US);

    private TextView header;
    private TextView big;
    private TextView line1;
    private TextView line2;
    private TextView footer;

    private int card = 0;
    private int routeStep = 0;
    private int captionIndex = 0;
    private int noteIndex = 0;
    private int focusSecondsLeft = 25 * 60;
    private boolean focusRunning = false;
    private float downX;
    private float downY;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (focusRunning && focusSecondsLeft > 0) {
                focusSecondsLeft--;
                if (focusSecondsLeft == 0) focusRunning = false;
            }
            render();
            handler.postDelayed(this, 1000);
        }
    };

    // Captions play on their own, like a live conversation.
    private final Runnable nextCaption = new Runnable() {
        @Override
        public void run() {
            captionIndex = (captionIndex + 1) % CAPTIONS.length;
            render();
            handler.postDelayed(this, 4000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(24, 16, 24, 16);

        header = label(14, DIM, true);
        big = label(56, GREEN, true);
        line1 = label(22, Color.WHITE, false);
        line2 = label(20, CYAN, false);
        footer = label(12, DIM, false);
        root.addView(header);
        root.addView(big);
        root.addView(line1);
        root.addView(line2);
        root.addView(footer);

        setContentView(root);
    }

    private TextView label(int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 6, 0, 6);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
        handler.postDelayed(nextCaption, 4000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(nextCaption);
        super.onPause();
    }

    private void render() {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < CARD_NAMES.length; i++) dots.append(i == card ? "● " : "○ ");
        header.setText(CARD_NAMES[card] + "   " + dots.toString().trim());
        big.setTextColor(GREEN);

        switch (card) {
            case 0: {
                Date now = new Date();
                big.setText(timeFormat.format(now));
                line1.setText(dateFormat.format(now));
                line2.setText("Battery " + batteryPercent() + "%");
                footer.setText("Swipe to browse · Tap to act");
                break;
            }
            case 1: {
                String[] step = ROUTE[routeStep];
                big.setText(step[0]);
                line1.setText(step[1]);
                line2.setText(step[2]);
                footer.setText("Tap: next step (simulated route)");
                break;
            }
            case 2: {
                String[] c = CAPTIONS[captionIndex];
                big.setTextColor(AMBER);
                big.setText(c[0] + " → EN");
                line1.setText(c[2]);
                line2.setText(c[1]);
                footer.setText("Live translation (scripted demo)");
                break;
            }
            case 3: {
                big.setText((noteIndex + 1) + "/" + NOTES.length);
                line1.setText(NOTES[noteIndex]);
                line2.setText(noteIndex + 1 < NOTES.length ? "Next: " + NOTES[noteIndex + 1] : "End of notes");
                footer.setText("Tap: next note");
                break;
            }
            default: {
                big.setTextColor(focusRunning ? GREEN : AMBER);
                big.setText(String.format(Locale.US, "%02d:%02d", focusSecondsLeft / 60, focusSecondsLeft % 60));
                line1.setText(focusSecondsLeft == 0 ? "Done. Take a break." : focusRunning ? "Focusing" : "Paused");
                line2.setText("");
                footer.setText("Tap: start / pause · Long-press: reset");
                break;
            }
        }
    }

    private int batteryPercent() {
        Intent status = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (status == null) return -1;
        int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return Math.round(100f * level / scale);
    }

    private void move(int delta) {
        card = (card + delta + CARD_NAMES.length) % CARD_NAMES.length;
        Log.i(TAG, "card " + CARD_NAMES[card]);
        render();
    }

    private void act() {
        switch (card) {
            case 1:
                routeStep = (routeStep + 1) % ROUTE.length;
                break;
            case 2:
                handler.removeCallbacks(nextCaption);
                nextCaption.run();
                return;
            case 3:
                noteIndex = (noteIndex + 1) % NOTES.length;
                break;
            case 4:
                if (focusSecondsLeft == 0) focusSecondsLeft = 25 * 60;
                focusRunning = !focusRunning;
                break;
            default:
                break;
        }
        Log.i(TAG, "act on " + CARD_NAMES[card]);
        render();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                long held = event.getEventTime() - event.getDownTime();
                if (Math.abs(dx) >= SWIPE_MIN_PX && Math.abs(dx) > Math.abs(dy)) {
                    move(dx > 0 ? 1 : -1);
                } else if (Math.abs(dy) >= SWIPE_MIN_PX) {
                    move(dy > 0 ? 1 : -1);
                } else if (held > 600 && card == 4) {
                    focusRunning = false;
                    focusSecondsLeft = 25 * 60;
                    render();
                } else {
                    act();
                }
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.i(TAG, "key " + KeyEvent.keyCodeToString(keyCode));
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                act();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
