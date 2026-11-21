package com.stevencastro.wornview;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.camera2.Camera2Config;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraXConfig;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live camera viewfinder for the M6 glasses: the camera feed fills the
 * 640x480 display. Tap (or DPAD center) to toggle fit/fill; swipe (or DPAD
 * left/right) to zoom; long-press to hide the info overlay.
 */
public class ViewActivity extends ComponentActivity {
    private static final String TAG = "WornView";
    private static final int CAMERA_REQUEST = 1;
    private static final float SWIPE_MIN_PX = 60;
    private static final float ZOOM_STEP = 0.25f;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();

    private PreviewView preview;
    private TextView overlay;
    private Camera camera;
    private boolean fill = true;
    private boolean showOverlay = true;
    private float zoom = 1f;
    private int frames;
    private long fpsWindowStart;
    private volatile float fps;
    private float downX;
    private float downY;

    private final Runnable updateOverlay = new Runnable() {
        @Override
        public void run() {
            overlay.setText(String.format(Locale.US, "LIVE  %.0f fps  %.2fx  %s",
                    fps, zoom, fill ? "FILL" : "FIT"));
            overlay.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new TextView(this);
        overlay.setTextColor(Color.rgb(0, 255, 140));
        overlay.setTextSize(12);
        overlay.setShadowLayer(4, 0, 0, Color.BLACK);
        overlay.setPadding(12, 8, 12, 8);
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                // Bottom corner: the glasses' status bar covers the top 24 px.
                Gravity.BOTTOM | Gravity.START));
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        overlay.post(updateOverlay);
    }

    @Override
    protected void onPause() {
        overlay.removeCallbacks(updateOverlay);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            overlay.setText("Camera permission denied");
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
                if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    overlay.removeCallbacks(updateOverlay);
                    overlay.setText("No camera is available to apps on this device");
                    return;
                }

                Preview previewUseCase = new Preview.Builder().build();
                previewUseCase.setSurfaceProvider(preview.getSurfaceProvider());

                // A no-op analyzer that just counts frames for the FPS readout.
                // Swap in recognition code here.
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, frame -> {
                    countFrame();
                    frame.close();
                });

                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis);
            } catch (Exception e) {
                Log.e(TAG, "camera start failed", e);
                overlay.removeCallbacks(updateOverlay);
                overlay.setText("Camera error: " + e.getMessage());
            }
        }, getMainExecutor());
    }

    private void countFrame() {
        long now = SystemClock.elapsedRealtime();
        if (fpsWindowStart == 0) fpsWindowStart = now;
        frames++;
        if (now - fpsWindowStart >= 1000) {
            fps = frames * 1000f / (now - fpsWindowStart);
            frames = 0;
            fpsWindowStart = now;
        }
    }

    private void toggleFit() {
        fill = !fill;
        preview.setScaleType(fill ? PreviewView.ScaleType.FILL_CENTER : PreviewView.ScaleType.FIT_CENTER);
    }

    private void changeZoom(int direction) {
        if (camera == null || camera.getCameraInfo().getZoomState().getValue() == null) return;
        float max = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
        float min = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
        zoom = Math.max(min, Math.min(max, zoom + direction * ZOOM_STEP));
        camera.getCameraControl().setZoomRatio(zoom);
    }

    private void toggleOverlay() {
        showOverlay = !showOverlay;
        overlay.setVisibility(showOverlay ? TextView.VISIBLE : TextView.GONE);
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
                if (Math.abs(dx) >= SWIPE_MIN_PX && Math.abs(dx) > Math.abs(dy)) {
                    changeZoom(dx > 0 ? 1 : -1);
                } else if (Math.abs(dy) >= SWIPE_MIN_PX) {
                    changeZoom(dy > 0 ? 1 : -1);
                } else if (held > 600) {
                    toggleOverlay();
                } else {
                    toggleFit();
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
                changeZoom(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                changeZoom(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                toggleFit();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onDestroy() {
        analysisExecutor.shutdown();
        super.onDestroy();
    }
}
