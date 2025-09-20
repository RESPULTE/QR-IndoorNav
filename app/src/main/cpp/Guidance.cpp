#include <opencv2/opencv.hpp>
#include "Guidance.h"
#include "QRDecoder.h" // Assumed to exist
#include <vector>
#include <cmath>
#include <functional>
#include <chrono>
#include <numeric>
#include <algorithm>

// --- Configuration Constants for easy tuning ---
// Preprocessing
const int ADAPTIVE_THRESH_BLOCK_SIZE = 11;
const int ADAPTIVE_THRESH_C = 7;
const double CLAHE_CLIP_LIMIT = 2.0;

// Decoding Area Constraints
const double MIN_DECODING_AREA = 0;
const double MAX_DECODING_AREA = 20000.0;

// Finder Pattern Validation
const double MIN_FINDER_AREA_ORIGINAL = 25.0;
const double PYRAMID_SCALE_FACTOR = 2.0;
const double MIN_FINDER_AREA_PYRAMID = MIN_FINDER_AREA_ORIGINAL * (PYRAMID_SCALE_FACTOR / 1.5);
const double FINDER_SQUARE_TOLERANCE = 0.55;
const double FINDER_AREA_RATIO_TOLERANCE = 0.6;
const double DEDUPE_DISTANCE_THRESH = 15.0;

// Guidance System
const double MIN_AREA_FOR_GUIDANCE = 20.0;
const float CENTER_DEAD_ZONE_RATIO = 0.25;
const double ANGLE_RATIO_TOLERANCE = 0.30;

// --- NEW: Configuration for Memory, Enhancement, and Scoring ---
const int FRAME_SKIP_INTERVAL = 10; // Process one frame every 5
const double MEMORY_PERSISTENCE_S = 1.5; // ROI persists for 0.8 seconds
const double MEMORY_ROI_SCALE_FACTOR = 1.8; // ROI is 1.8x the size of the found group
const double INITIAL_SEARCH_SCALE = 3.5; // Upscale center by 2.5x when no code is known
const double INITIAL_SEARCH_AREA_RATIO = 0.4; // Check middle 30% of the screen
const double MAX_ENHANCEMENT_SCALE = 6.0; // Max upscaling for a lost QR code
const float ENHANCEMENT_SCALE_STEP = 0.5f; // How much to increase scale each step
const double MIN_NOISE_SCORE_THRESHOLD = 20.0; // Minimum laplacian variance to be considered "noisy"


// --- Data Structures ---
struct FinderPattern {
    cv::Point2f center;
    double area;
    std::vector<cv::Point> actual_contour;

    FinderPattern(cv::Point2f c, double a, const std::vector<cv::Point>& contour_data)
            : center(c), area(a), actual_contour(contour_data) {}
};

// NEW: Represents a group of 1, 2, or 3 patterns that might be a QR code
struct PatternGroup {
    std::vector<FinderPattern> patterns;
    double score = 0.0;
    cv::Rect boundingRect;
    cv::Point2f center = {0, 0};

    PatternGroup() = default;
};

// NEW: Holds the state for the remembered Region of Interest
struct MemoryROI {
    cv::Rect roi;
    std::chrono::steady_clock::time_point last_seen;
    float current_enhancement_scale = 1.0f;
    bool is_active = false;
};

// --- State Variables (kept static to persist between calls) ---
static int frame_counter = 0;
static MemoryROI memory_roi;
static PatternGroup last_best_group;
static cv::Rect last_drawn_roi; // For drawing on skipped frames


// --- Preprocessing Functions (Unchanged) ---
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
    cv::adaptiveThreshold(claheImg, thresh, 255,
                          cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                          cv::THRESH_BINARY_INV, ADAPTIVE_THRESH_BLOCK_SIZE * 2 + 1, ADAPTIVE_THRESH_C);
    return thresh;
}

