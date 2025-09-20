#include <opencv2/opencv.hpp>

#include "Guidance.h"
#include "QRDecoder.h"
#include <vector>
#include <cmath>
#include <functional>
#include <chrono>
#include <optional>
#include <algorithm>

// --- Configuration Constants ---
// Preprocessing
const int ADAPTIVE_THRESH_BLOCK_SIZE = 11;
const int ADAPTIVE_THRESH_C = 7;
const double CLAHE_CLIP_LIMIT = 2.0;

// Finder Pattern Validation
const double MIN_FINDER_AREA_ORIGINAL = 25.0;
const double PYRAMID_SCALE_FACTOR = 2.0; // The standard upscale factor
const double FINDER_SQUARE_TOLERANCE = 0.25;
const double FINDER_AREA_RATIO_TOLERANCE = 0.6;
const double MIN_FINDER_AREA_PYRAMID = MIN_FINDER_AREA_ORIGINAL * (PYRAMID_SCALE_FACTOR / 1.5);

// Guidance System
const double MIN_AREA_FOR_GUIDANCE = 80.0;
const float CENTER_DEAD_ZONE_RATIO = 0.25;
const double ANGLE_RATIO_TOLERANCE = 0.30;
const double DEDUPE_DISTANCE_THRESH = 15.0;

// Temporal Filtering & Scoring
const int PERSISTENCE_THRESHOLD_FRAMES = 3;
const double CANDIDATE_MATCH_THRESHOLD = 50.0;
const int CONFIRMED_QR_TIMEOUT_MS = 2000; // General memory of a QR

// ROI-based Processing
const double TRACKING_ROI_ENLARGE_FACTOR = 1.5;
const double CENTRAL_SEARCH_ROI_RATIO = 0.35;

// NEW/MODIFIED: Dynamic "Lost QR" Search
const double DYNAMIC_UPSCALE_START = 2.0; // Start at the normal upscale
const double DYNAMIC_UPSCALE_MAX = 6.0; // Max upscale when searching
const int DYNAMIC_UPSCALE_DURATION_MS = 2000; // Duration of this special search mode

// NEW/MODIFIED: Noisy Region Detection (Relaxed)
const double NOISY_REGION_MIN_AREA = 500.0;
const double NOISY_REGION_MAX_AREA = 80000.0;
const double NOISY_REGION_SQUARENESS_TOL = 0.20; // Lowered from 0.25 to be more tolerant
const double NOISY_REGION_MAX_ASPECT_RATIO = 5.0; // Prevents finding long lines (e.g. 1:5 ratio is max)


// --- Data Structures ---
struct FinderPattern {
    cv::Point2f center; double area; std::vector<cv::Point> actual_contour;
    FinderPattern(cv::Point2f c, double a, const std::vector<cv::Point>& d) : center(c), area(a), actual_contour(d) {}
};

struct QRCodeCandidate {
    FinderPattern tl, tr, bl; cv::Point2f center; double area;
    int frames_seen = 1; double score = 0.0;
    std::chrono::steady_clock::time_point last_seen_timestamp;
    QRCodeCandidate(const FinderPattern& p_tl, const FinderPattern& p_tr, const FinderPattern& p_bl)
            : tl(p_tl), tr(p_tr), bl(p_bl) {
        center = (tl.center + tr.center + bl.center) / 3.0f;
        area = tl.area + tr.area + bl.area;
        last_seen_timestamp = std::chrono::steady_clock::now();
    }
    std::vector<FinderPattern> getPatterns() const { return {tl, tr, bl}; }
};

// --- State Variables ---
static std::vector<QRCodeCandidate> g_candidate_qrs;
static std::optional<QRCodeCandidate> g_last_confirmed_qr;
// NEW/MODIFIED: Timestamp for when a confirmed QR was last seen
static std::optional<std::chrono::steady_clock::time_point> g_time_qr_lost;


