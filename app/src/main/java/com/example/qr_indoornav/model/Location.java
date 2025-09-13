package com.example.qr_indoornav.model;

public class Location {
    public final String name;
    public final String nearestJunctionId;

    public Location(String name, String nearestJunctionId) {
        this.name = name;
        this.nearestJunctionId = nearestJunctionId;
    }
}