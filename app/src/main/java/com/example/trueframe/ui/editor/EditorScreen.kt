@file:Suppress("UnsafeOptInUsageError")
@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)

package com.example.trueframe.ui.editor

import android.net.Uri
import android.view.TextureView
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.trueframe.core.annotation.AnnotationOverlay
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.annotation.HitTesting
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
    viewModel.initialize(projectId)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var activeDragTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var activeShapeDragIndex by remember { mutableStateOf<Int?>(null) }
    val currentAnnotations by rememberUpdatedState(uiState.annotations)

    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(1000L) }
    var isPlaying by remember { mutableStateOf(false) }

    // Hardware ExoPlayer instance for 100% native video playback & instant seeking
    val exoPlayer = remember(uiState.videoUri) {
        if (uiState.videoUri != null) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(uiState.videoUri)))
                prepare()
            }
        } else null
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

    // Continuously sync player position and state
    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        while (isActive) {
            val pos = player.currentPosition
            val dur = player.duration.coerceAtLeast(1000L)
            currentPositionMs = pos
            totalDurationMs = dur
            isPlaying = player.isPlaying
            delay(33L)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Project $projectId", style = MaterialTheme.typography.titleMedium)
                        val seconds = currentPositionMs / 1000f
                        val currentFrameIdx = (currentPositionMs / 33L).toInt()
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
                            val currentFrameIdx = (currentPositionMs / 33L).toInt()

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
                                    exoPlayer?.pause()
                                    exoPlayer?.seekTo(newMs.toLong())
                                    currentPositionMs = newMs.toLong()
                                },
                                valueRange = 0f..totalDurationMs.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Transport Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                exoPlayer?.pause()
                                val target = (currentPositionMs - 330L).coerceAtLeast(0L)
                                exoPlayer?.seekTo(target)
                                currentPositionMs = target
                            }) {
                                Icon(Icons.Default.FastRewind, contentDescription = "-10 Frames")
                            }
                            IconButton(onClick = {
                                exoPlayer?.pause()
                                val target = (currentPositionMs - 33L).coerceAtLeast(0L)
                                exoPlayer?.seekTo(target)
                                currentPositionMs = target
                            }) {
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
                            IconButton(onClick = {
                                exoPlayer?.pause()
                                val target = (currentPositionMs + 33L).coerceAtMost(totalDurationMs)
                                exoPlayer?.seekTo(target)
                                currentPositionMs = target
                            }) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "+1 Frame")
                            }
                            IconButton(onClick = {
                                exoPlayer?.pause()
                                val target = (currentPositionMs + 330L).coerceAtMost(totalDurationMs)
                                exoPlayer?.seekTo(target)
                                currentPositionMs = target
                            }) {
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
                    .weight(1f)
            ) {
                val containerWidth = maxWidth
                val containerHeight = maxHeight
                val density = LocalContext.current.resources.displayMetrics.density
                val w = (containerWidth.value * density).coerceAtLeast(100f)
                val h = (containerHeight.value * density).coerceAtLeast(100f)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val pixelShapes = currentAnnotations.map { it.toPixelSpace(w, h) }
                                    val handleHit = findHitHandle(pixelShapes, startOffset, thresholdPx = 160f)
                                    if (handleHit != null) {
                                        activeDragTarget = handleHit
                                        activeShapeDragIndex = null
                                        viewModel.selectAnnotation(handleHit.first)
                                    } else {
                                        activeDragTarget = null
                                        val shapeHit = findHitShape(pixelShapes, startOffset)
                                        activeShapeDragIndex = shapeHit
                                        viewModel.selectAnnotation(shapeHit)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val normPos = Offset(change.position.x / w, change.position.y / h)
                                    val normDelta = Offset(dragAmount.x / w, dragAmount.y / h)

                                    activeDragTarget?.let { (shapeIdx, handleIdx) ->
                                        viewModel.updateShapeHandle(shapeIdx, handleIdx, normPos)
                                    } ?: activeShapeDragIndex?.let { shapeIdx ->
                                        viewModel.offsetShape(shapeIdx, normDelta)
                                    }
                                },
                                onDragEnd = {
                                    activeDragTarget = null
                                    activeShapeDragIndex = null
                                    viewModel.persistAnnotationsOnDragEnd()
                                }
                            )
                        }
                ) {
                    if (exoPlayer != null) {
                        val videoSize = exoPlayer.videoSize
                        val rawWidth = if (videoSize.width > 0) videoSize.width.toFloat() else 1080f
                        val rawHeight = if (videoSize.height > 0) videoSize.height.toFloat() else 1920f

                        val isSideways = (uiState.rotationDegrees == 90 || uiState.rotationDegrees == 270)

                        val effectiveWidth = if (isSideways) rawHeight else rawWidth
                        val effectiveHeight = if (isSideways) rawWidth else rawHeight

                        val videoAspect = effectiveWidth / effectiveHeight
                        val containerAspect = containerWidth.value / containerHeight.value

                        val (fittedWidth, fittedHeight) = if (videoAspect > containerAspect) {
                            Pair(containerWidth, containerWidth / videoAspect)
                        } else {
                            Pair(containerHeight * videoAspect, containerHeight)
                        }

                        val viewWidth = if (isSideways) fittedHeight else fittedWidth
                        val viewHeight = if (isSideways) fittedWidth else fittedHeight

                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).apply {
                                    exoPlayer.setVideoTextureView(this)
                                    setOnTouchListener { _, _ -> true }
                                }
                            },
                            update = { view ->
                                exoPlayer.setVideoTextureView(view)
                                view.rotation = uiState.rotationDegrees.toFloat()
                            },
                            modifier = Modifier.size(viewWidth, viewHeight)
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

                    // Vector Annotation Canvas layered directly on top
                    AnnotationOverlay(
                        shapes = uiState.annotations,
                        selectedIndex = uiState.selectedAnnotationIndex,
                        showHandles = (!isPlaying),
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
private fun findHitHandle(shapes: List<AnnotationShape>, touch: Offset, thresholdPx: Float = 160f): Pair<Int, Int>? {
    shapes.forEachIndexed { shapeIndex, shape ->
        val handles = when (shape) {
            is AnnotationShape.Line -> listOf(shape.start, shape.end)
            is AnnotationShape.Angle -> listOf(shape.start, shape.center, shape.end)
            is AnnotationShape.Circle -> listOf(shape.center, shape.center + Offset(shape.radius, 0f))
        }
        handles.forEachIndexed { handleIndex, handleOffset ->
            val dist = hypot((touch.x - handleOffset.x).toDouble(), (touch.y - handleOffset.y).toDouble()).toFloat()
            if (dist <= thresholdPx) {
                return Pair(shapeIndex, handleIndex)
            }
        }
    }
    return null
}

/**
 * Finds the shape index that contains or intersects the given touch offset.
 */
private fun findHitShape(shapes: List<AnnotationShape>, touch: Offset): Int? {
    shapes.forEachIndexed { index, shape ->
        if (HitTesting.hitTest(shape, touch, touchSlop = 80f)) {
            return index
        }
    }
    return null
}