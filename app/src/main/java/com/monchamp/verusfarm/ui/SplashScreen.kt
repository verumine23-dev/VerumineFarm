package com.monchamp.verusfarm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monchamp.verusfarm.R
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/*
 * Animation d'ouverture (8 secondes), d'après la maquette :
 *   0 - 1,5 s    le logo V apparaît au centre, avec lumière et particules
 *   1,5 - 2 s    le logo rétrécit et monte
 *   2 - 2,5 s    le nom « Verumine » descend du haut et se place sous le logo
 *   2,5 - 4 s    la barre de chargement apparaît, le pourcentage commence à monter
 *   4 - 5,5 s    la barre progresse, particules lumineuses autour
 *   5,5 - 6,5 s  presque terminé (95 %), effet d'énergie autour du logo et du nom
 *   6,5 - 7 s    100 % avec une petite vibration lumineuse
 *   7 - 8 s      fondu vers le tableau de bord
 * Un appui sur l'écran permet de passer l'animation.
 */

private class Particle(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float)

private fun seg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)
private fun easeInOut(x: Float): Float = x * x * (3f - 2f * x)

private fun progressAt(t: Float): Float = when {
    t <= 2.5f -> 0f
    t <= 4.0f -> 0.30f * (t - 2.5f) / 1.5f
    t <= 5.5f -> 0.30f + 0.42f * (t - 4.0f) / 1.5f
    t <= 6.5f -> 0.72f + 0.23f * (t - 5.5f) / 1.0f
    t <= 7.0f -> 0.95f + 0.05f * (t - 6.5f) / 0.5f
    else -> 1f
}

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val t = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val logo = ImageBitmap.imageResource(R.drawable.logo_v)
    val particles = remember {
        val r = Random(11)
        List(64) {
            Particle(r.nextFloat(), r.nextFloat(), 1f + r.nextFloat() * 2.5f, 0.02f + r.nextFloat() * 0.05f, r.nextFloat() * 6.28f)
        }
    }

    LaunchedEffect(Unit) {
        t.animateTo(8f, tween(8000, easing = LinearEasing))
        onFinished()
    }

    val tv = t.value
    val appear = easeOut(seg(tv, 0f, 1.5f))
    val shrink = easeInOut(seg(tv, 1.5f, 2.0f))
    val settle = easeInOut(seg(tv, 2.0f, 2.5f))
    val nameIn = easeOut(seg(tv, 2.0f, 2.5f))
    val barIn = easeOut(seg(tv, 2.5f, 2.9f))
    val energy = seg(tv, 5.5f, 6.5f) * (1f - seg(tv, 7f, 8f))
    val vib = if (tv in 6.5f..7.1f) 1f - seg(tv, 6.5f, 7.1f) else 0f
    val flash = if (tv in 6.5f..7.0f) sin(((tv - 6.5f) / 0.5f) * PI.toFloat()) else 0f
    val overlay = 1f - seg(tv, 7f, 8f)
    val progress = progressAt(tv)
    val pct = (progress * 100f).toInt()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlay }
            .background(Brush.verticalGradient(listOf(Color(0xFF04010D), Color(0xFF0C052E), Color(0xFF04010D))))
            .pointerInput(Unit) {
                detectTapGestures {
                    if (t.value < 6.9f) {
                        scope.launch {
                            t.animateTo(8f, tween(600, easing = LinearEasing))
                            onFinished()
                        }
                    }
                }
            }
    ) {
        val h = maxHeight

        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f

            // Faisceau de lumière vertical
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Neon.copy(alpha = 0.55f * appear), Color.Transparent)),
                topLeft = Offset(cx - 1.5.dp.toPx(), 0f),
                size = Size(3.dp.toPx(), size.height)
            )
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Violet.copy(alpha = 0.14f * appear), Color.Transparent)),
                topLeft = Offset(cx - 28.dp.toPx(), 0f),
                size = Size(56.dp.toPx(), size.height)
            )

            // Position et taille du logo
            val logoScale = (0.78f + 0.22f * appear) * (1f - 0.55f * shrink)
            val logoW = size.width * 0.62f * logoScale
            val logoH = logoW * logo.height / logo.width
            val yFrac = 0.46f + (0.28f - 0.46f) * shrink + (0.37f - 0.28f) * settle
            val cy = size.height * yFrac

            // Halo lumineux
            val pulse = 0.5f + 0.5f * sin(tv * 5f)
            val haloR = logoW * (0.75f + 0.08f * pulse) + size.width * 0.15f * energy
            val haloA = ((0.30f + 0.12f * pulse) * appear + 0.35f * energy + 0.5f * flash).coerceIn(0f, 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Neon.copy(alpha = haloA), Color.Transparent),
                    center = Offset(cx, cy), radius = haloR
                ),
                radius = haloR, center = Offset(cx, cy)
            )

            // Le logo (le noir de l'image disparaît grâce au mode Screen)
            drawImage(
                logo,
                dstOffset = IntOffset((cx - logoW / 2f).toInt(), (cy - logoH / 2f).toInt()),
                dstSize = IntSize(logoW.toInt(), logoH.toInt()),
                alpha = appear,
                blendMode = BlendMode.Screen
            )

            // Anneaux au sol, pendant la première étape
            val floorA = appear * (1f - shrink)
            if (floorA > 0.01f) {
                val fy = cy + logoH * 0.52f
                for (i in 0..2) {
                    val rw = logoW * (0.55f + 0.18f * i)
                    val rh = rw * 0.16f
                    drawOval(
                        color = Neon.copy(alpha = floorA * (0.55f - 0.15f * i)),
                        topLeft = Offset(cx - rw / 2f, fy - rh / 2f),
                        size = Size(rw, rh),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // Particules lumineuses
            val boost = 1f + 1.5f * energy
            val visible = seg(tv, 0f, 0.8f)
            particles.forEach { p ->
                val y = (((p.y - p.speed * tv) % 1f) + 1f) % 1f
                val x = p.x + 0.02f * sin(tv * 0.8f + p.phase)
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(tv * 3f + p.phase))
                drawCircle(
                    color = Lavande.copy(alpha = (0.55f * tw * boost * visible).coerceIn(0f, 1f)),
                    radius = p.size.dp.toPx() * (0.6f + 0.4f * energy),
                    center = Offset(x * size.width, y * size.height)
                )
            }

            // Effet d'énergie autour du logo et du nom
            if (energy > 0.01f) {
                val ccy = size.height * 0.43f
                for (i in 0..2) {
                    val f = (tv * 0.9f + i / 3f) % 1f
                    drawCircle(
                        color = Neon.copy(alpha = (energy * (1f - f) * 0.55f).coerceIn(0f, 1f)),
                        radius = size.width * (0.22f + 0.30f * f),
                        center = Offset(cx, ccy),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        // Le nom descend du haut de l'écran
        Text(
            "Verumine",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = h * 0.49f - 28.dp)
                .graphicsLayer {
                    alpha = nameIn
                    translationY = -(1f - nameIn) * h.toPx() * 0.25f
                },
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(shadow = Shadow(color = Neon, blurRadius = 28f))
        )

        // Barre de chargement et pourcentage
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = h * 0.60f)
                .fillMaxWidth(0.62f)
                .graphicsLayer {
                    alpha = barIn
                    scaleX = 0.7f + 0.3f * barIn
                    translationX = sin(tv * 120f) * 7f * vib
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(50))
                    .border(1.5.dp, Violet, RoundedCornerShape(50))
                    .background(Color(0x33000000))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Violet, Neon, Color(0xFFE9D5FF))))
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("$pct%", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }

        // Petit rappel discret
        Text(
            "Touchez l'écran pour passer",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .graphicsLayer { alpha = 0.35f * seg(tv, 2.5f, 3.2f) },
            color = Color.White,
            fontSize = 12.sp
        )
    }
}
