package com.example.qr_indoornav;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.example.qr_indoornav.model.Location;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private AutoCompleteTextView autoCompleteTextView;
    // REPLACED navigationOriginId with the more accurate scannedLocationId
    private String scannedLocationId;  // The actual ID of the scanned QR (can be a room or junction)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeUI();
        handleScannedOrigin();
    }

    private void initializeUI() {
        autoCompleteTextView = findViewById(R.id.autoCompleteTextView);
    }

    private void handleScannedOrigin() {
        Intent intent = getIntent();
        if (intent == null || !intent.hasExtra(InitialActivity.EXTRA_SCANNED_ORIGIN_DATA)) {
            Log.e(TAG, "MainActivity started without required origin QR data.");
            Toast.makeText(this, "Error: No starting location found.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String qrData = intent.getStringExtra(InitialActivity.EXTRA_SCANNED_ORIGIN_DATA);

        try {
            // Step 1: Load the entire map from the full QR string.
            MapData.loadMapFromQRString(qrData);

            // Step 2: Get the starting location ID directly from the now-loaded MapData.
            this.scannedLocationId = MapData.getScannedLocationId();

            if (this.scannedLocationId == null || this.scannedLocationId.isEmpty()) {
                throw new IllegalStateException("MapData failed to extract a scanned location ID.");
            }

            // Step 3: Get the Location object for UI display purposes.
            Location scannedLocation = MapData.getLocationById(this.scannedLocationId);
            if (scannedLocation == null) {
                throw new IllegalStateException("Scanned ID " + this.scannedLocationId + " not found in map data.");
            }

            // Step 4: Log the actual scanned location. The PathFinder will be responsible for
            // handling the path calculation whether it's a room or a junction.
            Log.i(TAG, "User's starting location confirmed as: " + scannedLocation.displayName +
                    " (ID: " + this.scannedLocationId + ")");

            // Step 5: Now that all data is ready, set up the UI.
            setCurrentLocationOnUI();
            setupDropdown();
            setupClickListeners();

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MapData from QR string: " + qrData, e);
            Toast.makeText(this, "Error: Could not process map data from QR code.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void setCurrentLocationOnUI() {
        Location currentLocation = MapData.getLocationById(scannedLocationId);
        TextView currentLocationValue = findViewById(R.id.currentLocationValue);

        if (currentLocation != null) {
            currentLocationValue.setText(currentLocation.displayName);
        } else {
            currentLocationValue.setText("Unknown Location");
            Log.e(TAG, "Could not find display name for scanned location ID: " + scannedLocationId);
        }
    }

    private void setupDropdown() {
        List<Location> allLocations = MapData.getAllLocations();

        if (allLocations.isEmpty()) {
            Log.e(TAG, "The list of locations is empty! Check MapData parsing logic.");
            Toast.makeText(this, "Error: Could not load map locations.", Toast.LENGTH_LONG).show();
            return;
        }

        ArrayAdapter<Location> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, allLocations);
        autoCompleteTextView.setAdapter(adapter);
        autoCompleteTextView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) autoCompleteTextView.showDropDown();
        });
        autoCompleteTextView.setOnClickListener(v -> autoCompleteTextView.showDropDown());
    }

    private void setupClickListeners() {
        Button continueButton = findViewById(R.id.continueButton);
        continueButton.setOnClickListener(v -> {
            String selectedText = autoCompleteTextView.getText().toString();
            Location selectedLocation = MapData.getAllLocations().stream()
                    .filter(loc -> loc.displayName.equals(selectedText))
                    .findFirst().orElse(null);

            if (selectedLocation == null) {
                Toast.makeText(this, "Please select a valid destination from the list.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (selectedLocation.id.equals(scannedLocationId)) {
                Toast.makeText(this, "You are already at your destination.", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(MainActivity.this, NavigationActivity.class);
            // Pass the TRUE origin ID (which can be a room or a junction).
            // The PathFinder will correctly interpret this ID to start navigation.
            intent.putExtra("USER_ORIGIN_ID", scannedLocationId);
            intent.putExtra("USER_DESTINATION_ID", selectedLocation.id);
            startActivity(intent);
        });

        FloatingActionButton micButton = findViewById(R.id.micButton);
        micButton.setOnClickListener(v -> Toast.makeText(MainActivity.this, "Voice input not implemented.", Toast.LENGTH_SHORT).show());
    }
}