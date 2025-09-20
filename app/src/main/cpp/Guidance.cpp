#include <opencv2/opencv.hpp>

#include "Guidance.h"
#include "QRDecoder.h" // NEW: Include our new decoder module
#include <vector>
#include <cmath>
#include <functional>

// --- Configuration Constants for easy tuning ---
// Preprocessing
const int ADAPTIVE_THRESH_BLOCK_SIZE = 11; // Smaller for finer details
const int ADAPTIVE_THRESH_C = 7;
const double CLAHE_CLIP_LIMIT = 2.0;

// These values are based on trial and error and may need tuning for your specific camera/resolution.
// They correspond to the QR code being roughly 0.7-1.0 meters away.
const double MIN_DECODING_AREA = 0;
const double MAX_DECODING_AREA = 20000.0;

// Finder Pattern Validation
const double MIN_FINDER_AREA_ORIGINAL = 25.0; // Lowered to catch very small patterns
const double PYRAMID_SCALE_FACTOR = 2.0; // For upscaling (detects small codes)
const double DOWNSCALE_FACTOR = 0.5; // For downscaling (detects large codes)

// Adjusted for scale - you might need to tune these more
const double MIN_FINDER_AREA_PYRAMID = MIN_FINDER_AREA_ORIGINAL * (PYRAMID_SCALE_FACTOR / 1.5);
const double MIN_FINDER_AREA_DOWNSCALE = MIN_FINDER_AREA_ORIGINAL * (DOWNSCALE_FACTOR / 0.75); // Example adjustment

const double FINDER_SQUARE_TOLERANCE = 0.45; // Slightly more tolerant for aliasing
const double FINDER_AREA_RATIO_TOLERANCE = 0.6;

// Guidance System
const double MIN_AREA_FOR_GUIDANCE = 80.0; // Kept this threshold
const float CENTER_DEAD_ZONE_RATIO = 0.25;
const double ANGLE_RATIO_TOLERANCE = 0.30; // More tolerant for distorted small codes
const double DEDUPE_DISTANCE_THRESH = 15.0; // Pixel distance to consider patterns duplicates

struct FinderPattern {
    cv::Point2f center;
    double area;
    std::vector<cv::Point> actual_contour; // Stores the contour points directly

    FinderPattern(cv::Point2f c, double a, const std::vector<cv::Point>& contour_data)
            : center(c), area(a), actual_contour(contour_data) {
    }
};

// Preprocessing for the original, standard-sized image
cv::Mat preprocessImage(const cv::Mat& frame) {
    cv::Mat gray, claheImg, thresh;
    cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(CLAHE_CLIP_LIMIT, cv::Size(8, 8));
    clahe->apply(gray, claheImg);
    cv::adaptiveThreshold(claheImg, thresh, 255,
                          cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                          cv::THRESH_BINARY_INV, ADAPTIVE_THRESH_BLOCK_SIZE, ADAPTIVE_THRESH_C);
    return thresh;
}

// Special preprocessing for the upscaled (pyramid) image
cv::Mat preprocessPyramid(const cv::Mat& frame) {
    cv::Mat gray, claheImg, thresh;
    cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(CLAHE_CLIP_LIMIT, cv::Size(8, 8));
    clahe->apply(gray, claheImg);
    // Use a larger block size because features are larger in the upscaled image
    cv::adaptiveThreshold(claheImg, thresh, 255,
                          cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                          cv::THRESH_BINARY_INV, ADAPTIVE_THRESH_BLOCK_SIZE * 2 + 1, ADAPTIVE_THRESH_C);
    return thresh;
}

// You might want a specific preprocess for downscaled if block size needs adjustment
// For simplicity, let's reuse preprocessImage for now, but be aware of block size implications.
cv::Mat preprocessDownscale(const cv::Mat& frame) {
    cv::Mat gray, claheImg, thresh;
    cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(CLAHE_CLIP_LIMIT, cv::Size(8, 8));
    clahe->apply(gray, claheImg);
    // Potentially a smaller block size than original if features become very small after downscaling
    // Or, if features are still large enough, ADAPTIVE_THRESH_BLOCK_SIZE might be fine.
    cv::adaptiveThreshold(claheImg, thresh, 255,
                          cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                          cv::THRESH_BINARY_INV, ADAPTIVE_THRESH_BLOCK_SIZE, ADAPTIVE_THRESH_C);
    return thresh;
}


