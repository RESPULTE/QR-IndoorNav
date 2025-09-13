package com.example.qr_indoornav;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QRScannerActivity extends AppCompatActivity {
    private static final String TAG = "QRScannerActivity";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 101;

    private PreviewView previewView;
    private ExecutorService cameraExecutor;

    // Load the native C++ library we built
    static {
        System.loadLibrary("qr_indoornav");
    }

    // Define the native method that will be implemented in C++
    public native void processFrame(long matAddr);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qrscanner);

        previewView = findViewById(R.id.cameraPreview);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Check for permissions. If granted, onResume will handle starting the camera.
        // If not, request them.
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The best practice is to initialize OpenCV here.
        // It ensures the library is loaded every time the app is resumed.
        if (OpenCVLoader.initDebug()) {
            Log.i(TAG, "OpenCV library found inside package. Using it!");
            // Now that OpenCV is loaded, check permissions again and start the camera.
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Log.w(TAG, "Permissions not granted. Camera will not start.");
            }
        } else {
            Log.e(TAG, "Internal OpenCV library not found.");
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
                    // Convert the ImageProxy to a Mat object
                    Mat bgrMat = imageProxyToBgrMat(image);
                    if (bgrMat != null) {
                        // Call the native C++ function, passing the memory address of the Mat
                        processFrame(bgrMat.getNativeObjAddr());

                        // IMPORTANT: After the C++ code modifies the Mat, you would need
                        // a separate ImageView overlay to display the result. The PreviewView
                        // shows the direct camera feed. This setup correctly processes the frame.

                        bgrMat.release(); // Crucial to release the Mat to prevent memory leaks
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
     * Converts a CameraX ImageProxy object in YUV_420_888 format to an OpenCV Mat in BGR format.
     * The C++ code expects a BGR image.
     */
    private Mat imageProxyToBgrMat(androidx.camera.core.ImageProxy image) {
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

        Mat bgr = new Mat();
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_NV21);
        yuv.release();
        return bgr;
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                // Permission was granted. onResume will be called automatically,
                // which will then load OpenCV and start the camera.
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