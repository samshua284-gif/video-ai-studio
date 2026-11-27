package com.hilalsameer.videoaistudio.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The studio skin (0.8.0, TD-014): the dark-and-gold design the
 * owner approved from the template — near-black surfaces, card
 * charcoal, one gold accent, no crown and no "Pro" (his explicit
 * decision: a free app carries no paid-tier branding). These
 * tokens are the single source every screen and shared component
 * reads, so a future skin change lands in exactly one place.
 * (The pre-0.8.0 skin was a warm-red accent on the same dark
 * base; every Material3 control re-skins from this file alone.)
 */
object StudioTokens {
    val Background = Color(0xFF080808)
    val Surface = Color(0xFF151515)
    val Card = Color(0xFF202020)
    val Gold = Color(0xFFFFC52F)
    val GoldDark = Color(0xFFB88616)
    val CardBorder = Color(0xFF66501F)
    val TextPrimary = Color(0xFFF5F2EA)
    val TextSecondary = Color(0xFFAAAAAA)
    val Error = Color(0xFFFF5252)
    val Success = Color(0xFF4CAF50)
    val CardRadius = 18.dp
    val ButtonRadius = 50.dp
}

private val StudioColors = darkColorScheme(
    primary = StudioTokens.Gold,
    onPrimary = Color.Black,
    secondary = StudioTokens.GoldDark,
    onSecondary = Color.Black,
    background = StudioTokens.Background,
    onBackground = StudioTokens.TextPrimary,
    surface = StudioTokens.Surface,
    onSurface = StudioTokens.TextPrimary,
    surfaceVariant = StudioTokens.Card,
    onSurfaceVariant = StudioTokens.TextSecondary,
    outline = StudioTokens.CardBorder,
    error = StudioTokens.Error
)

private val StudioShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(StudioTokens.CardRadius),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun VideoAIStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StudioColors, shapes = StudioShapes, content = content)
}
