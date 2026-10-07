package com.monchamp.verusfarm

import android.content.Context
import android.os.Build
import java.io.File

// Rapport de diagnostic : pourquoi le minage ne démarre pas sur ce téléphone ?
object Diagnostics {

    fun build(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("Verumine : diagnostic")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Types de processeur pris en charge : ${DeviceIdentity.allAbis()}")
        sb.appendLine("Système 64 bits : ${if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "oui" else "non"}")
        sb.appendLine("Cœurs : ${Runtime.getRuntime().availableProcessors()}")
        sb.appendLine("Puce : ${cpuInfo("Hardware")}")
        val features = cpuInfo("Features")
        sb.appendLine("Fonctions du processeur : $features")
        val list = features.split(" ")
        sb.appendLine("Chiffrement AES : ${if (list.contains("aes")) "oui" else "non"}   PMULL : ${if (list.contains("pmull")) "oui" else "non"}")

        val dir = File(ctx.applicationInfo.nativeLibraryDir ?: "")
        sb.appendLine("Dossier des programmes : ${dir.path}")
        val files = dir.listFiles()?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            sb.appendLine("  (vide)")
        } else {
            files.forEach { sb.appendLine("  ${it.name} (${it.length()} octets)") }
        }

        var tested = false
        for (name in Config.ENGINES) {
            val f = File(dir, name)
            if (f.exists()) {
                tested = true
                sb.appendLine(testEngine(f))
            }
        }
        if (!tested) sb.appendLine("Aucun programme de minage dans l'application pour ce processeur.")
        return sb.toString()
    }

    private fun cpuInfo(key: String): String = try {
        File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith(key) }?.substringAfter(":")?.trim() ?: "inconnu"
        }
    } catch (e: Exception) {
        "illisible"
    }

    // Lance le programme avec --version : s'il plante, on le voit tout de suite
    private fun testEngine(f: File): String = try {
        val p = ProcessBuilder(f.absolutePath, "--version").redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { if (out.length < 400) out.appendLine(it) }
            } catch (_: Exception) {
            }
        }
        reader.start()
        var code: Int? = null
        val end = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < end) {
            try {
                code = p.exitValue()
                break
            } catch (e: IllegalThreadStateException) {
                Thread.sleep(100)
            }
        }
        if (code == null) {
            p.destroy()
            "Test ${f.name} : pas de réponse en 5 secondes"
        } else {
            reader.join(500)
            val hint = when (code) {
                0 -> "  => le programme démarre correctement"
                132, 4 -> "  => instruction illégale : le processeur n'a pas les instructions nécessaires"
                126, 127 -> "  => le programme ne peut pas être lancé sur ce système"
                else -> ""
            }
            "Test ${f.name} : code $code\n${out.toString().trim()}\n$hint".trim()
        }
    } catch (e: Exception) {
        "Test ${f.name} impossible : ${e.javaClass.simpleName} ${e.message}"
    }
}
