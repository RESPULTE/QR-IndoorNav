package com.example.qr_indoornav;

import android.util.Log;
import android.util.Pair;
import com.example.qr_indoornav.model.Edge;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.Node;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PathFinder {

    private static final String TAG = "PathFinder";

    /**
     * Represents a single leg of the journey with pre-calculated data.
     * Implements Serializable to be easily passed via Intents.
     */
    public static class PathLeg implements Serializable {
        public final String fromId;
        public final String toId;
        public final float direction;
        public final int distance;

        public PathLeg(String fromId, String toId, float direction, int distance) {
            this.fromId = fromId;
            this.toId = toId;
            this.direction = direction;
            this.distance = distance;
        }
    }

    /**
     * Represents the complete result, containing a list of navigation legs and the total distance.
     */
    public static class PathResult {
        public final List<PathLeg> legs;
        public final int totalDistance;

        public PathResult(List<PathLeg> legs, int totalDistance) {
            this.legs = legs;
            this.totalDistance = totalDistance;
        }

        public boolean isFound() {
            return legs != null && !legs.isEmpty();
        }
    }

    /**
     * Main public method to find the optimal path.
     * Returns a detailed list of PathLegs, each with pre-calculated distance and direction.
     */
    public static PathResult findPath(Graph graph, String originId, String destinationId) {
        // --- Step 1: Find the optimal path as a sequence of IDs ---
        List<String> optimalNodePath = findOptimalNodeSequence(graph, originId, destinationId);

        if (optimalNodePath.isEmpty()) {
            return new PathResult(Collections.emptyList(), 0);
        }

        // --- Step 2: Convert the sequence of IDs into a list of detailed PathLegs ---
        List<PathLeg> pathLegs = new ArrayList<>();
        int totalDistance = 0;

        for (int i = 0; i < optimalNodePath.size() - 1; i++) {
            String fromId = optimalNodePath.get(i);
            String toId = optimalNodePath.get(i + 1);
            PathLeg leg = createNavigationLeg(graph, fromId, toId);
            if (leg != null) {
                pathLegs.add(leg);
                totalDistance += leg.distance;
            } else {
                // If any leg fails to be created, the path is invalid.
                Log.e(TAG, "Failed to create navigation leg from " + fromId + " to " + toId);
                return new PathResult(Collections.emptyList(), 0);
            }
        }

        return new PathResult(pathLegs, totalDistance);
    }

    /**
     * Determines the best sequence of location IDs (rooms and junctions) for the path.
     * This is an internal helper that finds the node list before details are calculated.
     */
    private static List<String> findOptimalNodeSequence(Graph graph, String originId, String destinationId) {
        boolean isOriginRoom = graph.getNode(originId) == null;
        boolean isDestinationRoom = graph.getNode(destinationId) == null;

        // Special case: Origin and destination are rooms on the same edge
        if (isOriginRoom && isDestinationRoom) {
            Pair<String, String> originJunctions = findEndpointJunctionsForRoom(graph, originId);
            Pair<String, String> destJunctions = findEndpointJunctionsForRoom(graph, destinationId);

            if (originJunctions != null && Objects.equals(originJunctions, destJunctions)) {
                List<String> path = new ArrayList<>();
                path.add(originId);
                path.add(destinationId);
                return path;
            }
        }

        // General case: Find best path via anchor points
        Map<String, Integer> originAnchors = getAnchorPoints(graph, originId);
        Map<String, Integer> destAnchors = getAnchorPoints(graph, destinationId);

        int minTotalDistance = Integer.MAX_VALUE;
        List<String> bestJunctionPath = null;

        for (Map.Entry<String, Integer> originAnchor : originAnchors.entrySet()) {
            for (Map.Entry<String, Integer> destAnchor : destAnchors.entrySet()) {
                List<String> junctionPath = graph.findShortestPath(originAnchor.getKey(), destAnchor.getKey());
                if (junctionPath.isEmpty() && !originAnchor.getKey().equals(destAnchor.getKey())) continue;

                int junctionPathDistance = calculateJunctionPathDistance(graph, junctionPath);
                int totalDistance = originAnchor.getValue() + junctionPathDistance + destAnchor.getValue();

                if (totalDistance < minTotalDistance) {
                    minTotalDistance = totalDistance;
                    bestJunctionPath = junctionPath;
                }
            }
        }

        if (bestJunctionPath == null) return Collections.emptyList();

        // Construct the final path list, adding rooms if necessary
        List<String> finalPath = new ArrayList<>(bestJunctionPath);
        if (isOriginRoom) finalPath.add(0, originId);
        if (isDestinationRoom) finalPath.add(destinationId);

        return finalPath;
    }

    /**
     * Creates a single detailed PathLeg object with calculated direction and distance.
     * This is the new centralized logic for leg calculation.
     */
    private static PathLeg createNavigationLeg(Graph graph, String fromId, String toId) {
        boolean isFromRoom = graph.getNode(fromId) == null;
        boolean isToRoom = graph.getNode(toId) == null;
        int distance = calculatePartialDistance(graph, fromId, toId);

        // Case 1: Junction -> Junction
        if (!isFromRoom && !isToRoom) {
            Edge edge = graph.getNode(fromId).getEdgeTo(toId);
            if (edge != null) return new PathLeg(fromId, toId, edge.directionDegrees, distance);
        }

        // Case 2: Involving at least one room
        String roomId = isFromRoom ? fromId : toId;
        Pair<String, String> endpoints = findEndpointJunctionsForRoom(graph, roomId);
        if (endpoints != null) {
            Edge forwardEdge = graph.getNode(endpoints.first).getEdgeTo(endpoints.second);
            if (forwardEdge != null) {
                float direction;
                int fromIndex = isFromRoom ? forwardEdge.roomIds.indexOf(fromId) : (fromId.equals(endpoints.first) ? -1 : 999);
                int toIndex = isToRoom ? forwardEdge.roomIds.indexOf(toId) : (toId.equals(endpoints.first) ? -1 : 999);

                // If traveling from a lower index to a higher index (or from J1 to anything), use forward direction.
                if (fromIndex < toIndex) {
                    direction = forwardEdge.directionDegrees;
                } else { // Otherwise, use reverse direction.
                    direction = (forwardEdge.directionDegrees + 180) % 360;
                }
                return new PathLeg(fromId, toId, direction, distance);
            }
        }
        return null;
    }

    /**
     * Universal distance calculator between any two points (rooms or junctions) on the same edge.
     */
    private static int calculatePartialDistance(Graph graph, String locA, String locB) {
        boolean isARoom = graph.getNode(locA) == null;
        boolean isBRoom = graph.getNode(locB) == null;

        // If both are junctions, get direct edge distance
        if (!isARoom && !isBRoom) {
            Edge edge = graph.getNode(locA).getEdgeTo(locB);
            return (edge != null) ? edge.distanceMeters : 0;
        }

        String aRoomId = isARoom ? locA : (isBRoom ? locB : null);
        if (aRoomId == null) return 0;

        Pair<String, String> endpoints = findEndpointJunctionsForRoom(graph, aRoomId);
        if (endpoints == null) return 0;
        Edge edge = graph.getNode(endpoints.first).getEdgeTo(endpoints.second);
        if (edge == null) return 0;

        int totalRooms = edge.roomIds.size();
        float ratioA = isARoom ? (float)(edge.roomIds.indexOf(locA) + 1) / (totalRooms + 1)
                : (locA.equals(endpoints.first) ? 0.0f : 1.0f);
        float ratioB = isBRoom ? (float)(edge.roomIds.indexOf(locB) + 1) / (totalRooms + 1)
                : (locB.equals(endpoints.first) ? 0.0f : 1.0f);

        return (int) (Math.abs(ratioA - ratioB) * edge.distanceMeters);
    }

    // --- Unchanged Helper Methods from previous version ---
    private static Map<String, Integer> getAnchorPoints(Graph graph, String locationId) {
        Map<String, Integer> anchors = new HashMap<>();
        if (graph.getNode(locationId) != null) {
            anchors.put(locationId, 0);
            return anchors;
        }
        Pair<String, String> endpoints = findEndpointJunctionsForRoom(graph, locationId);
        if (endpoints != null) {
            int distToA = calculatePartialDistance(graph, locationId, endpoints.first);
            int distToB = calculatePartialDistance(graph, locationId, endpoints.second);
            anchors.put(endpoints.first, distToA);
            anchors.put(endpoints.second, distToB);
        }
        return anchors;
    }

    private static int calculateJunctionPathDistance(Graph graph, List<String> junctionPath) {
        int totalDistance = 0;
        if (junctionPath == null || junctionPath.size() < 2) return 0;
        for (int i = 0; i < junctionPath.size() - 1; i++) {
            totalDistance += calculatePartialDistance(graph, junctionPath.get(i), junctionPath.get(i+1));
        }
        return totalDistance;
    }

    private static Pair<String, String> findEndpointJunctionsForRoom(Graph graph, String roomId) {
        for (Node node : graph.getAllNodes()) {
            for (Edge edge : node.getEdges().values()) {
                if (edge.roomIds.contains(roomId)) {
                    return new Pair<>(node.id, edge.toNodeId);
                }
            }
        }
        return null;
    }
}