// Create new file: Pathfinder.java
package com.example.qr_indoornav;

import android.util.Log;
import android.util.Pair;
import com.example.qr_indoornav.model.Edge;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.Node;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PathFinder {

    private static final String TAG = "PathFinder";

    /**
     * Represents the result of a pathfinding operation, including the path and its total distance.
     */
    public static class PathResult {
        public final List<String> nodeIds;
        public final int totalDistance;

        public PathResult(List<String> nodeIds, int totalDistance) {
            this.nodeIds = nodeIds;
            this.totalDistance = totalDistance;
        }

        public boolean isFound() {
            return nodeIds != null && !nodeIds.isEmpty();
        }
    }

    /**
     * Main public method to find the optimal path. It handles any combination of
     * Junctions and Rooms as origin and destination.
     *
     * @param graph The graph data.
     * @param originId The ID of the starting location (can be a Junction or a Room).
     * @param destinationId The ID of the destination location (can be a Junction or a Room).
     * @return A PathResult containing the list of node IDs (including rooms) and total distance.
     */
    public static PathResult findPath(Graph graph, String originId, String destinationId) {
        boolean isOriginRoom = graph.getNode(originId) == null;
        boolean isDestinationRoom = graph.getNode(destinationId) == null;

        // --- Step 1: Handle the special case where origin and destination are rooms on the SAME edge ---
        if (isOriginRoom && isDestinationRoom) {
            Pair<String, String> originJunctions = findEndpointJunctionsForRoom(graph, originId);
            Pair<String, String> destJunctions = findEndpointJunctionsForRoom(graph, destinationId);

            // Check if they share the same pair of endpoint junctions
            if (originJunctions != null && destJunctions != null &&
                    ((Objects.equals(originJunctions.first, destJunctions.first) && Objects.equals(originJunctions.second, destJunctions.second)) ||
                            (Objects.equals(originJunctions.first, destJunctions.second) && Objects.equals(originJunctions.second, destJunctions.first)))) {

                Log.d(TAG, "Origin and Destination are rooms on the same edge.");
                Edge edge = graph.getNode(originJunctions.first).getEdgeTo(originJunctions.second);
                if (edge != null) {
                    int dist = calculateIntraEdgeDistance(edge, originId, destinationId);
                    List<String> path = new ArrayList<>();
                    path.add(originId);
                    path.add(destinationId); // Simple path: Start Room -> End Room
                    return new PathResult(path, dist);
                }
            }
        }

        // --- Step 2: Get "Anchor Points" for origin and destination ---
        // An anchor point is a junction that a location is connected to, and the distance to it.
        // A Junction has one anchor (itself, distance 0). A Room has two (the ends of its edge).
        Map<String, Integer> originAnchors = getAnchorPoints(graph, originId);
        Map<String, Integer> destAnchors = getAnchorPoints(graph, destinationId);

        if (originAnchors.isEmpty() || destAnchors.isEmpty()) {
            Log.e(TAG, "Could not find anchor points for origin or destination.");
            return new PathResult(Collections.emptyList(), 0);
        }

        // --- Step 3: Find the best path by checking all anchor-to-anchor combinations ---
        int minTotalDistance = Integer.MAX_VALUE;
        List<String> bestJunctionPath = null;

        for (Map.Entry<String, Integer> originAnchor : originAnchors.entrySet()) {
            for (Map.Entry<String, Integer> destAnchor : destAnchors.entrySet()) {
                String startJunction = originAnchor.getKey();
                String endJunction = destAnchor.getKey();

                // Find the shortest path between the two anchor junctions
                List<String> junctionPath = graph.findShortestPath(startJunction, endJunction);
                if (junctionPath.isEmpty() && !startJunction.equals(endJunction)) {
                    continue; // No path found between these anchors
                }

                // Calculate the total distance for this combination
                int junctionPathDistance = calculateJunctionPathDistance(graph, junctionPath);
                int totalDistance = originAnchor.getValue() + junctionPathDistance + destAnchor.getValue();

                if (totalDistance < minTotalDistance) {
                    minTotalDistance = totalDistance;
                    bestJunctionPath = junctionPath;
                }
            }
        }

        if (bestJunctionPath == null) {
            Log.e(TAG, "No valid path found between any anchor points.");
            return new PathResult(Collections.emptyList(), 0);
        }

        // --- Step 4: Construct the final path list, adding rooms if necessary ---
        List<String> finalPath = new ArrayList<>(bestJunctionPath);
        if (isOriginRoom) {
            finalPath.add(0, originId);
        }
        if (isDestinationRoom) {
            // Avoid adding if the destination is the same as the last junction (edge case)
            if (finalPath.isEmpty() || !finalPath.get(finalPath.size() - 1).equals(destinationId)) {
                finalPath.add(destinationId);
            }
        }

        return new PathResult(finalPath, minTotalDistance);
    }

    /**
     * Gets the anchor junctions for a given location ID.
     * - If the location is a Junction, returns a map with one entry: {junctionId, 0}.
     * - If the location is a Room, returns a map with two entries: {junctionA, distToA}, {junctionB, distToB}.
     *
     * @return A map of anchor Junction IDs to the distance from the location to that junction.
     */
    private static Map<String, Integer> getAnchorPoints(Graph graph, String locationId) {
        Map<String, Integer> anchors = new HashMap<>();

        // Case 1: The location is a junction itself.
        if (graph.getNode(locationId) != null) {
            anchors.put(locationId, 0);
            return anchors;
        }

        // Case 2: The location is a room. Find its edge and calculate distances to both ends.
        Pair<String, String> endpoints = findEndpointJunctionsForRoom(graph, locationId);
        if (endpoints != null) {
            Edge edge = graph.getNode(endpoints.first).getEdgeTo(endpoints.second);
            if (edge != null) {
                int totalRooms = edge.roomIds.size();
                int roomIndex = edge.roomIds.indexOf(locationId);
                if (roomIndex != -1) {
                    float ratio = (float) (roomIndex + 1) / (totalRooms + 1);
                    int distToA = (int) (ratio * edge.distanceMeters);
                    int distToB = edge.distanceMeters - distToA;
                    anchors.put(endpoints.first, distToA);
                    anchors.put(endpoints.second, distToB);
                }
            }
        }
        return anchors;
    }

    /**
     * Calculates the distance between two rooms on the same edge.
     */
    private static int calculateIntraEdgeDistance(Edge edge, String roomA, String roomB) {
        int totalRooms = edge.roomIds.size();
        int indexA = edge.roomIds.indexOf(roomA);
        int indexB = edge.roomIds.indexOf(roomB);

        float ratioA = (float) (indexA + 1) / (totalRooms + 1);
        float ratioB = (float) (indexB + 1) / (totalRooms + 1);

        return (int) (Math.abs(ratioA - ratioB) * edge.distanceMeters);
    }

    /**
     * Calculates the total distance of a path consisting only of junctions.
     */
    private static int calculateJunctionPathDistance(Graph graph, List<String> junctionPath) {
        int totalDistance = 0;
        if (junctionPath == null || junctionPath.size() < 2) {
            return 0;
        }
        for (int i = 0; i < junctionPath.size() - 1; i++) {
            Node fromNode = graph.getNode(junctionPath.get(i));
            String toNodeId = junctionPath.get(i + 1);
            if (fromNode != null && fromNode.getEdgeTo(toNodeId) != null) {
                totalDistance += fromNode.getEdgeTo(toNodeId).distanceMeters;
            }
        }
        return totalDistance;
    }

    /**
     * Finds the two junctions that an edge (containing a specific room) connects.
     * @return A Pair of junction IDs (from, to), or null if not found.
     */
    private static Pair<String, String> findEndpointJunctionsForRoom(Graph graph, String roomId) {
        for (Node node : graph.getAllNodes()) {
            for (Edge edge : node.getEdges().values()) {
                if (edge.roomIds.contains(roomId)) {
                    return new Pair<>(node.id, edge.toNodeId);
                }
            }
        }
        return null; // Room not found on any edge
    }
}