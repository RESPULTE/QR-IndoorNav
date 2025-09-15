package com.example.qr_indoornav.model;

import android.content.Context;
import android.util.Log; // Import Log for better debugging

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MapData {
    private static final String TAG = "MapData"; // Tag for logging

    private static Graph graphInstance;
    private static List<Location> allLocations;
    private static Map<String, Location> locationMap;
    private static String currentJunctionId;

    private static void ensureLoaded(Context context) {
        if (graphInstance == null) {
            loadMapFromJSON(context);
        }
    }

    public static Graph getGraph(Context context) {
        ensureLoaded(context);
        return graphInstance;
    }

    public static List<Location> getAllLocations(Context context) {
        ensureLoaded(context);
        return allLocations;
    }

    public static Location getLocationById(Context context, String id) {
        ensureLoaded(context);
        return locationMap.get(id);
    }

    public static String getCurrentJunctionId(Context context) {
        ensureLoaded(context);
        return currentJunctionId;
    }

    private static void loadMapFromJSON(Context context) {
        graphInstance = new Graph();
        allLocations = new ArrayList<>();
        locationMap = new HashMap<>();

        try {
            String jsonString = loadJSONFromAsset(context, "map.json");
            JSONObject json = new JSONObject(jsonString);
            JSONArray pathsArray = json.getJSONArray("paths");

            // --- Pass 1: Discover all nodes and create Node objects ---
            for (int i = 0; i < pathsArray.length(); i++) {
                String[] nodeIds = pathsArray.getJSONObject(i).getString("path").split("-");
                if (graphInstance.getNode(nodeIds[0]) == null) graphInstance.addNode(new Node(nodeIds[0]));
                if (graphInstance.getNode(nodeIds[1]) == null) graphInstance.addNode(new Node(nodeIds[1]));
            }
            Log.d(TAG, "Discovered " + graphInstance.getAllNodes().size() + " unique junction nodes.");

            // Set current junction ID from the very first node of the first path
            currentJunctionId = pathsArray.getJSONObject(0).getString("path").split("-")[0];

            // --- Pass 2: Build edges and create Location objects for rooms ---
            for (int i = 0; i < pathsArray.length(); i++) {
                JSONObject pathObject = pathsArray.getJSONObject(i);
                String[] nodeIds = pathObject.getString("path").split("-");
                String fromId = nodeIds[0];
                String toId = nodeIds[1];
                int distance = pathObject.getInt("dist");
                float direction = (float) pathObject.getDouble("deg");
                JSONArray roomsArray = pathObject.getJSONArray("rooms");

                List<String> forwardRoomIds = parseRooms(roomsArray);
                for(String roomId : forwardRoomIds) {
                    Location roomLocation = new Location(roomId, "Room " + roomId, fromId);
                    locationMap.put(roomId, roomLocation);
                }

                List<String> reverseRoomIds = new ArrayList<>(forwardRoomIds);
                Collections.reverse(reverseRoomIds);

                graphInstance.getNode(fromId).addEdge(toId, distance, direction, forwardRoomIds);
                float reverseDirection = (direction + 180) % 360;
                graphInstance.getNode(toId).addEdge(fromId, distance, reverseDirection, reverseRoomIds);
            }
            Log.d(TAG, "Created " + locationMap.size() + " room locations.");

            // --- Pass 3: Create Location objects for junctions ---
            for (Node node : graphInstance.getAllNodes()) {
                Location junctionLocation = new Location(node.id, "Junction " + node.id, node.id);
                locationMap.put(node.id, junctionLocation);
            }
            Log.d(TAG, "Total locations (rooms + junctions) in map: " + locationMap.size());

            // --- FIX: COMPILE THE FINAL LIST *AFTER* EVERYTHING IS IN THE MAP ---
            allLocations = new ArrayList<>(locationMap.values());
            allLocations.sort((l1, l2) -> l1.displayName.compareTo(l2.displayName));
            Log.d(TAG, "Final sorted list `allLocations` contains: " + allLocations.size() + " items.");

        } catch (IOException | JSONException e) {
            // Make errors loud and clear during development
            Log.e(TAG, "CRITICAL ERROR: Failed to load or parse map.json. Check file existence and syntax.", e);
            // In a production app, you might handle this more gracefully. For now, crashing is better than silent failure.
            throw new RuntimeException("Failed to load map data from assets. See Logcat for details.", e);
        }
    }

    private static List<String> parseRooms(JSONArray roomsJsonArray) throws JSONException {
        List<String> roomIds = new ArrayList<>();
        // Updated Regex to be more flexible with room prefixes
        Pattern pattern = Pattern.compile("([A-Za-z]+)(\\d+)-[A-Za-z]*(\\d+)");

        for (int i = 0; i < roomsJsonArray.length(); i++) {
            String roomStr = roomsJsonArray.getString(i);
            Matcher matcher = pattern.matcher(roomStr);

            if (matcher.matches()) { // It's a range like "R100-R104" or "L201-L203"
                String prefix = matcher.group(1);
                int start = Integer.parseInt(matcher.group(2));
                int end = Integer.parseInt(matcher.group(3));
                for (int j = start; j <= end; j++) {
                    roomIds.add(prefix + j);
                }
            } else { // It's a single room like "C300"
                roomIds.add(roomStr);
            }
        }
        return roomIds;
    }

    private static String loadJSONFromAsset(Context context, String fileName) throws IOException {
        InputStream is = context.getAssets().open(fileName);
        int size = is.available();
        byte[] buffer = new byte[size];
        is.read(buffer);
        is.close();
        return new String(buffer, StandardCharsets.UTF_8);
    }
}