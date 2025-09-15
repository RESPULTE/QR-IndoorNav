#ifndef QR_INDOORNAV_GUIDANCE_H
#define QR_INDOORNAV_GUIDANCE_H

#include <opencv2/opencv.hpp>
#include <string> // Add string include

// The function now returns the decoded string, if any.
std::string processFrameForGuidance(cv::Mat& frame);

#endif //QR_INDOORNAV_GUIDANCE_H