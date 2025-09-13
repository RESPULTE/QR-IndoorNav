package com.example.qr_indoornav.model;

public class Edge {
    public final String toNodeId;
    public final int distanceMeters; // Renamed from 'steps'
    public final float directionDegrees;

    public Edge(String toNodeId, int distanceMeters, float directionDegrees) {
        this.toNodeId = toNodeId;
        this.distanceMeters = distanceMeters;
        this.directionDegrees = directionDegrees;
    }
}