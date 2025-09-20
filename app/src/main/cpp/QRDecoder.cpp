#include <opencv2/opencv.hpp>
#include <iostream>

using namespace cv;
using namespace std;

// --- Order points for perspective correction ---
vector<Point2f> order_points(vector<Point2f> pts) {
    vector<Point2f> rect(4);
    vector<float> s, diff;

    for (auto& p : pts) {
        s.push_back(p.x + p.y);
        diff.push_back(p.x - p.y);
    }

    rect[0] = pts[min_element(s.begin(), s.end()) - s.begin()]; // top-left
    rect[2] = pts[max_element(s.begin(), s.end()) - s.begin()]; // bottom-right
    rect[1] = pts[min_element(diff.begin(), diff.end()) - diff.begin()]; // top-right
    rect[3] = pts[max_element(diff.begin(), diff.end()) - diff.begin()]; // bottom-left

    return rect;
}

// --- Perspective correction ---
Mat correct_perspective(const Mat& image) {
    try {
        QRCodeDetector detector;
        Mat points;
        bool retval = detector.detect(image, points);

        if (retval && !points.empty()) {
            vector<Point2f> pts;
            for (int i = 0; i < points.cols; i++) {
                pts.emplace_back(points.at<Point2f>(0, i));
            }
            vector<Point2f> dst_pts = { {0,0}, {399,0}, {399,399}, {0,399} };
            Mat matrix = getPerspectiveTransform(pts, dst_pts);
            Mat warped;
            warpPerspective(image, warped, matrix, Size(400, 400));
            return warped;
        }

        // fallback with contours
        Mat gray, thresh;
        cvtColor(image, gray, COLOR_BGR2GRAY);
        threshold(gray, thresh, 127, 255, THRESH_BINARY);
        vector<vector<Point>> contours;
        findContours(thresh, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
        if (contours.empty()) return Mat();

        vector<Point> c = *max_element(contours.begin(), contours.end(),
                                       [](const vector<Point>& a, const vector<Point>& b) {
                                           return contourArea(a) < contourArea(b);
                                       });

        vector<Point> approx;
        approxPolyDP(c, approx, 0.02 * arcLength(c, true), true);
        if (approx.size() != 4) return Mat();

        vector<Point2f> corners;
        for (auto& p : approx) corners.push_back(Point2f(p.x, p.y));
        auto ordered = order_points(corners);

        vector<Point2f> dst_pts = { {0,0}, {399,0}, {399,399}, {0,399} };
        Mat matrix = getPerspectiveTransform(ordered, dst_pts);
        Mat warped;
        warpPerspective(image, warped, matrix, Size(400, 400), INTER_LINEAR, BORDER_CONSTANT, Scalar(255, 255, 255));

        return warped;

    }
    catch (exception& e) {
        cerr << "[ERROR] Perspective correction failed: " << e.what() << endl;
        return Mat();
    }
}

// --- Sharpening ---
Mat enhance_with_opencv(const Mat& image) {
    try {
        Mat kernel = (Mat_<float>(3, 3) <<
                                        -1, -1, -1,
                -1, 9, -1,
                -1, -1, -1);
        Mat enhanced;
        filter2D(image, enhanced, -1, kernel);
        return enhanced;
    }
    catch (exception& e) {
        cerr << "[ERROR] Enhancement failed: " << e.what() << endl;
        return image.clone();
    }
}

// --- Decode with OpenCV ---
string decode_with_opencv(const Mat& image) {
    QRCodeDetector detector;
    return detector.detectAndDecode(image);
}

// --- Main pipeline ---
string decodeQRCode(const Mat& image) {
    // Step 1: Direct decode
    string data = decode_with_opencv(image);
    if (!data.empty()) {
        return data;
    }

    // Step 2: Perspective correction + sharpening
    Mat corrected = correct_perspective(image);
    if (!corrected.empty()) {
        Mat enhanced = enhance_with_opencv(corrected);
        data = decode_with_opencv(enhanced);
        if (!data.empty()) {
            return data;
        }
    }

    // Step 3: Failure
    return "";
}
