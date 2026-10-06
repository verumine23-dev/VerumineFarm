package com.monchamp.verusfarm

import android.content.Context

enum class PowerMode(val label: String) {
    ECO("Éco"),
    BALANCED("Équilibré"),
    MAX("Maximum")
}

// Petite mémoire de l'application (survit aux redémarrages du téléphone)
object Prefs {
    private const val FILE = "verusfarm"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isFirstRun(ctx: Context): Boolean = !sp(ctx).getBoolean("first_run_done", false)
    fun markFirstRunDone(ctx: Context) = sp(ctx).edit().putBoolean("first_run_done", true).apply()

    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, value: Boolean) = sp(ctx).edit().putBoolean("enabled", value).apply()

    fun wallet(ctx: Context): String =
        sp(ctx).getString("wallet", null)?.takeIf { it.isNotBlank() } ?: Config.DEFAULT_WALLET

    fun setWallet(ctx: Context, value: String) = sp(ctx).edit().putString("wallet", value.trim()).apply()

    fun customName(ctx: Context): String = sp(ctx).getString("worker_name", "") ?: ""
    fun setCustomName(ctx: Context, value: String) =
        sp(ctx).edit().putString("worker_name", value.filter { it.isLetterOrDigit() }.take(20)).apply()

    fun mode(ctx: Context): PowerMode =
        try {
            PowerMode.valueOf(sp(ctx).getString("mode", PowerMode.MAX.name) ?: PowerMode.MAX.name)
        } catch (e: Exception) {
            PowerMode.MAX
        }

    fun setMode(ctx: Context, value: PowerMode) = sp(ctx).edit().putString("mode", value.name).apply()

    // Quel programme de minage fonctionne sur ce téléphone (0 = optimisé, 1 = secours)
    fun engineIndex(ctx: Context): Int = sp(ctx).getInt("engine_idx", 0)
    fun setEngineIndex(ctx: Context, value: Int) = sp(ctx).edit().putInt("engine_idx", value).apply()

    // Temps de minage cumulé (millisecondes)
    fun miningMs(ctx: Context): Long = sp(ctx).getLong("mining_ms", 0L)
    fun addMiningMs(ctx: Context, delta: Long) =
        sp(ctx).edit().putLong("mining_ms", miningMs(ctx) + delta).apply()

    // Identifiant aléatoire de secours, créé une seule fois
    fun randomSuffix(ctx: Context): String {
        val existing = sp(ctx).getString("random_suffix", null)
        if (!existing.isNullOrBlank()) return existing
        val created = java.util.UUID.randomUUID().toString().replace("-", "").take(6)
        sp(ctx).edit().putString("random_suffix", created).apply()
        return created
    }
}
