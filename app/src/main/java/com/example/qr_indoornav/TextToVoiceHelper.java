package com.example.qr_indoornav;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import java.util.Locale;

public class TextToVoiceHelper {
    private static final String TAG = "TextToVoiceHelper";
    private TextToSpeech textToSpeech;
    private boolean isInitialized = false;

    /**
     * Constructor that initializes the TextToSpeech engine.
     * @param context The application context.
     * @param listener A callback to be invoked when initialization is complete.
     */
    public TextToVoiceHelper(Context context, OnInitListener listener) {
        textToSpeech = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.getDefault());
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language is not supported.");
                    isInitialized = false;
                } else {
                    isInitialized = true;
                }
            } else {
                Log.e(TAG, "TextToSpeech initialization failed.");
                isInitialized = false;
            }
            // Notify the listener about the initialization status
            if (listener != null) {
                listener.onInit(isInitialized);
            }
        });
    }

    /**
     * Speaks the given text.
     * @param text The text to be spoken.
     */
    public void speak(String text) {
        if (!isInitialized) {
            Log.e(TAG, "TTS not initialized, cannot speak.");
            return;
        }
        // Use QUEUE_FLUSH to interrupt any ongoing speech and speak the new text immediately.
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    /**
     * Releases the resources used by the TextToSpeech engine.
     * Should be called in the onDestroy() method of the activity or fragment.
     */
    public void shutdown() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            isInitialized = false;
        }
    }

    /**
     * Interface for the initialization callback.
     */
    public interface OnInitListener {
        void onInit(boolean success);
    }
}