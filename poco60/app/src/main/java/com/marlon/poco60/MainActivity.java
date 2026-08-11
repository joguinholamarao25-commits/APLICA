package com.marlon.poco60;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Range;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 100;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int FPS = 60;
    // Xiaomi stock camera uses vendor operation mode 0x8000 + 60 for normal 60 FPS video.
    private static final int MTK_VENDOR_MODE_60 = 0x803C; // 32828

    private static final CaptureRequest.Key<Integer> MTK_HFPS =
            new CaptureRequest.Key<>("com.mediatek.streamingfeature.hfpsMode", Integer.class);
    private static final CaptureRequest.Key<int[]> MTK_QUICK_PREVIEW =
            new CaptureRequest.Key<>("com.mediatek.configure.setting.initrequest", int[].class);
    private static final CaptureRequest.Key<int[]> MTK_RECORD_STATE =
            new CaptureRequest.Key<>("com.mediatek.streamingfeature.recordState", int[].class);

    private TextureView textureView;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Executor cameraExecutor;

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private String cameraId = "2";
    private MediaRecorder mediaRecorder;
    private ParcelFileDescriptor outputPfd;
    private Uri outputUri;
    private File legacyOutputFile;
    private boolean recording = false;

    private long lastSensorTimestamp = 0L;
    private long sensorDeltaSum = 0L;
    private int sensorDeltaCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        startCameraThread();

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                ensureCameraPermissionAndOpen();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });

        startButton.setOnClickListener(v -> start60Recording());
        stopButton.setOnClickListener(v -> stop60Recording());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        statusView = new TextView(this);
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setTextSize(15f);
        statusView.setPadding(24, 18, 24, 18);
        statusView.setText("Abrindo câmera…");
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        textureView = new TextureView(this);
        LinearLayout.LayoutParams textureParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(textureView, textureParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(16, 16, 16, 16);

        startButton = new Button(this);
        startButton.setText("GRAVAR 1080p60");
        controls.addView(startButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        stopButton = new Button(this);
        stopButton.setText("PARAR");
        stopButton.setEnabled(false);
        controls.addView(stopButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(controls);
        setContentView(root);
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("POCO60Camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = command -> cameraHandler.post(command);
    }

    private void ensureCameraPermissionAndOpen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        openCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = chooseCameraId(manager);
            setStatus("Camera ID " + cameraId + " | preview 30 FPS | pronto para teste 0x803C");
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createPreviewSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    setStatus("Câmera desconectada");
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    setStatus("Erro ao abrir câmera: " + error);
                }
            }, cameraHandler);
        } catch (Exception e) {
            setStatus("Falha ao abrir câmera: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private String chooseCameraId(CameraManager manager) throws CameraAccessException {
        List<String> ids = Arrays.asList(manager.getCameraIdList());
        if (ids.contains("2")) return "2";
        if (ids.contains("0")) return "0";
        for (String id : ids) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return ids.get(0);
    }

    private Surface previewSurface() {
        SurfaceTexture st = textureView.getSurfaceTexture();
        if (st == null) throw new IllegalStateException("TextureView sem SurfaceTexture");
        st.setDefaultBufferSize(WIDTH, HEIGHT);
        return new Surface(st);
    }

    private void closeSession() {
        if (session != null) {
            try { session.stopRepeating(); } catch (Exception ignored) {}
            try { session.abortCaptures(); } catch (Exception ignored) {}
            try { session.close(); } catch (Exception ignored) {}
            session = null;
        }
    }

    private void createPreviewSession() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        closeSession();
        try {
            Surface preview = previewSurface();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(preview);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30));
            cameraDevice.createCaptureSession(Arrays.asList(preview), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (cameraDevice == null) return;
                    session = s;
                    try {
                        s.setRepeatingRequest(builder.build(), null, cameraHandler);
                        setStatus("Camera ID " + cameraId + " | preview 30 FPS | toque GRAVAR 1080p60");
                    } catch (CameraAccessException e) {
                        setStatus("Erro no preview: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    setStatus("Falha ao configurar preview");
                }
            }, cameraHandler);
        } catch (Exception e) {
            setStatus("Erro preview: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private void start60Recording() {
        if (recording || cameraDevice == null || !textureView.isAvailable()) return;
        startButton.setEnabled(false);
        setStatus("Preparando sessão vendor 0x803C…");
        cameraHandler.post(() -> {
            try {
                closeSession();
                prepareRecorder();
                createVendor60Session();
            } catch (Exception e) {
                setStatus("Falha ao iniciar 60 FPS: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                releaseRecorder(false);
                runOnUiThread(() -> startButton.setEnabled(true));
                createPreviewSession();
            }
        });
    }

    private void prepareRecorder() throws IOException {
        releaseRecorder(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mediaRecorder = new MediaRecorder(this);
        } else {
            mediaRecorder = new MediaRecorder();
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME,
                    "POCO60_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/POCO60Test");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            outputUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (outputUri == null) throw new IOException("MediaStore insert retornou null");
            outputPfd = getContentResolver().openFileDescriptor(outputUri, "w");
            if (outputPfd == null) throw new IOException("Não foi possível abrir arquivo de saída");
            mediaRecorder.setOutputFile(outputPfd.getFileDescriptor());
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "POCO60Test");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Falha ao criar pasta");
            legacyOutputFile = new File(dir, "POCO60_" + System.currentTimeMillis() + ".mp4");
            mediaRecorder.setOutputFile(legacyOutputFile.getAbsolutePath());
        }

        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoSize(WIDTH, HEIGHT);
        mediaRecorder.setVideoFrameRate(FPS);
        mediaRecorder.setVideoEncodingBitRate(30_000_000);
        mediaRecorder.setOrientationHint(90);
        mediaRecorder.prepare();
    }

    private void createVendor60Session() throws CameraAccessException {
        Surface preview = previewSurface();
        Surface record = mediaRecorder.getSurface();

        CaptureRequest.Builder repeating = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        repeating.addTarget(preview);
        repeating.addTarget(record);
        applyVendor60(repeating, true);

        CaptureRequest.Builder sessionParamsBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        applyVendor60(sessionParamsBuilder, true);

        List<OutputConfiguration> outputs = new ArrayList<>();
        outputs.add(new OutputConfiguration(preview));
        outputs.add(new OutputConfiguration(record));

        SessionConfiguration config = new SessionConfiguration(
                MTK_VENDOR_MODE_60,
                outputs,
                cameraExecutor,
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        if (cameraDevice == null) return;
                        session = s;
                        resetFpsCounter();
                        try {
                            s.setRepeatingRequest(repeating.build(), fpsCallback, cameraHandler);
                            mediaRecorder.start();
                            recording = true;
                            runOnUiThread(() -> {
                                stopButton.setEnabled(true);
                                startButton.setEnabled(false);
                            });
                            setStatus("GRAVANDO | ID " + cameraId + " | session 0x803C | hfpsMode=1 | aguardando FPS real…");
                        } catch (Exception e) {
                            setStatus("Sessão abriu, mas gravação falhou: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                            releaseRecorder(false);
                            createPreviewSession();
                        }
                    }

                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        setStatus("HAL recusou a sessão vendor 0x803C");
                        releaseRecorder(false);
                        runOnUiThread(() -> startButton.setEnabled(true));
                        createPreviewSession();
                    }
                });

        config.setSessionParameters(sessionParamsBuilder.build());
        cameraDevice.createCaptureSession(config);
    }

    private void applyVendor60(CaptureRequest.Builder b, boolean recordingState) {
        safeSet(b, MTK_HFPS, 1, "hfpsMode");
        safeSet(b, MTK_QUICK_PREVIEW, new int[]{1}, "initrequest");
        safeSet(b, MTK_RECORD_STATE, new int[]{recordingState ? 1 : 0}, "recordState");
        try {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(FPS, FPS));
        } catch (Exception ignored) {}
        try {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        } catch (Exception ignored) {}
        try {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        } catch (Exception ignored) {}
    }

    private <T> void safeSet(CaptureRequest.Builder b, CaptureRequest.Key<T> key, T value, String label) {
        try {
            b.set(key, value);
        } catch (Exception e) {
            android.util.Log.e("POCO60", "Vendor key failed " + label, e);
        }
    }

    private final CameraCaptureSession.CaptureCallback fpsCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                               TotalCaptureResult result) {
                    Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (ts == null) return;
                    if (lastSensorTimestamp != 0L) {
                        long delta = ts - lastSensorTimestamp;
                        if (delta > 0 && delta < 200_000_000L) {
                            sensorDeltaSum += delta;
                            sensorDeltaCount++;
                            if (sensorDeltaCount >= 30) {
                                double fps = 1_000_000_000.0 * sensorDeltaCount / sensorDeltaSum;
                                setStatus(String.format(Locale.US,
                                        "GRAVANDO | FPS SENSOR: %.1f | ID %s | session 0x803C | hfpsMode=1",
                                        fps, cameraId));
                                sensorDeltaSum = 0L;
                                sensorDeltaCount = 0;
                            }
                        }
                    }
                    lastSensorTimestamp = ts;
                }
            };

    private void resetFpsCounter() {
        lastSensorTimestamp = 0L;
        sensorDeltaSum = 0L;
        sensorDeltaCount = 0;
    }

    private void stop60Recording() {
        if (!recording) return;
        cameraHandler.post(() -> {
            try {
                if (session != null) {
                    try {
                        CaptureRequest.Builder previewState =
                                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        previewState.addTarget(previewSurface());
                        safeSet(previewState, MTK_RECORD_STATE, new int[]{0}, "recordState-preview");
                        session.setRepeatingRequest(previewState.build(), null, cameraHandler);
                    } catch (Exception ignored) {}
                }
                try { mediaRecorder.stop(); } catch (RuntimeException e) {
                    setStatus("MediaRecorder stop falhou: " + e.getMessage());
                }
            } finally {
                recording = false;
                closeSession();
                releaseRecorder(true);
                runOnUiThread(() -> {
                    stopButton.setEnabled(false);
                    startButton.setEnabled(true);
                });
                setStatus("Vídeo salvo. Reabrindo preview…");
                createPreviewSession();
            }
        });
    }

    private void releaseRecorder(boolean finalizeMediaStore) {
        if (mediaRecorder != null) {
            try { mediaRecorder.reset(); } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
        if (outputPfd != null) {
            try { outputPfd.close(); } catch (Exception ignored) {}
            outputPfd = null;
        }
        if (finalizeMediaStore && outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(outputUri, values, null, null);
            } catch (Exception ignored) {}
        }
        outputUri = null;
        legacyOutputFile = null;
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusView.setText(text));
        android.util.Log.i("POCO60", text);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (recording && mediaRecorder != null) mediaRecorder.stop();
        } catch (Exception ignored) {}
        recording = false;
        closeSession();
        releaseRecorder(true);
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
    }
}