// --- Finder Pattern Detection (Unchanged) ---
std::vector<FinderPattern> findFinderPatterns(const std::vector<std::vector<cv::Point>>& contours, const std::vector<cv::Vec4i>& hierarchy, double min_area) {
    std::vector<FinderPattern> patterns;
    for (int i = 0; i < contours.size(); ++i) {
        if (hierarchy[i][2] == -1 || hierarchy[i][3] != -1) continue;
        int child_idx = hierarchy[i][2];
        if (hierarchy[child_idx][2] == -1) continue;
        int grandchild_idx = hierarchy[child_idx][2];

        double outerArea = cv::contourArea(contours[i]);
        if (outerArea < min_area) continue;

        cv::RotatedRect box = cv::minAreaRect(contours[i]);
        if (box.size.area() == 0 || outerArea / box.size.area() < (1.0 - FINDER_SQUARE_TOLERANCE)) continue;

        double middleArea = cv::contourArea(contours[child_idx]);
        double innerArea = cv::contourArea(contours[grandchild_idx]);
        if (middleArea == 0 || outerArea == 0) continue;
        if (std::abs((middleArea / outerArea) - 0.18) > FINDER_AREA_RATIO_TOLERANCE || std::abs((innerArea / middleArea) - 0.11) > FINDER_AREA_RATIO_TOLERANCE) continue;

        cv::Moments M = cv::moments(contours[i]);
        if (M.m00 == 0) continue;
        patterns.emplace_back(cv::Point2f(M.m10 / M.m00, M.m01 / M.m00), outerArea, contours[i]);
    }
    return patterns;
}

// --- Multi-scale Processing (Mostly Unchanged) ---
std::vector<FinderPattern> processSubFrameForFinderPatterns(
        const cv::Mat& frame,
        std::function<cv::Mat(const cv::Mat&)> preprocess_func,
        double min_area,
        double scale_factor = 1.0) {
    cv::Mat current_frame = frame;
    if (scale_factor != 1.0) {
        cv::resize(frame, current_frame, cv::Size(), scale_factor, scale_factor, cv::INTER_LINEAR);
    }

    cv::Mat processed_img = preprocess_func(current_frame);
    std::vector<std::vector<cv::Point>> contours;
    std::vector<cv::Vec4i> hierarchy;
    cv::findContours(processed_img, contours, hierarchy, cv::RETR_TREE, cv::CHAIN_APPROX_SIMPLE);

    return findFinderPatterns(contours, hierarchy, min_area);
}


// --- NEW: Helper function to calculate noise/contrast ---
// Calculates the variance of the Laplacian, a good measure of edges and texture.
double calculateNoiseScore(const cv::Mat& gray_roi) {
    if (gray_roi.empty() || gray_roi.cols < 5 || gray_roi.rows < 5) return 0.0;
    cv::Mat laplacian;
    cv::Laplacian(gray_roi, laplacian, CV_64F);
    cv::Scalar mean, stddev;
    cv::meanStdDev(laplacian, mean, stddev);
    return stddev.val[0] * stddev.val[0];
}


