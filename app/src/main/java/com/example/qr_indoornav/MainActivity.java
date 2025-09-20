package com.example.qr_indoornav;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.qr_indoornav.model.Location;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private AutoCompleteTextView autoCompleteTextView;
    private String scannedLocationId;

    // --- Voice and Speech helpers ---
    private VoiceToTextHelper voiceToTextHelper;
    private TextToVoiceHelper textToVoiceHelper;
    private ActivityResultLauncher<Intent> speechResultLauncher;
    private boolean isTtsInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- Initialize helpers ---
        initializeVoiceAndSpeech();

        initializeUI();
        handleScannedOrigin();
    }

    private void initializeVoiceAndSpeech() {
        voiceToTextHelper = new VoiceToTextHelper(this);
        textToVoiceHelper = new TextToVoiceHelper(this, success -> {
            if (success) {
                Log.i(TAG, "TextToSpeech initialized successfully.");
                isTtsInitialized = true;
            } else {
                Log.e(TAG, "TextToSpeech failed to initialize.");
                isTtsInitialized = false;
            }
        });

        speechResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> results = result.getData().getStringArrayListExtra("android.speech.extra.RESULTS");
                        if (results != null && !results.isEmpty()) {
                            processVoiceInput(results);
                        }
                    }
                }
        );
    }

    // ===================================================================================
    // --- MODIFIED METHOD WITH SMARTER SEARCH LOGIC ---
    // ===================================================================================
    private void processVoiceInput(ArrayList<String> voiceResults) {
        List<Location> allLocations = MapData.getAllLocations();
        List<Location> potentialMatches = new ArrayList<>();

        // Pick best result (first valid one)
        String bestVoiceResult = voiceResults.stream()
                .filter(r -> r != null && !r.trim().isEmpty())
                .findFirst()
                .orElse("")
                .trim()
                .toLowerCase();

        // Convert number words into digits
        bestVoiceResult = convertWordsToNumbers(bestVoiceResult);

        // Strategy 1: Exact match
        for (Location location : allLocations) {
            if (location.displayName.equalsIgnoreCase(bestVoiceResult)) {
                potentialMatches.add(location);
                break;
            }
        }

        // Strategy 2: Partial match
        if (potentialMatches.isEmpty()) {
            for (Location location : allLocations) {
                if (location.displayName.toLowerCase().contains(bestVoiceResult)) {
                    potentialMatches.add(location);
                }
            }
        }

        // Handle results
        if (potentialMatches.size() == 1) {
            Location matchedLocation = potentialMatches.get(0);
            autoCompleteTextView.setText(matchedLocation.displayName);
            speak("Found location: " + matchedLocation.displayName);
        } else if (potentialMatches.size() > 1) {
            Toast.makeText(this, "Multiple locations found. Please be more specific.", Toast.LENGTH_LONG).show();
            StringBuilder feedback = new StringBuilder("I found multiple locations. Did you mean ");
            for (int i = 0; i < potentialMatches.size(); i++) {
                feedback.append(potentialMatches.get(i).displayName);
                if (i < potentialMatches.size() - 2) {
                    feedback.append(", ");
                } else if (i == potentialMatches.size() - 2) {
                    feedback.append(", or ");
                }
            }
            feedback.append("?");
            speak(feedback.toString());
        } else {
            Toast.makeText(this, "Could not find a matching destination.", Toast.LENGTH_SHORT).show();
            speak("Sorry, I could not find that location. Please try again.");
        }
    }

    private String convertWordsToNumbers(String input) {
        Map<String, String> numberMap = new HashMap<>();
        numberMap.put("zero", "0");
        numberMap.put("one", "1");
        numberMap.put("two", "2");
        numberMap.put("three", "3");
        numberMap.put("four", "4");
        numberMap.put("five", "5");
        numberMap.put("six", "6");
        numberMap.put("seven", "7");
        numberMap.put("eight", "8");
        numberMap.put("nine", "9");
        numberMap.put("ten", "10");

        // Replace word numbers with digits
        for (Map.Entry<String, String> entry : numberMap.entrySet()) {
            input = input.replaceAll("\\b" + entry.getKey() + "\\b", entry.getValue());
        }
        return input;
    }

    /** Helper method to speak text only if TTS is ready */
    private void speak(String text) {
        if (isTtsInitialized) {
            textToVoiceHelper.speak(text);
        }
    }
    // ===================================================================================
    // --- END OF MODIFIED SECTION ---
    // ===================================================================================


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
            MapData.loadMapFromQRString(qrData);
            this.scannedLocationId = MapData.getScannedLocationId();

            if (this.scannedLocationId == null || this.scannedLocationId.isEmpty()) {
                throw new IllegalStateException("MapData failed to extract a scanned location ID.");
            }

            Location scannedLocation = MapData.getLocationById(this.scannedLocationId);
            if (scannedLocation == null) {
                throw new IllegalStateException("Scanned ID " + this.scannedLocationId + " not found in map data.");
            }

            Log.i(TAG, "User's starting location confirmed as: " + scannedLocation.displayName +
                    " (ID: " + this.scannedLocationId + ")");

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

    @SuppressLint("ClickableViewAccessibility")
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
            intent.putExtra("USER_ORIGIN_ID", scannedLocationId);
            intent.putExtra("USER_DESTINATION_ID", selectedLocation.id);
            startActivity(intent);
        });

        FloatingActionButton micButton = findViewById(R.id.micButton);
        Animation scaleUp = AnimationUtils.loadAnimation(this, R.anim.scale_up);
        Animation scaleDown = AnimationUtils.loadAnimation(this, R.anim.scale_down);

        micButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    micButton.startAnimation(scaleUp);
                    micButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.mic_button_listening)));
                    voiceToTextHelper.startListening(speechResultLauncher);
                    return true;

                case MotionEvent.ACTION_UP:
                    micButton.startAnimation(scaleDown);
                    micButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.mic_button_default)));
                    return true;
            }
            return false;
        });
    }

    @Override
    protected void onDestroy() {
        if (textToVoiceHelper != null) {
            textToVoiceHelper.shutdown();
        }
        super.onDestroy();
    }
}