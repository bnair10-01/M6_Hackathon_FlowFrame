package com.stevencastro.worntranslate;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.OptIn;
import androidx.camera.camera2.Camera2Config;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraXConfig;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal translator prototype for the M6 glasses.
 * Left: live camera feed. Right: the target language, the translation of
 * whatever text the camera sees, and the original text underneath.
 * Swipe on the temple touchpad (or DPAD left/right) to change the target
 * language; tap (or DPAD center) to freeze/unfreeze the current result.
 */
public class TranslateActivity extends ComponentActivity {
    private static final String TAG = "WornTranslate";
    private static final int CAMERA_REQUEST = 1;
    private static final long SCAN_INTERVAL_MS = 700;
    private static final int MAX_CHARS = 400;
    private static final float SWIPE_MIN_PX = 60;

    private static final int GREEN = Color.rgb(0, 255, 140);
    private static final int CYAN = Color.rgb(0, 200, 255);
    private static final int AMBER = Color.rgb(255, 190, 0);
    private static final int DIM = Color.rgb(140, 140, 140);

    // Target languages offered, with display names.
    private static final String[][] TARGETS = {
            {TranslateLanguage.ENGLISH, "English"},
            {TranslateLanguage.SPANISH, "Español"},
            {TranslateLanguage.FRENCH, "Français"},
            {TranslateLanguage.GERMAN, "Deutsch"},
            {TranslateLanguage.ITALIAN, "Italiano"},
            {TranslateLanguage.PORTUGUESE, "Português"},
            {TranslateLanguage.JAPANESE, "日本語"},
            {TranslateLanguage.CHINESE, "中文"},
            {TranslateLanguage.KOREAN, "한국어"},
    };

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Translator> translators = new HashMap<>();
    // Language pairs whose model is known to be downloaded.
    private final Map<String, Boolean> translatorReady = new HashMap<>();
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final LanguageIdentifier languageId = LanguageIdentification.getClient();

    private PreviewView preview;
    private TextView targetLabel;
    private TextView translated;
    private TextView original;
    private TextView status;

