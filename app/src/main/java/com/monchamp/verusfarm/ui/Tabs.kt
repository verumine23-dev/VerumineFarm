package com.monchamp.verusfarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.monchamp.verusfarm.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monchamp.verusfarm.Config
import com.monchamp.verusfarm.HashPoint
import com.monchamp.verusfarm.MiningUi
import com.monchamp.verusfarm.PoolStats
import com.monchamp.verusfarm.PowerMode
import com.monchamp.verusfarm.Tone

// =============================================================== ACCUEIL
@Composable
fun HomeTab(ui: MiningUi, pool: PoolStats?, device: DeviceInfo, actions: Actions, goTab: (Int) -> Unit) {
    TabColumn {
        BrandHeader(ui.tone)

        if (ui.tone == Tone.ERROR || !ui.engineOk) {
            NeonCard {
                Text(
                    if (!ui.engineOk) "Minage impossible sur cet appareil" else "Une erreur est survenue",
                    fontWeight = FontWeight.Bold, color = Corail
                )
                Text(ui.status, fontSize = 13.sp, color = Texte)
                Text("Ouvre Plus, puis Diagnostic, pour voir la cause exacte.", fontSize = 12.sp, color = TexteDoux)
            }
        }

        Column {
            Text("Mon champ de minage", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Texte)
            val n = pool?.workers?.size ?: 0
            Text(
                if (n > 0) "$n ${if (n > 1) "appareils connectés" else "appareil connecté"} à Luckpool"
                else "On mine ensemble pour un meilleur avenir.",
                fontSize = 14.sp, color = TexteDoux
            )
        }

        NeonCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Solde en attente", fontSize = 13.sp, color = TexteDoux)
                    Text(
                        if (pool != null) "${fmtVrsc(pool.balance)} VRSC" else "—",
                        fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Texte
                    )
                    Text(
                        if (pool != null) "En maturation : ${fmtVrsc(pool.immature)} VRSC" else "Connexion à Luckpool…",
                        fontSize = 12.sp, color = if (pool != null) Menthe else TexteDoux
                    )
                }
                LogoMark(Modifier.height(72.dp))
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TapButton(ui = ui, cores = device.cores, onClick = actions.onToggle)
        }

        val online = pool?.workers?.count { it.online } ?: 0
        val total = pool?.workers?.size ?: 0
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Hashrate", ui.hashrate, Modifier.weight(1f))
            StatCard(
                "Appareils actifs",
                if (pool != null && total > 0) "$online/$total" else if (ui.enabled) "1/1" else "0/1",
                Modifier.weight(1f)
            )
            StatCard("Temps de minage", fmtDuration(ui.miningMs), Modifier.weight(1f))
        }

        AddDeviceCard { goTab(3) }
    }
}

@Composable
private fun AddDeviceCard(onClick: () -> Unit) {
    NeonCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Violet.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) { Icon(VIcons.Phone, null, tint = Lavande, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ajouter un appareil", fontWeight = FontWeight.Bold, color = Texte)
                Text(
                    "Installe Verumine sur un autre téléphone avec la même adresse VRSC : il apparaîtra tout seul dans Appareils.",
                    fontSize = 12.sp, color = TexteDoux
                )
            }
            Icon(VIcons.Chevron, null, tint = Lavande)
        }
    }
}

