cmake_minimum_required(VERSION 3.22.1)
project("qr_indoornav")

find_package(OpenCV REQUIRED)

# Add the new QRDecoder.cpp file to the library sources
add_library(qr_indoornav SHARED
        native-lib.cpp
        Guidance.cpp
        QRDecoder.cpp) # <-- ADD THIS LINE

target_link_libraries(qr_indoornav ${OpenCV_LIBS})