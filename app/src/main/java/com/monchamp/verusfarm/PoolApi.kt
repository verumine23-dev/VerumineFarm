package com.monchamp.verusfarm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Un appareil (« worker ») vu par Luckpool
data class PoolWorker(
    val name: String,
    val hashrate: Double,     // hachages par seconde
    val shares: Double,
    val online: Boolean,
    val server: String
)

// Ce que Luckpool sait de ton adresse VRSC
data class PoolStats(
    val balance: Double,      // solde en attente (VRSC)
    val immature: Double,     // en maturation (VRSC)
    val paid: Double,         // déjà payé (VRSC)
    val hashrate: Double,     // hashrate total de tous tes appareils
    val avg24h: Double,
    val efficiency: Double,
    val workers: List<PoolWorker>
)

data class HashPoint(val tsSec: Long, val hashrate: Double)

// Interroge l'API publique de Luckpool (aucun compte, aucune clé)
object PoolApi {
    private const val BASE = "https://luckpool.net/verus"

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "Verumine-Android")
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    fun fetchStats(address: String): PoolStats? {
        val body = get("$BASE/miner/$address") ?: return null
        return try {
            val j = JSONObject(body)
            val workers = ArrayList<PoolWorker>()
            val arr = j.optJSONArray("workers")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    // format : nom:hashrate:parts:on|off:serveur:...
                    val p = arr.optString(i).split(":")
                    if (p.size >= 4) {
                        workers.add(
                            PoolWorker(
                                name = p[0],
                                hashrate = p[1].toDoubleOrNull() ?: 0.0,
                                shares = p[2].toDoubleOrNull() ?: 0.0,
                                online = p[3] == "on",
                                server = if (p.size > 4) p[4] else ""
                            )
                        )
                    }
                }
            }
            PoolStats(
                balance = j.optDouble("balance", 0.0),
                immature = j.optDouble("immature", 0.0),
                paid = j.optDouble("paid", 0.0),
                hashrate = j.optDouble("hashrateSols", 0.0),
                avg24h = j.optDouble("avgHashrateSols24HR", 0.0),
                efficiency = j.optDouble("efficiency", 0.0),
                workers = workers
            )
        } catch (e: Exception) {
            null
        }
    }

    fun fetchHistory(address: String): List<HashPoint> {
        val body = get("$BASE/miner/history/$address") ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(body)
            val out = ArrayList<HashPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val a = o.optJSONArray("a") ?: continue
                if (a.length() >= 2) out.add(HashPoint(a.optLong(0), a.optDouble(1, 0.0)))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
