package com.example.qr_indoornav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.example.qr_indoornav.model.Edge;
import com.example.qr_indoornav.model.Graph;
import com.example.qr_indoornav.model.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class MapView extends View {

    // --- Paint objects for drawing ---
    private Paint nodePaint, startNodePaint, endNodePaint;
    private Paint edgePaint, pathPaint, textPaint;
    private final float nodeRadius = 30f;
    private final float padding = 50f; // Padding around the graph

    // --- Dynamic Data ---
    private Graph graph;
    private Map<String, PointF> nodeCoordinates = new HashMap<>(); // Final, on-screen coordinates
    private List<String> pathNodeIds = new ArrayList<>();
    private String startNodeId, endNodeId;

    public MapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    /**
     * This is the new public method to pass all necessary data to the view.
     * It triggers the layout calculation and redraws the view.
     */
    public void setData(Graph graph, List<String> pathIds, String startId, String endId) {
        this.graph = graph;
        this.pathNodeIds = pathIds;
        this.startNodeId = startId;
        this.endNodeId = endId;
        calculateNodeCoordinates(); // Calculate positions before drawing
        invalidate(); // Request a redraw
    }

    private void initPaints() {
        nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        nodePaint.setColor(Color.GRAY);
        startNodePaint = new Paint(nodePaint);
        startNodePaint.setColor(Color.RED);
        endNodePaint = new Paint(nodePaint);
        endNodePaint.setColor(Color.parseColor("#4CAF50")); // Green

        edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        edgePaint.setColor(Color.BLACK);
        edgePaint.setStrokeWidth(8f);
        pathPaint = new Paint(edgePaint);
        pathPaint.setColor(Color.parseColor("#03A9F4")); // Blue
        pathPaint.setStrokeWidth(12f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(40f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * Core of the new logic. Calculates proportional node positions based on graph data.
     */
    private void calculateNodeCoordinates() {
        if (graph == null || graph.getAllNodes().isEmpty() || getWidth() == 0) {
            return;
        }

        // --- 1. Calculate Relative Positions using BFS and Trigonometry ---
        Map<String, PointF> relativeCoords = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        // Start layout from the first node in the graph at origin (0,0)
        String firstNodeId = graph.getAllNodes().get(0).id;
        queue.add(firstNodeId);
        visited.add(firstNodeId);
        relativeCoords.put(firstNodeId, new PointF(0, 0));

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            Node currentNode = graph.getNode(currentId);
            PointF currentPos = relativeCoords.get(currentId);

            for (Edge edge : currentNode.edges.values()) {
                if (!visited.contains(edge.toNodeId)) {
                    visited.add(edge.toNodeId);
                    queue.add(edge.toNodeId);

                    // Convert direction to radians for trig functions
                    double angleRad = Math.toRadians(edge.directionDegrees);
                    // Calculate offset from current node
                    // Note: We subtract Y because computer graphics Y increases downwards
                    float dx = (float) (edge.distanceMeters * Math.sin(angleRad));
                    float dy = (float) (edge.distanceMeters * Math.cos(angleRad));
                    relativeCoords.put(edge.toNodeId, new PointF(currentPos.x + dx, currentPos.y - dy));
                }
            }
        }

        // --- 2. Find the Bounds of the Relative Graph ---
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE, minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        for (PointF pos : relativeCoords.values()) {
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
            minY = Math.min(minY, pos.y);
            maxY = Math.max(maxY, pos.y);
        }

        // --- 3. Scale and Translate to Fit the View Canvas ---
        float graphWidth = maxX - minX;
        float graphHeight = maxY - minY;
        float viewWidth = getWidth() - (2 * padding);
        float viewHeight = getHeight() - (2 * padding);

        // Handle case of single node to avoid division by zero
        if (graphWidth == 0) graphWidth = 1;
        if (graphHeight == 0) graphHeight = 1;

        // Use the smaller scale factor to maintain aspect ratio
        float scale = Math.min(viewWidth / graphWidth, viewHeight / graphHeight);

        nodeCoordinates.clear();
        for (Map.Entry<String, PointF> entry : relativeCoords.entrySet()) {
            PointF relPos = entry.getValue();
            // Scale and translate the point to fit within the padded view area
            float screenX = padding + (relPos.x - minX) * scale;
            float screenY = padding + (relPos.y - minY) * scale;
            nodeCoordinates.put(entry.getKey(), new PointF(screenX, screenY));
        }
    }


    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Recalculate coordinates if the view size changes (e.g., device rotation)
        calculateNodeCoordinates();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (graph == null || nodeCoordinates.isEmpty()) return;

        // --- 1. Draw ALL Edges from the Graph in Black (the full network) ---
        for (Node node : graph.getAllNodes()) {
            for (Edge edge : node.edges.values()) {
                drawEdge(canvas, node.id, edge.toNodeId, edgePaint);
            }
        }

        // --- 2. Draw the Calculated Path in Blue on Top ---
        if (pathNodeIds != null && pathNodeIds.size() > 1) {
            for (int i = 0; i < pathNodeIds.size() - 1; i++) {
                drawEdge(canvas, pathNodeIds.get(i), pathNodeIds.get(i + 1), pathPaint);
            }
        }

        // --- 3. Draw the Nodes on Top of the Lines ---
        for (String nodeId : nodeCoordinates.keySet()) {
            Paint currentPaint = nodePaint;
            if (nodeId.equals(startNodeId)) {
                currentPaint = startNodePaint; // Highlight origin
            } else if (nodeId.equals(endNodeId)) {
                currentPaint = endNodePaint;   // Highlight destination
            }
            drawNode(canvas, nodeId, currentPaint);
        }
    }

    private void drawEdge(Canvas canvas, String fromId, String toId, Paint paint) {
        PointF start = nodeCoordinates.get(fromId);
        PointF end = nodeCoordinates.get(toId);
        if (start != null && end != null) {
            canvas.drawLine(start.x, start.y, end.x, end.y, paint);
        }
    }

    private void drawNode(Canvas canvas, String nodeId, Paint paint) {
        PointF pos = nodeCoordinates.get(nodeId);
        if (pos != null) {
            canvas.drawCircle(pos.x, pos.y, nodeRadius, paint);
            canvas.drawText(nodeId, pos.x, pos.y - nodeRadius - 15, textPaint);
        }
    }
}