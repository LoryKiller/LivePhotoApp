# LivePhotoApp - Android Motion Photo Viewer

A robust and lightweight Android application designed to extract and play **Motion Photos** (Google/Samsung/iOS style) with a smooth, iOS-inspired "Live" effect.

## Features

-   **Dynamic Extraction**: Supports GContainer (Google), MicroVideo (Legacy), and Samsung-style motion photo metadata extraction.
-   **Smooth UI**: Features a high-fidelity "Live" effect where the video fades in over the static image when held.
-   **Media3 Integration**: Powered by ExoPlayer (Media3) for efficient video decoding.
-   **Modern Stack**: Built with Kotlin, ViewBinding, Glide, and Coroutine-ready logic.
-   **Optimized Performance**: Efficient byte-level searching and smart cache management.

## How it Works

1.  The app scans the selected JPEG for embedded MP4 data using multiple semantic markers (`MotionPhoto`, `MicroVideoOffset`, `ftyp`).
2.  The video stream is extracted to a temporary cache file.
3.  The UI displays the static image using **Glide**.
4.  On long-press, the app seamlessly overlays the **ExoPlayer** video with a smooth alpha transition.

## Project Structure

-   `MainActivity.kt`: Handles file selection (Action Open Document / Get Content) and permission requests.
-   `LivePhotoViewerActivity.kt`: Manages the playback lifecycle, UI transitions, and ExoPlayer instance.
-   `MotionPhotoHelper.kt`: The core engine for identifying and extracting video buffers from JPEG containers.

## Getting Started

### Prerequisites

-   Android Studio Jellyfish or newer.
-   Minimum SDK: 24 (Android 7.0).
-   Target SDK: 35 (Android 15).

### Installation & Build

1.  **Clone the repository**:
    ```bash
    git clone https://github.com/LoryKiller/LivePhotoApp.git
    ```
2.  **Open in Android Studio**:
    *   Go to `File -> Open` and select the `LivePhotoApp` folder.
    *   Wait for Gradle to sync.
3.  **Build via CLI** (Optional):
    If you have JDK 17 installed, you can build the APK directly:
    ```bash
    ./gradlew assembleDebug
    ```
    The APK will be located in `app/build/outputs/apk/debug/`.

4.  **Run on Device**:
    *   Connect your Android device.
    *   Click the **Run** button in Android Studio.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
