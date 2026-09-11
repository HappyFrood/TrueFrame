package com.example.trueframe.ui.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trueframe.core.annotation.AnnotationOverlay
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.annotation.HitTesting
import java.util.Locale
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    viewModel.initialize(projectId)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var activeDragTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var activeShapeDragIndex by remember { mutableStateOf<Int?>(null) }

    // Rotate bitmap using Matrix so it fills max resolution without Compose bounding-box artifacts
    val displayBitmap = remember(uiState.currentFrame, uiState.rotationDegrees) {
        val src = uiState.currentFrame ?: return@remember null
        if (uiState.rotationDegrees == 0) {
            src.asImageBitmap()
        } else {
            val matrix = Matrix().apply {
                postRotate(uiState.rotationDegrees.toFloat())
            }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true).asImageBitmap()
        }
    }

    val currentAnnotations by rememberUpdatedState(uiState.annotations)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Project $projectId", style = MaterialTheme.typography.titleMedium)
                        val seconds = uiState.currentTimeMs / 1000f
                        Text(
                            text = String.format(Locale.US, "Frame %d (%.2fs)", uiState.frameIndex, seconds),
                            style = MaterialTheme.typography.bodySmall
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
                        // Slider & Time display
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currentSec = uiState.currentTimeMs / 1000f
                            val totalSec = uiState.totalDurationMs / 1000f
                            Text(
                                text = String.format(Locale.US, "%.1fs / %.1fs", currentSec, totalSec),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "Frame ${uiState.frameIndex}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        if (uiState.totalDurationMs > 0) {
                            Slider(
                                value = uiState.currentTimeMs.toFloat(),
                                onValueChange = { viewModel.seekToMs(it.toLong(), isScrubbing = true) },
                                onValueChangeFinished = { viewModel.seekToMs(uiState.currentTimeMs, isScrubbing = false) },
                                valueRange = 0f..uiState.totalDurationMs.toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Transport Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { viewModel.stepFrames(-10) }) {
                                Icon(Icons.Default.FastRewind, contentDescription = "-10 Frames")
                            }
                            IconButton(onClick = { viewModel.stepFrames(-1) }) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "-1 Frame")
                            }
                            IconButton(
                                onClick = { viewModel.togglePlayPause() },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause"
                                )
                            }
                            IconButton(onClick = { viewModel.stepFrames(1) }) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "+1 Frame")
                            }
                            IconButton(onClick = { viewModel.stepFrames(10) }) {
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val shapes = currentAnnotations
                                val handleHit = findHitHandle(shapes, startOffset, thresholdPx = 160f)
                                if (handleHit != null) {
                                    activeDragTarget = handleHit
                                    activeShapeDragIndex = null
                                    viewModel.selectAnnotation(handleHit.first)
                                } else {
                                    activeDragTarget = null
                                    val shapeHit = findHitShape(shapes, startOffset)
                                    activeShapeDragIndex = shapeHit
                                    viewModel.selectAnnotation(shapeHit)
                                }
                            },
                            onDrag = { change, dragAmount ->
                                activeDragTarget?.let { (shapeIdx, handleIdx) ->
                                    viewModel.updateShapeHandle(shapeIdx, handleIdx, change.position)
                                } ?: activeShapeDragIndex?.let { shapeIdx ->
                                    viewModel.offsetShape(shapeIdx, dragAmount)
                                }
                            },
                            onDragEnd = {
                                activeDragTarget = null
                                activeShapeDragIndex = null
                            }
                        )
                    }
            ) {
                if (displayBitmap != null) {
                    Image(
                        bitmap = displayBitmap,
                        contentDescription = "Video Frame",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (uiState.error != null) {
                            Text("Error: ${uiState.error}")
                        } else {
                            Text("Loading frame...")
                        }
                    }
                }

                AnnotationOverlay(
                    shapes = uiState.annotations,
                    selectedIndex = uiState.selectedAnnotationIndex,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Finds the closest control point handle within [thresholdPx] pixels.
 */
private fun findHitHandle(shapes: List<AnnotationShape>, touch: Offset, thresholdPx: Float = 130f): Pair<Int, Int>? {
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
