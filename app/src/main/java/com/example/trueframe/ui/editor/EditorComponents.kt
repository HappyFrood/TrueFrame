@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.trueframe.ui.editor

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trueframe.core.annotation.AnnotationColors
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.annotation.SpeedMath
import com.example.trueframe.core.annotation.rotateNorm
import java.util.Locale

internal val ToolButtonSize = 44.dp
internal val ToolRowHeight = 48.dp

private val tabular: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")

// ------------------------------------------------------------------ Readouts

internal fun timeReadout(positionMs: Long, durationMs: Long): String =
    String.format(Locale.US, "%.2f / %.2f s", positionMs / 1000f, durationMs / 1000f)

internal fun frameReadout(frameIdx: Int, fps: Float, fpsAssumed: Boolean, selectedDrawnFrame: Int?): String = when {
    selectedDrawnFrame != null -> String.format(Locale.US, "F %d · drawn F %d", frameIdx, selectedDrawnFrame)
    fpsAssumed -> String.format(Locale.US, "F %d · fps?", frameIdx)
    else -> String.format(Locale.US, "F %d · %.0f fps", frameIdx, fps)
}

@Composable
internal fun ScrubSlider(
    positionMs: Long,
    durationMs: Long,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(0f)),
        onValueChange = { onScrub(it.toLong()) },
        onValueChangeFinished = onScrubFinished,
        enabled = durationMs > 0,
        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
        modifier = modifier,
    )
}

/** Portrait scrub row (~40dp): time on the left, slider, frame readout on the right. */
@Composable
internal fun ScrubRow(
    timeText: String,
    frameText: String,
    positionMs: Long,
    durationMs: Long,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(timeText, style = tabular, color = contentColor, maxLines = 1, modifier = Modifier.widthIn(min = 84.dp))
        ScrubSlider(positionMs, durationMs, onScrub, onScrubFinished, Modifier.weight(1f).padding(horizontal = 6.dp))
        Text(frameText, style = tabular, color = contentColor, maxLines = 1)
    }
}

/** Transport row: −10, −1, Play/Pause, +1, +10. */
@Composable
internal fun TransportRow(
    isPlaying: Boolean,
    onStep: (Int) -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 52.dp,
    iconSize: Dp = 32.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(buttonSize),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(Icons.Default.FastRewind, "-10 Frames", buttonSize, iconSize, tint) { onStep(-10) }
        TransportButton(Icons.Default.ChevronLeft, "-1 Frame", buttonSize, iconSize, tint) { onStep(-1) }
        TransportButton(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            "Play/Pause", buttonSize, iconSize, tint, onPlayPause,
        )
        TransportButton(Icons.Default.ChevronRight, "+1 Frame", buttonSize, iconSize, tint) { onStep(1) }
        TransportButton(Icons.Default.FastForward, "+10 Frames", buttonSize, iconSize, tint) { onStep(10) }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, description: String, size: Dp, iconSize: Dp, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

// ------------------------------------------------------------------ Tools

internal enum class Tool { LINE, ANGLE, CIRCLE, ARROW, TEXT, GHOST, SPEED, GRID, DELETE }

internal data class ToolSpec(val tool: Tool, val icon: ImageVector, val description: String, val tint: Color?)

internal val ToolGroups: List<List<ToolSpec>> = listOf(
    listOf(
        ToolSpec(Tool.LINE, Icons.Default.Straighten, "Add Line", AnnotationColors.Line),
        ToolSpec(Tool.ANGLE, Icons.Default.SquareFoot, "Add Angle", AnnotationColors.Angle),
        ToolSpec(Tool.CIRCLE, Icons.Default.Adjust, "Add Circle", AnnotationColors.Circle),
        ToolSpec(Tool.ARROW, Icons.Default.NorthEast, "Add Arrow", AnnotationColors.Arrow),
        ToolSpec(Tool.TEXT, Icons.Default.TextFields, "Add Text", AnnotationColors.Text),
    ),
    listOf(
        ToolSpec(Tool.GHOST, Icons.Default.Layers, "Ghost Frame", null),
        ToolSpec(Tool.SPEED, Icons.Default.Speed, "Speed Calculator", null),
        ToolSpec(Tool.GRID, Icons.Default.GridOn, "Grid", null),
    ),
    listOf(
        ToolSpec(Tool.DELETE, Icons.Default.Delete, "Delete Annotation", null),
    ),
)

/** Tool state the rows need: which toggles are on and whether Delete is enabled. */
internal data class ToolState(val ghostOn: Boolean, val gridOn: Boolean, val canDelete: Boolean)

@Composable
internal fun ToolButton(
    spec: ToolSpec,
    state: ToolState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = when (spec.tool) {
        Tool.GHOST -> state.ghostOn
        Tool.GRID -> state.gridOn
        else -> false
    }
    val enabled = spec.tool != Tool.DELETE || state.canDelete
    val tint = when {
        spec.tool == Tool.DELETE -> if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
        active -> MaterialTheme.colorScheme.primary
        else -> spec.tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(ToolButtonSize)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent),
    ) {
        Icon(spec.icon, contentDescription = spec.description, tint = tint)
    }
}