// Updated to take min_area as a parameter and store actual contour
std::vector<FinderPattern> findFinderPatterns(const std::vector<std::vector<cv::Point>>& contours, const std::vector<cv::Vec4i>& hierarchy, double min_area) {
    std::vector<FinderPattern> patterns;
    for (int i = 0; i < contours.size(); ++i) {
        int child_idx = hierarchy[i][2];
        if (child_idx == -1) continue;
        int grandchild_idx = hierarchy[child_idx][2];
        if (grandchild_idx == -1) continue;
        if (hierarchy[i][3] != -1) continue;

        double outerArea = cv::contourArea(contours[i]);
        if (outerArea < min_area) continue;

        cv::RotatedRect box = cv::minAreaRect(contours[i]);
        if (box.size.area() == 0 || outerArea / box.size.area() < (1.0 - FINDER_SQUARE_TOLERANCE)) continue;

        double middleArea = cv::contourArea(contours[child_idx]);
        double innerArea = cv::contourArea(contours[grandchild_idx]);
        if (middleArea == 0 || outerArea == 0) continue;
        if (std::abs((middleArea / outerArea) - 0.18) > FINDER_AREA_RATIO_TOLERANCE) continue;
        if (std::abs((innerArea / middleArea) - 0.11) > FINDER_AREA_RATIO_TOLERANCE) continue;

        cv::Moments M = cv::moments(contours[i]);
        if (M.m00 == 0) continue;
        patterns.emplace_back(cv::Point2f(M.m10 / M.m00, M.m01 / M.m00), outerArea, contours[i]);
    }
    return patterns;
}

// New function to encapsulate the multi-scale processing logic
std::vector<FinderPattern> processFrameForFinderPatterns(
        const cv::Mat& frame,
        std::function<cv::Mat(const cv::Mat&)> preprocess_func, // Function pointer for preprocessing
        double min_area,
        double scale_factor = 1.0 // Default to 1.0 for original scale
) {
    cv::Mat current_frame = frame;
    if (scale_factor != 1.0) {
        cv::resize(frame, current_frame, cv::Size(), scale_factor, scale_factor, cv::INTER_LINEAR);
    }

    cv::Mat processed_img = preprocess_func(current_frame);
    std::vector<std::vector<cv::Point>> contours;
    std::vector<cv::Vec4i> hierarchy;
    cv::findContours(processed_img, contours, hierarchy, cv::RETR_TREE, cv::CHAIN_APPROX_SIMPLE);

    std::vector<FinderPattern> patterns = findFinderPatterns(contours, hierarchy, min_area);

    // If we scaled the input frame, we need to scale the detected pattern coordinates back
    if (scale_factor != 1.0) {
        for (auto& p : patterns) {
            p.center /= scale_factor;
            p.area /= (scale_factor * scale_factor); // Area scales by the square of the factor
            for (auto& point : p.actual_contour) {
                point.x = static_cast<int>(point.x / scale_factor);
                point.y = static_cast<int>(point.y / scale_factor);
            }
        }
    }
    return patterns;
}


