package com.example.qr_indoornav;

import android.content.Intent;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class SuccessActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_success);

        ImageView tickImageView = findViewById(R.id.success_tick_image);
        View rootLayout = findViewById(R.id.success_root_layout);

        // Set the animated drawable and start the animation
        tickImageView.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_animated_tick));
        Drawable drawable = tickImageView.getDrawable();
        if (drawable instanceof Animatable) {
            ((Animatable) drawable).start();
        }

        // Set a click listener on the entire screen
        rootLayout.setOnClickListener(v -> {
            // Create an intent to go back to the app's main page
            // We assume NavigationActivity is the main selection screen.
            // FLAG_ACTIVITY_CLEAR_TOP will clear all activities on top of it.
            Intent intent = new Intent(SuccessActivity.this, InitialActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish(); // Finish this success activity
        });
    }

    // Prevent user from going back to the compass screen with the back button
    @Override
    public void onBackPressed() {
        // Do nothing, force the user to click to return to the main menu
    }
}