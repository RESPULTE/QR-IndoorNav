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
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.qr_indoornav.model.Edge;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.Location;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONException;
import org.json.JSONObject;

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

    // --- State Machine for Alignment ---
    private enum AlignmentState { ALIGNING, WAITING_TO_NAVIGATE, FINISHED }
    private AlignmentState currentState = AlignmentState.ALIGNING;

    // --- DYNAMIC NAVIGATION STATE ---
    private Graph graph;
    private ArrayList<String> pathNodeIds;
    private List<Location> fullPathLocations; // For the TimelineView
    private int currentLegIndex = 0; // Current index in pathNodeIds (start node of current leg)

    // --- Variables for the CURRENT leg of the journey ---
    private float targetDegree;
    private int distanceForLegMeters;
    private int stepsTakenInLeg = 0; // Steps taken for the current leg. Persists between ProgressActivity launches.
    private static final int DEGREE_MARGIN_OF_ERROR = 5;

    private final Handler navigationHandler = new Handler();
    private Runnable navigationRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compass);

        // Load map data and path from the Intent
        graph = MapData.getGraph(this);
        pathNodeIds = getIntent().getStringArrayListExtra("PATH_NODE_IDS");
        String finalDestinationId = getIntent().getStringExtra("FINAL_DESTINATION_ID");

        initializeUI();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        setupNavigationRunnable();

        if (pathNodeIds == null || pathNodeIds.size() < 2 || finalDestinationId == null) {
            Toast.makeText(this, "Invalid path received.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Build the full visual timeline based on the entire planned path
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
        timelineView = findViewById(R.id.timelineView); // Initialize the TimelineView
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
        fullPathLocations = pathNodeIds.stream()
                .map(nodeId -> MapData.getLocationById(this, nodeId))
                .collect(Collectors.toList());

        Location finalDestLocation = MapData.getLocationById(this, finalDestinationId);
        boolean isRoom = finalDestLocation != null && !finalDestLocation.id.equals(finalDestLocation.parentJunctionId);

        if (isRoom) {
            fullPathLocations.add(finalDestLocation);
        }
    }

    /**
     * Loads the direction and distance for the current segment of the path and updates the UI.
     * This is called on initial load and after each successful QR scan.
     */
    private void loadCurrentLegData() {
        // Check if the entire journey is complete
        if (currentLegIndex >= pathNodeIds.size() - 1) {
            currentState = AlignmentState.FINISHED;
            instructionTextView.setText("You have arrived at your destination!");
            targetTextView.setText("Target: Complete");
            arrowImageView.setRotation(0); // Point arrow straight up for "finished"
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_target_reached));
            sensorManager.unregisterListener(this); // Stop all sensor listening
            updateTimeline(); // Final update to show the user at the end
            return;
        }

        String fromNodeId = pathNodeIds.get(currentLegIndex);
        String toNodeId = pathNodeIds.get(currentLegIndex + 1);
        Edge currentEdge = graph.getNode(fromNodeId).edges.get(toNodeId);

        if (currentEdge == null) {
            Toast.makeText(this, "Error: Path data is inconsistent for leg " + fromNodeId + "->" + toNodeId, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Set the dynamic targets for the current leg
        targetDegree = currentEdge.directionDegrees;
        distanceForLegMeters = currentEdge.distanceMeters;
        stepsTakenInLeg = 0; // Reset steps for the NEW leg when it's loaded

        // Update UI and reset alignment state for the new leg
        updateTargetText();
        updateTimeline(); // Update timeline to show the new 'currentLegIndex'
        currentState = AlignmentState.ALIGNING;
        instructionTextView.setText("Align for next checkpoint");
    }

    /**
     * Sets up the runnable that will launch ProgressActivity after 2 seconds of alignment.
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
     * Handles results from other activities (ProgressActivity or QRScannerActivity).
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PROGRESS_REQUEST_CODE) {
            // This block is executed when returning from ProgressActivity (walking phase)
            if (data != null) {
                // IMPORTANT: Always update stepsTakenInLeg, regardless of OK or CANCELED result.
                // This preserves progress if the user deviated or backed out.
                stepsTakenInLeg = data.getIntExtra("EXTRA_STEPS_TAKEN", 0);
            }

            if (resultCode == RESULT_OK) {
                // User successfully completed the walking part of the leg.
                // Now, they need to scan the QR code to confirm arrival.
                Intent scannerIntent = new Intent(this, QRScannerActivity.class);
                startActivityForResult(scannerIntent, QR_SCANNER_REQUEST_CODE);
            } else { // resultCode == RESULT_CANCELED (deviation or back press)
                // User deviated or cancelled. Prompt to re-align and continue.
                Toast.makeText(this, "Navigation paused. Re-align to resume walking.", Toast.LENGTH_SHORT).show();
                // We reload the current leg's data, which uses the saved `stepsTakenInLeg` to resume.
                loadCurrentLegData();
            }
        } else if (requestCode == QR_SCANNER_REQUEST_CODE) {
            // This block is executed when returning from QRScannerActivity
            if (resultCode == RESULT_OK && data != null) {
                String decodedJson = data.getStringExtra("DECODED_TEXT");
                String scannedJunctionId = parseJunctionIdFromQrJson(decodedJson);
                String expectedJunctionId = pathNodeIds.get(currentLegIndex + 1);

                if (scannedJunctionId != null && scannedJunctionId.equals(expectedJunctionId)) {
                    // SUCCESS! The user has successfully confirmed their arrival at the correct junction.
                    Toast.makeText(this, "Correct junction scanned: " + scannedJunctionId, Toast.LENGTH_SHORT).show();
                    currentLegIndex++; // Advance to the next leg
                    loadCurrentLegData(); // Load the new leg's data and reset steps
                } else {
                    // Scanned wrong QR code.
                    Toast.makeText(this, "Wrong QR Code! Expected " + expectedJunctionId + ", but scanned " + scannedJunctionId, Toast.LENGTH_LONG).show();
                    // User stays on the current leg. They need to scan the correct code.
                }
            } else { // resultCode == RESULT_CANCELED or no data
                // User cancelled QR scan or it failed.
                Toast.makeText(this, "QR scan cancelled. Please scan the correct code to continue.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Helper method to parse the junction ID from the QR code's JSON string.
     */
    private String parseJunctionIdFromQrJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) return "Unknown";
        try {
            JSONObject json = new JSONObject(jsonString);
            // Assumes the QR code JSON contains a "paths" array and the first path's "from" node is the current junction ID.
            return json.getJSONArray("paths").getJSONObject(0).getString("path").split("-")[0];
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse QR JSON for junction ID", e);
            return "Invalid QR";
        }
    }

    /**
     * Registers sensor listeners when the activity resumes.
     * This is called on initial launch and when returning from other activities.
     */
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
        // Ensure we have data from both sensors before attempting to calculate orientation
        if (accelerometerReading[0] == 0 && magnetometerReading[0] == 0) return;

        float[] rotationAngles = new float[3]; // Placeholder for orientation output
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
        SensorManager.getOrientation(rotationMatrix, rotationAngles); // orientationAngles is reused, but local var for clarity

        float azimuthInRadians = rotationAngles[0];
        float currentDegree = (float) Math.toDegrees(azimuthInRadians);
        if (currentDegree < 0) currentDegree += 360; // Normalize to 0-360

        float bearingToTarget = (targetDegree - currentDegree + 360) % 360;
        arrowImageView.setRotation(bearingToTarget); // Rotate the arrow visually
        updateCurrentText(currentDegree);
        checkAlignment(currentDegree); // Check if the user is facing the correct direction
    }

    /**
     * Checks if the user is currently aligned with the target direction.
     */
    private void checkAlignment(float currentDegree) {
        float difference = Math.abs(currentDegree - targetDegree);
        if (difference > 180) difference = 360 - difference; // Get shortest angular difference

        boolean isOnTarget = difference <= DEGREE_MARGIN_OF_ERROR;

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
                // User moved off target during the waiting period, reset.
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