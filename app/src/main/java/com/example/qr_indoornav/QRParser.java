package com.example.qr_indoornav;

import android.util.Log;

public class QRParser {

    private static final String TAG = "QRParser";

    // A simple data class to hold the parsed result
    public static class ScannedQRData {
        public enum QRType { JUNCTION, ROOM, INVALID }
        public final QRType type;
        public final String id;

        ScannedQRData(QRType type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    /**
     * Parses a compact string from a QR code and identifies its type and ID.
     *
     * @param qrString The raw string decoded from the QR code.
     * @return A ScannedQRData object with the parsed information.
     */
    public static ScannedQRData parse(String qrString) {
        if (qrString == null || qrString.isEmpty()) {
            return new ScannedQRData(ScannedQRData.QRType.INVALID, "Empty");
        }

        // Check for the new compact junction format.
        // It must contain ":" and "-" and should not be a JSON string.
        if (qrString.contains(":") && qrString.contains("-") && !qrString.trim().startsWith("{")) {
            try {
                // Example: "1-2:54,290,N008-N010;..."
                // We only need the first path to identify the 'from' node of the junction.
                String firstPathEntry = qrString.split(";")[0]; // "1-2:54,290,N008-N010"
                String pathPart = firstPathEntry.split(":")[0];      // "1-2"
                String fromNode = pathPart.split("-")[0];           // "1"

                // Reconstruct the full junction ID to match the app's internal format (e.g., "N1")
                String junctionId = "N" + fromNode;
                return new ScannedQRData(ScannedQRData.QRType.JUNCTION, junctionId);

            } catch (Exception e) {
                Log.e(TAG, "Failed to parse compact QR string: " + qrString, e);
                return new ScannedQRData(ScannedQRData.QRType.INVALID, "Parsing Error");
            }
        }

        // If the format doesn't match, it's considered invalid in this new system.
        // You could add logic here later to parse "Room" QR codes if needed.
        return new ScannedQRData(ScannedQRData.QRType.INVALID, "Unknown Format");
    }
}