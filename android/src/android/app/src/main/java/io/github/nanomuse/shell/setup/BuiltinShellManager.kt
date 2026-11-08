package io.github.nanomuse.shell.setup

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import io.github.nanomuse.shell.SharedShell
import io.github.nanomuse.hands.Hands
import io.github.nanomuse.shell.adb.AdbConnectPort
import io.github.nanomuse.shell.adb.AdbConnection
import io.github.nanomuse.shell.adb.AdbConnectionException
import io.github.nanomuse.shell.adb.AdbKeyUnreadableException
import io.github.nanomuse.shell.adb.AdbPairing
import io.github.nanomuse.shell.adb.AdbPairingException
import io.github.nanomuse.shell.adb.AdbServiceTypes
import io.github.nanomuse.shell.adb.AndroidAdb
import io.github.nanomuse.shell.adb.ConnectCandidates
import io.github.nanomuse.shell.adb.LocalPortScan
import io.github.nanomuse.shell.adb.NioPortScanner
import io.github.nanomuse.shell.adb.NsdAdbServiceFinder
import io.github.nanomuse.shell.adb.PortCache
import io.github.nanomuse.shell.recovery.A11yObservation
import io.github.nanomuse.shell.recovery.AccessibilityList
import io.github.nanomuse.shell.recovery.AccessibilityRepair
import io.github.nanomuse.shell.recovery.AutoOnBlock
import io.github.nanomuse.shell.recovery.PermissionCommands
import io.github.nanomuse.shell.recovery.PermissionResult
import io.github.nanomuse.shell.recovery.RecoveryCapability
import io.github.nanomuse.shell.recovery.RecoveryConsent
import io.github.nanomuse.shell.recovery.RecoveryConsentStore
import io.github.nanomuse.shell.recovery.RecoveryPermissions
import io.github.nanomuse.shell.recovery.ShellCommandRunner
import io.github.nanomuse.shell.recovery.WirelessDebuggingAutoOn
import com.openminis.app.accessibility.MinisAccessibilityService
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.provider.Settings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * 内置特权助手连接管理器:
 * 负责无线调试一键免电脑配对、守护进程本地拉起、开机/网络恢复与自愈。
 */
class BuiltinShellManager private constructor(private val app: Context) : BuiltinShellControl {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val io = Dispatchers.IO
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _status = MutableStateFlow<BuiltinShellStatus>(initialStatus())
    override val status: StateFlow<BuiltinShellStatus> = _status.asStateFlow()

    private val _pairingDiscovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    override val pairingDiscovery: StateFlow<PairingDiscovery> = _pairingDiscovery.asStateFlow()

    private val _scanningPorts = MutableStateFlow(false)
    val scanningPorts: StateFlow<Boolean> = _scanningPorts.asStateFlow()

    @Volatile var lastError: String? = null
        private set

    private val finder = NsdAdbServiceFinder(app)
    private var discoveryJob: Job? = null
    private var connectJob: Job? = null
    private var repairJob: Job? = null
    private var started = false
    private val runState = HelperRunState(prefs.getBoolean(KEY_DESIRED, paired))
    private val recoveryBudget = HelperRecoveryBudget()
    @Volatile private var cleanupPending = prefs.getBoolean(KEY_CLEANUP_PENDING, false)
    @Volatile private var permanentFailure = false
    @Volatile private var rootAuthorized = false
    @Volatile private var stopping = false

    private val portCache = object : PortCache {
        override fun load(): Int? = prefs.getInt("port", 0).takeIf { it > 0 }
        override fun save(port: Int) { prefs.edit().putInt("port", port).apply() }
    }

    private val consentStore = object : RecoveryConsentStore {
        private val _consent = MutableStateFlow(
            RecoveryConsent(
                applied = RecoveryCapability.entries.filter { prefs.getBoolean("applied_${it.name}", false) }.toSet(),
                declined = RecoveryCapability.entries.filter { prefs.getBoolean("declined_${it.name}", false) }.toSet(),
            ),
        )
        override val consent: StateFlow<RecoveryConsent> = _consent.asStateFlow()

        override fun save(consent: RecoveryConsent) {
            _consent.value = consent
            val e = prefs.edit()
            RecoveryCapability.entries.forEach {
                e.putBoolean("applied_${it.name}", consent.isApplied(it))
                e.putBoolean("declined_${it.name}", !consent.wants(it))
            }
            e.apply()
        }
    }

