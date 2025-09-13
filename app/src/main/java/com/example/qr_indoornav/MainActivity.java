package com.example.qr_indoornav;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent; // <-- Import Intent
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class MainActivity extends AppCompatActivity {

    // (Keep the existing DESTINATIONS array and UI component declarations)
    private static final String[] DESTINATIONS = new String[]{
            "N001 - Lecture Hall", "N002 - Library", "N003 - Cafeteria",
            "N004 - Admin Office", "E101 - Engineering Lab", "E102 - Computer Lab",
            "S201 - Science Wing", "S202 - Chemistry Lab"
    };

    AutoCompleteTextView autoCompleteTextView;
    ArrayAdapter<String> adapterItems;
    FloatingActionButton micButton;
    Button continueButton;
    TextView currentLocationValue;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // (Keep the existing code to initialize UI components, setupDropdown, and loadCurrentLocation)
        autoCompleteTextView = findViewById(R.id.autoCompleteTextView);
        micButton = findViewById(R.id.micButton);
        continueButton = findViewById(R.id.continueButton);
        currentLocationValue = findViewById(R.id.currentLocationValue);

        setupDropdown();
        setupClickListeners(); // This method will be modified
        loadCurrentLocation();
    }

    private void setupDropdown() {
        adapterItems = new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, DESTINATIONS);
        autoCompleteTextView.setAdapter(adapterItems);
    }

    private void setupClickListeners() {
        micButton.setOnClickListener(v ->
                Toast.makeText(MainActivity.this, "Voice-to-text will be implemented later.", Toast.LENGTH_SHORT).show()
        );

        // --- MODIFICATION START ---
        // Modify the Continue button's click listener
        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String selectedDestination = autoCompleteTextView.getText().toString();

                if (selectedDestination.isEmpty() || selectedDestination.equals(getString(R.string.destination_hint))) {
                    Toast.makeText(MainActivity.this, "Please select a destination.", Toast.LENGTH_SHORT).show();
                } else {
                    // Launch the NavigationActivity
                    Intent intent = new Intent(MainActivity.this, NavigationActivity.class);
                    // You can pass data to the next activity like this:
                    intent.putExtra("USER_DESTINATION", selectedDestination);
                    intent.putExtra("USER_ORIGIN", currentLocationValue.getText().toString());
                    startActivity(intent);
                }
            }
        });
        // --- MODIFICATION END ---
    }

    private void loadCurrentLocation() {
        currentLocationValue.setText("Entrance Hall (N4)"); // Updated to match the map
    }
}