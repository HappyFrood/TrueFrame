package com.example.trueframe.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
    error = TrueFrameError,
    onError = TrueFrameOnError,
)

private val LightColorScheme = lightColorScheme(
    primary = TrueFramePrimary,
    onPrimary = TrueFrameOnPrimary,
    primaryContainer = TrueFramePrimaryContainer,
    onPrimaryContainer = TrueFrameOnPrimaryContainer,
    secondary = TrueFrameSecondary,
    onSecondary = TrueFrameOnSecondary,
    secondaryContainer = TrueFrameSecondaryContainer,
    onSecondaryContainer = TrueFrameOnSecondaryContainer,
    tertiary = TrueFrameTertiary,
    onTertiary = TrueFrameOnTertiary,
    tertiaryContainer = TrueFrameTertiaryContainer,
    onTertiaryContainer = TrueFrameOnTertiaryContainer,
    error = TrueFrameError,
    onError = TrueFrameOnError,
)

@Composable
fun TrueFrameTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TrueFrameTypography,
        content = content,
    )
}