// --- Core Detection & Preprocessing Functions (Unchanged from previous version) ---
cv::Mat preprocessImage(const cv::Mat& frame);
cv::Mat preprocessPyramid(const cv::Mat& frame);
std::vector<FinderPattern> findFinderPatterns(const std::vector<std::vector<cv::Point>>&, const std::vector<cv::Vec4i>&, double);
std::vector<FinderPattern> processFullFrameForFinderPatterns(const cv::Mat&, std::function<cv::Mat(const cv::Mat&)>, double, double);
std::vector<FinderPattern> processROIForFinderPatterns(const cv::Mat&, const cv::Rect&, std::function<cv::Mat(const cv::Mat&)>, double, double);
// ... (Paste unchanged definitions for the above functions here) ...
cv::Mat preprocessImage(const cv::Mat& frame) {
    cv::Mat gray, claheImg, thresh; cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(CLAHE_CLIP_LIMIT, cv::Size(8, 8)); clahe->apply(gray, claheImg);
    cv::adaptiveThreshold(claheImg, thresh, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C, cv::THRESH_BINARY_INV, ADAPTIVE_THRESH_BLOCK_SIZE, ADAPTIVE_THRESH_C);
    return thresh;
}
cv::Mat preprocessPyramid(const cv::Mat& frame) {
    cv::Mat gray, claheImg, thresh; cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(CLAHE_CLIP_LIMIT, cv::Size(8, 8)); clahe->apply(gray, claheImg);
    cv::adaptiveThreshold(claheImg, thresh, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C, cv::THRESH_BINARY_INV, ADAPTIVE_THRESH_BLOCK_SIZE * 2 + 1, ADAPTIVE_THRESH_C);
    return thresh;
}
std::vector<FinderPattern> findFinderPatterns(const std::vector<std::vector<cv::Point>>& contours, const std::vector<cv::Vec4i>& hierarchy, double min_area) {
    std::vector<FinderPattern> patterns;
    for (int i = 0; i < contours.size(); ++i) {
        if (hierarchy[i][2] == -1 || hierarchy[hierarchy[i][2]][2] == -1 || hierarchy[i][3] != -1) continue;
        int child_idx = hierarchy[i][2], grandchild_idx = hierarchy[child_idx][2];
        double outerArea = cv::contourArea(contours[i]); if (outerArea < min_area) continue;
        cv::RotatedRect box = cv::minAreaRect(contours[i]); if (box.size.area() == 0 || outerArea / box.size.area() < (1.0 - FINDER_SQUARE_TOLERANCE)) continue;
        double middleArea = cv::contourArea(contours[child_idx]), innerArea = cv::contourArea(contours[grandchild_idx]);
        if (middleArea == 0 || outerArea == 0 || std::abs((middleArea / outerArea) - 0.18) > FINDER_AREA_RATIO_TOLERANCE || std::abs((innerArea / middleArea) - 0.11) > FINDER_AREA_RATIO_TOLERANCE) continue;
        cv::Moments M = cv::moments(contours[i]); if (M.m00 != 0) patterns.emplace_back(cv::Point2f(M.m10 / M.m00, M.m01 / M.m00), outerArea, contours[i]);
    }
    return patterns;
}
std::vector<FinderPattern> processFullFrameForFinderPatterns(const cv::Mat& frame, std::function<cv::Mat(const cv::Mat&)> preprocess_func, double min_area, double scale_factor) {
    cv::Mat current_frame = frame; if (scale_factor != 1.0) cv::resize(frame, current_frame, cv::Size(), scale_factor, scale_factor, cv::INTER_LINEAR);
    cv::Mat processed_img = preprocess_func(current_frame); std::vector<std::vector<cv::Point>> contours; std::vector<cv::Vec4i> hierarchy;
    cv::findContours(processed_img, contours, hierarchy, cv::RETR_TREE, cv::CHAIN_APPROX_SIMPLE);
    std::vector<FinderPattern> patterns = findFinderPatterns(contours, hierarchy, min_area);
    if (scale_factor != 1.0) { for (auto& p : patterns) { p.center /= scale_factor; p.area /= (scale_factor * scale_factor); for (auto& pt : p.actual_contour) { pt.x = static_cast<int>(pt.x / scale_factor); pt.y = static_cast<int>(pt.y / scale_factor); } } } return patterns;
}
std::vector<FinderPattern> processROIForFinderPatterns(const cv::Mat& frame, const cv::Rect& roi, std::function<cv::Mat(const cv::Mat&)> preprocess_func, double min_area, double scale_factor) {
    if (roi.width <= 0 || roi.height <= 0) return {};
    cv::Mat roi_mat = frame(roi), scaled_roi; cv::resize(roi_mat, scaled_roi, cv::Size(), scale_factor, scale_factor, cv::INTER_LINEAR);
    cv::Mat processed_roi = preprocess_func(scaled_roi); std::vector<std::vector<cv::Point>> contours; std::vector<cv::Vec4i> hierarchy;
    cv::findContours(processed_roi, contours, hierarchy, cv::RETR_TREE, cv::CHAIN_APPROX_SIMPLE);
    std::vector<FinderPattern> patterns = findFinderPatterns(contours, hierarchy, min_area);
    for (auto& p : patterns) {
        p.center /= scale_factor; p.area /= (scale_factor * scale_factor); p.center += cv::Point2f(roi.tl());
        for (auto& pt : p.actual_contour) { pt.x = static_cast<int>(pt.x / scale_factor) + roi.x; pt.y = static_cast<int>(pt.y / scale_factor) + roi.y; }
    }
    return patterns;
}


