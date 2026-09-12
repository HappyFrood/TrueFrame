# TrueFrame 🎥📐

An offline Android app for coaches, athletes, and video analysts. Open a video from the device gallery, step through it frame by frame, and draw measurement annotations — lines, angles, circles — directly on the picture. Built for high-frame-rate sports footage (60 / 120 / 240 fps).

**Status:** alpha (`v3.4`). The architecture below is the target. See [Implementation status](#implementation-status) for what actually works today — some sections describe the design we're building toward, not the current tree.

**Non-goals for v1:** cloud sync, accounts, side-by-side comparison, trimming, annotated video export.

---

## Contents

- [Architecture](#architecture)
- [Module layout](#module-layout)
- [Core design contracts](#core-design-contracts)
- [Data model](#data-model)
- [Video pipeline](#video-pipeline)
- [Annotation system](#annotation-system)
- [Threading & lifecycle rules](#threading--lifecycle-rules)
- [Tech stack](#tech-stack)
- [Implementation status](#implementation-status)
- [Getting started](#getting-started)

---

## Architecture

Clean-ish layering, unidirectional data flow, single source of truth per screen.

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
│  ProxyReader (+ Session)     │  Geometry / measurement  │
│  FrameIndex · PlaybackClock  │  AnnotationRenderer      │
│  AudioExtractor              │  HitTesting              │
└──────────────────────────────┴──────────────────────────┘
                 :core:designsystem (theme, tokens)
```

**Rules:**

- ViewModels own all state. Composables are pure functions of `UiState` plus event callbacks — no business logic, no side effects in the composable body.
- `:core:*` modules are Android-library-only and know nothing about the app or each other.
- `:data` depends on `:core:annotation` (for shape types in JSON I/O) and exposes it via `api`, not `implementation`, since `AnnotationShape` appears in its public signatures.
- One playback engine. Not two. See [Video pipeline](#video-pipeline).

---

## Module layout

```text
:app                 Activity, Navigation 3, screens, ViewModels, DI wiring,
                     TranscodeService (foreground service)
:core:video          ProxyTranscoder, ProxyReader, FrameIndex, PlaybackClock,
                     AudioExtractor — no Compose, no app types
:core:annotation     Shape models, geometry/measurement math, Canvas renderer,
                     hit-testing. Pure geometry, fully unit-testable.
:core:designsystem   Material 3 theme, color tokens, typography
:data                Room entities/DAOs, repositories, proxy cache manager,
                     annotation JSON serialization
```

---

## Core design contracts

These are the invariants that keep the annotation and frame-stepping features correct. Violating them is how the alpha broke.

### 1. Annotation coordinates are normalized (0..1), always

Shapes are stored and manipulated in **normalized video-frame space**: `(0,0)` is the top-left of the video image, `(1,1)` the bottom-right. Radii are normalized against frame **width**.

| Layer | Coordinate space |
|---|---|
| `AnnotationShape`, Room, JSON | Normalized 0..1 |
| `EditorViewModel` state | Normalized 0..1 |
| `AnnotationRenderer` / `Canvas` | View pixels |
| Touch input (`change.position`) | View pixels |

Conversion happens **only** at the UI boundary, against the fitted video rect (the letterboxed area the video actually occupies, not the full composable):

```kotlin
fun Offset.toView(rect: Rect) = Offset(rect.left + x * rect.width, rect.top + y * rect.height)
fun Offset.toNorm(rect: Rect) = Offset((x - rect.left) / rect.width, (y - rect.top) / rect.height)
```

Map on the way into the renderer and hit-tester, and on the way out of `detectDragGestures`. Stroke widths and handle radii stay in px/dp and are never normalized.

**Why:** this is what makes annotations survive device rotation, video rotation, different screen sizes, and reload on a different device. It's also a prerequisite for zoom/pan later.

### 2. Frame timing derives from the video's actual frame rate

No constant `33L` anywhere. `EditorUiState.frameRate` is resolved at load time, in this order:

1. `MediaFormat.KEY_FRAME_RATE` from the container track
2. `METADATA_KEY_VIDEO_FRAME_COUNT / duration`
3. `METADATA_KEY_CAPTURE_FRAMERATE` (slow-mo captures only — note this is the *capture* rate, which differs from the container playback rate; use the rate that matches the timestamps you seek against)
4. Fall back to 30 fps

All stepping, frame counters, and cache-key quantization use `frameIntervalMs` derived from that value. For exact indices on variable-frame-rate footage, `FrameIndex` is authoritative over arithmetic.

### 3. Touch targets are declared in dp

Minimum 48dp handle targets, converted to px via `LocalDensity` at the call site. One constant, not three hardcoded pixel values in three files.

### 4. Persistence is debounced and id-stable

Annotation writes happen on `onDragEnd`, not on every pointer-move event. Shapes carry their Room row id in the UI model and are written with `@Update` on that row — never delete-all-and-reinsert, which churns ids, reorders the list out from under positional selection, and rewrites unrelated rows.

---

## Data model

```kotlin
@Entity("projects")
ProjectEntity(id, name, videoUri, proxyUri?, transcodeState, createdAt, updatedAt)

@Entity("annotations", FK → projects CASCADE, index(projectId, frameIndex))
AnnotationEntity(id, projectId, frameIndex, shapeType, serializedData)

@Entity("tags")             TagEntity(id, name)
@Entity("project_tags")     ProjectTagCrossRef(projectId, tagId)
```

- `serializedData` is kotlinx-serialization JSON of a `SerializableShape`, with normalized offsets encoded as `"x,y"` strings.
- `frameIndex` scopes an annotation to a frame. Queries filter on it; writes stamp only the shape being edited.
- Malformed rows deserialize to `null` and are dropped, never crash the project.
- `exportSchema = true`, schema JSON committed to VCS, explicit `Migration` objects from v1 onward. No `fallbackToDestructiveMigration` in release.

### File storage

Proxy videos live in `context.noBackupFilesDir/proxy_videos/`, **not** `cacheDir` — the OS evicts `cacheDir` under storage pressure, and these are large files. Naming is `proxy_<projectId>.mp4`.

`ProxyCacheManager.reconcileCache()` runs at startup and deletes orphans, but must skip files belonging to projects whose transcode is still in flight (that's what the `transcodeState` column is for — `proxyUri` is only written on completion).

`allowBackup` is on, so the manifest must reference `data_extraction_rules.xml` and the app must tolerate a restored database whose proxy files don't exist — re-transcode rather than showing a broken project.

---

## Video pipeline

### Source access

Videos are picked with the system photo picker. **The photo picker's read grant is not persistable** — `takePersistableUriPermission` throws on those URIs. Two valid options, pick one:

- Use `ActivityResultContracts.OpenDocument` and take a persistable grant, or
- Import: copy/transcode the file into app storage at add time and treat the app-owned file as canonical, letting the original URI go stale.

The second is preferred, since a transcode step already exists.

### Proxy generation

`TranscodeService` (foreground, `mediaProcessing` type on Android 15+) drives `ProxyTranscoder` to produce a lower-resolution proxy optimized for fast random seeking, with rotation baked in via an OpenGL surface so downstream code never handles rotation metadata.

Contract:

- One transcoder instance **per job** — not a `@Singleton`. Concurrent imports must not share state.
- Progress is keyed by project id and throttled to whole-percent changes. Emitting per-sample floods the notification manager.
- Terminal events (`Complete` / `Error`) go over a `SharedFlow` or `Channel`, not a conflated `StateFlow`, so a completion can't be swallowed by a subsequent state change.
- Cleanup is in `finally`. A cancelled or failed job deletes its partial output.
- If the source has no video track, that's an `Error`, not a `Complete` with a zero-byte file.

### Playback & frame stepping

**One engine.** The spec called for MediaCodec with no ExoPlayer dependency; the alpha added ExoPlayer alongside the MediaCodec path, and the result is two engines where one is dead code and fixes land in the wrong half.

Trade-off, to be resolved:

| | ExoPlayer (Media3) | MediaCodec + ProxyReader |
|---|---|---|
| Smooth playback | free | must be built |
| Audio | free | `AudioExtractor` + `AudioTrack` |
| Exact frame indices | needs `FrameIndex` alongside | native to the design |
| Scrub responsiveness | good | `OPTION_CLOSEST_SYNC` + LRU cache |
| Dependency weight | +Media3 | none |

Whichever wins, the other's code is deleted, not left in place.

Target behavior either way:

- Scrubbing uses keyframe-sync seeks for instant feedback during drag; release resolves the exact frame.
- In-flight decode jobs cancel on new seek input.
- A single reused `MediaMetadataRetriever` per video, with an LRU bitmap cache **sized in bytes** (`sizeOf` returning `byteCount / 1024`), budgeted off `Runtime.maxMemory()`. Sizing by entry count is how you allocate 220 MB of bitmaps by accident.
- `FrameIndex` sorts samples by presentation timestamp before indexing — `MediaExtractor` walks decode order, and B-frame timestamps are not monotonic, so an unsorted binary search returns garbage.
- `PlaybackClock.pause()` captures the current time before clearing `isPlaying`, and re-bases on speed change.

---

## Annotation system

### Shapes

| Shape | Geometry | Measurement |
|---|---|---|
| 📏 Line | two endpoints | distance |
| 📐 Angle | vertex + two rays | **interior angle, 0–180°** |
| ⭕ Circle | center + radius | radial overlay |

Angles report the interior angle, so the reading doesn't depend on which ray the user drew first.

### Interaction

- **Handles** — endpoints, vertex, radius handle. 48dp minimum touch target, converted from dp.
- **Whole-shape drag** — touching anywhere on a line, ray, or circle boundary drags the entire shape.
- **Nearest-wins hit testing** — overlapping shapes resolve to the closest hit, not the first in list order.
- **Live measurement** — angle values render on-canvas with high-contrast shadows. `Paint` objects and format strings are hoisted out of the draw phase, not allocated per frame.

### Editor layout

- Top bar: project name, frame counter, time readout, rotate.
- Bottom bar, row 1: filmstrip scrubber, `X.Xs / Y.Ys`, `Frame N`, transport (`<<`, `<`, play/pause, `>`, `>>`).
- Bottom bar, row 2: 📏 Line, 📐 Angle, ⭕ Circle, 🗑️ Delete, 🧹 Clear.
- Bottom bars apply `navigationBarsPadding()` so controls clear the gesture nav area.

---

## Threading & lifecycle rules

- **No media I/O on the main thread.** `viewModelScope.launch` defaults to `Dispatchers.Main.immediate`; `MediaMetadataRetriever.setDataSource` and friends must be wrapped in `withContext(Dispatchers.IO)`.
- **No side effects in composable bodies.** ViewModel initialization goes in `LaunchedEffect(key)`.
- **No permanent polling loops.** Player position comes from a `Player.Listener`; any polling runs only while playing and stops when the screen isn't resumed.
- **Interop views don't eat touches.** A `TextureView` returning `true` from `onTouch` blocks the annotation gesture handler layered above it.
- **Every scope gets cancelled.** `TranscodeService.serviceScope` is cancelled in `onDestroy`.
- **`AndroidView.update` is guarded.** Reattaching the video surface on every recomposition causes black frames.

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
| Video | MediaCodec, MediaExtractor, MediaMuxer, MediaMetadataRetriever *(+ Media3 ExoPlayer, pending resolution)* |
| Testing | JUnit 4, kotlinx-coroutines-test, Compose UI Test, Hilt Android Testing |

### Permissions

| Permission | Why |
|---|---|
| `READ_MEDIA_VIDEO` | gallery access |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROCESSING` | transcode survival |
| `POST_NOTIFICATIONS` | transcode progress — **must be requested at runtime on SDK 33+**, declaring it is not enough |

---

## Implementation status

Honest accounting as of `v3.4`.

### Working

- Project create / list / delete, Room persistence
- Video picking, 5-minute duration guard
- Foreground transcode service with progress notification
- Annotation shapes, interior-angle math, hit-testing geometry
- Annotation JSON serialization with safe failure on malformed rows
- Proxy storage in `noBackupFilesDir`
- Unit tests for angle math, hit-testing, and JSON round-trip

### Partially implemented

- **Normalized coordinates** — the ViewModel emits 0..1 shapes, but the renderer and hit-tester still work in pixels. Annotations currently draw in the top-left corner. Needs the conversion layer from [contract #1](#1-annotation-coordinates-are-normalized-01-always).
- **Frame-rate awareness** — implemented in `EditorViewModel`, which the editor screen doesn't call. The UI still uses `33L` constants.
- **Annotation persistence** — wired up, but writes on every drag event via delete-all-and-reinsert, and stamps all shapes with the current frame index.
- **Per-frame annotations** — schema supports it; queries and writes don't filter on `frameIndex` yet.

### Not implemented

- **Real transcoding.** `ProxyTranscoder` currently remuxes the video track at source resolution with no re-encode, no OpenGL rotation baking, and drops audio.
- **Audio playback.** `AudioExtractor` exists but has no call sites.
- **Tagging.** Entities and DAOs exist; no UI.
- **Slow-motion playback.** `PlaybackClock` exists but is unused.
- **Rotation correctness.** Currently a `View.rotation` transform; annotations don't follow it.
- **Room migrations**, release minification, static analysis, CI.

---

## Getting started

### Prerequisites

- Android Studio (AGP 9.x)
- Android SDK 36, min SDK 33
- Java 17

### Build

```bash
./gradlew :app:assembleDebug       # build
./gradlew test                     # unit tests
./gradlew connectedAndroidTest     # instrumented (Hilt) tests
./gradlew :app:installDebug        # deploy
```

### Contributing

- Geometry and measurement code goes in `:core:annotation` and must be unit-tested. It has no Android dependencies — there's no excuse for untested math.
- Anything touching annotation coordinates: read [contract #1](#1-annotation-coordinates-are-normalized-01-always) first.
- Anything touching frame timing: no magic `33`.

---

## License

MIT.
