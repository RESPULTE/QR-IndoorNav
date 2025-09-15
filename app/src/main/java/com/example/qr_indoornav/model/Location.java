package com.example.qr_indoornav.model;

public class Location {
    public final String id; // e.g., "N1" or "R101"
    public final String displayName; // e.g., "Junction N1" or "Room R101"
    public final String parentJunctionId; // The main junction this location is associated with

    public Location(String id, String displayName, String parentJunctionId) {
        this.id = id;
        this.displayName = displayName;
        this.parentJunctionId = parentJunctionId;
    }

    // Override for simple display in ArrayAdapter
    @Override
    public String toString() {
        return displayName;
    }
}