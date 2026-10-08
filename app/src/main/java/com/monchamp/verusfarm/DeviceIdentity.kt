package com.monchamp.verusfarm

import android.content.Context
import android.os.Build
import android.provider.Settings

// Donne à chaque téléphone un nom unique pour que Luckpool les distingue,
// même si tous utilisent la même adresse VRSC.
object DeviceIdentity {

    // Nom automatique : modèle + 6 caractères de l'identifiant Android
    fun autoName(ctx: Context): String {
        val androidId = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
        val clean = androidId?.filter { it.isLetterOrDigit() } ?: ""
        // 9774d56d682e549c = identifiant bogué présent sur certains téléphones
        val suffix = if (clean.length < 6 || clean.startsWith("9774d56d")) {
            Prefs.randomSuffix(ctx)
        } else {
            clean.takeLast(6)
        }
        // Luckpool affiche « noname » si le nom du worker ne lui plaît pas : on reste simple
        // (minuscules et chiffres, 12 caractères au plus).
        val model = Build.MODEL.filter { it.isLetterOrDigit() }.lowercase().take(6).ifEmpty { "phone" }
        return (model + suffix.takeLast(4)).lowercase()
    }

    // Nom réellement utilisé : celui choisi par toi, sinon le nom automatique
    fun workerName(ctx: Context): String =
        Prefs.customName(ctx).ifBlank { autoName(ctx) }

    // Identifiant envoyé à Luckpool : ADRESSE.nomDuWorker
    fun poolUser(ctx: Context): String = "${Prefs.wallet(ctx)}.${workerName(ctx)}"

    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "inconnu"

    fun allAbis(): String = Build.SUPPORTED_ABIS.joinToString(", ").ifEmpty { "inconnu" }
}
