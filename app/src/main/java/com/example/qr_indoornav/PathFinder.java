// Create new file: Pathfinder.java
package com.example.qr_indoornav;

import android.util.Log;
import android.util.Pair;

import com.example.qr_indoornav.model.Edge;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.Node;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class PathFinder {

    private static final String TAG = "Pathfinder";

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
     * The main public method to find the optimal path from an origin to a destination.
     * It intelligently handles cases where the origin or destination is a room.
     */
    public static PathResult findPath(Graph graph, String originId, String destinationId) {
        // --- 1. Determine Start and End Junctions ---

        // The start junction is where the user scanned the QR code.
        String startJunctionId = findJunctionForLocation(graph, originId);
        if (startJunctionId == null) {
            Log.e(TAG, "Could not determine a starting junction for originId: " + originId);
            return new PathResult(Collections.emptyList(), 0);
        }

        // If the destination is a junction, the logic is simple.
        if (graph.getNode(destinationId) != null) {
            List<String> path = graph.findShortestPath(startJunctionId, destinationId);
            int distance = calculateDistance(graph, path, originId, destinationId);
            return new PathResult(path, distance);
        }

        // --- 2. Handle Room Destination ---
        // If the destination is a room, we must find the two junctions it lies between.
        Pair<String, String> endJunctions = findEndpointJunctionsForRoom(graph, destinationId);
        if (endJunctions == null) {
            Log.e(TAG, "Could not find endpoint junctions for room: " + destinationId);
            return new PathResult(Collections.emptyList(), 0);
        }

        String endJunctionA = endJunctions.first;
        String endJunctionB = endJunctions.second;

        // --- 3. Find and Compare the Two Possible Paths ---

        // Path 1: From start to endJunctionA
        List<String> pathA = graph.findShortestPath(startJunctionId, endJunctionA);
        int distanceA = calculateDistance(graph, pathA, originId, destinationId);

        // Path 2: From start to endJunctionB
        List<String> pathB = graph.findShortestPath(startJunctionId, endJunctionB);
        int distanceB = calculateDistance(graph, pathB, originId, destinationId);

        Log.d(TAG, String.format(Locale.US, "Path to %s: %d m. Path to %s: %d m.", endJunctionA, distanceA, endJunctionB, distanceB));

        // Return the result with the shorter total distance.
        if (distanceA <= distanceB) {
            return new PathResult(pathA, distanceA);
        } else {
            return new PathResult(pathB, distanceB);
        }
    }

    /**
     * Finds the junction associated with a location ID. If the ID is a junction, it's returned.
     * If it's a room, it finds the *first* junction of the edge the room is on.
     */
    private static String findJunctionForLocation(Graph graph, String locationId) {
        if (graph.getNode(locationId) != null) {
            return locationId; // It's already a junction.
        }
        // It's a room, find which edge it's on.
        for (Node node : graph.getAllNodes()) {
            for (Edge edge : node.getEdges().values()) {
                if (edge.roomIds.contains(locationId)) {
                    return node.id; // Return the 'from' junction of the edge.
                }
            }
        }
        return null; // Should not happen with valid data.
    }

    /**
     * Finds the two junctions that an edge (containing a specific room) connects.
     * Returns a Pair of junction IDs (from, to).
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

    /**
     * Finds the specific edge starting from a given junction that contains a specific room.
     * This is a helper for distance calculation.
     */
    private static Edge findEdgeContainingRoom(Graph graph, String fromJunctionId, String roomId) {
        Node fromJunction = graph.getNode(fromJunctionId);
        if (fromJunction == null) return null;

        for (Edge edge : fromJunction.getEdges().values()) {
            if (edge.roomIds.contains(roomId)) {
                return edge;
            }
        }
        return null;
    }

    /**
     * Calculates the total walking distance of a path, accounting for partial distances if the
     * start or end points are rooms located along an edge. THIS IS THE CORRECTED VERSION.
     */
    private static int calculateDistance(Graph graph, List<String> path, String originId, String destinationId) {
        if (path.isEmpty()) return 0;

        boolean isOriginRoom = graph.getNode(originId) == null;
        boolean isDestinationRoom = graph.getNode(destinationId) == null;

        // --- CASE 1: Origin and Destination are rooms on the SAME single edge. ---
        // This is a special case that doesn't involve traversing any full junction paths.
        if (path.size() <= 2 && isOriginRoom && isDestinationRoom) {
            String firstJunction = path.get(0);
            Edge edge = findEdgeContainingRoom(graph, firstJunction, originId);

            if (edge != null && edge.roomIds.contains(destinationId)) {
                int originIndex = edge.roomIds.indexOf(originId);
                int destIndex = edge.roomIds.indexOf(destinationId);
                int totalRooms = edge.roomIds.size();

                float originRatio = (float) (originIndex + 1) / (totalRooms + 1);
                float destRatio = (float) (destIndex + 1) / (totalRooms + 1);

                return (int) Math.abs((destRatio - originRatio) * edge.distanceMeters);
            }
        }

        // --- CASE 2: General path calculation ---
        int totalDistance = 0;

        // STEP A: Calculate the full distance of the junction-to-junction path.
        for (int i = 0; i < path.size() - 1; i++) {
            Edge edge = graph.getNode(path.get(i)).getEdgeTo(path.get(i + 1));
            if (edge != null) {
                totalDistance += edge.distanceMeters;
            }
        }

        // STEP B: Adjust for a room origin.
        // If starting at a room, we didn't travel the first part of the first edge. So, subtract it.
        if (isOriginRoom) {
            String startJunctionId = path.get(0);
            Edge firstEdge = findEdgeContainingRoom(graph, startJunctionId, originId);
            if (firstEdge != null) {
                int originIndex = firstEdge.roomIds.indexOf(originId);
                float originRatio = (float) (originIndex + 1) / (firstEdge.roomIds.size() + 1);
                int skippedDistance = (int) (originRatio * firstEdge.distanceMeters);
                totalDistance -= skippedDistance;
            }
        }

        // STEP C: Adjust for a room destination.
        // If ending at a room, we need to travel an extra partial distance from the last junction. So, add it.
        if (isDestinationRoom) {
            String lastJunctionId = path.get(path.size() - 1);
            Edge finalEdge = findEdgeContainingRoom(graph, lastJunctionId, destinationId);
            if (finalEdge != null) {
                int destIndex = finalEdge.roomIds.indexOf(destinationId);
                float destRatio = (float) (destIndex + 1) / (finalEdge.roomIds.size() + 1);
                int extraDistance = (int) (destRatio * finalEdge.distanceMeters);
                totalDistance += extraDistance;
            }
        }

        return Math.max(0, totalDistance); // Ensure distance is not negative
    }
}