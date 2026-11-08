package io.github.nanomuse.shell.setup

import kotlinx.coroutines.flow.StateFlow

/**
 * 内置助手(经本机「无线调试」以 shell 身份拉起的特权进程)此刻的状态。
 * 界面、设置页和任务入口都只看它; 配对、连接、看门狗的实现各自把结果写成这几种之一。
 */
sealed interface BuiltinShellStatus {
    /** 还没配对过(或解除了配对): 要走一遍引导。 */
    data object NotPaired : BuiltinShellStatus

    /** 配对过, 但无线调试关着: 打开后会自动连上。 */
    data object WirelessDebuggingOff : BuiltinShellStatus

    /** 正在配对、连接或启动助手。 */
    data object Connecting : BuiltinShellStatus

    data object Running : BuiltinShellStatus

    /** 出错了: [reason] 说明是哪一类, [fix] 是一键修复走哪条路。 */
    data class Failed(val reason: BuiltinShellFailure, val fix: BuiltinShellFix = reason.defaultFix) : BuiltinShellStatus
}

/** 失败的类别。文案在界面层; 这里只区分「用户该做什么」不同的情况。 */
enum class BuiltinShellFailure(val defaultFix: BuiltinShellFix, val duringPairing: Boolean) {
    /** 配对码不对或已过期。 */
    WrongCode(BuiltinShellFix.RePair, duringPairing = true),
    /** 配对窗口在填码前关掉了(找不到配对端口)。 */
    PairingWindowClosed(BuiltinShellFix.RePair, duringPairing = true),
    /** 没连 Wi-Fi: 无线调试打不开或连不上。 */
    NoWifi(BuiltinShellFix.OpenWifi, duringPairing = false),
    /** 替用户打开无线调试, 系统马上又关掉了: 这个 Wi-Fi 还没被允许无线调试, 要用户亲手打开一次并允许。 */
    WifiNotAllowed(BuiltinShellFix.OpenWirelessDebugging, duringPairing = false),
    /** 连不上本机的无线调试。 */
    ConnectFailed(BuiltinShellFix.Retry, duringPairing = false),
    /** 已配对的授权不认了(太久没用被系统撤销, 或用户在系统里删了)。 */
    AuthorizationRevoked(BuiltinShellFix.RePair, duringPairing = false),
    /** 配对用的钥匙存着却读不出来了: 设备只认那一把, 不能悄悄换, 要重新配对(配对时才换新钥匙)。 */
    KeyUnreadable(BuiltinShellFix.RePair, duringPairing = false),
    /** 连上了, 助手却没能启动或交接。 */
    HelperStartFailed(BuiltinShellFix.Retry, duringPairing = false),
    /** 经 USB 启动命令起的助手停了(多半是手机重启过), 又没配对过无线调试, 自己起不来: 再运行一次启动命令。 */
    UsbHelperStopped(BuiltinShellFix.RunUsbCommand, duringPairing = false),
    UnsupportedAndroid(BuiltinShellFix.Retry, duringPairing = false),
    BackendUnavailable(BuiltinShellFix.Retry, duringPairing = false),
    RootDenied(BuiltinShellFix.Retry, duringPairing = false),
    CleanupPending(BuiltinShellFix.Retry, duringPairing = false),
    Unknown(BuiltinShellFix.Retry, duringPairing = false),
}

enum class BuiltinShellFix {
    /** 用已配对的钥匙再连一次。 */
    Retry,
    /** 重新走配对那一步。 */
    RePair,
    /** 去系统的 Wi-Fi 设置。 */
    OpenWifi,
    /** 去开发者选项里亲手打开无线调试。 */
    OpenWirelessDebugging,
    /** 再运行一次 USB 启动命令(界面给出命令和复制按钮)。 */
    RunUsbCommand,
}

/** 助手停了以后靠什么再起来。 */
enum class HelperRestart {
    None,
    Wireless,
    UsbCommand,
}

/** 找「使用配对码配对设备」窗口公布的配对端口(本机 mDNS)的结果。 */
sealed interface PairingDiscovery {
    data object Idle : PairingDiscovery
    data object Searching : PairingDiscovery
    data class Found(val port: Int) : PairingDiscovery
}

/** 内置助手对界面的控制接口。 */
interface BuiltinShellControl {
    val status: StateFlow<BuiltinShellStatus>
    val pairingDiscovery: StateFlow<PairingDiscovery>

    val paired: Boolean get() = status.value != BuiltinShellStatus.NotPaired

    val restart: HelperRestart get() = when {
        status.value == BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped) -> HelperRestart.UsbCommand
        paired -> HelperRestart.Wireless
        else -> HelperRestart.None
    }

    fun startDiscovery()
    fun stopDiscovery()

    suspend fun startPairing(code: String, port: Int? = null)
    suspend fun retry()
    suspend fun unpair()
}

/** 配对码: 6 位数字规范化。 */
object PairingCode {
    const val LENGTH = 6
    private const val DASHES = "-－‐‑–—"

    fun normalize(input: String): String? {
        val digits = input.filterNot { it.isWhitespace() || it in DASHES }
            .map { c -> if (c in '０'..'９') '0' + (c - '０') else c }
            .joinToString("")
        return digits.takeIf { it.length == LENGTH && it.all { c -> c in '0'..'9' } }
    }

    fun port(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1..65535 }
}