/**
 * Portrait tool row (48dp): `Line · Angle · Circle · Arrow · Text | Ghost · Speed · Grid | Delete`.
 * Fixed slots; scrolls horizontally instead of shrinking targets below 44dp on narrow screens.
 */
@Composable
internal fun ToolRow(
    state: ToolState,
    onTool: (Tool) -> Unit,
    modifier: Modifier = Modifier,
    ghostAnchor: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(ToolRowHeight)) {
        val toolCount = ToolGroups.sumOf { it.size }
        val needed = ToolButtonSize * toolCount + 1.dp * (ToolGroups.size - 1) + 16.dp
        val fits = maxWidth >= needed
        val rowModifier = if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())
        Row(
            modifier = rowModifier.height(ToolRowHeight).padding(horizontal = 4.dp),
            horizontalArrangement = if (fits) Arrangement.SpaceEvenly else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolGroups.forEachIndexed { groupIdx, group ->
                if (groupIdx > 0) VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 2.dp))
                group.forEach { spec ->
                    Box {
                        ToolButton(spec, state, onClick = { onTool(spec.tool) })
                        if (spec.tool == Tool.GHOST) ghostAnchor()
                    }
                }
            }
        }
    }
}

/** Landscape side-panel tools in a 3-column grid. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolGrid(
    state: ToolState,
    onTool: (Tool) -> Unit,
    modifier: Modifier = Modifier,
    ghostAnchor: @Composable () -> Unit = {},
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        maxItemsInEachRow = 3,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToolGroups.flatten().forEach { spec ->
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ToolButton(spec, state, onClick = { onTool(spec.tool) })
                if (spec.tool == Tool.GHOST) ghostAnchor()
            }
        }
    }
}

// ------------------------------------------------------------------ Speed wizard

internal fun speedStepLabel(step: SpeedStep): String = when (step) {
    SpeedStep.REFERENCE -> "1/4 Reference"
    SpeedStep.LENGTH -> "2/4 Length"
    SpeedStep.BALL_START -> "3/4 Ball start"
    SpeedStep.BALL_END -> "4/4 Ball end"
    SpeedStep.RESULT -> "Result"
}

internal fun speedBannerText(step: SpeedStep): String = when (step) {
    SpeedStep.REFERENCE -> "Drag the line onto an object of known length (club, bat, racket)."
    SpeedStep.LENGTH -> "Enter the object's actual length."
    SpeedStep.BALL_START -> "Go to the frame where the ball starts, then tap the center of the ball."
    SpeedStep.BALL_END -> "Step forward a few frames, then tap the ball again."
    SpeedStep.RESULT -> "Speed result"
}

/** Replaces the tool row at the same height: `Cancel · step label · Back · Next`. */
@Composable
internal fun SpeedWizardRow(
    speed: SpeedState,
    canNext: Boolean,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(ToolRowHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Text(
            speedStepLabel(speed.step),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onBack, enabled = speed.step != SpeedStep.REFERENCE) { Text("Back") }
        TextButton(onClick = onNext, enabled = canNext && speed.step != SpeedStep.RESULT) { Text("Next") }
    }
}

/** Slim instruction banner floating over the top of the video (takes no layout space). */
@Composable
internal fun SpeedBanner(
    speed: SpeedState,
    onUsePrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        contentColor = Color.White,
        shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(speedBannerText(speed.step), style = MaterialTheme.typography.bodySmall)
            speed.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (speed.step == SpeedStep.REFERENCE && speed.canUsePreviousReference) {
                TextButton(onClick = onUsePrevious, modifier = Modifier.height(36.dp)) { Text("Use previous reference") }
            }
        }
    }
}

internal val FpsChoices = listOf(30f, 60f, 120f, 240f, 480f)

