package com.example.trueframe.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

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
    onBackground = TrueFrameOnSurfaceDark,
    surface = TrueFrameDarkSurface,
    onSurface = TrueFrameOnSurfaceDark,
    surfaceVariant = TrueFrameDarkSurfaceVariant,
    onSurfaceVariant = TrueFrameSecondaryDark,
    outline = TrueFrameOutlineDark,
    outlineVariant = TrueFrameOutlineVariantDark,
    error = TrueFrameErrorDark,
    onError = TrueFrameOnErrorDark,
)

@Composable
fun TrueFrameTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = TrueFrameTypography,
        content = content,
    )
}
