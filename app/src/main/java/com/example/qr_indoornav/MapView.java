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
    private Paint nodePaint, startNodePaint, endNodePaint, pathNodePaint;
    private Map<String, PointF> roomCoordinates = new HashMap<>();

    private Paint edgePaint, pathPaint, textPaint;
    private final float nodeRadius = 30f;
    private final float roomMarkerRadius = 15f;
    private final float viewPadding = 60f;

    // --- Dynamic Data ---
    private Graph graph;
    private Map<String, PointF> nodeCoordinates = new HashMap<>();
    private List<String> pathNodeIds = new ArrayList<>();
    private String startNodeId;

    // --- Unified Destination Handling ---
    private boolean destinationIsRoom = false;
    private String finalDestinationId;

    public MapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    public void setData(Graph graph, List<String> pathIds, String destinationId) {
        this.graph = graph;
        this.pathNodeIds = pathIds;
        this.finalDestinationId = destinationId;
        this.destinationIsRoom = !graph.getAllNodes().stream().anyMatch(n -> n.id.equals(destinationId));

        if (!pathIds.isEmpty()) {
            this.startNodeId = pathIds.get(0);
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

    private void calculateNodeCoordinates() {
        if (graph == null || graph.getAllNodes().isEmpty() || getWidth() == 0) {
            return;
        }

        // --- Step 1: Calculate Relative Positions for JUNCTIONS ONLY using BFS ---
        Map<String, PointF> relativeCoords = new HashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        if (startNodeId == null) return;
        Node startNode = graph.getNode(startNodeId);
        if (startNode == null) return; // Ensure the start node is a junction

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

                    double angleRad = Math.toRadians(edge.directionDegrees - 90);
                    float dx = (float) (edge.distanceMeters * Math.cos(angleRad));
                    float dy = (float) (edge.distanceMeters * Math.sin(angleRad));
                    relativeCoords.put(edge.toNodeId, new PointF(currentPos.x + dx, currentPos.y + dy));
                }
            }
        }

        // --- Step 2 & 3: Find Bounding Box and Scale Factor (Unchanged) ---
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE, minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        for (PointF pos : relativeCoords.values()) {
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
            minY = Math.min(minY, pos.y);
            maxY = Math.max(maxY, pos.y);
        }
        float graphWidth = maxX - minX;
        float graphHeight = maxY - minY;
        if (graphWidth < 1) graphWidth = 1;
        if (graphHeight < 1) graphHeight = 1;

        float availableWidth = getWidth() - (2 * viewPadding);
        float availableHeight = getHeight() - (2 * viewPadding);
        float scale = Math.min(availableWidth / graphWidth, availableHeight / graphHeight);


        // --- Step 4: Calculate Final On-Screen Coordinates for JUNCTIONS ---
        nodeCoordinates.clear();
        for (Map.Entry<String, PointF> entry : relativeCoords.entrySet()) {
            PointF relPos = entry.getValue();
            float screenX = viewPadding + (relPos.x - minX) * scale;
            float screenY = viewPadding + (relPos.y - minY) * scale;
            nodeCoordinates.put(entry.getKey(), new PointF(screenX, screenY));
        }

        // --- Step 5: Calculate Screen Coordinates for ALL Rooms via Interpolation ---
        roomCoordinates.clear();
        Set<String> drawnEdges = new HashSet<>(); // To avoid drawing rooms twice for bidirectional edges
        for (Node junction : graph.getAllNodes()) {
            for (Edge edge : junction.getEdges().values()) {
                // Create a unique key for the edge to handle bidirectional graph
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
                        // Calculate position ratio. For 3 rooms, ratios are 1/4, 2/4, 3/4
                        float ratio = (float) (i + 1) / (totalRooms + 1);

                        float roomX = startPos.x + (endPos.x - startPos.x) * ratio;
                        float roomY = startPos.y + (endPos.y - startPos.y) * ratio;
                        roomCoordinates.put(roomId, new PointF(roomX, roomY));
                    }
                }
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        calculateNodeCoordinates();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (graph == null || nodeCoordinates.isEmpty()) return;

        // --- 1. Draw ALL Edges (Junction to Junction) ---
        for (Node node : graph.getAllNodes()) {
            for (Edge edge : node.edges.values()) {
                // To avoid drawing edges twice in an undirected graph representation
                if (node.id.compareTo(edge.toNodeId) < 0) {
                    drawEdge(canvas, node.id, edge.toNodeId, edgePaint);
                }
            }
        }

        // --- 2. Draw the Calculated Path ---
        if (pathNodeIds != null && pathNodeIds.size() > 1) {
            for (int i = 0; i < pathNodeIds.size() - 1; i++) {
                drawEdge(canvas, pathNodeIds.get(i), pathNodeIds.get(i + 1), pathPaint);
            }
        }
        // Special case: Draw partial path line to the destination room
        if (destinationIsRoom && finalDestinationId != null && pathNodeIds.size() >= 1) {
            PointF finalJunctionPos = nodeCoordinates.get(pathNodeIds.get(pathNodeIds.size() - 1));
            PointF destRoomPos = roomCoordinates.get(finalDestinationId);
            if (finalJunctionPos != null && destRoomPos != null) {
                canvas.drawLine(finalJunctionPos.x, finalJunctionPos.y, destRoomPos.x, destRoomPos.y, pathPaint);
            }
        }


        // --- 3. Draw the Junction Nodes ---
        for (String nodeId : nodeCoordinates.keySet()) {
            Paint currentPaint = nodePaint; // Default to gray
            if (nodeId.equals(startNodeId)) {
                currentPaint = startNodePaint; // Origin
            } else if (pathNodeIds.contains(nodeId)) {
                currentPaint = pathNodePaint; // Traversed path node
            }
            if (!destinationIsRoom && nodeId.equals(finalDestinationId)) {
                currentPaint = endNodePaint; // Destination Junction
            }
            drawNode(canvas, nodeId, currentPaint);
        }

        // --- 4. Draw All Room Markers ---
        for(Map.Entry<String, PointF> entry : roomCoordinates.entrySet()) {
            String roomId = entry.getKey();
            PointF pos = entry.getValue();

            Paint roomPaint = nodePaint; // Default gray
            // If this room is the final destination, color it green
            if(roomId.equals(finalDestinationId)) {
                roomPaint = endNodePaint;
            }

            canvas.drawCircle(pos.x, pos.y, roomMarkerRadius, roomPaint);
            // Optionally draw room text, might get cluttered
            // canvas.drawText(roomId, pos.x, pos.y - roomMarkerRadius - 10, textPaint);
        }
    }

    // --- drawEdge() and drawNode() are UNCHANGED ---
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