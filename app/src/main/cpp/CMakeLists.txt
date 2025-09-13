cmake_minimum_required(VERSION 3.22.1)

project("qr_indoornav")

# This command finds the OpenCV libraries using the path we provided in build.gradle
find_package(OpenCV REQUIRED)

# Add our C++ source files to the library
add_library(qr_indoornav SHARED
        native-lib.cpp
        Guidance.cpp)

# Link our library against the OpenCV libraries
target_link_libraries(qr_indoornav ${OpenCV_LIBS})