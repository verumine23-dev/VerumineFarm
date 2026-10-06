package com.monchamp.verusfarm

import kotlinx.coroutines.flow.MutableStateFlow

enum class Tone { IDLE, OK, WARN, ERROR }

// Tout ce que l'écran affiche : le service le met à jour, l'écran le lit.
data class MiningUi(
    val enabled: Boolean = false,
    val status: String = "Arrêté",
    val tone: Tone = Tone.IDLE,
    val hashrate: String = "—",
    val tempC: Float = 0f,
    val batteryPercent: Int = 0,
    val charging: Boolean = false,
    val threads: Int = 0,
    val pool: String = "—",
    val levelLabel: String = "—",
    val accepted: Int = 0,
    val rejected: Int = 0,
    val engineOk: Boolean = true,
    val miningMs: Long = 0L,
    val logs: List<String> = emptyList()
)

// État des protections (cases à cocher de l'écran)
data class Checks(
    val notif: Boolean = false,
    val battery: Boolean = false,
    val admin: Boolean = false
)

object MiningState {
    val ui = MutableStateFlow(MiningUi())

    // À appeler uniquement depuis le thread principal
    fun set(block: (MiningUi) -> MiningUi) {
        ui.value = block(ui.value)
    }

    fun addLog(line: String) {
        set { it.copy(logs = (it.logs + line).takeLast(Config.MAX_LOG_LINES)) }
    }
}

// Ce que Luckpool dit de ton adresse (mis à jour par l'écran)
object PoolState {
    val stats = MutableStateFlow<PoolStats?>(null)
    val history = MutableStateFlow<List<HashPoint>>(emptyList())
}
