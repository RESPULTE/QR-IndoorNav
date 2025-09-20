package com.example.qr_indoornav.model;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MapData {
    private static final String TAG = "MapData";

    // Static instances to hold the single, globally accessible map data
    private static Graph graphInstance;
    private static List<Location> allLocations;
    private static Map<String, Location> locationMap;
    private static String currentJunctionId;

    /**
     * Resets all static map data. Should be called if a new map needs to be loaded.
     */
    public static void reset() {
        graphInstance = null;
        allLocations = null;
        locationMap = null;
        currentJunctionId = null;
    }

    /**
     * The new primary method to load and parse all map data from a QR string.
     * This replaces the old `loadMapFromJSON` method.
     * @param qrString The complete string data from the scanned junction QR code.
     */
    public static void loadMapFromQRString(String qrString) {
        // Reset previous data before loading a new map
        reset();

        graphInstance = new Graph();
        allLocations = new ArrayList<>();
        locationMap = new HashMap<>();

        try {
            String[] pathEntries = qrString.split(";");

            // --- Pass 1: Discover all nodes to create Node objects ---
            for (String entry : pathEntries) {
                String pathPart = entry.split(":")[0]; // "1-2"
                String[] nodeIds = pathPart.split("-");
                String fromId = "N" + nodeIds[0];
                String toId = "N" + nodeIds[1];
                if (graphInstance.getNode(fromId) == null) graphInstance.addNode(new Node(fromId));
                if (graphInstance.getNode(toId) == null) graphInstance.addNode(new Node(toId));
            }
            Log.d(TAG, "Discovered " + graphInstance.getAllNodes().size() + " unique junction nodes from QR string.");

            // --- Pass 2: Build edges and create Location objects for rooms ---
            for (String entry : pathEntries) {
                String[] parts = entry.split(":");
                String[] nodeIds = parts[0].split("-");
                String fromId = "N" + nodeIds[0];
                String toId = "N" + nodeIds[1];

                String[] details = parts[1].split(",");
                double distance = Double.parseDouble(details[0]);
                float direction = Float.parseFloat(details[1]);

                List<String> forwardRoomIds = new ArrayList<>();
                if (details.length > 2) {
                    // There are rooms specified for this path
                    forwardRoomIds = parseRooms(details[2]);
                }

                for (String roomId : forwardRoomIds) {
                    Location roomLocation = new Location(roomId, "Room " + roomId, fromId);
                    locationMap.put(roomId, roomLocation);
                }

                List<String> reverseRoomIds = new ArrayList<>(forwardRoomIds);
                Collections.reverse(reverseRoomIds);

                graphInstance.getNode(fromId).addEdge(toId, (int) Math.round(distance), direction, forwardRoomIds);
                float reverseDirection = (direction + 180) % 360;
                graphInstance.getNode(toId).addEdge(fromId, (int) Math.round(distance), reverseDirection, reverseRoomIds);
            }
            Log.d(TAG, "Created " + locationMap.size() + " room locations.");


            // --- Pass 3: Create Location objects for junctions ---
            for (Node node : graphInstance.getAllNodes()) {
                // The display name is now simplified, e.g., "Junction N1"
                Location junctionLocation = new Location(node.id, "Junction " + node.id, node.id);
                locationMap.put(node.id, junctionLocation);
            }

            // --- Final Step: Compile the final list for the dropdown ---
            allLocations = new ArrayList<>(locationMap.values());
            allLocations.sort((l1, l2) -> l1.displayName.compareTo(l2.displayName));
            Log.d(TAG, "Map data successfully loaded from QR String. Total locations: " + allLocations.size());

        } catch (Exception e) {
            Log.e(TAG, "CRITICAL ERROR: Failed to parse QR string map data.", e);
            reset(); // Clear any partially loaded data
            throw new RuntimeException("Failed to load map from QR string. See Logcat.", e);
        }
    }

    private static List<String> parseRooms(String roomStr) {
        List<String> roomIds = new ArrayList<>();
        // This pattern can handle "N008-N010", "A1-A5", etc.
        Pattern rangePattern = Pattern.compile("([A-Za-z]+)(\\d+)-([A-Za-z]+)?(\\d+)");
        Matcher matcher = rangePattern.matcher(roomStr.trim());

        if (matcher.matches()) {
            String prefix1 = matcher.group(1);
            int start = Integer.parseInt(matcher.group(2));
            String prefix2 = matcher.group(3) != null ? matcher.group(3) : prefix1;
            int end = Integer.parseInt(matcher.group(4));

            if (prefix1.equals(prefix2)) {
                for (int j = start; j <= end; j++) {
                    String num = String.format("%0" + matcher.group(2).length() + "d", j);
                    roomIds.add(prefix1 + num);
                }
            } else {
                roomIds.add(roomStr.trim());
            }
        } else {
            roomIds.add(roomStr.trim());
        }
        return roomIds;
    }

    // --- PUBLIC GETTERS (API for the rest of the app) ---
    // Note: These methods no longer need a Context parameter.

    private static void checkLoaded() {
        if (graphInstance == null) {
            throw new IllegalStateException("MapData has not been loaded. Call loadMapFromQRString() first.");
        }
    }

    public static Graph getGraph() {
        checkLoaded();
        return graphInstance;
    }

    public static List<Location> getAllLocations() {
        checkLoaded();
        return allLocations;
    }

    public static Location getLocationById(String id) {
        checkLoaded();
        return locationMap.get(id);
    }

    public static String getCurrentJunctionId() {
        checkLoaded();
        return currentJunctionId;
    }

    public static void setCurrentJunctionId(String id) {
        currentJunctionId = id;
    }
}