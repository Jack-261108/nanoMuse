package io.github.nanomuse.shell.recovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 经已配对的无线调试连接, 以 shell 身份跑一条命令, 返回它的输出。连接断了就抛异常。 */
fun interface ShellCommandRunner {
    suspend fun exec(command: String): String
}

/**
 * 手机特权守护能力的几项拓展:
 */
enum class RecoveryCapability {
    Accessibility,
    BatteryWhitelist,
    RestoreAfterReboot,
    AutoWirelessDebugging;

    val writesSecureSettings: Boolean get() = this == RestoreAfterReboot || this == AutoWirelessDebugging
}

data class RecoveryConsent(
    val applied: Set<RecoveryCapability> = emptySet(),
    val declined: Set<RecoveryCapability> = emptySet(),
) {
    fun wants(c: RecoveryCapability): Boolean = c !in declined
    fun isApplied(c: RecoveryCapability): Boolean = c in applied
    val pending: List<RecoveryCapability> get() = RecoveryCapability.entries.filter { wants(it) && !isApplied(it) }

    fun chose(c: RecoveryCapability, on: Boolean): RecoveryConsent = copy(declined = if (on) declined - c else declined + c)
    fun withApplied(c: RecoveryCapability, on: Boolean): RecoveryConsent = copy(applied = if (on) applied + c else applied - c)
}

interface RecoveryConsentStore {
    val consent: StateFlow<RecoveryConsent>
    fun save(consent: RecoveryConsent)
}

sealed interface PermissionResult {
    data object Done : PermissionResult
    data object NoRunner : PermissionResult
    data class Failed(val detail: String) : PermissionResult
}

object PermissionCommands {
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    const val READ_ACCESSIBILITY = "settings get secure enabled_accessibility_services"
    const val EXIT_MARK = "__nanomuse_exit="

    fun accessibilityService(pkg: String): String {
        return "$pkg/com.openminis.app.accessibility.MinisAccessibilityService"
    }

    fun grantWriteSecureSettings(pkg: String) = "pm grant $pkg $WRITE_SECURE_SETTINGS"
    fun revokeWriteSecureSettings(pkg: String) = "pm revoke $pkg $WRITE_SECURE_SETTINGS"
    fun whitelistAdd(pkg: String) = "cmd deviceidle whitelist +$pkg"
    fun whitelistRemove(pkg: String) = "cmd deviceidle whitelist -$pkg"

    fun writeAccessibility(list: String): List<String> =
        if (list.isEmpty()) listOf("settings delete secure enabled_accessibility_services", "settings put secure accessibility_enabled 0")
        else listOf("settings put secure enabled_accessibility_services ${quote(list)}", "settings put secure accessibility_enabled 1")

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun checked(command: String): String = "$command 2>&1; echo $EXIT_MARK\$?"

    fun exitCode(output: String): Int? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(EXIT_MARK) }?.removePrefix(EXIT_MARK)?.toIntOrNull()
}