    val coordinator by lazy { PairingCoordinator(app, this) }

    val recoveryPermissions: RecoveryPermissions by lazy {
        RecoveryPermissions(
            runner = {
                if (ShizukuManager.isReady()) {
                    ShellCommandRunner { cmd ->
                        withContext(io) {
                            ShizukuManager.runProcess(arrayOf("sh", "-c", cmd)).combined
                        }
                    }
                } else if (paired && DeveloperSettings.wirelessDebugging(app.contentResolver)) {
                    ShellCommandRunner { cmd ->
                        withContext(io) {
                            connectAdb().use { conn ->
                                conn.exec(cmd).readText()
                            }
                        }
                    }
                } else null
            },
            store = consentStore,
            packageName = app.packageName,
            allowApply = { runState.desiredRunning && !cleanupPending && !stopping },
        )
    }

    private fun canWriteSecure() =
        app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private suspend fun restartAccessibilityDirectly(gapMs: Long): PermissionResult = withContext(io) {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun allowed() = runState.desiredRunning && !cleanupPending && !stopping
        fun read() = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        fun writeIfAllowed(list: String, enabling: Boolean = false): Boolean = synchronized(this@BuiltinShellManager) {
            if (!allowed()) return@synchronized false
            if (enabling) {
                consentStore.save(consentStore.consent.value.withApplied(RecoveryCapability.Accessibility, true))
            }
            check(Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.ifEmpty { null }))
            check(Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1))
            true
        }
        try {
            if (!allowed()) return@withContext PermissionResult.NoRunner
            val current = read()
            if (AccessibilityList.contains(current, me)) {
                if (!writeIfAllowed(AccessibilityList.without(current, me))) return@withContext PermissionResult.NoRunner
                withContext(NonCancellable) restore@{
                    delay(gapMs)
                    try {
                        if (!writeIfAllowed(AccessibilityList.with(read(), me), enabling = true)) {
                            return@restore PermissionResult.NoRunner
                        }
                        PermissionResult.Done
                    } catch (e: Exception) {
                        runCatching { writeIfAllowed(current.orEmpty(), enabling = true) }
                        throw e
                    }
                }
            } else {
                if (writeIfAllowed(AccessibilityList.with(current, me), enabling = true)) PermissionResult.Done
                else PermissionResult.NoRunner
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    val accessibilityRepair by lazy {
        AccessibilityRepair(
            restart = { gap -> if (canWriteSecure()) restartAccessibilityDirectly(gap) else recoveryPermissions.restartAccessibility(gap) },
            consent = { consentStore.consent.value },
            direct = ::canWriteSecure,
            allowed = { runState.desiredRunning && !cleanupPending },
        )
    }

    private val userUnlocked: StateFlow<Boolean> by lazy {
        fun read() = runCatching { app.getSystemService(UserManager::class.java)?.isUserUnlocked }.getOrNull() != false
        callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { trySend(read()) }
            }
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            trySend(read())
            awaitClose { runCatching { app.unregisterReceiver(receiver) } }
        }.stateIn(scope, SharingStarted.Eagerly, read())
    }

    val accessibility: StateFlow<A11yObservation> by lazy {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun enabled() = AccessibilityList.contains(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), me)
        val listChanges = callbackFlow {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
            trySend(Unit)
            awaitClose { cr.unregisterContentObserver(observer) }
        }
        combine(listChanges, MinisAccessibilityService.instance) { _, service ->
            A11yObservation(enabled(), service != null)
        }.stateIn(scope, SharingStarted.Eagerly, A11yObservation(enabled(), MinisAccessibilityService.getInstance() != null))
    }

    val wirelessAutoOn: WirelessDebuggingAutoOn by lazy {
        WirelessDebuggingAutoOn(
            wants = { runState.desiredRunning && !cleanupPending && consentStore.consent.value.wants(RecoveryCapability.AutoWirelessDebugging) },
            canWrite = { app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED },
            read = { DeveloperSettings.wirelessDebugging(app.contentResolver) },
            write = {
                synchronized(this) {
                    runState.desiredRunning && !cleanupPending && !stopping &&
                        DeveloperSettings.turnOnWirelessDebugging(app.contentResolver)
                }
            },
        )
    }

    init {
        scope.launch {
            var wasReady = false
            SharedShell.shell.collect { shell ->
                if (shell != null) {
                    if (!runState.desiredRunning || cleanupPending) {
                        SharedShell.release()
                        return@collect
                    }
                    wasReady = true
                    recoveryBudget.connected(android.os.SystemClock.elapsedRealtime())
                    _status.value = BuiltinShellStatus.Running
                    wirelessAutoOn.reset()
                    if (paired) runCatching { recoveryPermissions.applyPending() }
                } else if (wasReady) {
                    wasReady = false
                    if (runState.desiredRunning && !cleanupPending) {
                        _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)
                        if (recoveryBudget.retryAfterDeath(android.os.SystemClock.elapsedRealtime())) {
                            // A fast crash can arrive while the successful start is still unwinding.
                            scope.launch {
                                connectJob?.join()
                                resumeIfWanted()
                            }
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true

        app.contentResolver.registerContentObserver(
            DeveloperSettings.wirelessDebuggingUri,
            false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val on = DeveloperSettings.wirelessDebugging(app.contentResolver)
                    AppLogger.info(TAG, "wireless debugging state changed: on=$on")
                    if (on && paired && !SharedShell.isReady()) {
                        resumeIfWanted()
                    } else if (!on && paired && runState.desiredRunning && !cleanupPending && !SharedShell.isReady()) {
                        _status.value = BuiltinShellStatus.WirelessDebuggingOff
                    }
                }
            },
        )

        runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AppLogger.info(TAG, "wifi connected, checking if helper needed...")
                    if (paired && !SharedShell.isReady()) resumeIfWanted()
                }
            })
        }

        startRepairWatch()
    }

    override val paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)

    private fun setPaired(value: Boolean) {
        prefs.edit().putBoolean(KEY_PAIRED, value).apply()
    }

    private fun initialStatus(): BuiltinShellStatus = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> BuiltinShellStatus.Failed(BuiltinShellFailure.UnsupportedAndroid)
        prefs.getBoolean(KEY_CLEANUP_PENDING, false) -> BuiltinShellStatus.Failed(BuiltinShellFailure.CleanupPending)
        SharedShell.isReady() -> BuiltinShellStatus.Running
        !prefs.getBoolean(KEY_DESIRED, paired) || !paired -> BuiltinShellStatus.NotPaired
        !DeveloperSettings.wirelessDebugging(app.contentResolver) -> BuiltinShellStatus.WirelessDebuggingOff
        else -> BuiltinShellStatus.Connecting
    }

    private fun startRepairWatch() {
        if (repairJob?.isActive == true || !runState.desiredRunning || cleanupPending) return
        repairJob = scope.launch { accessibilityRepair.watch(accessibility, status, userUnlocked) }
    }

    private fun persistDesired(wanted: Boolean) {
        prefs.edit().putBoolean(KEY_DESIRED, wanted).apply()
    }

    /** Explicit user request; automatic callbacks use resumeIfWanted instead. */
    fun need() { scheduleStart(userInitiated = true) }

    fun restoreAfterBoot() {
        if (consentStore.consent.value.wants(RecoveryCapability.RestoreAfterReboot)) resumeIfWanted()
    }

    private fun resumeIfWanted() {
        if (runState.desiredRunning && !cleanupPending && !permanentFailure) scheduleStart(userInitiated = false)
    }

    @Synchronized
    private fun scheduleStart(userInitiated: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.UnsupportedAndroid)
            return
        }
        if (stopping) return
        if (cleanupPending) {
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.CleanupPending)
            return
        }
        if (!userInitiated && (!runState.desiredRunning || permanentFailure)) return
        if (connectJob?.isActive == true) return
        if (userInitiated) {
            permanentFailure = false
            recoveryBudget.reset()
        }
        val generation = runState.request()
        persistDesired(true)
        start()
        startRepairWatch()
        if (SharedShell.isReady()) return
        connectJob = scope.launch(start = CoroutineStart.LAZY) {
            for (attempt in 0..HelperRunState.RETRY_DELAYS_MS.size) {
                if (!runState.current(generation)) break
                if (attempt > 0) delay(HelperRunState.RETRY_DELAYS_MS[attempt - 1])
                if (!runState.current(generation)) break
                lock.withLock {
                    if (runState.current(generation)) launchHelperInternal(allowRoot = userInitiated || rootAuthorized, generation = generation)
                }
                if (!runState.current(generation) || SharedShell.isReady()) break
                val failure = (_status.value as? BuiltinShellStatus.Failed)?.reason
                if (!HelperRunState.retryable(failure)) {
                    permanentFailure = failure in setOf(
                        BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFailure.KeyUnreadable,
                        BuiltinShellFailure.RootDenied, BuiltinShellFailure.UnsupportedAndroid,
                        BuiltinShellFailure.BackendUnavailable,
                    )
                    break
                }
            }
        }.also { it.start() }
    }

    @Synchronized
    fun prepareManualStart(nonce: String): Boolean {
        if (stopping || cleanupPending || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        connectJob?.cancel()
        runState.request()
        permanentFailure = false
        persistDesired(true)
        start()
        startRepairWatch()
        _status.value = BuiltinShellStatus.Connecting
        SharedShell.prepareStart(nonce)
        return true
    }

    @Synchronized
    private fun prepareHandoff(nonce: String, generation: Long): Boolean {
        if (!runState.current(generation) || stopping || cleanupPending) return false
        SharedShell.prepareStart(nonce)
        return true
    }

    override fun startDiscovery() {
        if (stopping || cleanupPending || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        discoveryJob?.cancel()
        _pairingDiscovery.value = PairingDiscovery.Searching
        discoveryJob = scope.launch {
            AppLogger.info(TAG, "starting mDNS pairing discovery...")
            val ports = finder.findPorts(AdbServiceTypes.PAIRING, 10 * 60_000L)
            val port = ports.firstOrNull()
            if (port != null) {
                AppLogger.info(TAG, "mDNS pairing port found: $port")
                _pairingDiscovery.value = PairingDiscovery.Found(port)
            } else {
                _pairingDiscovery.value = PairingDiscovery.Idle
            }
        }
    }

    override fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _pairingDiscovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        connectJob?.cancelAndJoin()
        val pairingContext = currentCoroutineContext()
        val generation = synchronized(this) {
            pairingContext.ensureActive()
            if (stopping || cleanupPending || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            permanentFailure = false
            recoveryBudget.reset()
            persistDesired(true)
            runState.request()
        }
        start()
        startRepairWatch()
        lock.withLock {
            if (!runState.current(generation) || cleanupPending) return@withLock
            _status.value = BuiltinShellStatus.Connecting
            val targetPort = port ?: (pairingDiscovery.value as? PairingDiscovery.Found)?.port
                ?: finder.findPorts(AdbServiceTypes.PAIRING, 5_000L).firstOrNull()
                ?: run {
                    _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed)
                    return@withLock
                }

            AppLogger.info(TAG, "starting pairing with 127.0.0.1:$targetPort, code length=${code.length}")
            val tls = AndroidAdb.tls(AndroidAdb.pairingIdentity(app))
            try {
                withContext(io) {
                    AdbPairing(tls).pair(LOCALHOST, targetPort, code)
                }
                AppLogger.info(TAG, "pairing succeeded!")
                lastError = null
                setPaired(true)
                stopDiscovery()
                if (runState.desiredRunning && !cleanupPending) launchHelperInternal(allowRoot = false, generation = generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbPairingException) {
                AppLogger.warning(TAG, "pairing failed: ${e.reason} - ${e.message}")
                lastError = "AdbPairingException(${e.reason}): ${e.message}"
                val failure = when (e.reason) {
                    AdbPairingException.Reason.WRONG_CODE -> BuiltinShellFailure.WrongCode
                    AdbPairingException.Reason.CONNECT_FAILED -> BuiltinShellFailure.PairingWindowClosed
                    else -> BuiltinShellFailure.Unknown
                }
                _status.value = BuiltinShellStatus.Failed(failure)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "pairing error: ${t.message}")
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.Unknown)
            }
        }
    }

    override suspend fun retry() {
        if (cleanupPending) unpair() else {
            need()
            connectJob?.join()
        }
    }

    private suspend fun stopWork() {
        // Invalidate callbacks before awaiting cancelled work or acquiring the launch lock.
        synchronized(this) {
            stopping = true
            runState.stop()
            persistDesired(false)
            SharedShell.cancelStart()
        }
        Hands.stopCurrent()
        coordinator.end()
        connectJob?.cancelAndJoin()
        connectJob = null
        repairJob?.cancelAndJoin()
        repairJob = null
    }

    suspend fun stop(): Unit = withContext(NonCancellable + io) {
        try {
            stopWork()
            lock.withLock {
                SharedShell.release()
                _status.value = BuiltinShellStatus.NotPaired
            }
        } finally {
            stopping = false
        }
    }

    override suspend fun unpair(): Unit = withContext(NonCancellable + io) {
        cleanupPending = true
        prefs.edit().putBoolean(KEY_CLEANUP_PENDING, true).apply()
        try {
            stopWork()
            lock.withLock {
                val result = recoveryPermissions.revokeAll()
                // Disconnect only after using the still-valid identity to undo our grants.
                SharedShell.release()
                if (result != PermissionResult.Done) {
                    runCatching { MinisAccessibilityService.getInstance()?.disableSelf() }
                    lastError = (result as? PermissionResult.Failed)?.detail
                    _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.CleanupPending)
                    return@withLock
                }
                setPaired(false)
                AndroidAdb.forget(app)
                cleanupPending = false
                prefs.edit().putBoolean(KEY_CLEANUP_PENDING, false).apply()
                lastError = null
                _status.value = BuiltinShellStatus.NotPaired
            }
        } finally {
            stopping = false
        }
    }

    private suspend fun launchHelperInternal(allowRoot: Boolean, generation: Long) {
        if (SharedShell.isReady()) {
            _status.value = BuiltinShellStatus.Running
            return
        }
        if (!runState.current(generation) || cleanupPending) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.UnsupportedAndroid)
            return
        }
        if (ShizukuManager.isReady()) {
            launchLocalBackend(root = false, generation = generation)
            return
        }
        if (!paired) {
            if (allowRoot && hasSu()) launchLocalBackend(root = true, generation = generation)
            else _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.BackendUnavailable)
            return
        }

        var wd = DeveloperSettings.wirelessDebugging(app.contentResolver)
        if (!wd) {
            val wifi = isWifiConnected()
            val gate = wirelessAutoOn.gate(wifi)
            if (gate is io.github.nanomuse.shell.recovery.AutoOnGate.Act) {
                AppLogger.info(TAG, "attempting to auto turn on wireless debugging...")
                _status.value = BuiltinShellStatus.Connecting
                val turned = wirelessAutoOn.turnOn()
                if (turned) {
                    wd = true
                }
            }
        }

        if (!wd) {
            val failure = if (wirelessAutoOn.blocked.value == AutoOnBlock.NotAllowedHere) {
                BuiltinShellFailure.WifiNotAllowed
            } else if (!isWifiConnected()) {
                BuiltinShellFailure.NoWifi
            } else {
                null
            }
            _status.value = if (failure != null) BuiltinShellStatus.Failed(failure) else BuiltinShellStatus.WirelessDebuggingOff
            return
        }

        _status.value = BuiltinShellStatus.Connecting
        AppLogger.info(TAG, "connecting to adbd to launch helper...")

        try {
            val connection = withContext(io) { connectAdb() }
            if (!runState.desiredRunning || cleanupPending) {
                connection.close()
                return
            }

            val apkPath = app.applicationInfo.sourceDir
            val pkg = app.packageName
            val uid = Process.myUid()
            val nonce = UUID.randomUUID().toString()
            if (!prepareHandoff(nonce, generation)) {
                connection.close()
                return
            }

            val cmd = "read n; (echo \"\$n\" | CLASSPATH='$apkPath' setsid app_process /system/bin --nice-name='$pkg:helper' io.github.nanomuse.shell.HelperProcess $pkg $uid - >/dev/null 2>&1 &); read _"

            try {
                withContext(io) {
                    connection.use { conn ->
                        val stream = conn.exec(cmd)
                        stream.write((nonce + "\n").toByteArray(Charsets.UTF_8))
                        withTimeoutOrNull(15_000L) { SharedShell.shell.first { it != null } }
                    }
                }
            } finally {
                SharedShell.cancelStart(nonce)
            }

            if (SharedShell.isReady()) {
                AppLogger.info(TAG, "helper process attached and running!")
                lastError = null
                _status.value = BuiltinShellStatus.Running
                if (runState.desiredRunning && !cleanupPending) recoveryPermissions.applyPending()
            } else {
                AppLogger.warning(TAG, "helper process did not attach in time")
                lastError = "helper handoff timed out"
                _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbKeyUnreadableException) {
            AppLogger.warning(TAG, "stored adb key is unreadable: ${e.message}")
            lastError = "AdbKeyUnreadableException: ${e.message}"
            setPaired(false)
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.KeyUnreadable)
        } catch (e: AdbConnectionException) {
            AppLogger.warning(TAG, "failed to connect adb: ${e.reason} - ${e.message}")
            lastError = "AdbConnectionException(${e.reason}): ${e.message}"
            val failure = when (e.reason) {
                AdbConnectionException.Reason.UNAUTHORIZED -> {
                    setPaired(false)
                    BuiltinShellFailure.AuthorizationRevoked
                }
                AdbConnectionException.Reason.CONNECT_FAILED -> {
                    if (!isWifiConnected()) BuiltinShellFailure.NoWifi else BuiltinShellFailure.ConnectFailed
                }
                else -> BuiltinShellFailure.ConnectFailed
            }
            _status.value = BuiltinShellStatus.Failed(failure)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "unexpected error during launchHelper: ${t.message}")
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.Unknown)
        }
    }

    private fun hasSu(): Boolean = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su", "/debug_ramdisk/su",
    ).any { File(it).canExecute() }

    private suspend fun launchLocalBackend(root: Boolean, generation: Long) {
        val nonce = UUID.randomUUID().toString()
        if (!prepareHandoff(nonce, generation)) return
        _status.value = BuiltinShellStatus.Connecting
        try {
            val command = SharedShell.command(app, nonce)
            val exit = if (root) {
                runInterruptible(io) {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                    try {
                        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                            process.destroy()
                            -1
                        } else process.exitValue()
                    } finally {
                        process.destroy()
                    }
                }
            } else withContext(io) {
                ShizukuManager.runProcess(arrayOf("sh", "-c", command), timeoutMs = 15_000L).exitCode
            }
            if (exit != 0) {
                _status.value = BuiltinShellStatus.Failed(
                    if (root) BuiltinShellFailure.RootDenied else BuiltinShellFailure.BackendUnavailable,
                )
                return
            }
            withTimeoutOrNull(15_000L) { SharedShell.shell.first { it != null } }
            if (SharedShell.isReady()) {
                rootAuthorized = root
                lastError = null
                _status.value = BuiltinShellStatus.Running
            } else {
                _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message
            _status.value = BuiltinShellStatus.Failed(
                if (root) BuiltinShellFailure.RootDenied else BuiltinShellFailure.BackendUnavailable,
            )
        } finally {
            SharedShell.cancelStart(nonce)
        }
    }

    suspend fun connectAdb(): AdbConnection {
        val tls = AndroidAdb.tls(AndroidAdb.identity(app))
        val scan = LocalPortScan(
            NioPortScanner(),
            ::localHosts,
            { LocalPortScan.parseRange(portRangeText()) },
        )
        return AdbConnectPort(
            property = { readPropTlsPort() },
            finder = finder,
            cache = portCache,
            scan = scan,
            scanning = { _scanningPorts.value = it },
        ).connect { host, port ->
            AdbConnection.open(host, port, tls)
        }
    }

    private fun isWifiConnected(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(true)

    private fun readPropTlsPort(): Int? = runCatching {
        val clz = Class.forName("android.os.SystemProperties")
        val get = clz.getMethod("get", String::class.java)
        (get.invoke(null, "service.adb.tls.port") as? String)?.toIntOrNull()?.takeIf { it in 1..65535 }
    }.getOrNull()

    private fun portRangeText(): String? = runCatching {
        File("/proc/sys/net/ipv4/ip_local_port_range").readText()
    }.getOrNull()

    private fun localHosts(): List<String> = buildList {
        add(LOCALHOST)
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { nic ->
                nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filterNot { it.isLoopbackAddress }.map { it.hostAddress }
            }?.let { addAll(it) }
        }
    }

    companion object {
        private const val TAG = "BuiltinShellManager"
        private const val PREFS_NAME = "nanomuse_builtin_shell"
        private const val KEY_PAIRED = "paired"
        private const val KEY_DESIRED = "desired_running"
        private const val KEY_CLEANUP_PENDING = "cleanup_pending"
        private const val LOCALHOST = "127.0.0.1"

        @Volatile
        private var instance: BuiltinShellManager? = null

        fun instance(context: Context): BuiltinShellManager = instance ?: synchronized(this) {
            instance ?: BuiltinShellManager(context.applicationContext).also { instance = it }
        }
    }
}
