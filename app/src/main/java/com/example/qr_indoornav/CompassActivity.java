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
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.qr_indoornav.model.Location;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

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

    // --- NAVIGATION STATE ---
    private List<PathFinder.PathLeg> pathLegs;
    private List<Location> timelineLocations;
    private int currentLegIndex = 0;
    private int stepsTakenInLeg = 0;
    private final Handler navigationHandler = new Handler(Looper.getMainLooper());
    private Runnable navigationRunnable;

    // --- TTS Guidance State ---
    private long lastTtsGuidanceTime = 0;
    private static final long TTS_GUIDANCE_INTERVAL = 1700;
    private boolean hasAnnouncedAlignment = false;
    private boolean justReturnedFromDeviation = false;

    // --- Haptic and Audio Feedback ---
    private Vibrator vibrator;
    private SoundPool soundPool;
    private int dingSoundId;
    private enum VibrationLevel { NONE, LOW, MEDIUM, HIGH }
    private VibrationLevel currentVibrationLevel = VibrationLevel.NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compass);

        pathLegs = (ArrayList<PathFinder.PathLeg>) getIntent().getSerializableExtra(NavigationActivity.EXTRA_PATH_LEGS);

        if (pathLegs == null || pathLegs.isEmpty()) {
            Toast.makeText(this, "Invalid navigation path received.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initializeUI();
        setupSensors();
        setupFeedbackServices(); // New: Initialize vibrator and sound pool
        setupTimeline();
        setupNavigationRunnable();
        loadCurrentLegData();
    }

    private void setupFeedbackServices() {
        // Setup Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager.getDefaultVibrator();
        } else {
            // Deprecated in API 26
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        // Setup SoundPool for success ding
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(audioAttributes)
                .build();

        // IMPORTANT: You must have a sound file named 'ding.mp3' (or similar) in your res/raw folder.
        // If you don't have a 'raw' folder, create one under 'res'.
        dingSoundId = soundPool.load(this, R.raw.ding, 1);
    }

    private void loadCurrentLegData() {
        if (currentLegIndex >= pathLegs.size()) {
            currentState = AlignmentState.FINISHED;
            return;
        }

        PathFinder.PathLeg currentLeg = pathLegs.get(currentLegIndex);
        updateTargetText(currentLeg.direction);
        updateTimeline();
        currentState = AlignmentState.ALIGNING;
        instructionTextView.setText(R.string.align_for_checkpoint);
        hasAnnouncedAlignment = false;
        lastTtsGuidanceTime = System.currentTimeMillis();
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
            } else { // This means RESULT_CANCELED (deviation or back button)
                Toast.makeText(this, "Navigation paused. Re-align to resume.", Toast.LENGTH_SHORT).show();
                justReturnedFromDeviation = true;
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
        provideTtsGuidance(currentDegree, targetDegree);
        // --- NEW: Add haptic feedback based on proximity to target ---
        provideHapticFeedback(currentDegree, targetDegree);

        boolean isOnTarget = stepDetector.isAlignedWithTarget(currentDegree, targetDegree);
        if (isOnTarget) {
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_target_reached));
            if (currentState == AlignmentState.ALIGNING) {
                // --- NEW: Play success ding sound on alignment ---
                if (soundPool != null) {
                    soundPool.play(dingSoundId, 1, 1, 0, 0, 1);
                }
                currentState = AlignmentState.WAITING_TO_NAVIGATE;
                instructionTextView.setText(R.string.hold_steady);
                navigationHandler.postDelayed(navigationRunnable, 2000);
            }
        } else {
            compassBackgroundCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.compass_bg_default));
            if (currentState == AlignmentState.WAITING_TO_NAVIGATE) {
                TtsManager.speak(getTurnDirectionInstruction(currentDegree, targetDegree));
                currentState = AlignmentState.ALIGNING;
                instructionTextView.setText(R.string.align_with_target);
                navigationHandler.removeCallbacks(navigationRunnable);
            }
        }
    }

    private void provideHapticFeedback(float currentDegree, float targetDegree) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        // Calculate shortest angle difference (0 to 180)
        float diff = Math.abs(currentDegree - targetDegree);
        if (diff > 180) {
            diff = 360 - diff;
        }

        VibrationLevel newLevel;
        // Using the alignment threshold from the step detector (assumed to be ~15 degrees)
        if (diff <= 20) { // Aligned
            newLevel = VibrationLevel.NONE;
        } else if (diff <= 60) { // Close
            newLevel = VibrationLevel.HIGH;
        } else if (diff <= 90) { // Medium
            newLevel = VibrationLevel.MEDIUM;
        } else { // Far
            newLevel = VibrationLevel.LOW;
        }

        // Only update the vibration pattern if the level changes, to prevent stuttering
        if (newLevel == currentVibrationLevel) {
            return;
        }

        currentVibrationLevel = newLevel;
        vibrator.cancel(); // Always cancel the old pattern before starting a new one

        if (newLevel == VibrationLevel.NONE) {
            return; // We're aligned, vibration is cancelled, job done.
        }

        // Patterns are defined as: [delay, vibrate, pause, vibrate, pause, ...]
        long[] pattern;
        switch (newLevel) {
            case HIGH: // Fastest pulses
                pattern = new long[]{0, 100, 150}; // Vibrate 100ms, pause 150ms
                break;
            case MEDIUM:
                pattern = new long[]{0, 100, 400}; // Vibrate 100ms, pause 400ms
                break;
            case LOW:
            default: // Slowest pulses
                pattern = new long[]{0, 100, 800}; // Vibrate 100ms, pause 800ms
                break;
        }

        // Repeat the pattern indefinitely
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            // Deprecated in API 26
            vibrator.vibrate(pattern, 0);
        }
    }


    private void provideTtsGuidance(float currentDegree, float targetDegree) {
        if (justReturnedFromDeviation) {
            TtsManager.speak("Off-course. " + getTurnDirectionInstruction(currentDegree, targetDegree));
            justReturnedFromDeviation = false;
            lastTtsGuidanceTime = System.currentTimeMillis();
            hasAnnouncedAlignment = false;
            return;
        }

        boolean isCurrentlyAligned = stepDetector.isAlignedWithTarget(currentDegree, targetDegree);
        if (isCurrentlyAligned) {
            if (!hasAnnouncedAlignment) {
                TtsManager.speak("Aligned. Hold steady.");
                hasAnnouncedAlignment = true;
            }
            return;
        }

        if (hasAnnouncedAlignment) {
            TtsManager.speak("Re-aligning.");
            hasAnnouncedAlignment = false;
            lastTtsGuidanceTime = System.currentTimeMillis();
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTtsGuidanceTime > TTS_GUIDANCE_INTERVAL) {
            TtsManager.speak(getPeriodicGuidanceInstruction(currentDegree, targetDegree));
            lastTtsGuidanceTime = currentTime;
        }
    }

    private String getTurnDirectionInstruction(float current, float target) {
        float diff = target - current;
        if (diff > 180) diff -= 360;
        else if (diff < -180) diff += 360;

        String direction = (diff > 0) ? "right" : "left";
        return "Turn " + direction + ".";
    }

    private String getPeriodicGuidanceInstruction(float current, float target) {
        float diff = target - current;
        if (diff > 180) diff -= 360;
        else if (diff < -180) diff += 360;

        String direction = (diff > 0) ? "right" : "left";
        float absDiff = Math.abs(diff);

        if (absDiff <= 25) return "Turn a little to the " + direction + ".";
        if (absDiff <= 70) return "Turn slightly to the " + direction + ".";
        return "Turn " + direction + ".";
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
        // --- NEW: Stop vibration when the activity is paused ---
        if (vibrator != null) {
            vibrator.cancel();
            currentVibrationLevel = VibrationLevel.NONE;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // --- NEW: Release SoundPool resources ---
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
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

    private void verifyScanAndUpdateJourney(String decodedJson) {
        QRParser.ScannedQRData scannedData = QRParser.parse(decodedJson);
        if (scannedData.type == QRParser.ScannedQRData.QRType.INVALID) {
            showErrorDialog("valid QR code", "Invalid Data");
            return;
        }

        String expectedNextNodeId = pathLegs.get(currentLegIndex).toId;
        String finalDestinationId = pathLegs.get(pathLegs.size() - 1).toId;

        if (scannedData.id.equals(expectedNextNodeId)) {
            Toast.makeText(this, "Correct Location: " + scannedData.id, Toast.LENGTH_SHORT).show();
            currentLegIndex++;

            if (scannedData.id.equals(finalDestinationId)) {
                Intent intent = new Intent(this, SuccessActivity.class);
                startActivity(intent);
                finish();
                return;
            } else {
                int remainingStops = pathLegs.size() - currentLegIndex;
                String message = "Checkpoint reached. You have " + remainingStops + " more stops to go.";
                TtsManager.speak(message);
                showSuccessDialog("You have arrived at " + expectedNextNodeId + ".\n" + remainingStops + " more stop(s) to go.");
            }
        } else {
            TtsManager.speak("Wrong QR code scanned. Please find the correct one.");
            showErrorDialog(expectedNextNodeId, scannedData.id);
        }
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
    }

    private void setupSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        stepDetector = new StepDetector(null);
    }

    private void setupTimeline() {
        ArrayList<String> pathIds = new ArrayList<>();
        pathIds.add(pathLegs.get(0).fromId);
        for (PathFinder.PathLeg leg : pathLegs) {
            pathIds.add(leg.toId);
        }
        this.timelineLocations = pathIds.stream()
                .map(MapData::getLocationById)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
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

    private void launchQrScanner() {
        Intent scannerIntent = new Intent(this, QRScannerActivity.class);
        String expectedNextNodeId = pathLegs.get(currentLegIndex).toId;
        int remainingLegs = pathLegs.size() - 1 - currentLegIndex;
        scannerIntent.putExtra("EXPECTED_NODE_ID", expectedNextNodeId);
        scannerIntent.putExtra("REMAINING_LEGS", remainingLegs);
        startActivityForResult(scannerIntent, QR_SCANNER_REQUEST_CODE);
    }

    private void showSuccessDialog(String message) {
        new AlertDialog.Builder(this)
                .setTitle("Checkpoint Reached!")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Proceed", (dialog, which) -> {
                    stepsTakenInLeg = 0;
                    loadCurrentLegData();
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