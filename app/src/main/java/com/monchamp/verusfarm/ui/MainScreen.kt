package com.monchamp.verusfarm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.monchamp.verusfarm.Checks
import com.monchamp.verusfarm.Config
import com.monchamp.verusfarm.HashPoint
import com.monchamp.verusfarm.MiningUi
import com.monchamp.verusfarm.PoolStats
import com.monchamp.verusfarm.PowerMode

// Informations sur cet appareil, fournies par l'activité
class DeviceInfo(
    val wallet: String,
    val customName: String,
    val autoName: String,
    val mode: PowerMode,
    val checks: Checks,
    val abi: String,
    val cores: Int
)

// Actions déclenchées par l'écran
class Actions(
    val onToggle: () -> Unit,
    val onModeChange: (PowerMode) -> Unit,
    val onSaveIdentity: (String, String) -> Unit,
    val onAskNotif: () -> Unit,
    val onAskBattery: () -> Unit,
    val onAskAdmin: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenUrl: (String) -> Unit
)

// L'application : l'écran principal, avec l'animation de démarrage par-dessus au lancement
@Composable
fun AppRoot(
    ui: MiningUi,
    pool: PoolStats?,
    history: List<HashPoint>,
    device: DeviceInfo,
    actions: Actions
) {
    var splashDone by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        MainScreen(ui, pool, history, device, actions, ready = splashDone)
        if (!splashDone) SplashScreen(onFinished = { splashDone = true })
    }
}

@Composable
fun MainScreen(
    ui: MiningUi,
    pool: PoolStats?,
    history: List<HashPoint>,
    device: DeviceInfo,
    actions: Actions,
    ready: Boolean
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var showEdit by remember { mutableStateOf(false) }
    val walletOk = Config.isUsableWallet(device.wallet)

    // Première ouverture : on demande l'adresse, une fois l'animation terminée
    LaunchedEffect(walletOk, ready) { if (!walletOk && ready) showEdit = true }
    BackHandler(enabled = tab != 0) { tab = 0 }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Fond, FondBas)))
            .systemBarsPadding()
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HomeTab(ui, pool, device, actions) { tab = it }
                1 -> MineTab(ui, pool, history, device, actions)
                2 -> WalletTab(pool, device, actions) { showEdit = true }
                3 -> DevicesTab(ui, pool, device, actions)
                else -> MoreTab(ui, device, actions) { showEdit = true }
            }
        }
        BottomBar(tab) { tab = it }
    }

    if (showEdit) {
        EditDialog(
            wallet = device.wallet,
            name = device.customName,
            autoName = device.autoName,
            onDismiss = { showEdit = false },
            onSave = { w, n ->
                showEdit = false
                actions.onSaveIdentity(w, n)
            }
        )
    }
}
