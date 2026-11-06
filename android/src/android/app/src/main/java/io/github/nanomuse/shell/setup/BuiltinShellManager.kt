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
import io.github.nanomuse.shell.SharedShell
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
    private var started = false

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
                if (paired && DeveloperSettings.wirelessDebugging(app.contentResolver)) {
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
        )
    }

    private fun canWriteSecure() =
        app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private suspend fun restartAccessibilityDirectly(gapMs: Long): PermissionResult = withContext(io) {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun read() = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        fun write(list: String) {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.ifEmpty { null })
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
        }
        try {
            val current = read()
            if (AccessibilityList.contains(current, me)) {
                write(AccessibilityList.without(current, me))
                withContext(NonCancellable) {
                    delay(gapMs)
                    try {
                        write(AccessibilityList.with(read(), me))
                    } catch (e: Exception) {
                        runCatching { write(current.orEmpty()) }
                        throw e
                    }
                }
            } else {
                write(AccessibilityList.with(current, me))
            }
            PermissionResult.Done
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
            wants = { consentStore.consent.value.wants(RecoveryCapability.AutoWirelessDebugging) },
            canWrite = { app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED },
            read = { DeveloperSettings.wirelessDebugging(app.contentResolver) },
            write = { DeveloperSettings.turnOnWirelessDebugging(app.contentResolver) },
        )
    }

    init {
        scope.launch {
            SharedShell.shell.collect { shell ->
                if (shell != null) {
                    _status.value = BuiltinShellStatus.Running
                    wirelessAutoOn.reset()
                    runCatching { recoveryPermissions.applyPending() }
                } else if (_status.value is BuiltinShellStatus.Failed) {
                    // 保留失败状态与一键修复路径，不覆盖为 NotPaired
                } else if (paired) {
                    val wd = DeveloperSettings.wirelessDebugging(app.contentResolver)
                    _status.value = if (!wd) BuiltinShellStatus.WirelessDebuggingOff else BuiltinShellStatus.Connecting
                } else {
                    _status.value = BuiltinShellStatus.NotPaired
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
                        need()
                    } else if (!on && paired && !SharedShell.isReady()) {
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
                    if (paired && !SharedShell.isReady()) {
                        need()
                    }
                }
            })
        }

        scope.launch {
            accessibilityRepair.watch(accessibility, status, userUnlocked)
        }
    }

    override val paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)

    private fun setPaired(value: Boolean) {
        prefs.edit().putBoolean(KEY_PAIRED, value).apply()
    }

    private fun initialStatus(): BuiltinShellStatus = when {
        SharedShell.isReady() -> BuiltinShellStatus.Running
        !paired -> BuiltinShellStatus.NotPaired
        !DeveloperSettings.wirelessDebugging(app.contentResolver) -> BuiltinShellStatus.WirelessDebuggingOff
        else -> BuiltinShellStatus.Connecting
    }

    fun need() {
        if (SharedShell.isReady()) return
        start()
        scope.launch {
            lock.withLock {
                launchHelperInternal()
            }
        }
    }

    override fun startDiscovery() {
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
        lock.withLock {
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
                launchHelperInternal()
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
        lock.withLock {
            launchHelperInternal()
        }
    }

    override suspend fun unpair() {
        lock.withLock {
            setPaired(false)
            lastError = null
            AndroidAdb.forget(app)
            runCatching { recoveryPermissions.revokeAll() }
            SharedShell.release()
            _status.value = BuiltinShellStatus.NotPaired
        }
    }

    private suspend fun launchHelperInternal() {
        if (SharedShell.isReady()) {
            _status.value = BuiltinShellStatus.Running
            return
        }

        if (!paired) {
            _status.value = BuiltinShellStatus.NotPaired
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

            val apkPath = app.applicationInfo.sourceDir
            val pkg = app.packageName
            val uid = Process.myUid()
            val nonce = UUID.randomUUID().toString()

            val cmd = "read n; (echo \"\$n\" | CLASSPATH='$apkPath' setsid app_process /system/bin --nice-name='$pkg:helper' io.github.nanomuse.shell.HelperProcess $pkg $uid - >/dev/null 2>&1 &); read _"

            withContext(io) {
                connection.use { conn ->
                    val stream = conn.exec(cmd)
                    stream.write((nonce + "\n").toByteArray(Charsets.UTF_8))
                    withTimeoutOrNull(15_000L) {
                        SharedShell.shell.first { it != null }
                    }
                }
            }

            if (SharedShell.isReady()) {
                AppLogger.info(TAG, "helper process attached and running!")
                lastError = null
                _status.value = BuiltinShellStatus.Running
                recoveryPermissions.applyPending()
            } else {
                AppLogger.warning(TAG, "helper process did not attach in time")
                lastError = "helper handoff timed out"
                _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)
            }
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
        private const val LOCALHOST = "127.0.0.1"

        @Volatile
        private var instance: BuiltinShellManager? = null

        fun instance(context: Context): BuiltinShellManager = instance ?: synchronized(this) {
            instance ?: BuiltinShellManager(context.applicationContext).also { instance = it }
        }
    }
}
