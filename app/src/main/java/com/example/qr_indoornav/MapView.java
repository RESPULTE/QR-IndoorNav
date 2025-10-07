package com.example.qr_indoornav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.util.Log;
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

// ---------------------------------
// Done by: Lim Yeu Chuan
// --------------------------------


public class MapView extends View {

    // --- Paint objects for drawing ---
    private Paint nodePaint, startNodePaint, endNodePaint, pathNodePaint;
    private Map<String, PointF> roomCoordinates = new HashMap<>();

    private Paint edgePaint, pathPaint, textPaint;
    private final float nodeRadius = 30f;
    private final float roomMarkerRadius = 15f;
    private final float viewPadding = 80f;

    // --- NEW: Minimum desired pixel distance between the centers of two nodes ---
    private static final float MIN_NODE_DISTANCE_PIXELS = 150f;


    // --- Dynamic Data ---
    private Graph graph;
    private Map<String, PointF> nodeCoordinates = new HashMap<>();
    private List<String> pathNodeIds = new ArrayList<>();
    private String startNodeId;
    private String finalDestinationId;

    // --- NEW: Booleans to track location types ---
    private boolean startIsRoom = false;
    private boolean destinationIsRoom = false;


    public MapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    public void setData(Graph graph, List<String> pathIds, String destinationId) {
        this.graph = graph;
        this.pathNodeIds = pathIds != null ? pathIds : new ArrayList<>();
        this.finalDestinationId = destinationId;
        this.destinationIsRoom = graph.getNode(destinationId) == null;

        if (!this.pathNodeIds.isEmpty()) {
            this.startNodeId = this.pathNodeIds.get(0);
            this.startIsRoom = graph.getNode(this.startNodeId) == null;
        } else {
            this.startNodeId = null;
            this.startIsRoom = false;
        }

        if (getWidth() > 0) {
            calculateNodeCoordinates();
        }
        invalidate();
    }

    private void initPaints() {
        nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        nodePaint.setColor(Color.GRAY);
        startNodePaint = new Paint(nodePaint);
        startNodePaint.setColor(Color.RED);
        endNodePaint = new Paint(nodePaint);
        endNodePaint.setColor(Color.parseColor("#4CAF50")); // Green for destination
        pathNodePaint = new Paint(nodePaint);
        pathNodePaint.setColor(Color.parseColor("#303F9F")); // Dark Blue
        edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        edgePaint.setColor(Color.BLACK);
        edgePaint.setStrokeWidth(8f);

        pathPaint = new Paint(edgePaint);
        pathPaint.setColor(Color.parseColor("#03A9F4")); // Light Blue
        pathPaint.setStrokeWidth(12f);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(40f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }



    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        calculateNodeCoordinates();
    }

