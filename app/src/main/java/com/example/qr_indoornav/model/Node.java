package com.example.qr_indoornav.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Node {
    public final String id;
    public final Map<String, Edge> edges = new HashMap<>();

    public Node(String id) {
        this.id = id;
    }

    public void addEdge(String toNodeId, int distanceMeters, float direction, List<String> roomIds) {
        edges.put(toNodeId, new Edge(id+toNodeId, toNodeId, distanceMeters, direction, roomIds));
    }
}