// =============================================================== MINAGE
@Composable
fun MineTab(ui: MiningUi, pool: PoolStats?, history: List<HashPoint>, device: DeviceInfo, actions: Actions) {
    var range by remember { mutableStateOf(24) }
    TabColumn {
        TabHeader("Minage", "Votre puissance, votre champ")

        NeonCard {
            Text("Statut du minage", fontSize = 13.sp, color = TexteDoux)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(toneColor(ui.tone), 10)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !ui.enabled -> "Arrêté"
                        ui.tone == Tone.OK -> "Actif"
                        ui.tone == Tone.WARN -> "En attente"
                        else -> "Erreur"
                    },
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, color = toneColor(ui.tone), modifier = Modifier.weight(1f)
                )
                Text("VerusHash", fontSize = 12.sp, color = Lavande)
            }
            Text(ui.status, fontSize = 13.sp, color = TexteDoux)
        }

        SectionTitle("Statistiques en temps réel")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                "Hashrate du champ",
                if (pool != null) fmtHash(pool.hashrate) else "—",
                Modifier.weight(1f),
                hint = if (pool != null) "moy. 24h ${fmtHash(pool.avg24h)}" else null,
                hintColor = TexteDoux
            )
            StatCard(
                "Parts validées",
                ui.accepted.toString(),
                Modifier.weight(1f),
                hint = if (ui.rejected > 0) "${ui.rejected} rejetées" else "aucune rejetée",
                hintColor = if (ui.rejected > 0) Ambre else Menthe
            )
            StatCard(
                "Efficacité",
                if (pool != null) "${"%.1f".format(java.util.Locale.US, pool.efficiency)} %" else "—",
                Modifier.weight(1f)
            )
        }

        NeonCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Évolution du hashrate", Modifier.weight(1f))
                listOf(1, 6, 24).forEach { h ->
                    Chip("${h}h", range == h) { range = h }
                    Spacer(Modifier.width(6.dp))
                }
            }
            HashChart(history, range)
        }

        NeonCard {
            SectionTitle("Paramètres de minage")
            InfoRow("Algorithme", "VerusHash 2.1 (VRSC)")
            InfoRow("Puissance utilisée", ui.hashrate)
            InfoRow("Cœurs actifs", "${ui.threads} sur ${device.cores}")
            val hot = ui.tempC >= 41f
            InfoRow(
                "Température",
                if (ui.tempC > 0f) "${ui.tempC.toInt()} °C  ${if (hot) "Chaud" else "OK"}" else "—",
                if (hot) Ambre else Menthe
            )
            InfoRow("Serveur Luckpool", if (ui.pool != "—") ui.pool else "—")
        }

        NeonCard {
            SectionTitle("Puissance de calcul")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PowerMode.values().forEach { m ->
                    Chip(m.label, m == device.mode, Modifier.weight(1f)) { actions.onModeChange(m) }
                }
            }
            Text(
                when (device.mode) {
                    PowerMode.ECO -> "La moitié des cœurs : le téléphone reste frais et la batterie souffre moins."
                    PowerMode.BALANCED -> "Les trois quarts des cœurs : un bon compromis."
                    PowerMode.MAX -> "Tous les cœurs. L'application ralentit toute seule si le téléphone chauffe trop."
                },
                fontSize = 12.sp, color = TexteDoux
            )
        }
    }
}

