package com.swiftget.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Mint = Color(0xFF2DD4A7)
val Violet = Color(0xFF7C5CFF)
val Coral = Color(0xFFFF7A59)
val Sky = Color(0xFF38BDF8)
val Gold = Color(0xFFFFC53D)
val WhatsApp = Color(0xFF25D366)
val Ink = Color(0xFF0B0F14)

val BrandGradient = Brush.linearGradient(listOf(Mint, Violet))
val HeroGradient = Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF4C2FBF)))

private val Scheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00382A),
    secondary = Violet,
    background = Ink,
    onBackground = Color(0xFFEAF0F6),
    surface = Color(0xFF131A22),
    onSurface = Color(0xFFEAF0F6),
    surfaceVariant = Color(0xFF1B2430),
    onSurfaceVariant = Color(0xFF9AA6B5),
    outline = Color(0xFF2A3544),
    error = Color(0xFFFF6B6B),
)

@Composable
fun ClipzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
        ),
        content = content,
    )
}

fun catEmoji(c: String): String = when (c) {
    "Videos" -> "🎬"
    "Music" -> "🎵"
    "Images" -> "🖼️"
    "Documents" -> "📄"
    "Archives" -> "🗜️"
    "Apps" -> "📦"
    "Games" -> "🎮"
    "Status" -> "💬"
    else -> "📁"
}

fun catColor(c: String): Color = when (c) {
    "Videos" -> Coral
    "Music" -> Violet
    "Images" -> Sky
    "Documents" -> Gold
    "Archives" -> Color(0xFFC89B6D)
    "Apps" -> Mint
    "Games" -> Color(0xFFFF5DA2)
    "Status" -> WhatsApp
    else -> Color(0xFF8A97A8)
}
