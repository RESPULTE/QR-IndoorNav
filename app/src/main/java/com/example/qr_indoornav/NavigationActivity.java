package com.example.qr_indoornav;// In NavigationActivity.java

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
// No need to import Edge or Location here anymore
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.ArrayList;
import java.util.Locale;

public class NavigationActivity extends AppCompatActivity {

    private static final String TAG = "NavigationActivity";

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

        // --- Get Origin and Destination ---
        Intent intent = getIntent();
        String originId = intent.getStringExtra("USER_ORIGIN_ID");
        String destinationId = intent.getStringExtra("USER_DESTINATION_ID");

        if (originId == null || destinationId == null) {
            Toast.makeText(this, "Invalid origin or destination.", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Origin or Destination ID is null.");
            finish();
            return;
        }

        Graph graph = MapData.getGraph();

        // --- Use the Pathfinder to get the optimal path and distance ---
        PathFinder.PathResult result = PathFinder.findPath(graph, originId, destinationId);

        if (!result.isFound()) {
            Toast.makeText(this, "Could not find a valid path.", Toast.LENGTH_LONG).show();
            distanceTextView.setText("Path not found");
            return;
        }

        // --- Display Results ---
        mapView.setData(graph, result.nodeIds, destinationId);
        distanceTextView.setText(String.format(Locale.getDefault(), "%d meters (approx.)", result.totalDistance));

        // --- Set up Confirm Button ---
        confirmButton.setOnClickListener(v -> {
            Intent navIntent = new Intent(NavigationActivity.this, CompassActivity.class);
            // Pass the list of JUNCTION IDs for navigation steps
            navIntent.putStringArrayListExtra("PATH_NODE_IDS", new ArrayList<>(result.nodeIds));
            // Pass the TRUE final destination ID (which could be a room)
            navIntent.putExtra("FINAL_DESTINATION_ID", destinationId);
            startActivity(navIntent);
        });
    }
}