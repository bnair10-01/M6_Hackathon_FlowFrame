package com.stevencastro.wornlink;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.YuvImage;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.WindowManager;
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

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams the glasses' microphone and camera to the phone over Wi-Fi (see
 * WifiLink), and shows what the phone sends back: what it heard and the
 * assistant's answer. Streaming runs while the phone is connected.
 */
public class LinkActivity extends ComponentActivity implements WifiLink.Listener {
    private static final String TAG = "WornLink";
    private static final int PERMISSIONS_REQUEST = 1;
    private static final int SAMPLE_RATE = 16000;
    private static final int SAMPLES_PER_CHUNK = SAMPLE_RATE / 50; // 20 ms
    private static final int JPEG_QUALITY = 70;
    private static final int GREEN = Color.rgb(0, 255, 140);
    private static final int CYAN = Color.rgb(0, 200, 255);
    private static final int DIM = Color.rgb(140, 140, 140);

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private WifiLink link;
    private TextView status;
    private TextView heard;
    private TextView answer;
    private TextView stats;
    private ProcessCameraProvider cameraProvider;
    private Thread audioThread;
    private volatile boolean streaming;
    private volatile long frameIntervalMs = 500;
    private long lastFrameMs;
    private volatile int framesSent;

    private final Runnable updateStats = new Runnable() {
        @Override
        public void run() {
            long bytes = link.takeBytesSent();
            int frames = framesSent;
            framesSent = 0;
            stats.setText(streaming
                    ? String.format(Locale.US, "%.1f kB/s · %d frame%s/s", bytes / 1024f, frames, frames == 1 ? "" : "s")
                    : "");
            stats.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildLayout());

        link = new WifiLink(this, this);
        String[] needed = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        boolean granted = true;
        for (String p : needed) granted &= checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            link.start();
        } else {
            requestPermissions(needed, PERMISSIONS_REQUEST);
        }
    }

    private LinearLayout buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        // The glasses' status bar covers the top 24 px.
        root.setPadding(24, 32, 24, 16);

        status = label(13, GREEN, true);
        heard = label(15, CYAN, false);
        answer = label(22, Color.WHITE, true);
        stats = label(11, DIM, false);
        answer.setMaxLines(9);

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER_VERTICAL);
        middle.addView(heard);
        middle.addView(answer);

        root.addView(status);
        root.addView(middle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(stats);

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
                status.setText("Camera and microphone permissions are required");
                return;
            }
        }
        link.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        stats.post(updateStats);
    }

    @Override
    protected void onPause() {
        stats.removeCallbacks(updateStats);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopCapture();
        link.stop();
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    // WifiLink.Listener, on the main thread.

    @Override
    public void onStatus(String text) {
        status.setText(text);
    }

    @Override
    public void onStreaming(boolean on) {
        if (on) {
            startCapture();
        } else {
            stopCapture();
        }
    }

    @Override
    public void onCommand(String json) {
        try {
            JSONObject command = new JSONObject(json);
            switch (command.optString("cmd")) {
                case "show":
                    if (command.has("heard")) heard.setText(command.optString("heard"));
                    if (command.has("text")) answer.setText(command.optString("text"));
                    break;
                case "fps":
                    double fps = command.optDouble("value", 1);
                    frameIntervalMs = (long) (1000 / Math.max(0.1, Math.min(5, fps)));
                    break;
                default:
                    Log.w(TAG, "unknown command " + json);
            }
        } catch (Exception e) {
            Log.w(TAG, "bad command " + json, e);
        }
    }

    // Capture.

    private void startCapture() {
        if (streaming) return;
        streaming = true;
        startAudio();
        startCamera();
    }

    private void stopCapture() {
        streaming = false;
        if (audioThread != null) audioThread.interrupt();
        audioThread = null;
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    private void startAudio() {
        audioThread = new Thread(() -> {
            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer, SAMPLE_RATE));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                runOnUiThread(() -> status.setText("Microphone unavailable"));
                recorder.release();
                return;
            }
            short[] samples = new short[SAMPLES_PER_CHUNK];
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                runOnUiThread(() -> status.setText("Microphone is in use by another app"));
            }
            try {
                while (streaming && !Thread.currentThread().isInterrupted()) {
                    int read = recorder.read(samples, 0, samples.length);
                    if (read <= 0) continue;
                    // 16-bit little-endian PCM.
                    byte[] pcm = new byte[read * 2];
                    for (int i = 0; i < read; i++) {
                        pcm[2 * i] = (byte) samples[i];
                        pcm[2 * i + 1] = (byte) (samples[i] >> 8);
                    }
                    link.sendAudio(pcm);
                }
            } finally {
                recorder.stop();
                recorder.release();
            }
        }, "WornLink-audio");
        audioThread.start();
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
                cameraProvider = future.get();
                if (!streaming) return;
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::onFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis);
            } catch (Exception e) {
                Log.e(TAG, "camera start failed", e);
                status.setText("Camera error: " + e.getMessage());
            }
        }, getMainExecutor());
    }

    private void onFrame(ImageProxy frame) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (!streaming || now - lastFrameMs < frameIntervalMs) return;
            byte[] jpeg = toJpeg(frame);
            if (link.sendFrame(jpeg)) {
                lastFrameMs = now;
                framesSent++;
            }
        } finally {
            frame.close();
        }
    }

    /** YUV_420_888 to JPEG, via NV21 (handles any row and pixel stride). */
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
}