// --- Noisy Region Detection (MODIFIED with relaxed constraints) ---
std::vector<cv::Rect> findNoisyRectangularRegions(const cv::Mat& frame) {
    std::vector<cv::Rect> regions;
    cv::Mat gray, gradient, thresh;
    cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);

    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3));
    cv::morphologyEx(gray, gradient, cv::MORPH_GRADIENT, kernel);
    cv::threshold(gradient, thresh, 50, 255, cv::THRESH_BINARY);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(thresh, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

    for (const auto& c : contours) {
        double area = cv::contourArea(c);
        if (area < NOISY_REGION_MIN_AREA || area > NOISY_REGION_MAX_AREA) continue;

        cv::RotatedRect rRect = cv::minAreaRect(c);
        double rRectArea = rRect.size.width * rRect.size.height;
        if (rRectArea == 0) continue;

        // NEW/MODIFIED: Aspect ratio check to discard line-like shapes
        float w = rRect.size.width, h = rRect.size.height;
        if (w == 0 || h == 0) continue;
        float aspect = std::max(w,h) / std::min(w,h);
        if (aspect > NOISY_REGION_MAX_ASPECT_RATIO) continue;

        // Using relaxed fill-ratio check
        if (area / rRectArea > NOISY_REGION_SQUARENESS_TOL) {
            regions.push_back(cv::boundingRect(c));
        }
    }
    return regions;
}

