package com.example.qr_indoornav;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.MotionEvent;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.qr_indoornav.model.Location;
import com.example.qr_indoornav.model.MapData;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UI Components
    private AutoCompleteTextView autoCompleteTextView;
    private TextView recognizedTextView;
    private FloatingActionButton micButton;

    // Navigation State
    private String scannedLocationId;

    // Voice and Speech Components
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private TextToVoiceHelper textToVoiceHelper;
    private boolean isTtsInitialized = false;
    private ActivityResultLauncher<String> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializePermissionLauncher();
        initializeUI();
        initializeVoiceAndSpeech();
        handleScannedOrigin();
        setupClickListeners();
    }

    private void initializePermissionLauncher() {
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                Toast.makeText(this, "Microphone permission granted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Microphone permission denied. Voice search is disabled.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void initializeUI() {
        autoCompleteTextView = findViewById(R.id.autoCompleteTextView);
        recognizedTextView = findViewById(R.id.recognizedTextView);
        micButton = findViewById(R.id.micButton);
        recognizedTextView.setText("Press and hold the mic to speak");
    }

    private void initializeVoiceAndSpeech() {
        // Text-To-Speech Setup
        textToVoiceHelper = new TextToVoiceHelper(this, success -> {
            isTtsInitialized = success;
            Log.i(TAG, "TTS Initialization successful: " + success);
        });

        // Speech-To-Text Setup (Direct Recognizer)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);

        speechRecognizer.setRecognitionListener(createRecognitionListener());
    }

    private RecognitionListener createRecognitionListener() {
        return new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                recognizedTextView.setText("Listening...");
            }

            @Override
            public void onBeginningOfSpeech() {
                // UI can indicate that speech has started
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    recognizedTextView.setText(matches.get(0));
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String bestResult = matches.get(0);
                    recognizedTextView.setText("You said: \"" + bestResult + "\"");
                    processVoiceInput(matches);
                } else {
                    recognizedTextView.setText("Didn't catch that. Try again.");
                }
            }

            @Override
            public void onEndOfSpeech() {
                recognizedTextView.setText("Processing...");
            }

            @Override
            public void onError(int error) {
                String errorMessage = getErrorText(error);
                Log.e(TAG, "Speech Recognizer Error: " + errorMessage);
                recognizedTextView.setText("Error: " + errorMessage);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!speechRecognizer.toString().isEmpty()) { // Avoid clearing if a new session started
                        recognizedTextView.setText("Press and hold the mic to speak");
                    }
                }, 3000);

                speak("what the fuck u say.");
            }

            @Override
            public void onRmsChanged(float rmsdB) {}
            @Override
            public void onBufferReceived(byte[] buffer) {}
            @Override
            public void onEvent(int eventType, Bundle params) {}
        };
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
            setCurrentLocationOnUI();
            setupDropdown();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize MapData from QR string.", e);
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
        }
    }

    private void setupDropdown() {
        List<Location> allLocations = MapData.getAllLocations();
        if (allLocations.isEmpty()) {
            Toast.makeText(this, "Error: Could not load map locations.", Toast.LENGTH_LONG).show();
            return;
        }
        ArrayAdapter<Location> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, allLocations);
        autoCompleteTextView.setAdapter(adapter);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupClickListeners() {
        Button continueButton = findViewById(R.id.continueButton);
        continueButton.setOnClickListener(v -> navigateToNextScreen());

        // Microphone Button Listener
        Animation scaleUp = AnimationUtils.loadAnimation(this, R.anim.scale_up);
        Animation scaleDown = AnimationUtils.loadAnimation(this, R.anim.scale_down);

        micButton.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                        return false;
                    }
                    view.startAnimation(scaleUp);
                    micButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.mic_button_listening)));
                    speechRecognizer.startListening(speechRecognizerIntent);
                    return true;

                case MotionEvent.ACTION_UP:
                    view.startAnimation(scaleDown);
                    micButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.mic_button_default)));
                    speechRecognizer.stopListening();
                    return true;
            }
            return false;
        });
    }

    private void navigateToNextScreen() {
        String selectedText = autoCompleteTextView.getText().toString();
        Location selectedLocation = MapData.getAllLocations().stream()
                .filter(loc -> loc.displayName.equals(selectedText))
                .findFirst().orElse(null);

        if (selectedLocation == null) {
            Toast.makeText(this, "Please select a valid destination.", Toast.LENGTH_SHORT).show();
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
    }

    private void processVoiceInput(ArrayList<String> voiceResults) {
        String bestVoiceResult = voiceResults.stream()
                .filter(r -> r != null && !r.trim().isEmpty())
                .findFirst()
                .orElse("")
                .trim()
                .toLowerCase();

        bestVoiceResult = convertWordsToNumbers(bestVoiceResult);

        List<Location> potentialMatches = new ArrayList<>();
        List<Location> allLocations = MapData.getAllLocations();

        // Strategy 1: Exact match
        for (Location loc : allLocations) {
            if (loc.displayName.equalsIgnoreCase(bestVoiceResult)) {
                potentialMatches.add(loc);
                break;
            }
        }
        // Strategy 2: Partial match if no exact match found
        if (potentialMatches.isEmpty()) {
            for (Location loc : allLocations) {
                if (loc.displayName.toLowerCase().contains(bestVoiceResult)) {
                    potentialMatches.add(loc);
                }
            }
        }

        // Handle the results
        if (potentialMatches.size() == 1) {
            Location matchedLocation = potentialMatches.get(0);
            autoCompleteTextView.setText(matchedLocation.displayName, false); // Update text without filtering
            autoCompleteTextView.clearFocus();
            speak("Found location: " + matchedLocation.displayName);
        }
    }

    private String convertWordsToNumbers(String input) {
        Map<String, String> numberMap = new HashMap<>();
        numberMap.put("zero", "0"); numberMap.put("one", "1"); numberMap.put("two", "2");
        numberMap.put("three", "3"); numberMap.put("four", "4"); numberMap.put("five", "5");
        numberMap.put("six", "6"); numberMap.put("seven", "7"); numberMap.put("eight", "8");
        numberMap.put("nine", "9"); numberMap.put("ten", "10");

        for (Map.Entry<String, String> entry : numberMap.entrySet()) {
            input = input.replaceAll("\\b" + entry.getKey() + "\\b", entry.getValue());
        }
        return input;
    }

    private void speak(String text) {
        if (isTtsInitialized) {
            textToVoiceHelper.speak(text);
        }
    }

    public static String getErrorText(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO: return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT: return "Client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK: return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "No match found";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Recognition service busy";
            case SpeechRecognizer.ERROR_SERVER: return "Error from server";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech input";
            default: return "An unknown error occurred";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (textToVoiceHelper != null) {
            textToVoiceHelper.shutdown();
        }
    }
}