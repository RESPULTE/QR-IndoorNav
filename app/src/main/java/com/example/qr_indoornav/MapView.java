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
    private final float viewPadding = 60f; // Increased padding for better aesthetics

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
     * Public entry point to provide all necessary data to the view.
     * This triggers the automatic layout calculation and redraws the view.
     */
    public void setData(Graph graph, List<String> pathIds, String startId, String endId) {
        this.graph = graph;
        this.pathNodeIds = pathIds;
        this.startNodeId = startId;
        this.endNodeId = endId;
        // If the view is already laid out, calculate immediately.
        // Otherwise, onSizeChanged will handle the initial calculation.
        if (getWidth() > 0 && getHeight() > 0) {
            calculateNodeCoordinates();
        }
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
     * The core of the dynamic layout logic. It builds, scales, and centers the graph.
     */
    private void calculateNodeCoordinates() {
        if (graph == null || graph.getAllNodes().isEmpty() || getWidth() == 0) {
            return;
        }

        // --- Step 1: Calculate Relative Positions using BFS and Trigonometry ---
        // This builds a "virtual map" where 1 unit = 1 meter from your JSON data.
        Map<String, PointF> relativeCoords = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        // Start the layout algorithm from the user's origin for a consistent view.
        if (startNodeId == null) return;
        queue.add(startNodeId);
        visited.add(startNodeId);
        relativeCoords.put(startNodeId, new PointF(0, 0));

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            Node currentNode = graph.getNode(currentId);
            PointF currentPos = relativeCoords.get(currentId);

            if (currentNode == null || currentPos == null) continue;

            for (Edge edge : currentNode.edges.values()) {
                if (!visited.contains(edge.toNodeId)) {
                    visited.add(edge.toNodeId);
                    queue.add(edge.toNodeId);

                    // Convert compass degrees to radians for trigonometric functions.
                    // We subtract 90 degrees because in standard math, 0 degrees is East,
                    // but for us, 0 degrees is North.
                    double angleRad = Math.toRadians(edge.directionDegrees - 90);

                    // Calculate the (x, y) offset from the current node.
                    float dx = (float) (edge.distanceMeters * Math.cos(angleRad));
                    float dy = (float) (edge.distanceMeters * Math.sin(angleRad)); // Y is correct in math coords for now
                    relativeCoords.put(edge.toNodeId, new PointF(currentPos.x + dx, currentPos.y + dy));
                }
            }
        }

        // --- Step 2: Find the Bounding Box of the Virtual Map ---
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE, minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        for (PointF pos : relativeCoords.values()) {
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
            minY = Math.min(minY, pos.y);
            maxY = Math.max(maxY, pos.y);
        }

        float graphWidth = maxX - minX;
        float graphHeight = maxY - minY;

        // Avoid division by zero for single-node or linear graphs
        if (graphWidth < 1) graphWidth = 1;
        if (graphHeight < 1) graphHeight = 1;

        // --- Step 3: Calculate the Optimal Scale Factor to Fit the Screen ---
        float availableWidth = getWidth() - (2 * viewPadding);
        float availableHeight = getHeight() - (2 * viewPadding);

        // We use the smaller of the two possible scale factors to ensure the entire
        // graph fits while maintaining its aspect ratio. THIS IS KEY FOR PROPORTIONALITY.
        float scale = Math.min(availableWidth / graphWidth, availableHeight / graphHeight);

        // --- Step 4: Center the Graph and Calculate Final On-Screen Coordinates ---
        float scaledGraphWidth = graphWidth * scale;
        float scaledGraphHeight = graphHeight * scale;
        float offsetX = (availableWidth - scaledGraphWidth) / 2f;
        float offsetY = (availableHeight - scaledGraphHeight) / 2f;

        nodeCoordinates.clear();
        for (Map.Entry<String, PointF> entry : relativeCoords.entrySet()) {
            PointF relPos = entry.getValue();
            // Translate the point from the virtual map's coordinate system to the screen's.
            float screenX = viewPadding + offsetX + (relPos.x - minX) * scale;
            float screenY = viewPadding + offsetY + (relPos.y - minY) * scale;
            nodeCoordinates.put(entry.getKey(), new PointF(screenX, screenY));
        }
    }


    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // This is the first time we know the view's dimensions, so we calculate coordinates here.
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