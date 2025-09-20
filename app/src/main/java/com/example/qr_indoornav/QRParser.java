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
     * This method specifically parses the header of the new map data format to
     * determine the type (Junction/Room) and ID of the scanned location.
     *
     * @param qrString The raw string decoded from the QR code.
     *                 Example: "JN3A|AB,54,290,H-J|..."
     * @return A ScannedQRData object with the parsed information.
     */
    public static ScannedQRData parse(String qrString) {
        if (qrString == null || qrString.isEmpty() || !qrString.contains("|")) {
            return new ScannedQRData(ScannedQRData.QRType.INVALID, "Invalid or Empty Format");
        }

        try {
            // The header is the first part, before the first "|"
            String header = qrString.substring(0, qrString.indexOf('|'));

            // Header format: <Type Char><Prefix Char><Num Digits><Location Char>
            // Example: JN3A
            if (header.length() < 4) {
                return new ScannedQRData(ScannedQRData.QRType.INVALID, "Malformed Header");
            }

            // --- Extract information from the header ---
            char typeChar = header.charAt(0);
            String prefix = header.substring(1, 2);
            int numDigits = Integer.parseInt(header.substring(2, 3));
            char locationChar = header.charAt(3);

            // --- Determine the type of the QR point ---
            ScannedQRData.QRType type;
            if (typeChar == 'J') {
                type = ScannedQRData.QRType.JUNCTION;
            } else if (typeChar == 'r') {
                type = ScannedQRData.QRType.ROOM;
            } else {
                Log.w(TAG, "Unknown QR type character in header: " + typeChar);
                return new ScannedQRData(ScannedQRData.QRType.INVALID, "Unknown Type");
            }

            // --- Generate the full ID string ---
            // 'A' corresponds to 1, 'B' to 2, and so on.
            int numericValue = locationChar - 'A' + 1;
            // Pad the number with leading zeros based on numDigits
            String formatString = "%0" + numDigits + "d";
            String id = prefix + String.format(formatString, numericValue);

            // Return the successfully parsed data
            return new ScannedQRData(type, id);

        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse number of digits from header in QR string: " + qrString, e);
            return new ScannedQRData(ScannedQRData.QRType.INVALID, "Header Digit Error");
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse QR string: " + qrString, e);
            return new ScannedQRData(ScannedQRData.QRType.INVALID, "Parsing Error");
        }
    }
}