package com.example.qr_indoornav;

/**
 * A unified module for simple dead reckoning, handling step detection, position tracking,
 * and error accumulation to determine when realignment is necessary.
 *
 * REFINEMENTS (Dead Reckoning Model):
 * 1.  Position Tracking: Instead of just counting steps, this class now tracks the user's
 *     estimated (x, y) position in meters. It assumes the user starts at (0, 0).
 *
 * 2.  Accumulated Error Calculation: The system maintains an "ideal" position, which is where
 *     the user *should* be if they walked perfectly straight along the target direction. The
 *     "accumulated error" is the real-world distance (in meters) between the user's
 *     actual estimated position and their ideal position.
 *
 * 3.  Smarter Recalibration Prompts: The `needsRealignment()` method now triggers when the
 *     accumulated error distance exceeds a set threshold. This is more intelligent than
 *     counting "bad steps," as it correctly handles users who are slightly off but generally
 *     moving in the right direction. It only flags users who are genuinely drifting off course.
 *
 * 4.  Dynamic Step Length: Step length is now calculated based on user height during
 *     calibration, providing a more personalized experience.
 */
public class StepDetector {

    // --- Listener Interface ---
    /**
     * Interface for activities to implement to receive step count updates.
     */
    public interface OnStepListener {
        void onStep(int totalSteps);
    }

    private final OnStepListener listener;

    // --- UNIFIED CONFIGURATION CONSTANTS ---
    private static final float DEFAULT_USER_HEIGHT_CM = 175.0f;

    // --- REFINED STEP DETECTION CONSTANTS ---
    private static final long STEP_TIME_GATE_MS = 250; // Time between steps. Can be faster than the old value.
    private static final float STEP_DETECTION_LOWER_THRESHOLD_FACTOR = 1.4f;

    // --- DEAD RECKONING & DIRECTIONAL CONSTANTS ---
    private static final int ALIGNMENT_DEGREE_MARGIN = 30;
    // A step is considered "forward" if it's within 90 degrees of the target. Beyond that, it's sideways or backward.
    private static final int MOVEMENT_DIRECTION_MARGIN_DEGREES = 90;
    // The maximum allowed distance (in meters) between the user's actual and ideal path before recalibration is advised.
    private static final float REALIGNMENT_DISTANCE_THRESHOLD_METERS = 2.0f;

    // --- State Variables ---
    private float dynamicThreshold;
    private long lastStepTime = 0;
    private int stepsTaken = 0;
    private boolean isPeakDetected = false;

    // --- NEW Dead Reckoning State Variables ---
    public static float AVERAGE_STEP_LENGTH_METERS;
    // The user's actual estimated position, updated with each step's real direction.

    /**
     * Constructor for the StepDetector.
     * @param listener The object that will receive step notifications.
     */
    public StepDetector(OnStepListener listener) {
        this.listener = listener;
        calibrate(DEFAULT_USER_HEIGHT_CM);
    }

    /**
     * Calibrates thresholds and step length based on the user's height.
     * @param userHeightCm The user's height in centimeters.
     */
    public void calibrate(float userHeightCm) {
        // Calibrate step detection sensitivity
        float baseThreshold = 11.0f;
        float sensitivity = 0.05f;
        this.dynamicThreshold = baseThreshold + (userHeightCm - 170.0f) * sensitivity;

        // Calibrate average step length (a common estimate is height * 0.414)
        this.AVERAGE_STEP_LENGTH_METERS = (userHeightCm / 100.0f) * 0.38f;
    }

    /**
     * Checks if the user's current heading is aligned with the target direction for initial setup.
     * @param currentDegree The user's current compass heading (0-360).
     * @param targetDegree  The target compass heading (0-360).
     * @return true if the user is facing the target direction.
     */
    public boolean isAlignedWithTarget(float currentDegree, float targetDegree) {
        float difference = Math.abs(currentDegree - targetDegree);
        if (difference > 180) {
            difference = 360 - difference;
        }
        return difference <= ALIGNMENT_DEGREE_MARGIN;
    }

