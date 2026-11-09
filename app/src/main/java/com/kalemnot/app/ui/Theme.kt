package com.kalemnot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3949AB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF0B1B5E),
    secondary = Color(0xFF5C6BC0),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFCFCFF),
    surfaceVariant = Color(0xFFE3E5F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8C1FF),
    onPrimary = Color(0xFF16237A),
    primaryContainer = Color(0xFF2E3A8F),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF9FA8DA),
    background = Color(0xFF121318),
    surface = Color(0xFF17181E),
    surfaceVariant = Color(0xFF2A2C36),
)

@Composable
fun KalemNotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

val NoteColors = listOf(
    Color(0xFF5C6BC0),
    Color(0xFF26A69A),
    Color(0xFFEF5350),
    Color(0xFFFFA726),
    Color(0xFF66BB6A),
    Color(0xFFAB47BC),
    Color(0xFF29B6F6),
    Color(0xFF8D6E63),
)

fun noteColor(idx: Int): Color = NoteColors[((idx % NoteColors.size) + NoteColors.size) % NoteColors.size]
