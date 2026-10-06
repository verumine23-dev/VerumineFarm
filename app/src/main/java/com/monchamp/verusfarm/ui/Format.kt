package com.monchamp.verusfarm.ui

import java.util.Locale

// 207738672.75 -> "207.74 MH/s"
fun fmtHash(hs: Double): String {
    if (hs.isNaN() || hs <= 0.0) return "0 H/s"
    val units = arrayOf("H/s", "kH/s", "MH/s", "GH/s", "TH/s")
    var v = hs
    var i = 0
    while (v >= 1000.0 && i < units.size - 1) {
        v /= 1000.0
        i++
    }
    return String.format(Locale.US, "%.2f %s", v, units[i])
}

fun fmtVrsc(v: Double): String = String.format(Locale.US, "%.4f", v)

// 52320000 ms -> "14h 32m"
fun fmtDuration(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun maskWallet(w: String): String =
    if (w.length > 14) w.take(7) + "…" + w.takeLast(5) else w
