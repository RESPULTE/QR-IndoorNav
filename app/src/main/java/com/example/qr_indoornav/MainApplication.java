package com.example.qr_indoornav;

import android.app.Application;

public class MainApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize our global TTS Manager once for the entire app lifecycle
        TtsManager.init(this);
    }
}