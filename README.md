# TrueFrame 🎥📐

An offline Android app for coaches, athletes, and video analysts. Open a video from the device gallery, step through it frame by frame, and draw measurement annotations — lines, angles, circles — directly on the picture. Built for high-frame-rate sports footage (60 / 120 / 240 fps).

**Status:** `v3.9`.

**Non-goals for v1:** cloud sync, accounts, side-by-side comparison, trimming.

---

## Contents

- [Architecture](#architecture)
- [Module layout](#module-layout)
- [Core design contracts](#core-design-contracts)
- [Data model](#data-model)
- [Video pipeline](#video-pipeline)
- [Annotation system & Frame Sharing](#annotation-system--frame-sharing)
- [UI & Theming](#ui--theming)
- [Threading & lifecycle rules](#threading--lifecycle-rules)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)

---

## Architecture

Clean layering, unidirectional data flow, single source of truth per screen.

```
┌─────────────────────────────────────────────────────────┐
│  :app                                                   │
│  MainActivity → MainNavigation (Navigation 3)           │
│    ├── MainScreen      ←→ MainScreenViewModel           │
│    └── EditorScreen    ←→ EditorViewModel               │
│  TranscodeService (foreground)                          │
└───────────────┬─────────────────────────────────────────┘
                │ StateFlow<UiState> down, events up
┌───────────────▼─────────────────────────────────────────┐
│  :data                                                  │
│  ProjectRepository · AnnotationRepository               │
│  Room (projects, annotations, tags) · ProxyCacheManager │
└───────────────┬─────────────────────────────────────────┘
                │
┌───────────────▼──────────────┬──────────────────────────┐
│  :core:video                 │  :core:annotation        │
│  ProxyTranscoder             │  AnnotationShape         │
│  TranscodeBus                │  Geometry / measurement  │
│  FrameMath                   │  AnnotationRenderer      │
│                              │  HitTesting              │
└──────────────────────────────┴──────────────────────────┘
                 :core:designsystem (theme, tokens)
```

**Rules:**

- ViewModels own all state. Composables are pure functions of `UiState` plus event callbacks — no business logic or unmanaged side effects in composable bodies.
- Navigation 3 includes `rememberViewModelStoreNavEntryDecorator` and `rememberSaveableStateHolderNavEntryDecorator` to scope ViewModels and saved state per entry.
- `:core:*` modules are Android-library-only and know nothing about the app or each other.
- `:data` depends on `:core:annotation` (for shape types in JSON I/O) and exposes it via `api`.
- Media3 ExoPlayer is the single playback engine.

---

## Module layout

```text
:app                 Activity, Navigation 3, screens, ViewModels, DI wiring,
                     TranscodeService (foreground service), FrameExporter
:core:video          ProxyTranscoder, TranscodeBus, FrameMath — no Compose, no app types
:core:annotation     Shape models, geometry/measurement math, Canvas renderer,
                     hit-testing. Pure geometry, fully unit-testable.
:core:designsystem   Material 3 theme, color tokens, typography
:data                Room entities/DAOs, repositories, proxy cache manager,
                     annotation JSON serialization
```

---

## Core design contracts

### 1. Annotation coordinates are normalized (0..1), always

Shapes are stored and manipulated in **normalized video-frame space**: `(0,0)` is top-left, `(1,1)` bottom-right. Radii are normalized against frame **width**.

| Layer | Coordinate space |
|---|---|
| `AnnotationShape`, Room, JSON | Normalized 0..1 |
| `EditorViewModel` state | Normalized 0..1 |
| `AnnotationRenderer` / `Canvas` | View pixels |
| Touch input (`change.position`) | View pixels |

Conversion happens at the UI boundary against the fitted video box. Measurements are reported in source-video pixels (`px`), invariant under screen density, window resizes, and orientation rotation.

### 2. Unified frame arithmetic & Position Persistence

All frame calculations, time-to-frame conversions, and seek target calculations use `FrameMath` in `:core:video`:
- `intervalMs(frameRate)` = `1000f / frameRate`
- `frameForMs(timeMs, frameRate)` = `floor(timeMs / interval)`
- `msForFrame(frame, frameRate)` = `(frame + 0.5f) * interval` (mid-frame target seeking prevents millisecond truncation errors)
- **Position Persistence** — Last playback position (`lastPositionMs`) is saved to Room upon exiting or navigating away and restored when reopening the video.

### 3. Project-wide annotations

Annotations are observed project-wide (`annotationDao.observeForProject(projectId)`). Each shape retains its creation `frameIndex`, displayed when selected (e.g., `"drawn on frame X"`).

### 4. Persistence on drag end

Annotation writes update Room on `onDragEnd` via `@Update`, preserving row IDs and keeping frame metadata intact.

---

## Data model

```text
@Entity("projects")
ProjectEntity(id, name, videoUri, proxyUri, transcodeState, rotationDegrees, lastPositionMs, createdAt, updatedAt)

@Entity("annotations", FK -> projects CASCADE, index(projectId, frameIndex))
AnnotationEntity(id, projectId, frameIndex, shapeType, serializedData)

@Entity("tags")             TagEntity(id, name)
@Entity("project_tags")     ProjectTagCrossRef(projectId, tagId)
```

- `serializedData` is kotlinx-serialization JSON of shape geometry.
- `lastPositionMs` stores the last saved playback position in milliseconds.
- `transcodeState` tracks background conversion status (`PENDING`, `RUNNING`, `COMPLETE`, `ERROR`).
- Project names are timestamped by default and editable from both the project list and editor top bar.

---

## Video pipeline

### Import & Transcode

Videos imported via the photo picker are copied into durable app storage (`noBackupFilesDir`). `TranscodeService` runs as a foreground service, using `ProxyTranscoder` to generate a proxy video.

### Playback & Rotation

`EditorScreen` renders video using Media3 ExoPlayer attached to a `TextureView` with `graphicsLayer` Z-rotation and `requiredSize` aspect bounds. Content is clipped via `clipToBounds()` and rotates seamlessly without aspect squashing.

Transport controls support 50% larger touch targets, frame stepping (`±1`, `±10`), smooth scrubbing with frame snapping on release, and guarded frame snapping on user pause.

---

## Annotation system & Frame Sharing

### Shapes & Colors

| Shape | Geometry | Measurement | Canonical Color |
|---|---|---|---|
| 📏 Line | two endpoints | source video pixels (`px`) | Vibrant Orange (`#FFFF8F00`) |
| 📐 Angle | vertex + two rays | interior angle (`0–180°`) | Mint Green (`#00E676`) |
| ⭕ Circle | center + radius | radial readout (`r: X px`) | Sky Blue (`#448AFF`) |

### Interaction, Labels & Export

- **Tap selection & Faint Glow** — tapping a shape selects it. Selected shapes preserve their natural shape colors and render a faint glow path underneath at `3 × strokeWidth` with `25% alpha`.
- **Continuous Angle Readout** — measured angle labels (`0–180°`) are rendered continuously on all angle annotations.
- **Pill Readout Labels** — measurement readouts are rendered on a 60% alpha black rounded-corner pill background (`6dp` radius, `6×3dp` padding) with text in the shape's color, guaranteeing high legibility over bright grass or white walls.
- **Handles & Readouts** — interactive handles and measurement readouts appear on the active selected annotation when paused, and automatically hide during playback, scrubbing, or when tapping empty space. Active dragged handle grows by `1.3×` during drag.
- **Golden-angle spawn** — new shapes spawn in a non-repeating golden-angle spiral (`spawnCounter++`) and are clamped inside `0.05..0.95` normalized bounds.
- **Frame Sharing** — `FrameExporter` renders the exact displayed video frame and its vector annotations overlay into a JPEG image, which is shared using `FileProvider` and the Android system share sheet.

---

## UI & Theming

- **Subdued Slate Dark Theme** — dark background (`#121316`), surface (`#191B1F`), and crisp near-white primary text (`#E3E5E8`).
- **Project List Thumbnails & FPS** — project list items feature cached `64dp × 48dp` JPEG thumbnails (`cacheDir/thumbs/thumb_<id>.jpg`) and display duration and frame rate (e.g. `14.9s · 240 fps`).
- **Jitter-Free Readouts & Toolbar** — time and frame readouts use tabular figures (`tnum`) and display FPS (`Frame 482 · 240 fps`). Bottom toolbar buttons (`Line`, `Angle`, `Circle`, `Share`, `Delete`, `Clear All`) sit in fixed layout slots with active/disabled states.

---

## Threading & lifecycle rules

- **No media I/O on the main thread.** `viewModelScope.launch` defaults to `Dispatchers.Main.immediate`; `MediaMetadataRetriever` calls must be wrapped in `withContext(Dispatchers.IO)`.
- **No side effects in composable bodies.** ViewModel initialization goes in `LaunchedEffect(key)`.
- **No permanent polling loops.** Position polling runs only while playing and stops when not resumed.
- **Interop views don't eat touches.** `TextureView` is configured so annotation gesture handlers layer directly above it.
- **Every scope gets cancelled.** `TranscodeService.serviceScope` is cancelled in `onDestroy`.

---

## Tech stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.2 |
| Min / Target SDK | 33 (Android 13) / 36 |
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation 3 (`androidx.navigation3`) |
| DI | Hilt |
| Persistence | Room + Coroutines + StateFlow |
| Serialization | kotlinx.serialization |
| Video | Media3 ExoPlayer, MediaExtractor, MediaMuxer |
| Testing | JUnit 4, kotlinx-coroutines-test |

---

## Getting started

### Build & Test

```bash
./gradlew assembleDebug      # build app
./gradlew test               # run all unit tests
```
