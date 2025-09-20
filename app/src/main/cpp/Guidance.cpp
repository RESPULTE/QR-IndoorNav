#include <opencv2/opencv.hpp>
#include <iostream> // For std::cout
#include <vector>
#include <cmath>
#include <functional>
#include <algorithm> // For std::sort
#include "Guidance.h"
#include "QRDecoder.h"
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

// This struct will hold a valid group of 3 finder patterns and their properties.
struct QRGroup {
    const FinderPattern* tl; // Top-left corner of the QR code
    const FinderPattern* bl; // Bottom-left corner
    const FinderPattern* tr; // Top-right corner
    double score;            // A measure of how "good" this group is (lower is better)

    QRGroup(const FinderPattern* p1, const FinderPattern* p2, const FinderPattern* p3, double s)
            : tl(p1), bl(p2), tr(p3), score(s) {}
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
    cv::adaptiveThreshold(claheImg, thresh, 255,
                          cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                          cv::THRESH_BINARY_INV, ADAPTIVE_THRESH_BLOCK_SIZE * 2 + 1, ADAPTIVE_THRESH_C);
    return thresh;
}

// Special preprocessing for the downscaled image
cv::Mat preprocessDownscale(const cv::Mat& frame) {
    cv::Mat gray, claheImg, thresh;
    cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(CLAHE_CLIP_LIMIT, cv::Size(8, 8));
    clahe->apply(gray, claheImg);
    cv::adaptiveThreshold(claheImg, thresh, 255,
                          cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                          cv::THRESH_BINARY_INV, ADAPTIVE_THRESH_BLOCK_SIZE, ADAPTIVE_THRESH_C);
    return thresh;
}


// --- NEWLY ADDED FUNCTION ---
// Checks for the B-W-B-W-B pattern by counting transitions along scanlines.
bool passesRunLengthTest(const cv::Mat& gray, const cv::Rect& box) {
    if (box.width <= 5 || box.height <= 5) return false;

    // Ensure the box is within the image boundaries
    if (box.x < 0 || box.y < 0 || box.x + box.width >= gray.cols || box.y + box.height >= gray.rows) return false;

    cv::Point center = box.tl() + cv::Point(box.width / 2, box.height / 2);

    auto checkScanline = [&](int start, int end, bool isHorizontal) {
        int transitions = 0;

        // Check if starting pixel is accessible
        int start_x = isHorizontal ? start : center.x;
        int start_y = isHorizontal ? center.y : start;
        if (start_x < 0 || start_y < 0 || start_x >= gray.cols || start_y >= gray.rows) {
            return false; // Cannot start check, scanline is out of bounds
        }

        bool lastPixelWhite = gray.at<uchar>(start_y, start_x) > 128;

        for (int i = start + 1; i < end; ++i) {
            int current_x = isHorizontal ? i : center.x;
            int current_y = isHorizontal ? center.y : i;

            // Check if current pixel is accessible
            if (current_x < 0 || current_y < 0 || current_x >= gray.cols || current_y >= gray.rows) {
                continue; // Skip out-of-bounds pixels
            }

            bool currentPixelWhite = gray.at<uchar>(current_y, current_x) > 128;
            if (currentPixelWhite != lastPixelWhite) {
                transitions++;
            }
            lastPixelWhite = currentPixelWhite;
        }
        // Expecting 5 runs = 4 transitions (B->W, W->B, B->W, W->B)
        return transitions >= 3 && transitions <= 6; // Allow some tolerance
    };

    bool horizontalPass = checkScanline(box.x, box.x + box.width, true);
    bool verticalPass = checkScanline(box.y, box.y + box.height, false);

    return horizontalPass && verticalPass;
}