// Courbe du hashrate (historique fourni par Luckpool pour ton adresse)
@Composable
private fun HashChart(history: List<HashPoint>, hours: Int) {
    val last = history.lastOrNull()?.tsSec ?: 0L
    val inRange = history.filter { it.tsSec >= last - hours * 3600L }
    val step = (inRange.size / 120).coerceAtLeast(1)
    val pts = inRange.filterIndexed { i, _ -> i % step == 0 }

    if (pts.size < 2) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text("Pas encore de données à afficher.", color = TexteDoux, fontSize = 13.sp)
        }
        return
    }
    val maxV = (pts.maxOf { it.hashrate } * 1.1).coerceAtLeast(1.0)
    Box(Modifier.fillMaxWidth().height(160.dp)) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            for (i in 0..3) {
                val y = size.height * i / 3f
                drawLine(Bord.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val minT = pts.first().tsSec
            val spanT = (pts.last().tsSec - minT).coerceAtLeast(1L)
            val line = Path()
            val fill = Path()
            pts.forEachIndexed { i, p ->
                val x = (p.tsSec - minT).toFloat() / spanT.toFloat() * size.width
                val y = size.height - (p.hashrate / maxV).toFloat() * size.height
                if (i == 0) {
                    line.moveTo(x, y)
                    fill.moveTo(x, size.height)
                    fill.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    fill.lineTo(x, y)
                }
            }
            fill.lineTo(size.width, size.height)
            fill.close()
            drawPath(fill, brush = Brush.verticalGradient(listOf(Violet.copy(alpha = 0.40f), Color.Transparent)))
            drawPath(line, color = Neon, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Text(fmtHash(maxV), fontSize = 10.sp, color = TexteDoux, modifier = Modifier.align(Alignment.TopStart))
    }
}

// =============================================================== WALLET
@Composable
fun WalletTab(pool: PoolStats?, device: DeviceInfo, actions: Actions, onEdit: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    TabColumn {
        TabHeader("Wallet", "Vos récompenses, votre liberté")

        NeonCard {
            Text("Solde en attente", fontSize = 13.sp, color = TexteDoux)
            Text(
                if (pool != null) "${fmtVrsc(pool.balance)} VRSC" else "—",
                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Texte
            )
            if (pool != null) {
                InfoRow("En maturation", "${fmtVrsc(pool.immature)} VRSC")
                InfoRow("Déjà payé", "${fmtVrsc(pool.paid)} VRSC", Menthe)
            } else {
                Text(
                    "Données Luckpool indisponibles pour le moment (internet ou adresse sans activité).",
                    fontSize = 12.sp, color = Ambre
                )
            }
        }

        NeonCard {
            SectionTitle("Adresse de réception")
            Text(
                if (Config.isUsableWallet(device.wallet)) device.wallet else "Aucune adresse enregistrée",
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Lavande
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(device.wallet)) },
                    enabled = Config.isUsableWallet(device.wallet),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(VIcons.Copy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copier")
                }
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(12.dp)) { Text("Modifier") }
            }
        }

        NeonCard {
            SectionTitle("Comment sont versés les gains ?")
            Text(
                "Tous tes appareils minent vers cette même adresse. Luckpool regroupe leurs gains et les verse " +
                    "directement sur ton portefeuille Verus, selon ses propres règles de paiement.",
                fontSize = 13.sp, color = TexteDoux
            )
            OutlinedButton(
                onClick = { actions.onOpenUrl("https://luckpool.net/verus/stats.html") },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Ouvrir Luckpool") }
        }
    }
}

// =============================================================== APPAREILS
@Composable
fun DevicesTab(ui: MiningUi, pool: PoolStats?, device: DeviceInfo, actions: Actions) {
    val myName = device.customName.ifBlank { device.autoName }
    val workers = pool?.workers ?: emptyList()
    val mine = workers.firstOrNull { it.name.equals(myName, ignoreCase = true) }
    val others = workers.filter { it !== mine }
    val online = workers.count { it.online }

    TabColumn {
        TabHeader("Mes appareils", "Chaque téléphone est identifié sur Luckpool")

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Puissance totale", if (pool != null) fmtHash(pool.hashrate) else "—", Modifier.weight(1f))
            StatCard(
                "Appareils actifs",
                if (workers.isNotEmpty()) "$online/${workers.size}" else if (ui.enabled) "1/1" else "0/1",
                Modifier.weight(1f)
            )
        }

        // Cet appareil en premier
        val localOnline = ui.enabled && ui.tone == Tone.OK
        DeviceCard(
            name = myName,
            isThis = true,
            online = mine?.online ?: localOnline,
            hashrate = if (localOnline) ui.hashrate else fmtHash(mine?.hashrate ?: 0.0),
            detail = buildString {
                append("Processeur ${device.abi}, ${device.cores} cœurs")
                if (mine != null) append("\nParts : ${mine.shares.toLong()}  Serveur : ${mine.server}")
            },
            switchChecked = ui.enabled,
            onSwitch = actions.onToggle
        )

        others.forEach { w ->
            DeviceCard(
                name = w.name,
                isThis = false,
                online = w.online,
                hashrate = fmtHash(w.hashrate),
                detail = "Parts : ${w.shares.toLong()}  Serveur : ${w.server}",
                switchChecked = false,
                onSwitch = null
            )
        }

        if (pool != null && workers.isEmpty()) {
            Text(
                "Luckpool n'a pas encore reçu de part de cet appareil. Patiente quelques minutes après le démarrage du minage.",
                fontSize = 13.sp, color = TexteDoux
            )
        }

        NeonCard {
            SectionTitle("Ajouter un appareil")
            Text("1. Installe Verumine sur un autre téléphone.", fontSize = 13.sp, color = TexteDoux)
            Text("2. Ouvre l'application : elle utilise la même adresse VRSC et un nom différent.", fontSize = 13.sp, color = TexteDoux)
            Text("3. Après quelques minutes, il apparaît ici et dans le tableau de bord de Luckpool.", fontSize = 13.sp, color = TexteDoux)
        }
    }
}

