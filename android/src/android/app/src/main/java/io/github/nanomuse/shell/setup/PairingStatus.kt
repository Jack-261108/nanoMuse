package io.github.nanomuse.shell.setup

/** 配对通知看到的状态: 「重新配对」时, 配对开始之前助手在不在运行都当作还没配对。 */
object PairingStatus {
    fun effective(status: BuiltinShellStatus, repair: Boolean): BuiltinShellStatus =
        if (repair && status !is BuiltinShellStatus.Connecting) BuiltinShellStatus.NotPaired else status
}