// --- NEW: Grouping and Scoring Logic ---
std::vector<PatternGroup> groupAndScorePatterns(const std::vector<FinderPattern>& patterns, const cv::Mat& frame) {
    std::vector<PatternGroup> groups;
    if (patterns.empty()) return groups;

    // Create groups of 1 (base case)
    for (const auto& p : patterns) {
        PatternGroup g;
        g.patterns.push_back(p);
        g.score = 10; // Base score for just existing
        g.boundingRect = cv::boundingRect(p.actual_contour);
        g.center = p.center;
        groups.push_back(g);
    }

    // Create groups of 2
    for (size_t i = 0; i < patterns.size(); ++i) {
        for (size_t j = i + 1; j < patterns.size(); ++j) {
            double area_i = patterns[i].area;
            double area_j = patterns[j].area;
            double ratio = std::min(area_i, area_j) / std::max(area_i, area_j);
            if (ratio > 0.5) { // Must be of similar size
                PatternGroup g;
                g.patterns.push_back(patterns[i]);
                g.patterns.push_back(patterns[j]);
                g.score = 25 + ratio * 10; // Score based on size similarity
                std::vector<cv::Point> combined_contour;
                combined_contour.insert(combined_contour.end(), patterns[i].actual_contour.begin(), patterns[i].actual_contour.end());
                combined_contour.insert(combined_contour.end(), patterns[j].actual_contour.begin(), patterns[j].actual_contour.end());
                g.boundingRect = cv::boundingRect(combined_contour);
                g.center = (patterns[i].center + patterns[j].center) * 0.5f;
                groups.push_back(g);
            }
        }
    }

    // Create groups of 3
    if (patterns.size() >= 3) {
        cv::Mat gray;
        cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);

        for (size_t i = 0; i < patterns.size(); ++i) {
            for (size_t j = i + 1; j < patterns.size(); ++j) {
                for (size_t k = j + 1; k < patterns.size(); ++k) {
                    const FinderPattern* p1 = &patterns[i], *p2 = &patterns[j], *p3 = &patterns[k];

                    // Check size similarity
                    double max_area = std::max({p1->area, p2->area, p3->area});
                    double min_area = std::min({p1->area, p2->area, p3->area});
                    if (min_area / max_area < 0.4) continue;

                    // Check for right-angle geometry
                    float d12_sq = pow(cv::norm(p1->center - p2->center), 2);
                    float d13_sq = pow(cv::norm(p1->center - p3->center), 2);
                    float d23_sq = pow(cv::norm(p2->center - p3->center), 2);
                    float hyp_sq, a_sq, b_sq;
                    if (d12_sq > d13_sq && d12_sq > d23_sq) { hyp_sq = d12_sq; a_sq = d13_sq; b_sq = d23_sq; }
                    else if (d13_sq > d12_sq && d13_sq > d23_sq) { hyp_sq = d13_sq; a_sq = d12_sq; b_sq = d23_sq; }
                    else { hyp_sq = d23_sq; a_sq = d12_sq; b_sq = d13_sq; }

                    if (std::abs(hyp_sq - (a_sq + b_sq)) / hyp_sq < ANGLE_RATIO_TOLERANCE) {
                        PatternGroup g;
                        g.patterns = { *p1, *p2, *p3 };
                        g.score = 50; // High base score for good geometry

                        // Calculate noise score for the enclosed area
                        std::vector<cv::Point2f> centers = {p1->center, p2->center, p3->center};
                        g.boundingRect = cv::boundingRect(centers);
                        cv::Rect clamped_rect = g.boundingRect & cv::Rect(0, 0, gray.cols, gray.rows);

                        if (clamped_rect.area() > 10) {
                            double noise = calculateNoiseScore(gray(clamped_rect));
                            if (noise > MIN_NOISE_SCORE_THRESHOLD) {
                                g.score += 100 + noise; // Huge bonus for being noisy
                            }
                        }

                        std::vector<cv::Point> combined_contour;
                        combined_contour.insert(combined_contour.end(), p1->actual_contour.begin(), p1->actual_contour.end());
                        combined_contour.insert(combined_contour.end(), p2->actual_contour.begin(), p2->actual_contour.end());
                        combined_contour.insert(combined_contour.end(), p3->actual_contour.begin(), p3->actual_contour.end());
                        g.boundingRect = cv::boundingRect(combined_contour);
                        g.center = (p1->center + p2->center + p3->center) / 3.0f;
                        groups.push_back(g);
                    }
                }
            }
        }
    }

    return groups;
}

// --- Guidance Drawing Logic (Unchanged) ---
void provideUserGuidance(cv::Mat& displayFrame, const std::vector<FinderPattern>& patterns) {
    // This function now just handles drawing and text guidance for a given set of patterns
    const cv::Point2f frameCenter(displayFrame.cols / 2.0f, displayFrame.rows / 2.0f);
    cv::Point textOrg(10, 30);

    if (patterns.empty()) {
        cv::putText(displayFrame, "Searching for QR Code...", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2);
        return;
    }

    for (const auto& fp : patterns) {
        std::vector<std::vector<cv::Point>> temp_contour_list = { fp.actual_contour };
        cv::drawContours(displayFrame, temp_contour_list, 0, cv::Scalar(0, 255, 0), 2); // Green for confirmed patterns
    }

    double totalArea = 0;
    for (const auto& p : patterns) totalArea += p.area;
    double averageArea = totalArea / patterns.size();
    if (averageArea < MIN_AREA_FOR_GUIDANCE) {
        cv::putText(displayFrame, "Move Closer", textOrg, cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 165, 255), 2);
        return;
    }

    cv::Point2f qrCenter(0, 0);
    for (const auto& p : patterns) qrCenter += p.center;
    qrCenter /= (float)patterns.size();
    cv::circle(displayFrame, qrCenter, 7, cv::Scalar(255, 0, 255), -1);

    // ... (rest of guidance logic is unchanged) ...
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

    if (patterns.size() < 3) {
        cv::putText(displayFrame, "Angle: Need 3 corners", textOrg, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(0, 165, 255), 2);
        return;
    }
}


