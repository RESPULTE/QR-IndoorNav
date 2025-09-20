#include <opencv2/opencv.hpp>

#include "Guidance.h" // Assuming this contains any external declarations for guidance
#include "QRDecoder.h" // Assuming this contains the decodeQRCode(cv::Mat&) function
#include <vector>
#include <cmath>
#include <functional>
#include <chrono>
#include <optional>
#include <algorithm> // For std::min/max

// --- Configuration Constants ---
// Preprocessing
const int ADAPTIVE_THRESH_BLOCK_SIZE = 11;
const int ADAPTIVE_THRESH_C = 7;
const double CLAHE_CLIP_LIMIT = 2.0;

// Decoding Area (These values are based on trial and error and may need tuning)
const double MIN_DECODING_AREA = 0;
const double MAX_DECODING_AREA = 2000000000.0; // Effectively no upper limit for detection, decoding will handle large sizes

// Finder Pattern Validation
const double MIN_FINDER_AREA_ORIGINAL = 25.0; // Lowered to catch very small patterns
const double PYRAMID_SCALE_FACTOR = 2.0; // For upscaling (detects small codes)
const double FINDER_SQUARE_TOLERANCE = 0.45; // Slightly more tolerant for aliasing
const double FINDER_AREA_RATIO_TOLERANCE = 0.6; // Tolerant for distorted small codes

// Adjusted for scale - you might need to tune these more
const double MIN_FINDER_AREA_PYRAMID = MIN_FINDER_AREA_ORIGINAL * (PYRAMID_SCALE_FACTOR / 1.5);

// Guidance System
const double MIN_AREA_FOR_GUIDANCE = 80.0; // Kept this threshold
const float CENTER_DEAD_ZONE_RATIO = 0.25;
const double ANGLE_RATIO_TOLERANCE = 0.30; // More tolerant for distorted small codes
const double DEDUPE_DISTANCE_THRESH = 15.0; // Pixel distance to consider patterns duplicates

// Constants for Temporal Filtering
const int PERSISTENCE_THRESHOLD_FRAMES = 5; // How many frames a candidate must be seen to be confirmed.
const double CANDIDATE_MATCH_THRESHOLD = 50.0; // How close (in pixels) the center of a new QR group needs to be to an old candidate to be considered a match.
const int CONFIRMED_QR_TIMEOUT_MS = 2000; // How long (in milliseconds) we remember a confirmed QR code after it disappears.

// Constants for ROI-based processing
const double TRACKING_ROI_ENLARGE_FACTOR = 1.3; // How much to enlarge the last known QR bounding box for the tracking ROI. 1.2 = 20% larger.
const double CENTRAL_SEARCH_ROI_RATIO = 0.40; // The size of the central search area when no QR is tracked. 0.3 = 30% of the screen.

// Constants for Texture-based QR Candidate Search
const int GRADIENT_BLUR_SIZE = 15; // Size of the blur kernel applied to the gradient map to create an edge density map.
const int EDGE_DENSITY_THRESHOLD = 25; // Threshold to binarize the edge density map. Lower values find more, less dense regions.
const double MIN_QR_ROI_AREA = 1000.0; // Minimum area for a texture blob to be considered a candidate ROI.
const double MAX_QR_ROI_AREA = 400000.0; // Maximum area for a texture blob.
const double QR_ROI_SQUARE_TOLERANCE = 0.4; // How close to a square the ROI's bounding box must be (e.g., 0.4 means width/height is between 0.6 and 1.4).
const double MIN_QR_ROI_FILL_RATIO = 0.3; // The ratio of contour area to bounding box area, to filter out non-solid shapes.


// --- Data Structures ---
struct FinderPattern {
    cv::Point2f center;
    double area;
    std::vector<cv::Point> actual_contour; // Stores the contour points directly

    FinderPattern(cv::Point2f c, double a, const std::vector<cv::Point>& contour_data)
            : center(c), area(a), actual_contour(contour_data) {}
};

