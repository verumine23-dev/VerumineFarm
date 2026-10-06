package com.monchamp.verusfarm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.ContextCompat

// Quand internet revient et que le service n'est plus actif, on le relance.
class NetworkWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (Prefs.isEnabled(context) && !MiningService.running) {
                ContextCompat.startForegroundService(context, Intent(context, MiningService::class.java))
            }
        } catch (e: Exception) {
            // Android peut refuser selon la version : le prochain réveil réessaiera
            Log.w("NetworkWake", "relance refusée", e)
        }
    }
}

object NetworkWake {
    private const val ACTION = "com.monchamp.verusfarm.NETWORK_WAKE"

    // Demande à Android de nous réveiller dès qu'un réseau avec internet apparaît
    fun register(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val pi = PendingIntent.getBroadcast(
                ctx, 77,
                Intent(ctx, NetworkWakeReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            cm.registerNetworkCallback(request, pi)
        } catch (e: Exception) {
            Log.w("NetworkWake", "enregistrement impossible", e)
        }
    }
}
