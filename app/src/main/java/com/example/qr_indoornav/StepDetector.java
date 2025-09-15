package com.example.qr_indoornav;

import android.hardware.SensorManager;

/**
 * A unified module for detecting steps from accelerometer data.
 * It also handles calibration and provides utilities for step-to-distance conversion.
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
    public static final double AVERAGE_STEP_LENGTH_METERS = 5; // The estimated distance of one step in meters
    private static final float DEFAULT_USER_HEIGHT_CM = 175.0f; // Default height for initial calibration
    private static final long STEP_TIME_GATE_MS = 300; // Minimum time between steps to filter out shaking
    private static final int MOVEMENT_DIRECTION_MARGIN_DEGREES = 45; // Max allowed deviation (degrees) for a step to be considered "forward"

    // --- State Variables ---
    private float dynamicThreshold; // Accelerometer magnitude threshold, adjusted by height
    private long lastStepTime = 0; // Timestamp of the last detected valid step
    private int stepsTaken = 0; // Total steps counted since the last reset

    /**
     * Constructor for the StepDetector.
     * @param listener The object that will receive step notifications.
     */
    public StepDetector(OnStepListener listener) {
        this.listener = listener;
        // Calibrate with a default user height when the detector is created.
        calibrate(DEFAULT_USER_HEIGHT_CM);
    }

    /**
     * Calibrates the step detection threshold based on the user's height.
     * Taller individuals generally have a more pronounced acceleration spike during a step.
     * This method can be called dynamically if user height changes.
     * @param userHeightCm The user's height in centimeters.
     */
    public void calibrate(float userHeightCm) {
        // Base threshold (for an average 170cm person) + adjustment based on height difference.
        // These values are empirical and might need further tuning.
        float baseThreshold = 11.0f; // A typical acceleration magnitude for a step (m/s^2)
        float sensitivity = 0.05f;   // How much the threshold changes per cm of height difference
        this.dynamicThreshold = baseThreshold + (userHeightCm - 170.0f) * sensitivity;
    }

    /**
     * Processes raw accelerometer data to detect steps.
     * A step is counted only if it passes a magnitude threshold, a time gate,
     * and is oriented in the general forward direction of travel.
     * @param acceleration The 3-axis (X, Y, Z) accelerometer data (m/s^2).
     * @param rotationMatrix The current 9-element rotation matrix of the device,
     *                       used to transform accelerometer data to world coordinates.
     * @param targetDirection The compass direction (0-360 degrees) the user is intended to walk towards.
     */
    public void processSensorData(float[] acceleration, float[] rotationMatrix, float targetDirection) {
        long now = System.currentTimeMillis();

        // 1. Time Gate: Filter out rapid, non-step-like movements (e.g., shaking hands).
        if (now - lastStepTime < STEP_TIME_GATE_MS) {
            return;
        }

        // 2. Magnitude Threshold: Calculate the overall force (magnitude) of acceleration.
        float magnitude = (float) Math.sqrt(
                acceleration[0] * acceleration[0] +
                        acceleration[1] * acceleration[1] +
                        acceleration[2] * acceleration[2]
        );

        // 3. Magnitude Check: If the jolt is strong enough to be a step.
        if (magnitude > dynamicThreshold) {
            // 4. Directional Check: Confirm the jolt was in the intended forward direction.
            if (isStepInForwardDirection(acceleration, rotationMatrix, targetDirection)) {
                lastStepTime = now;
                stepsTaken++;
                // Notify the listener that a valid step has occurred.
                if (listener != null) {
                    listener.onStep(stepsTaken);
                }
            }
        }
    }

    /**
     * Determines if the detected acceleration jolt (step) was primarily in the forward direction.
     * This uses the device's rotation matrix to transform the acceleration vector from
     * the phone's local coordinate system to the Earth's (world) coordinate system.
     * @param acceleration The 3-axis accelerometer data in the device's coordinate system.
     * @param currentRotationMatrix The 9-element rotation matrix representing the device's orientation.
     * @param targetDirection The intended direction of movement (compass degrees).
     * @return true if the horizontal component of the acceleration aligns with the target direction.
     */
    private boolean isStepInForwardDirection(float[] acceleration, float[] currentRotationMatrix, float targetDirection) {
        float[] accelerationInWorldCoords = new float[3];

        // Transform acceleration from device coordinates to world coordinates.
        // We only care about the X and Y components (horizontal movement).
        // R[0], R[1], R[2] are for World X (East)
        // R[3], R[4], R[5] are for World Y (North)
        accelerationInWorldCoords[0] = currentRotationMatrix[0] * acceleration[0] + currentRotationMatrix[1] * acceleration[1] + currentRotationMatrix[2] * acceleration[2]; // World X
        accelerationInWorldCoords[1] = currentRotationMatrix[3] * acceleration[0] + currentRotationMatrix[4] * acceleration[1] + currentRotationMatrix[5] * acceleration[2]; // World Y

        // Calculate the azimuth (direction) of this horizontal movement.
        // atan2(x, y) gives the angle from the +Y axis (North).
        float movementAzimuth = (float) Math.toDegrees(Math.atan2(accelerationInWorldCoords[0], accelerationInWorldCoords[1]));
        if (movementAzimuth < 0) {
            movementAzimuth += 360; // Normalize to 0-360 degrees
        }

        // Compare the movement direction with the target direction.
        float difference = Math.abs(movementAzimuth - targetDirection);
        if (difference > 180) {
            difference = 360 - difference; // Find the shortest angular difference
        }

        // If the movement direction is within the allowed margin of error from the target direction.
        return difference <= MOVEMENT_DIRECTION_MARGIN_DEGREES;
    }

    /**
     * Resets the internal step count to a specified initial value.
     * Useful for resuming progress in a navigation leg.
     * @param initialSteps The number of steps to set as the starting point.
     */
    public void reset(int initialSteps) {
        this.stepsTaken = initialSteps;
        this.lastStepTime = 0; // Reset last step time to allow immediate step detection if needed
    }

    /**
     * Gets the total number of steps detected since the last reset.
     * @return The current step count.
     */
    public int getStepsTaken() {
        return stepsTaken;
    }

    /**
     * Calculates the total distance covered in meters based on the steps taken.
     * @return The total distance in meters.
     */
    public double getMetersCovered() {
        return stepsTaken * AVERAGE_STEP_LENGTH_METERS;
    }

    /**
     * Calculates the estimated number of steps remaining to reach a target distance.
     * @param totalDistanceMeters The total distance (in meters) for the current navigation leg.
     * @return The estimated number of steps still needed.
     */
    public int getRemainingSteps(int totalDistanceMeters) {
        double metersRemaining = Math.max(0, totalDistanceMeters - getMetersCovered());
        return (int) Math.ceil(metersRemaining / AVERAGE_STEP_LENGTH_METERS);
    }
}