// --- Scoring and Guidance Functions (Unchanged) ---
std::vector<QRCodeCandidate> findAndScoreQRGroups(const std::vector<FinderPattern>& patterns);
void provideUserGuidance(cv::Mat& displayFrame, const std::optional<QRCodeCandidate>& current_qr, const std::optional<QRCodeCandidate>& last_known_qr);
// ... (Paste unchanged definitions for findAndScoreQRGroups and provideUserGuidance here) ...
std::vector<QRCodeCandidate> findAndScoreQRGroups(const std::vector<FinderPattern>& patterns) {
    std::vector<QRCodeCandidate> valid_groups; if (patterns.size() < 3) return valid_groups;
    for (size_t i = 0; i < patterns.size(); ++i) for (size_t j = i + 1; j < patterns.size(); ++j) for (size_t k = j + 1; k < patterns.size(); ++k) {
                const FinderPattern* p1 = &patterns[i], *p2 = &patterns[j], *p3 = &patterns[k];
                double d12 = cv::norm(p1->center - p2->center), d13 = cv::norm(p1->center - p3->center), d23 = cv::norm(p2->center - p3->center);
                const FinderPattern *tl, *bl, *tr; double hyp_sq, a_sq, b_sq;
                if (d12 > d13 && d12 > d23) { hyp_sq = d12*d12; a_sq = d13*d13; b_sq = d23*d23; tl = p3; bl = p2; tr = p1; }
                else if (d13 > d12 && d13 > d23) { hyp_sq = d13*d13; a_sq = d12*d12; b_sq = d23*d23; tl = p2; bl = p3; tr = p1; }
                else { hyp_sq = d23*d23; a_sq = d12*d12; b_sq = d13*d13; tl = p1; bl = p3; tr = p2; }
                double right_angle_error = std::abs(hyp_sq - (a_sq + b_sq)) / hyp_sq;
                if (right_angle_error < ANGLE_RATIO_TOLERANCE) {
                    double side_ratio = std::sqrt(a_sq) / std::sqrt(b_sq);
                    double squareness_error = std::abs(1.0 - side_ratio);
                    if (squareness_error < ANGLE_RATIO_TOLERANCE) {
                        double geometric_purity = (1.0 - right_angle_error) * (1.0 - squareness_error);
                        QRCodeCandidate candidate(*tl, *tr, *bl); candidate.score = geometric_purity;
                        valid_groups.push_back(candidate);
                    }
                }
            }
    return valid_groups;
}
void provideUserGuidance(cv::Mat& displayFrame, const std::optional<QRCodeCandidate>& current_qr, const std::optional<QRCodeCandidate>& last_known_qr) {
    const cv::Point2f frameCenter(displayFrame.cols/2.0f, displayFrame.rows/2.0f); cv::Point textOrg(10, 30);
    if (!current_qr && !last_known_qr) { cv::putText(displayFrame, "Searching for QR Code...", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2); return; }
    const QRCodeCandidate& guidance_qr = current_qr ? *current_qr : *last_known_qr;
    if (current_qr) {
        cv::Scalar color = (current_qr->frames_seen >= PERSISTENCE_THRESHOLD_FRAMES) ? cv::Scalar(0, 255, 0) : cv::Scalar(0, 255, 255);
        for (const auto& fp : guidance_qr.getPatterns()) { std::vector<std::vector<cv::Point>> t = {fp.actual_contour}; cv::drawContours(displayFrame, t, 0, color, 3); }
        cv::line(displayFrame, guidance_qr.tl.center, guidance_qr.tr.center, cv::Scalar(255, 255, 0), 3);
        cv::line(displayFrame, guidance_qr.tl.center, guidance_qr.bl.center, cv::Scalar(255, 255, 0), 3);
    } else {
        cv::putText(displayFrame, "QR Lost! Searching...", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2); textOrg.y += 30;
        cv::rectangle(displayFrame, cv::boundingRect(guidance_qr.getPatterns()[0].actual_contour), cv::Scalar(0, 165, 255), 2);
    }
    if (guidance_qr.area < MIN_AREA_FOR_GUIDANCE * 3) { cv::putText(displayFrame, "Move Closer", textOrg, cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 165, 255), 2); return; }
    cv::circle(displayFrame, guidance_qr.center, 7, cv::Scalar(255, 0, 255), -1);
    cv::Point2f delta = frameCenter - guidance_qr.center; float dX = displayFrame.cols * CENTER_DEAD_ZONE_RATIO, dY = displayFrame.rows * CENTER_DEAD_ZONE_RATIO; std::string pos_text = "Position: OK";
    if (delta.y > dY) pos_text = "Move UP"; else if (delta.y < -dY) pos_text = "Move DOWN"; else if (delta.x > dX) pos_text = "Move LEFT"; else if (delta.x < -dX) pos_text = "Move RIGHT";
    cv::putText(displayFrame, pos_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 255, 0), 2); textOrg.y += 30;
    float h_dist = cv::norm(guidance_qr.tr.center - guidance_qr.tl.center), v_dist = cv::norm(guidance_qr.bl.center - guidance_qr.tl.center);
    double ratio = (v_dist > 0) ? h_dist / v_dist : 1.0; std::string angle_text = "Angle: OK";
    if (ratio > 1.0 + ANGLE_RATIO_TOLERANCE) angle_text = "Rotate RIGHT"; else if (ratio < 1.0 - ANGLE_RATIO_TOLERANCE) angle_text = "Rotate LEFT";
    cv::putText(displayFrame, angle_text, textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 255, 0), 2);
}


