package dev.localphoto.enhancer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Day = lightColorScheme(
    primary = Color(0xFF43634D), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E8D8), onPrimaryContainer = Color(0xFF26382A),
    secondary = Color(0xFF777C61), secondaryContainer = Color(0xFFEBECDC),
    background = Color(0xFFF7F6F2), onBackground = Color(0xFF252923),
    surface = Color(0xFFF7F6F2), onSurface = Color(0xFF252923),
    surfaceVariant = Color(0xFFEDEEE7), onSurfaceVariant = Color(0xFF686D64),
    outlineVariant = Color(0xFFDDE0D5),
)
private val Night = darkColorScheme(
    primary = Color(0xFFBACFAE), primaryContainer = Color(0xFF324736),
    background = Color(0xFF191D19), surface = Color(0xFF191D19),
    onSurface = Color(0xFFEAECE5), surfaceVariant = Color(0xFF2B322A),
)

@Composable fun EnhancerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Night else Day,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 43.sp),
            headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Medium),
            titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        ), content = content)
}
