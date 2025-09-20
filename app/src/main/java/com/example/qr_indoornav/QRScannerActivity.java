package com.example.qr_indoornav;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QRScannerActivity extends AppCompatActivity {
    private static final String TAG = "QRScannerActivity";

    private boolean isProcessing = false;

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 101;
    // Debounce time in milliseconds to prevent TTS from speaking too often
    private static final long GUIDANCE_DEBOUNCE_MS = 2500;

    private PreviewView previewView;
    private ImageView overlayImageView;
    private ExecutorService cameraExecutor;
    private Bitmap bitmapForOverlay;

    // Variables to manage guidance speech
    private String lastSpokenGuidance = "";
    private long lastGuidanceTime = 0;

    static {
        System.loadLibrary("qr_indoornav");
    }

    public native String processFrame(long matAddr);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qrscanner);

        previewView = findViewById(R.id.cameraPreview);
        overlayImageView = findViewById(R.id.overlayImageView);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (OpenCVLoader.initDebug()) {
            Log.i(TAG, "OpenCV library loaded successfully.");
            if (allPermissionsGranted()) {
                startCamera();
            }
        } else {
            Log.e(TAG, "OpenCV failed to load.");
            Toast.makeText(this, "OpenCV failed to load!", Toast.LENGTH_LONG).show();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (isProcessing) {
                        image.close();
                        return;
                    }

                    Mat bgrMat = getRotatedBgrMatFromImage(image);
                    if (bgrMat != null) {
                        // The native function now returns a string like "decoded_text|guidance_message"
                        String combinedResult = processFrame(bgrMat.getNativeObjAddr());

                        // Update the visual overlay
                        if (bitmapForOverlay == null || bitmapForOverlay.getWidth() != bgrMat.cols() || bitmapForOverlay.getHeight() != bgrMat.rows()) {
                            bitmapForOverlay = Bitmap.createBitmap(bgrMat.cols(), bgrMat.rows(), Bitmap.Config.ARGB_8888);
                        }
                        Utils.matToBitmap(bgrMat, bitmapForOverlay);
                        runOnUiThread(() -> overlayImageView.setImageBitmap(bitmapForOverlay));

                        bgrMat.release();

                        // --- NEW PARSING AND TTS LOGIC ---
                        if (combinedResult != null && !combinedResult.isEmpty()) {
                            String[] parts = combinedResult.split("\\+", -1);
                            String decodedResult = parts.length > 0 ? parts[0] : "";
                            String guidanceText = parts.length > 1 ? parts[1] : "";

                            // Speak guidance if it's new, not empty, and enough time has passed
                            if (!guidanceText.isEmpty() && !guidanceText.equals(lastSpokenGuidance)) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastGuidanceTime > GUIDANCE_DEBOUNCE_MS) {
                                    TtsManager.speak(guidanceText);
                                    lastSpokenGuidance = guidanceText;
                                    lastGuidanceTime = currentTime;
                                }
                            } else if (guidanceText.isEmpty()) {
                                // Reset last spoken guidance if guidance is now "OK" (empty string)
                                lastSpokenGuidance = "";
                            }

                            // If the C++ module decoded something, send it back to CompassActivity and finish.
                            if (!decodedResult.isEmpty()) {
                                isProcessing = true; // Stop the analyzer from sending more results

                                TtsManager.speak("QR code scanned");

                                Intent resultIntent = new Intent();
                                resultIntent.putExtra("DECODED_TEXT", decodedResult);
                                setResult(RESULT_OK, resultIntent);
                                finish();
                            }
                        }
                    }
                    image.close();
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Converts an ImageProxy to a BGR Mat and applies the correct rotation.
     * @param image The ImageProxy from CameraX.
     * @return A correctly rotated Mat in BGR color space.
     */
    private Mat getRotatedBgrMatFromImage(ImageProxy image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();
        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);
        Mat yuv = new Mat(image.getHeight() + image.getHeight() / 2, image.getWidth(), CvType.CV_8UC1);
        yuv.put(0, 0, nv21);
        Mat bgrMat = new Mat();
        Imgproc.cvtColor(yuv, bgrMat, Imgproc.COLOR_YUV2BGR_NV21);
        yuv.release();

        int rotationDegrees = image.getImageInfo().getRotationDegrees();
        if (rotationDegrees != 0) {
            int rotateCode;
            switch (rotationDegrees) {
                case 90:
                    rotateCode = Core.ROTATE_90_CLOCKWISE;
                    break;
                case 180:
                    rotateCode = Core.ROTATE_180;
                    break;
                case 270:
                    rotateCode = Core.ROTATE_90_COUNTERCLOCKWISE;
                    break;
                default:
                    return bgrMat;
            }
            Core.rotate(bgrMat, bgrMat, rotateCode);
        }
        return bgrMat;
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                Log.i(TAG, "Camera permission granted.");
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}