package com.monchamp.verusfarm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.monchamp.verusfarm.Tone

// Palette « néon violet » de Verumine
val Fond = Color(0xFF07021A)
val FondBas = Color(0xFF0B0430)
val Carte = Color(0xFF130B33)
val CarteHaute = Color(0xFF1B1046)
val Bord = Color(0xFF3B1FA3)
val Violet = Color(0xFF8B5CF6)
val Neon = Color(0xFFA855F7)
val Lavande = Color(0xFFC4B5FD)
val Texte = Color(0xFFF5F3FF)
val TexteDoux = Color(0xFFA79BD0)
val Menthe = Color(0xFF34D399)
val Ambre = Color(0xFFF5B942)
val Corail = Color(0xFFFF7A93)
val Brume = Color(0xFF8E85A8)

private val Colors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    background = Fond,
    onBackground = Texte,
    surface = Carte,
    onSurface = Texte,
    surfaceVariant = CarteHaute,
    onSurfaceVariant = TexteDoux,
    outline = Bord,
    error = Corail
)

fun toneColor(tone: Tone): Color = when (tone) {
    Tone.OK -> Menthe
    Tone.WARN -> Ambre
    Tone.ERROR -> Corail
    Tone.IDLE -> Brume
}

@Composable
fun VerusFarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
