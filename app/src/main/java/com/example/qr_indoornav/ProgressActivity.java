package com.example.qr_indoornav;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast; // <-- ADD THIS IMPORT
import java.util.Locale;

public class ProgressActivity extends AppCompatActivity implements SensorEventListener {

    // --- Intent Keys (for passing data) ---
    public static final String EXTRA_TARGET_DEGREE = "EXTRA_TARGET_DEGREE";
    public static final String EXTRA_TOTAL_STEPS = "EXTRA_TOTAL_STEPS";
    public static final String EXTRA_STEPS_TAKEN = "EXTRA_STEPS_TAKEN";

    // --- UI Components ---
    private ProgressBar progressBar;
    private TextView progressPercentageText, progressStepsText;
    private ImageView deviationIndicator;
    private View dot1, dot2, dot3;

    // --- Sensor variables ---
    private SensorManager sensorManager;
    private final float[] accelerometerReading = new float[3];
    private final float[] magnetometerReading = new float[3];
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];
    private boolean hasAccelerometerData = false;
    private boolean hasMagnetometerData = false;

    // --- Navigation variables ---
    private float targetDegree;
    private int totalSteps, stepsTaken;
    private static final int ALIGNMENT_MARGIN_OF_ERROR = 15; // Margin for checking if user is facing the right way

    // --- Step Detection variables ---
    private float dynamicThreshold;
    private long lastStepTime = 0;
    private static final long STEP_TIME_GATE_MS = 300;
    private static final float USER_HEIGHT_CM = 175.0f;
    // --- NEW: Margin for checking if the STEP was in the forward direction ---
    private static final int MOVEMENT_DIRECTION_MARGIN_DEGREES = 45; // User's step can be +/- 45 degrees from target

    // --- Animation Handlers ---
    private Handler dotsHandler = new Handler();
    private boolean isScannerLaunched = false; // Add this flag
    private int dotIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_progress);

        initializeUI();
        loadIntentData();
        calibrateStepDetector();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        updateProgressUI();
        startDotsAnimation();
    }

    // ... (initializeUI, loadIntentData, calibrateStepDetector, startDotsAnimation methods are unchanged)
    private void initializeUI() {
        progressBar = findViewById(R.id.progressBar);
        progressPercentageText = findViewById(R.id.progressPercentageText);
        progressStepsText = findViewById(R.id.progressStepsText);
        deviationIndicator = findViewById(R.id.deviationIndicator);
        dot1 = findViewById(R.id.dot1);
        dot2 = findViewById(R.id.dot2);
        dot3 = findViewById(R.id.dot3);
    }
    private void loadIntentData() {
        Intent intent = getIntent();
        targetDegree = intent.getFloatExtra(EXTRA_TARGET_DEGREE, 0f);
        totalSteps = intent.getIntExtra(EXTRA_TOTAL_STEPS, 5);
        stepsTaken = intent.getIntExtra(EXTRA_STEPS_TAKEN, 0);
    }
    private void calibrateStepDetector() {
        float baseThreshold = 11.0f;
        float sensitivity = 0.05f;
        dynamicThreshold = baseThreshold + (USER_HEIGHT_CM - 170.0f) * sensitivity;
    }
    private void startDotsAnimation() {
        dotsHandler.post(new Runnable() {
            @Override
            public void run() {
                dot1.setAlpha(0.3f);
                dot2.setAlpha(0.3f);
                dot3.setAlpha(0.3f);
                if (dotIndex == 0) dot1.setAlpha(1.0f);
                else if (dotIndex == 1) dot2.setAlpha(1.0f);
                else dot3.setAlpha(1.0f);
                dotIndex = (dotIndex + 1) % 3;
                dotsHandler.postDelayed(this, 400); // Blink speed
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        Sensor magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (magneticField != null) {
            sensorManager.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        dotsHandler.removeCallbacksAndMessages(null); // Stop animation
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.length);
            hasAccelerometerData = true;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.length);
            hasMagnetometerData = true;
        }

        // Only proceed if we have fresh data from both sensors
        if (hasAccelerometerData && hasMagnetometerData) {
            // First, update the rotation matrix and check if the user is still aligned
            boolean isStillAligned = updateOrientationAndCheckAlignment();

            // Only try to detect a step if the user is facing the correct direction
            if (isStillAligned) {
                // Pass the current rotation matrix to the step detector for directional analysis
                detectStep(accelerometerReading, rotationMatrix);
            }
        }
    }

    /**
     * MODIFIED: This method now takes the full acceleration vector and the current rotation matrix.
     */
    private void detectStep(float[] acceleration, float[] currentRotationMatrix) {
        long now = System.currentTimeMillis();
        if (now - lastStepTime < STEP_TIME_GATE_MS) return;

        float magnitude = (float) Math.sqrt(acceleration[0] * acceleration[0] + acceleration[1] * acceleration[1] + acceleration[2] * acceleration[2]);

        if (magnitude > dynamicThreshold) {
            if (isStepInForwardDirection(acceleration, currentRotationMatrix)) {
                lastStepTime = now;
                stepsTaken++;
                updateProgressUI(); // updateProgressUI will now contain the trigger

                if (stepsTaken >= totalSteps) {
                    handleArrival();
                }
            }
        }
    }

    /**
     * NEW: This is the core logic for detecting forward movement.
     * It transforms the phone's acceleration into world coordinates and checks its direction.
     * @return true if the step's primary horizontal acceleration is towards the target direction.
     */
    private boolean isStepInForwardDirection(float[] acceleration, float[] currentRotationMatrix) {
        float[] accelerationInWorldCoords = new float[3];
        // We need to multiply the rotation matrix by the acceleration vector.
        // The formula for this transformation is:
        // x' = R[0]*x + R[1]*y + R[2]*z
        // y' = R[3]*x + R[4]*y + R[5]*z
        // z' = R[6]*x + R[7]*y + R[8]*z
        accelerationInWorldCoords[0] = currentRotationMatrix[0] * acceleration[0] + currentRotationMatrix[1] * acceleration[1] + currentRotationMatrix[2] * acceleration[2];
        accelerationInWorldCoords[1] = currentRotationMatrix[3] * acceleration[0] + currentRotationMatrix[4] * acceleration[1] + currentRotationMatrix[5] * acceleration[2];
        // We don't need the Z (vertical) component for directional checking.

        // Now, get the direction of the horizontal movement (x', y') in degrees.
        // Note: In the world coordinate system from Android sensors, +Y is North, +X is East.
        // atan2(x, y) gives the angle from the +Y axis (North).
        float movementAzimuth = (float) Math.toDegrees(Math.atan2(accelerationInWorldCoords[0], accelerationInWorldCoords[1]));
        if (movementAzimuth < 0) {
            movementAzimuth += 360;
        }

        // Calculate the angular difference between movement direction and target direction.
        float difference = Math.abs(movementAzimuth - targetDegree);
        if (difference > 180) {
            difference = 360 - difference;
        }

        // If the difference is within our margin, it's a valid forward step.
        return difference <= MOVEMENT_DIRECTION_MARGIN_DEGREES;
    }

    /**
     * MODIFIED: This method now returns a boolean indicating if the user is aligned.
     */
    private boolean updateOrientationAndCheckAlignment() {
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);
        float currentDegree = (float) Math.toDegrees(orientationAngles[0]);
        if (currentDegree < 0) {
            currentDegree += 360;
        }

        float difference = Math.abs(currentDegree - targetDegree);
        if (difference > 180) {
            difference = 360 - difference;
        }

        if (difference > ALIGNMENT_MARGIN_OF_ERROR) {
            handleDeviation();
            return false; // User is NOT aligned
        }
        return true; // User IS aligned
    }

    private void handleDeviation() {
        // ... (This method is unchanged)
        sensorManager.unregisterListener(this);
        dotsHandler.removeCallbacksAndMessages(null);
        deviationIndicator.setVisibility(View.VISIBLE);
        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_STEPS_TAKEN, stepsTaken);
        setResult(RESULT_OK, resultIntent);
        new Handler().postDelayed(this::finish, 1500);
    }

    private void handleArrival() {
        progressStepsText.setText("You have arrived!");
        dotsHandler.removeCallbacksAndMessages(null);
        // This stops all sensor listening to prevent further steps/deviation checks
        sensorManager.unregisterListener(this);
        // TODO: In a real app, you might want to automatically navigate to the next screen or show a finish button.
    }

    private void updateProgressUI() {
        int remainingSteps = Math.max(0, totalSteps - stepsTaken);
        int progress = (totalSteps > 0) ? (int) ((stepsTaken / (float) totalSteps) * 100) : 0;

        progressBar.setProgress(progress);
        progressPercentageText.setText(String.format(Locale.getDefault(), "%d%%", progress));
        progressStepsText.setText(String.format(Locale.getDefault(), "%d steps left", remainingSteps));

        // --- TRIGGER LOGIC ---
        if (progress >= 80 && !isScannerLaunched) {
            isScannerLaunched = true; // Prevent multiple launches
            Toast.makeText(this, "Approaching destination, starting QR scanner...", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(this, QRScannerActivity.class);
            startActivity(intent);
            // Optionally finish this activity if you don't want the user to return to it
            finish();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* Not used */ }
}