package com.monchamp.verusfarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Le cœur de l'application : il tourne en arrière-plan, surveille internet,
 * la chaleur et la batterie, et relance le minage quand il le faut.
 * Chaque étape est protégée pour qu'une erreur ne ferme jamais l'application.
 */
class MiningService : Service() {

    private enum class Level(val label: String) {
        FULL("Pleine puissance"),
        REDUCED("Puissance réduite"),
        PAUSED("En pause")
    }

    private lateinit var miner: MinerProcess
    private lateinit var cm: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallbackRegistered = false
    private var stopping = false

    // Capteurs
    private var hasInternet = false
    private var batteryTempC = 0f
    private var batteryPercent = 100
    private var charging = true
    private var thermalStatus = 0
    private var level = Level.FULL

    // Minage
    private var engineIdx = 0
    private var poolIndex = 0
    private var failures = 0
    private var illegalCount = 0
    private var retryDelayMs = 5_000L
    private var retryPending = false
    private var hashed = false
    private var runningThreads = 0
    private var currentHost = ""
    private var accepted = 0
    private var rejected = 0

    // Notification
    private var lastNotifText = ""
    private var lastNotifAt = 0L

    private val hashRegex = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([kKMG]?)H/s""")
    private val acceptedRegex = Regex("""accepted:\s*(\d+)/(\d+)""")

    // ---------- Internet : arrivée et perte ----------
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { hasInternet = checkInternet(); evaluate() }
        }

        override fun onLost(network: Network) {
            handler.post { hasInternet = checkInternet(); evaluate() }
        }
    }

    // Réessaie après un plantage (avec une attente qui grandit)
    private val retryRunnable = Runnable {
        retryPending = false
        evaluate()
    }

    // Contrôle toutes les 15 secondes : température, batterie, internet, relance
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (stopping) return
            try {
                readSensors()
                hasInternet = checkInternet()
                evaluate()
                if (miner.isRunning()) {
                    Prefs.addMiningMs(this@MiningService, TICK_MS)
                    MiningState.set { it.copy(miningMs = Prefs.miningMs(this@MiningService)) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "tick", e)
            }
            if (!stopping) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createChannel()
        miner = MinerProcess(
            this,
            onLine = { line -> handler.post { onMinerLine(line) } },
            onExit = { code -> handler.post { onMinerExited(code) } }
        )
        miner.killOrphans()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Demande d'arrêt volontaire
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            shutdown(setIdle = true)
            return START_NOT_STICKY
        }

        // Android impose d'afficher la notification tout de suite
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        stopping = false
        acquireLocks()
        registerNetwork()
        NetworkWake.register(this)

        if (intent?.getBooleanExtra(EXTRA_RELOAD, false) == true) {
            // Nouvelle adresse ou nouveau nom : on repart proprement
            miner.stop()
            retryPending = false
            handler.removeCallbacks(retryRunnable)
        }

        engineIdx = Prefs.engineIndex(this)
        readSensors()
        hasInternet = checkInternet()

        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)

        // START_STICKY : si Android coupe le service, il le relance tout seul
        return START_STICKY
    }

    // ---------- Décision centrale ----------
    private fun evaluate() {
        if (stopping) return
        if (!Prefs.isEnabled(this)) {
            shutdown(setIdle = true)
            return
        }

        level = computeLevel()

        if (miner.availableEngines().isEmpty()) {
            engineMissing()
            return
        }
        if (!Config.isUsableWallet(Prefs.wallet(this))) {
            haltMiner("Adresse VRSC manquante : ouvre l'application", Tone.ERROR)
            return
        }
        if (!hasInternet) {
            haltMiner("En attente d'internet…", Tone.WARN)
            return
        }
        if (level == Level.PAUSED) {
            haltMiner(pauseReason(), Tone.WARN)
            return
        }

        val desired = threadsFor(level)
        if (miner.isRunning() && runningThreads != desired) miner.stop()
        if (!miner.isRunning() && !retryPending) startMiner(desired)
        publish()
    }

    private fun haltMiner(text: String, tone: Tone) {
        if (miner.isRunning()) miner.stop()
        runningThreads = 0
        MiningState.set { it.copy(hashrate = "—") }
        setStatus(text, tone)
        publish()
    }

    private fun startMiner(threads: Int) {
        val engines = miner.availableEngines()
        if (engines.isEmpty()) {
            engineMissing()
            return
        }
        if (engineIdx >= engines.size) engineIdx = 0

        val host = Config.POOLS[poolIndex % Config.POOLS.size]
        when (miner.start(engines[engineIdx], host, threads)) {
            MinerProcess.Result.OK -> {
                runningThreads = threads
                currentHost = host
                hashed = false
                accepted = 0
                rejected = 0
                MiningState.set { it.copy(accepted = 0, rejected = 0, hashrate = "—") }
                setStatus("Connexion au pool…", Tone.OK)
            }
            MinerProcess.Result.NO_ENGINE -> engineMissing()
            MinerProcess.Result.ERROR -> handleFailure(-1)
        }
    }

    // ---------- Ce que dit le mineur ----------
    private val ansiRegex = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    private fun onMinerLine(rawLine: String) {
        if (stopping) return
        val line = ansiRegex.replace(rawLine, "")
        MiningState.addLog(line.take(160))

        acceptedRegex.find(line)?.let {
            accepted = it.groupValues[1].toIntOrNull() ?: accepted
            val total = it.groupValues[2].toIntOrNull() ?: accepted
            rejected = (total - accepted).coerceAtLeast(0)
        }

        val m = hashRegex.findAll(line).lastOrNull()
        if (m != null) {
            val rate = "${m.groupValues[1]} ${m.groupValues[2]}H/s"
            if (!hashed) {
                hashed = true
                failures = 0
                illegalCount = 0
                retryDelayMs = 5_000L
                // On retient le programme qui fonctionne sur ce téléphone
                if (Prefs.engineIndex(this) != engineIdx) Prefs.setEngineIndex(this, engineIdx)
            }
            val suffix = if (level == Level.REDUCED) " (puissance réduite)" else ""
            MiningState.set {
                it.copy(
                    hashrate = rate, accepted = accepted, rejected = rejected,
                    status = "Minage en cours$suffix", tone = Tone.OK
                )
            }
            maybeNotify("Minage $rate, ${batteryTempC.toInt()}°C")
        } else if (acceptedRegex.containsMatchIn(line)) {
            MiningState.set { it.copy(accepted = accepted, rejected = rejected) }
        }
    }

    // Le mineur s'est arrêté sans qu'on l'ait demandé
    private fun onMinerExited(code: Int) {
        if (stopping || !Prefs.isEnabled(this)) return
        runningThreads = 0
        MiningState.addLog("Le mineur s'est arrêté (code $code)")
        handleFailure(code)
    }

    private fun handleFailure(code: Int) {
        failures++
        val engines = miner.availableEngines()
        // 132 et 4 = « instruction illégale » : le processeur ne comprend pas ce programme
        // 139 et 11 = plantage mémoire
        val incompatible = code == 132 || code == 4 || code == 139 || code == 11 || code == -1

        // Instruction illégale répétée : ce processeur n'a pas le chiffrement ARM, inutile d'insister
        val illegal = code == 132 || code == 4
        illegalCount = if (illegal) illegalCount + 1 else 0
        if (illegal && illegalCount >= 2 * engines.size.coerceAtLeast(1)) {
            cpuUnsupported()
            return
        }

        if (incompatible && engines.size > 1) {
            engineIdx = (engineIdx + 1) % engines.size
            Prefs.setEngineIndex(this, engineIdx)
            MiningState.addLog("Changement de programme de minage (${engines[engineIdx]})")
            failures = 0
        } else if (failures >= 3) {
            poolIndex++            // on essaie le serveur suivant
            failures = 0
        }

        retryPending = true
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
        setStatus("Nouvelle tentative…", Tone.WARN)
    }

    // Aucun programme de minage pour ce processeur : message clair, pas de plantage
    private fun engineMissing() {
        Prefs.setEnabled(this, false)
        shutdown(setIdle = false)
        MiningState.set {
            it.copy(
                enabled = false, engineOk = false, hashrate = "—", threads = 0, tone = Tone.ERROR,
                status = "Aucun programme de minage pour ce processeur (${DeviceIdentity.allAbis()}). Ouvre Plus, puis Diagnostic."
            )
        }
    }

    // Le processeur ne comprend pas les instructions nécessaires : message clair, plus de relance
    private fun cpuUnsupported() {
        Prefs.setEnabled(this, false)
        shutdown(setIdle = false)
        MiningState.set {
            it.copy(
                enabled = false, engineOk = false, hashrate = "—", threads = 0, tone = Tone.ERROR,
                status = "Ce processeur ne gère pas les instructions de chiffrement ARM " +
                    "(AES et PMULL) nécessaires au minage de Verus."
            )
        }
    }

    // ---------- Température, batterie, puissance ----------
    private fun readSensors() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i != null) {
            batteryTempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0 && scale > 0) batteryPercent = lvl * 100 / scale
            charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                thermalStatus = pm.currentThermalStatus
            } catch (_: Exception) {
            }
        }
    }

    private fun computeLevel(): Level {
        if (!charging && batteryPercent < 20) return Level.PAUSED
        val hot = batteryTempC >= 45f || thermalStatus >= 3
        val warm = batteryTempC >= 41f || thermalStatus == 2
        val cool = batteryTempC <= 38f && thermalStatus <= 1
        val ok = batteryTempC <= 40f && thermalStatus <= 2
        return when (level) {
            Level.FULL -> if (hot) Level.PAUSED else if (warm) Level.REDUCED else Level.FULL
            Level.REDUCED -> if (hot) Level.PAUSED else if (cool) Level.FULL else Level.REDUCED
            Level.PAUSED -> if (ok) Level.REDUCED else Level.PAUSED
        }
    }

    private fun threadsFor(l: Level): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val base = when (Prefs.mode(this)) {
            PowerMode.ECO -> cores / 2
            PowerMode.BALANCED -> cores * 3 / 4
            PowerMode.MAX -> cores
        }.coerceAtLeast(1)
        return if (l == Level.REDUCED) (base / 2).coerceAtLeast(1) else base
    }

    private fun pauseReason(): String =
        if (!charging && batteryPercent < 20) "Pause : batterie faible, branche le téléphone"
        else "Pause : téléphone trop chaud (${batteryTempC.toInt()}°C), il refroidit"

    private fun checkInternet(): Boolean = try {
        val n = cm.activeNetwork
        val caps = if (n != null) cm.getNetworkCapabilities(n) else null
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        false
    }

    // ---------- Affichage ----------
    private fun publish() {
        MiningState.set {
            it.copy(
                enabled = true,
                tempC = batteryTempC, batteryPercent = batteryPercent, charging = charging,
                threads = if (miner.isRunning()) runningThreads else 0,
                pool = if (miner.isRunning()) currentHost else "—",
                levelLabel = level.label, engineOk = true
            )
        }
    }

    private fun setStatus(text: String, tone: Tone) {
        MiningState.set { it.copy(status = text, tone = tone) }
        updateNotification(text)
    }

    private fun maybeNotify(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifAt > 10_000L) updateNotification(text)
    }

    // ---------- Arrêt ----------
    private fun shutdown(setIdle: Boolean) {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        miner.stop()
        runningThreads = 0
        if (setIdle) {
            MiningState.set {
                it.copy(
                    enabled = false, status = "Arrêté", tone = Tone.IDLE,
                    hashrate = "—", threads = 0, pool = "—", levelLabel = "—"
                )
            }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        try { miner.stop() } catch (_: Exception) { }
        if (networkCallbackRegistered) {
            try { cm.unregisterNetworkCallback(networkCallback) } catch (_: Exception) { }
            networkCallbackRegistered = false
        }
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wakeLock = null
        wifiLock = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Verrous : empêchent le téléphone de s'endormir ----------
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VerusFarm::Mining")
                    .apply { setReferenceCounted(false); acquire() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock", e)
        }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VerusFarm::Wifi")
                    .apply { setReferenceCounted(false); acquire() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifiLock", e)
        }
    }

    private fun registerNetwork() {
        if (networkCallbackRegistered) return
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "networkCallback", e)   // le contrôle toutes les 15 s prend le relais
        }
    }

    // ---------- Notification ----------
    private fun createChannel() {
        try {
            val channel = NotificationChannel(CHANNEL_ID, "Minage", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.w(TAG, "channel", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Verumine")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_mining)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun enterForeground(): Boolean = try {
        val n = buildNotification("Démarrage…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground refusé", e)
        false
    }

    private fun updateNotification(text: String) {
        if (text == lastNotifText && SystemClock.elapsedRealtime() - lastNotifAt < 10_000L) return
        lastNotifText = text
        lastNotifAt = SystemClock.elapsedRealtime()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_STOP = "com.monchamp.verusfarm.STOP"
        const val EXTRA_RELOAD = "reload"
        private const val CHANNEL_ID = "mining"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 15_000L
        private const val TAG = "MiningService"

        @Volatile
        var running = false
    }
}
