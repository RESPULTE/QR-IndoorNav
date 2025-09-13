package com.example.qr_indoornav;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent; // <-- Import Intent
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;

public class NavigationActivity extends AppCompatActivity {

    TextView distanceTextView;
    Button confirmButton;
    MaterialToolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navigation);

        distanceTextView = findViewById(R.id.distanceTextView);
        confirmButton = findViewById(R.id.confirmButton);
        toolbar = findViewById(R.id.toolbar);

        toolbar.setNavigationOnClickListener(v -> finish());

        distanceTextView.setText("25 meters (approx.)");

        // --- MODIFICATION START ---
        confirmButton.setOnClickListener(v -> {
            // Launch the CompassActivity
            Intent intent = new Intent(NavigationActivity.this, CompassActivity.class);
            // In the future, you'll pass the actual target direction here
            // For now, we'll let CompassActivity use a hardcoded value
            startActivity(intent);
        });
        // --- MODIFICATION END ---
    }
}