// --- MODIFIED FUNCTION ---
// Now accepts the grayscale image to perform the run-length test.
std::vector<FinderPattern> findFinderPatterns(
        const cv::Mat& gray, // MODIFIED: Added gray image parameter
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

        // --- INTEGRATION POINT ---
        // Get an upright bounding box for the scanline test.
        cv::Rect boundingBox = cv::boundingRect(contours[i]);
        if (!passesRunLengthTest(gray, boundingBox)) continue; // Discard if it fails the pattern test
        // --- END INTEGRATION ---

        cv::Moments M = cv::moments(contours[i]);
        if (M.m00 == 0) continue;
        patterns.emplace_back(cv::Point2f(M.m10 / M.m00, M.m01 / M.m00), outerArea, contours[i]);
    }
    return patterns;
}

// --- MODIFIED FUNCTION ---
// Now creates and passes the gray image to findFinderPatterns.
std::vector<FinderPattern> processFrameForFinderPatterns(
        const cv::Mat& frame,
        std::function<cv::Mat(const cv::Mat&)> preprocess_func,
        double min_area,
        double scale_factor = 1.0
) {
    cv::Mat current_frame = frame;
    if (scale_factor != 1.0) {
        cv::resize(frame, current_frame, cv::Size(), scale_factor, scale_factor, cv::INTER_LINEAR);
    }

    // MODIFIED: Create grayscale image needed for run-length test
    cv::Mat gray;
    cv::cvtColor(current_frame, gray, cv::COLOR_BGR2GRAY);

    // Get the binary image for contour finding
    cv::Mat processed_img = preprocess_func(current_frame);

    std::vector<std::vector<cv::Point>> contours;
    std::vector<cv::Vec4i> hierarchy;
    cv::findContours(processed_img, contours, hierarchy, cv::RETR_TREE, cv::CHAIN_APPROX_SIMPLE);

    // MODIFIED: Pass the grayscale image to the finder function
    std::vector<FinderPattern> patterns = findFinderPatterns(gray, contours, hierarchy, min_area);

    if (scale_factor != 1.0) {
        for (auto& p : patterns) {
            p.center /= scale_factor;
            p.area /= (scale_factor * scale_factor);
            for (auto& point : p.actual_contour) {
                point.x = static_cast<int>(point.x / scale_factor);
                point.y = static_cast<int>(point.y / scale_factor);
            }
        }
    }
    return patterns;
}

