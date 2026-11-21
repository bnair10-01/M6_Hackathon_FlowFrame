package com.stevencastro.wornhello;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
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
 * Starter app for the M6 glasses (640x480, 160 dpi, Android 9).
 * Black pixels are see-through, so everything is bright text on black.
 * Shows a clock and the last input event from the temple touchpad or ring,
 * which is the quickest way to learn which key codes the hardware sends.
 */
public class MainActivity extends Activity {
    private static final String TAG = "WornHello";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private TextView clock;
    private TextView lastInput;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(clockFormat.format(new Date()));
            handler.postDelayed(this, 1000);
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

        root.addView(label("WORN", 40, Color.rgb(0, 255, 140), true));
        clock = label("", 32, Color.WHITE, false);
        root.addView(clock);
        lastInput = label("Tap the touchpad or ring", 18, Color.rgb(0, 200, 255), false);
        root.addView(lastInput);

        setContentView(root);
    }

    private TextView label(String text, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 8, 0, 8);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);
        showInput("Key: " + KeyEvent.keyCodeToString(keyCode) + " (" + keyCode + ")");
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        showInput("Motion: " + MotionEvent.actionToString(event.getAction()));
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            showInput(String.format(Locale.US, "Touch at %.0f, %.0f", event.getX(), event.getY()));
        }
        return true;
    }

    private void showInput(String text) {
        Log.i(TAG, text);
        lastInput.setText(text);
    }
}
