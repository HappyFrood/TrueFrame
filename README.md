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
