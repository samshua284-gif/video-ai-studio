package com.hilalsameer.videoaistudio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Premium dark theme matching the design mockups (near-black surfaces,
// vivid warm-red accent). Light theme arrives with the Milestone 9 polish.
private val StudioColors = darkColorScheme(
    primary = Color(0xFFFF4A2D),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFFF8A3D),
    background = Color(0xFF0B0B10),
    onBackground = Color(0xFFF2EFE9),
    surface = Color(0xFF16161E),
    onSurface = Color(0xFFF2EFE9),
    surfaceVariant = Color(0xFF1E1E28),
    onSurfaceVariant = Color(0xFFB9B4A8),
    outline = Color(0xFF2C2C38)
)

@Composable
fun VideoAIStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StudioColors, content = content)
}
