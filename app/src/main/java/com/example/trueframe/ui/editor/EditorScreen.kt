@file:Suppress("UnsafeOptInUsageError")
@file:OptIn(ExperimentalLayoutApi::class)

package com.example.trueframe.ui.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.example.trueframe.core.annotation.AnnotationOverlay
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.annotation.HitTesting
import com.example.trueframe.core.annotation.TextPill
import com.example.trueframe.core.annotation.rotateNorm
import com.example.trueframe.core.annotation.rotateVectorNorm
import com.example.trueframe.core.annotation.textPillRect
import com.example.trueframe.core.annotation.toPixelSpace
import com.example.trueframe.core.video.FrameMath
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds

private enum class GhostMenuAnchor { NONE, BUTTON, CHIP }

private enum class SpeedDragTarget { REF_START, REF_END, REF_LINE, MARKER }

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
    val ghostBitmap by viewModel.ghostBitmap.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Drag targets are tracked by annotation ID, never by list index (indices shift on re-emit).
    var activeDragTarget by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var activeShapeDragId by remember { mutableStateOf<Long?>(null) }
    var speedDrag by remember { mutableStateOf<SpeedDragTarget?>(null) }
    // Frame snapshot for the Speed-mode magnifier; only held while a handle or marker is dragged.
    var loupeFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val latestState by rememberUpdatedState(uiState)

    var currentPositionMs by rememberSaveable { mutableLongStateOf(0L) }
    var scrubPositionMs by rememberSaveable { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }

    var showClearConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInputText by remember { mutableStateOf("") }
    var showOverflow by remember { mutableStateOf(false) }
    var ghostMenuAnchor by remember { mutableStateOf(GhostMenuAnchor.NONE) }
    var showSpeedTips by remember { mutableStateOf(false) }
    var showCustomFps by remember { mutableStateOf(false) }
    var focusMode by rememberSaveable { mutableStateOf(false) }

    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }
    var pixelRatio by remember { mutableFloatStateOf(1f) }
    val frameRate = uiState.frameRate
    val currentFrameRate by rememberUpdatedState(frameRate)

    val currentFrameIdx = FrameMath.frameForMs(currentPositionMs, frameRate)

    // Kept so Ghost Frame can grab exactly the displayed frame via TextureView.getBitmap().
    var textureView by remember { mutableStateOf<TextureView?>(null) }

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

    fun togglePlay() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun onScrub(newMs: Long) {
        val player = exoPlayer ?: return
        if (!isScrubbing) {
            isScrubbing = true
            viewModel.selectAnnotation(null)
            player.pause()
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
        scrubPositionMs = newMs
        player.seekTo(scrubPositionMs)
    }

    fun onScrubFinished() {
        val player = exoPlayer ?: return
        player.setSeekParameters(SeekParameters.EXACT)
        val snapped = FrameMath.msForFrame(
            FrameMath.frameForMs(scrubPositionMs, frameRate), frameRate
        ).coerceIn(0L, totalDurationMs)
        player.seekTo(snapped)
        currentPositionMs = snapped
        isScrubbing = false
    }

    DisposableEffect(exoPlayer) {
        val player = exoPlayer ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoW = size.width
                videoH = size.height
                pixelRatio = if (size.pixelWidthHeightRatio > 0f) size.pixelWidthHeightRatio else 1f
                viewModel.onPlayerFrameRate(player.videoFormat?.frameRate)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    viewModel.selectAnnotation(null)
                } else if (!player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
                    val fps = currentFrameRate
                    val idx = FrameMath.frameForMs(player.currentPosition, fps)
                    val snapped = FrameMath.msForFrame(idx, fps).coerceIn(0L, totalDurationMs.coerceAtLeast(0L))
                    player.setSeekParameters(SeekParameters.EXACT)
                    player.seekTo(snapped)
                    currentPositionMs = snapped
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    totalDurationMs = player.duration.coerceAtLeast(1L)
                    viewModel.onPlayerFrameRate(player.videoFormat?.frameRate)
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

    // Save the position on ON_STOP too, so it survives the app being backgrounded and killed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer?.pause()
                viewModel.saveLastPosition(currentPositionMs)
            }
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
            delay(16.milliseconds)
        }
    }

    LaunchedEffect(uiState.currentTimeMs) {
        if (currentPositionMs == 0L && uiState.currentTimeMs > 0L) {
            currentPositionMs = uiState.currentTimeMs
            exoPlayer?.seekTo(currentPositionMs)
        }
    }

    LaunchedEffect(currentPositionMs, totalDurationMs, isPlaying) {
        viewModel.updatePlayerState(currentPositionMs, totalDurationMs, isPlaying)
    }

    // Saved in the application scope by the ViewModel, so it completes after the screen is gone.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveLastPosition(currentPositionMs)
        }
    }

    val rawWidth = if (videoW > 0) videoW * pixelRatio else 1080f
    val rawHeight = if (videoH > 0) videoH.toFloat() else 1920f
    LaunchedEffect(videoW, videoH, pixelRatio) {
        if (videoW > 0 && videoH > 0) viewModel.setSourceSize(rawWidth, rawHeight)
    }

    // Focus mode: immersive system bars with transient swipe-to-show.
    val view = LocalView.current
    DisposableEffect(focusMode) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (focusMode) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(enabled = focusMode) { focusMode = false }

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.userMessage) {
        val msg = uiState.userMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    fun share() {
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

    fun openRename() {
        renameInputText = uiState.projectName.ifEmpty { "Project $projectId" }
        showRenameDialog = true
    }

    fun captureGhost() {
        viewModel.captureGhost(textureView?.bitmap)
    }

    fun onGhostTapped(anchor: GhostMenuAnchor) {
        if (uiState.isGhostOn) ghostMenuAnchor = anchor else captureGhost()
    }

    fun onTool(tool: Tool) {
        when (tool) {
            Tool.LINE -> viewModel.addLine()
            Tool.ANGLE -> viewModel.addAngle()
            Tool.CIRCLE -> viewModel.addCircle()
            Tool.ARROW -> viewModel.addArrow()
            Tool.TEXT -> viewModel.openAddTextDialog()
            Tool.GHOST -> onGhostTapped(GhostMenuAnchor.BUTTON)
            Tool.GRID -> viewModel.toggleGrid()
            Tool.DELETE -> if (uiState.selectedAnnotationId != null) {
                viewModel.deleteSelectedAnnotation()
            } else {
                coroutineScope.launch { snackbarHostState.showSnackbar("Select a shape to delete, or long-press to delete all") }
            }
        }
    }

    fun toggleSpeedMode() {
        if (uiState.speed != null) {
            viewModel.exitSpeedMode()
        } else {
            exoPlayer?.pause()
            viewModel.enterSpeedMode()
        }
    }

    val ghostMenu: @Composable (GhostMenuAnchor) -> Unit = { anchor ->
        GhostMenu(
            expanded = ghostMenuAnchor == anchor,
            opacity = uiState.ghostOpacity,
            onOpacity = viewModel::setGhostOpacity,
            onRecapture = {
                captureGhost()
                ghostMenuAnchor = GhostMenuAnchor.NONE
            },
            onTurnOff = {
                viewModel.turnOffGhost()
                ghostMenuAnchor = GhostMenuAnchor.NONE
            },
            onDismiss = { ghostMenuAnchor = GhostMenuAnchor.NONE },
        )
    }

    // ------------------------------------------------------------------ Dialogs

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
                TextButton(onClick = {
                    viewModel.renameProject(renameInputText)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
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

    uiState.textDialog?.let { dialog ->
        TextAnnotationDialog(
            state = dialog,
            onSave = viewModel::submitTextDialog,
            onDismiss = viewModel::dismissTextDialog,
        )
    }

    val speed = uiState.speed
    if (speed != null && speed.step == SpeedStep.LENGTH) {
        KnownLengthDialog(
            initialInches = speed.knownInches,
            error = speed.error,
            onNext = { viewModel.submitKnownLength(it) },
            onBack = viewModel::speedBack,
        )
    }
    if (showSpeedTips) SpeedTipsDialog(onDismiss = { showSpeedTips = false })
    if (showCustomFps && speed != null) {
        CustomFpsDialog(
            initialFps = speed.fps,
            onSave = {
                viewModel.setSpeedFps(it)
                showCustomFps = false
            },
            onDismiss = { showCustomFps = false },
        )
    }

    // ------------------------------------------------------------------ Shared pieces

    val timeText = timeReadout(if (isScrubbing) scrubPositionMs else currentPositionMs, totalDurationMs)
    val frameText = frameReadout(currentFrameIdx, uiState.selectedAnnotation?.frameIndex)
    val sliderMs = if (isScrubbing) scrubPositionMs else currentPositionMs
    val toolState = ToolState(
        ghostOn = uiState.isGhostOn,
        gridOn = uiState.showGrid,
        canDelete = uiState.selectedAnnotationId != null,
        hasAnnotations = uiState.annotations.isNotEmpty(),
    )

    val headerActions: @Composable () -> Unit = {
        IconButton(onClick = { focusMode = true }, modifier = Modifier.size(ToolButtonSize)) {
            Icon(Icons.Default.Fullscreen, contentDescription = "Focus mode")
        }
        val speedOn = uiState.speed != null
        IconButton(
            onClick = ::toggleSpeedMode,
            modifier = Modifier
                .size(ToolButtonSize)
                .clip(CircleShape)
                .background(if (speedOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent),
        ) {
            Icon(
                Icons.Default.Speed,
                contentDescription = "Speed Calculator",
                tint = if (speedOn) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        IconButton(onClick = { viewModel.rotateVideo() }, modifier = Modifier.size(ToolButtonSize)) {
            Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate Video")
        }
        IconButton(onClick = ::share, modifier = Modifier.size(ToolButtonSize)) {
            Icon(Icons.Default.Share, contentDescription = "Share Frame")
        }
        Box {
            IconButton(onClick = { showOverflow = true }, modifier = Modifier.size(ToolButtonSize)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        showOverflow = false
                        openRename()
                    },
                )
            }
        }
    }

    val projectTitle = uiState.projectName.ifEmpty { "Project $projectId" }

    val toolsOrWizard: @Composable (landscape: Boolean) -> Unit = { landscape ->
        if (speed != null) {
            if (landscape) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(speedStepLabel(speed.step), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                    Row {
                        TextButton(onClick = viewModel::speedBack, enabled = speed.step != SpeedStep.REFERENCE) { Text("Back") }
                        TextButton(
                            onClick = viewModel::speedNext,
                            enabled = viewModel.canAdvanceSpeed(speed) && speed.step != SpeedStep.RESULT,
                        ) { Text("Next") }
                    }
                    TextButton(onClick = viewModel::exitSpeedMode) { Text("Cancel") }
                }
            } else {
                SpeedWizardRow(
                    speed = speed,
                    canNext = viewModel.canAdvanceSpeed(speed),
                    onCancel = viewModel::exitSpeedMode,
                    onBack = viewModel::speedBack,
                    onNext = viewModel::speedNext,
                )
            }
        } else if (landscape) {
            ToolGrid(toolState, ::onTool, onDeleteAll = { showClearConfirm = true }, ghostAnchor = { ghostMenu(GhostMenuAnchor.BUTTON) })
        } else {
            ToolRow(toolState, ::onTool, onDeleteAll = { showClearConfirm = true }, ghostAnchor = { ghostMenu(GhostMenuAnchor.BUTTON) })
        }
    }

    // ------------------------------------------------------------------ Video area

    val videoArea: @Composable (Modifier) -> Unit = { areaModifier ->
        BoxWithConstraints(
            modifier = areaModifier.background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val containerWidth = maxWidth
            val containerHeight = maxHeight
            val density = LocalDensity.current.density
            val handleTouchPx = with(LocalDensity.current) { 24.dp.toPx() }
            val speedHandleTouchPx = with(LocalDensity.current) { 36.dp.toPx() }
            val shapeTouchPx = with(LocalDensity.current) { 20.dp.toPx() }
            val hitPaint = remember { TextPill.newPaint() }

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

            val viewWidthDp = if (isSideways) fittedHeightDp else fittedWidthDp
            val viewHeightDp = if (isSideways) fittedWidthDp else fittedHeightDp
            val frameAspect = rawWidth / rawHeight

            fun pixelShapes(): List<AnnotationShape> = latestState.annotations.map {
                it.shape.rotateNorm(latestState.rotationDegrees, frameAspect).toPixelSpace(vw, vh)
            }
            fun textRects(shapes: List<AnnotationShape>): List<Rect?> = shapes.map { s ->
                if (s is AnnotationShape.Text) textPillRect(s, vw, vh, hitPaint) else null
            }
            fun unrotated(p: Offset): Offset = Offset(p.x / vw, p.y / vh).rotateNorm(-latestState.rotationDegrees)

            Box(
                modifier = Modifier
                    .size(fittedWidthDp, fittedHeightDp)
                    .clipToBounds()
                    .pointerInput(vw, vh, frameAspect, shapeTouchPx) {
                        detectTapGestures { tap ->
                            val state = latestState
                            val speedState = state.speed
                            if (speedState != null) {
                                if (speedState.step == SpeedStep.BALL_START || speedState.step == SpeedStep.BALL_END) {
                                    viewModel.placeSpeedMarker(unrotated(tap))
                                }
                                return@detectTapGestures
                            }
                            val shapes = pixelShapes()
                            val hitIdx = findHitShape(shapes, textRects(shapes), tap, shapeTouchPx)
                            val hitItem = hitIdx?.let { state.annotations.getOrNull(it) }
                            if (hitItem != null && hitItem.id == state.selectedAnnotationId && hitItem.shape is AnnotationShape.Text) {
                                viewModel.openEditTextDialog(hitItem.id)
                            } else {
                                viewModel.selectAnnotation(hitItem?.id)
                            }
                        }
                    }
                    .pointerInput(vw, vh, frameAspect, handleTouchPx, shapeTouchPx) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val state = latestState
                                val speedState = state.speed
                                if (speedState != null) {
                                    fun px(p: Offset) = p.rotateNorm(state.rotationDegrees).let { Offset(it.x * vw, it.y * vh) }
                                    speedDrag = when (speedState.step) {
                                        SpeedStep.REFERENCE -> {
                                            val a = px(speedState.referenceStart)
                                            val b = px(speedState.referenceEnd)
                                            val da = (startOffset - a).getDistance()
                                            val db = (startOffset - b).getDistance()
                                            when {
                                                da <= speedHandleTouchPx && da <= db -> SpeedDragTarget.REF_START
                                                db <= speedHandleTouchPx -> SpeedDragTarget.REF_END
                                                HitTesting.distanceTo(AnnotationShape.Line(a, b), startOffset) <= shapeTouchPx -> SpeedDragTarget.REF_LINE
                                                else -> null
                                            }
                                        }
                                        SpeedStep.BALL_START, SpeedStep.BALL_END -> {
                                            val marker = if (speedState.step == SpeedStep.BALL_START) speedState.markerA else speedState.markerB
                                            // Grab an existing marker near the finger and move it relative to the finger,
                                            // otherwise drop it where the drag started and move it from there.
                                            if (marker == null || (startOffset - px(marker.position)).getDistance() > speedHandleTouchPx * 1.5f) {
                                                viewModel.placeSpeedMarker(unrotated(startOffset))
                                            } else {
                                                viewModel.offsetSpeedMarker(Offset.Zero)
                                            }
                                            SpeedDragTarget.MARKER
                                        }
                                        else -> null
                                    }
                                    if (speedDrag != null && speedDrag != SpeedDragTarget.REF_LINE) {
                                        loupeFrame = textureView?.bitmap?.asImageBitmap()
                                    }
                                    return@detectDragGestures
                                }
                                viewModel.onDragStarted()
                                val shapes = pixelShapes()
                                val rects = textRects(shapes)
                                val handleHit = findHitHandle(shapes, rects, startOffset, handleTouchPx)
                                if (handleHit != null) {
                                    val id = state.annotations.getOrNull(handleHit.first)?.id
                                    activeDragTarget = id?.let { it to handleHit.second }
                                    activeShapeDragId = null
                                    viewModel.selectAnnotation(id)
                                } else {
                                    activeDragTarget = null
                                    val id = findHitShape(shapes, rects, startOffset, shapeTouchPx)
                                        ?.let { state.annotations.getOrNull(it)?.id }
                                    activeShapeDragId = id
                                    viewModel.selectAnnotation(id)
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val rotation = latestState.rotationDegrees
                                val unrotatedPos = unrotated(change.position)
                                val unrotatedDelta = Offset(dragAmount.x / vw, dragAmount.y / vh).rotateVectorNorm(-rotation)

                                if (latestState.speed != null) {
                                    // Relative moves: the point never jumps under the finger, so the
                                    // user can grab slightly off the end and still see it.
                                    when (speedDrag) {
                                        SpeedDragTarget.REF_START -> viewModel.offsetReferenceHandle(0, unrotatedDelta)
                                        SpeedDragTarget.REF_END -> viewModel.offsetReferenceHandle(1, unrotatedDelta)
                                        SpeedDragTarget.REF_LINE -> viewModel.offsetReference(unrotatedDelta)
                                        SpeedDragTarget.MARKER -> viewModel.offsetSpeedMarker(unrotatedDelta)
                                        null -> Unit
                                    }
                                    return@detectDragGestures
                                }

                                activeDragTarget?.let { (id, handleIdx) ->
                                    // The aspect correction for the handle update needs to be the original aspect
                                    viewModel.updateShapeHandle(id, handleIdx, unrotatedPos, aspectCorrection = rawHeight / rawWidth)
                                } ?: activeShapeDragId?.let { id ->
                                    viewModel.offsetShape(id, unrotatedDelta)
                                }
                            },
                            onDragEnd = {
                                speedDrag = null
                                loupeFrame = null
                                val draggedId = activeDragTarget?.first ?: activeShapeDragId
                                activeDragTarget = null
                                activeShapeDragId = null
                                if (latestState.speed == null) viewModel.persistAnnotationsOnDragEnd(draggedId)
                            },
                            onDragCancel = {
                                speedDrag = null
                                loupeFrame = null
                                activeDragTarget = null
                                activeShapeDragId = null
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
                                TextureView(ctx).also { tv ->
                                    exoPlayer.setVideoTextureView(tv)
                                    textureView = tv
                                }
                            },
                            onRelease = { tv ->
                                runCatching { exoPlayer.clearVideoTextureView(tv) }
                                if (textureView === tv) textureView = null
                            },
                            modifier = Modifier
                                // requiredSize: must NOT be clamped by the parent box when sideways.
                                .requiredSize(viewWidthDp, viewHeightDp)
                                .graphicsLayer { rotationZ = uiState.rotationDegrees.toFloat() },
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (uiState.loadError != null) "Error: ${uiState.loadError}" else "Loading video...", color = Color.White)
                    }
                }

                // Ghost: above the video, below grid and annotations. Same modifiers as the TextureView.
                val ghost = ghostBitmap
                if (uiState.isGhostOn && ghost != null && !ghost.isRecycled) {
                    val image = remember(ghost) { ghost.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        alpha = uiState.ghostOpacity,
                        modifier = Modifier
                            .requiredSize(viewWidthDp, viewHeightDp)
                            .graphicsLayer { rotationZ = uiState.rotationDegrees.toFloat() },
                    )
                }

                if (uiState.showGrid) GridOverlay(Modifier.fillMaxSize())

                val selectedIdx = uiState.selectedAnnotationIndex
                val dragIdx = activeDragTarget?.let { (id, handle) ->
                    uiState.annotations.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { it to handle }
                }
                AnnotationOverlay(
                    shapes = uiState.annotations.map { it.shape },
                    selectedIndex = selectedIdx,
                    activeDragTarget = dragIdx,
                    showHandles = !isPlaying && speed == null,
                    rotationDegrees = uiState.rotationDegrees,
                    frameAspect = frameAspect,
                    sourceWidthPx = effectiveWidth,
                    modifier = Modifier.fillMaxSize()
                )

                if (speed != null) {
                    SpeedOverlay(speed, uiState.rotationDegrees, Modifier.fillMaxSize())
                }
            }

            // Magnifier while dragging a Speed handle/marker, placed on the side away from the finger.
            val loupe = loupeFrame
            val loupeFocus = speed?.let { sp ->
                when (speedDrag) {
                    SpeedDragTarget.REF_START -> sp.referenceStart
                    SpeedDragTarget.REF_END -> sp.referenceEnd
                    SpeedDragTarget.MARKER -> if (sp.step == SpeedStep.BALL_START) sp.markerA?.position else sp.markerB?.position
                    else -> null
                }
            }
            if (loupe != null && speed != null && loupeFocus != null) {
                val onLeft = loupeFocus.rotateNorm(uiState.rotationDegrees).x < 0.5f
                SpeedLoupe(
                    frame = loupe,
                    focus = loupeFocus,
                    speed = speed,
                    rotationDegrees = uiState.rotationDegrees,
                    modifier = Modifier
                        .align(if (onLeft) Alignment.TopEnd else Alignment.TopStart)
                        .padding(top = 72.dp, start = 12.dp, end = 12.dp),
                )
            }

            // ---- Floating overlays: never change the video box size.
            if (speed != null) {
                SpeedBanner(
                    speed = speed,
                    onUsePrevious = viewModel::usePreviousReference,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            val chipFrame = uiState.ghostFrameIndex
            if (chipFrame != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = if (speed != null) 64.dp else 8.dp)
                ) {
                    GhostChip(chipFrame, onClick = { onGhostTapped(GhostMenuAnchor.CHIP) })
                    ghostMenu(GhostMenuAnchor.CHIP)
                }
            }
            if (focusMode) {
                IconButton(
                    onClick = { focusMode = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(ToolButtonSize)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "Exit focus mode", tint = Color.White)
                }
            }
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                if (speed != null && speed.step == SpeedStep.RESULT) {
                    SpeedResultCard(
                        speed = speed,
                        result = uiState.speedResult,
                        onFps = viewModel::setSpeedFps,
                        onCustomFps = { showCustomFps = true },
                        onRedo = viewModel::redoBall,
                        onAddLabel = viewModel::addSpeedAsLabel,
                        onDone = viewModel::exitSpeedMode,
                        onTips = { showSpeedTips = true },
                    )
                }
                if (focusMode) {
                    // Only a translucent scrub + transport strip stays in Focus mode.
                    Column(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f))) {
                        ScrubRow(timeText, frameText, sliderMs, totalDurationMs, ::onScrub, ::onScrubFinished, contentColor = Color.White)
                        TransportRow(isPlaying, ::seekToFrame, ::togglePlay, tint = Color.White)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Layout

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            when {
                focusMode -> videoArea(Modifier.fillMaxSize())
                landscape -> Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    videoArea(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        modifier = Modifier
                            .width(200.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack, modifier = Modifier.size(ToolButtonSize)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Text(
                                projectTitle,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).clickable(onClick = ::openRename),
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { headerActions() }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(timeText, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                            Text(frameText, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                        }
                        ScrubSlider(sliderMs, totalDurationMs, ::onScrub, ::onScrubFinished, Modifier.fillMaxWidth())
                        TransportRow(isPlaying, ::seekToFrame, ::togglePlay, buttonSize = 40.dp, iconSize = 26.dp)
                        HorizontalDivider()
                        toolsOrWizard(true)
                    }
                }
                else -> Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    // Header, 48dp: Back · name (tap to rename) · Focus · Rotate · Share · ⋮
                    Row(
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(ToolButtonSize)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            projectTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).clickable(onClick = ::openRename).padding(horizontal = 4.dp),
                        )
                        headerActions()
                    }
                    videoArea(Modifier.fillMaxWidth().weight(1f))
                    ScrubRow(timeText, frameText, sliderMs, totalDurationMs, ::onScrub, ::onScrubFinished)
                    TransportRow(isPlaying, ::seekToFrame, ::togglePlay)
                    toolsOrWizard(false)
                    Spacer(Modifier.height(2.dp))
                }
            }
            SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun findHitHandle(
    pixelShapes: List<AnnotationShape>,
    textRects: List<Rect?>,
    touch: Offset,
    slopPx: Float,
): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    var bestDist = Float.MAX_VALUE
    pixelShapes.forEachIndexed { shapeIdx, shape ->
        val handles = when (shape) {
            is AnnotationShape.Line -> listOf(shape.start, shape.end)
            is AnnotationShape.Arrow -> listOf(shape.start, shape.end)
            is AnnotationShape.Angle -> listOf(shape.start, shape.center, shape.end)
            is AnnotationShape.Circle -> listOf(shape.center, shape.center + Offset(shape.radius, 0f))
            // One handle at the pill's (clamped) top-left; dragging the body is handled as a move.
            is AnnotationShape.Text -> listOf(textRects.getOrNull(shapeIdx)?.topLeft ?: shape.anchor)
        }
        handles.forEachIndexed { handleIdx, p ->
            val d = hypot((touch.x - p.x).toDouble(), (touch.y - p.y).toDouble()).toFloat()
            if (d <= slopPx && d <= bestDist) {
                bestDist = d
                best = shapeIdx to handleIdx
            }
        }
    }
    return best
}

private fun findHitShape(
    pixelShapes: List<AnnotationShape>,
    textRects: List<Rect?>,
    touch: Offset,
    slopPx: Float,
): Int? {
    var minDistance = Float.MAX_VALUE
    var bestIndex: Int? = null
    pixelShapes.forEachIndexed { index, shape ->
        val dist = HitTesting.distanceTo(shape, touch, textRects.getOrNull(index))
        if (dist <= slopPx && dist <= minDistance) {
            minDistance = dist
            bestIndex = index
        }
    }
    return bestIndex
}