void provideUserGuidance(cv::Mat& displayFrame, const std::vector<FinderPattern>& patterns) {
    const cv::Point2f frameCenter(displayFrame.cols / 2.0f, displayFrame.rows / 2.0f);
    cv::Point textOrg(10, 30);

    if (patterns.empty()) {
        cv::putText(displayFrame, "No QR Code Found", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 0, 255), 2);
        return;
    }

    double totalArea = 0;
    for (const auto& p : patterns) totalArea += p.area;
    double averageArea = totalArea / patterns.size();

    for (const auto& fp : patterns) {
        std::vector<std::vector<cv::Point>> temp_contour_list = { fp.actual_contour };
        cv::drawContours(displayFrame, temp_contour_list, 0, cv::Scalar(255, 0, 0), 2);
    }

    if (averageArea < MIN_AREA_FOR_GUIDANCE) {
        cv::putText(displayFrame, "Move Closer", textOrg, cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 165, 255), 2);
        return;
    }

    cv::Point2f qrCenter(0, 0);
    for (const auto& p : patterns) qrCenter += p.center;
    qrCenter /= (float)patterns.size();
    cv::circle(displayFrame, qrCenter, 7, cv::Scalar(255, 0, 255), -1);

    cv::Point2f delta = frameCenter - qrCenter;
    float deadZoneX = displayFrame.cols * CENTER_DEAD_ZONE_RATIO;
    float deadZoneY = displayFrame.rows * CENTER_DEAD_ZONE_RATIO;
    std::string pos_text = "Position: OK";

    // Prioritize vertical movement (UP/DOWN)
    if (delta.y > deadZoneY) {
        pos_text = "Move Camera UP";
    }
    else if (delta.y < -deadZoneY) {
        pos_text = "Move Camera DOWN";
    }
        // If vertical position is within the dead zone, then check horizontal movement (LEFT/RIGHT)
    else if (delta.x > deadZoneX) {
        pos_text = "Move Camera LEFT";
    }
    else if (delta.x < -deadZoneX) {
        pos_text = "Move Camera RIGHT";
    }

    cv::putText(displayFrame, pos_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 255, 0), 2);
    textOrg.y += 30;

    if (patterns.size() < 3) {
        cv::putText(displayFrame, "Angle: Need 3 corners", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2);
        return;
    }

    for (size_t i = 0; i < patterns.size(); ++i) {
        for (size_t j = i + 1; j < patterns.size(); ++j) {
            for (size_t k = j + 1; k < patterns.size(); ++k) {
                const FinderPattern* p1 = &patterns[i], * p2 = &patterns[j], * p3 = &patterns[k];
                float d12_sq = pow(cv::norm(p1->center - p2->center), 2);
                float d13_sq = pow(cv::norm(p1->center - p3->center), 2);
                float d23_sq = pow(cv::norm(p2->center - p3->center), 2);
                const FinderPattern* tl, * bl, * tr;
                float hyp_sq, a_sq, b_sq;
                if (d12_sq > d13_sq && d12_sq > d23_sq) { hyp_sq = d12_sq; a_sq = d13_sq; b_sq = d23_sq; tl = p3; bl = p2; tr = p1; }
                else if (d13_sq > d12_sq && d13_sq > d23_sq) { hyp_sq = d13_sq; a_sq = d12_sq; b_sq = d23_sq; tl = p2; bl = p3; tr = p1; }
                else { hyp_sq = d23_sq; a_sq = d12_sq; b_sq = d13_sq; tl = p1; bl = p3; tr = p2; }

                if (std::abs(hyp_sq - (a_sq + b_sq)) / hyp_sq < ANGLE_RATIO_TOLERANCE) {
                    float horz_dist = cv::norm(tr->center - tl->center);
                    float vert_dist = cv::norm(bl->center - tl->center);
                    double ratio = horz_dist / vert_dist;
                    std::string angle_text = "Angle: OK";
                    if (ratio > 1.0 + ANGLE_RATIO_TOLERANCE) angle_text = "Rotate Camera RIGHT";
                    else if (ratio < 1.0 - ANGLE_RATIO_TOLERANCE) angle_text = "Rotate Camera LEFT";

                    cv::putText(displayFrame, angle_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 255, 0), 2);
                    cv::line(displayFrame, tl->center, tr->center, cv::Scalar(255, 255, 0), 3);
                    cv::line(displayFrame, tl->center, bl->center, cv::Scalar(255, 255, 0), 3);
                    return;
                }
            }
        }
    }
}


// --- NEW: This is the main entry point that replaces your old main() function ---
std::string processFrameForGuidance(cv::Mat& frame) {
    if (frame.empty()) return "";

    // 1. Process at original scale
    std::vector<FinderPattern> all_patterns = processFrameForFinderPatterns(
            frame, preprocessImage, MIN_FINDER_AREA_ORIGINAL);

    // 2. If needed, try upscaling
    if (all_patterns.size() < 2) {
        std::vector<FinderPattern> patterns_pyramid_scaled = processFrameForFinderPatterns(
                frame, preprocessPyramid, MIN_FINDER_AREA_PYRAMID, PYRAMID_SCALE_FACTOR);
        all_patterns.insert(all_patterns.end(), patterns_pyramid_scaled.begin(), patterns_pyramid_scaled.end());
    }

    // 3. Deduplication
    std::vector<FinderPattern> final_patterns;
    if (!all_patterns.empty()) {
        std::vector<bool> taken(all_patterns.size(), false);
        for (size_t i = 0; i < all_patterns.size(); ++i) {
            if (taken[i]) continue;
            for (size_t j = i + 1; j < all_patterns.size(); ++j) {
                if (taken[j]) continue;
                if (cv::norm(all_patterns[i].center - all_patterns[j].center) < DEDUPE_DISTANCE_THRESH) {
                    taken[j] = true;
                }
            }
            final_patterns.push_back(all_patterns[i]);
        }
    }

    // 4. Provide guidance (draws directly onto the 'frame' Mat)
    provideUserGuidance(frame, final_patterns);

    std::string decoded_text = "";
    if (!final_patterns.empty()) {
        double totalArea = 0;
        for (const auto& p : final_patterns) totalArea += p.area;

        // Check if the QR code's apparent size is within our target range
        if (totalArea >= MIN_DECODING_AREA && totalArea <= MAX_DECODING_AREA) {
            // We are close enough, attempt to decode.
            decoded_text = decodeQRCode(frame);

            if (!decoded_text.empty()) {
                // If successful, draw a confirmation on the screen
                cv::Point textOrg(frame.cols / 2 - 100, frame.rows / 2);
                cv::putText(frame, "DECODED!", textOrg, cv::FONT_HERSHEY_TRIPLEX, 1.5, cv::Scalar(0, 255, 0), 3);
            }
        }
    }

    return decoded_text;
}