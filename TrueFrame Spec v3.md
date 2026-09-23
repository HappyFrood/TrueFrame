# TrueFrame — Android App Specification

**Version:** 0.3.8
**Date:** 2026-09-23
**Status:** Approved & Implemented

---

## 1. Product summary

An offline Android app for coaches and athletes to open a video from the device gallery, step through it frame by frame, and draw measurement annotations (lines, angles, circles) on top of the video. Supports high-frame-rate sports footage (60/120/240 fps).

---

## 2. Target platform & stack

| Item | Choice | Rationale |
|---|---|---|
| Language | Kotlin 2.2 | Modern idiomatic Kotlin |
| Min SDK | **33 (Android 13)** | Targets modern Android devices. |
| Target SDK | 36 | Play Store requirement |
| UI | Jetpack Compose + Material 3 | Custom `Canvas` overlay for vector annotations |
| Navigation | Navigation 3 (`androidx.navigation3`) | Scoped ViewModels & saved state per entry |
| Video | **Media3 ExoPlayer** + **ProxyTranscoder** | Accelerated video playback & proxy generation |
| Persistence | Room (SQLite) | Projects + annotations + tags |
| DI | Hilt | Unidirectional data flow |
| Async | Coroutines + Flow + **Foreground Service** | Reliable background transcoding |

### Module layout

```text
:app                 // Activity, Navigation 3, screens, ViewModels, TranscodeService, FrameExporter
:core:video          // ProxyTranscoder, TranscodeBus, FrameMath
:core:annotation     // Shape models, geometry/measurement math, renderer, hit-testing
:core:designsystem   // Subdued dark theme, tokens, Material 3 styling
:data                // Room entities/DAOs, repositories, proxy cache manager, JSON I/O
```
