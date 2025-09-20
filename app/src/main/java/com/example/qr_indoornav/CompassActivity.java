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
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.qr_indoornav.model.Edge;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.Location;
import com.example.qr_indoornav.model.MapData;
import com.example.qr_indoornav.QRParser;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class CompassActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "CompassActivity";

    // --- Request codes for starting activities for a result ---
    private static final int PROGRESS_REQUEST_CODE = 1001;
    private static final int QR_SCANNER_REQUEST_CODE = 1002;

    // --- UI Components ---
    private ImageView arrowImageView;
    private TextView targetTextView, currentTextView, instructionTextView;
    private MaterialCardView compassBackgroundCard;
    private TimelineView timelineView;

    // --- Sensor variables ---
    private SensorManager sensorManager;
    private final float[] accelerometerReading = new float[3];
    private final float[] magnetometerReading = new float[3];
    private final float[] rotationMatrix = new float[9];
    private StepDetector stepDetector; // Handles alignment checks and step counting logic

    // --- State Machine for Alignment ---
    private enum AlignmentState { ALIGNING, WAITING_TO_NAVIGATE, FINISHED }
    private AlignmentState currentState = AlignmentState.ALIGNING;

    // --- DYNAMIC NAVIGATION STATE ---
    private Graph graph;
    private ArrayList<String> pathNodeIds; // List of junction IDs to traverse
    private List<Location> fullPathLocations; // Full list of Location objects for the TimelineView
    private int currentLegIndex = 0; // Index of the STARTING node for the current leg in pathNodeIds

    // --- Variables for the CURRENT leg of the journey ---
    private float targetDegree; // Compass direction for the current leg
    private int distanceForLegMeters; // Distance for the current leg
    private int stepsTakenInLeg = 0; // Steps accumulated for the current leg (persists between ProgressActivity launches)

    private String finalDestinationId; // The absolute final destination (room ID or junction ID)

    private final Handler navigationHandler = new Handler();
    private Runnable navigationRunnable;

    private Edge lastEdge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compass);

        // Load map data and path information passed from NavigationActivity
        graph = MapData.getGraph();
        pathNodeIds = getIntent().getStringArrayListExtra("PATH_NODE_IDS");
        finalDestinationId = getIntent().getStringExtra("FINAL_DESTINATION_ID"); // Store the true final destination

        initializeUI();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        // Initialize the StepDetector. The listener is null because this activity only uses it for alignment checks, not step counting.
        stepDetector = new StepDetector(null);
        setupNavigationRunnable();

        // Basic validation for the received path
        if (pathNodeIds == null || pathNodeIds.size() < 2 || finalDestinationId == null) {
            Toast.makeText(this, "Invalid navigation path received.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Build the full visual timeline based on the entire planned path (junctions + final room)
        buildFullPathLocations(finalDestinationId);

        // Load data for the first leg of the journey immediately
        loadCurrentLegData();
    }

    private void initializeUI() {
        arrowImageView = findViewById(R.id.arrowImageView);
        targetTextView = findViewById(R.id.targetTextView);
        currentTextView = findViewById(R.id.currentTextView);
        instructionTextView = findViewById(R.id.instructionTextView);
        compassBackgroundCard = findViewById(R.id.compassBackgroundCard);
        timelineView = findViewById(R.id.timelineView);
        SwitchMaterial audioSwitch = findViewById(R.id.audioSwitch);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);

        toolbar.setNavigationOnClickListener(v -> finish());
        audioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String state = isChecked ? "enabled" : "disabled";
            Toast.makeText(this, "Audio assistance " + state, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Constructs the master list of all stops (junctions and final room) for the timeline display.
     */
    private void buildFullPathLocations(String finalDestinationId) {
        // Convert the list of junction IDs into a list of Location objects for the timeline
        fullPathLocations = pathNodeIds.stream()
                .map(MapData::getLocationById)
                .collect(Collectors.toList());

        Location finalDestLocation = MapData.getLocationById(finalDestinationId);
        fullPathLocations.add(finalDestLocation);

        lastEdge = graph.getNode(pathNodeIds.get(pathNodeIds.size()-2)).edges.get(pathNodeIds.get(pathNodeIds.size()-1));

        // removing the node: this is shit programing lol
        fullPathLocations.remove(fullPathLocations.size() - 2);

    }

    /**
     * Loads the direction and distance for the current segment of the path and updates the UI.
     * This is called on initial launch, after a successful QR scan, and when re-aligning.
     */
    private void loadCurrentLegData() {
        // Check if the entire journey is complete (currentLegIndex has advanced past the last junction)
        if (currentLegIndex >= fullPathLocations.size() - 1) {
            currentState = AlignmentState.FINISHED;
            instructionTextView.setText("You have arrived at your destination!");
            targetTextView.setText("Target: Complete");
            arrowImageView.setRotation(0); // Point arrow straight up for "finished" state
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_target_reached));
            sensorManager.unregisterListener(this); // Stop all sensor listening
            updateTimeline(); // Final update to show the user at the very end of the timeline
            return;
        }

        String fromNodeId = fullPathLocations.get(currentLegIndex).id;
        String toNodeId = fullPathLocations.get(currentLegIndex + 1).id;
        Edge currentEdge = graph.getNode(fromNodeId).edges.get(toNodeId);

        // Use the 'lastEdge' as a fallback if the edge isn't found (for the final leg)
        Edge edgeToUse = (currentEdge != null) ? currentEdge : lastEdge;

        // Set the dynamic targets for the current leg
        targetDegree = edgeToUse.directionDegrees;
        distanceForLegMeters = edgeToUse.distanceMeters;
        // NOTE: stepsTakenInLeg is INTENTIONALLY NOT RESET HERE.
        // It's reset only when a new leg officially starts (see showSuccessDialog).
        // This preserves progress if the user has to re-align mid-leg.

        // Update UI elements for the current leg
        updateTargetText();
        updateTimeline();
        currentState = AlignmentState.ALIGNING;
        instructionTextView.setText("Align for next checkpoint");
    }

    /**
     * Sets up the runnable that will launch ProgressActivity after 2 seconds of sustained alignment.
     */
    private void setupNavigationRunnable() {
        navigationRunnable = () -> {
            if (currentState == AlignmentState.WAITING_TO_NAVIGATE) {
                Intent intent = new Intent(CompassActivity.this, ProgressActivity.class);
                intent.putExtra(ProgressActivity.EXTRA_TARGET_DEGREE, targetDegree);
                intent.putExtra(ProgressActivity.EXTRA_DISTANCE_METERS, distanceForLegMeters);
                intent.putExtra(ProgressActivity.EXTRA_INITIAL_STEPS, stepsTakenInLeg); // Pass accumulated steps for this leg
                startActivityForResult(intent, PROGRESS_REQUEST_CODE);
                // After launching ProgressActivity, we reset to aligning state, anticipating return
                currentState = AlignmentState.ALIGNING;
            }
        };
    }

    /**
     * Handles results returned from other activities (ProgressActivity or QRScannerActivity).
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PROGRESS_REQUEST_CODE) {
            // This block is executed when returning from ProgressActivity (walking phase)
            if (data != null) {
                // IMPORTANT: Always update stepsTakenInLeg, regardless of OK or CANCELED result.
                // This preserves progress if the user deviated or backed out.
                stepsTakenInLeg = data.getIntExtra("EXTRA_STEPS_TAKEN", stepsTakenInLeg);
            }

            if (resultCode == RESULT_OK) {
                // User successfully completed the walking part of the leg.
                // Now, they need to scan the QR code to confirm arrival.
                launchQrScanner();
            } else { // resultCode == RESULT_CANCELED (user deviated or pressed back)
                // User deviated or cancelled. Prompt to re-align and continue from saved progress.
                Toast.makeText(this, "Navigation paused. Re-align to resume walking.", Toast.LENGTH_SHORT).show();
                // The `stepsTakenInLeg` is already updated from the result, so `loadCurrentLegData`
                // will set up the compass for the same leg with the preserved progress.
                loadCurrentLegData(); // Re-load UI for current leg to show updated steps (if any)
            }
        } else if (requestCode == QR_SCANNER_REQUEST_CODE) {
            // This block is executed when returning from QRScannerActivity
            if (resultCode == RESULT_OK && data != null) {
                String decodedJson = data.getStringExtra("DECODED_TEXT");
                // Process and verify the scanned QR code
                verifyScanAndUpdateJourney(decodedJson);
            } else { // resultCode == RESULT_CANCELED or no data (user cancelled QR scan)
                Toast.makeText(this, "QR scan cancelled. Please scan again to continue.", Toast.LENGTH_SHORT).show();
                // User remains at the current leg. No change to currentLegIndex.
            }
        }
    }

    /**
     * Launches the QR scanner activity.
     */
    private void launchQrScanner() {
        Intent scannerIntent = new Intent(this, QRScannerActivity.class);

        // Determine the ID of the very next expected stop.
        // This logic is already present in verifyScanAndUpdateJourney, so we can reuse/adapt it.
        String expectedNextNodeId;
        boolean isFinalLeg = (currentLegIndex + 1) >= (fullPathLocations.size() - 1);

        if (isFinalLeg) {
            expectedNextNodeId = finalDestinationId;
        } else {
            expectedNextNodeId = fullPathLocations.get(currentLegIndex + 1).id;
        }

        scannerIntent.putExtra("EXPECTED_NODE_ID", expectedNextNodeId); // <--- ADD THIS LINE
        scannerIntent.putExtra("REMAINING_LEGS", (fullPathLocations.size() - 1) - currentLegIndex - (isFinalLeg ? 1 : 0)); // <--- ADD THIS LINE for dialog message

        startActivityForResult(scannerIntent, QR_SCANNER_REQUEST_CODE);
    }

    /**
     * Verifies the decoded QR text against the expected node ID and manages journey progression.
     */
    private void verifyScanAndUpdateJourney(String decodedJson) {
        QRParser.ScannedQRData scannedData = QRParser.parse(decodedJson);
        String expectedNextNodeId;

        // Determine the ID of the very next expected stop.
        // If this is the last leg of the journey, the expected stop is the final destination (which could be a room).
        boolean isFinalLeg = (currentLegIndex + 1) >= (fullPathLocations.size() - 1); // Check if the *next* node in pathNodeIds is the last

        if (isFinalLeg) {
            expectedNextNodeId = finalDestinationId;
        } else {
            // Otherwise, the expected stop is the next junction in the pathNodeIds list.
            expectedNextNodeId = fullPathLocations.get(currentLegIndex + 1).id;
        }


        if (scannedData.type != QRParser.ScannedQRData.QRType.INVALID && scannedData.id.equals(expectedNextNodeId)) {
            // --- SUCCESS: Scanned QR matches the expected next stop ---
            Toast.makeText(this, "Correct Location: " + scannedData.id, Toast.LENGTH_SHORT).show();

            // Check if this was the very final stop
            if (scannedData.id.equals(finalDestinationId)) {
                showSuccessDialog("You have arrived at your final destination: " + finalDestinationId + "!", true);
            } else {
                // User has confirmed arrival at an intermediate junction. Advance the leg.
                currentLegIndex++; // Advance to the next leg
                int remainingJunctions = (fullPathLocations.size() - 1) - currentLegIndex; // Count remaining junctions
                String message = "You have arrived at " + expectedNextNodeId + ".\n" +
                        remainingJunctions + " more junction(s) until your destination area.";
                showSuccessDialog(message, false);
            }
        } else {
            // --- FAILURE: Scanned QR does NOT match expected ID or is invalid ---
            showErrorDialog(expectedNextNodeId, scannedData.id);
        }
    }

    /**
     * Shows a success dialog to the user and manages progression.
     * @param message The message to display in the dialog.
     * @param isFinished True if the entire journey is complete, false otherwise.
     */
    private void showSuccessDialog(String message, boolean isFinished) {
        new AlertDialog.Builder(this)
                .setTitle("Success!")
                .setMessage(message)
                .setCancelable(false) // User must interact with the dialog
                .setPositiveButton("Proceed", (dialog, which) -> {
                    if (isFinished) {
                        finish(); // Finish CompassActivity, go back to MainActivity
                    } else {
                        // Reset step count for the new leg before loading its data.
                        stepsTakenInLeg = 0;
                        loadCurrentLegData(); // Load the next leg's data and update UI
                    }
                })
                .show();
    }

    /**
     * Shows an error dialog if the scanned QR code is incorrect.
     * Provides options to rescan or cancel (go back to alignment).
     * @param expectedId The ID that was expected.
     * @param scannedId The ID that was actually scanned.
     */
    private void showErrorDialog(String expectedId, String scannedId) {
        new AlertDialog.Builder(this)
                .setTitle("QR Code Mismatch")
                .setMessage("Expected: " + expectedId + "\nScanned: " + scannedId + "\n\nPlease find the correct QR code and try again.")
                .setCancelable(false)
                .setPositiveButton("Rescan", (dialog, which) -> launchQrScanner()) // Option to immediately rescan
                .setNegativeButton("Cancel", (dialog, which) -> {
                    dialog.dismiss(); // Dismiss dialog
                    // User remains at the current leg, returns to alignment phase.
                    // onResume will set state to ALIGNING.
                })
                .show();
    }

    /**
     * Registers sensor listeners when the activity resumes.
     * This is called on initial launch and when returning from other activities.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (currentState != AlignmentState.FINISHED) {
            // Re-register sensor listeners
            Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            }
            Sensor magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (magneticField != null) {
                sensorManager.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_UI);
            }
            // Reset state to aligning, as user needs to re-align after returning to this screen.
            currentState = AlignmentState.ALIGNING;
            instructionTextView.setText("Align for next checkpoint");
        }
    }

    /**
     * Unregisters sensor listeners when the activity pauses to save battery.
     */
    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        navigationHandler.removeCallbacks(navigationRunnable); // Stop any pending alignment timers
    }

    /**
     * Processes raw sensor data to update device orientation.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (currentState == AlignmentState.FINISHED) return; // No need to process if journey is over

        // Ensure we have valid data from both sensors before attempting to calculate orientation
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.length);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.length);
        }
        updateOrientationAngles();
    }

    /**
     * Calculates the device's current orientation and updates the compass arrow.
     */
    public void updateOrientationAngles() {
        // Ensure we have recent data from both sensors before attempting to calculate orientation
        // (A simple check, more robust would be to check if data is fresh)
        if (accelerometerReading[0] == 0 && magnetometerReading[0] == 0) return;

        float[] orientationAngles = new float[3];
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        float azimuthInRadians = orientationAngles[0];
        float currentDegree = (float) Math.toDegrees(azimuthInRadians);
        if (currentDegree < 0) currentDegree += 360; // Normalize to 0-360

        float bearingToTarget = (targetDegree - currentDegree + 360) % 360;
        arrowImageView.setRotation(bearingToTarget); // Rotate the arrow visually
        updateCurrentText(currentDegree);
        checkAlignment(currentDegree); // Check if the user is facing the correct direction
    }

    /**
     * Checks if the user is currently aligned with the target direction by delegating to the StepDetector.
     */
    private void checkAlignment(float currentDegree) {
        // Delegate the alignment check to the StepDetector class
        boolean isOnTarget = stepDetector.isAlignedWithTarget(currentDegree, targetDegree);

        if (isOnTarget) {
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_target_reached));
            if (currentState == AlignmentState.ALIGNING) {
                // User just aligned, start the 2-second timer to launch ProgressActivity
                currentState = AlignmentState.WAITING_TO_NAVIGATE;
                instructionTextView.setText("Hold steady...");
                navigationHandler.postDelayed(navigationRunnable, 2000);
            }
        } else {
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_default));
            if (currentState == AlignmentState.WAITING_TO_NAVIGATE) {
                // User moved off target during the waiting period, reset alignment state.
                currentState = AlignmentState.ALIGNING;
                instructionTextView.setText("Align with the target direction");
                navigationHandler.removeCallbacks(navigationRunnable); // Cancel the pending launch
            }
        }
    }

    /**
     * Updates the TimelineView to reflect the current position in the journey.
     */
    private void updateTimeline() {
        if (timelineView != null && fullPathLocations != null) {
            timelineView.updatePath(fullPathLocations, currentLegIndex);
        }
    }

    /**
     * Updates the TextView showing the user's current compass heading.
     */
    private void updateCurrentText(float currentDegree) {
        int degreeValue = Math.round(currentDegree);
        String direction = getDirectionFromDegree(degreeValue);
        String currentText = getString(R.string.current_label, String.format(Locale.getDefault(), "%d°%s", degreeValue, direction));
        currentTextView.setText(currentText);
    }

    /**
     * Updates the TextView showing the target compass heading for the current leg.
     */
    private void updateTargetText() {
        int degreeValue = Math.round(targetDegree);
        String direction = getDirectionFromDegree(degreeValue);
        String targetText = getString(R.string.target_label, String.format(Locale.getDefault(), "%d°%s", degreeValue, direction));
        targetTextView.setText(targetText);
    }

    /**
     * Converts a compass degree value to a cardinal/intercardinal direction string (e.g., "N", "SE").
     */
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

    /**
     * Called when the accuracy of a sensor changes. Not used in this implementation.
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* Not used */ }
}