package com.example.qr_indoornav.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class Graph {
    private final Map<String, Node> nodes = new HashMap<>();

    public void addNode(Node node) {
        nodes.put(node.id, node);
    }

    public Node getNode(String id) {
        return nodes.get(id);
    }

    public List<Node> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    // Inside your Graph class

    public Edge findEdgeContainingRoom(String junctionId, String roomId) {
        Node junction = getNode(junctionId);
        if (junction != null) {
            for (Edge edge : junction.getEdges().values()) {
                if (edge.roomIds.contains(roomId)) {
                    return edge;
                }
            }
        }
        return null; // Room not found on any edge connected to this junction
    }
    public List<String> findShortestPath(String startId, String endId) {
        Map<String, Integer> distances = new HashMap<>();
        Map<String, String> predecessors = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>((n1, n2) ->
                Integer.compare(distances.get(n1.id), distances.get(n2.id)));

        for (String nodeId : nodes.keySet()) {
            distances.put(nodeId, Integer.MAX_VALUE);
        }
        distances.put(startId, 0);
        queue.add(nodes.get(startId));

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            if (current.id.equals(endId)) break;

            for (Edge edge : current.edges.values()) {
                // Use distanceMeters as the weight for the pathfinding algorithm
                int newDist = distances.get(current.id) + edge.distanceMeters;
                if (newDist < distances.get(edge.toNodeId)) {
                    distances.put(edge.toNodeId, newDist);
                    predecessors.put(edge.toNodeId, current.id);
                    queue.add(nodes.get(edge.toNodeId));
                }
            }
        }

        List<String> path = new ArrayList<>();
        String at = endId;
        while (at != null) {
            path.add(at);
            at = predecessors.get(at);
        }
        Collections.reverse(path);

        return (path.size() > 0 && path.get(0).equals(startId)) ? path : new ArrayList<>();
    }
}