object AccessibilityList {
    fun entries(raw: String?): List<String> {
        val v = raw?.trim().orEmpty()
        if (v == "null") return emptyList()
        return v.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun contains(raw: String?, component: String): Boolean = entries(raw).any { same(it, component) }

    fun with(raw: String?, component: String): String {
        val list = entries(raw)
        return (if (list.any { same(it, component) }) list else list + component).joinToString(":")
    }

    fun without(raw: String?, component: String): String = entries(raw).filterNot { same(it, component) }.joinToString(":")

    private fun same(a: String, b: String) = expand(a) == expand(b)

    private fun expand(c: String): String {
        if ('/' !in c) return c
        val pkg = c.substringBefore('/')
        val cls = c.substringAfter('/')
        return if (cls.startsWith('.')) "$pkg/$pkg$cls" else c
    }
}

/**
 * 自动下发与恢复系统特权 (WRITE_SECURE_SETTINGS, Battery Whitelist, Accessibility)。
 */
class RecoveryPermissions(
    private val runner: () -> ShellCommandRunner?,
    private val store: RecoveryConsentStore,
    private val packageName: String,
    private val allowApply: () -> Boolean = { true },
) {
    private val lock = Mutex()
    private val _autoFailed = MutableStateFlow<Set<RecoveryCapability>>(emptySet())
    val autoFailed: StateFlow<Set<RecoveryCapability>> = _autoFailed.asStateFlow()

    suspend fun set(capability: RecoveryCapability, on: Boolean): PermissionResult = lock.withLock {
        if (on && !allowApply()) return@withLock PermissionResult.NoRunner
        val now = store.consent.value
        // Persist the user's choice even if the connection needed for revocation is gone.
        store.save(now.chose(capability, on))
        val shell = runner()
        if (shell == null) {
            return@withLock when {
                on -> PermissionResult.NoRunner.also { store.save(now.chose(capability, true)) }
                !now.isApplied(capability) -> PermissionResult.Done.also { store.save(now.chose(capability, false)) }
                else -> PermissionResult.NoRunner
            }
        }
        val result = run(shell, listOf(capability), on)
        if (result == PermissionResult.Done) {
            store.save(store.consent.value.chose(capability, on).withApplied(capability, on))
            _autoFailed.update { it - capability }
        }
        result
    }

    suspend fun applyPending(): PermissionResult = lock.withLock {
        if (!allowApply()) return@withLock PermissionResult.NoRunner
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        var failure: PermissionResult? = null
        for (c in store.consent.value.pending) {
            if (!allowApply()) return@withLock PermissionResult.NoRunner
            val r = run(shell, listOf(c), on = true)
            if (r == PermissionResult.Done) store.save(store.consent.value.withApplied(c, true))
            else if (failure == null) failure = r
            _autoFailed.update { if (r == PermissionResult.Done) it - c else it + c }
        }
        failure ?: PermissionResult.Done
    }

    suspend fun revokeAll(): PermissionResult = lock.withLock {
        val now = store.consent.value
        // The manager closes allowApply before cleanup. Preserve the person's choices for
        // a later explicit pairing, and retain every applied bit until revocation succeeds.
        if (now.applied.isEmpty()) return@withLock PermissionResult.Done
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        var failure: PermissionResult? = null
        for (capability in now.applied.filterNot { it.writesSecureSettings }) {
            val result = run(shell, listOf(capability), on = false)
            if (result == PermissionResult.Done) {
                store.save(store.consent.value.withApplied(capability, false))
                _autoFailed.update { it - capability }
            } else if (failure == null) failure = result
        }
        val secure = now.applied.filter { it.writesSecureSettings }.toSet()
        if (secure.isNotEmpty()) {
            // Both switches share one OS permission. Revoke it once, without the per-switch
            // "another feature still uses it" shortcut, then clear both records together.
            val result = try {
                runAll(shell, listOf(PermissionCommands.revokeWriteSecureSettings(packageName)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (result == PermissionResult.Done) {
                store.save(store.consent.value.copy(applied = store.consent.value.applied - secure))
                _autoFailed.update { it - secure }
            } else if (failure == null) failure = result
        }
        failure ?: PermissionResult.Done
    }

    suspend fun restartAccessibility(gapMs: Long = 400L): PermissionResult = lock.withLock {
        if (!allowApply()) return@withLock PermissionResult.NoRunner
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        val me = PermissionCommands.accessibilityService(packageName)
        suspend fun enable(raw: String?): PermissionResult {
            if (!allowApply()) return PermissionResult.NoRunner
            // A command may complete after a transport cancellation. Keep a cleanup record
            // before dispatching any write that could enable our service.
            store.save(store.consent.value.withApplied(RecoveryCapability.Accessibility, true))
            return runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(raw, me)), allowApply)
        }
        try {
            val current = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
            if (!allowApply()) return@withLock PermissionResult.NoRunner
            if (!AccessibilityList.contains(current, me)) return@withLock enable(current)
            val removed = runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.without(current, me)))
            if (removed != PermissionResult.Done) return@withLock removed
            withContext(NonCancellable) {
                delay(gapMs)
                if (!allowApply()) return@withContext PermissionResult.NoRunner
                val now = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
                val back = enable(now)
                if (back != PermissionResult.Done && allowApply()) {
                    runCatching { enable(current) }
                }
                back
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun run(shell: ShellCommandRunner, capabilities: List<RecoveryCapability>, on: Boolean): PermissionResult {
        var failure: PermissionResult? = null
        for (c in capabilities) {
            val r = try {
                runAll(shell, commands(shell, c, on), if (on) allowApply else { { true } })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (r != PermissionResult.Done && failure == null) failure = r
        }
        return failure ?: PermissionResult.Done
    }

    private suspend fun commands(shell: ShellCommandRunner, c: RecoveryCapability, on: Boolean): List<String> = when (c) {
        RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging -> when {
            on -> listOf(PermissionCommands.grantWriteSecureSettings(packageName))
            RecoveryCapability.entries.any { it != c && it.writesSecureSettings && store.consent.value.isApplied(it) } -> emptyList()
            else -> listOf(PermissionCommands.revokeWriteSecureSettings(packageName))
        }
        RecoveryCapability.BatteryWhitelist ->
            listOf(if (on) PermissionCommands.whitelistAdd(packageName) else PermissionCommands.whitelistRemove(packageName))
        RecoveryCapability.Accessibility -> {
            val current = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
            val me = PermissionCommands.accessibilityService(packageName)
            PermissionCommands.writeAccessibility(if (on) AccessibilityList.with(current, me) else AccessibilityList.without(current, me))
        }
    }

    private suspend fun runAll(
        shell: ShellCommandRunner,
        commands: List<String>,
        allowed: () -> Boolean = { true },
    ): PermissionResult {
        for (cmd in commands) {
            if (!allowed()) return PermissionResult.NoRunner
            val out = shell.exec(PermissionCommands.checked(cmd))
            if (PermissionCommands.exitCode(out) != 0) return PermissionResult.Failed("$cmd: ${out.trim()}")
        }
        return PermissionResult.Done
    }
}
