#include "QRDecoder.h"

std::string decodeQRCode(const cv::Mat& frame) {
    cv::Mat gray;
    if (frame.channels() == 3) {
        cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    } else {
        gray = frame;
    }

    cv::QRCodeDetector qrDecoder;
    std::vector<cv::Point> points;

    // Detect QR code position
    bool found = qrDecoder.detect(gray, points);
    if (!found) return "";

    // Decode using detected points
    std::string decodedText = qrDecoder.decode(gray, points);

    return decodedText;
}
