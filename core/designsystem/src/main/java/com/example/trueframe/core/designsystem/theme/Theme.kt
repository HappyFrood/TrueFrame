package com.example.trueframe.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = TrueFramePrimaryDark,
    onPrimary = TrueFrameOnPrimaryDark,
    primaryContainer = TrueFramePrimaryContainerDark,
    onPrimaryContainer = TrueFrameOnPrimaryContainerDark,
    secondary = TrueFrameSecondaryDark,
    onSecondary = TrueFrameOnSecondaryDark,
    secondaryContainer = TrueFrameSecondaryContainerDark,
    onSecondaryContainer = TrueFrameOnSecondaryContainerDark,
    tertiary = TrueFrameTertiaryDark,
    onTertiary = TrueFrameOnTertiaryDark,
    tertiaryContainer = TrueFrameTertiaryContainerDark,
    onTertiaryContainer = TrueFrameOnTertiaryContainerDark,
    background = TrueFrameDarkBackground,
    onBackground = TrueFrameSecondaryDark,
    surface = TrueFrameDarkSurface,
    onSurface = TrueFrameSecondaryDark,
    surfaceVariant = TrueFrameDarkSurfaceVariant,
    onSurfaceVariant = TrueFrameSecondaryDark,
    outline = TrueFrameOutlineDark,
    outlineVariant = TrueFrameOutlineVariantDark,
    error = TrueFrameErrorDark,
    onError = TrueFrameOnErrorDark,
)

@Composable
fun TrueFrameTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TrueFrameTypography,
        content = content,
    )
}
