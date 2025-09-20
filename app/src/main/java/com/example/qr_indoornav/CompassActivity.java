package com.example.qr_indoornav;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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

import com.example.qr_indoornav.model.Location;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public class CompassActivity extends AppCompatActivity implements SensorEventListener {

    // --- Request codes ---
    private static final int PROGRESS_REQUEST_CODE = 1001;
    private static final int QR_SCANNER_REQUEST_CODE = 1002;

    // --- UI Components ---
    private ImageView arrowImageView;
    private TextView targetTextView, currentTextView, instructionTextView;
    private MaterialCardView compassBackgroundCard;
    private TimelineView timelineView;

    // --- Sensor variables ---
    private SensorManager sensorManager;
    private StepDetector stepDetector;
    private final float[] accelerometerReading = new float[3];
    private final float[] magnetometerReading = new float[3];
    private final float[] rotationMatrix = new float[9];

    // --- State Machine ---
    private enum AlignmentState { ALIGNING, WAITING_TO_NAVIGATE, FINISHED }
    private AlignmentState currentState = AlignmentState.ALIGNING;

    // --- NAVIGATION STATE (Refactored) ---
    private List<PathFinder.PathLeg> pathLegs; // The single source of truth for the path
    private List<Location> timelineLocations; // Location objects for the UI timeline
    private int currentLegIndex = 0; // Index of the leg to execute from the pathLegs list
    private int stepsTakenInLeg = 0; // Steps accumulated for the current leg

    private final Handler navigationHandler = new Handler();
    private Runnable navigationRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compass);

        // Receive the pre-calculated path legs from NavigationActivity
        pathLegs = (ArrayList<PathFinder.PathLeg>) getIntent().getSerializableExtra(NavigationActivity.EXTRA_PATH_LEGS);

        // Validate the received path
        if (pathLegs == null || pathLegs.isEmpty()) {
            Toast.makeText(this, "Invalid navigation path received.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initializeUI();
        setupSensors();
        setupTimeline();
        setupNavigationRunnable();

        loadCurrentLegData();
    }

    private void initializeUI() {
        arrowImageView = findViewById(R.id.arrowImageView);
        targetTextView = findViewById(R.id.targetTextView);
        currentTextView = findViewById(R.id.currentTextView);
        instructionTextView = findViewById(R.id.instructionTextView);
        compassBackgroundCard = findViewById(R.id.compassBackgroundCard);
        timelineView = findViewById(R.id.timelineView);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        // Other UI setup as before...
    }

    private void setupSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        stepDetector = new StepDetector(null);
    }

    /**
     * Constructs the list of Location objects for the UI timeline from the path legs.
     */
    private void setupTimeline() {
        ArrayList<String> pathIds = new ArrayList<>();
        pathIds.add(pathLegs.get(0).fromId); // Add the starting point
        for (PathFinder.PathLeg leg : pathLegs) {
            pathIds.add(leg.toId); // Add each destination point
        }

        this.timelineLocations = pathIds.stream()
                .map(MapData::getLocationById)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Loads data for the current leg directly from the pre-calculated pathLegs list.
     * NO internal calculations are performed.
     */
    private void loadCurrentLegData() {
        // Check if the journey is complete
        if (currentLegIndex >= pathLegs.size()) {
            currentState = AlignmentState.FINISHED;
            instructionTextView.setText(R.string.destination_reached);
            targetTextView.setText(R.string.target_complete);
            arrowImageView.setRotation(0);
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_target_reached));
            sensorManager.unregisterListener(this);
            updateTimeline();
            return;
        }

        // Get the current leg's pre-calculated data
        PathFinder.PathLeg currentLeg = pathLegs.get(currentLegIndex);

        // Update UI for the current leg
        updateTargetText(currentLeg.direction);
        updateTimeline();
        currentState = AlignmentState.ALIGNING;
        instructionTextView.setText(R.string.align_for_checkpoint);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PROGRESS_REQUEST_CODE) {
            if (data != null) {
                stepsTakenInLeg = data.getIntExtra("EXTRA_STEPS_TAKEN", stepsTakenInLeg);
            }
            if (resultCode == RESULT_OK) {
                launchQrScanner();
            } else {
                Toast.makeText(this, "Navigation paused. Re-align to resume.", Toast.LENGTH_SHORT).show();
                loadCurrentLegData();
            }
        } else if (requestCode == QR_SCANNER_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                verifyScanAndUpdateJourney(data.getStringExtra("DECODED_TEXT"));
            } else {
                Toast.makeText(this, "QR scan cancelled. Please scan to continue.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Verifies the scanned QR code against the expected destination of the current leg.
     */
    private void verifyScanAndUpdateJourney(String decodedJson) {
        QRParser.ScannedQRData scannedData = QRParser.parse(decodedJson);
        if (scannedData.type == QRParser.ScannedQRData.QRType.INVALID) {
            showErrorDialog("valid QR code", "Invalid Data");
            return;
        }

        String expectedNextNodeId = pathLegs.get(currentLegIndex).toId;
        String finalDestinationId = pathLegs.get(pathLegs.size() - 1).toId;

        if (scannedData.id.equals(expectedNextNodeId)) {
            // SUCCESS: Scanned QR matches the expected stop.
            Toast.makeText(this, "Correct Location: " + scannedData.id, Toast.LENGTH_SHORT).show();
            currentLegIndex++; // Advance to the next leg

            if (scannedData.id.equals(finalDestinationId)) {
                showSuccessDialog("You have arrived at your final destination: " + finalDestinationId + "!", true);
            } else {
                int remainingStops = pathLegs.size() - currentLegIndex;
                String message = "You have arrived at " + expectedNextNodeId + ".\n" +
                        remainingStops + " more stop(s) to go.";
                showSuccessDialog(message, false);
            }
        } else {
            // FAILURE: Scanned QR does not match.
            showErrorDialog(expectedNextNodeId, scannedData.id);
        }
    }

    private void setupNavigationRunnable() {
        navigationRunnable = () -> {
            if (currentState == AlignmentState.WAITING_TO_NAVIGATE) {
                PathFinder.PathLeg currentLeg = pathLegs.get(currentLegIndex);

                Intent intent = new Intent(CompassActivity.this, ProgressActivity.class);
                intent.putExtra(ProgressActivity.EXTRA_TARGET_DEGREE, currentLeg.direction);
                intent.putExtra(ProgressActivity.EXTRA_DISTANCE_METERS, currentLeg.distance);
                intent.putExtra(ProgressActivity.EXTRA_INITIAL_STEPS, stepsTakenInLeg);
                startActivityForResult(intent, PROGRESS_REQUEST_CODE);
                currentState = AlignmentState.ALIGNING;
            }
        };
    }

    // --- The rest of the file (sensor handling, UI updates, dialogs) is functionally unchanged, ---
    // --- but simplified as it relies on the pre-calculated data. ---

    private void launchQrScanner() {
        Intent scannerIntent = new Intent(this, QRScannerActivity.class);
        String expectedNextNodeId = pathLegs.get(currentLegIndex).toId;
        int remainingLegs = pathLegs.size() - 1 - currentLegIndex;
        scannerIntent.putExtra("EXPECTED_NODE_ID", expectedNextNodeId);
        scannerIntent.putExtra("REMAINING_LEGS", remainingLegs);
        startActivityForResult(scannerIntent, QR_SCANNER_REQUEST_CODE);
    }

    private void showSuccessDialog(String message, boolean isFinished) {
        new AlertDialog.Builder(this)
                .setTitle("Checkpoint Reached!")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Proceed", (dialog, which) -> {
                    if (isFinished) {
                        loadCurrentLegData(); // Load final "arrived" state
                    } else {
                        stepsTakenInLeg = 0; // Reset for new leg
                        loadCurrentLegData(); // Load next leg's data
                    }
                })
                .show();
    }

    private void showErrorDialog(String expectedId, String scannedId) {
        new AlertDialog.Builder(this)
                .setTitle("QR Code Mismatch")
                .setMessage("Expected: " + expectedId + "\nScanned: " + scannedId + "\n\nPlease find the correct QR code and try again.")
                .setCancelable(false)
                .setPositiveButton("Rescan", (dialog, which) -> launchQrScanner())
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .show();
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
            currentState = AlignmentState.ALIGNING;
            instructionTextView.setText(R.string.align_for_checkpoint);
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
        if (currentState == AlignmentState.FINISHED) return;
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.length);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.length);
        }
        updateOrientationAngles();
    }

    public void updateOrientationAngles() {
        if (currentState == AlignmentState.FINISHED || pathLegs.isEmpty() || currentLegIndex >= pathLegs.size()) return;
        if (accelerometerReading[0] == 0 && magnetometerReading[0] == 0) return;

        float targetDegree = pathLegs.get(currentLegIndex).direction;

        float[] orientationAngles = new float[3];
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);
        float azimuthInRadians = orientationAngles[0];
        float currentDegree = (float) Math.toDegrees(azimuthInRadians);
        if (currentDegree < 0) currentDegree += 360;
        float bearingToTarget = (targetDegree - currentDegree + 360) % 360;
        arrowImageView.setRotation(bearingToTarget);
        updateCurrentText(currentDegree);
        checkAlignment(currentDegree, targetDegree);
    }

    private void checkAlignment(float currentDegree, float targetDegree) {
        boolean isOnTarget = stepDetector.isAlignedWithTarget(currentDegree, targetDegree);
        if (isOnTarget) {
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_target_reached));
            if (currentState == AlignmentState.ALIGNING) {
                currentState = AlignmentState.WAITING_TO_NAVIGATE;
                instructionTextView.setText(R.string.hold_steady);
                navigationHandler.postDelayed(navigationRunnable, 2000);
            }
        } else {
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_default));
            if (currentState == AlignmentState.WAITING_TO_NAVIGATE) {
                currentState = AlignmentState.ALIGNING;
                instructionTextView.setText(R.string.align_with_target);
                navigationHandler.removeCallbacks(navigationRunnable);
            }
        }
    }

    private void updateTimeline() {
        if (timelineView != null && timelineLocations != null) {
            timelineView.updatePath(timelineLocations, currentLegIndex);
        }
    }

    private void updateCurrentText(float currentDegree) {
        int degreeValue = Math.round(currentDegree);
        String direction = getDirectionFromDegree(degreeValue);
        currentTextView.setText(getString(R.string.current_label, String.format(Locale.getDefault(), "%d°%s", degreeValue, direction)));
    }

    private void updateTargetText(float targetDegree) {
        int degreeValue = Math.round(targetDegree);
        String direction = getDirectionFromDegree(degreeValue);
        targetTextView.setText(getString(R.string.target_label, String.format(Locale.getDefault(), "%d°%s", degreeValue, direction)));
    }

    private String getDirectionFromDegree(int degree) {
        int normalizedDegree = (degree + 360) % 360;
        if (normalizedDegree >= 338 || normalizedDegree < 23) return "N";
        if (normalizedDegree < 68) return "NE";
        if (normalizedDegree < 113) return "E";
        if (normalizedDegree < 158) return "SE";
        if (normalizedDegree < 203) return "S";
        if (normalizedDegree < 248) return "SW";
        if (normalizedDegree < 293) return "W";
        return "NW";
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* Not used */ }
}