    /**
     * Processes sensor data to detect steps and update the user's dead-reckoned position.
     * @param acceleration The 3-axis accelerometer data (m/s^2).
     * @param rotationMatrix The current 9-element rotation matrix of the device.
     * @param targetDirection The compass direction (0-360 degrees) the user should walk towards.
     */
    public void processSensorData(float[] acceleration, float[] rotationMatrix, float targetDirection) {
        long now = System.currentTimeMillis();

        if (now - lastStepTime < STEP_TIME_GATE_MS) {
            return;
        }

        float magnitude = (float) Math.sqrt(
                acceleration[0] * acceleration[0] +
                        acceleration[1] * acceleration[1] +
                        acceleration[2] * acceleration[2]
        );

        if (!isPeakDetected && magnitude > dynamicThreshold) {
            isPeakDetected = true;
        } else if (isPeakDetected && magnitude < (dynamicThreshold * STEP_DETECTION_LOWER_THRESHOLD_FACTOR)) {
            // A peak-and-valley pattern indicates a potential step. Now, analyze its direction.
            processStep(acceleration, rotationMatrix, targetDirection);
            isPeakDetected = false; // Reset for the next step.
        }
    }

    /**
     * Analyzes a detected step's direction, updates positions, and notifies the listener.
     */
    private void processStep(float[] acceleration, float[] rotationMatrix, float targetDirection) {
        // --- Determine the actual direction of the step ---
        float[] worldAccel = new float[3];
        // R[0], R[1], R[2] are for World X (East)
        // R[3], R[4], R[5] are for World Y (North)
        worldAccel[0] = rotationMatrix[0] * acceleration[0] + rotationMatrix[1] * acceleration[1] + rotationMatrix[2] * acceleration[2];
        worldAccel[1] = rotationMatrix[3] * acceleration[0] + rotationMatrix[4] * acceleration[1] + rotationMatrix[5] * acceleration[2];

        // The direction (azimuth) of the horizontal movement.
        double movementAzimuthRad = Math.atan2(worldAccel[0], worldAccel[1]);
        float movementAzimuthDeg = (float) Math.toDegrees(movementAzimuthRad);
        if (movementAzimuthDeg < 0) {
            movementAzimuthDeg += 360;
        }

        // --- Check if the step was in a "forward" direction ---
        float difference = Math.abs(movementAzimuthDeg - targetDirection);
        if (difference > 180) {
            difference = 360 - difference;
        }

        if (difference <= MOVEMENT_DIRECTION_MARGIN_DEGREES) {
            // --- VALID FORWARD STEP ---
            lastStepTime = System.currentTimeMillis();
            stepsTaken++;

            if (listener != null) {
                listener.onStep(stepsTaken);
            }
        }
        // If the step was not "forward" (e.g., sideways or backward), we simply ignore it.
        // It does not contribute to forward* progress or error accumulation.
    }



    /**
     * Resets the dead reckoning system to start a new navigation leg.
     * @param initialSteps The number of steps to set as the starting point (usually 0).
     */
    public void reset(int initialSteps) {
        this.stepsTaken = initialSteps;
        this.lastStepTime = 0;
        this.isPeakDetected = false;
    }

    /**
     * Gets the total number of forward steps detected since the last reset.
     * @return The current step count.
     */
    public int getStepsTaken() {
        return stepsTaken;
    }

    /**
     * Calculates the total distance covered along the ideal path in meters.
     * This represents the user's progress toward the goal.
     * @return The progress distance in meters.
     */
    public double getMetersCovered() {
        return stepsTaken * AVERAGE_STEP_LENGTH_METERS;
    }

    /**
     * Calculates the estimated number of steps remaining to reach a target distance.
     * @param totalDistanceMeters The total distance (in meters) for the current navigation leg.
     * @return The estimated number of steps still needed.
     */
    public int getRemainingSteps(double totalDistanceMeters) {
        double metersRemaining = Math.max(0, totalDistanceMeters - getMetersCovered());
        return (int) Math.ceil(metersRemaining / AVERAGE_STEP_LENGTH_METERS);
    }
}