    private int target = 1;
    private volatile boolean frozen;
    private volatile boolean busy;
    private long lastScanMs;
    private String lastText = "";
    private float downX;
    private float downY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildLayout());
        showTarget();

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    private LinearLayout buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);

        // Camera on the left, keeping the sensor's 4:3 shape.
        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(preview, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 11));

        // Translation panel on the right.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(16, 16, 16, 16);
        panel.setGravity(Gravity.CENTER_VERTICAL);

        targetLabel = label(16, GREEN, true);
        translated = label(22, Color.WHITE, true);
        original = label(13, CYAN, false);
        status = label(11, DIM, false);
        panel.addView(targetLabel);
        panel.addView(translated);
        panel.addView(original);
        panel.addView(status);
        root.addView(panel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 9));

        translated.setText("Point at some text");
        return root;
    }

    private TextView label(int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setPadding(0, 6, 0, 6);
        view.setMaxLines(8);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            setStatus("Camera permission denied", AMBER);
        }
    }

    private void startCamera() {
        // The M6 has a single back camera but advertises a front one too, which
        // fails CameraX's startup check. Only enumerate the back camera.
        try {
            ProcessCameraProvider.configureInstance(CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                    .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
                    .build());
        } catch (IllegalStateException alreadyConfigured) {
            // Configured earlier in this process (e.g. activity recreated).
        }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                CameraSelector selector = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                        ? CameraSelector.DEFAULT_BACK_CAMERA
                        : provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : null;
                if (selector == null) {
                    setStatus("No camera is available to apps on this device", AMBER);
                    return;
                }

                Preview previewUseCase = new Preview.Builder().build();
                previewUseCase.setSurfaceProvider(preview.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);

                provider.unbindAll();
                provider.bindToLifecycle(this, selector, previewUseCase, analysis);
                setStatus("Scanning…", DIM);
            } catch (Exception e) {
                Log.e(TAG, "camera start failed", e);
                setStatus("Camera error: " + e.getMessage(), AMBER);
            }
        }, getMainExecutor());
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(ImageProxy frame) {
        long now = SystemClock.elapsedRealtime();
        if (frozen || busy || frame.getImage() == null || now - lastScanMs < SCAN_INTERVAL_MS) {
            frame.close();
            return;
        }
        lastScanMs = now;
        busy = true;

        InputImage image = InputImage.fromMediaImage(frame.getImage(), frame.getImageInfo().getRotationDegrees());
        recognizer.process(image)
                .addOnSuccessListener(this::onText)
                .addOnFailureListener(e -> {
                    Log.w(TAG, "OCR failed", e);
                    busy = false;
                })
                .addOnCompleteListener(task -> frame.close());
    }

    private void onText(Text result) {
        String text = result.getText().replace('\n', ' ').trim();
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
        if (text.length() < 2) {
            busy = false;
            return;
        }
        if (text.equals(lastText)) {
            busy = false;
            return;
        }
        lastText = text;
        String scanned = text;

        languageId.identifyLanguage(scanned)
                .addOnSuccessListener(code -> translate(scanned, code))
                .addOnFailureListener(e -> translate(scanned, "und"));
    }

    private void translate(String text, String detected) {
        String targetCode = TARGETS[target][0];
        String source = TranslateLanguage.fromLanguageTag(detected);
        if (source == null) source = TranslateLanguage.ENGLISH;

        if (source.equals(targetCode)) {
            show(text, text, source);
            return;
        }

        Translator translator = translatorFor(source, targetCode);
        String sourceCode = source;
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> translator.translate(text)
                        .addOnSuccessListener(out -> show(out, text, sourceCode))
                        .addOnFailureListener(e -> fail("Translation failed", e)))
                .addOnFailureListener(e -> fail("Model download failed (needs Wi-Fi once)", e));
        if (!translatorReady.containsKey(source + ">" + targetCode)) {
            setStatus("Downloading " + source.toUpperCase() + "→" + targetCode.toUpperCase() + " model…", AMBER);
        }
    }

    private Translator translatorFor(String source, String targetCode) {
        String key = source + ">" + targetCode;
        Translator translator = translators.get(key);
        if (translator == null) {
            translator = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(targetCode)
                    .build());
            translators.put(key, translator);
            getLifecycle().addObserver(translator);
        }
        return translator;
    }

    private void show(String translation, String source, String sourceCode) {
        translatorReady.put(sourceCode + ">" + TARGETS[target][0], true);
        runOnUiThread(() -> {
            if (!frozen) {
                translated.setText(translation);
                original.setText(sourceCode.toUpperCase() + ": " + source);
                setStatus("Scanning…", DIM);
            }
            busy = false;
        });
    }

    private void fail(String message, Exception e) {
        Log.w(TAG, message, e);
        setStatus(message, AMBER);
        lastText = "";
        busy = false;
    }

    private void setStatus(String text, int color) {
        runOnUiThread(() -> {
            status.setText(frozen ? "FROZEN · tap to resume" : text);
            status.setTextColor(frozen ? AMBER : color);
        });
    }

    private void showTarget() {
        targetLabel.setText("→ " + TARGETS[target][1] + "   ‹ swipe ›");
    }

    private void changeTarget(int delta) {
        target = (target + delta + TARGETS.length) % TARGETS.length;
        lastText = "";
        showTarget();
        Log.i(TAG, "target " + TARGETS[target][0]);
    }

    private void toggleFreeze() {
        frozen = !frozen;
        setStatus("Scanning…", DIM);
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
                if (Math.abs(dx) >= SWIPE_MIN_PX && Math.abs(dx) > Math.abs(dy)) {
                    changeTarget(dx > 0 ? 1 : -1);
                } else if (Math.abs(dy) >= SWIPE_MIN_PX) {
                    changeTarget(dy > 0 ? 1 : -1);
                } else {
                    toggleFreeze();
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
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                changeTarget(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                changeTarget(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                toggleFreeze();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onDestroy() {
        analysisExecutor.shutdown();
        recognizer.close();
        languageId.close();
        super.onDestroy();
    }
}
