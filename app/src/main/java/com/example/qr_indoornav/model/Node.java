package com.example.qr_indoornav.model;

import java.util.HashMap;
import java.util.Map;

public class Node {
    public final String id;
    public final String locationName; // Added location name
    public final Map<String, Edge> edges = new HashMap<>();

    public Node(String id, String locationName) {
        this.id = id;
        this.locationName = locationName;
    }

    public void addEdge(String toNodeId, int distanceMeters, float direction) {
        edges.put(toNodeId, new Edge(toNodeId, distanceMeters, direction));
    }
}