package com.example.qr_indoornav.model;

import java.util.List;

public class Edge {
    public final String toNodeId;
    public final int distanceMeters;
    public final float directionDegrees;
    public final List<String> roomIds; // NEW: To store the list of rooms on this path

    public Edge(String toNodeId, int distanceMeters, float directionDegrees, List<String> roomIds) {
        this.toNodeId = toNodeId;
        this.distanceMeters = distanceMeters;
        this.directionDegrees = directionDegrees;
        this.roomIds = roomIds;
    }
}