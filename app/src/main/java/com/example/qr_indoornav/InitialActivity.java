package com.example.qr_indoornav;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.Toast;

public class InitialActivity extends AppCompatActivity {

    // Define a key for passing the scanned data to MainActivity.
    // Using a constant is good practice to avoid typos.
    public static final String EXTRA_SCANNED_ORIGIN_DATA = "SCANNED_ORIGIN_QR_DATA";

    // Modern way to handle activity results. This replaces the old onActivityResult.
    private final ActivityResultLauncher<Intent> qrScannerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // This block is executed when QRScannerActivity finishes and returns a result.
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.hasExtra("DECODED_TEXT")) {
                        // We received a successful scan. Get the raw QR text.
                        String qrData = data.getStringExtra("DECODED_TEXT");

                        // Now, start MainActivity and pass this initial location data to it.
                        Intent mainActivityIntent = new Intent(InitialActivity.this, MainActivity.class);
                        mainActivityIntent.putExtra(EXTRA_SCANNED_ORIGIN_DATA, qrData);
                        startActivity(mainActivityIntent);

                        // Finish StartActivity so the user cannot navigate back to it.
                        finish();

                    } else {
                        // This might happen if the scanner activity is closed unexpectedly.
                        Toast.makeText(this, "Failed to get QR data.", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    // This block is executed if the user presses the back button from the scanner.
                    Toast.makeText(this, "Scan cancelled.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start);

        ImageButton playButton = findViewById(R.id.playButton);

        playButton.setOnClickListener(v -> {
            // When the play button is clicked, launch the QRScannerActivity.
            // The result will be handled by the qrScannerLauncher defined above.
            Intent intent = new Intent(InitialActivity.this, QRScannerActivity.class);
            qrScannerLauncher.launch(intent);
        });
    }
}