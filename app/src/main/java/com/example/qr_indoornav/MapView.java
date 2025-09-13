package com.example.qr_indoornav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

public class MapView extends View {

    private Paint nodePaint, startNodePaint, endNodePaint;
    private Paint edgePaint, pathPaint;
    private Paint textPaint;
    private float nodeRadius = 30f;

    // Use a Map to store node coordinates for easy lookup
    private Map<String, PointF> nodeCoordinates = new HashMap<>();

    public MapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Paint for default (gray) nodes
        nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        nodePaint.setColor(Color.GRAY);
        nodePaint.setStyle(Paint.Style.FILL_AND_STROKE);

        // Paint for start (red) node
        startNodePaint = new Paint(nodePaint);
        startNodePaint.setColor(Color.RED);

        // Paint for end (green) node
        endNodePaint = new Paint(nodePaint);
        endNodePaint.setColor(Color.parseColor("#4CAF50")); // A nice green

        // Paint for default (black) edges
        edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        edgePaint.setColor(Color.BLACK);
        edgePaint.setStrokeWidth(8f);

        // Paint for path (blue) edges
        pathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pathPaint.setColor(Color.parseColor("#03A9F4")); // A nice light blue
        pathPaint.setStrokeWidth(12f);

        // Paint for text labels
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(40f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Define node coordinates based on the view's dimensions
        // This makes the drawing responsive to different screen sizes.
        // The values (e.g., w * 0.1f) are percentages of the width/height.
        nodeCoordinates.put("N1", new PointF(w * 0.15f, h * 0.25f));
        nodeCoordinates.put("N2", new PointF(w * 0.5f, h * 0.25f));
        nodeCoordinates.put("N3", new PointF(w * 0.85f, h * 0.25f));
        nodeCoordinates.put("N4", new PointF(w * 0.15f, h * 0.75f));
        nodeCoordinates.put("N5", new PointF(w * 0.5f, h * 0.75f));
        nodeCoordinates.put("N6", new PointF(w * 0.85f, h * 0.75f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (nodeCoordinates.isEmpty()) return;

        // --- DRAW EDGES ---
        // This is hardcoded for now. Later, this will be driven by your graph data.
        drawEdge(canvas, "N1", "N2", edgePaint);
        drawEdge(canvas, "N1", "N4", edgePaint);
        drawEdge(canvas, "N2", "N3", edgePaint);
        drawEdge(canvas, "N2", "N5", edgePaint);

        // --- DRAW THE DYNAMIC PATH ---
        // This is also hardcoded. Later, you'll pass a list of nodes to this view.
        drawEdge(canvas, "N4", "N5", pathPaint);
        drawEdge(canvas, "N5", "N6", pathPaint);
        drawEdge(canvas, "N6", "N3", pathPaint);


        // --- DRAW NODES ---
        // Draw all nodes. The color will be determined by whether it's a start/end node.
        // For now, we hardcode N4 as start and N3 as end.
        for (String nodeName : nodeCoordinates.keySet()) {
            Paint currentPaint;
            if (nodeName.equals("N4")) {
                currentPaint = startNodePaint; // Origin
            } else if (nodeName.equals("N3")) {
                currentPaint = endNodePaint;   // Destination
            } else {
                currentPaint = nodePaint;      // Intermediate
            }
            drawNode(canvas, nodeName, currentPaint);
        }
    }

    private void drawEdge(Canvas canvas, String from, String to, Paint paint) {
        PointF start = nodeCoordinates.get(from);
        PointF end = nodeCoordinates.get(to);
        if (start != null && end != null) {
            canvas.drawLine(start.x, start.y, end.x, end.y, paint);
        }
    }

    private void drawNode(Canvas canvas, String name, Paint paint) {
        PointF pos = nodeCoordinates.get(name);
        if (pos != null) {
            // Draw the circle
            canvas.drawCircle(pos.x, pos.y, nodeRadius, paint);
            // Draw the label
            canvas.drawText(name, pos.x, pos.y - nodeRadius - 15, textPaint);
        }
    }
}