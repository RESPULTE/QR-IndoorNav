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
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class ProgressActivity extends AppCompatActivity implements SensorEventListener, StepDetector.OnStepListener {

    private static final String TAG = "ProgressActivity";

    // --- Intent Keys ---
    public static final String EXTRA_TARGET_DEGREE = "EXTRA_TARGET_DEGREE";
    public static final String EXTRA_DISTANCE_METERS = "EXTRA_DISTANCE_METERS";
    public static final String EXTRA_INITIAL_STEPS = "EXTRA_INITIAL_STEPS";

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
    private boolean hasAccelerometerData = false;
    private boolean hasMagnetometerData = false;

    // --- Navigation variables ---
    private float targetDegree;
    private int totalDistanceMeters;
    private int totalStepsForLeg; // --- NEW: Calculate total steps for this leg
    private static final int ALIGNMENT_MARGIN_OF_ERROR = 15;
    private StepDetector stepDetector;

    // --- NEW: TTS State Tracking ---
    private boolean hasAnnouncedHalfway = false;
    private boolean hasAnnouncedClosingIn = false;

    private int dotIndex = 0;

    // --- Animation Handler ---
    private final Handler dotsHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_progress);

        stepDetector = new StepDetector(this);

        initializeUI();
        loadIntentData();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        updateProgressUI();
        startDotsAnimation();
    }

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
        totalDistanceMeters = intent.getIntExtra(EXTRA_DISTANCE_METERS, 30);
        int initialSteps = intent.getIntExtra(EXTRA_INITIAL_STEPS, 0);

        // --- NEW: Calculate total steps for this leg and announce it ---
        totalStepsForLeg = (int) (totalDistanceMeters / StepDetector.AVERAGE_STEP_LENGTH_METERS);
        stepDetector.reset(initialSteps);

        Log.d(TAG, "ProgressActivity loaded: Target=" + targetDegree + ", Distance=" + totalDistanceMeters + ", Initial Steps=" + initialSteps);
    }


    /**
     * Starts the blinking animation for the three dots at the bottom of the screen.
     */
    private void startDotsAnimation() {
        dotsHandler.post(new Runnable() {
            @Override
            public void run() {
                // Dim all dots
                dot1.setAlpha(0.3f);
                dot2.setAlpha(0.3f);
                dot3.setAlpha(0.3f);

                // Highlight the current dot in the sequence
                if (dotIndex == 0) dot1.setAlpha(1.0f);
                else if (dotIndex == 1) dot2.setAlpha(1.0f);
                else dot3.setAlpha(1.0f);

                dotIndex = (dotIndex + 1) % 3; // Cycle through 0, 1, 2
                dotsHandler.postDelayed(this, 400); // Repeat every 400ms
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

        // --- NEW: Announce the required steps when activity starts/resumes ---
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            int stepsRemaining = stepDetector.getRemainingSteps(totalDistanceMeters);
            TtsManager.speak("Walk straight. " + stepsRemaining + " steps to go.");
        }, 500); // Short delay to allow activity to settle
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        dotsHandler.removeCallbacksAndMessages(null);
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

        if (hasAccelerometerData && hasMagnetometerData) {
            boolean isStillAligned = updateOrientationAndCheckAlignment();
            if (isStillAligned) {
                stepDetector.processSensorData(accelerometerReading, rotationMatrix, targetDegree);
            }
        }
    }

    @Override
    public void onStep(int totalSteps) {
        updateProgressUI();

        // --- NEW: TTS announcements based on step progress ---
        checkStepMilestones(totalSteps);

        if (stepDetector.getMetersCovered() >= totalDistanceMeters) {
            TtsManager.speak("You have arrived. Look for the QR code to scan.");
            finishWithResult(RESULT_OK);
        }
    }

    // --- NEW: Method to handle TTS for step milestones ---
    private void checkStepMilestones(int currentSteps) {
        // Check for halfway point (only for longer paths and if not already announced)
        if (totalStepsForLeg > 10 && !hasAnnouncedHalfway && currentSteps >= totalStepsForLeg / 2) {
            TtsManager.speak("You are halfway there.");
            hasAnnouncedHalfway = true;
        }

        // Check for closing in point (less than 5 steps remaining)
        int stepsRemaining = stepDetector.getRemainingSteps(totalDistanceMeters);
        if (stepsRemaining < 5 && !hasAnnouncedClosingIn) {
            TtsManager.speak("Almost there, just a few more steps.");
            hasAnnouncedClosingIn = true;
        }
    }

    private boolean updateOrientationAndCheckAlignment() {
        float[] orientationAngles = new float[3];
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        float currentDegree = (float) Math.toDegrees(orientationAngles[0]);
        if (currentDegree < 0) currentDegree += 360;

        float difference = Math.abs(currentDegree - targetDegree);
        if (difference > 180) difference = 360 - difference;

        if (difference > ALIGNMENT_MARGIN_OF_ERROR) {
            handleDeviation(currentDegree, targetDegree); // Pass degrees for TTS feedback
            return false;
        }
        return true;
    }

    // --- MODIFIED: Added parameters for specific TTS guidance ---
    private void handleDeviation(float currentDegree, float targetDegree) {
        sensorManager.unregisterListener(this);
        dotsHandler.removeCallbacksAndMessages(null);
        deviationIndicator.setVisibility(View.VISIBLE);

        // --- NEW: Immediate TTS feedback with re-alignment instructions ---
        float diff = targetDegree - currentDegree;
        if (diff > 180) diff -= 360;
        else if (diff < -180) diff += 360;

        String direction = (diff > 0) ? "right" : "left";

        finishWithResult(RESULT_CANCELED);
    }

    private void finishWithResult(int resultCode) {
        sensorManager.unregisterListener(this);
        dotsHandler.removeCallbacksAndMessages(null);

        if (resultCode == RESULT_OK) {
            Toast.makeText(this, "Leg completed!", Toast.LENGTH_SHORT).show();
        } else if (resultCode == RESULT_CANCELED) {
            Toast.makeText(this, "Direction deviated!", Toast.LENGTH_SHORT).show();
        }

        Intent resultIntent = new Intent();
        resultIntent.putExtra("EXTRA_STEPS_TAKEN", stepDetector.getStepsTaken());
        setResult(resultCode, resultIntent);

        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1500);
    }

    private void updateProgressUI() {
        int stepsRemaining = stepDetector.getRemainingSteps(totalDistanceMeters);
        double metersCovered = stepDetector.getMetersCovered();
        int progress = (totalDistanceMeters > 0) ? (int) ((metersCovered / totalDistanceMeters) * 100) : 0;
        progress = Math.min(100, progress);

        progressBar.setProgress(progress);
        progressPercentageText.setText(String.format(Locale.getDefault(), "%d%%", progress));
        progressStepsText.setText(String.format(Locale.getDefault(), "%d steps left", stepsRemaining));
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finishWithResult(RESULT_CANCELED);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* Not used */ }
}