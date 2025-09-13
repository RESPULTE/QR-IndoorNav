package com.example.qr_indoornav;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class CompassActivity extends AppCompatActivity implements SensorEventListener {

    private static final int PROGRESS_REQUEST_CODE = 1001;

    // --- UI Components ---
    private ImageView arrowImageView;
    private TextView targetTextView, currentTextView, stepsTextView, instructionTextView;
    private MaterialCardView compassBackgroundCard;

    // --- Sensor variables ---
    private SensorManager sensorManager;
    private final float[] accelerometerReading = new float[3];
    private final float[] magnetometerReading = new float[3];
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    // --- Navigation State ---
    private enum AlignmentState { ALIGNING, WAITING_TO_NAVIGATE }
    private AlignmentState currentState = AlignmentState.ALIGNING;

    // --- Navigation variables ---
    private float currentDegree = 0f;
    private float targetDegree = 180f;
    private int totalStepsForLeg = 30;
    private int stepsTaken = 0; // This will be updated when ProgressActivity returns
    private static final int DEGREE_MARGIN_OF_ERROR = 5;

    private Handler navigationHandler = new Handler();
    private Runnable navigationRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compass);

        initializeUI();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        setupNavigationRunnable();
        updateTargetText();
        updateStepsText();
    }

    private void initializeUI() {
        arrowImageView = findViewById(R.id.arrowImageView);
        targetTextView = findViewById(R.id.targetTextView);
        currentTextView = findViewById(R.id.currentTextView);
        stepsTextView = findViewById(R.id.stepsTextView);
        instructionTextView = findViewById(R.id.instructionTextView);
        compassBackgroundCard = findViewById(R.id.compassBackgroundCard);
        SwitchMaterial audioSwitch = findViewById(R.id.audioSwitch);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        audioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String state = isChecked ? "enabled" : "disabled";
            Toast.makeText(this, "Audio assistance " + state, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupNavigationRunnable() {
        navigationRunnable = () -> {
            if (currentState == AlignmentState.WAITING_TO_NAVIGATE) {
                Intent intent = new Intent(CompassActivity.this, ProgressActivity.class);
                intent.putExtra(ProgressActivity.EXTRA_TARGET_DEGREE, targetDegree);
                intent.putExtra(ProgressActivity.EXTRA_TOTAL_STEPS, totalStepsForLeg);
                intent.putExtra(ProgressActivity.EXTRA_STEPS_TAKEN, stepsTaken);
                startActivityForResult(intent, PROGRESS_REQUEST_CODE);
                // Reset to aligning for when we come back
                currentState = AlignmentState.ALIGNING;
            }
        };
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
        // When we return to this screen, ensure we're in the aligning state
        instructionTextView.setText("Align with the target direction");
        currentState = AlignmentState.ALIGNING;
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        navigationHandler.removeCallbacks(navigationRunnable);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PROGRESS_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            // User deviated, so update our step count from the progress activity
            stepsTaken = data.getIntExtra(ProgressActivity.EXTRA_STEPS_TAKEN, stepsTaken);
            Toast.makeText(this, "Re-align to continue", Toast.LENGTH_SHORT).show();
            updateStepsText();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.length);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.length);
        }
        updateOrientationAngles();
    }

    public void updateOrientationAngles() {
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);
        float azimuthInRadians = orientationAngles[0];
        currentDegree = (float) Math.toDegrees(azimuthInRadians);
        if (currentDegree < 0) currentDegree += 360;

        float bearingToTarget = (targetDegree - currentDegree + 360) % 360;
        arrowImageView.setRotation(bearingToTarget);
        updateCurrentText();
        checkAlignment();
    }

    private void checkAlignment() {
        float difference = Math.abs(currentDegree - targetDegree);
        if (difference > 180) difference = 360 - difference;

        boolean isOnTarget = difference <= DEGREE_MARGIN_OF_ERROR;

        if (isOnTarget) {
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_target_reached));
            if (currentState == AlignmentState.ALIGNING) {
                currentState = AlignmentState.WAITING_TO_NAVIGATE;
                instructionTextView.setText("Hold steady...");
                navigationHandler.postDelayed(navigationRunnable, 2000);
            }
        } else {
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_default));
            if (currentState == AlignmentState.WAITING_TO_NAVIGATE) {
                currentState = AlignmentState.ALIGNING;
                instructionTextView.setText("Align with the target direction");
                navigationHandler.removeCallbacks(navigationRunnable);
            }
        }
    }

    // --- UI Update Helper Methods ---
    private void updateStepsText() {
        int remainingSteps = Math.max(0, totalStepsForLeg - stepsTaken);
        stepsTextView.setText(String.valueOf(remainingSteps));
    }

    private void updateCurrentText() {
        int degreeValue = Math.round(currentDegree);
        String direction = getDirectionFromDegree(degreeValue);
        String currentText = getString(R.string.current_label, degreeValue + "°" + direction);
        currentTextView.setText(currentText);
    }

    private void updateTargetText() {
        int degreeValue = Math.round(targetDegree);
        String direction = getDirectionFromDegree(degreeValue);
        String targetText = getString(R.string.target_label, degreeValue + "°" + direction);
        targetTextView.setText(targetText);
    }

    private String getDirectionFromDegree(int degree) {
        int normalizedDegree = degree % 360;
        if (normalizedDegree >= 338 || normalizedDegree < 23) return "N";
        if (normalizedDegree < 68) return "NE";
        if (normalizedDegree < 113) return "E";
        if (normalizedDegree < 158) return "SE";
        if (normalizedDegree < 203) return "S";
        if (normalizedDegree < 248) return "SW";
        if (normalizedDegree < 293) return "W";
        if (normalizedDegree < 338) return "NW";
        return "";
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* Not used */ }
}