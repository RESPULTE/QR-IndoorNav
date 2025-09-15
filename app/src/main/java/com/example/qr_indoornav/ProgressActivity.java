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
import android.util.Log; // Import Log for debugging
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * ProgressActivity displays the user's walking progress towards the next checkpoint.
 * It uses {@link StepDetector} to count steps and monitors orientation for deviations.
 * It returns the final step count and outcome (completed/cancelled) to {@link CompassActivity}.
 */
public class ProgressActivity extends AppCompatActivity implements SensorEventListener, StepDetector.OnStepListener {

    private static final String TAG = "ProgressActivity";

    // --- Keys for receiving data from Intent ---
    public static final String EXTRA_TARGET_DEGREE = "EXTRA_TARGET_DEGREE";
    public static final String EXTRA_DISTANCE_METERS = "EXTRA_DISTANCE_METERS";
    public static final String EXTRA_INITIAL_STEPS = "EXTRA_INITIAL_STEPS"; // Key to receive initial steps

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
    private static final int ALIGNMENT_MARGIN_OF_ERROR = 15; // Wider margin during active walking

    // --- Unified Step Detection Module ---
    private StepDetector stepDetector;

    // --- Animation Handler for blinking dots ---
    private final Handler dotsHandler = new Handler();
    private int dotIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_progress);

        // Instantiate our unified StepDetector and pass this activity as the listener.
        stepDetector = new StepDetector(this);

        initializeUI();
        loadIntentData(); // Load data from the Intent and set initial state for StepDetector

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        updateProgressUI(); // Show initial state (progress based on initial steps)
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

    /**
     * Loads navigation parameters (target degree, total distance, initial steps)
     * from the {@link Intent} that started this activity.
     */
    private void loadIntentData() {
        Intent intent = getIntent();
        targetDegree = intent.getFloatExtra(EXTRA_TARGET_DEGREE, 0f);
        totalDistanceMeters = intent.getIntExtra(EXTRA_DISTANCE_METERS, 30);

        // Retrieve the initial steps taken for this leg from the calling activity
        int initialSteps = intent.getIntExtra(EXTRA_INITIAL_STEPS, 0);
        stepDetector.reset(initialSteps); // Set the StepDetector to start counting from this value
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
        // Register sensor listeners when the activity becomes active
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
        // Unregister sensor listeners and stop animations to save battery and resources
        sensorManager.unregisterListener(this);
        dotsHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Callback method for {@link SensorEventListener} when sensor values change.
     * It feeds the raw sensor data to the {@link StepDetector}.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.length);
            hasAccelerometerData = true;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.length);
            hasMagnetometerData = true;
        }

        // Only proceed if we have valid data from both accelerometer and magnetometer
        if (hasAccelerometerData && hasMagnetometerData) {
            boolean isStillAligned = updateOrientationAndCheckAlignment(); // Check if user is still facing the right way
            if (isStillAligned) {
                // Delegate step detection to our unified StepDetector module
                // Pass current accelerometer data, device rotation, and target direction
                stepDetector.processSensorData(accelerometerReading, rotationMatrix, targetDegree);
            }
        }
    }

    /**
     * This is the required callback method from our {@link StepDetector.OnStepListener} interface.
     * It is automatically called by the {@link StepDetector} whenever a valid step is detected.
     * @param totalSteps The new total number of steps taken for this leg.
     */
    @Override
    public void onStep(int totalSteps) {
        updateProgressUI(); // Update the UI to reflect the new step count

        // Check if the destination for this leg has been reached
        if (stepDetector.getMetersCovered() >= totalDistanceMeters) {
            finishWithResult(RESULT_OK); // Finish and signal success
        }
    }

    /**
     * Determines the device's current orientation and checks if it aligns with the target direction.
     * If deviation is too high, it triggers {@link #handleDeviation()}.
     * @return true if the user is currently aligned with the target, false otherwise.
     */
    private boolean updateOrientationAndCheckAlignment() {
        // Calculate the device's rotation matrix
        float[] orientationAngles = new float[3]; // Used only for calculating currentDegree
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
        SensorManager.getOrientation(rotationMatrix, orientationAngles); // Calculate orientation angles from the rotation matrix

        float currentDegree = (float) Math.toDegrees(orientationAngles[0]);
        if (currentDegree < 0) currentDegree += 360; // Normalize to 0-360 degrees

        float difference = Math.abs(currentDegree - targetDegree);
        if (difference > 180) difference = 360 - difference; // Find the shortest angular difference

        if (difference > ALIGNMENT_MARGIN_OF_ERROR) {
            handleDeviation(); // User has deviated too much
            return false;
        }
        return true;
    }

    /**
     * Called when the user deviates too far from the target direction.
     * It signals {@link CompassActivity} that the progress was interrupted.
     */
    private void handleDeviation() {
        // Stop sensor processing and animations
        sensorManager.unregisterListener(this);
        dotsHandler.removeCallbacksAndMessages(null);

        deviationIndicator.setVisibility(View.VISIBLE); // Show the red 'X' indicator

        finishWithResult(RESULT_CANCELED); // Signal interruption
    }

    /**
     * NEW: A unified method to finish the activity and send back the current step count.
     * This is called on arrival (RESULT_OK), deviation (RESULT_CANCELED), or when the back button is pressed.
     * @param resultCode Either {@link android.app.Activity#RESULT_OK} (completed) or {@link android.app.Activity#RESULT_CANCELED} (interrupted).
     */
    private void finishWithResult(int resultCode) {
        // Make sure sensors and animations are stopped if not already
        sensorManager.unregisterListener(this);
        dotsHandler.removeCallbacksAndMessages(null);

        // Show appropriate Toast message
        if (resultCode == RESULT_OK) {
            Toast.makeText(this, "Leg completed!", Toast.LENGTH_SHORT).show();
        } else if (resultCode == RESULT_CANCELED) {
            // Toast for deviation is handled by CompassActivity
            Toast.makeText(this, "Direction deviated!", Toast.LENGTH_SHORT).show();
        }

        // Prepare the result Intent to send back the accumulated step count
        Intent resultIntent = new Intent();
        resultIntent.putExtra("EXTRA_STEPS_TAKEN", stepDetector.getStepsTaken());
        setResult(resultCode, resultIntent);

        // Delay finishing to allow the user to see any final messages/indicators
        new Handler().postDelayed(this::finish, 1500);
    }

    /**
     * Updates the UI elements (progress bar, percentage, steps remaining) based on
     * the current step count from the {@link StepDetector}.
     */
    private void updateProgressUI() {
        // Get remaining steps directly from the detector
        int stepsRemaining = stepDetector.getRemainingSteps(totalDistanceMeters);

        // Calculate progress percentage based on meters covered
        double metersCovered = stepDetector.getMetersCovered();
        int progress = (totalDistanceMeters > 0) ? (int) ((metersCovered / totalDistanceMeters) * 100) : 0;
        progress = Math.min(100, progress); // Cap progress at 100%

        progressBar.setProgress(progress);
        progressPercentageText.setText(String.format(Locale.getDefault(), "%d%%", progress));
        progressStepsText.setText(String.format(Locale.getDefault(), "%d steps left", stepsRemaining));
    }

    /**
     * Overrides the default back button behavior to send back a {@link #RESULT_CANCELED}
     * with the current progress.
     */
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finishWithResult(RESULT_CANCELED);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* Not used */ }
}