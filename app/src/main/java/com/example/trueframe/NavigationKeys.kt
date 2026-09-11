package com.example.trueframe

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey

@Serializable data class Editor(val projectId: Long) : NavKey
