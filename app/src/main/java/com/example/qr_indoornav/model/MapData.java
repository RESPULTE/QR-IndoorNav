package com.example.qr_indoornav.model;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapData {
    private static Graph graphInstance;
    private static Map<String, String> locationNameToNodeIdMap;

    public static Graph getGraph(Context context) {
        if (graphInstance == null) {
            loadMapFromJSON(context);
        }
        return graphInstance;
    }

    public static List<Node> getLocations(Context context) {
        if (graphInstance == null) {
            loadMapFromJSON(context);
        }
        return graphInstance.getAllNodes();
    }

    public static String getNodeIdByName(Context context, String name) {
        if (locationNameToNodeIdMap == null) {
            loadMapFromJSON(context);
        }
        return locationNameToNodeIdMap.get(name);
    }

    private static void loadMapFromJSON(Context context) {
        graphInstance = new Graph();
        locationNameToNodeIdMap = new HashMap<>();
        try {
            String jsonString = loadJSONFromAsset(context, "map_data.json");
            JSONObject json = new JSONObject(jsonString);

            // 1. Load all nodes
            JSONArray nodesArray = json.getJSONArray("nodes");
            for (int i = 0; i < nodesArray.length(); i++) {
                JSONObject nodeObject = nodesArray.getJSONObject(i);
                String id = nodeObject.getString("id");
                String name = nodeObject.getString("locationName");
                graphInstance.addNode(new Node(id, name));
                locationNameToNodeIdMap.put(name, id);
            }

            // 2. Load all edges and create two-way paths
            JSONArray edgesArray = json.getJSONArray("edges");
            for (int i = 0; i < edgesArray.length(); i++) {
                JSONObject edgeObject = edgesArray.getJSONObject(i);
                String fromId = edgeObject.getString("from");
                String toId = edgeObject.getString("to");
                int distance = edgeObject.getInt("distanceMeters");
                float direction = (float) edgeObject.getDouble("directionDegrees");

                // Add the forward edge
                graphInstance.getNode(fromId).addEdge(toId, distance, direction);

                // Add the reverse edge automatically
                float reverseDirection = (direction + 180) % 360;
                graphInstance.getNode(toId).addEdge(fromId, distance, reverseDirection);
            }

        } catch (IOException | JSONException e) {
            e.printStackTrace();
            // Handle error, maybe load a default empty graph
            graphInstance = new Graph();
            locationNameToNodeIdMap = new HashMap<>();
        }
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