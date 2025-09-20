package com.example.qr_indoornav;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class NavigationActivity extends AppCompatActivity {

    private static final String TAG = "NavigationActivity";
    public static final String EXTRA_PATH_LEGS = "PATH_LEGS"; // Public constant for the key

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navigation);

        TextView distanceTextView = findViewById(R.id.distanceTextView);
        Button confirmButton = findViewById(R.id.confirmButton);
        MapView mapView = findViewById(R.id.mapView);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

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

        // Use the Pathfinder to get the detailed path legs.
        PathFinder.PathResult result = PathFinder.findPath(graph, originId, destinationId);

        if (!result.isFound()) {
            Toast.makeText(this, "Could not find a valid path.", Toast.LENGTH_LONG).show();
            distanceTextView.setText("Path not found");
            return;
        }

        // --- Display Results ---
        // For the MapView, we need to reconstruct the simple list of node IDs from the legs.
        List<String> pathNodeIds = new ArrayList<>();
        if (!result.legs.isEmpty()) {
            pathNodeIds.add(result.legs.get(0).fromId); // Add the very first node
            for (PathFinder.PathLeg leg : result.legs) {
                pathNodeIds.add(leg.toId); // Add the destination of each leg
            }
        }
        mapView.setData(graph, pathNodeIds, destinationId);
        distanceTextView.setText(String.format(Locale.getDefault(), "%d meters (approx.)", result.totalDistance));

        // --- Set up Confirm Button ---
        confirmButton.setOnClickListener(v -> {
            Intent navIntent = new Intent(NavigationActivity.this, CompassActivity.class);
            // Pass the detailed list of PathLeg objects. ArrayList is Serializable.
            navIntent.putExtra(EXTRA_PATH_LEGS, new ArrayList<>(result.legs));
            startActivity(navIntent);
        });
    }
}