// --- FULLY CORRECTED FUNCTION ---
void provideUserGuidance(cv::Mat& displayFrame, const std::vector<FinderPattern>& patterns) {
    const cv::Point2f frameCenter(displayFrame.cols / 2.0f, displayFrame.rows / 2.0f);
    cv::Point textOrg(10, 30);

    if (patterns.empty()) {
        cv::putText(displayFrame, "No QR Code Found", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 0, 255), 2);
        return;
    }

    // *** CHANGE: DO NOT DRAW ALL PATTERNS HERE ANYMORE ***
    // We will now only draw the patterns belonging to the single best group later.

    if (patterns.size() < 3) {
        // We can still draw the detected patterns if there are fewer than 3, for user feedback.
        for (const auto& fp : patterns) {
            std::vector<std::vector<cv::Point>> temp_contour_list = { fp.actual_contour };
            cv::drawContours(displayFrame, temp_contour_list, 0, cv::Scalar(255, 0, 0), 2);
        }
        cv::putText(displayFrame, "Angle: Need 3 corners", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2);
        return;
    }

    // --- Start of Grouping and Sorting Logic ---
    std::vector<QRGroup> valid_groups;
    for (size_t i = 0; i < patterns.size(); ++i) {
        for (size_t j = i + 1; j < patterns.size(); ++j) {
            for (size_t k = j + 1; k < patterns.size(); ++k) {
                const FinderPattern* p1 = &patterns[i];
                const FinderPattern* p2 = &patterns[j];
                const FinderPattern* p3 = &patterns[k];

                float d12_sq = pow(cv::norm(p1->center - p2->center), 2);
                float d13_sq = pow(cv::norm(p1->center - p3->center), 2);
                float d23_sq = pow(cv::norm(p2->center - p3->center), 2);

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
        cv::putText(displayFrame, "Angle: Invalid shape", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2);
        return;
    }

    std::sort(valid_groups.begin(), valid_groups.end(), [](const QRGroup& a, const QRGroup& b) {
        return a.score < b.score;
    });

    const QRGroup& best_group = valid_groups[0];
    const FinderPattern* tl = best_group.tl;
    const FinderPattern* bl = best_group.bl;
    const FinderPattern* tr = best_group.tr;

    // --- *** NEW DRAWING LOGIC *** ---
    // Now, draw the contours of ONLY the 3 patterns in the best group.
    std::vector<std::vector<cv::Point>> contours_to_draw;
    contours_to_draw.push_back(tl->actual_contour);
    contours_to_draw.push_back(bl->actual_contour);
    contours_to_draw.push_back(tr->actual_contour);

    // Draw them in a distinct color (green) to show they are the chosen ones.
    cv::drawContours(displayFrame, contours_to_draw, -1, cv::Scalar(0, 255, 0), 2); // -1 draws all contours
    // --- End of New Logic ---

    // Perform all guidance calculations using ONLY the 3 patterns from the best group
    double averageArea = (tl->area + bl->area + tr->area) / 3.0;

    if (averageArea < MIN_AREA_FOR_GUIDANCE) {
        cv::putText(displayFrame, "Move Closer", textOrg, cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 165, 255), 2);
        return;
    }

    cv::Point2f qrCenter = (tl->center + bl->center + tr->center) / 3.0f;
    cv::circle(displayFrame, qrCenter, 7, cv::Scalar(255, 0, 255), -1);

    cv::Point2f delta = frameCenter - qrCenter;
    float deadZoneX = displayFrame.cols * CENTER_DEAD_ZONE_RATIO;
    float deadZoneY = displayFrame.rows * CENTER_DEAD_ZONE_RATIO;
    std::string pos_text = "Position: OK";

    if (delta.y > deadZoneY) pos_text = "Move Camera UP";
    else if (delta.y < -deadZoneY) pos_text = "Move Camera DOWN";
    else if (delta.x > deadZoneX) pos_text = "Move Camera LEFT";
    else if (delta.x < -deadZoneX) pos_text = "Move Camera RIGHT";

    cv::putText(displayFrame, pos_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 255, 0), 2);
    textOrg.y += 30;

    float horz_dist = cv::norm(tr->center - tl->center);
    float vert_dist = cv::norm(bl->center - tl->center);
    double ratio = horz_dist / vert_dist;
    std::string angle_text = "Angle: OK";

    if (ratio > 1.0 + ANGLE_RATIO_TOLERANCE) angle_text = "Rotate Camera RIGHT";
    else if (ratio < 1.0 - ANGLE_RATIO_TOLERANCE) angle_text = "Rotate Camera LEFT";

    cv::putText(displayFrame, angle_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 255, 0), 2);

    // Draw lines only for the single best group
    cv::line(displayFrame, tl->center, tr->center, cv::Scalar(0, 255, 255), 3); // Yellow
    cv::line(displayFrame, tl->center, bl->center, cv::Scalar(0, 255, 255), 3); // Yellow
}

