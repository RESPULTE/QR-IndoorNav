#include <jni.h>
#include <opencv2/opencv.hpp>
#include "Guidance.h" // Include our refactored guidance code

extern "C"
JNIEXPORT void JNICALL
Java_com_example_qr_1indoornav_QRScannerActivity_processFrame(JNIEnv *env, jobject thiz, jlong mat_addr) {
// Cast the 'long' address passed from Java back to a cv::Mat pointer
cv::Mat& frame = *(cv::Mat*)mat_addr;

// Call the main processing function from Guidance.cpp
processFrameForGuidance(frame);
}