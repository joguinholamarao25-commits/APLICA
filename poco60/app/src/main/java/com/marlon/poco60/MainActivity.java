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
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 100;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int ENCODER_FPS = 60;
    private static final String LOGICAL_ID = "0";
    private static final String PHYSICAL_ID = "2";

    private static final CaptureRequest.Key<Integer> MTK_HFPS =
            new CaptureRequest.Key<>("com.mediatek.streamingfeature.hfpsMode", Integer.class);

    private TextureView textureView;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Executor cameraExecutor;

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private MediaRecorder mediaRecorder;
    private ParcelFileDescriptor outputPfd;
    private Uri outputUri;
    private File legacyOutputFile;
    private boolean recording = false;
    private boolean physical2Available = false;

    private long lastSensorTimestamp = 0L;
    private long sensorDeltaSum = 0L;
    private int sensorDeltaCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        startCameraThread();

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                ensureCameraPermissionAndOpen();
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });

        startButton.setOnClickListener(v -> startPhysical2Recording());
        stopButton.setOnClickListener(v -> stopRecording());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        statusView = new TextView(this);
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setTextSize(15f);
        statusView.setPadding(24, 18, 24, 18);
        statusView.setText("Abrindo câmera lógica 0…");
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        textureView = new TextureView(this);
        root.addView(textureView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(16, 16, 16, 24);

        startButton = new Button(this);
        startButton.setText("TESTAR FÍSICA 2 / 60");
        startButton.setEnabled(false);
        controls.addView(startButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        stopButton = new Button(this);
        stopButton.setText("PARAR");
        stopButton.setEnabled(false);
        controls.addView(stopButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(controls);
        setContentView(root);
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("POCO60Physical2");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = command -> cameraHandler.post(command);
    }

    private void ensureCameraPermissionAndOpen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        openLogicalCamera0();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openLogicalCamera0();
        }
    }

    private void openLogicalCamera0() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            List<String> ids = Arrays.asList(manager.getCameraIdList());
            if (!ids.contains(LOGICAL_ID)) {
                setStatus("Camera lógica ID 0 não encontrada. IDs: " + ids);
                return;
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(LOGICAL_ID);
            Set<String> physicalIds = characteristics.getPhysicalCameraIds();
            physical2Available = physicalIds.contains(PHYSICAL_ID);
            setStatus("Abrindo lógica 0 | físicas: " + physicalIds);

            if (!physical2Available) {
                setStatus("A câmera lógica 0 não expõe a física 2. Físicas: " + physicalIds);
                return;
            }

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

            manager.openCamera(LOGICAL_ID, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createLogicalPreview();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    setButtons(false, false);
                    setStatus("Câmera desconectada");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    recording = false;
                    setButtons(false, false);
                    setStatus("Erro CameraDevice: " + error + " | feche e abra o app para novo teste");
                }
            }, cameraHandler);
        } catch (Exception e) {
            setStatus("Falha ao abrir lógica 0: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
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

    private void createLogicalPreview() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        closeSession();
        try {
            Surface preview = previewSurface();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(preview);

            cameraDevice.createCaptureSession(Arrays.asList(preview),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (cameraDevice == null) return;
                            session = s;
                            try {
                                s.setRepeatingRequest(builder.build(), null, cameraHandler);
                                setButtons(true, false);
                                setStatus("PRONTO | lógica 0 | física 2 disponível | sessão normal");
                            } catch (CameraAccessException e) {
                                setStatus("Erro no preview: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            setButtons(false, false);
                            setStatus("Falha ao configurar preview lógico");
                        }
                    }, cameraHandler);
        } catch (Exception e) {
            setButtons(false, false);
            setStatus("Erro preview: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private void startPhysical2Recording() {
        if (recording || cameraDevice == null || !physical2Available || !textureView.isAvailable()) return;
        setButtons(false, false);
        setStatus("Preparando: lógica 0 → física 2 | sessão NORMAL | somente hfpsMode=1…");

        cameraHandler.post(() -> {
            try {
                closeSession();
                prepareRecorder();
                createPhysical2NormalSession();
            } catch (Exception e) {
                setStatus("Falha ao iniciar teste: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                discardRecorderOutput();
                if (cameraDevice != null) createLogicalPreview();
            }
        });
    }

    private void prepareRecorder() throws IOException {
        discardRecorderOutput();

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
                    "POCO60_PHYS2_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/POCO60Test");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            outputUri = getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (outputUri == null) throw new IOException("MediaStore insert retornou null");
            outputPfd = getContentResolver().openFileDescriptor(outputUri, "w");
            if (outputPfd == null) throw new IOException("Falha ao abrir arquivo de saída");
            mediaRecorder.setOutputFile(outputPfd.getFileDescriptor());
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "POCO60Test");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Falha ao criar pasta");
            legacyOutputFile = new File(dir,
                    "POCO60_PHYS2_" + System.currentTimeMillis() + ".mp4");
            mediaRecorder.setOutputFile(legacyOutputFile.getAbsolutePath());
        }

        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoSize(WIDTH, HEIGHT);
        mediaRecorder.setVideoFrameRate(ENCODER_FPS);
        mediaRecorder.setVideoEncodingBitRate(30_000_000);
        mediaRecorder.setOrientationHint(90);
        mediaRecorder.prepare();
    }

    private void createPhysical2NormalSession() throws CameraAccessException {
        Surface preview = previewSurface();
        Surface record = mediaRecorder.getSurface();

        OutputConfiguration previewOutput = new OutputConfiguration(preview);
        OutputConfiguration recordOutput = new OutputConfiguration(record);
        previewOutput.setPhysicalCameraId(PHYSICAL_ID);
        recordOutput.setPhysicalCameraId(PHYSICAL_ID);

        List<OutputConfiguration> outputs = new ArrayList<>();
        outputs.add(previewOutput);
        outputs.add(recordOutput);

        CaptureRequest.Builder repeating =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        repeating.addTarget(preview);
        repeating.addTarget(record);
        safeSet(repeating, MTK_HFPS, 1, "hfpsMode repeating");
        try {
            repeating.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        } catch (Exception ignored) {}

        CaptureRequest.Builder sessionParams =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        safeSet(sessionParams, MTK_HFPS, 1, "hfpsMode session");

        SessionConfiguration config = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                cameraExecutor,
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        if (cameraDevice == null) return;
                        session = s;
                        resetFpsCounter();
                        try {
                            s.setRepeatingRequest(repeating.build(), fpsCallback, cameraHandler);
                            mediaRecorder.start();
                            recording = true;
                            setButtons(false, true);
                            setStatus("GRAVANDO | lógica 0 → física 2 | NORMAL | hfpsMode=1 | medindo…");
                        } catch (Exception e) {
                            setStatus("Sessão abriu, mas gravação falhou: "
                                    + e.getClass().getSimpleName() + " - " + e.getMessage());
                            discardRecorderOutput();
                            if (cameraDevice != null) createLogicalPreview();
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession s) {
                        setStatus("HAL recusou sessão NORMAL com outputs na física 2 + hfpsMode=1");
                        discardRecorderOutput();
                        if (cameraDevice != null) createLogicalPreview();
                    }
                });

        config.setSessionParameters(sessionParams.build());
        cameraDevice.createCaptureSession(config);
    }

    private <T> void safeSet(CaptureRequest.Builder builder,
                             CaptureRequest.Key<T> key,
                             T value,
                             String label) {
        try {
            builder.set(key, value);
            android.util.Log.i("POCO60", label + " OK");
        } catch (Exception e) {
            android.util.Log.e("POCO60", label + " FALHOU", e);
        }
    }

    private final CameraCaptureSession.CaptureCallback fpsCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s,
                                               CaptureRequest request,
                                               TotalCaptureResult result) {
                    Long ts = null;
                    String source = "logical";

                    try {
                        Map<String, CaptureResult> physicalResults =
                                result.getPhysicalCameraResults();
                        CaptureResult physical2 = physicalResults.get(PHYSICAL_ID);
                        if (physical2 != null) {
                            ts = physical2.get(CaptureResult.SENSOR_TIMESTAMP);
                            source = "physical2";
                        }
                    } catch (Exception ignored) {}

                    if (ts == null) ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (ts == null) return;

                    if (lastSensorTimestamp != 0L) {
                        long delta = ts - lastSensorTimestamp;
                        if (delta > 0 && delta < 200_000_000L) {
                            sensorDeltaSum += delta;
                            sensorDeltaCount++;
                            if (sensorDeltaCount >= 30) {
                                double fps = 1_000_000_000.0 * sensorDeltaCount / sensorDeltaSum;
                                String finalSource = source;
                                setStatus(String.format(Locale.US,
                                        "GRAVANDO | FPS SENSOR: %.1f | %s | lógica0→física2 | NORMAL | hfps=1",
                                        fps, finalSource));
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

    private void stopRecording() {
        if (!recording) return;
        cameraHandler.post(() -> {
            try {
                if (session != null) {
                    try { session.stopRepeating(); } catch (Exception ignored) {}
                }
                try {
                    mediaRecorder.stop();
                } catch (RuntimeException e) {
                    setStatus("MediaRecorder stop falhou: " + e.getMessage());
                }
            } finally {
                recording = false;
                closeSession();
                finalizeRecorderOutput();
                setButtons(false, false);
                if (cameraDevice != null) {
                    setStatus("Vídeo salvo. Reabrindo preview lógico…");
                    createLogicalPreview();
                }
            }
        });
    }

    private void finalizeRecorderOutput() {
        releaseRecorderObjects();
        if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(outputUri, values, null, null);
            } catch (Exception ignored) {}
        }
        outputUri = null;
        legacyOutputFile = null;
    }

    private void discardRecorderOutput() {
        releaseRecorderObjects();
        if (outputUri != null) {
            try { getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
        }
        if (legacyOutputFile != null) {
            try { legacyOutputFile.delete(); } catch (Exception ignored) {}
        }
        outputUri = null;
        legacyOutputFile = null;
    }

    private void releaseRecorderObjects() {
        if (mediaRecorder != null) {
            try { mediaRecorder.reset(); } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
        if (outputPfd != null) {
            try { outputPfd.close(); } catch (Exception ignored) {}
            outputPfd = null;
        }
    }

    private void setButtons(boolean startEnabled, boolean stopEnabled) {
        runOnUiThread(() -> {
            startButton.setEnabled(startEnabled);
            stopButton.setEnabled(stopEnabled);
        });
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
        discardRecorderOutput();
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
