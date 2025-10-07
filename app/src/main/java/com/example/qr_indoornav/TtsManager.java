package com.example.qr_indoornav;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

// ---------------------------------
// Done by: Lim Yeu Chuan
// --------------------------------


public class TtsManager {

    private static final String TAG = "TtsManager";
    private static volatile TtsManager INSTANCE = null;

    private TextToSpeech textToSpeech;
    private boolean isInitialized = false;

    // Private constructor to prevent instantiation
    private TtsManager() {}

    /**
     * The method to get the single instance of the class.
     * Throws an exception if init() has not been called first.
     */
    public static TtsManager getInstance() {
        if (INSTANCE == null) {
            // This should not happen if init() is called in Application class.
            // It's a safeguard.
            throw new IllegalStateException("TtsManager must be initialized in the Application class before use.");
        }
        return INSTANCE;
    }

    /**
     * Initializes the Singleton instance. This must be called once, ideally in the
     * Application's onCreate method, to avoid memory leaks.
     *
     * @param context The application context.
     */
    public static void init(final Context context) {
        if (INSTANCE == null) {
            // Synchronize to prevent race conditions during initialization
            synchronized (TtsManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TtsManager();
                    INSTANCE.initializeTts(context);
                }
            }
        }
    }

    private void initializeTts(Context context) {
        textToSpeech = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.getDefault());
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language is not supported by TTS engine.");
                    isInitialized = false;
                } else {
                    isInitialized = true;
                    Log.i(TAG, "TTS Engine Initialized successfully.");
                }
            } else {
                Log.e(TAG, "TTS Initialization failed with status: " + status);
                isInitialized = false;
            }
        });
    }

    /**
     * Speaks the given text.
     *
     * @param text The text to be spoken.
     */
    public void _speak(String text) {
        if (!isInitialized) {
            Log.e(TAG, "TTS not initialized, cannot speak. Check for errors during init.");
            return;
        }
        // QUEUE_FLUSH interrupts the current speech to speak the new one.
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    /**
     * Releases the resources used by the TextToSpeech engine.
     * This can be called from the Application's onTerminate if needed.
     */
    public void shutdown() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            isInitialized = false;
            INSTANCE = null; // Allow re-initialization if needed
        }
    }

    static public void speak(String text) {
        // The call is now simpler and global
        getInstance()._speak(text);
    }
}