/** Result card overlaid at the bottom of the video area. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeedResultCard(
    speed: SpeedState,
    result: SpeedMath.Result?,
    onFps: (Float) -> Unit,
    onCustomFps: () -> Unit,
    onRedo: () -> Unit,
    onAddLabel: () -> Unit,
    onDone: () -> Unit,
    onTips: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.8f),
        contentColor = Color.White,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.padding(8.dp).fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val headline = when {
                    !speed.fpsConfirmed -> "Pick the frame rate"
                    result is SpeedMath.Result.Speed -> EditorViewModel.formatMph(result.mph)
                    result is SpeedMath.Result.Error -> SpeedMath.errorMessage(result)
                    else -> "—"
                }
                Text(
                    headline,
                    fontSize = if (result is SpeedMath.Result.Speed && speed.fpsConfirmed) 34.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    style = tabular,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTips, modifier = Modifier.size(ToolButtonSize)) {
                    Icon(Icons.Default.Info, contentDescription = "Speed tips")
                }
            }
            if (result is SpeedMath.Result.Speed && speed.fpsConfirmed) {
                Text(
                    String.format(
                        Locale.US, "Ball moved %.1f in over %d frames (%.3f s @ %.0f fps)",
                        result.ballInches, result.frames, result.seconds, result.fps,
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (speed.fpsConfirmed) String.format(Locale.US, "%.0f fps", speed.fps) else "fps: Unconfirmed",
                    style = tabular,
                    color = if (speed.fpsConfirmed) Color.White else MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onCustomFps).padding(end = 8.dp, top = 12.dp, bottom = 12.dp),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FpsChoices.forEach { fps ->
                    FilterChip(
                        selected = speed.fpsConfirmed && speed.fps == fps,
                        onClick = { onFps(fps) },
                        label = { Text(fps.toInt().toString()) },
                    )
                }
                FilterChip(selected = false, onClick = onCustomFps, label = { Text("Custom") })
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRedo) { Text("Redo ball") }
                TextButton(
                    onClick = onAddLabel,
                    enabled = speed.fpsConfirmed && result is SpeedMath.Result.Speed,
                ) { Text("Add as label") }
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

@Composable
internal fun SpeedTipsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tips for accurate speed") },
        text = {
            Text(
                "• Keep the reference object at the same distance from the camera as the ball path.\n" +
                    "• The ball should travel roughly across the frame, not toward or away from the camera.\n" +
                    "• Tap the center of any blur streak.\n" +
                    "• Use points at least 5 frames apart while the ball is still flying straight.\n" +
                    "• Slow-motion clips are often saved at 30 fps — pick the capture frame rate."
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

internal data class LengthPreset(val label: String, val inches: Float)

internal val LengthPresets = listOf(
    LengthPreset("Driver 45\"", 45f),
    LengthPreset("Tennis racket 27\"", 27f),
    LengthPreset("Baseball bat 33\"", 33f),
    LengthPreset("Pickleball paddle 16\"", 16f),
)

/** Step 2: "Actual length (inches)", 1–600, with editable typical-value chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KnownLengthDialog(
    initialInches: Float?,
    error: String?,
    onNext: (String) -> Unit,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf(initialInches?.let { formatInches(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("Known length") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                    singleLine = true,
                    label = { Text("Actual length (inches)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = error != null,
                    supportingText = { Text(error ?: "Typical values — edit to match your object") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LengthPresets.forEach { preset ->
                        SuggestionChip(onClick = { text = formatInches(preset.inches) }, label = { Text(preset.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onNext(text) }, enabled = text.isNotBlank()) { Text("Next") } },
        dismissButton = { TextButton(onClick = onBack) { Text("Back") } },
    )
}

private fun formatInches(inches: Float): String =
    if (inches % 1f == 0f) inches.toInt().toString() else String.format(Locale.US, "%.1f", inches)

@Composable
internal fun CustomFpsDialog(initialFps: Float, onSave: (Float) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%.0f", initialFps)) }
    val value = text.replace(',', '.').toFloatOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frame rate") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) },
                singleLine = true,
                label = { Text("Frames per second") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null && value > 0f) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ Text dialog

@Composable
internal fun TextAnnotationDialog(
    state: TextDialogState,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(state) { mutableStateOf(state.initialText) }
    val valid = AnnotationShape.Text.sanitize(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.editingId == null) "Add text" else "Edit text") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace("\n", " ").take(AnnotationShape.Text.MAX_LENGTH) },
                singleLine = true,
                label = { Text("Text") },
                supportingText = { Text("${text.length} / ${AnnotationShape.Text.MAX_LENGTH}") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ Ghost

/** Popover anchored to the Ghost button (or chip): opacity, re-capture, turn off. */
@Composable
internal fun GhostMenu(
    expanded: Boolean,
    opacity: Float,
    onOpacity: (Float) -> Unit,
    onRecapture: () -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.width(240.dp).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(String.format(Locale.US, "Opacity %d%%", (opacity * 100).toInt()), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = opacity,
                onValueChange = onOpacity,
                valueRange = EditorUiState.MIN_GHOST_OPACITY..EditorUiState.MAX_GHOST_OPACITY,
            )
        }
        DropdownMenuItem(text = { Text("Re-capture from this frame") }, onClick = onRecapture)
        DropdownMenuItem(text = { Text("Turn off") }, onClick = onTurnOff)
    }
}

