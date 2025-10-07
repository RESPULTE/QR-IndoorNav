#include <opencv2/opencv.hpp>
#include <iostream> // For std::cout
#include <vector>
#include <cmath>
#include <functional>
#include <algorithm> // For std::sort
#include <chrono>    // <-- ADDED for time tracking
#include "Guidance.h"
#include "QRDecoder.h"

// ---------------------------------
// Done by: Lim Yeu Chuan
// --------------------------------


// --- Configuration Constants for easy tuning ---
// Preprocessing
const int ADAPTIVE_THRESH_BLOCK_SIZE = 11; // Smaller for finer details
const int ADAPTIVE_THRESH_C = 7;
const double CLAHE_CLIP_LIMIT = 2.0;

// These values are based on trial and error and may need tuning for your specific camera/resolution.
// They correspond to the QR code being roughly 0.7-1.0 meters away.
const double MIN_DECODING_AREA = 0;
const double MAX_DECODING_AREA = 200000.0;

// Finder Pattern Validation
const double MIN_FINDER_AREA_ORIGINAL = 25.0; // Lowered to catch very small patterns
const double SCALE_FACTOR = 2.0; // For upscaling (detects small codes)


// Adjusted for scale - you might need to tune these more
const double MIN_FINDER_AREA_PYRAMID = MIN_FINDER_AREA_ORIGINAL * (SCALE_FACTOR / 1.5);

const double FINDER_SQUARE_TOLERANCE = 0.45; // Slightly more tolerant for aliasing
const double FINDER_AREA_RATIO_TOLERANCE = 0.6;

// Guidance System
const double MIN_AREA_FOR_GUIDANCE = 45.0; // Increased threshold to ask user to move closer sooner
const float CENTER_DEAD_ZONE_RATIO = 0.25;
const double ANGLE_RATIO_TOLERANCE = 0.30; // More tolerant for distorted small codes



struct FinderPattern {
    cv::Point2f center;
    double area;
    std::vector<cv::Point> actual_contour; // Stores the contour points directly

    FinderPattern(cv::Point2f c, double a, const std::vector<cv::Point>& contour_data)
            : center(c), area(a), actual_contour(contour_data) {
    }
};

// This struct will hold a valid group of 3 finder patterns and their properties.
struct QRGroup {
    const FinderPattern* tl; // Top-left corner of the QR code
    const FinderPattern* bl; // Bottom-left corner
    const FinderPattern* tr; // Top-right corner
    double score;            // A measure of how "good" this group is (lower is better)

    QRGroup(const FinderPattern* p1, const FinderPattern* p2, const FinderPattern* p3, double s)
            : tl(p1), bl(p2), tr(p3), score(s) {}
};

// --- OPTIMIZED PREPROCESSING FUNCTIONS ---
// They now accept a grayscale image and reuse a static CLAHE object.
cv::Mat preprocessImage(const cv::Mat& gray) {
    static cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(CLAHE_CLIP_LIMIT, cv::Size(8, 8));
    cv::Mat claheImg, thresh;
    clahe->apply(gray, claheImg);
    cv::adaptiveThreshold(claheImg, thresh, 255,
                          cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                          cv::THRESH_BINARY_INV, ADAPTIVE_THRESH_BLOCK_SIZE * 2 + 1, ADAPTIVE_THRESH_C);
    return thresh;
}


// --- OPTIMIZED RUN LENGTH TEST ---
bool passesRunLengthTest(const cv::Mat& gray, const cv::Rect& box) {
    if (box.width <= 5 || box.height <= 5) return false;
    if (box.x < 0 || box.y < 0 || box.x + box.width >= gray.cols || box.y + box.height >= gray.rows) return false;

    cv::Point center = box.tl() + cv::Point(box.width / 2, box.height / 2);

    auto checkScanline = [&](int start, int end, bool isHorizontal) {
        // OPTIMIZATION: Use a stride to sample pixels instead of checking every single one.
        // This is much faster for larger finder patterns.
        int length = end - start;
        int stride = std::max(1, length / 15); // Aim for ~15 samples across the pattern
        int transitions = 0;

        int start_x = isHorizontal ? start : center.x;
        int start_y = isHorizontal ? center.y : start;
        if (start_x < 0 || start_y < 0 || start_x >= gray.cols || start_y >= gray.rows) return false;

        bool lastPixelWhite = gray.at<uchar>(start_y, start_x) > 128;

        for (int i = start + stride; i < end; i += stride) {
            int current_x = isHorizontal ? i : center.x;
            int current_y = isHorizontal ? center.y : i;
            if (current_x < 0 || current_y < 0 || current_x >= gray.cols || current_y >= gray.rows) continue;

            bool currentPixelWhite = gray.at<uchar>(current_y, current_x) > 128;
            if (currentPixelWhite != lastPixelWhite) {
                transitions++;
            }
            lastPixelWhite = currentPixelWhite;
        }
        return transitions >= 3 && transitions <= 6;
    };

    bool horizontalPass = checkScanline(box.x, box.x + box.width, true);
    bool verticalPass = checkScanline(box.y, box.y + box.height, false);

    return horizontalPass && verticalPass;
}

