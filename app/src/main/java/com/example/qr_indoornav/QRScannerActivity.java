package com.example.qr_indoornav;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy; // Use the specific class for clarity
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
import org.opencv.core.Core; // NEW: Import Core for rotation constants
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QRScannerActivity extends AppCompatActivity {
    private static final String TAG = "QRScannerActivity";

    private boolean isDecoding = false; // Add a flag to prevent multiple decodes
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 101;

    private PreviewView previewView;
    private ImageView overlayImageView;
    private ExecutorService cameraExecutor;
    private Bitmap bitmapForOverlay;

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
                    // Prevent processing new frames if we're already handling a decoded result
                    if (isDecoding) {
                        image.close();
                        return;
                    }
                    // The conversion AND rotation is now handled in a single helper function.
                    Mat bgrMat = getRotatedBgrMatFromImage(image);

                    if (bgrMat != null) {
                        // After this call, bgrMat is now RGBA with drawings on it.
                        String decodedResult = processFrame(bgrMat.getNativeObjAddr());

                        if (bitmapForOverlay == null || bitmapForOverlay.getWidth() != bgrMat.cols() || bitmapForOverlay.getHeight() != bgrMat.rows()) {
                            // Create or recreate the Bitmap if the dimensions change (e.g., device rotation)
                            bitmapForOverlay = Bitmap.createBitmap(bgrMat.cols(), bgrMat.rows(), Bitmap.Config.ARGB_8888);
                        }

                        Utils.matToBitmap(bgrMat, bitmapForOverlay);
                        runOnUiThread(() -> overlayImageView.setImageBitmap(bitmapForOverlay));

                        bgrMat.release();

                        if (decodedResult != null && !decodedResult.isEmpty()) {
                            isDecoding = true; // Set flag to stop further processing

                            // Create a result Intent to send the data back
                            Intent resultIntent = new Intent();
                            resultIntent.putExtra("DECODED_TEXT", decodedResult);
                            setResult(RESULT_OK, resultIntent);
                            finish(); // Close this activity and return to CompassActivity
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
     * NEW and IMPROVED: Converts an ImageProxy to a BGR Mat and applies the correct rotation.
     *
     * @param image The ImageProxy from CameraX.
     * @return A correctly rotated Mat in BGR color space.
     */
    private Mat getRotatedBgrMatFromImage(ImageProxy image) {
        // 1. Convert YUV to BGR Mat as before
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

        // 2. Get the rotation degrees from the ImageProxy
        int rotationDegrees = image.getImageInfo().getRotationDegrees();

        // 3. Rotate the Mat based on the degrees
        //    The C++ code expects an upright image.
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
                    // Should not happen, but as a fallback, don't rotate.
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