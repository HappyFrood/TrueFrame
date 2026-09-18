@file:Suppress("UnsafeOptInUsageError")
@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)

package com.example.trueframe.ui.editor

import android.net.Uri
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.trueframe.core.annotation.AnnotationOverlay
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.annotation.HitTesting
import com.example.trueframe.core.annotation.rotateNorm
import com.example.trueframe.core.annotation.rotateVectorNorm
import com.example.trueframe.core.annotation.toPixelSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.OptIn
import java.util.Locale
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun EditorScreen(
    projectId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) {
        viewModel.initialize(projectId)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var activeDragTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var activeShapeDragIndex by remember { mutableStateOf<Int?>(null) }
    val currentAnnotations by rememberUpdatedState(uiState.annotations)

    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }

    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }
    var pixelRatio by remember { mutableFloatStateOf(1f) }
    var frameRate by remember { mutableFloatStateOf(30f) }

    val frameIntervalMs = (1000f / frameRate.coerceAtLeast(1f))
    val currentFrameIdx = (currentPositionMs / frameIntervalMs).toInt()

    // Hardware ExoPlayer instance for 100% native video playback & instant seeking
    val exoPlayer = remember(uiState.videoUri) {
        if (uiState.videoUri != null) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(uiState.videoUri)))
                prepare()
            }
        } else null
    }

    fun seekToFrame(delta: Int) {
        val player = exoPlayer ?: return
        player.pause()
        val target = ((currentFrameIdx + delta).coerceAtLeast(0) * frameIntervalMs)
            .toLong().coerceIn(0L, totalDurationMs)
        player.seekTo(target)
        currentPositionMs = target
    }

    DisposableEffect(exoPlayer) {
        val player = exoPlayer ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                val rot = size.unappliedRotationDegrees
                if (rot == 90 || rot == 270) {
                    videoW = size.height
                    videoH = size.width
                } else {
                    videoW = size.width
                    videoH = size.height
                }
                pixelRatio = if (size.pixelWidthHeightRatio > 0f) size.pixelWidthHeightRatio else 1f
                viewModel.updatePlayerState(player.currentPosition, totalDurationMs, isPlaying, frameRate)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                viewModel.updatePlayerState(player.currentPosition, totalDurationMs, playing, frameRate)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    totalDurationMs = player.duration.coerceAtLeast(1L)
                    player.videoFormat?.frameRate?.let { if (it > 0f) frameRate = it }
                    viewModel.updatePlayerState(player.currentPosition, totalDurationMs, isPlaying, frameRate)
                }
            }
            override fun onPositionDiscontinuity(
                old: Player.PositionInfo,
                new: Player.PositionInfo,
                reason: Int,
            ) {
                currentPositionMs = new.positionMs
                viewModel.updatePlayerState(new.positionMs, totalDurationMs, isPlaying, frameRate)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exoPlayer?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Poll ONLY while playing — required by the "no permanent polling loops" rule.
    LaunchedEffect(exoPlayer, isPlaying) {
        val player = exoPlayer ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            currentPositionMs = player.currentPosition
            delay(16L)
        }
    }

    LaunchedEffect(currentPositionMs, totalDurationMs, isPlaying, frameRate) {
        viewModel.updatePlayerState(currentPositionMs, totalDurationMs, isPlaying, frameRate)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Project $projectId", style = MaterialTheme.typography.titleMedium)
                        val seconds = currentPositionMs / 1000f
                        Text(
                            text = String.format(Locale.US, "Frame %d (%.2fs)", currentFrameIdx, seconds),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.rotateVideo() }) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate Video")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 12.dp)) {
                    // ================= ROW 1: FILMSTRIP & SCRUBBING CONTROL =================
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currentSec = currentPositionMs / 1000f
                            val totalSec = totalDurationMs / 1000f

                            Text(
                                text = String.format(Locale.US, "%.1fs / %.1fs", currentSec, totalSec),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "Frame $currentFrameIdx",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        if (totalDurationMs > 0) {
                            Slider(
                                value = currentPositionMs.toFloat().coerceIn(0f, totalDurationMs.toFloat()),
                                onValueChange = { newMs ->
                                    val player = exoPlayer ?: return@Slider
                                    if (!isScrubbing) {
                                        isScrubbing = true
                                        player.pause()
                                        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                                    }
                                    player.seekTo(newMs.toLong())
                                    currentPositionMs = newMs.toLong()
                                },
                                onValueChangeFinished = {
                                    val player = exoPlayer ?: return@Slider
                                    player.setSeekParameters(SeekParameters.EXACT)
                                    player.seekTo(currentPositionMs)
                                    isScrubbing = false
                                },
                                valueRange = 0f..totalDurationMs.toFloat().coerceAtLeast(1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Transport Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { seekToFrame(-10) }) {
                                Icon(Icons.Default.FastRewind, contentDescription = "-10 Frames")
                            }
                            IconButton(onClick = { seekToFrame(-1) }) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "-1 Frame")
                            }
                            IconButton(
                                onClick = {
                                    val player = exoPlayer ?: return@IconButton
                                    if (player.isPlaying) player.pause() else player.play()
                                },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause"
                                )
                            }
                            IconButton(onClick = { seekToFrame(+1) }) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "+1 Frame")
                            }
                            IconButton(onClick = { seekToFrame(+10) }) {
                                Icon(Icons.Default.FastForward, contentDescription = "+10 Frames")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ================= ROW 2: ANNOTATION & EDITING TOOLBAR =================
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.addLine() }) {
                            Icon(Icons.Default.LinearScale, contentDescription = "Add Line")
                        }
                        IconButton(onClick = { viewModel.addAngle() }) {
                            Icon(Icons.Default.Architecture, contentDescription = "Add Angle")
                        }
                        IconButton(onClick = { viewModel.addCircle() }) {
                            Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "Add Circle")
                        }

                        if (uiState.selectedAnnotationIndex != null) {
                            IconButton(onClick = { viewModel.deleteSelectedAnnotation() }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete Annotation",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        if (uiState.annotations.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearAllAnnotations() }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val containerWidth = maxWidth
                val containerHeight = maxHeight
                val density = LocalDensity.current.density
                val handleTouchPx = with(LocalDensity.current) { 24.dp.toPx() }
                val shapeTouchPx = with(LocalDensity.current) { 16.dp.toPx() }

                val rawWidth = if (videoW > 0) videoW * pixelRatio else 1080f
                val rawHeight = if (videoH > 0) videoH.toFloat() else 1920f

                val isSideways = (uiState.rotationDegrees == 90 || uiState.rotationDegrees == 270)

                val effectiveWidth = if (isSideways) rawHeight else rawWidth
                val effectiveHeight = if (isSideways) rawWidth else rawHeight

                val videoAspect = effectiveWidth / effectiveHeight
                val containerAspect = containerWidth.value / containerHeight.value

                val (fittedWidthDp, fittedHeightDp) = if (videoAspect > containerAspect) {
                    Pair(containerWidth, containerWidth / videoAspect)
                } else {
                    Pair(containerHeight * videoAspect, containerHeight)
                }

                val vw = fittedWidthDp.value * density
                val vh = fittedHeightDp.value * density
                val aspectCorrection = vh / vw

                val viewWidthDp = if (isSideways) fittedHeightDp else fittedWidthDp
                val viewHeightDp = if (isSideways) fittedWidthDp else fittedHeightDp

                Box(
                    modifier = Modifier
                        .size(fittedWidthDp, fittedHeightDp)
                        .pointerInput(vw, vh, uiState.rotationDegrees, rawWidth, rawHeight, handleTouchPx, shapeTouchPx) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    viewModel.onDragStarted()
                                    val rotatedShapes = currentAnnotations.map { it.shape.rotateNorm(uiState.rotationDegrees, rawWidth / rawHeight) }
                                    val pixelShapes = rotatedShapes.map { it.toPixelSpace(vw, vh) }
                                    val handleHit = findHitHandle(pixelShapes, startOffset, handleTouchPx)
                                    if (handleHit != null) {
                                        activeDragTarget = handleHit
                                        activeShapeDragIndex = null
                                        viewModel.selectAnnotation(handleHit.first)
                                    } else {
                                        activeDragTarget = null
                                        val shapeHit = findHitShape(pixelShapes, startOffset, shapeTouchPx)
                                        activeShapeDragIndex = shapeHit
                                        viewModel.selectAnnotation(shapeHit)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val normPos = Offset(change.position.x / vw, change.position.y / vh)
                                    val normDelta = Offset(dragAmount.x / vw, dragAmount.y / vh)

                                    val unrotatedPos = normPos.rotateNorm(-uiState.rotationDegrees)
                                    val unrotatedDelta = normDelta.rotateVectorNorm(-uiState.rotationDegrees)

                                    activeDragTarget?.let { (shapeIdx, handleIdx) ->
                                        // The aspect correction for the handle update needs to be the original aspect
                                        viewModel.updateShapeHandle(shapeIdx, handleIdx, unrotatedPos, aspectCorrection = rawHeight / rawWidth)
                                    } ?: activeShapeDragIndex?.let { shapeIdx ->
                                        viewModel.offsetShape(shapeIdx, unrotatedDelta)
                                    }
                                },
                                onDragEnd = {
                                    val draggedIndex = activeDragTarget?.first ?: activeShapeDragIndex
                                    activeDragTarget = null
                                    activeShapeDragIndex = null
                                    viewModel.persistAnnotationsOnDragEnd(draggedIndex)
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (exoPlayer != null) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    // Disable touch consumption so parent Box can detect drag gestures
                                    isClickable = false
                                    isFocusable = false
                                }
                            },
                            update = { view ->
                                if (view.player !== exoPlayer) {
                                    view.player = exoPlayer
                                }
                                val target = uiState.rotationDegrees.toFloat()
                                if (view.rotation != target) {
                                    view.rotation = target
                                }
                            },
                            modifier = Modifier.size(viewWidthDp, viewHeightDp)
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (uiState.error != null) {
                                Text("Error: ${uiState.error}")
                            } else {
                                Text("Loading video...")
                            }
                        }
                    }

                    // Vector Annotation Canvas layered directly on top of video
                    AnnotationOverlay(
                        shapes = uiState.annotations.map { it.shape },
                        selectedIndex = uiState.selectedAnnotationIndex,
                        showHandles = (!isPlaying),
                        rotationDegrees = uiState.rotationDegrees,
                        frameAspect = rawWidth / rawHeight,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Finds the closest control point handle within [thresholdPx] pixels.
 */
private fun findHitHandle(
    shapes: List<AnnotationShape>,
    touch: Offset,
    thresholdPx: Float,
): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    var bestDist = Float.MAX_VALUE
    shapes.forEachIndexed { shapeIndex, shape ->
        val handles = when (shape) {
            is AnnotationShape.Line -> listOf(shape.start, shape.end)
            is AnnotationShape.Angle -> listOf(shape.start, shape.center, shape.end)
            is AnnotationShape.Circle -> listOf(shape.center, shape.center + Offset(shape.radius, 0f))
        }
        handles.forEachIndexed { handleIndex, handleOffset ->
            val dist = hypot(
                (touch.x - handleOffset.x).toDouble(),
                (touch.y - handleOffset.y).toDouble(),
            ).toFloat()
            if (dist <= thresholdPx && dist < bestDist) {
                bestDist = dist
                best = shapeIndex to handleIndex
            }
        }
    }
    return best
}

private fun findHitShape(
    shapes: List<AnnotationShape>,
    touch: Offset,
    thresholdPx: Float,
): Int? {
    var best: Int? = null
    var bestDist = Float.MAX_VALUE
    shapes.forEachIndexed { index, shape ->
        val d = HitTesting.distanceTo(shape, touch)
        if (d <= thresholdPx && d < bestDist) {
            bestDist = d
            best = index
        }
    }
    return best
}