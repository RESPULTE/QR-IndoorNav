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
import android.util.Pair;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.qr_indoornav.model.Edge;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.Location;
import com.example.qr_indoornav.model.MapData;
import com.example.qr_indoornav.model.Node;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public class CompassActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "CompassActivity";

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
    private Graph graph;
    private ArrayList<String> pathNodeIds; // The complete path, e.g., ["Room101", "J1", "J2", "Room205"]
    private List<Location> timelineLocations; // Location objects for the UI timeline
    private int currentLegIndex = 0; // Index of the STARTING node for the current leg

    // --- CURRENT LEG DATA ---
    private float targetDegree;
    private int distanceForLegMeters;
    private int stepsTakenInLeg = 0;

    private final Handler navigationHandler = new Handler();
    private Runnable navigationRunnable;

    // Helper class to hold data for a single navigation leg
    private static class LegInfo {
        final float direction;
        final int distance;
        LegInfo(float dir, int dist) { this.direction = dir; this.distance = dist; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compass);

        // Load map data and the complete path from the intent
        graph = MapData.getGraph();
        pathNodeIds = getIntent().getStringArrayListExtra("PATH_NODE_IDS");

        // Validate the received path
        if (pathNodeIds == null || pathNodeIds.size() < 2) {
            Toast.makeText(this, "Invalid navigation path received.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initializeUI();
        setupSensors();
        setupTimeline();
        setupNavigationRunnable();

        // Load data for the first leg of the journey
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

    private void setupSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        stepDetector = new StepDetector(null);
    }

    /**
     * Creates the list of Location objects for the UI timeline from the path IDs.
     */
    private void setupTimeline() {
        this.timelineLocations = pathNodeIds.stream()
                .map(MapData::getLocationById)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Loads the direction and distance for the current leg of the path by interpreting
     * the pathNodeIds list and querying the graph model.
     */
    private void loadCurrentLegData() {
        // Check if the journey is complete
        if (currentLegIndex >= pathNodeIds.size() - 1) {
            currentState = AlignmentState.FINISHED;
            instructionTextView.setText(R.string.destination_reached);
            targetTextView.setText(R.string.target_complete);
            arrowImageView.setRotation(0);
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_target_reached));
            sensorManager.unregisterListener(this);
            updateTimeline();
            return;
        }

        String fromId = pathNodeIds.get(currentLegIndex);
        String toId = pathNodeIds.get(currentLegIndex + 1);

        LegInfo leg = getNavigationLegInfo(fromId, toId);

        if (leg == null) {
            Toast.makeText(this, "Error: Could not calculate route for " + fromId + " -> " + toId, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        this.targetDegree = leg.direction;
        this.distanceForLegMeters = leg.distance;

        // Update UI for the current leg
        updateTargetText();
        updateTimeline();
        currentState = AlignmentState.ALIGNING;
        instructionTextView.setText(R.string.align_for_checkpoint);
    }

    /**
     * Derives the direction and distance for a single leg of the journey.
     * This handles all combinations: J->J, R->J, J->R, and R->R.
     */
    private LegInfo getNavigationLegInfo(String fromId, String toId) {
        boolean isFromRoom = graph.getNode(fromId) == null;
        boolean isToRoom = graph.getNode(toId) == null;

        // Case 1: Junction -> Junction
        if (!isFromRoom && !isToRoom) {
            Edge edge = graph.getNode(fromId).getEdgeTo(toId);
            if (edge != null) {
                return new LegInfo(edge.directionDegrees, edge.distanceMeters);
            }
        }

        // Case 2: Room -> Junction or Junction -> Room
        if (isFromRoom != isToRoom) {
            String roomId = isFromRoom ? fromId : toId;
            String junctionId = isFromRoom ? toId : fromId;
            Pair<String, String> endpoints = findEndpointJunctionsForRoom(roomId);
            if (endpoints != null) {
                Edge edge;
                // Determine the direction of travel along the edge
                if (endpoints.first.equals(junctionId)) { // Traveling towards the first endpoint
                    edge = graph.getNode(endpoints.second).getEdgeTo(endpoints.first);
                } else if (endpoints.second.equals(junctionId)) { // Traveling towards the second endpoint
                    edge = graph.getNode(endpoints.first).getEdgeTo(endpoints.second);
                } else { return null; /* Path is invalid */ }

                if (edge != null) {
                    int dist = calculatePartialDistance(fromId, toId);
                    return new LegInfo(edge.directionDegrees, dist);
                }
            }
        }

        // Case 3: Room -> Room (on the same edge)
        if (isFromRoom && isToRoom) {
            Pair<String, String> endpoints = findEndpointJunctionsForRoom(fromId);
            if (endpoints != null) {
                Edge forwardEdge = graph.getNode(endpoints.first).getEdgeTo(endpoints.second);
                if (forwardEdge != null && forwardEdge.roomIds.contains(toId)) {
                    int dist = calculatePartialDistance(fromId, toId);
                    float direction;
                    // Determine direction based on room order on the edge
                    if (forwardEdge.roomIds.indexOf(fromId) < forwardEdge.roomIds.indexOf(toId)) {
                        direction = forwardEdge.directionDegrees;
                    } else {
                        direction = (forwardEdge.directionDegrees + 180) % 360;
                    }
                    return new LegInfo(direction, dist);
                }
            }
        }
        return null; // Should not be reached with a valid path
    }

    /**
     * Helper to find the two junction endpoints for an edge that contains a given room.
     */
    private Pair<String, String> findEndpointJunctionsForRoom(String roomId) {
        for (Node node : graph.getAllNodes()) {
            for (Edge edge : node.getEdges().values()) {
                if (edge.roomIds.contains(roomId)) {
                    return new Pair<>(node.id, edge.toNodeId);
                }
            }
        }
        return null;
    }

    /**
     * Helper to calculate the walking distance between any two locations (rooms or junctions) on the same edge.
     */
    private int calculatePartialDistance(String locA, String locB) {
        boolean isARoom = graph.getNode(locA) == null;
        boolean isBRoom = graph.getNode(locB) == null;
        String aRoomId = isARoom ? locA : (isBRoom ? locB : null);
        if (aRoomId == null) return 0; // Should not happen

        Pair<String, String> endpoints = findEndpointJunctionsForRoom(aRoomId);
        if (endpoints == null) return 0;

        Edge edge = graph.getNode(endpoints.first).getEdgeTo(endpoints.second);
        if (edge == null) return 0;

        int totalRooms = edge.roomIds.size();
        float ratioA = isARoom ? (float)(edge.roomIds.indexOf(locA) + 1) / (totalRooms + 1)
                : (locA.equals(endpoints.first) ? 0.0f : 1.0f);

        float ratioB = isBRoom ? (float)(edge.roomIds.indexOf(locB) + 1) / (totalRooms + 1)
                : (locB.equals(endpoints.first) ? 0.0f : 1.0f);

        return (int) (Math.abs(ratioA - ratioB) * edge.distanceMeters);
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
     * Verifies the scanned QR code against the expected next stop in the path.
     */
    private void verifyScanAndUpdateJourney(String decodedJson) {
        QRParser.ScannedQRData scannedData = QRParser.parse(decodedJson);
        if (scannedData.type == QRParser.ScannedQRData.QRType.INVALID) {
            showErrorDialog("valid QR code", "Invalid Data");
            return;
        }

        String expectedNextNodeId = pathNodeIds.get(currentLegIndex + 1);
        String finalDestinationId = pathNodeIds.get(pathNodeIds.size() - 1);

        if (scannedData.id.equals(expectedNextNodeId)) {
            // SUCCESS: Scanned QR matches the expected stop.
            Toast.makeText(this, "Correct Location: " + scannedData.id, Toast.LENGTH_SHORT).show();
            currentLegIndex++; // Advance to the next leg

            if (scannedData.id.equals(finalDestinationId)) {
                showSuccessDialog("You have arrived at your final destination: " + finalDestinationId + "!", true);
            } else {
                int remainingStops = (pathNodeIds.size() - 1) - currentLegIndex;
                String message = "You have arrived at " + expectedNextNodeId + ".\n" +
                        remainingStops + " more stop(s) to go.";
                showSuccessDialog(message, false);
            }
        } else {
            // FAILURE: Scanned QR does not match.
            showErrorDialog(expectedNextNodeId, scannedData.id);
        }
    }

    // --- Other methods (dialogs, sensor handling, UI updates) are largely unchanged ---
    // Note: Minor changes made to use pathNodeIds where appropriate.

    private void launchQrScanner() {
        Intent scannerIntent = new Intent(this, QRScannerActivity.class);
        String expectedNextNodeId = pathNodeIds.get(currentLegIndex + 1);
        int remainingLegs = (pathNodeIds.size() - 2) - currentLegIndex;
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
                        // The journey is complete, load the final "arrived" state
                        loadCurrentLegData();
                    } else {
                        stepsTakenInLeg = 0; // Reset step count for the new leg
                        loadCurrentLegData(); // Load the next leg's data
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

    private void setupNavigationRunnable() {
        navigationRunnable = () -> {
            if (currentState == AlignmentState.WAITING_TO_NAVIGATE) {
                Intent intent = new Intent(CompassActivity.this, ProgressActivity.class);
                intent.putExtra(ProgressActivity.EXTRA_TARGET_DEGREE, targetDegree);
                intent.putExtra(ProgressActivity.EXTRA_DISTANCE_METERS, distanceForLegMeters);
                intent.putExtra(ProgressActivity.EXTRA_INITIAL_STEPS, stepsTakenInLeg);
                startActivityForResult(intent, PROGRESS_REQUEST_CODE);
                currentState = AlignmentState.ALIGNING;
            }
        };
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
        if (accelerometerReading[0] == 0 && magnetometerReading[0] == 0) return;
        float[] orientationAngles = new float[3];
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

    private void updateTargetText() {
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