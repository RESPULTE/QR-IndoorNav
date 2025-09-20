package com.example.qr_indoornav;

import android.hardware.SensorManager;

/**
 * A unified module for handling step detection, alignment checks, and related calibration.
 *
 * REFINEMENTS:
 * 1.  Improved Step Detection: Implemented a peak-and-valley detection algorithm.
 *     A step is only registered after acceleration rises above a threshold (peak)
 *     and then falls below a lower threshold (valley). This makes the detector
 *     more robust against single, sudden shakes.
 *
 * 2.  Directional Deviation Tolerance: Added a mechanism to tolerate brief
 *     deviations from the target direction. Instead of immediately rejecting
 *     off-direction steps, it now tracks consecutive off-direction movements.
 *     A new method, `needsRealignment()`, allows the calling UI to query if the
 *     user is consistently off-track before prompting for realignment.
 *
 * 3.  Centralized Alignment Logic: Includes a method to check for static alignment
 *     with a target direction, consolidating all directional checks into this class.
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
    private static final float DEFAULT_USER_HEIGHT_CM = 175.0f; // Default height for initial calibration

    // --- REFINED STEP DETECTION CONSTANTS ---
    private static final long STEP_TIME_GATE_MS = 800; // Increased minimum time between steps to better filter out shakes.
    private static final float STEP_DETECTION_LOWER_THRESHOLD_FACTOR = 0.9f; // A step is confirmed when magnitude drops below this factor of the dynamic threshold.

    // --- DIRECTIONAL DEVIATION CONSTANTS ---
    private static final int ALIGNMENT_DEGREE_MARGIN = 15; // Tolerance (degrees) for static alignment before starting to walk.
    private static final int MOVEMENT_DIRECTION_MARGIN_DEGREES = 45; // Max allowed deviation (degrees) for a step to be considered "forward"
    private static final int OFF_DIRECTION_TOLERANCE = 3; // Number of consecutive off-direction steps before flagging for realignment.
    private static final long OFF_DIRECTION_RESET_TIME_MS = 2000; // If 2 seconds pass without an off-direction step, the counter resets.

    // --- State Variables ---
    public static double step_length_meter = 5; // The estimated distance of one step in meters

    private float dynamicThreshold; // Accelerometer magnitude threshold, adjusted by height
    private long lastStepTime = 0; // Timestamp of the last detected valid step
    private int stepsTaken = 0; // Total steps counted since the last reset

    // --- NEW State Variables for Refined Detection ---
    private boolean isPeakDetected = false; // Tracks if the 'peak' of a step's acceleration has been detected.
    private int consecutiveOffDirectionSteps = 0; // Counter for steps taken in the wrong direction.
    private long lastOffDirectionStepTime = 0; // Timestamp of the last off-direction step.

    /**
     * Constructor for the StepDetector.
     * @param listener The object that will receive step notifications. Can be null if only using for alignment checks.
     */
    public StepDetector(OnStepListener listener) {
        this.listener = listener;
        // Calibrate with a default user height when the detector is created.
        calibrate(DEFAULT_USER_HEIGHT_CM);
    }

    /**
     * Calibrates the step detection threshold and step length based on the user's height.
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
     * Checks if the user's current heading is aligned with the target direction within a predefined margin of error.
     * This is used for static alignment before navigation begins.
     *
     * @param currentDegree The user's current compass heading (0-360).
     * @param targetDegree  The target compass heading (0-360).
     * @return true if the user is facing the target direction, false otherwise.
     */
    public boolean isAlignedWithTarget(float currentDegree, float targetDegree) {
        float difference = Math.abs(currentDegree - targetDegree);
        if (difference > 180) {
            difference = 360 - difference; // Get the shortest angular difference
        }
        return difference <= ALIGNMENT_DEGREE_MARGIN;
    }

    /**
     * Processes raw accelerometer data to detect steps using a peak-and-valley algorithm.
     * A step is counted if:
     * 1. The acceleration magnitude surpasses a dynamic threshold (the "peak").
     * 2. The magnitude then drops below a lower threshold (the "valley").
     * 3. The time since the last step is sufficient (time gate).
     * 4. The step's orientation is in the general forward direction of travel.
     *
     * It also tracks consecutive off-direction steps to determine if realignment is needed.
     *
     * @param acceleration The 3-axis (X, Y, Z) accelerometer data (m/s^2).
     * @param rotationMatrix The current 9-element rotation matrix of the device,
     *                       used to transform accelerometer data to world coordinates.
     * @param targetDirection The compass direction (0-360 degrees) the user is intended to walk towards.
     */
    public void processSensorData(float[] acceleration, float[] rotationMatrix, float targetDirection) {
        long now = System.currentTimeMillis();

        // Automatically reset the off-direction counter if the user has been walking correctly
        // or has stopped for a couple of seconds.
        if (now - lastOffDirectionStepTime > OFF_DIRECTION_RESET_TIME_MS) {
            consecutiveOffDirectionSteps = 0;
        }

        // 1. Time Gate: Prevents counting a single step multiple times.
        if (now - lastStepTime < STEP_TIME_GATE_MS) {
            return;
        }

        // 2. Magnitude Calculation: Calculate the overall force (magnitude) of acceleration.
        float magnitude = (float) Math.sqrt(
                acceleration[0] * acceleration[0] +
                        acceleration[1] * acceleration[1] +
                        acceleration[2] * acceleration[2]
        );

        // 3. Peak and Valley Detection Logic
        // Check for the peak of the step
        if (!isPeakDetected && magnitude > dynamicThreshold) {
            isPeakDetected = true;
        }
        // Check for the valley (end of the step) after a peak has been detected
        else if (isPeakDetected && magnitude < (dynamicThreshold * STEP_DETECTION_LOWER_THRESHOLD_FACTOR)) {
            // A potential step motion is complete. Now, check its direction.
            if (isStepInForwardDirection(acceleration, rotationMatrix, targetDirection)) {
                // --- VALID STEP IN CORRECT DIRECTION ---
                lastStepTime = now;
                stepsTaken++;

                // A correct step resets the off-direction counter.
                consecutiveOffDirectionSteps = 0;

                // Notify the listener that a valid step has occurred.
                if (listener != null) {
                    listener.onStep(stepsTaken);
                }
            } else {
                // --- STEP-LIKE MOTION IN WRONG DIRECTION ---
                // This was a step, but not towards the target. Increment the off-direction counter.
                consecutiveOffDirectionSteps++;
                lastOffDirectionStepTime = now;
            }

            // Reset the peak detector to be ready for the next step.
            isPeakDetected = false;
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
     * Checks if the user is consistently heading in the wrong direction.
     * This can be polled by the UI to decide when to prompt the user to realign.
     * The check is based on the number of consecutive steps detected
     * that were outside the allowed directional margin.
     *
     * @return true if the number of consecutive off-direction steps has
     *         exceeded the tolerance, otherwise false.
     */
    public boolean needsRealignment() {
        return consecutiveOffDirectionSteps >= OFF_DIRECTION_TOLERANCE;
    }

    /**
     * Resets the internal step count and all related state variables.
     * Useful for starting a new navigation leg.
     * @param initialSteps The number of steps to set as the starting point.
     */
    public void reset(int initialSteps) {
        this.stepsTaken = initialSteps;
        this.lastStepTime = 0;
        this.isPeakDetected = false;
        this.consecutiveOffDirectionSteps = 0;
        this.lastOffDirectionStepTime = 0;
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
        return stepsTaken * step_length_meter;
    }

    /**
     * Calculates the estimated number of steps remaining to reach a target distance.
     * @param totalDistanceMeters The total distance (in meters) for the current navigation leg.
     * @return The estimated number of steps still needed.
     */
    public int getRemainingSteps(int totalDistanceMeters) {
        double metersRemaining = Math.max(0, totalDistanceMeters - getMetersCovered());
        return (int) Math.ceil(metersRemaining / step_length_meter);
    }
}