// A struct to represent a potential QR code candidate
struct QRCodeCandidate {
    FinderPattern tl, tr, bl; // Top-Left, Top-Right, Bottom-Left corners
    cv::Point2f center;
    double area;
    int frames_seen = 1;
    std::chrono::steady_clock::time_point last_seen_timestamp;

    QRCodeCandidate(const FinderPattern& p_tl, const FinderPattern& p_tr, const FinderPattern& p_bl)
            : tl(p_tl), tr(p_tr), bl(p_bl) {
        center = (tl.center + tr.center + bl.center) / 3.0f;
        area = tl.area + tr.area + bl.area;
        last_seen_timestamp = std::chrono::steady_clock::now();
    }

    // Helper to get all patterns for drawing
    std::vector<FinderPattern> getPatterns() const {
        return {tl, tr, bl};
    }
};


// --- State Variables (static to persist between function calls) ---
static std::vector<QRCodeCandidate> g_candidate_qrs;
static std::optional<QRCodeCandidate> g_last_confirmed_qr;


// --- Preprocessing Functions ---
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


// --- Finder Pattern Detection Functions ---

// Helper function to detect individual finder patterns from contours
std::vector<FinderPattern> findFinderPatterns(const std::vector<std::vector<cv::Point>>& contours, const std::vector<cv::Vec4i>& hierarchy, double min_area) {
    std::vector<FinderPattern> patterns;
    for (int i = 0; i < contours.size(); ++i) {
        int child_idx = hierarchy[i][2];
        if (child_idx == -1) continue;
        int grandchild_idx = hierarchy[child_idx][2];
        if (grandchild_idx == -1) continue;
        if (hierarchy[i][3] != -1) continue; // Must not have a parent (outermost contour)

        double outerArea = cv::contourArea(contours[i]);
        if (outerArea < min_area) continue;

        cv::RotatedRect box = cv::minAreaRect(contours[i]);
        if (box.size.area() == 0 || outerArea / box.size.area() < (1.0 - FINDER_SQUARE_TOLERANCE)) continue;

        double middleArea = cv::contourArea(contours[child_idx]);
        double innerArea = cv::contourArea(contours[grandchild_idx]);
        if (middleArea == 0 || outerArea == 0) continue;
        // Check the area ratios of the concentric squares (0.18 for middle/outer, 0.11 for inner/middle)
        if (std::abs((middleArea / outerArea) - 0.18) > FINDER_AREA_RATIO_TOLERANCE) continue;
        if (std::abs((innerArea / middleArea) - 0.11) > FINDER_AREA_RATIO_TOLERANCE) continue;

        cv::Moments M = cv::moments(contours[i]);
        if (M.m00 == 0) continue;
        patterns.emplace_back(cv::Point2f(M.m10 / M.m00, M.m01 / M.m00), outerArea, contours[i]);
    }
    return patterns;
}

