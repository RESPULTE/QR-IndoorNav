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
        setupDropdown();
        setupClickListeners();
        loadCurrentLocation();
    }

    private void initializeUI() {
        autoCompleteTextView = findViewById(R.id.autoCompleteTextView);
    }

    private void setupDropdown() {
        List<Location> allLocations = MapData.getAllLocations(this);

        // Add a check for better debugging and user feedback
        if (allLocations.isEmpty()) {
            Log.e(TAG, "The list of locations is empty! Check MapData and map.json.");
            Toast.makeText(this, "Error: Could not load map locations.", Toast.LENGTH_LONG).show();
            return;
        }

        // The adapter works with the Location object's toString() method
        ArrayAdapter<Location> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, allLocations);
        autoCompleteTextView.setAdapter(adapter);
        autoCompleteTextView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) autoCompleteTextView.showDropDown();
        });
        autoCompleteTextView.setOnClickListener(v -> autoCompleteTextView.showDropDown());
    }

    private void loadCurrentLocation() {
        currentJunctionId = MapData.getCurrentJunctionId(this);
        Location currentLocation = MapData.getLocationById(this, currentJunctionId);
        TextView currentLocationValue = findViewById(R.id.currentLocationValue);
        if (currentLocation != null) {
            currentLocationValue.setText(currentLocation.displayName);
        } else {
            currentLocationValue.setText("Unknown Location");
            Log.e(TAG, "Could not find display name for current junction ID: " + currentJunctionId);
        }
    }

    private void setupClickListeners() {
        Button continueButton = findViewById(R.id.continueButton);
        continueButton.setOnClickListener(v -> {
            String selectedText = autoCompleteTextView.getText().toString();
            // Find the selected Location object from the master list
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