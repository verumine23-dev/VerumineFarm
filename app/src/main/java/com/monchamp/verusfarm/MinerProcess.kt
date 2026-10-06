package com.monchamp.verusfarm

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Lance et arrête le programme de minage (ccminer) comme un processus à part.
class MinerProcess(
    private val ctx: Context,
    private val onLine: (String) -> Unit,   // chaque ligne écrite par le mineur
    private val onExit: (Int) -> Unit       // arrêt NON voulu (plantage, etc.), avec le code de sortie
) {
    enum class Result { OK, NO_ENGINE, ERROR }

    @Volatile private var process: Process? = null
    @Volatile private var stopFlag = AtomicBoolean(false)

    private fun libDir(): String = ctx.applicationInfo.nativeLibraryDir ?: ""

    // Liste des programmes de minage réellement présents dans l'application
    fun availableEngines(): List<String> =
        Config.ENGINES.filter { name ->
            val f = File(libDir(), name)
            f.exists() && f.length() > 0
        }

    fun isRunning(): Boolean = process?.isAlive == true

    fun start(engine: String, host: String, threads: Int): Result {
        if (isRunning()) return Result.OK

        val file = File(libDir(), engine)
        if (!file.exists()) return Result.NO_ENGINE

        val command = listOf(
            file.absolutePath,
            "-a", "verus",
            "-o", "stratum+tcp://$host:${Config.POOL_PORT}",
            "-u", DeviceIdentity.poolUser(ctx),
            "-p", Config.POOL_PASSWORD,
            "-t", threads.toString()
        )

        return try {
            val p = ProcessBuilder(command)
                .directory(ctx.filesDir)
                .redirectErrorStream(true)
                .start()
            val flag = AtomicBoolean(false)
            process = p
            stopFlag = flag

            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (!flag.get()) onLine(line)
                    }
                } catch (_: Exception) {
                }
                val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
                if (!flag.get()) onExit(code)
            }
            reader.isDaemon = true
            reader.name = "miner-reader"
            reader.start()
            Result.OK
        } catch (e: Exception) {
            Log.e(TAG, "Impossible de lancer $engine", e)
            Result.ERROR
        }
    }

    // Arrêt voulu : on lève le drapeau d'abord, pour ne pas le prendre pour un plantage
    fun stop() {
        val p = process ?: return
        stopFlag.set(true)
        process = null
        try { p.destroy() } catch (_: Exception) { }
        Thread {
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
            } catch (_: Exception) { }
        }.start()
    }

    // Si Android a tué l'application mais pas son mineur, on le nettoie
    // avant de relancer (évite deux mineurs en double).
    fun killOrphans() {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("pkill", "-f", "libccminer"))
            p.waitFor(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "MinerProcess"
    }
}
