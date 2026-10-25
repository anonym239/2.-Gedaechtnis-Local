package de.gedaechtnis.local.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Tiefschwarz / Dunkelgrau mit Neon-Akzenten
val Black = Color(0xFF050507)
val Surface0 = Color(0xFF0D0D12)
val Surface1 = Color(0xFF15151C)
val Surface2 = Color(0xFF1E1E28)
val Outline = Color(0xFF2E2E3A)
val NeonViolet = Color(0xFFB388FF)
val DeepViolet = Color(0xFF7C5CFF)
val NeonCyan = Color(0xFF22D3EE)
val NeonPink = Color(0xFFF472B6)
val SuccessGreen = Color(0xFF34D399)
val WarningAmber = Color(0xFFFBBF24)
val TextHigh = Color(0xFFECECF4)

/** Eingabeleiste und Chips im Chat-Stil. */
val InputBarColor = Color(0xFF1E1F24)
/** Runder Aktionsknopf (Tiefblau). */
val SendButtonColor = Color(0xFF1F3A93)

/** App-Hintergrund: Tiefschwarz, das nach unten in ein dunkles Blau übergeht. */
val AppBackgroundBrush = Brush.verticalGradient(
    0.0f to Color(0xFF020204),
    0.55f to Color(0xFF040614),
    1.0f to Color(0xFF0E1A55),
)
val TextMid = Color(0xFFA1A1B5)

private val DarkScheme = darkColorScheme(
    primary = NeonViolet,
    onPrimary = Color(0xFF1A0B3D),
    primaryContainer = Color(0xFF2A1C55),
    onPrimaryContainer = Color(0xFFE6DAFF),
    secondary = NeonCyan,
    onSecondary = Color(0xFF00222A),
    secondaryContainer = Color(0xFF0B3440),
    onSecondaryContainer = Color(0xFFBDF4FF),
    tertiary = NeonPink,
    background = Black,
    onBackground = TextHigh,
    surface = Surface0,
    onSurface = TextHigh,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Surface0,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Color(0xFF262632),
    outline = Outline,
    outlineVariant = Color(0xFF23232D),
    error = Color(0xFFFF6B81),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
}

/** Die App ist bewusst immer im Dark Mode (OLED-schonend, unauffällig). */
@Composable
fun ZweitGedaechtnisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography, content = content)
}
