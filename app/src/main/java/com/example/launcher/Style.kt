package com.example.launcher

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Typography = Typography(
//    bodyLarge = TextStyle(fontSize = 25.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, shadow = ),
    labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium ),
//    headlineSmall = TextStyle(fontWeight = FontWeight.Medium),
    headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.Medium)
)
val Shapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    val theme = if (isSystemInDarkTheme()) ::dynamicDarkColorScheme else ::dynamicLightColorScheme
    val colorScheme = theme(LocalContext.current)
    MaterialTheme(colorScheme, Shapes, Typography, content)
}