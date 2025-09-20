package com.example.qr_indoornav.model;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MapData {
    private static final String TAG = "MapData";

    // Static instances to hold the single, globally accessible map data
    private static Graph graphInstance;
    private static List<Location> allLocations;
    private static Map<String, Location> locationMap;

    // Static fields to hold the map's naming convention, parsed from the header
    private static String idPrefix;
    private static int idNumDigits;
    private static String scannedLocationId; // Stores the ID from the QR header

    /**
     * Resets all static map data. Should be called if a new map needs to be loaded.
     */
    public static void reset() {
        graphInstance = null;
        allLocations = null;
        locationMap = null;
        idPrefix = null;
        idNumDigits = 0;
        scannedLocationId = null;
    }

    /**
     * The primary method to load and parse all map data from a QR string.
     * This is called once to build the entire map in memory.
     * @param qrString The complete string data from any scanned QR code.
     *                 Example: "JN3A|AB,54,290,H-J|AF,5,245|..."
     */
    public static void loadMapFromQRString(String qrString) {
        reset();

        graphInstance = new Graph();
        allLocations = new ArrayList<>();
        locationMap = new HashMap<>();

        try {
            String[] parts = qrString.split("\\|");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid QR format: Must contain a header and at least one edge.");
            }

            // --- Step 1: Parse Header to understand ID format AND get scanned location ---
            parseHeader(parts[0]);
            Log.d(TAG, "Map ID format parsed: Prefix=" + idPrefix + ", Digits=" + idNumDigits);
            Log.i(TAG, "Scanned Location ID from header: " + scannedLocationId);

            Set<Character> discoveredNodeChars = new HashSet<>();
            List<String> edgeStrings = new ArrayList<>();

            // --- Step 2 (Pass 1): Discover all nodes (junctions) and prepare edge data ---
            for (int i = 1; i < parts.length; i++) {
                String edgeString = parts[i];
                if (edgeString == null || edgeString.isEmpty()) continue;

                String nodeChars = edgeString.split(",")[0]; // "AB" from "AB,54,290,H-J"
                if(nodeChars.length() < 2) continue;

                discoveredNodeChars.add(nodeChars.charAt(0));
                discoveredNodeChars.add(nodeChars.charAt(1));
                edgeStrings.add(edgeString);
            }

            // Create Node objects for all discovered junctions using the junction ID format
            for (Character nodeChar : discoveredNodeChars) {
                // <-- CHANGE: Use the specific function for junctions
                String junctionId = charToJunctionId(nodeChar);
                graphInstance.addNode(new Node(junctionId));
            }
            Log.d(TAG, "Discovered " + graphInstance.getAllNodes().size() + " unique junction nodes.");


            // --- Step 3 (Pass 2): Build edges and create Location objects for rooms ---
            for (String edgeString : edgeStrings) {
                String[] details = edgeString.split(","); // [AB, 54, 290, H-J]

                String nodeChars = details[0];
                // <-- CHANGE: Use the specific function for junctions
                String fromId = charToJunctionId(nodeChars.charAt(0));
                String toId = charToJunctionId(nodeChars.charAt(1));

                int distance = Integer.parseInt(details[1]);
                float direction = Float.parseFloat(details[2]);

                List<String> forwardRoomIds = new ArrayList<>();
                if (details.length > 3) {
                    // There are rooms specified for this path. This already uses the correct room ID format.
                    forwardRoomIds = parseRoomRange(details[3]);
                }

                // Create Location objects for each room on this path
                for (String roomId : forwardRoomIds) {
                    Location roomLocation = new Location(roomId, "Room " + roomId, fromId);
                    locationMap.put(roomId, roomLocation);
                }

                // Add forward edge
                graphInstance.getNode(fromId).addEdge(toId, distance, direction, forwardRoomIds);

                // Add reverse edge
                List<String> reverseRoomIds = new ArrayList<>(forwardRoomIds);
                Collections.reverse(reverseRoomIds);
                float reverseDirection = (direction + 180) % 360;
                graphInstance.getNode(toId).addEdge(fromId, distance, reverseDirection, reverseRoomIds);
            }
            Log.d(TAG, "Created " + locationMap.size() + " room locations and all graph edges.");


            // --- Step 4 (Pass 3): Create Location objects for junctions ---
            for (Node node : graphInstance.getAllNodes()) {
                // node.id is now already in the correct format (e.g., "N1")
                Location junctionLocation = new Location(node.id, "Junction " + node.id, node.id);
                locationMap.put(node.id, junctionLocation);
            }

            // --- Final Step: Compile the final list for the dropdown ---
            allLocations = new ArrayList<>(locationMap.values());
            allLocations.sort((l1, l2) -> l1.displayName.compareTo(l2.displayName));
            Log.i(TAG, "Map data successfully loaded from QR String. Total locations: " + allLocations.size());

        } catch (Exception e) {
            Log.e(TAG, "CRITICAL ERROR: Failed to parse QR string map data.", e);
            reset(); // Clear any partially loaded data to prevent app instability
            throw new RuntimeException("Failed to load map from QR string. See Logcat.", e);
        }
    }

    /**
     * Parses the header to set the global ID prefix, number of digits, and the scanned location ID.
     * @param header e.g., "JN3A" or "rN3H"
     */
    private static void parseHeader(String header) {
        if (header == null || header.length() < 4) {
            throw new IllegalArgumentException("Header is malformed.");
        }
        char typeChar = header.charAt(0); // 'J' or 'r'
        idPrefix = header.substring(1, 2);    // "N"
        idNumDigits = Integer.parseInt(header.substring(2, 3)); // 3
        char locationChar = header.charAt(3); // 'A' or 'H' etc.

        // <-- CHANGE: Generate the scanned location ID based on its type
        if (typeChar == 'J') {
            scannedLocationId = charToJunctionId(locationChar);
        } else if (typeChar == 'R') {
            scannedLocationId = charToRoomId(locationChar);
        } else {
            throw new IllegalArgumentException("Unknown type character in header: " + typeChar);
        }
    }

    /**
     * <-- NEW METHOD -->
     * Converts a character to a JUNCTION ID (e.g., 'A' -> "N1", 'B' -> "N2").
     * Uses no zero-padding.
     */
    private static String charToJunctionId(char c) {
        if (idPrefix == null) {
            throw new IllegalStateException("Header must be parsed before converting chars to IDs.");
        }
        int numericValue = c - 'A' + 1;
        return idPrefix + numericValue;
    }

    /**
     * <-- NEW METHOD -->
     * Converts a character to a ROOM ID (e.g., 'H' -> "N008").
     * Uses the zero-padding specified in the header.
     */
    private static String charToRoomId(char c) {
        if (idPrefix == null || idNumDigits == 0) {
            throw new IllegalStateException("Header must be parsed before converting chars to IDs.");
        }
        int numericValue = c - 'A' + 1;
        String formatString = "%0" + idNumDigits + "d";
        return idPrefix + String.format(formatString, numericValue);
    }


    /**
     * Parses a room range string (e.g., "H-J") into a list of full room IDs.
     * @param rangeStr e.g., "H-J"
     * @return List of strings, e.g., ["N008", "N009", "N010"]
     */
    private static List<String> parseRoomRange(String rangeStr) {
        List<String> roomIds = new ArrayList<>();
        String[] range = rangeStr.split("-");
        if (range.length == 2) {
            char startChar = range[0].charAt(0);
            char endChar = range[1].charAt(0);
            for (char c = startChar; c <= endChar; c++) {
                // <-- CHANGE: Use the specific function for rooms
                roomIds.add(charToRoomId(c));
            }
        }
        return roomIds;
    }


    // --- PUBLIC GETTERS (API for the rest of the app) ---

    private static void checkLoaded() {
        if (graphInstance == null) {
            throw new IllegalStateException("MapData has not been loaded. Call loadMapFromQRString() first.");
        }
    }

    public static String getScannedLocationId() {
        checkLoaded();
        return scannedLocationId;
    }

    public static Graph getGraph() {
        checkLoaded();
        return graphInstance;
    }

    public static List<Location> getAllLocations() {
        checkLoaded();
        return allLocations != null ? allLocations : Collections.emptyList();
    }

    public static Location getLocationById(String id) {
        checkLoaded();
        return locationMap.get(id);
    }
}