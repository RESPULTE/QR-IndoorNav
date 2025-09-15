package com.example.qr_indoornav;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.example.qr_indoornav.model.Edge;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.Location;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class NavigationActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navigation);

        // --- UI Initialization ---
        TextView distanceTextView = findViewById(R.id.distanceTextView);
        Button confirmButton = findViewById(R.id.confirmButton);
        MapView mapView = findViewById(R.id.mapView);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // --- Path Planning Logic ---
        Intent intent = getIntent();
        String originId = intent.getStringExtra("USER_ORIGIN_ID");
        String destinationId = intent.getStringExtra("USER_DESTINATION_ID");

        Location originLoc = MapData.getLocationById(this, originId);
        Location destLoc = MapData.getLocationById(this, destinationId);

        if (originLoc == null || destLoc == null) {
            Toast.makeText(this, "Error: Invalid location ID.", Toast.LENGTH_LONG).show();
            return;
        }

        String startJunctionId = originLoc.parentJunctionId;
        String endJunctionId = destLoc.parentJunctionId;
        boolean isRoomDestination = !destLoc.id.equals(endJunctionId);

        Graph graph = MapData.getGraph(this);
        List<String> pathNodeIds = graph.findShortestPath(startJunctionId, endJunctionId);

        // --- CRITICAL FIX: Ensure the path includes the edge where the room is located ---
        if (isRoomDestination) {
            // Case 1: Room is on an edge connected to the start junction.
            // The pathfinder returns just the start node. We must find the other end of the edge.
            if (pathNodeIds.size() == 1) {
                for (Edge edge : graph.getNode(startJunctionId).edges.values()) {
                    if (edge.roomIds.contains(destinationId)) {
                        pathNodeIds.add(edge.toNodeId); // Add the adjacent node to complete the path
                        break;
                    }
                }
            }
            // Case 2: The path leads to the room's parent junction, but the room is on a different edge.
            // We need to find the "next hop" from the end of the path to reach the room's edge.
            else if (pathNodeIds.size() > 1) {
                String lastJunctionInPath = pathNodeIds.get(pathNodeIds.size() - 1);
                // Check if the room is on the final edge of the current path.
                String secondLastJunction = pathNodeIds.get(pathNodeIds.size() - 2);
                Edge finalEdge = graph.getNode(secondLastJunction).edges.get(lastJunctionInPath);

                // If the room is NOT on the final edge, we need to find the correct next hop.
                if (finalEdge == null || !finalEdge.roomIds.contains(destinationId)) {
                    for (Edge edge : graph.getNode(lastJunctionInPath).edges.values()) {
                        if (edge.roomIds.contains(destinationId)) {
                            pathNodeIds.add(edge.toNodeId); // Add the true adjacent node
                            break;
                        }
                    }
                }
            }
        }


        if (pathNodeIds.isEmpty() || (pathNodeIds.size() == 1 && !isRoomDestination)) {
            Toast.makeText(this, "Could not find a valid path.", Toast.LENGTH_LONG).show();
            distanceTextView.setText("Path not found");
            return;
        }

        // --- Pass the corrected path and final destination ID to the MapView ---
        mapView.setData(graph, pathNodeIds, destinationId);

        // --- UI Text Update ---
        int totalDistance = 0;
        for (int i = 0; i < pathNodeIds.size() - 1; i++) {
            Edge edge = graph.getNode(pathNodeIds.get(i)).edges.get(pathNodeIds.get(i + 1));
            if (edge != null) totalDistance += edge.distanceMeters;
        }

        if (isRoomDestination) {
            distanceTextView.setText(String.format(Locale.getDefault(),
                    "Path to destination area: %d meters\nYour room is on the final path segment.",
                    totalDistance));
        } else {
            distanceTextView.setText(String.format(Locale.getDefault(), "%d meters (approx.)", totalDistance));
        }

        // --- Set up Confirm Button ---
        ArrayList<String> pathList = new ArrayList<>(pathNodeIds);
        confirmButton.setOnClickListener(v -> {
            Intent navIntent = new Intent(NavigationActivity.this, CompassActivity.class);
            navIntent.putStringArrayListExtra("PATH_NODE_IDS", pathList);
            startActivity(navIntent);
        });
    }
}