@Composable
private fun DeviceCard(
    name: String,
    isThis: Boolean,
    online: Boolean,
    hashrate: String,
    detail: String,
    switchChecked: Boolean,
    onSwitch: (() -> Unit)?
) {
    NeonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Violet.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) { Icon(VIcons.Phone, null, tint = Lavande, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontWeight = FontWeight.Bold, color = Texte, maxLines = 1)
                    if (isThis) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Cet appareil", fontSize = 10.sp, color = Lavande,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Violet.copy(alpha = 0.25f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (online) Menthe else Corail, 7)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (online) "En ligne  $hashrate" else "Hors ligne",
                        fontSize = 12.sp, color = if (online) Menthe else Corail
                    )
                }
            }
            if (onSwitch != null) {
                Switch(checked = switchChecked, onCheckedChange = { onSwitch() })
            }
        }
        Text(detail, fontSize = 12.sp, color = TexteDoux)
    }
}

// =============================================================== PLUS
@Composable
fun MoreTab(ui: MiningUi, device: DeviceInfo, actions: Actions, onEdit: () -> Unit) {
    var showLogs by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    TabColumn {
        TabHeader("Plus", "Réglages et protections")

        NeonCard {
            SectionTitle("Pour que le minage ne s'arrête jamais")
            CheckRow(
                "Notifications", "Android les exige pour travailler en arrière-plan.",
                device.checks.notif, "Autoriser", actions.onAskNotif
            )
            CheckRow(
                "Batterie sans restriction", "Empêche Android d'endormir le minage.",
                device.checks.battery, "Autoriser", actions.onAskBattery
            )
            CheckRow(
                "Protection contre la désinstallation", "Active l'administrateur de l'appareil.",
                device.checks.admin, "Activer", actions.onAskAdmin
            )
            CheckRow(
                "Démarrage automatique",
                "Sur Tecno, Infinix, Xiaomi, Samsung : ouvre Batterie ou Démarrage automatique de l'application.",
                false, "Ouvrir les réglages", actions.onOpenAppSettings
            )
        }

        NeonCard {
            SectionTitle("Cet appareil")
            InfoRow("Nom sur Luckpool", device.customName.ifBlank { device.autoName })
            InfoRow("Adresse VRSC", if (Config.isUsableWallet(device.wallet)) maskWallet(device.wallet) else "À renseigner")
            InfoRow("Processeur", "${device.abi}, ${device.cores} cœurs")
            OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(12.dp)) { Text("Modifier l'adresse et le nom") }
        }

        NeonCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Journal du mineur", Modifier.weight(1f))
                TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "Masquer" else "Afficher") }
            }
            if (showLogs) {
                Text(
                    if (ui.logs.isEmpty()) "Aucun message pour le moment." else ui.logs.takeLast(25).joinToString("\n"),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TexteDoux
                )
            }
        }

        NeonCard {
            SectionTitle("Diagnostic")
            Text(
                "À utiliser si le minage ne démarre pas : vérifie le processeur et teste le programme de minage.",
                fontSize = 13.sp, color = TexteDoux
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            report = withContext(Dispatchers.IO) { Diagnostics.build(ctx) }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (busy) "Test en cours…" else "Lancer le diagnostic") }
                if (report.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(report)) },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Copier") }
                }
            }
            if (report.isNotEmpty()) {
                Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TexteDoux)
            }
        }

        NeonCard {
            SectionTitle("À propos")
            Text(
                "Verumine : un champ de minage Verus (VRSC). Tous tes appareils minent vers la même adresse " +
                    "et sont reconnus par leur nom sur Luckpool.",
                fontSize = 13.sp, color = TexteDoux
            )
            Text("Version 1.0", fontSize = 12.sp, color = Brume)
        }
        Spacer(Modifier.height(8.dp))
    }
}