// Accepts grayscale image for run-length test
std::vector<FinderPattern> findFinderPatterns(
        const cv::Mat& gray,
        const std::vector<std::vector<cv::Point>>& contours,
        const std::vector<cv::Vec4i>& hierarchy,
        double min_area
) {
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

        cv::Rect boundingBox = cv::boundingRect(contours[i]);
        if (!passesRunLengthTest(gray, boundingBox)) continue;

        cv::Moments M = cv::moments(contours[i]);
        if (M.m00 == 0) continue;
        patterns.emplace_back(cv::Point2f(M.m10 / M.m00, M.m01 / M.m00), outerArea, contours[i]);
    }
    return patterns;
}


// --- REFACTORED: Now accepts a grayscale image to avoid redundant conversions ---
std::vector<FinderPattern> processFrameForFinderPatterns(
        const cv::Mat& gray_frame,
        std::function<cv::Mat(const cv::Mat&)> preprocess_func,
        double min_area,
        double scale_factor = 1.0
) {
    cv::Mat current_gray = gray_frame;
    if (scale_factor != 1.0) {
        cv::resize(gray_frame, current_gray, cv::Size(), scale_factor, scale_factor, cv::INTER_LINEAR);
    }

    cv::Mat processed_img = preprocess_func(current_gray);

    std::vector<std::vector<cv::Point>> contours;
    std::vector<cv::Vec4i> hierarchy;
    cv::findContours(processed_img, contours, hierarchy, cv::RETR_TREE, cv::CHAIN_APPROX_SIMPLE);

    std::vector<FinderPattern> patterns = findFinderPatterns(current_gray, contours, hierarchy, min_area);

    if (scale_factor != 1.0) {
        double inv_scale_factor = 1.0 / scale_factor;
        for (auto& p : patterns) {
            p.center.x *= inv_scale_factor;
            p.center.y *= inv_scale_factor;
            p.area *= (inv_scale_factor * inv_scale_factor);
            for (auto& point : p.actual_contour) {
                point.x = static_cast<int>(point.x * inv_scale_factor);
                point.y = static_cast<int>(point.y * inv_scale_factor);
            }
        }
    }
    return patterns;
}

// OPTIMIZATION: Inlined helper for fast squared distance calculation
inline float distanceSq(const cv::Point2f& p1, const cv::Point2f& p2) {
    float dx = p1.x - p2.x;
    float dy = p1.y - p2.y;
    return dx * dx + dy * dy;
}


// =========================================================================================
// == AFFECTED CODE SECTION STARTS HERE
// =========================================================================================

/**
 * @brief Provides visual feedback on the frame and returns a string with actionable guidance.
 * @param displayFrame The frame to draw on.
 * @param patterns The list of detected finder patterns.
 * @return A string containing a single, prioritized guidance command (e.g., "Move Closer"). Returns an empty string if no guidance is needed.
 */
