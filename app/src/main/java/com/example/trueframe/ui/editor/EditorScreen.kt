package com.example.trueframe.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ModeEdit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trueframe.core.annotation.AnnotationOverlay

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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Editor - Project $projectId") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.prevFrame() }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Frame")
                    }
                    
                    Row {
                        IconButton(onClick = { viewModel.addLine() }) {
                            Icon(Icons.Default.ModeEdit, contentDescription = "Add Line")
                        }
                        IconButton(onClick = { viewModel.addAngle() }) {
                            Icon(Icons.Default.Edit, contentDescription = "Add Angle")
                        }
                        IconButton(onClick = { viewModel.addCircle() }) {
                            Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "Add Circle")
                        }
                    }

                    IconButton(onClick = { viewModel.nextFrame() }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next Frame")
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
            ) {
                if (uiState.currentFrame != null) {
                    Image(
                        bitmap = uiState.currentFrame!!.asImageBitmap(),
                        contentDescription = "Video Frame",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (uiState.error != null) {
                            Text("Error: ${uiState.error}")
                        } else {
                            Text("Loading frame ${uiState.frameIndex}...")
                        }
                    }
                }

                AnnotationOverlay(
                    shapes = uiState.annotations,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
