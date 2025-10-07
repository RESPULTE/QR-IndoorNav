# Project Title

**Indoor Navigation Android Application For Visually Impaired (VI) Persons**

## Important Note
- The current prototype is limited to **UTAR FICT Block N**.
- Please update the `build.gradle` file with the following statements: `-DOpenCV_DIR` and `jniLibs.srcDirs`, pointing to your own OpenCV Android folder directory.
- The following software and device requirements must be met before running the code:

### Software Tools

| **Android Studio (IDE)** | Giraffe (2022.3.1) or newer |
| ------------------------ | --------------------------- |
| **Java**                 | Java 17 or newer            |
| **CMake**                | 3.22.1 or newer             |
| **C++ (OpenCV)**         | C++17                       |
|                          |                             |

### Android Phone Specification
- Android 12.0 (API 26) or higher


## Special Features
- User-friendly interface.
- Audio guidance and haptic feedback.
- QR Code pre-processing before decoding (sharpening and deblurring).

## Future Work
- User-friendly map initialization beyond UTAR FICT Ground Floor.
- Dynamic rerouting when navigating to an incorrect location.
- 3D audio feedback for orientation deviation (via headphones).

