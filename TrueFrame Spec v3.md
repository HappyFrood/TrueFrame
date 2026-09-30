# TrueFrame — Android App Specification

**Version:** 0.4.0 (README `v4.0`)
**Date:** 2026-09-30
**Status:** Implemented

---

## 1. Product summary

An offline Android app for coaches and athletes to open a video from the device gallery, step through it frame by frame, and draw annotations (lines, angles, circles, arrows, text) on top of the video. Supports high-frame-rate sports footage (60/120/240 fps).

Analysis tools (v0.4):

- **Ghost Frame** — onion-skin overlay of a captured reference frame, adjustable opacity, blended into shared images.
- **Speed Calculator** — calibrated ball speed in mph from a reference object of known length (capture-fps aware).
- **Grid** — screen-aligned alignment grid over the video.

The editor layout maximizes the video: compact header, scrub/transport/tool rows, Focus mode, and a landscape side panel.

---

## 2. Target platform & stack

| Item | Choice | Rationale |
|---|---|---|
| Language | Kotlin 2.2 | Modern idiomatic Kotlin |
| Min SDK | **33 (Android 13)** | Targets modern Android devices. |
| Target SDK | 36 | Play Store requirement |
| UI | Jetpack Compose + Material 3 | Custom `Canvas` overlay for vector annotations |
| Navigation | Navigation 3 (`androidx.navigation3`) | Scoped ViewModels & saved state per entry |
| Video | **Media3 ExoPlayer** + **ProxyTranscoder** (remux, no re-encode) | Accelerated playback; video track remuxed into app storage with color/HDR metadata preserved |
| Persistence | Room (SQLite) | Projects + annotations + tags |
| DI | Hilt | Unidirectional data flow |
| Async | Coroutines + Flow + **Foreground Service** | Reliable background proxy remux |

### Module layout

```text
:app                 // Activity, Navigation 3, screens, ViewModels, TranscodeService, FrameExporter
:core:video          // ProxyTranscoder (remux), TranscodeBus, FrameMath, VideoProbe
:core:annotation     // Shape models, geometry/measurement math, renderer, hit-testing, SpeedMath
:core:designsystem   // Subdued dark theme, tokens, Material 3 styling
:data                // Room entities/DAOs, repositories, proxy cache manager, JSON I/O
```
