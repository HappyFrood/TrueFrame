# TrueFrame 🎥📐

An offline Android app for coaches, athletes, and video analysts. Open a video from the device gallery, step through it frame by frame, and draw annotations — lines, angles, circles, arrows and text — directly on the picture. Analysis tools add a Ghost Frame overlay, a calibrated Speed Calculator and an alignment Grid. Built for high-frame-rate sports footage (60 / 120 / 240 fps).

**Status:** `v4.0`.

**Non-goals for v1:** cloud sync, accounts, side-by-side comparison, trimming.

---

## Contents

- [Architecture](#architecture)
- [Module layout](#module-layout)
- [Core design contracts](#core-design-contracts)
- [Data model](#data-model)
- [Video pipeline](#video-pipeline)
- [Annotation system & Frame Sharing](#annotation-system--frame-sharing)
- [Analysis tools](#analysis-tools)
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
│  ProxyTranscoder (remux)     │  AnnotationShape         │
│  TranscodeBus                │  Geometry / measurement  │
│  FrameMath · VideoProbe      │  AnnotationRenderer      │
│                              │  HitTesting · SpeedMath  │
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
:core:video          ProxyTranscoder (remux), TranscodeBus, FrameMath, VideoProbe (fps
                     metadata) — no Compose, no app types
:core:annotation     Shape models, geometry/measurement math, Canvas renderer,
                     hit-testing, SpeedMath (calibrated ball speed). Pure geometry,
                     fully unit-testable.
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
- **Position Persistence** — Last playback position (`lastPositionMs`) is saved to Room on `ON_STOP` (app backgrounded) and when leaving the editor, then restored when reopening the video. The save runs in an application-level `@ApplicationScope` coroutine scope so it completes even though the editor's `viewModelScope` is cancelled right after the screen is disposed.

### 3. Project-wide annotations

Annotations are observed project-wide (`annotationDao.observeForProject(projectId)`). Each shape retains its creation `frameIndex`, displayed when selected (e.g., `"drawn on frame X"`).

### 4. Persistence on drag end

Annotation writes update Room on `onDragEnd` via `@Update`, preserving row IDs and keeping frame metadata intact.

### 5. Selection by annotation ID

Selection (`EditorUiState.selectedAnnotationId`) and drag targets are tracked by `AnnotationItem.id`, never by list index — Room re-emits the list after every insert/delete, which shifts indices. IDs are resolved to an index only where the overlay needs one for drawing; if the selected ID disappears after a re-emit, the selection is cleared.

### 6. Targeted project writes

Project fields are written with targeted single-column DAO updates (`updateLastPosition`, `updateRotation`, `updateName`, each also setting `updatedAt`) instead of read-copy-update, so overlapping writes (e.g. rotate, then leave the screen) never restore stale values.

---

## Data model

```text
@Entity("projects")
ProjectEntity(id, name, videoUri, proxyUri, transcodeState, rotationDegrees, lastPositionMs, frameRate, captureFps, createdAt, updatedAt)

@Entity("annotations", FK -> projects CASCADE, index(projectId, frameIndex))
AnnotationEntity(id, projectId, frameIndex, shapeType, serializedData)   // shapeType: line | angle | circle | arrow | text

@Entity("tags")             TagEntity(id, name)
@Entity("project_tags")     ProjectTagCrossRef(projectId, tagId)
```

- `serializedData` is kotlinx-serialization JSON of shape geometry. The `type` discriminator is the `SerializableShape` subclass name, so existing subclasses are never renamed — new shapes are only added.
- `lastPositionMs` stores the last saved playback position in milliseconds.
- `frameRate` / `captureFps` hold the original file's playback and capture (slow-motion) frame rates, read at import (schema v4, `MIGRATION_3_4`). Projects imported before v4 are probed once on first load and backfilled.
- `transcodeState` tracks background conversion status (`PENDING`, `RUNNING`, `COMPLETE`, `ERROR`).
- Project names are timestamped by default and editable from both the project list and the editor header.

---

## Video pipeline

### Import & proxy remux

Videos imported via the photo picker are copied into durable app storage (`noBackupFilesDir`). `TranscodeService` runs as a foreground service, using `ProxyTranscoder` to **remux** the video track into app storage (MediaExtractor → MediaMuxer). Despite the historical class names this is not a transcode: compressed samples are copied as-is at full resolution with no re-encode or downscale, and rotation is carried as an orientation hint. Color standard/transfer/range, HDR static info, profile, level and frame rate are copied from the source track so 10-bit/HDR clips keep correct colors. The sample buffer is sized from the track's max-input-size (32 MB fallback) so high-bitrate 4K keyframes fit.

### Frame-rate detection

The editor holds the frame rate in `EditorUiState` (`frameRate`, `frameRateSource`):

1. the player's reported `videoFormat.frameRate`, when positive;
2. otherwise the rate stored on the project, computed by `VideoProbe` (frame count / duration from `MediaMetadataRetriever`, falling back to counting samples with `MediaExtractor`);
3. otherwise 30 fps, marked **assumed** — the readout then shows `fps?`.

`VideoProbe` also reads `METADATA_KEY_CAPTURE_FRAMERATE` (`captureFps`), used by the Speed Calculator and shown in the readout as `F 482 · 240 fps slo-mo` when it exceeds the playback rate. Frame-rate metadata is always read from the **original** imported file, never the proxy: MediaMuxer does not carry the capture-rate tag (`com.android.capture.fps`) into the remux. It is read once at import (and backfilled on first load for older projects) and stored on `ProjectEntity`.

### Playback & Rotation

`EditorScreen` renders video using Media3 ExoPlayer attached to a `TextureView` with `graphicsLayer` Z-rotation and `requiredSize` aspect bounds. Content is clipped via `clipToBounds()` and rotates seamlessly without aspect squashing.

Transport controls support frame stepping (`±1`, `±10`), smooth scrubbing with frame snapping on release, and guarded frame snapping on user pause.

---

## Annotation system & Frame Sharing

### Shapes & Colors

| Shape | Geometry | Measurement | Canonical Color |
|---|---|---|---|
| 📏 Line | two endpoints | source video pixels (`px`) | Vibrant Orange (`#FFFF8F00`) |
| 📐 Angle | vertex + two rays | interior angle (`0–180°`) | Mint Green (`#00E676`) |
| ⭕ Circle | center + radius | radial readout (`r: X px`) | Sky Blue (`#448AFF`) |
| ➡️ Arrow | tail (`start`) + head (`end`); filled head = `4 × stroke`, 28° half-angle, clamped to 40% of length | none — a pointer, not a ruler | Vivid Red (`#FFFF1744`) |
| 🔤 Text | pill top-left `anchor` + text (≤ 60 chars, single line) | none | Yellow (`#FFFFEB3B`) |

**Stroke widths:** `5.4dp` on screen (selected shape `1.3×`, glow `3×`), `0.009 × width` (min `7.2px`) in the exported JPEG. Text pills scale with the frame: font size `0.04 × min(frameWidth, frameHeight)` on screen and in the export, with padding and corner radius proportional. Text glyphs always render upright; only the anchor follows video rotation.

### Interaction, Labels & Export

- **Tap selection & Faint Glow** — tapping a shape selects it. Selected shapes preserve their natural shape colors and render a faint glow path underneath at `3 × strokeWidth` with `25% alpha`.
- **Continuous Angle Readout** — measured angle labels (`0–180°`) are rendered continuously on all angle annotations.
- **Pill Readout Labels** — measurement readouts are rendered on a 60% alpha black rounded-corner pill background (`6dp` radius, `6×3dp` padding) with text in the shape's color, guaranteeing high legibility over bright grass or white walls.
- **Handles & Readouts** — interactive handles and measurement readouts appear on the active selected annotation when paused, and automatically hide during playback, scrubbing, or when tapping empty space. Active dragged handle grows by `1.3×` during drag.
- **Golden-angle spawn** — new shapes spawn in a non-repeating golden-angle spiral (`spawnCounter++`) and are clamped inside `0.05..0.95` normalized bounds.
- **Text editing** — the Text tool opens an "Add text" dialog. Tapping an already-selected Text label again reopens the dialog pre-filled; Save updates it through the DAO. Dragging anywhere on the pill moves it.
- **Frame Sharing** — `FrameExporter` renders the exact displayed video frame and its vector annotations overlay into a JPEG image, which is shared using `FileProvider` and the Android system share sheet. When the Ghost Frame is on, the ghost is blended into the export under the annotations at the same opacity. The grid and Speed markers are never exported.

---

## Analysis tools

All analysis-tool state is session-only (never written to Room).

### Ghost Frame

Freezes one frame (e.g. address) and shows it semi-transparent over the video while stepping through other frames.

- **Ghost** tool: when off, a tap captures the currently displayed frame with `TextureView.getBitmap()` and turns the ghost on. When on, a tap opens a popover with an opacity slider (20–70%, default 40%), *Re-capture from this frame* and *Turn off*.
- A `Ghost · F 312` chip at the top-left of the video does the same as the button.
- The ghost is drawn with the same `requiredSize` + `graphicsLayer { rotationZ }` modifiers as the TextureView, so it lines up in all rotations. It sits above the video and below the grid and annotations, and ignores touches.
- The bitmap lives in `EditorViewModel` (survives configuration changes) and is recycled on turn-off, re-capture and `onCleared()`. `EditorUiState` holds `ghostFrameIndex` and `ghostOpacity`.
- For export, the ghost frame is re-grabbed at full resolution via `MediaMetadataRetriever` at `FrameMath.msForFrame(ghostFrameIndex)`, through the same frame-grab/orientation helper as the current frame.

### Speed Calculator

Estimates ball speed in **mph** from an object of known length in the frame. A step-by-step Speed mode replaces the tool row with `Cancel · step · Back · Next`; annotation tap/drag is disabled, while the scrub bar and frame stepping stay active. A slim banner floats over the video.

1. **Reference** — drag a temporary white line onto an object of known length. Endpoints are marked with perpendicular ticks inside hollow rings, so the end of the object stays visible.
2. **Known length** — enter the length in inches (1–600); quick-fill chips: Driver 45", Tennis racket 27", Baseball bat 33", Pickleball paddle 16".
3. **Ball start** — go to the frame where the ball starts and tap its center (marker **A**, records the frame).
4. **Ball end** — step forward and tap the ball again (marker **B**; Next requires a different frame).
5. **Result card** — speed, detail line, fps chips (30/60/120/240/480 + custom, live recalculation), *Redo ball*, *Add as label* (creates a persistent Text annotation at B on B's frame) and *Done*.

Handles and markers move **relative** to the finger (they never jump under it, so you can grab slightly off the point), and a 3× magnifier with a crosshair appears on the opposite side of the video while dragging. Markers can be dragged as well as tapped; dragging re-stamps the marker with the frame currently shown.

The calibration is kept for the session ("Use previous reference" skips steps 1–2). Reference line and markers are temporary and never exported.

**Formula** (`SpeedMath`, `:core:annotation`). All distances are measured in **source pixels** (`x * rawWidth`, `y * rawHeight`), never in normalized units:

```
inchesPerPx = knownInches / referenceLengthPx
ballInches  = distancePx(A, B) * inchesPerPx
seconds     = |frameB − frameA| / fps
mph         = (ballInches / seconds) × 3600 / 63360
```

Validation returns typed errors: reference < 20 source px ("Reference line is too short"), identical frames, fps ≤ 0.

**Frame-rate caveat:** many phones save slow-motion video as a 30 fps file even though it was captured at 120/240 fps; using the playback rate would make the speed 4–8× too low. The fps is pre-filled from `METADATA_KEY_CAPTURE_FRAMERATE` when present and higher than playback, otherwise from the detected playback rate. If the rate was only assumed (see [Frame-rate detection](#frame-rate-detection)), the fps is marked *Unconfirmed* and the user must pick one before a result is shown.

### Grid

Toggle in the tool row (highlighted when on). 8 square columns across the displayed video box, as many rows as fit at the same pitch, centered vertically; 1dp white lines at 22% alpha, center lines at 45%. Drawn in view space over the video and ghost, under the annotations — it never rotates with the video. Ignores touches and is not exported. `showGrid` is session-only.

---

## UI & Theming

- **Subdued Slate Dark Theme** — dark background (`#121316`), surface (`#191B1F`), and crisp near-white primary text (`#E3E5E8`).
- **Project List Thumbnails & FPS** — project list items feature cached `64dp × 48dp` JPEG thumbnails (`cacheDir/thumbs/thumb_<id>.jpg`) and display duration and frame rate (e.g. `14.9s · 240 fps`).
- **Editor layout (portrait)** — chrome is kept to ~200dp so the video gets the rest of the screen on a pure black background:
  1. **Header (48dp)** — Back · project name (one line, tap to rename) · Focus · Rotate · Share · ⋮ (*Rename*, *Clear all annotations*).
  2. **Video area** — all remaining space.
  3. **Scrub row (~40dp)** — `1.23 / 3.00 s` · slider · `F 482 · 240 fps` (`F 482 · drawn F 310` when a shape is selected, `fps?` when the rate is assumed), tabular figures.
  4. **Transport row (52dp)** — −10, −1, Play/Pause, +1, +10.
  5. **Tool row (48dp)** — `Line · Angle · Circle · Arrow · Text | Ghost · Speed · Grid | Delete`, 44dp fixed slots with dividers; scrolls horizontally on narrow screens instead of shrinking targets.
- **Focus mode** — the header's fullscreen icon hides the header and tool row and enters immersive mode (transient swipe-to-show system bars). A translucent scrub + transport strip overlays the bottom of the video; a small exit button sits top-right, and system Back exits Focus mode first. Annotations keep working.
- **Landscape** — the video fills the full height on the left; a ~200dp right-side panel holds Back + name, Rotate/Share/⋮, the scrub slider with labels, transport (5 × 40dp) and the tools in a 3-column grid.
- **Overlays never resize the video** — the Speed banner and result card, Ghost popover and chip, and dialogs float over the video area; the Speed wizard row replaces the tool row at the same height.

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
| Video | Media3 ExoPlayer, MediaExtractor, MediaMuxer (remux), MediaMetadataRetriever |
| Testing | JUnit 4, kotlinx-coroutines-test |

---

## Getting started

### Build & Test

```bash
./gradlew assembleDebug      # build app
./gradlew test               # run all unit tests
```