@Composable
internal fun GhostChip(frameIndex: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        modifier = modifier.height(32.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(String.format(Locale.US, "Ghost · F %d", frameIndex), style = tabular)
        }
    }
}

// ------------------------------------------------------------------ Overlays

/**
 * Screen-aligned grid over the displayed video box: 8 square columns, as many rows as fit at the
 * same pitch, centered vertically. 1dp white lines at 22% alpha; center lines at 45%.
 * Never rotates with the video, ignores touches and is never exported.
 */
@Composable
internal fun GridOverlay(modifier: Modifier = Modifier) {
    val strokePx = with(LocalDensity.current) { 1.dp.toPx() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val pitch = w / 8f
        val faint = Color.White.copy(alpha = 0.22f)
        val strong = Color.White.copy(alpha = 0.45f)
        for (i in 1 until 8) {
            val x = i * pitch
            drawLine(if (i == 4) strong else faint, Offset(x, 0f), Offset(x, h), strokeWidth = strokePx)
        }
        val cy = h / 2f
        drawLine(strong, Offset(0f, cy), Offset(w, cy), strokeWidth = strokePx)
        var k = 1
        while (k * pitch < cy) {
            drawLine(faint, Offset(0f, cy - k * pitch), Offset(w, cy - k * pitch), strokeWidth = strokePx)
            drawLine(faint, Offset(0f, cy + k * pitch), Offset(w, cy + k * pitch), strokeWidth = strokePx)
            k++
        }
    }
}

/** Temporary Speed-mode reference line and ball markers. Never saved or exported. */
@Composable
internal fun SpeedOverlay(
    speed: SpeedState,
    rotationDegrees: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val lineStroke = with(density) { 3.dp.toPx() }
    val handleR = with(density) { 9.dp.toPx() }
    val markerR = with(density) { 14.dp.toPx() }
    val labelPaint = remember(density) {
        Paint().apply {
            isAntiAlias = true
            isFakeBoldText = true
            textSize = with(density) { 13.dp.toPx() }
            color = android.graphics.Color.WHITE
        }
    }
    val bgPaint = remember { Paint().apply { color = android.graphics.Color.argb(153, 0, 0, 0); isAntiAlias = true } }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        fun px(p: Offset): Offset = p.rotateNorm(rotationDegrees).let { Offset(it.x * w, it.y * h) }

        val editingReference = speed.step == SpeedStep.REFERENCE
        val a = px(speed.referenceStart)
        val b = px(speed.referenceEnd)
        val refColor = Color.White.copy(alpha = if (editingReference) 1f else 0.5f)
        drawLine(refColor, a, b, strokeWidth = lineStroke, cap = StrokeCap.Round)
        if (editingReference) {
            listOf(a, b).forEach {
                drawCircle(Color.White, handleR, it)
                drawCircle(Color.Black.copy(alpha = 0.7f), handleR * 0.55f, it)
            }
        }

        fun marker(m: SpeedMarker, label: String) {
            val c = px(m.position)
            drawCircle(Color.White, markerR, c, style = Stroke(width = lineStroke))
            drawLine(Color.White, c - Offset(markerR * 0.6f, 0f), c + Offset(markerR * 0.6f, 0f), strokeWidth = lineStroke / 2f)
            drawLine(Color.White, c - Offset(0f, markerR * 0.6f), c + Offset(0f, markerR * 0.6f), strokeWidth = lineStroke / 2f)
            val text = String.format(Locale.US, "%s · F %d", label, m.frameIndex)
            val tw = labelPaint.measureText(text)
            val fm = labelPaint.fontMetrics
            val left = c.x + markerR + 4f
            val baseline = c.y - markerR
            drawContext.canvas.nativeCanvas.drawRoundRect(
                left, baseline + fm.ascent - 4f, left + tw + 12f, baseline + fm.descent + 4f, 8f, 8f, bgPaint,
            )
            drawContext.canvas.nativeCanvas.drawText(text, left + 6f, baseline, labelPaint)
        }
        speed.markerA?.let { marker(it, "A") }
        speed.markerB?.let { marker(it, "B") }
    }
}
