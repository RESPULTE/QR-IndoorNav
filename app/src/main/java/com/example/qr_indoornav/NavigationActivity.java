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

        // Handle rooms connected directly or requiring a "next hop"
        if (isRoomDestination) {
            if (pathNodeIds.size() == 1) {
                for (Edge edge : graph.getNode(startJunctionId).edges.values()) {
                    if (edge.roomIds.contains(destinationId)) {
                        pathNodeIds.add(edge.toNodeId);
                        break;
                    }
                }
            } else if (pathNodeIds.size() > 1) {
                String lastJunctionInPath = pathNodeIds.get(pathNodeIds.size() - 1);
                String secondLastJunction = pathNodeIds.get(pathNodeIds.size() - 2);
                Edge finalEdge = graph.getNode(secondLastJunction).edges.get(lastJunctionInPath);
                if (finalEdge == null || !finalEdge.roomIds.contains(destinationId)) {
                    for (Edge edge : graph.getNode(lastJunctionInPath).edges.values()) {
                        if (edge.roomIds.contains(destinationId)) {
                            pathNodeIds.add(edge.toNodeId);
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

        mapView.setData(graph, pathNodeIds, destinationId);

        // --- REFINED DISTANCE CALCULATION ---
        int totalDistance = 0;
        // Calculate the distance of all the full path segments
        for (int i = 0; i < pathNodeIds.size() - 2; i++) {
            Edge edge = graph.getNode(pathNodeIds.get(i)).edges.get(pathNodeIds.get(i + 1));
            if (edge != null) totalDistance += edge.distanceMeters;
        }

        // Now, handle the final segment specifically
        if (pathNodeIds.size() >= 2) {
            String finalEdgeStartId = pathNodeIds.get(pathNodeIds.size() - 2);
            String finalEdgeEndId = pathNodeIds.get(pathNodeIds.size() - 1);
            Edge finalEdge = graph.getNode(finalEdgeStartId).edges.get(finalEdgeEndId);

            if (finalEdge != null) {
                if (isRoomDestination) {
                    // Find the room's position on the edge and calculate partial distance
                    int roomIndex = finalEdge.roomIds.indexOf(destinationId);
                    int totalRoomsOnEdge = finalEdge.roomIds.size();
                    if (roomIndex != -1) {
                        // Calculate ratio (e.g., room 3 of 10 is at ~36% of the way)
                        float ratio = (float) (roomIndex + 1) / (totalRoomsOnEdge + 1);
                        totalDistance += (int) (finalEdge.distanceMeters * ratio); // Add the partial distance
                    } else {
                        // Fallback in case room isn't found (shouldn't happen with prior logic)
                        totalDistance += finalEdge.distanceMeters;
                    }
                } else {
                    // If the destination is a junction, add the full length of the final edge
                    totalDistance += finalEdge.distanceMeters;
                }
            }
        }

        distanceTextView.setText(String.format(Locale.getDefault(), "%d meters (approx.)", totalDistance));

        // --- Set up Confirm Button ---
        ArrayList<String> pathList = new ArrayList<>(pathNodeIds);
        confirmButton.setOnClickListener(v -> {
            Intent navIntent = new Intent(NavigationActivity.this, CompassActivity.class);
            navIntent.putStringArrayListExtra("PATH_NODE_IDS", pathList);
            // We can also pass the final calculated distance to the next activity if needed
            // navIntent.putExtra("TOTAL_DISTANCE", totalDistance);
            startActivity(navIntent);
        });
    }
}