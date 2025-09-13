#ifndef QR_INDOORNAV_GUIDANCE_H
#define QR_INDOORNAV_GUIDANCE_H

#include <opencv2/opencv.hpp>

// Declare the function that will be called from our JNI bridge.
// It takes a reference to a Mat object and modifies it in place.
void processFrameForGuidance(cv::Mat& frame);

#endif //QR_INDOORNAV_GUIDANCE_H