#include <jni.h>
#include <opencv2/opencv.hpp>
#include "Guidance.h"

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_qr_1indoornav_QRScannerActivity_processFrame(JNIEnv *env, jobject thiz, jlong mat_addr) {
    // Cast the 'long' address from Java back to a cv::Mat pointer
    cv::Mat& frame = *(cv::Mat*)mat_addr;

    // Call the processing function, which draws on the BGR frame and returns a decoded string
    std::string result = processFrameForGuidance(frame);

    // --- THE FIX: ADD THIS LINE BACK ---
    // Before returning to Java, convert the BGR frame (with drawings) to RGBA.
    // This ensures Utils.matToBitmap in Java interprets the color channels correctly.
    cv::cvtColor(frame, frame, cv::COLOR_BGR2RGBA);

    // Convert the C++ std::string to a Java String (jstring) and return it
    return env->NewStringUTF(result.c_str());
}