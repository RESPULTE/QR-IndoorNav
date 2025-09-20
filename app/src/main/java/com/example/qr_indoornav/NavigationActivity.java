package com.example.qr_indoornav;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
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

        Location originLoc = MapData.getLocationById(originId);
        Location destLoc = MapData.getLocationById(destinationId);

        if (originLoc == null || destLoc == null) {
            return;
        }

        String startJunctionId = originLoc.parentJunctionId;
        String endJunctionId = destLoc.parentJunctionId;
        boolean isRoomDestination = !destLoc.id.equals(endJunctionId);

        Graph graph = MapData.getGraph();
        List<String> pathNodeIds;

        // --- REWRITTEN PATH PLANNING ---

        // First, check for the special case: Is the room on an edge directly connected to the start?
        Edge directEdge = null;
        if (isRoomDestination) {
            for (Edge edge : graph.getNode(startJunctionId).edges.values()) {
                if (edge.roomIds.contains(destinationId)) {
                    directEdge = edge;
                    break;
                }
            }
        }

        if (directEdge != null) {
            // CASE 1: The room is directly connected to the starting junction.
            // The path is simply from the start junction towards the next one, but the user will
            // stop at the room before reaching the next junction's QR code.
            pathNodeIds = new ArrayList<>();
            pathNodeIds.add(startJunctionId);
            pathNodeIds.add(directEdge.toNodeId); // Add the next node for directional purposes only.

        } else {
            // CASE 2: The room is further away. Calculate the full path of junctions.
            pathNodeIds = graph.findShortestPath(startJunctionId, endJunctionId);

            // If the destination is a room, we might need to add one final "hop" if the room
            // is on an edge *leaving* the calculated destination junction.
            if (isRoomDestination && !pathNodeIds.isEmpty()) {
                String lastJunctionInPath = pathNodeIds.get(pathNodeIds.size() - 1);
                boolean roomFoundOnPath = false;

                // Check if the room is on the final leg of the already calculated path.
                if (pathNodeIds.size() >= 2) {
                    String secondLastJunction = pathNodeIds.get(pathNodeIds.size() - 2);
                    Edge finalEdge = graph.getNode(secondLastJunction).edges.get(lastJunctionInPath);
                    if (finalEdge != null && finalEdge.roomIds.contains(destinationId)) {
                        roomFoundOnPath = true; // Path is correct as is.
                    }
                }

                // If not found on the path, it must be on an "offshoot" edge.
                if (!roomFoundOnPath) {
                    for (Edge edge : graph.getNode(lastJunctionInPath).edges.values()) {
                        if (edge.roomIds.contains(destinationId)) {
                            pathNodeIds.add(edge.toNodeId); // Add the final hop for direction.
                            break;
                        }
                    }
                }
            }
        }

        // --- END OF REWRITTEN PATH PLANNING ---

        if (pathNodeIds.isEmpty() || (pathNodeIds.size() == 1 && !isRoomDestination)) {
            Toast.makeText(this, "Could not find a valid path.", Toast.LENGTH_LONG).show();
            distanceTextView.setText("Path not found");
            return;
        }

        mapView.setData(graph, pathNodeIds, destinationId);

        // --- REFINED DISTANCE CALCULATION ---
        int totalDistance = 0;
        // Calculate the distance of all but the last path segment
        for (int i = 0; i < pathNodeIds.size() - 1; i++) {
            // Check to prevent index out of bounds on the next line if loop condition changes
            if (i + 1 >= pathNodeIds.size()) break;

            String fromNode = pathNodeIds.get(i);
            String toNode = pathNodeIds.get(i+1);
            Edge edge = graph.getNode(fromNode).edges.get(toNode);

            // Special handling for the very last edge in the list
            boolean isFinalEdgeOfJourney = (i == pathNodeIds.size() - 2);

            if (edge != null) {
                if (isRoomDestination && isFinalEdgeOfJourney) {
                    // Find the room's position on the edge and calculate partial distance
                    int roomIndex = edge.roomIds.indexOf(destinationId);
                    int totalRoomsOnEdge = edge.roomIds.size();
                    if (roomIndex != -1) {
                        // Calculate ratio (e.g., room 3 of 10 is at ~36% of the way)
                        float ratio = (float) (roomIndex + 1) / (totalRoomsOnEdge + 1);
                        totalDistance += (int) (edge.distanceMeters * ratio); // Add the partial distance
                    } else {
                        // Fallback: If room isn't found on the final edge (should not happen with new logic)
                        totalDistance += edge.distanceMeters;
                    }
                } else {
                    // For intermediate edges, or if the destination is a junction, add the full length
                    totalDistance += edge.distanceMeters;
                }
            }
        }

        distanceTextView.setText(String.format(Locale.getDefault(), "%d meters (approx.)", totalDistance));

        // --- Set up Confirm Button ---
        ArrayList<String> pathList = new ArrayList<>(pathNodeIds);
        confirmButton.setOnClickListener(v -> {
            Intent navIntent = new Intent(NavigationActivity.this, CompassActivity.class);
            navIntent.putStringArrayListExtra("PATH_NODE_IDS", pathList);
            navIntent.putExtra("FINAL_DESTINATION_ID", destinationId);
            startActivity(navIntent);
        });
    }
}