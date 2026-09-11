# TrueFrame 🎥📐

**TrueFrame** is an offline Android application designed for coaches, athletes, and video analysts to review high-frame-rate sports footage, step through videos frame-by-frame, and draw precise measurement annotations (lines, angles, circles) directly over the video frames.

---

## 🎯 Key Objectives & Features

- **Offline High-FPS Video Review**: Smoothly review normal and high-frame-rate videos (60, 120, 240 fps) completely offline.
- **Frame-by-Frame Stepping**: Precise frame navigation (Next Frame, Previous Frame) using custom `PlaybackClock` and `FrameIndex` utilities.
- **Measurement Annotations**:
  - 📏 **Lines**: Distance measurement between two endpoints.
  - 📐 **Angles**: Vertex and ray angle calculation (in degrees).
  - ⭕ **Circles**: Radial geometry overlays.
- **Background Transcoding & Proxy Generation**: Foreground `TranscodeService` automatically generates optimized proxy videos using `ProxyTranscoder` for smooth frame extraction without freezing the main thread.
- **Video Duration Guard**: Automatically checks incoming video length to prevent processing videos longer than 5 minutes.
- **Project Management**: Create, list, track background progress, and delete video analysis projects.

---

## 📌 Mandatory UI & Functional Requirements

The following core UI and interaction behaviors are strictly enforced across the application:

1. **Filmstrip Scrubbing & Performance**:
   - Scrubbing the filmstrip timeline must provide **instantaneous 60fps frame feedback** using keyframe sync (`OPTION_CLOSEST_SYNC`) during drag.
   - Releasing the scrubber resolves exact frame decoding (`OPTION_CLOSEST`).
   - Frame decode requests must cancel in-flight jobs on new seek inputs to prevent UI lag.
   - Reuses an active `MediaMetadataRetriever` session per video with an in-memory `LruCache` for zero-latency cached frame retrieval.

2. **Rotated Video Frame Layout**:
   - Rotating the video (0°, 90°, 180°, 270°) must rotate the underlying bitmap pixel buffer directly.
   - The rotated video frame must scale using `ContentScale.Fit` to fill the maximum screen area at high resolution without shrinking into a small central box.

3. **Two-Row Control Bar & System Insets**:
   - The bottom control panel is organized into two distinct rows:
     - **Row 1**: Filmstrip scrubbing slider, time readout (`X.Xs / Y.Ys`), current frame counter (`Frame N`), and transport controls (`<<`, `<`, `Play/Pause`, `>`, `>>`).
     - **Row 2**: Annotation tools (`📏 Line`, `📐 Angle`, `⭕ Circle`, `🗑️ Delete`, `🧹 Clear`).
   - Bottom bars MUST apply `navigationBarsPadding()` so controls sit well above Android system gesture navigation areas.

4. **Interactive Annotation Manipulation**:
   - **Handle Adjustments**: Touch targets for control points (endpoints, vertex, radius handle) must have a touch slop threshold of at least `120px (~40dp)` for effortless touch accuracy.
   - **Entire Shape Dragging**: Touching anywhere on a shape (line, angle ray, circle boundary) allows dragging the entire annotation shape across the canvas.
   - **Real-Time Measurement Overlay**: Angle values in degrees (`XX.X°`) are rendered dynamically with high-contrast text shadows directly on the canvas.

---

## 🏗️ Project Architecture & Module Structure

The project follows modern Android development best practices with modern Jetpack Compose, Clean Architecture, Hilt Dependency Injection, Room Database, and Navigation 3.

```
TrueFrame/
├── app/                  # Main Android Application module
│   ├── ui/
│   │   ├── main/         # Main Screen (Project List, Video Selection, Transcode Progress)
│   │   └── editor/       # Video Editor Screen (Frame Viewing, Annotation Overlay, Controls)
│   ├── service/          # Foreground Service for background video transcoding
│   ├── di/               # Hilt Dependency Injection modules
│   └── Navigation.kt     # Jetpack Navigation 3 Display configuration
│
├── core/
│   ├── annotation/       # Canvas rendering overlays and geometric annotation shapes (Lines, Angles, Circles)
│   ├── video/            # Video decoding, FrameIndex, PlaybackClock, ProxyReader, ProxyTranscoder
│   └── designsystem/     # Material Design 3 theme, colors, typography
│
└── data/                 # Data layer containing Room DB, DAOs, Entities, Repositories, Cache Management
```

---

## ⚙️ Tech Stack & Key Libraries

- **Language**: Kotlin 2.2+
- **UI Framework**: Jetpack Compose with Material 3
- **Navigation**: Jetpack Navigation 3 (`androidx.navigation3`)
- **Dependency Injection**: Hilt
- **Database & Storage**: Room Database with Coroutines & StateFlow
- **Video Processing**: Android `MediaCodec`, `MediaExtractor`, `MediaMuxer`, `MediaMetadataRetriever`
- **Testing**: JUnit 4, Kotlinx Coroutines Test, Compose UI Testing, Hilt Android Testing

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug or newer
- Android SDK 36 (Minimum SDK 33)
- Java 17

### Building & Running
1. Open the project in **Android Studio**.
2. Sync Gradle dependencies: `./gradlew sync`
3. Run unit tests: `./gradlew check`
4. Deploy to a connected device or emulator:
   ```bash
   ./gradlew :app:installDebug
   ```

---

## 📄 License

TrueFrame is open-source and available under the MIT License.
