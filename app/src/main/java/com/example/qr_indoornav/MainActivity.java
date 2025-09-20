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
    private String currentJunctionId; // This will now be set from the scanned QR code

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeUI();
        handleScannedOrigin(); // This new method will set up the activity
    }

    private void initializeUI() {
        autoCompleteTextView = findViewById(R.id.autoCompleteTextView);
    }

    /**
     * New method to handle the initial location passed from StartActivity.
     * This is the core of the new logic.
     */
    private void handleScannedOrigin() {
        // Get the Intent that started this activity
        Intent intent = getIntent();

        // Check if the intent has the QR data we're expecting
        if (intent != null && intent.hasExtra(StartActivity.EXTRA_SCANNED_ORIGIN_DATA)) {
            String qrData = intent.getStringExtra(StartActivity.EXTRA_SCANNED_ORIGIN_DATA);

            // Use your existing QRParser to analyze the data
            QRParser.ScannedQRData parsedData = QRParser.parse(qrData);

            // We MUST start at a JUNCTION. If it's a room or invalid, we can't proceed.
            if (parsedData.type == QRParser.ScannedQRData.QRType.JUNCTION) {
                // SUCCESS! The scanned QR is a valid starting point.
                this.currentJunctionId = parsedData.id;

                // Now that we have a valid origin, set up the rest of the UI.
                setCurrentLocationOnUI();
                setupDropdown();
                setupClickListeners();

            } else {
                // ERROR: The user scanned an invalid QR code to start (e.g., a room).
                Log.e(TAG, "Invalid starting QR scanned. Type: " + parsedData.type + ", Info: " + parsedData.id);
                Toast.makeText(this, "Error: You must scan a valid junction QR code to start.", Toast.LENGTH_LONG).show();
                finish(); // Close this activity and return the user to the start screen.
            }
        } else {
            // ERROR: This activity was started without the necessary QR data.
            Log.e(TAG, "MainActivity started without required origin QR data.");
            Toast.makeText(this, "Error: No starting location found.", Toast.LENGTH_LONG).show();
            finish(); // Close the activity.
        }
    }

    /**
     * This method was previously named loadCurrentLocation.
     * It now just updates the UI based on the already-set currentJunctionId.
     */
    private void setCurrentLocationOnUI() {
        Location currentLocation = MapData.getLocationById(this, currentJunctionId);
        TextView currentLocationValue = findViewById(R.id.currentLocationValue);
        if (currentLocation != null) {
            currentLocationValue.setText(currentLocation.displayName);
        } else {
            currentLocationValue.setText("Unknown Location");
            Log.e(TAG, "Could not find display name for current junction ID: " + currentJunctionId);
        }
    }

    private void setupDropdown() {
        List<Location> allLocations = MapData.getAllLocations(this);

        if (allLocations.isEmpty()) {
            Log.e(TAG, "The list of locations is empty! Check MapData and map.json.");
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
            Location selectedLocation = MapData.getAllLocations(this).stream()
                    .filter(loc -> loc.displayName.equals(selectedText))
                    .findFirst().orElse(null);

            if (selectedLocation == null) {
                Toast.makeText(this, "Please select a valid destination from the list.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (selectedLocation.id.equals(currentJunctionId)) {
                Toast.makeText(this, "You are already at this junction.", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(MainActivity.this, NavigationActivity.class);
            intent.putExtra("USER_ORIGIN_ID", currentJunctionId);
            intent.putExtra("USER_DESTINATION_ID", selectedLocation.id);
            startActivity(intent);
        });

        FloatingActionButton micButton = findViewById(R.id.micButton);
        micButton.setOnClickListener(v -> Toast.makeText(MainActivity.this, "Voice input not implemented.", Toast.LENGTH_SHORT).show());
    }
}