package com.stevencastro.wornask;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.camera2.Camera2Config;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraXConfig;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ask Claude about what you're looking at.
 *
 * Tap the touchpad (or DPAD center): the glasses grab a camera frame and
 * listen. When you stop talking, the photo and what you said go to Claude
 * and the answer streams onto the display. Tap while listening to stop
 * early. Long-press asks about the photo alone ("What am I looking at?").
 * Swipe down clears the screen.
 *
 * Set the API key once over adb:
 *   adb shell am start -n com.stevencastro.wornask/.AskActivity --es api_key sk-ant-...
 */
public class AskActivity extends ComponentActivity {
    private static final String TAG = "WornAsk";
    private static final String EXTRA_API_KEY = "api_key";
    private static final String PREF_API_KEY = "api_key";
    private static final int PERMISSIONS_REQUEST = 1;
    private static final int JPEG_QUALITY = 80;
    private static final float SWIPE_MIN_PX = 60;
    private static final long LONG_PRESS_MS = 600;
    private static final String PHOTO_ONLY_QUESTION = "What am I looking at?";

    private static final int GREEN = Color.rgb(0, 255, 140);
    private static final int CYAN = Color.rgb(0, 200, 255);
    private static final int RED = Color.rgb(255, 90, 90);

    private enum State { IDLE, LISTENING, THINKING }

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService claudeExecutor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private TextView status;
    private TextView heard;
    private TextView answer;
    private ImageView thumbnail;

    private SpeechRecognizer recognizer;
    private boolean speechAvailable;
    private boolean cameraReady;
    private State state = State.IDLE;

    // Set from the main thread; the analyzer captures the next frame.
    private volatile boolean captureRequested;
    private byte[] photo;
    // Photo-only question waiting for its frame.
    private boolean photoOnlyPending;

    private ClaudeClient claude;
    private String claudeKey;

    private float downX;
    private float downY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("ask", MODE_PRIVATE);
        setContentView(buildLayout());

        saveKeyFrom(getIntent());

