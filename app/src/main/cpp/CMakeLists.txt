# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.22.1)

project("qr_indoornav")

# This command finds the OpenCV libraries using the path we provided in build.gradle
find_package(OpenCV REQUIRED)

# Add our C++ source files to the library
add_library(qr_indoornav SHARED
        native-lib.cpp
        Guidance.cpp)

# --- ADD THIS BLOCK ---
# This is the crucial missing part. It tells the compiler where to find header files.
# ${OpenCV_INCLUDE_DIRS} is a variable set by find_package() that points to the OpenCV headers.
# ${CMAKE_CURRENT_SOURCE_DIR} points to the current directory (your 'cpp' folder),
# so it can find your own "Guidance.h".
target_include_directories(qr_indoornav PRIVATE ${OpenCV_INCLUDE_DIRS} ${CMAKE_CURRENT_SOURCE_DIR})
# --- END OF ADDED BLOCK ---

# Link our library against the OpenCV libraries
target_link_libraries(qr_indoornav ${OpenCV_LIBS})