// --- MODIFIED FUNCTION WITH UPSCALING AND SHARPENING ---
std::string processFrameForGuidance(cv::Mat& frame) {
    if (frame.empty()) return "";

    // --- NEW: High-detail central scan for very small QR codes ---
    // 1. Define and extract the middle 50% of the screen (full width).
    int roi_y = frame.rows / 4; // Start at 25% from the top
    int roi_height = frame.rows / 2; // Take 50% of the total height
    cv::Rect central_roi_rect(0, roi_y, frame.cols, roi_height);
    cv::Mat central_roi = frame(central_roi_rect);

    // 2. Upscale the ROI by 4x using a high-quality interpolation method.
    cv::Mat upscaled_roi;
    cv::resize(central_roi, upscaled_roi, cv::Size(), 4.0, 4.0, cv::INTER_CUBIC);

    // 3. Sharpen the upscaled ROI using an unsharp mask technique.
    cv::Mat blurred, sharpened_roi;
    cv::GaussianBlur(upscaled_roi, blurred, cv::Size(0, 0), 3);
    cv::addWeighted(upscaled_roi, 1.5, blurred, -0.5, 0, sharpened_roi);

    // 4. Find finder patterns in this new high-res, sharp image.
    //    The min_area is scaled up to compensate for the 4x image upscale.
    std::vector<FinderPattern> patterns_from_roi = processFrameForFinderPatterns(
            sharpened_roi,
            preprocessPyramid, // This preprocessing is good for scaled images
            MIN_FINDER_AREA_ORIGINAL * 10.0, // Area is scaled by 4^2, this is a tolerant threshold
            1.0 // Pass scale_factor=1.0 as we handle coordinate conversion manually
    );

    // 5. Convert pattern coordinates from the ROI space back to the original frame's space.
    for (auto& p : patterns_from_roi) {
        // Scale down position by 4
        p.center /= 4.0;
        // Add the ROI's top-left offset
        p.center += cv::Point2f(central_roi_rect.x, central_roi_rect.y);
        // Scale down area by 4*4 = 16
        p.area /= 16.0;
        // Transform all points in the contour
        for (auto& point : p.actual_contour) {
            point.x = static_cast<int>(point.x / 4.0) + central_roi_rect.x;
            point.y = static_cast<int>(point.y / 4.0) + central_roi_rect.y;
        }
    }
    // --- END: High-detail central scan ---

    // Now, combine the results with other detection strategies.
    // Start the master list of patterns with the ones we just found from the high-res scan.
    std::vector<FinderPattern> all_patterns = patterns_from_roi;

    // Then, run the standard detection on the original image and add the results.
    std::vector<FinderPattern> patterns_original = processFrameForFinderPatterns(
            frame, preprocessImage, MIN_FINDER_AREA_ORIGINAL);
    all_patterns.insert(all_patterns.end(), patterns_original.begin(), patterns_original.end());

    // Fallback to pyramid scaling if still not enough patterns are found
    if (all_patterns.size() < 3) {
        std::vector<FinderPattern> patterns_pyramid_scaled = processFrameForFinderPatterns(
                frame, preprocessPyramid, MIN_FINDER_AREA_PYRAMID, PYRAMID_SCALE_FACTOR);
        all_patterns.insert(all_patterns.end(), patterns_pyramid_scaled.begin(), patterns_pyramid_scaled.end());
    }

    // --- Deduplication and Final Selection Logic (Unchanged from original) ---
    std::vector<FinderPattern> final_patterns;
    if (!all_patterns.empty()) {
        std::vector<bool> taken(all_patterns.size(), false);
        for (size_t i = 0; i < all_patterns.size(); ++i) {
            if (taken[i]) continue;
            FinderPattern representative = all_patterns[i];
            int count = 1;

            for (size_t j = i + 1; j < all_patterns.size(); ++j) {
                if (taken[j]) continue;
                if (cv::norm(all_patterns[i].center - all_patterns[j].center) < DEDUPE_DISTANCE_THRESH) {
                    representative.center += all_patterns[j].center;
                    representative.area += all_patterns[j].area;
                    count++;
                    taken[j] = true;
                }
            }
            representative.center /= count;
            representative.area /= count;
            final_patterns.push_back(representative);
            taken[i] = true;
        }
    }

    std::string decoded_text = "";
    if (final_patterns.size() >= 3) {
        decoded_text = decodeQRCode(frame);
        if (!decoded_text.empty()) {
            cv::Point textOrg(frame.cols / 2 - 100, frame.rows / 2);
            cv::putText(frame, "DECODED!", textOrg, cv::FONT_HERSHEY_TRIPLEX, 1.5, cv::Scalar(0, 255, 0), 3);
        }
    }

    provideUserGuidance(frame, final_patterns);
    return decoded_text;
}