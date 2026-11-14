package com.bitvoid.network.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

val VoidObsidian = Color(0xFF0D1117)
val VoidSurface = Color(0xFF161B22)
val VoidSurfaceElevated = Color(0xFF1C232D)
val VoidBorder = Color(0xFF283240)
val VoidMint = Color(0xFF00FF9C)
val VoidMintDim = Color(0xFF00C77A)
val VoidMintContainer = Color(0xFF072B1E)
val VoidCyan = Color(0xFF00D1FF)
val VoidAmber = Color(0xFFFFB020)
val VoidCrimson = Color(0xFFFF4D6D)
val VoidTextPrimary = Color(0xFFE6EDF3)
val VoidTextSecondary = Color(0xFF8B949E)

val SpaceGroteskFontFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.Bold)
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)

private val BitVoidDarkColorScheme = darkColorScheme(
    primary = VoidMint,
    onPrimary = VoidObsidian,
    primaryContainer = VoidMintContainer,
    onPrimaryContainer = VoidMint,
    secondary = VoidCyan,
    onSecondary = VoidObsidian,
    secondaryContainer = Color(0xFF092530),
    onSecondaryContainer = VoidCyan,
    tertiary = VoidAmber,
    onTertiary = VoidObsidian,
    background = VoidObsidian,
    onBackground = VoidTextPrimary,
    surface = VoidSurface,
    onSurface = VoidTextPrimary,
    surfaceVariant = VoidSurfaceElevated,
    onSurfaceVariant = VoidTextSecondary,
    outline = VoidBorder,
    outlineVariant = Color(0xFF1F2733),
    error = VoidCrimson,
    onError = VoidObsidian,
    errorContainer = Color(0xFF36121B),
    onErrorContainer = VoidCrimson
)

val BitVoidTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.6.sp
    ),
    labelMedium = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    )
)

val BitVoidShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun BitVoidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BitVoidDarkColorScheme,
        typography = BitVoidTypography,
        shapes = BitVoidShapes,
        content = content
    )
}
