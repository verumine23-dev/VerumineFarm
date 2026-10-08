package com.monchamp.verusfarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monchamp.verusfarm.Config
import com.monchamp.verusfarm.MiningUi
import com.monchamp.verusfarm.R
import com.monchamp.verusfarm.Tone

// Colonne défilante utilisée par chaque onglet
@Composable
fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

// Carte à bord néon
@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    padding: Int = 16,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Carte, Color(0xFF0E0728))))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Violet.copy(alpha = 0.75f), Bord.copy(alpha = 0.5f))),
                shape
            )
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Texte
    )
}

@Composable
fun TabHeader(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Texte)
        Text(subtitle, fontSize = 14.sp, color = TexteDoux)
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null, hintColor: Color = Menthe) {
    NeonCard(modifier = modifier, padding = 12) {
        Text(label, fontSize = 12.sp, color = TexteDoux, maxLines = 1)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Texte, maxLines = 1)
        if (hint != null) Text(hint, fontSize = 11.sp, color = hintColor, maxLines = 1)
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun StatusPill(tone: Tone) {
    val c = toneColor(tone)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(c)
        Spacer(Modifier.width(8.dp))
        Text(
            when (tone) {
                Tone.IDLE -> "Arrêté"
                Tone.OK -> "En marche"
                Tone.WARN -> "Attention"
                Tone.ERROR -> "Erreur"
            },
            color = c, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
        )
    }
}

// Le logo V de Verumine, fondu dans le fond sombre (mode « Screen » : le noir devient transparent)
@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    val img = ImageBitmap.imageResource(R.drawable.logo_v)
    Canvas(modifier.aspectRatio(img.width.toFloat() / img.height.toFloat())) {
        drawImage(
            img,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            blendMode = BlendMode.Screen
        )
    }
}

@Composable
fun BrandHeader(tone: Tone) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LogoMark(Modifier.height(34.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Verumine",
            modifier = Modifier.weight(1f),
            fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Texte
        )
        StatusPill(tone)
    }
}

// Anneau : un segment par cœur du processeur (allumé = ce cœur mine)
@Composable
fun CoreRing(total: Int, active: Int, color: Color, track: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 10.dp.toPx()
        val n = total.coerceIn(1, 16)
        val gap = 7f
        val step = 360f / n
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        for (i in 0 until n) {
            drawArc(
                color = if (i < active) color else track,
                startAngle = -90f + i * step + gap / 2f,
                sweepAngle = step - gap,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

// Gros bouton rond lumineux : démarre ou arrête le minage
@Composable
fun TapButton(ui: MiningUi, cores: Int, onClick: () -> Unit) {
    val running = ui.enabled
    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Violet.copy(alpha = if (running) 0.50f else 0.22f), Color.Transparent),
                    center = center, radius = r
                ),
                radius = r, center = center
            )
        }
        CoreRing(
            total = cores,
            active = if (running) ui.threads else 0,
            color = Neon,
            track = Bord.copy(alpha = 0.7f),
            modifier = Modifier.size(212.dp)
        )
        Box(
            modifier = Modifier
                .size(170.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF4C1D95), Color(0xFF1E0B5C), Color(0xFF12073A))))
                .border(2.dp, Brush.verticalGradient(listOf(Neon, Violet.copy(alpha = 0.35f))), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (running) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.hashrate, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Texte)
                    Text(
                        if (ui.tone == Tone.OK) "Minage en cours" else "En attente",
                        fontSize = 12.sp, color = if (ui.tone == Tone.OK) Menthe else Ambre
                    )
                    Text("Appuyer pour arrêter", fontSize = 10.sp, color = TexteDoux)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(VIcons.Bolt, contentDescription = null, tint = Lavande, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Appuyer pour miner",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Texte, textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// Petit bouton à choisir (puissance, durée du graphique)
@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Violet else CarteHaute)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (selected) Color.White else Texte)
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Texte) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = TexteDoux, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Medium, color = valueColor, textAlign = TextAlign.End, fontSize = 14.sp)
    }
}

@Composable
fun CheckRow(title: String, detail: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Texte)
            Text(detail, fontSize = 12.sp, color = TexteDoux)
        }
        Spacer(Modifier.width(12.dp))
        if (done) {
            Text("Activé", color = Menthe, fontWeight = FontWeight.SemiBold)
        } else {
            FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text(action) }
        }
    }
}

// Barre de navigation du bas
@Composable
fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val items: List<Pair<String, ImageVector>> = listOf(
        "Accueil" to VIcons.Home,
        "Minage" to VIcons.Bolt,
        "Wallet" to VIcons.Wallet,
        "Appareils" to VIcons.Phone,
        "Plus" to VIcons.More
    )
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(shape)
            .background(Color(0xFF110A2E))
            .border(1.dp, Bord.copy(alpha = 0.7f), shape)
            .padding(vertical = 6.dp)
    ) {
        items.forEachIndexed { i, item ->
            val sel = i == selected
            val tint = if (sel) Neon else Brume
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(item.second, contentDescription = item.first, tint = tint, modifier = Modifier.size(24.dp))
                Text(item.first, fontSize = 11.sp, color = tint, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun EditDialog(
    wallet: String,
    name: String,
    autoName: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var w by remember { mutableStateOf(if (Config.isPlaceholder(wallet)) "" else wallet) }
    var n by remember { mutableStateOf(name) }
    val valid = Config.isUsableWallet(w.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarteHaute,
        title = { Text("Identité de cet appareil", color = Texte) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = w,
                    onValueChange = { w = it.trim() },
                    label = { Text("Adresse VRSC") },
                    singleLine = true,
                    isError = w.isNotEmpty() && !valid,
                    supportingText = {
                        Text(if (w.isEmpty() || valid) "Elle commence par la lettre R." else "Adresse invalide : vérifie qu'elle est complète.")
                    }
                )
                OutlinedTextField(
                    value = n,
                    onValueChange = { n = it.filter { c -> c.isLetterOrDigit() }.lowercase().take(12) },
                    label = { Text("Nom de l'appareil (facultatif)") },
                    singleLine = true,
                    supportingText = { Text("Lettres et chiffres, 12 au plus. Vide = nom automatique : $autoName") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(w.trim(), n) }, enabled = valid) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