// Processes the entire frame at a given scale factor.
std::vector<FinderPattern> processFullFrameForFinderPatterns(
        const cv::Mat& frame,
        std::function<cv::Mat(const cv::Mat&)> preprocess_func,
        double min_area,
        double scale_factor
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

// This function processes only a specific ROI with scaling
std::vector<FinderPattern> processROIForFinderPatterns(
        const cv::Mat& frame,
        const cv::Rect& roi,
        std::function<cv::Mat(const cv::Mat&)> preprocess_func,
        double min_area,
        double scale_factor
) {
    if (roi.width <= 0 || roi.height <= 0) return {}; // Invalid ROI

    // Extract the ROI from the original frame
    cv::Mat roi_mat = frame(roi);

    // Scale the ROI
    cv::Mat scaled_roi;
    cv::resize(roi_mat, scaled_roi, cv::Size(), scale_factor, scale_factor, cv::INTER_LINEAR);

    // Preprocess the scaled ROI
    cv::Mat processed_roi = preprocess_func(scaled_roi);

    // Find contours and patterns within the processed ROI
    std::vector<std::vector<cv::Point>> contours;
    std::vector<cv::Vec4i> hierarchy;
    cv::findContours(processed_roi, contours, hierarchy, cv::RETR_TREE, cv::CHAIN_APPROX_SIMPLE);

    std::vector<FinderPattern> patterns = findFinderPatterns(contours, hierarchy, min_area);

    // Transform coordinates from scaled ROI space back to original full-frame space
    for (auto& p : patterns) {
        // 1. Scale back to original ROI size
        p.center /= scale_factor;
        p.area /= (scale_factor * scale_factor);
        // 2. Add ROI offset to get coordinates in full frame
        p.center += cv::Point2f(roi.tl());

        for (auto& point : p.actual_contour) {
            point.x = static_cast<int>(point.x / scale_factor) + roi.x;
            point.y = static_cast<int>(point.y / scale_factor) + roi.y;
        }
    }
    return patterns;
}


// New function to find geometrically valid groups of 3 finder patterns
std::vector<QRCodeCandidate> findValidQRGroups(const std::vector<FinderPattern>& patterns) {
    std::vector<QRCodeCandidate> valid_groups;
    if (patterns.size() < 3) return valid_groups;

    for (size_t i = 0; i < patterns.size(); ++i) {
        for (size_t j = i + 1; j < patterns.size(); ++j) {
            for (size_t k = j + 1; k < patterns.size(); ++k) {
                const FinderPattern* p1 = &patterns[i];
                const FinderPattern* p2 = &patterns[j];
                const FinderPattern* p3 = &patterns[k];

                // Calculate squared distances between the 3 points
                double d12_sq = pow(cv::norm(p1->center - p2->center), 2);
                double d13_sq = pow(cv::norm(p1->center - p3->center), 2);
                double d23_sq = pow(cv::norm(p2->center - p3->center), 2);

                const FinderPattern* tl, * bl, * tr; // Top-left, Bottom-left, Top-right
                double hyp_sq, a_sq, b_sq;

                // Identify the hypotenuse (longest side) and the two other sides
                // This logic also attempts to assign Top-Left, Top-Right, Bottom-Left based on distances
                if (d12_sq > d13_sq && d12_sq > d23_sq) { hyp_sq = d12_sq; a_sq = d13_sq; b_sq = d23_sq; tl = p3; bl = p2; tr = p1; }
                else if (d13_sq > d12_sq && d13_sq > d23_sq) { hyp_sq = d13_sq; a_sq = d12_sq; b_sq = d23_sq; tl = p2; bl = p3; tr = p1; }
                else { hyp_sq = d23_sq; a_sq = d12_sq; b_sq = d13_sq; tl = p1; bl = p3; tr = p2; }

                // 1. Check if it's a right-angled triangle (Pythagorean theorem)
                if (std::abs(hyp_sq - (a_sq + b_sq)) / hyp_sq < ANGLE_RATIO_TOLERANCE) {
                    // 2. Check if the two shorter sides are roughly equal length (should form a square)
                    double side_ratio = (std::sqrt(b_sq) > 0) ? std::sqrt(a_sq) / std::sqrt(b_sq) : 1.0;
                    if (std::abs(1.0 - side_ratio) < ANGLE_RATIO_TOLERANCE) {
                        valid_groups.emplace_back(*tl, *tr, *bl);
                    }
                }
            }
        }
    }
    return valid_groups;
}


// Function to find promising ROIs based on texture analysis
std::vector<cv::Rect> findPromisingQRCandidatesByTexture(const cv::Mat& frame) {
    std::vector<cv::Rect> promising_rois;
    cv::Mat gray, grad_x, grad_y, abs_grad_x, abs_grad_y, grad, blurred, thresholded;

    // 1. Convert to grayscale
    cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);

    // 2. Use Scharr operator to find gradients (edges)
    cv::Scharr(gray, grad_x, CV_16S, 1, 0);
    cv::Scharr(gray, grad_y, CV_16S, 0, 1);
    cv::convertScaleAbs(grad_x, abs_grad_x);
    cv::convertScaleAbs(grad_y, abs_grad_y);
    cv::addWeighted(abs_grad_x, 0.5, abs_grad_y, 0.5, 0, grad);

    // 3. Create an edge density map by blurring
    cv::blur(grad, blurred, cv::Size(GRADIENT_BLUR_SIZE, GRADIENT_BLUR_SIZE));

    // 4. Threshold the density map to get blobs
    cv::threshold(blurred, thresholded, EDGE_DENSITY_THRESHOLD, 255, cv::THRESH_BINARY);

    // 5. Find contours of the high-density blobs
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(thresholded, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

    // 6. Filter contours to find QR-like rectangles
    for (const auto& contour : contours) {
        cv::Rect bbox = cv::boundingRect(contour);
        double area = bbox.area();

        // Filter by area
        if (area < MIN_QR_ROI_AREA || area > MAX_QR_ROI_AREA) continue;

        // Filter by aspect ratio (square-ness)
        double aspect_ratio = static_cast<double>(bbox.width) / bbox.height;
        if (std::abs(1.0 - aspect_ratio) > QR_ROI_SQUARE_TOLERANCE) continue;

        // Filter by fill ratio (solidity) - checks if the contour fills its bounding box sufficiently
        double contour_area = cv::contourArea(contour);
        if (contour_area / area < MIN_QR_ROI_FILL_RATIO) continue;

        promising_rois.push_back(bbox);
    }

    return promising_rois;
}


// --- User Guidance Function ---
void provideUserGuidance(cv::Mat& displayFrame, const std::optional<QRCodeCandidate>& current_qr, const std::optional<QRCodeCandidate>& last_known_qr) {
    const cv::Point2f frameCenter(displayFrame.cols / 2.0f, displayFrame.rows / 2.0f);
    cv::Point textOrg(10, 30);

    if (!current_qr && !last_known_qr) {
        cv::putText(displayFrame, "No QR Code Found", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 0, 255), 2);
        return;
    }

    // Determine which QR data to use for guidance (prefer current over last known)
    const QRCodeCandidate& guidance_qr = current_qr ? *current_qr : *last_known_qr;

    // Draw based on whether the QR is currently visible or just remembered
    if (current_qr) {
        // Draw solid lines for currently visible QR
        for (const auto& fp : guidance_qr.getPatterns()) {
            std::vector<std::vector<cv::Point>> temp_contour_list = { fp.actual_contour };
            cv::drawContours(displayFrame, temp_contour_list, 0, cv::Scalar(0, 255, 0), 3); // Green for confirmed
        }
        // Draw the connecting lines
        cv::line(displayFrame, guidance_qr.tl.center, guidance_qr.tr.center, cv::Scalar(255, 255, 0), 3);
        cv::line(displayFrame, guidance_qr.tl.center, guidance_qr.bl.center, cv::Scalar(255, 255, 0), 3);
    } else {
        // Draw text indicating QR is lost, and a ghosted box for the last known position
        cv::putText(displayFrame, "QR Lost! Last seen here:", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2);
        textOrg.y += 30;
        cv::rectangle(displayFrame,
                      cv::Point(guidance_qr.center.x - 50, guidance_qr.center.y - 50),
                      cv::Point(guidance_qr.center.x + 50, guidance_qr.center.y + 50),
                      cv::Scalar(0, 165, 255), 3, cv::LINE_AA); // Orange box
    }

    // Guidance logic (distance, position, angle)
    if (guidance_qr.area < MIN_AREA_FOR_GUIDANCE * 3) { // Area is sum of 3 patterns, so multiply threshold
        cv::putText(displayFrame, "Move Closer", textOrg, cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 165, 255), 2);
        return;
    }

    cv::circle(displayFrame, guidance_qr.center, 7, cv::Scalar(255, 0, 255), -1);

    cv::Point2f delta = frameCenter - guidance_qr.center;
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

    // Angle guidance
    float horz_dist = cv::norm(guidance_qr.tr.center - guidance_qr.tl.center);
    float vert_dist = cv::norm(guidance_qr.bl.center - guidance_qr.tl.center);
    double ratio = (vert_dist > 0) ? horz_dist / vert_dist : 1.0;
    std::string angle_text = "Angle: OK";
    if (ratio > 1.0 + ANGLE_RATIO_TOLERANCE) angle_text = "Rotate Camera RIGHT";
    else if (ratio < 1.0 - ANGLE_RATIO_TOLERANCE) angle_text = "Rotate Camera LEFT";

    cv::putText(displayFrame, angle_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 255, 0), 2);
}