// --- Main Entry Point ---
// This is the primary orchestrator for the entire detection process.
std::string processFrameForGuidance(cv::Mat& frame) {
    if (frame.empty()) return "";
    frame_counter++;

    // --- On non-processing frames, just draw the last known state for smooth visuals ---
    if (frame_counter % FRAME_SKIP_INTERVAL != 0) {
        if (memory_roi.is_active) {
            cv::rectangle(frame, last_drawn_roi, cv::Scalar(255, 255, 0), 2); // Draw memory ROI
        }
        if (!last_best_group.patterns.empty()) {
            provideUserGuidance(frame, last_best_group.patterns);
        } else {
            provideUserGuidance(frame, {}); // Show "Searching..."
        }
        return ""; // No processing or decoding on skipped frames
    }

    // --- Processing Frame Logic (every Nth frame) ---
    auto now = std::chrono::steady_clock::now();
    if (memory_roi.is_active) {
        auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - memory_roi.last_seen).count();
        if (elapsed_ms > MEMORY_PERSISTENCE_S * 1000) {
            memory_roi.is_active = false;
            memory_roi.current_enhancement_scale = 1.0f;
        }
    }

    std::vector<FinderPattern> all_patterns;
    PatternGroup best_group;
    cv::Mat search_region = frame;
    cv::Point search_offset(0, 0);
    double processing_scale = 1.0;
    cv::Rect search_roi_rect;

    // 1. Determine Search Strategy
    if (memory_roi.is_active && last_best_group.patterns.empty()) {
        // STRATEGY 1: Enhance remembered ROI. We had a code, but lost it.
        memory_roi.current_enhancement_scale = std::min((float)MAX_ENHANCEMENT_SCALE, memory_roi.current_enhancement_scale + ENHANCEMENT_SCALE_STEP);

        cv::Rect enlarged_roi = memory_roi.roi;
        enlarged_roi.x -= 5; enlarged_roi.y -= 5;
        enlarged_roi.width += 10; enlarged_roi.height += 10;
        search_roi_rect = enlarged_roi & cv::Rect(0, 0, frame.cols, frame.rows);

        if (search_roi_rect.area() > 0) {
            search_region = frame(search_roi_rect);
            cv::resize(search_region, search_region, cv::Size(), memory_roi.current_enhancement_scale, memory_roi.current_enhancement_scale, cv::INTER_CUBIC);
            cv::Mat kernel = (cv::Mat_<float>(3,3) << 0, -1, 0, -1, 5, -1, 0, -1, 0);
            cv::filter2D(search_region, search_region, -1, kernel); // Sharpen
            search_offset = search_roi_rect.tl();
            processing_scale = memory_roi.current_enhancement_scale;
        }
    } else if (!memory_roi.is_active) {
        // STRATEGY 2: Initial Search. No memory, so check the center of the screen.
        int w = frame.cols, h = frame.rows;
        search_roi_rect = cv::Rect(w * (1 - INITIAL_SEARCH_AREA_RATIO) / 2,
                                   h * (1 - INITIAL_SEARCH_AREA_RATIO) / 2,
                                   w * INITIAL_SEARCH_AREA_RATIO,
                                   h * INITIAL_SEARCH_AREA_RATIO);
        search_region = frame(search_roi_rect);
        cv::resize(search_region, search_region, cv::Size(), INITIAL_SEARCH_SCALE, INITIAL_SEARCH_SCALE, cv::INTER_LINEAR);
        search_offset = search_roi_rect.tl();
        processing_scale = INITIAL_SEARCH_SCALE;
    }

    // 2. Find Raw Patterns (either in a sub-region or full frame)
    if (!search_region.empty() && search_region.cols > 0 && search_region.rows > 0) {
        // Use the selected strategy's search region
        all_patterns = processSubFrameForFinderPatterns(search_region, preprocessPyramid, MIN_FINDER_AREA_PYRAMID, 1.0);
    }

    // STRATEGY 3: Full-frame search as a fallback if other strategies fail
    if (all_patterns.empty()) {
        processing_scale = 1.0;
        search_offset = {0,0};
        search_roi_rect = cv::Rect(0,0,frame.cols, frame.rows);
        all_patterns = processSubFrameForFinderPatterns(frame, preprocessImage, MIN_FINDER_AREA_ORIGINAL);
        if (all_patterns.size() < 3) {
            auto pyramid_patterns = processSubFrameForFinderPatterns(frame, preprocessPyramid, MIN_FINDER_AREA_PYRAMID, PYRAMID_SCALE_FACTOR);
            all_patterns.insert(all_patterns.end(), pyramid_patterns.begin(), pyramid_patterns.end());
        }
    }

    // 3. Post-process found patterns (scale back, deduplicate)
    if (!all_patterns.empty()) {
        // Scale coordinates back to original frame if we searched a scaled ROI
        for (auto& p : all_patterns) {
            p.center /= processing_scale;
            p.center += cv::Point2f(search_offset);
            p.area /= (processing_scale * processing_scale);
            for (auto& point : p.actual_contour) {
                point.x = static_cast<int>(point.x / processing_scale) + search_offset.x;
                point.y = static_cast<int>(point.y / processing_scale) + search_offset.y;
            }
        }

        // Deduplicate
        std::vector<FinderPattern> final_patterns;
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
        all_patterns = final_patterns;
    }

    // 4. Group, Score, and Select Best Group
    if (!all_patterns.empty()) {
        auto groups = groupAndScorePatterns(all_patterns, frame);
        if (!groups.empty()) {
            auto best_it = std::max_element(groups.begin(), groups.end(),
                                            [](const auto& a, const auto& b) { return a.score < b.score; });
            best_group = *best_it;
        }
    }

    // 5. Update State and Attempt Decoding
    std::string decoded_text = "";
    if (best_group.score > 20) { // Require at least a group of 2
        last_best_group = best_group;

        // Update memory with the new finding
        cv::Rect group_rect = best_group.boundingRect;
        int w = group_rect.width * MEMORY_ROI_SCALE_FACTOR;
        int h = group_rect.height * MEMORY_ROI_SCALE_FACTOR;
        int x = group_rect.x - (w - group_rect.width) / 2;
        int y = group_rect.y - (h - group_rect.height) / 2;
        memory_roi.roi = cv::Rect(x, y, w, h) & cv::Rect(0, 0, frame.cols, frame.rows);

        memory_roi.last_seen = now;
        memory_roi.is_active = true;
        memory_roi.current_enhancement_scale = 1.0f; // Reset enhancement on success

        // Attempt to decode
        double totalArea = 0;
        for (const auto& p : last_best_group.patterns) totalArea += p.area;
        if (totalArea >= MIN_DECODING_AREA && totalArea <= MAX_DECODING_AREA) {
            decoded_text = decodeQRCode(frame);
            if (!decoded_text.empty()) {
                cv::Point textOrg(frame.cols / 2 - 100, frame.rows / 2);
                cv::putText(frame, "DECODED!", textOrg, cv::FONT_HERSHEY_TRIPLEX, 1.5, cv::Scalar(0, 255, 0), 3);
            }
        }
    } else {
        last_best_group.patterns.clear(); // Clear if no good group was found
    }

    // 6. Final Drawing
    if (memory_roi.is_active) {
        cv::rectangle(frame, memory_roi.roi, cv::Scalar(255, 255, 0), 2); // Yellow for memory ROI
        last_drawn_roi = memory_roi.roi;
    } else if (search_roi_rect.area() > 0 && search_roi_rect.area() < frame.total()*0.9) {
        // Draw the initial search ROI if it was used
        cv::rectangle(frame, search_roi_rect, cv::Scalar(0, 255, 255), 2); // Cyan for initial search ROI
        last_drawn_roi = search_roi_rect;
    }

    // Provide guidance based on the best group found in this frame
    provideUserGuidance(frame, last_best_group.patterns);

    return decoded_text;
}