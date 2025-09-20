package com.example.qr_indoornav;

import android.content.Context;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import java.util.Locale;

public class VoiceToTextHelper {

    private final Context context;

    public VoiceToTextHelper(Context context) {
        this.context = context;
    }

    /**
     * Creates an Intent for speech recognition and launches it.
     * @param speechLauncher The ActivityResultLauncher to handle the result.
     */
    public void startListening(ActivityResultLauncher<Intent> speechLauncher) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Please say your destination");

        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000);


        try {
            speechLauncher.launch(intent);
        } catch (Exception e) {
            // This can happen if no speech recognition service is available on the device
            Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}