// --- Main Entry Point (MODIFIED with dynamic upscale logic) ---
std::string processFrameForGuidance(cv::Mat& frame) {
    if (frame.empty()) return "";
    auto now = std::chrono::steady_clock::now();

    // 1. Fast scan on the full original image
    std::vector<FinderPattern> all_patterns = processFullFrameForFinderPatterns(frame, preprocessImage, MIN_FINDER_AREA_ORIGINAL, 1.0);

    // 2. Determine Search ROIs and scale factor
    std::vector<cv::Rect> rois_to_search;
    double current_scale_factor = PYRAMID_SCALE_FACTOR;

    // NEW/MODIFIED: Dynamic upscale logic
    if (g_time_qr_lost) {
        auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - *g_time_qr_lost).count();
        if (elapsed_ms < DYNAMIC_UPSCALE_DURATION_MS) {
            // We are in the "desperate search" window
            float progress = static_cast<float>(elapsed_ms) / DYNAMIC_UPSCALE_DURATION_MS;
            current_scale_factor = DYNAMIC_UPSCALE_START + (DYNAMIC_UPSCALE_MAX - DYNAMIC_UPSCALE_START) * progress;
        } else {
            // Timer expired, stop the desperate search
            g_time_qr_lost.reset();
        }
    }

    if (g_last_confirmed_qr) { // Priority 1: Tracking last known position
        const auto& qr = *g_last_confirmed_qr;
        float min_x = std::min({qr.tl.center.x, qr.tr.center.x, qr.bl.center.x}); float max_x = std::max({qr.tl.center.x, qr.tr.center.x, qr.bl.center.x});
        float min_y = std::min({qr.tl.center.y, qr.tr.center.y, qr.bl.center.y}); float max_y = std::max({qr.tl.center.y, qr.tr.center.y, qr.bl.center.y});
        cv::Rect bbox(cv::Point(min_x, min_y), cv::Point(max_x, max_y));
        int new_w = bbox.width * TRACKING_ROI_ENLARGE_FACTOR, new_h = bbox.height * TRACKING_ROI_ENLARGE_FACTOR;
        int new_x = bbox.x - (new_w - bbox.width)/2, new_y = bbox.y - (new_h - bbox.height)/2;
        rois_to_search.push_back(cv::Rect(new_x, new_y, new_w, new_h) & cv::Rect(0, 0, frame.cols, frame.rows));
    }

    std::vector<cv::Rect> noisy_regions = findNoisyRectangularRegions(frame);
    rois_to_search.insert(rois_to_search.end(), noisy_regions.begin(), noisy_regions.end());
    if (rois_to_search.empty()) { // Fallback
        int roi_w = frame.cols * CENTRAL_SEARCH_ROI_RATIO, roi_h = frame.rows * CENTRAL_SEARCH_ROI_RATIO;
        rois_to_search.push_back(cv::Rect((frame.cols - roi_w) / 2, (frame.rows - roi_h) / 2, roi_w, roi_h));
    }

    // 3. Process ROIs with the determined scale factor
    for(const auto& roi : rois_to_search) {
        std::vector<FinderPattern> scaled_patterns = processROIForFinderPatterns(frame, roi, preprocessPyramid, MIN_FINDER_AREA_PYRAMID, current_scale_factor);
        all_patterns.insert(all_patterns.end(), scaled_patterns.begin(), scaled_patterns.end());
    }
    // For debugging: cv::rectangle(frame, rois_to_search[0], cv::Scalar(0, 255, 255), 2);

    // 4. Deduplicate, find groups, and update candidates
    std::vector<FinderPattern> final_patterns;
    // ... (Deduplication logic is unchanged)
    if (!all_patterns.empty()) {
        std::vector<bool> taken(all_patterns.size(), false);
        for (size_t i = 0; i < all_patterns.size(); ++i) { if (taken[i]) continue;
            for (size_t j = i + 1; j < all_patterns.size(); ++j) { if (!taken[j] && cv::norm(all_patterns[i].center-all_patterns[j].center) < DEDUPE_DISTANCE_THRESH) taken[j] = true; }
            final_patterns.push_back(all_patterns[i]);
        }
    }

    std::vector<QRCodeCandidate> current_frame_groups = findAndScoreQRGroups(final_patterns);
    std::vector<bool> matched_current(current_frame_groups.size(), false);
    for (auto& old_c : g_candidate_qrs) { for (size_t i = 0; i < current_frame_groups.size(); ++i) {
            if (!matched_current[i] && cv::norm(old_c.center - current_frame_groups[i].center) < CANDIDATE_MATCH_THRESHOLD) {
                old_c.center=current_frame_groups[i].center; old_c.tl=current_frame_groups[i].tl; old_c.tr=current_frame_groups[i].tr; old_c.bl=current_frame_groups[i].bl; old_c.area=current_frame_groups[i].area;
                old_c.frames_seen++; old_c.last_seen_timestamp=now; old_c.score = current_frame_groups[i].score * old_c.frames_seen;
                matched_current[i] = true; break;
            }
        }}
    for (size_t i = 0; i < current_frame_groups.size(); ++i) if (!matched_current[i]) g_candidate_qrs.push_back(current_frame_groups[i]);
    g_candidate_qrs.erase(std::remove_if(g_candidate_qrs.begin(), g_candidate_qrs.end(), [&](auto& c){return std::chrono::duration_cast<std::chrono::milliseconds>(now-c.last_seen_timestamp).count() > 500;}), g_candidate_qrs.end());

    // 5. Select best candidate
    std::optional<QRCodeCandidate> best_candidate;
    if (!g_candidate_qrs.empty()) {
        auto it = std::max_element(g_candidate_qrs.begin(), g_candidate_qrs.end(), [](auto& a, auto& b){ return a.score < b.score; });
        best_candidate = *it;
    }

    // 6. Update confirmed state and trigger "lost" timer
    bool is_confirmed_visible = best_candidate && best_candidate->frames_seen >= PERSISTENCE_THRESHOLD_FRAMES;
    if (is_confirmed_visible) {
        g_last_confirmed_qr = best_candidate;
        g_time_qr_lost.reset(); // Found it again, so reset the timer
    } else {
        if (g_last_confirmed_qr) { // We had a QR, but it's not confirmed anymore
            if (!g_time_qr_lost) g_time_qr_lost = now; // Start the lost timer
            if (std::chrono::duration_cast<std::chrono::milliseconds>(now - g_last_confirmed_qr->last_seen_timestamp).count() > CONFIRMED_QR_TIMEOUT_MS) {
                g_last_confirmed_qr.reset(); // Forget it completely after a long time
            }
        }
    }

    provideUserGuidance(frame, best_candidate, g_last_confirmed_qr);

    // 7. Decode if confirmed
    std::string decoded_text = "";
    if (is_confirmed_visible) {
        decoded_text = decodeQRCode(frame);
        if (!decoded_text.empty()) {
            cv::Point textOrg(frame.cols / 2 - 100, frame.rows / 2);
            cv::putText(frame, "DECODED!", textOrg, cv::FONT_HERSHEY_TRIPLEX, 1.5, cv::Scalar(0, 255, 0), 3);
        }
    }

    return decoded_text;
}