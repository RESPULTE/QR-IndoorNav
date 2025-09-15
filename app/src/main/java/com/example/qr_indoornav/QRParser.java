package com.example.qr_indoornav;

import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

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
     * Parses a JSON string from a QR code and identifies its type and ID.
     * @param jsonString The raw string decoded from the QR code.
     * @return A ScannedQRData object with the parsed information.
     */
    public static ScannedQRData parse(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return new ScannedQRData(ScannedQRData.QRType.INVALID, "Empty");
        }

        try {
            JSONObject json = new JSONObject(jsonString);

            // Check for Junction QR format (contains "paths" array)
            if (json.has("paths")) {
                // We extract the 'from' node of the first path in the QR's JSON
                String junctionId = json.getJSONArray("paths").getJSONObject(0).getString("path").split("-")[0];
                return new ScannedQRData(ScannedQRData.QRType.JUNCTION, junctionId);
            }

            // Check for Room QR format (contains "id" and "def" keys)
            if (json.has("id") && json.has("def")) {
                String roomId = json.getString("id");
                return new ScannedQRData(ScannedQRData.QRType.ROOM, roomId);
            }

            // If neither format matches our known QR types
            return new ScannedQRData(ScannedQRData.QRType.INVALID, "Unknown Format");

        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse QR JSON string: " + jsonString, e);
            return new ScannedQRData(ScannedQRData.QRType.INVALID, "Parsing Error");
        }
    }
}