        String[] needed = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        boolean granted = true;
        for (String p : needed) granted &= checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            start();
        } else {
            requestPermissions(needed, PERMISSIONS_REQUEST);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        saveKeyFrom(intent);
        showIdleStatus();
    }

    /** Stores an API key passed with `--es api_key`, then drops it from the intent. */
    private void saveKeyFrom(Intent intent) {
        if (intent == null) return;
        String key = intent.getStringExtra(EXTRA_API_KEY);
        intent.removeExtra(EXTRA_API_KEY);
        if (key == null || key.trim().isEmpty()) return;
        prefs.edit().putString(PREF_API_KEY, key.trim()).apply();
        status.setTextColor(GREEN);
        status.setText("API key saved");
    }

    private FrameLayout buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        // The glasses' status bar covers the top 24 px.
        column.setPadding(24, 32, 24, 16);

        status = label(13, GREEN, true);
        heard = label(15, CYAN, false);
        answer = label(20, Color.WHITE, true);
        answer.setMaxLines(10);

        column.addView(status);
        column.addView(heard);
        column.addView(answer);
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // What was sent, so the wearer knows what Claude saw.
        thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setAlpha(0.8f);
        FrameLayout.LayoutParams thumb = new FrameLayout.LayoutParams(128, 96,
                Gravity.BOTTOM | Gravity.END);
        thumb.setMargins(0, 0, 16, 16);
        root.addView(thumbnail, thumb);

        status.setText("Starting…");
        return root;
    }

    private TextView label(int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setPadding(0, 4, 0, 4);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                showError("Camera and microphone permissions are required");
                return;
            }
        }
        start();
    }

    private void start() {
        speechAvailable = SpeechRecognizer.isRecognitionAvailable(this);
        if (speechAvailable) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(speechListener);
        }
        startCamera();
        showIdleStatus();
    }

    private void showIdleStatus() {
        if (state != State.IDLE) return;
        status.setTextColor(GREEN);
        if (apiKey() == null) {
            status.setTextColor(RED);
            status.setText("No API key. Set it with: am start … --es api_key <key>");
        } else if (!speechAvailable) {
            status.setText("No speech recognizer on this device. Tap to ask about what you see");
        } else {
            status.setText("Tap to ask · long-press: what am I looking at?");
        }
    }

    private void showError(String message) {
        status.setTextColor(RED);
        status.setText(message);
    }

    private String apiKey() {
        return prefs.getString(PREF_API_KEY, null);
    }

    // Input.

    private void onTap() {
        switch (state) {
            case LISTENING:
                recognizer.stopListening();
                break;
            case THINKING:
                break;
            case IDLE:
                if (!speechAvailable) {
                    askAboutPhoto();
                } else {
                    startListening();
                }
                break;
        }
    }

    private void onLongPress() {
        if (state == State.IDLE) askAboutPhoto();
    }

    private void clearScreen() {
        if (state != State.IDLE) return;
        heard.setText("");
        answer.setText("");
        thumbnail.setImageDrawable(null);
        showIdleStatus();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                long held = event.getEventTime() - event.getDownTime();
                if (dy >= SWIPE_MIN_PX && Math.abs(dy) > Math.abs(dx)) {
                    clearScreen();
                } else if (Math.abs(dx) < SWIPE_MIN_PX && Math.abs(dy) < SWIPE_MIN_PX) {
                    if (held >= LONG_PRESS_MS) {
                        onLongPress();
                    } else {
                        onTap();
                    }
                }
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Act on key-up so a long press can mean "photo only".
                event.startTracking();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                clearScreen();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            onLongPress();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            boolean wasLong = (event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) != 0;
            if (!wasLong && !event.isCanceled()) onTap();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // Speech.

    private void startListening() {
        if (apiKey() == null) {
            showIdleStatus();
            return;
        }
        state = State.LISTENING;
        photo = null;
        requestPhoto();
        heard.setText("");
        answer.setText("");
        status.setTextColor(GREEN);
        status.setText("Listening… (tap to stop)");

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizer.startListening(intent);
    }

    private final RecognitionListener speechListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            status.setText("Got it…");
        }

        @Override
        public void onError(int error) {
            if (state != State.LISTENING) return;
            state = State.IDLE;
            showError(speechError(error));
        }

        @Override
        public void onResults(Bundle results) {
            if (state != State.LISTENING) return;
            String text = firstResult(results);
            if (text == null || text.isEmpty()) {
                state = State.IDLE;
                showError("Didn't catch that. Tap to try again");
                return;
            }
            heard.setText("You: " + text);
            send(text);
        }

        @Override
        public void onPartialResults(Bundle partial) {
            String text = firstResult(partial);
            if (text != null && !text.isEmpty()) heard.setText("You: " + text);
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };

    private static String firstResult(Bundle bundle) {
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static String speechError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Didn't catch that. Tap to try again";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Speech recognizer needs a network connection";
            case SpeechRecognizer.ERROR_AUDIO:
                return "Microphone error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission denied";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Speech recognizer busy. Try again";
            default:
                return "Speech error " + error;
        }
    }

    // Claude.

    private void askAboutPhoto() {
        if (apiKey() == null) {
            showIdleStatus();
            return;
        }
        if (!cameraReady) {
            showError("Camera isn't ready");
            return;
        }
        state = State.THINKING;
        photo = null;
        photoOnlyPending = true;
        heard.setText("You: " + PHOTO_ONLY_QUESTION);
        answer.setText("");
        status.setTextColor(GREEN);
        status.setText("Taking a photo…");
        requestPhoto();
    }

    private void onPhoto(byte[] jpeg) {
        photo = jpeg;
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        thumbnail.setImageBitmap(bitmap);
        if (photoOnlyPending) {
            photoOnlyPending = false;
            send(PHOTO_ONLY_QUESTION);
        }
    }

    private void send(String question) {
        String key = apiKey();
        if (key == null) {
            state = State.IDLE;
            showIdleStatus();
            return;
        }
        if (claude == null || !key.equals(claudeKey)) {
            claude = new ClaudeClient(key);
            claudeKey = key;
        }
        state = State.THINKING;
        answer.setText("");
        status.setTextColor(GREEN);
        status.setText(photo != null ? "Asking Claude…" : "Asking Claude (no photo)…");

        byte[] jpeg = photo;
        ClaudeClient client = claude;
        claudeExecutor.execute(() -> client.ask(jpeg, question, new ClaudeClient.Callback() {
            @Override
            public void onText(String delta) {
                runOnUiThread(() -> answer.append(delta));
            }

            @Override
            public void onDone(String error) {
                runOnUiThread(() -> {
                    state = State.IDLE;
                    if (error != null) {
                        showError(error);
                    } else {
                        showIdleStatus();
                    }
                });
            }
        }));
    }

    // Camera.

    private void requestPhoto() {
        if (cameraReady) captureRequested = true;
    }

    private void startCamera() {
        // The M6 has a single back camera but advertises a front one too, which
        // fails CameraX's startup check. Only enumerate the back camera.
        try {
            ProcessCameraProvider.configureInstance(CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                    .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
                    .build());
        } catch (IllegalStateException alreadyConfigured) {
            // Configured earlier in this process.
        }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    Log.w(TAG, "no back camera");
                    return;
                }
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::onFrame);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis);
                cameraReady = true;
            } catch (Exception e) {
                Log.e(TAG, "camera start failed", e);
                showError("Camera error: " + e.getMessage());
            }
        }, getMainExecutor());
    }

    private void onFrame(ImageProxy frame) {
        try {
            if (!captureRequested) return;
            captureRequested = false;
            byte[] jpeg = toJpeg(frame);
            runOnUiThread(() -> onPhoto(jpeg));
        } catch (RuntimeException e) {
            Log.e(TAG, "frame capture failed", e);
        } finally {
            frame.close();
        }
    }

    /** YUV_420_888 to JPEG, via NV21 (handles any row and pixel stride). Same as Worn Link. */
    private static byte[] toJpeg(ImageProxy image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];

        ImageProxy.PlaneProxy y = image.getPlanes()[0];
        ByteBuffer yBuffer = y.getBuffer();
        int pos = 0;
        for (int row = 0; row < height; row++) {
            yBuffer.position(row * y.getRowStride());
            yBuffer.get(nv21, pos, width);
            pos += width;
        }

        ImageProxy.PlaneProxy u = image.getPlanes()[1];
        ImageProxy.PlaneProxy v = image.getPlanes()[2];
        ByteBuffer uBuffer = u.getBuffer();
        ByteBuffer vBuffer = v.getBuffer();
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int i = row * u.getRowStride() + col * u.getPixelStride();
                nv21[pos++] = vBuffer.get(i);
                nv21[pos++] = uBuffer.get(i);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, out);
        return out.toByteArray();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        cameraExecutor.shutdown();
        claudeExecutor.shutdownNow();
        super.onDestroy();
    }
}
