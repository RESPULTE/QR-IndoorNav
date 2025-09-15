package com.example.qr_indoornav.model;

import java.util.List;

public class Edge {
    public final String id;
    public final String toNodeId;
    public final int distanceMeters;
    public final float directionDegrees;
    public final List<String> roomIds; // NEW: To store the list of rooms on this path

    public Edge(String id, String toNodeId, int distanceMeters, float directionDegrees, List<String> roomIds) {
        this.id = id;
        this.toNodeId = toNodeId;
        this.distanceMeters = distanceMeters;
        this.directionDegrees = directionDegrees;
        this.roomIds = roomIds;
    }
}