std::string provideUserGuidance(cv::Mat& displayFrame, const std::vector<FinderPattern>& patterns) {
    // --- Static variables to track state across function calls ---
    static std::chrono::steady_clock::time_point last_good_alignment_time;
    static bool was_in_good_alignment = false;

    const cv::Point2f frameCenter(displayFrame.cols / 2.0f, displayFrame.rows / 2.0f);
    cv::Point textOrg(10, 30);
    std::string guidance_text = ""; // The text to be spoken

    if (patterns.empty()) {
        was_in_good_alignment = false; // Reset state if no patterns
        cv::putText(displayFrame, "No QR Code Found", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 0, 255), 2);
        return ""; // No actionable guidance
    }

    // --- Logic for 1 or 2 patterns ---
    if (patterns.size() < 3) {
        was_in_good_alignment = false; // Reset state if not enough patterns
        for (const auto& fp : patterns) {
            std::vector<std::vector<cv::Point>> temp_contour_list = { fp.actual_contour };
            cv::drawContours(displayFrame, temp_contour_list, 0, cv::Scalar(255, 0, 0), 2); // Blue for partial detection
        }

        if (patterns.size() == 2) {
            float patterns_center_x = (patterns[0].center.x + patterns[1].center.x) / 2.0f;
            float deadZoneX = displayFrame.cols * 0.20f; // 20% dead zone from each side

            if (patterns_center_x < frameCenter.x - deadZoneX) {
                guidance_text = "Move LEFT";
            } else if (patterns_center_x > frameCenter.x + deadZoneX) {
                guidance_text = "Move RIGHT";
            }

            if (!guidance_text.empty()){
                cv::putText(displayFrame, guidance_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2);
                return guidance_text;
            }
        }

        cv::putText(displayFrame, "Locating corners...", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2);
        return "";
    }

    // --- Logic for 3 or more patterns ---
    std::vector<QRGroup> valid_groups;
    for (size_t i = 0; i < patterns.size(); ++i) {
        for (size_t j = i + 1; j < patterns.size(); ++j) {
            for (size_t k = j + 1; k < patterns.size(); ++k) {
                const FinderPattern* p1 = &patterns[i];
                const FinderPattern* p2 = &patterns[j];
                const FinderPattern* p3 = &patterns[k];

                float d12_sq = distanceSq(p1->center, p2->center);
                float d13_sq = distanceSq(p1->center, p3->center);
                float d23_sq = distanceSq(p2->center, p3->center);

                const FinderPattern* tl, * bl, * tr;
                float hyp_sq, a_sq, b_sq;

                if (d12_sq > d13_sq && d12_sq > d23_sq) {
                    hyp_sq = d12_sq; a_sq = d13_sq; b_sq = d23_sq; tl = p3; bl = p2; tr = p1;
                } else if (d13_sq > d12_sq && d13_sq > d23_sq) {
                    hyp_sq = d13_sq; a_sq = d12_sq; b_sq = d23_sq; tl = p2; bl = p3; tr = p1;
                } else {
                    hyp_sq = d23_sq; a_sq = d12_sq; b_sq = d13_sq; tl = p1; bl = p3; tr = p2;
                }

                if ((a_sq + b_sq) >= hyp_sq * (1.0 - ANGLE_RATIO_TOLERANCE * 2)) {
                    double score = std::abs(hyp_sq - (a_sq + b_sq));
                    valid_groups.emplace_back(tl, bl, tr, score);
                }
            }
        }
    }

    if (valid_groups.empty()) {
        was_in_good_alignment = false; // Reset state if shape is invalid
        cv::putText(displayFrame, "Angle: Invalid shape", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2);
        return "";
    }

    std::sort(valid_groups.begin(), valid_groups.end(), [](const QRGroup& a, const QRGroup& b) {
        return a.score < b.score;
    });

    const QRGroup& best_group = valid_groups[0];
    const FinderPattern* tl = best_group.tl;
    const FinderPattern* bl = best_group.bl;
    const FinderPattern* tr = best_group.tr;

    std::vector<std::vector<cv::Point>> contours_to_draw = {tl->actual_contour, bl->actual_contour, tr->actual_contour};
    cv::drawContours(displayFrame, contours_to_draw, -1, cv::Scalar(0, 255, 0), 2);

    // GUIDANCE PRIORITY 1: DISTANCE
    double averageArea = (tl->area + bl->area + tr->area) / 3.0;
    if (averageArea < MIN_AREA_FOR_GUIDANCE) {
        guidance_text = "Move Closer";
        cv::putText(displayFrame, guidance_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 165, 255), 2);
    } else {
        // GUIDANCE PRIORITY 2: POSITION
        cv::Point2f qrCenter = (tl->center + bl->center + tr->center) / 3.0f;
        cv::circle(displayFrame, qrCenter, 7, cv::Scalar(255, 0, 255), -1);

        cv::Point2f delta = frameCenter - qrCenter;
        float deadZoneX = displayFrame.cols * CENTER_DEAD_ZONE_RATIO;
        float deadZoneY = displayFrame.rows * CENTER_DEAD_ZONE_RATIO;
        std::string pos_text = "Position: OK";

        if (delta.y > deadZoneY) { pos_text = "Move Camera UP"; guidance_text = pos_text; }
        else if (delta.y < -deadZoneY) { pos_text = "Move Camera DOWN"; guidance_text = pos_text; }
        else if (delta.x > deadZoneX) { pos_text = "Move Camera LEFT"; guidance_text = pos_text; }
        else if (delta.x < -deadZoneX) { pos_text = "Move Camera RIGHT"; guidance_text = pos_text; }

        cv::putText(displayFrame, pos_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 255, 0), 2);
        textOrg.y += 30;

        if (guidance_text.empty()) {
            // GUIDANCE PRIORITY 3: ROTATION (ANGLE SKEW)
            float horz_dist = cv::norm(tr->center - tl->center);
            float vert_dist = cv::norm(bl->center - tl->center);
            double ratio = horz_dist / vert_dist;
            std::string angle_text = "Angle: OK";

            if (ratio > 1.0 + ANGLE_RATIO_TOLERANCE) { angle_text = "Rotate Camera RIGHT"; guidance_text = angle_text; }
            else if (ratio < 1.0 - ANGLE_RATIO_TOLERANCE) { angle_text = "Rotate Camera LEFT"; guidance_text = angle_text; }

            cv::putText(displayFrame, angle_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 255, 0), 2);
        }
    }

    cv::line(displayFrame, tl->center, tr->center, cv::Scalar(0, 255, 255), 3);
    cv::line(displayFrame, tl->center, bl->center, cv::Scalar(0, 255, 255), 3);

    // --- NEW LOGIC: GUIDANCE PRIORITY 4: TIMEOUT ---
    bool is_currently_aligned = guidance_text.empty();

    if (is_currently_aligned) {
        if (!was_in_good_alignment) {
            // This is the first frame we are perfectly aligned. Start the timer.
            last_good_alignment_time = std::chrono::steady_clock::now();
            was_in_good_alignment = true;
        } else {
            // We have been aligned. Check if the timeout has been reached.
            auto elapsed_time = std::chrono::steady_clock::now() - last_good_alignment_time;
            if (std::chrono::duration_cast<std::chrono::seconds>(elapsed_time).count() >= 1) {
                // We've been aligned for over 2 seconds without a decode. Provide new guidance.
                guidance_text = "Walk forward slowly";
                // Display it on screen as well for visual feedback
                cv::putText(displayFrame, "Walk forward slowly", {10, displayFrame.rows - 20}, cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 165, 255), 2);
            }
        }
    } else {
        // Alignment is not perfect, so reset the state.
        was_in_good_alignment = false;
    }

    return guidance_text;
}

