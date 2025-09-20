package com.example.qr_indoornav.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Node {
    public final String id;
    // Made this private to encourage using the getter for better encapsulation
    public final Map<String, Edge> edges = new HashMap<>();

    public Node(String id) {
        this.id = id;
    }

    public void addEdge(String toNodeId, int distanceMeters, float direction, List<String> roomIds) {
        edges.put(toNodeId, new Edge(id + "-" + toNodeId, toNodeId, distanceMeters, direction, roomIds));
    }

    /**
     * Retrieves the edge that connects this node to a specific destination node.
     * @param toNodeId The ID of the destination node.
     * @return The Edge object, or null if no direct edge exists.
     */
    public Edge getEdgeTo(String toNodeId) {
        return edges.get(toNodeId);
    }

    /**
     * Returns the map of all edges originating from this node.
     * The keys are the destination node IDs.
     * @return A Map of all outgoing edges.
     */
    public Map<String, Edge> getEdges() {
        return edges;
    }
}