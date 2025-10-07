#ifndef QR_INDOORNAV_GUIDANCE_H
#define QR_INDOORNAV_GUIDANCE_H
// ---------------------------------
// Done by: Lim Yeu Chuan
// --------------------------------

#include <opencv2/opencv.hpp>
#include <string> // Add string include

// The function now returns the decoded string, and guidance, separated by a pipe character '|'
std::string processFrameForGuidance(cv::Mat& frame);

#endif //QR_INDOORNAV_GUIDANCE_H