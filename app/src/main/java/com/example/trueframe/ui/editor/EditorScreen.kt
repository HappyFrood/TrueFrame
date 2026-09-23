@file:Suppress("UnsafeOptInUsageError")
@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)

package com.example.trueframe.ui.editor

import android.content.Intent
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.trueframe.core.annotation.AnnotationOverlay
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.annotation.HitTesting
import com.example.trueframe.core.annotation.rotateNorm
import com.example.trueframe.core.annotation.rotateVectorNorm
import com.example.trueframe.core.annotation.toPixelSpace
import com.example.trueframe.core.video.FrameMath
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    var currentPositionMs by rememberSaveable { mutableLongStateOf(0L) }
    var scrubPositionMs by rememberSaveable { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInputText by remember { mutableStateOf("") }

    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }
    var pixelRatio by remember { mutableFloatStateOf(1f) }
    var frameRate by remember { mutableFloatStateOf(30f) }

    val currentFrameIdx = FrameMath.frameForMs(currentPositionMs, frameRate)

    // Hardware ExoPlayer instance for 100% native video playback & instant seeking
    val exoPlayer = remember(uiState.videoUri) {
        if (uiState.videoUri != null) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(uiState.videoUri)))
                prepare()
                if (currentPositionMs > 0L) {
                    seekTo(currentPositionMs)
                }
            }
        } else null
    }

    fun seekToFrame(delta: Int) {
        val player = exoPlayer ?: return
        player.pause()
        player.setSeekParameters(SeekParameters.EXACT)
        val target = FrameMath.calculateTargetTimeMs(currentFrameIdx, delta, frameRate, totalDurationMs)
        player.seekTo(target)
        currentPositionMs = target
    }

    DisposableEffect(exoPlayer) {
        val player = exoPlayer ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                // Note: unappliedRotationDegrees is dead code on API 21+ (Media3 lets the codec apply rotation and reports display-oriented size with unappliedRotationDegrees == 0).
                val rot = size.unappliedRotationDegrees
                if (rot == 90 || rot == 270) {
                    videoW = size.height
                    videoH = size.width
                } else {
                    videoW = size.width
                    videoH = size.height
                }
                pixelRatio = if (size.pixelWidthHeightRatio > 0f) size.pixelWidthHeightRatio else 1f
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    viewModel.selectAnnotation(null)
                } else {
                    val idx = FrameMath.frameForMs(player.currentPosition, frameRate)
                    val snapped = FrameMath.msForFrame(idx, frameRate).coerceIn(0L, totalDurationMs.coerceAtLeast(0L))
                    player.setSeekParameters(SeekParameters.EXACT)
                    player.seekTo(snapped)
                    currentPositionMs = snapped
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    totalDurationMs = player.duration.coerceAtLeast(1L)
                    player.videoFormat?.frameRate?.let { if (it > 0f) frameRate = it }
                } else if (state == Player.STATE_ENDED) {
                    currentPositionMs = player.currentPosition
                }
            }
            override fun onPositionDiscontinuity(
                old: Player.PositionInfo,
                new: Player.PositionInfo,
                reason: Int,
            ) {
                if (!isScrubbing) currentPositionMs = new.positionMs
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
            if (!isScrubbing) currentPositionMs = player.currentPosition
            delay(16L)
        }
    }

    LaunchedEffect(currentPositionMs, totalDurationMs, isPlaying, frameRate) {
        viewModel.updatePlayerState(currentPositionMs, totalDurationMs, isPlaying, frameRate)
    }

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        val err = uiState.error
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearError()
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(
                    value = renameInputText,
                    onValueChange = { renameInputText = it },
                    singleLine = true,
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameProject(renameInputText)
                        showRenameDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            renameInputText = uiState.projectName.ifEmpty { "Project $projectId" }
                            showRenameDialog = true
                        }
                    ) {
                        Column {
                            Text(uiState.projectName.ifEmpty { "Project $projectId" }, style = MaterialTheme.typography.titleMedium)
                            val seconds = currentPositionMs / 1000f
                            val selectedItem = uiState.selectedAnnotationIndex?.let { uiState.annotations.getOrNull(it) }
                            val subtitleText = if (selectedItem != null) {
                                String.format(Locale.US, "Frame %d (%.2fs) · selected: drawn on frame %d", currentFrameIdx, seconds, selectedItem.frameIndex)
                            } else {
                                String.format(Locale.US, "Frame %d (%.2fs)", currentFrameIdx, seconds)
                            }
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(
                            onClick = {
                                renameInputText = uiState.projectName.ifEmpty { "Project $projectId" }
                                showRenameDialog = true
                            }
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Project Name", modifier = Modifier.size(18.dp))
                        }
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
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 20.dp)) {
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
                                value = (if (isScrubbing) scrubPositionMs else currentPositionMs)
                                    .toFloat().coerceIn(0f, totalDurationMs.toFloat()),
                                onValueChange = { newMs ->
                                    val player = exoPlayer ?: return@Slider
                                    if (!isScrubbing) {
                                        isScrubbing = true
                                        viewModel.selectAnnotation(null)
                                        player.pause()
                                        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                                    }
                                    scrubPositionMs = newMs.toLong()
                                    player.seekTo(scrubPositionMs)
                                },
                                onValueChangeFinished = {
                                    val player = exoPlayer ?: return@Slider
                                    player.setSeekParameters(SeekParameters.EXACT)
                                    // Land dead-centre on a frame rather than between two.
                                    val snapped = FrameMath.msForFrame(
                                        FrameMath.frameForMs(scrubPositionMs, frameRate), frameRate
                                    ).coerceIn(0L, totalDurationMs)
                                    player.seekTo(snapped)
                                    currentPositionMs = snapped
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

                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    val uri = viewModel.exportFrameUri(context)
                                    if (uri != null) {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "image/jpeg"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share Annotated Frame"))
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share Frame")
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

                        if (showClearConfirm) {
                            AlertDialog(
                                onDismissRequest = { showClearConfirm = false },
                                title = { Text("Clear all annotations?") },
                                text = { Text("This deletes all ${uiState.annotations.size} annotations in this project. This can't be undone.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        viewModel.clearAllAnnotations()
                                        showClearConfirm = false
                                    }) { Text("Clear all") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
                                },
                            )
                        }

                        if (uiState.annotations.isNotEmpty()) {
                            IconButton(onClick = { showClearConfirm = true }) {
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
                        .clipToBounds()
                        .pointerInput(vw, vh, uiState.rotationDegrees, rawWidth, rawHeight, shapeTouchPx) {
                            detectTapGestures { tap ->
                                val pixelShapes = currentAnnotations.map {
                                    it.shape.rotateNorm(uiState.rotationDegrees, rawWidth / rawHeight).toPixelSpace(vw, vh)
                                }
                                viewModel.selectAnnotation(findHitShape(pixelShapes, tap, shapeTouchPx))
                            }
                        }
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
                                },
                                onDragCancel = {
                                    activeDragTarget = null
                                    activeShapeDragIndex = null
                                    viewModel.onDragCancelled()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (exoPlayer != null) {
                        key(exoPlayer) {
                            AndroidView(
                                factory = { ctx ->
                                    TextureView(ctx).also { tv -> exoPlayer.setVideoTextureView(tv) }
                                },
                                onRelease = { tv ->
                                    runCatching { exoPlayer.clearVideoTextureView(tv) }
                                },
                                modifier = Modifier
                                    // requiredSize: must NOT be clamped by the parent box when sideways.
                                    .requiredSize(viewWidthDp, viewHeightDp)
                                    .graphicsLayer { rotationZ = uiState.rotationDegrees.toFloat() },
                            )
                        }
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
                        sourceWidthPx = effectiveWidth,
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