    /**
     * Helper method to get the screen coordinates for any ID, whether it's a junction or a room.
     */
    private PointF getCoordinatesForId(String id) {
        if (nodeCoordinates.containsKey(id)) {
            return nodeCoordinates.get(id);
        }
        return roomCoordinates.get(id); // Returns null if not found in either map
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (graph == null || nodeCoordinates.isEmpty()) return;

        // --- 1. Draw base map: all junctions, rooms, and edges ---
        // Draw all edges first so they are underneath the nodes.
        for (Node node : graph.getAllNodes()) {
            for (Edge edge : node.edges.values()) {
                if (node.id.compareTo(edge.toNodeId) < 0) {
                    drawEdge(canvas, node.id, edge.toNodeId, edgePaint);
                }
            }
        }

        // --- 2. Draw the navigation path line ON TOP of the base map ---
        if (pathNodeIds.size() > 1) {
            for (int i = 0; i < pathNodeIds.size() - 1; i++) {
                PointF startPoint = getCoordinatesForId(pathNodeIds.get(i));
                PointF endPoint = getCoordinatesForId(pathNodeIds.get(i + 1));
                if (startPoint != null && endPoint != null) {
                    canvas.drawLine(startPoint.x, startPoint.y, endPoint.x, endPoint.y, pathPaint);
                }
            }
        }

        // Draw all junctions, coloring path junctions blue.
        for (String nodeId : nodeCoordinates.keySet()) {
            Paint currentPaint = pathNodeIds.contains(nodeId) ? pathNodePaint : nodePaint;
            drawNode(canvas, nodeId, currentPaint);
        }
        // Draw all rooms with default gray paint.
        for(Map.Entry<String, PointF> entry : roomCoordinates.entrySet()) {
            canvas.drawCircle(entry.getValue().x, entry.getValue().y, roomMarkerRadius, nodePaint);
        }

        // --- 3. Draw start and end markers ON TOP of everything else ---
        // This ensures they are always visible and correctly colored.
        if (startNodeId != null) {
            PointF startPos = getCoordinatesForId(startNodeId);
            if (startPos != null) {
                if (startIsRoom) {
                    canvas.drawCircle(startPos.x, startPos.y, roomMarkerRadius, startNodePaint);
                    canvas.drawText(startNodeId, startPos.x, startPos.y - roomMarkerRadius - 15, textPaint);
                } else { // It's a junction
                    drawNode(canvas, startNodeId, startNodePaint);
                }
            }
        }

        if (finalDestinationId != null) {
            PointF endPos = getCoordinatesForId(finalDestinationId);
            if (endPos != null) {
                if (destinationIsRoom) {
                    canvas.drawCircle(endPos.x, endPos.y, roomMarkerRadius, endNodePaint);
                    canvas.drawText(finalDestinationId, endPos.x, endPos.y - roomMarkerRadius - 15, textPaint);
                } else { // It's a junction
                    drawNode(canvas, finalDestinationId, endNodePaint);
                }
            }
        }
    }

    private void calculateNodeCoordinates() {
        if (graph == null || graph.getAllNodes().isEmpty() || getWidth() == 0) {
            return;
        }

        // --- Step 1: Calculate Relative Positions for JUNCTIONS using BFS ---
        Map<String, PointF> relativeCoords = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        String layoutRootNodeId = graph.getAllNodes().stream()
                .map(node -> node.id)
                .min(String::compareTo)
                .orElse(null);

        if (layoutRootNodeId == null) return;

        queue.add(layoutRootNodeId);
        visited.add(layoutRootNodeId);
        relativeCoords.put(layoutRootNodeId, new PointF(0, 0));

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            Node currentNode = graph.getNode(currentId);
            PointF currentPos = relativeCoords.get(currentId);

            if (currentNode == null || currentPos == null) continue;

            for (Edge edge : currentNode.edges.values()) {
                if (!visited.contains(edge.toNodeId)) {
                    visited.add(edge.toNodeId);
                    queue.add(edge.toNodeId);

                    double angleRad = Math.toRadians(edge.directionDegrees - 90);
                    float dx = (float) (edge.distanceMeters * Math.cos(angleRad));
                    float dy = (float) (edge.distanceMeters * Math.sin(angleRad));
                    relativeCoords.put(edge.toNodeId, new PointF(currentPos.x + dx, currentPos.y + dy));
                }
            }
        }

        // Handle trivial case of one or zero nodes
        if (relativeCoords.size() < 2) {
            nodeCoordinates.clear();
            if (!relativeCoords.isEmpty()) {
                nodeCoordinates.put(layoutRootNodeId, new PointF(getWidth() / 2f, getHeight() / 2f));
            }
            calculateRoomCoordinates(); // Will be empty but good practice
            return;
        }

        // --- Step 2: Find Bounding Box and Dimensions of Relative Coordinates ---
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE, minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        for (PointF pos : relativeCoords.values()) {
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
            minY = Math.min(minY, pos.y);
            maxY = Math.max(maxY, pos.y);
        }
        float graphWidth = maxX - minX;
        float graphHeight = maxY - minY;
        if (graphWidth < 0.1f) graphWidth = 0.1f;
        if (graphHeight < 0.1f) graphHeight = 0.1f;