// --- Main Entry Point ---
std::string processFrameForGuidance(cv::Mat& frame) {
    if (frame.empty()) return "";

    // 1. Always perform a fast scan on the full original image (catches large, obvious QR codes)
    std::vector<FinderPattern> all_patterns = processFullFrameForFinderPatterns(
            frame, preprocessImage, MIN_FINDER_AREA_ORIGINAL, 1.0);

    // 2. Determine ROIs for targeted, scaled search based on current state
    std::vector<cv::Rect> search_rois;
    if (g_last_confirmed_qr) {
        // --- TRACKING MODE ---
        // If we have a confirmed QR, create an enlarged bounding box around its last known position
        const auto& qr = *g_last_confirmed_qr;
        float min_x = std::min({qr.tl.center.x, qr.tr.center.x, qr.bl.center.x});
        float max_x = std::max({qr.tl.center.x, qr.tr.center.x, qr.bl.center.x});
        float min_y = std::min({qr.tl.center.y, qr.tr.center.y, qr.bl.center.y});
        float max_y = std::max({qr.tl.center.y, qr.tr.center.y, qr.bl.center.y});

        cv::Rect bbox(cv::Point(min_x, min_y), cv::Point(max_x, max_y));

        // Enlarge the bounding box to create the search ROI
        int new_w = static_cast<int>(bbox.width * TRACKING_ROI_ENLARGE_FACTOR);
        int new_h = static_cast<int>(bbox.height * TRACKING_ROI_ENLARGE_FACTOR);
        int new_x = bbox.x - (new_w - bbox.width) / 2;
        int new_y = bbox.y - (new_h - bbox.height) / 2;

        cv::Rect enlarged_roi(new_x, new_y, new_w, new_h);

        // Ensure the ROI is within the frame boundaries
        search_rois.push_back(enlarged_roi & cv::Rect(0, 0, frame.cols, frame.rows));
    } else {
        // --- SEARCHING MODE ---
        // If no QR is confirmed, first try to find promising regions based on texture analysis
        search_rois = findPromisingQRCandidatesByTexture(frame);

        // If the texture search yields no promising ROIs, fall back to a central screen area
        if (search_rois.empty()) {
            int roi_w = static_cast<int>(frame.cols * CENTRAL_SEARCH_ROI_RATIO);
            int roi_h = static_cast<int>(frame.rows * CENTRAL_SEARCH_ROI_RATIO);
            search_rois.push_back(cv::Rect((frame.cols - roi_w) / 2, (frame.rows - roi_h) / 2, roi_w, roi_h));
        }
    }

    // Process all determined ROIs for finder patterns using upscaling
    for (const auto& roi : search_rois) {
        if (roi.area() > 0) { // Ensure ROI is valid before processing
            std::vector<FinderPattern> scaled_patterns = processROIForFinderPatterns(
                    frame, roi, preprocessPyramid, MIN_FINDER_AREA_PYRAMID, PYRAMID_SCALE_FACTOR
            );
            all_patterns.insert(all_patterns.end(), scaled_patterns.begin(), scaled_patterns.end());
            // For debugging: draw the search ROI
            // cv::rectangle(frame, roi, cv::Scalar(0, 255, 255), 2);
        }
    }

    // 3. Deduplicate raw finder patterns from all sources (full frame + all ROIs)
    std::vector<FinderPattern> final_patterns;
    if (!all_patterns.empty()) {
        std::vector<bool> taken(all_patterns.size(), false);
        for (size_t i = 0; i < all_patterns.size(); ++i) {
            if (taken[i]) continue;
            for (size_t j = i + 1; j < all_patterns.size(); ++j) {
                if (!taken[j] && cv::norm(all_patterns[i].center - all_patterns[j].center) < DEDUPE_DISTANCE_THRESH) {
                    taken[j] = true;
                }
            }
            final_patterns.push_back(all_patterns[i]);
        }
    }

    // 4. Find valid QR groups from the final patterns and apply temporal filtering
    std::vector<QRCodeCandidate> current_frame_groups = findValidQRGroups(final_patterns);

    std::vector<bool> matched_current(current_frame_groups.size(), false);
    auto now = std::chrono::steady_clock::now();

    // Match current frame groups with existing candidates
    for (auto& old_candidate : g_candidate_qrs) {
        bool found_match = false;
        for (size_t i = 0; i < current_frame_groups.size(); ++i) {
            if (matched_current[i]) continue; // Skip if this current group already matched

            if (cv::norm(old_candidate.center - current_frame_groups[i].center) < CANDIDATE_MATCH_THRESHOLD) {
                // Matched! Update the old candidate's data but increment its frames_seen count
                old_candidate.center = current_frame_groups[i].center;
                old_candidate.tl = current_frame_groups[i].tl;
                old_candidate.tr = current_frame_groups[i].tr;
                old_candidate.bl = current_frame_groups[i].bl;
                old_candidate.area = current_frame_groups[i].area;
                old_candidate.frames_seen++;
                old_candidate.last_seen_timestamp = now;

                matched_current[i] = true;
                found_match = true;
                break;
            }
        }
    }

    // Add any new, unmatched groups from this frame to the candidate list
    for (size_t i = 0; i < current_frame_groups.size(); ++i) {
        if (!matched_current[i]) {
            g_candidate_qrs.push_back(current_frame_groups[i]);
        }
    }

    // Prune old candidates that haven't been seen recently (e.g., within 500ms)
    g_candidate_qrs.erase(std::remove_if(g_candidate_qrs.begin(), g_candidate_qrs.end(),
                                         [&](const QRCodeCandidate& cand) {
                                             auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - cand.last_seen_timestamp).count();
                                             return elapsed > 500;
                                         }), g_candidate_qrs.end());

    // Find the best confirmed candidate (the one seen for enough frames)
    std::optional<QRCodeCandidate> best_candidate;
    int max_frames_seen = 0;
    for (const auto& cand : g_candidate_qrs) {
        if (cand.frames_seen >= PERSISTENCE_THRESHOLD_FRAMES) {
            if (cand.frames_seen > max_frames_seen) { // Prioritize the candidate seen most consistently
                best_candidate = cand;
                max_frames_seen = cand.frames_seen;
            }
        }
    }

    // 5. Update our "memory" of the last confirmed QR code
    if (best_candidate) {
        g_last_confirmed_qr = best_candidate;
    } else {
        // If we had a confirmed QR but lost it, check if the timeout has expired
        if (g_last_confirmed_qr) {
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - g_last_confirmed_qr->last_seen_timestamp).count();
            if (elapsed > CONFIRMED_QR_TIMEOUT_MS) {
                g_last_confirmed_qr.reset(); // Forget the QR after the timeout
            }
        }
    }

    // 6. Provide user guidance (draws directly onto the 'frame' Mat)
    provideUserGuidance(frame, best_candidate, g_last_confirmed_qr);

    // 7. Attempt to decode ONLY if we have a confirmed, visible QR code
    std::string decoded_text = "";
    if (best_candidate) {
        double totalArea = best_candidate->area; // Area is sum of 3 patterns
        // Check if the QR code's apparent size is within our target range for decoding
        if (totalArea >= MIN_DECODING_AREA && totalArea <= MAX_DECODING_AREA) {
            decoded_text = decodeQRCode(frame); // Call the external decoder

            if (!decoded_text.empty()) {
                // If successful, draw a confirmation on the screen
                cv::Point textOrg(frame.cols / 2 - 100, frame.rows / 2);
                cv::putText(frame, "DECODED!", textOrg, cv::FONT_HERSHEY_TRIPLEX, 1.5, cv::Scalar(0, 255, 0), 3);
            }
        }
    }

    return decoded_text;
}