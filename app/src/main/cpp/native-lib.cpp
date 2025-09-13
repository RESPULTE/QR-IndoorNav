#include <jni.h>
#include <opencv2/opencv.hpp>
#include "Guidance.h"

extern "C"
JNIEXPORT void JNICALL
Java_com_example_qr_1indoornav_QRScannerActivity_processFrame(JNIEnv *env, jobject thiz, jlong mat_addr) {
    cv::Mat& frame = *(cv::Mat*)mat_addr;

    // Call your existing processing function. This draws BGR contours on the BGR frame.
    processFrameForGuidance(frame);

    // --- NEW: Convert the final processed frame from BGR to RGBA ---
    // This ensures the colors are correct when it's converted to an Android Bitmap.
    cv::cvtColor(frame, frame, cv::COLOR_BGR2RGBA);
}