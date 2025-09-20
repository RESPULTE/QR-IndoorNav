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
    private String currentJunctionId;

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
        if (intent != null && intent.hasExtra(StartActivity.EXTRA_SCANNED_ORIGIN_DATA)) {
            String qrData = intent.getStringExtra(StartActivity.EXTRA_SCANNED_ORIGIN_DATA);
            QRParser.ScannedQRData parsedData = QRParser.parse(qrData);

            if (parsedData.type == QRParser.ScannedQRData.QRType.JUNCTION) {
                try {
                    // --- MODIFIED SECTION START ---
                    // Load the entire map using the full QR string
                    MapData.loadMapFromQRString(qrData);

                    // Set the user's current location using the ID from the parsed data
                    MapData.setCurrentJunctionId(parsedData.id);
                    this.currentJunctionId = MapData.getCurrentJunctionId();
                    // --- MODIFIED SECTION END ---

                    // Now that MapData is populated, set up the UI
                    setCurrentLocationOnUI();
                    setupDropdown();
                    setupClickListeners();

                } catch (Exception e) {
                    Log.e(TAG, "Failed to initialize MapData from QR string.", e);
                    Toast.makeText(this, "Error: Could not process map data from QR code.", Toast.LENGTH_LONG).show();
                    finish();
                }
            } else {
                Log.e(TAG, "Invalid starting QR scanned. Type: " + parsedData.type + ", Info: " + parsedData.id);
                Toast.makeText(this, "Error: You must scan a valid junction QR code to start.", Toast.LENGTH_LONG).show();
                finish();
            }
        } else {
            Log.e(TAG, "MainActivity started without required origin QR data.");
            Toast.makeText(this, "Error: No starting location found.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void setCurrentLocationOnUI() {
        // Note: No 'context' parameter needed anymore
        Location currentLocation = MapData.getLocationById(currentJunctionId);
        TextView currentLocationValue = findViewById(R.id.currentLocationValue);
        if (currentLocation != null) {
            currentLocationValue.setText(currentLocation.displayName);
        } else {
            currentLocationValue.setText("Unknown Location");
            Log.e(TAG, "Could not find display name for current junction ID: " + currentJunctionId);
        }
    }

    private void setupDropdown() {
        // Note: No 'context' parameter needed anymore
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
            // Note: No 'context' parameter needed anymore
            Location selectedLocation = MapData.getAllLocations().stream()
                    .filter(loc -> loc.displayName.equals(selectedText))
                    .findFirst().orElse(null);

            if (selectedLocation == null) {
                Toast.makeText(this, "Please select a valid destination from the list.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (selectedLocation.id.equals(currentJunctionId)) {
                Toast.makeText(this, "You are already at your destination.", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(MainActivity.this, NavigationActivity.class);
            // The rest of your app will now use the globally loaded MapData
            intent.putExtra("USER_ORIGIN_ID", currentJunctionId);
            intent.putExtra("USER_DESTINATION_ID", selectedLocation.id);
            startActivity(intent);
        });

        FloatingActionButton micButton = findViewById(R.id.micButton);
        micButton.setOnClickListener(v -> Toast.makeText(MainActivity.this, "Voice input not implemented.", Toast.LENGTH_SHORT).show());
    }
}