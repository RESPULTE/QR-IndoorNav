package com.example.qr_indoornav;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.qr_indoornav.model.MapData;
import com.example.qr_indoornav.model.Node;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity {

    // UI Components
    private AutoCompleteTextView autoCompleteTextView;
    private Button continueButton;
    private TextView currentLocationValue;
    private FloatingActionButton micButton;

    // State variable for the user's current location (dummy value).
    // This name MUST match one of the "locationName" entries in your map.json
    private String currentLocationName = "Lecture Room 1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI components by finding them in the layout
        initializeUI();

        // Populate the dropdown with locations from the map data AND add click listeners
        setupDropdown();

        // Set up the click listeners for the other buttons
        setupClickListeners();

        // Display the hardcoded current location
        loadCurrentLocation();
    }

    /**
     * Finds and assigns all the UI views from the layout file.
     */
    private void initializeUI() {
        autoCompleteTextView = findViewById(R.id.autoCompleteTextView);
        continueButton = findViewById(R.id.continueButton);
        currentLocationValue = findViewById(R.id.currentLocationValue);
        micButton = findViewById(R.id.micButton);
    }

    /**
     * Fetches location data from MapData (which reads from JSON),
     * creates an adapter, and sets it for the AutoCompleteTextView.
     * --- THIS METHOD CONTAINS THE FIX ---
     */
    private void setupDropdown() {
        // Get all locations (nodes) from the MapData class, passing the context
        List<Node> locations = MapData.getLocations(this);

        // Use a stream to extract the names, sort them alphabetically, and collect into a List
        List<String> locationNames = locations.stream()
                .map(node -> node.locationName)
                .sorted()
                .collect(Collectors.toList());

        // Create an ArrayAdapter to display the location names in the dropdown
        ArrayAdapter<String> adapterItems = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, locationNames);

        // Set the adapter on the AutoCompleteTextView
        autoCompleteTextView.setAdapter(adapterItems);

        // --- FIX: ADD THIS ONCLICKLISTENER ---
        // By default, an AutoCompleteTextView with inputType="none" doesn't know to open
        // its list when clicked. We must explicitly tell it to show the dropdown.
        autoCompleteTextView.setOnClickListener(v -> autoCompleteTextView.showDropDown());
    }

    /**
     * Sets up the OnClickListeners for the interactive elements on the screen.
     */
    private void setupClickListeners() {
        // Listener for the "Continue" button
        continueButton.setOnClickListener(v -> {
            String selectedDestinationName = autoCompleteTextView.getText().toString();

            // --- Validation Logic ---
            if (selectedDestinationName.isEmpty() || selectedDestinationName.equals(getString(R.string.destination_hint)) || MapData.getNodeIdByName(this, selectedDestinationName) == null) {
                Toast.makeText(MainActivity.this, "Please select a valid destination.", Toast.LENGTH_SHORT).show();
                return; // Stop further execution
            }

            if (selectedDestinationName.equals(currentLocationName)) {
                Toast.makeText(MainActivity.this, "Origin and destination cannot be the same.", Toast.LENGTH_SHORT).show();
                return; // Stop further execution
            }

            // --- Navigation Logic ---
            // If validation passes, create an intent to start the NavigationActivity
            Intent intent = new Intent(MainActivity.this, NavigationActivity.class);

            // Pass the origin and destination names to the next activity for path planning
            intent.putExtra("USER_ORIGIN_NAME", currentLocationName);
            intent.putExtra("USER_DESTINATION_NAME", selectedDestinationName);

            // Start the activity
            startActivity(intent);
        });

        // Placeholder listener for the microphone button
        micButton.setOnClickListener(v -> {
            Toast.makeText(MainActivity.this, "Voice-to-text will be implemented later.", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Displays the current location on the screen.
     * For now, it uses the hardcoded value from the 'currentLocationName' variable.
     */
    private void loadCurrentLocation() {
        currentLocationValue.setText(currentLocationName);
    }
}