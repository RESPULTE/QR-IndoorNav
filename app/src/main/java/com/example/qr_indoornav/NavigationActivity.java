package com.example.qr_indoornav;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.example.qr_indoornav.model.Edge;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class NavigationActivity extends AppCompatActivity {

    private MapView mapView;
    private ArrayList<String> pathNodeIds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navigation);

        // Initialize UI components
        TextView distanceTextView = findViewById(R.id.distanceTextView);
        Button confirmButton = findViewById(R.id.confirmButton);
        mapView = findViewById(R.id.mapView);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // --- DYNAMIC PATH PLANNING ---
        Intent intent = getIntent();
        String originName = intent.getStringExtra("USER_ORIGIN_NAME");
        String destName = intent.getStringExtra("USER_DESTINATION_NAME");

        // Get the graph object (which loads from JSON)
        Graph graph = MapData.getGraph(this);

        // Get the start and end node IDs from the location names
        String startNodeId = MapData.getNodeIdByName(this, originName);
        String endNodeId = MapData.getNodeIdByName(this, destName);

        if (startNodeId == null || endNodeId == null) {
            Toast.makeText(this, "Error: Unknown location name.", Toast.LENGTH_LONG).show();
            return;
        }

        // Find the shortest path using the graph's algorithm
        List<String> path = graph.findShortestPath(startNodeId, endNodeId);
        pathNodeIds = new ArrayList<>(path);

        if (pathNodeIds.isEmpty()) {
            Toast.makeText(this, "Could not find a path to the destination.", Toast.LENGTH_LONG).show();
            distanceTextView.setText("Path not found");
            return;
        }

        // --- FIX: PASS ALL DATA TO THE MAPVIEW FOR DYNAMIC LAYOUT ---
        mapView.setData(graph, pathNodeIds, startNodeId, endNodeId);

        // Calculate total distance for display
        int totalDistance = 0;
        for (int i = 0; i < pathNodeIds.size() - 1; i++) {
            Edge edge = graph.getNode(pathNodeIds.get(i)).edges.get(pathNodeIds.get(i + 1));
            if (edge != null) totalDistance += edge.distanceMeters;
        }
        distanceTextView.setText(String.format(Locale.getDefault(), "%d meters (approx.)", totalDistance));

        // Set up the confirm button to start navigation
        confirmButton.setOnClickListener(v -> {
            Intent navIntent = new Intent(NavigationActivity.this, CompassActivity.class);
            navIntent.putStringArrayListExtra("PATH_NODE_IDS", pathNodeIds);
            startActivity(navIntent);
        });
    }
}