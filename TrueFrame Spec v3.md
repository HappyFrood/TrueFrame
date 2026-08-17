# TrueFrame — Android App Specification

**Version:** 0.3
**Date:** 2026-08-16
**Status:** Approved for implementation
**Changes from 0.2:** App renamed to TrueFrame. Min SDK bumped to 33. Transcode pipeline now uses an OpenGL surface to bake in rotation. Added Foreground Service to prevent OS killing transcode. Audio playback added. Tagging added to data model. Removed length measurements and Z-ordering for v1. Added minimum touch targets for annotations and cache reconciliation on startup.

---

## 1. Product summary

An offline Android app for coaches and athletes to open a video from the device gallery, step through it frame by frame, and draw measurement annotations (lines, angles, circles) on top of the picture. Supports normal-speed playback and slow-motion review of high-frame-rate footage (60/120/240 fps).

**Non-goals for v1:** cloud sync, accounts, side-by-side dual video comparison, video trimming, annotated video export (v2).

---

## 2. Target platform & stack

| Item | Choice | Rationale |
|---|---|---|
| Language | Kotlin 2.x | |
| Min SDK | **33 (Android 13)** | Targets devices ≤3 years old. Stable MediaStore, `READ_MEDIA_VIDEO`. |
| Target SDK | 36 | Play Store requirement |
| UI | Jetpack Compose + Material 3 | Custom `Canvas` overlay is the core UI |
| Video | **MediaCodec + OpenGL + MediaMuxer**, **MediaCodec** | No ExoPlayer dependency. OpenGL bakes rotation. |
| Persistence | Room (SQLite) | Projects + annotations + tags |
| DI | Hilt | |
| Async | Coroutines + Flow + **Foreground Service** | Guaranteed transcode execution |

### Module layout

```text
:app                 // Activity, navigation, screens, DI wiring, Foreground Services
:core:video          // ProxyTranscoder, ProxyReader, FrameIndex, PlaybackClock, AudioExtractor
:core:annotation     // Shape models, geometry/measurement math, renderer, hit-testing
:core:designsystem   // Theme, tokens, reusable composables
:data                // Room entities/DAOs, repositories, proxy cache manager, JSON I/O