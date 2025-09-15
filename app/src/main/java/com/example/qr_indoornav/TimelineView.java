package com.example.qr_indoornav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.qr_indoornav.model.Location;

import java.util.ArrayList;
import java.util.List;

public class TimelineView extends View {

    private Paint redPaint, greenPaint, grayPaint, textPaint;
    private Paint completedLinePaint, futureLinePaint;

    private List<Location> pathLocations = new ArrayList<>();
    private int currentNodeIndex = 0;

    private final float nodeRadius = 20f;
    private final float padding = 60f; // Padding on left and right

    public TimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        redPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        redPaint.setColor(Color.RED);

        greenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        greenPaint.setColor(ContextCompat.getColor(getContext(), R.color.compass_bg_target_reached)); // A nice green

        grayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        grayPaint.setColor(Color.LTGRAY);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(30f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        completedLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        completedLinePaint.setColor(Color.DKGRAY);
        completedLinePaint.setStrokeWidth(8f);

        futureLinePaint = new Paint(completedLinePaint);
        futureLinePaint.setPathEffect(new DashPathEffect(new float[]{15, 15}, 0));
    }

    /**
     * Public method to update the timeline's data and trigger a redraw.
     * @param path The full list of locations (junctions and final room) in the path.
     * @param currentLegIndex The index of the current starting node for this leg of the journey.
     */
    public void updatePath(List<Location> path, int currentLegIndex) {
        this.pathLocations = new ArrayList<>(path);
        this.currentNodeIndex = currentLegIndex;
        invalidate(); // Request a redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (pathLocations == null || pathLocations.size() < 2) {
            return; // Nothing to draw
        }

        int totalNodes = pathLocations.size();
        float availableWidth = getWidth() - (2 * padding);
        float spacing = availableWidth / (totalNodes - 1);
        float yPos = getHeight() / 2f;

        // Draw lines first, so nodes are drawn on top
        for (int i = 1; i < totalNodes; i++) {
            float startX = padding + (i - 1) * spacing;
            float stopX = padding + i * spacing;
            // If the line leads to the current node or a past one, it's "completed"
            Paint linePaint = (i <= currentNodeIndex) ? completedLinePaint : futureLinePaint;
            canvas.drawLine(startX, yPos, stopX, yPos, linePaint);
        }

        // Draw nodes and text
        for (int i = 0; i < totalNodes; i++) {
            float xPos = padding + i * spacing;
            Paint nodePaint;

            if (i == currentNodeIndex) {
                nodePaint = redPaint; // Current node is red
            } else if (i == currentNodeIndex + 1) {
                nodePaint = greenPaint; // Next node is green
            } else {
                nodePaint = grayPaint; // All other nodes are gray
            }

            canvas.drawCircle(xPos, yPos, nodeRadius, nodePaint);

            // Draw text labels below the nodes
            String label = pathLocations.get(i).id; // Use the short ID for the label
            canvas.drawText(label, xPos, yPos + nodeRadius + 40, textPaint);
        }
    }
}