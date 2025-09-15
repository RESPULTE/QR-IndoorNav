#ifndef QR_INDOORNAV_QRDECODER_H
#define QR_INDOORNAV_QRDECODER_H

#include <opencv2/opencv.hpp>
#include <string>

/**
 * Attempts to detect and decode a QR code from the given frame.
 * @param frame The input image, expected to be grayscale for best performance.
 * @return The decoded string if successful, otherwise an empty string.
 */
std::string decodeQRCode(const cv::Mat& frame);

#endif //QR_INDOORNAV_QRDECODER_H