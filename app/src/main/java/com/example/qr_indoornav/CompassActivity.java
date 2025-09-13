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

import com.example.qr_indoornav.model.Edge;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Locale;

public class CompassActivity extends AppCompatActivity implements SensorEventListener {

    private static final int PROGRESS_REQUEST_CODE = 1001;
    private static final double AVERAGE_STEP_LENGTH_METERS = 0.75; // Used for converting meters to steps

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

    // --- State Machine for Alignment ---
    private enum AlignmentState { ALIGNING, WAITING_TO_NAVIGATE, FINISHED }
    private AlignmentState currentState = AlignmentState.ALIGNING;

    // --- DYNAMIC NAVIGATION STATE ---
    private Graph graph;
    private ArrayList<String> pathNodeIds;
    private int currentLegIndex = 0;

    // --- Variables for the CURRENT leg of the journey ---
    private float targetDegree;
    private int distanceForLegMeters;
    private int stepsTakenInLeg = 0; // This tracks steps taken during ProgressActivity
    private static final int DEGREE_MARGIN_OF_ERROR = 5;

    private final Handler navigationHandler = new Handler();
    private Runnable navigationRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compass);

        // Load the map data and the path passed from NavigationActivity
        graph = MapData.getGraph(this);
        pathNodeIds = getIntent().getStringArrayListExtra("PATH_NODE_IDS");

        initializeUI();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        setupNavigationRunnable();

        // Validate the path. If invalid, exit.
        if (pathNodeIds == null || pathNodeIds.size() < 2) {
            Toast.makeText(this, "Invalid path received.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Load the data for the first leg of the journey
        loadCurrentLegData();
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

    /**
     * This is the core logic for managing the multi-leg journey.
     * It loads the direction and distance for the current segment of the path.
     */
    private void loadCurrentLegData() {
        // Check if we have completed the last leg
        if (currentLegIndex >= pathNodeIds.size() - 1) {
            currentState = AlignmentState.FINISHED;
            instructionTextView.setText("You have arrived at your destination!");
            stepsTextView.setText("0");
            targetTextView.setText("Target: Complete");
            arrowImageView.setRotation(0);
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_target_reached));
            sensorManager.unregisterListener(this); // Stop sensors
            return;
        }

        String fromNodeId = pathNodeIds.get(currentLegIndex);
        String toNodeId = pathNodeIds.get(currentLegIndex + 1);
        Edge currentEdge = graph.getNode(fromNodeId).edges.get(toNodeId);

        if (currentEdge == null) {
            Toast.makeText(this, "Error: Path data is inconsistent.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Set the dynamic targets for the current leg
        targetDegree = currentEdge.directionDegrees;
        distanceForLegMeters = currentEdge.distanceMeters;
        stepsTakenInLeg = 0; // Reset steps for the new leg

        // Update UI and reset state for the new leg
        updateTargetText();
        updateStepsText();
        currentState = AlignmentState.ALIGNING;
        instructionTextView.setText("Align for next checkpoint");
    }

    private void setupNavigationRunnable() {
        navigationRunnable = () -> {
            if (currentState == AlignmentState.WAITING_TO_NAVIGATE) {
                Intent intent = new Intent(CompassActivity.this, ProgressActivity.class);
                intent.putExtra(ProgressActivity.EXTRA_TARGET_DEGREE, targetDegree);
                // The 'total steps' for ProgressActivity is actually the distance in meters
                intent.putExtra(ProgressActivity.EXTRA_TOTAL_STEPS, distanceForLegMeters);
                // Pass the current progress (0 for a new leg, or >0 for a resumed leg)
                intent.putExtra(ProgressActivity.EXTRA_STEPS_TAKEN, stepsTakenInLeg);
                startActivityForResult(intent, PROGRESS_REQUEST_CODE);

                // We reset to aligning here, anticipating a return from ProgressActivity
                currentState = AlignmentState.ALIGNING;
            }
        };
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PROGRESS_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            // Update our step count with the progress made in ProgressActivity
            stepsTakenInLeg = data.getIntExtra(ProgressActivity.EXTRA_STEPS_TAKEN, stepsTakenInLeg);

            // Check if the leg is complete by converting steps taken to meters
            double metersCovered = stepsTakenInLeg * AVERAGE_STEP_LENGTH_METERS;
            if (metersCovered >= distanceForLegMeters) {
                Toast.makeText(this, "Checkpoint reached!", Toast.LENGTH_SHORT).show();
                currentLegIndex++;      // Move to the next leg
                loadCurrentLegData();   // Load the new leg's data
            } else {
                // User deviated. We just update the UI and wait for re-alignment.
                Toast.makeText(this, "Re-align to continue", Toast.LENGTH_SHORT).show();
                updateStepsText();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentState != AlignmentState.FINISHED) {
            Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            }
            Sensor magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (magneticField != null) {
                sensorManager.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_UI);
            }
            // Ensure we are in alignment mode when returning to this screen
            instructionTextView.setText("Align with the target direction");
            currentState = AlignmentState.ALIGNING;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        navigationHandler.removeCallbacks(navigationRunnable);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (currentState == AlignmentState.FINISHED) return; // Don't process if done

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
        float currentDegree = (float) Math.toDegrees(azimuthInRadians);
        if (currentDegree < 0) currentDegree += 360;

        float bearingToTarget = (targetDegree - currentDegree + 360) % 360;
        arrowImageView.setRotation(bearingToTarget);
        updateCurrentText(currentDegree);
        checkAlignment(currentDegree);
    }

    private void checkAlignment(float currentDegree) {
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

    /**
     * Updates the UI to show the approximate number of steps remaining for the current leg.
     */
    private void updateStepsText() {
        double metersCovered = stepsTakenInLeg * AVERAGE_STEP_LENGTH_METERS;
        double metersRemaining = Math.max(0, distanceForLegMeters - metersCovered);
        int stepsRemaining = (int) Math.ceil(metersRemaining / AVERAGE_STEP_LENGTH_METERS);
        stepsTextView.setText(String.valueOf(stepsRemaining));
    }

    private void updateCurrentText(float currentDegree) {
        int degreeValue = Math.round(currentDegree);
        String direction = getDirectionFromDegree(degreeValue);
        String currentText = getString(R.string.current_label, String.format(Locale.getDefault(), "%d°%s", degreeValue, direction));
        currentTextView.setText(currentText);
    }

    private void updateTargetText() {
        int degreeValue = Math.round(targetDegree);
        String direction = getDirectionFromDegree(degreeValue);
        String targetText = getString(R.string.target_label, String.format(Locale.getDefault(), "%d°%s", degreeValue, direction));
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