// =========================================================================================
// == AFFECTED CODE SECTION ENDS HERE
// =========================================================================================

std::string processFrameForGuidance(cv::Mat& frame) {
    if (frame.empty()) return "|";

    // OPTIMIZATION: Convert to grayscale ONCE per frame.
    cv::Mat gray_original;
    cv::cvtColor(frame, gray_original, cv::COLOR_BGR2GRAY);

    std::vector<FinderPattern> patterns = processFrameForFinderPatterns(
            gray_original,
            preprocessImage,
            MIN_FINDER_AREA_ORIGINAL
    );

    if (patterns.size() < 2) {
        // --- High-detail central scan ---
        int roi_y = frame.rows / 4;
        int roi_height = frame.rows / 2;
        cv::Rect central_roi_rect(0, roi_y, frame.cols, roi_height);
        cv::Mat central_roi_gray = gray_original(central_roi_rect);

        cv::Mat upscaled_roi_gray;
        cv::resize(central_roi_gray, upscaled_roi_gray, cv::Size(), 4.0, 4.0, cv::INTER_CUBIC);

        cv::Mat blurred, sharpened_roi_gray;
        cv::GaussianBlur(upscaled_roi_gray, blurred, cv::Size(0, 0), 3);
        cv::addWeighted(upscaled_roi_gray, 1.5, blurred, -0.5, 0, sharpened_roi_gray);

        // Find patterns in the high-res ROI, passing the grayscale image directly.
        patterns = processFrameForFinderPatterns(
                sharpened_roi_gray,
                preprocessImage,
                MIN_FINDER_AREA_ORIGINAL * 10.0
        );

        // Convert ROI coordinates back to original frame space
        for (auto& p : patterns) {
            p.center /= 4.0;
            p.center += cv::Point2f(central_roi_rect.x, central_roi_rect.y);
            p.area /= 16.0;
            for (auto& point : p.actual_contour) {
                point.x = static_cast<int>(point.x / 4.0) + central_roi_rect.x;
                point.y = static_cast<int>(point.y / 4.0) + central_roi_rect.y;
            }
        }
    }


    std::string decoded_text = "";
    if (patterns.size() >= 3) {
        double totalArea = 0;
        for (const auto& p : patterns) totalArea += p.area;

        // Check if the QR code's apparent size is within our target range
        if (totalArea >= MIN_DECODING_AREA && totalArea <= MAX_DECODING_AREA) {
            decoded_text = decodeQRCode(frame);
            if (!decoded_text.empty()) {
                cv::Point textOrg(frame.cols / 2 - 100, frame.rows / 2);
                cv::putText(frame, "DECODED!", textOrg, cv::FONT_HERSHEY_TRIPLEX, 1.5, cv::Scalar(0, 255, 0), 3);
            }
        }
    }

    std::string guidance_message = provideUserGuidance(frame, patterns);

    // Return a combined string with a separator
    return decoded_text + "+" + guidance_message;
}