        // --- Step 3: Determine the Optimal Scale Factor ---
        float availableWidth = getWidth() - (2 * viewPadding);
        float availableHeight = getHeight() - (2 * viewPadding);

        // a) Calculate the scale required to make the entire graph fit within the view.
        float scaleToFit = Math.min(availableWidth / graphWidth, availableHeight / graphHeight);

        // b) Calculate the scale required to enforce the minimum distance between the two closest nodes.
        float minRelativeDist = Float.MAX_VALUE;
        List<PointF> points = new ArrayList<>(relativeCoords.values());
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                PointF p1 = points.get(i);
                PointF p2 = points.get(j);
                float dist = (float) Math.hypot(p1.x - p2.x, p1.y - p2.y);
                minRelativeDist = Math.min(minRelativeDist, dist);
            }
        }

        float scaleForMinDist = Float.MAX_VALUE;
        Log.i("aaaaaa", "calculateNodeCoordinates: " + minRelativeDist);
        if (minRelativeDist > 0.001f) {
            scaleForMinDist = MIN_NODE_DISTANCE_PIXELS / minRelativeDist;
        }

        // c) The final scale is the SMALLER of the two, ensuring it's both readable and fits on screen.
        float finalScale = Math.min(scaleToFit, scaleForMinDist);

        // --- Step 4: Calculate Centering Offsets ---
        float scaledGraphWidth = graphWidth * finalScale;
        float scaledGraphHeight = graphHeight * finalScale;
        float offsetX = (availableWidth - scaledGraphWidth) / 2f;
        float offsetY = (availableHeight - scaledGraphHeight) / 2f;


        // --- Step 5: Calculate Final On-Screen Coordinates for JUNCTIONS ---
        nodeCoordinates.clear();
        for (Map.Entry<String, PointF> entry : relativeCoords.entrySet()) {
            PointF relPos = entry.getValue();
            // Apply scale, padding, and centering offset
            float screenX = viewPadding + offsetX + (relPos.x - minX) * finalScale;
            float screenY = viewPadding + offsetY + (relPos.y - minY) * finalScale;
            nodeCoordinates.put(entry.getKey(), new PointF(screenX, screenY));
        }

        // --- Step 6: Calculate Screen Coordinates for ALL Rooms based on Junctions ---
        calculateRoomCoordinates();
    }

    private void calculateRoomCoordinates() {
        roomCoordinates.clear();
        if (graph == null) return;

        Set<String> drawnEdges = new HashSet<>();
        for (Node junction : graph.getAllNodes()) {
            for (Edge edge : junction.getEdges().values()) {
                String edgeKey = junction.id.compareTo(edge.toNodeId) < 0 ? junction.id + "-" + edge.toNodeId : edge.toNodeId + "-" + junction.id;
                if (drawnEdges.contains(edgeKey) || edge.roomIds.isEmpty()) {
                    continue;
                }
                drawnEdges.add(edgeKey);

                PointF startPos = nodeCoordinates.get(junction.id);
                PointF endPos = nodeCoordinates.get(edge.toNodeId);

                if (startPos != null && endPos != null) {
                    int totalRooms = edge.roomIds.size();
                    for (int i = 0; i < totalRooms; i++) {
                        String roomId = edge.roomIds.get(i);
                        float ratio = (float) (i + 1) / (totalRooms + 1);

                        float roomX = startPos.x + (endPos.x - startPos.x) * ratio;
                        float roomY = startPos.y + (endPos.y - startPos.y) * ratio;
                        roomCoordinates.put(roomId, new PointF(roomX, roomY));
                    }
                }
            }
        }
    }


    // --- Drawing helper methods (Unchanged) ---
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