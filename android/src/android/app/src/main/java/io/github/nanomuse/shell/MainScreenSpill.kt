package io.github.nanomuse.shell

/**
 * 判断 Agent 的一个动作是否导致应用启动/溢出到了用户主屏上。
 * 应用不是从自己在虚拟屏上的页面、而是从后台 (服务、通知跳转、系统选择器等) 打开另一个应用时，
 * 系统默认将其放在主屏 (Display.DEFAULT_DISPLAY)；
 * 此时虚拟屏上无变化，模型可能会误判为未点中而重复操作。检测到后将其拉回虚拟屏。
 *
 * 判据逻辑：
 * 仅观察动作开始后指定时间窗口内主屏最上面换上来的任务 (来自 MainScreenLog)。
 * 排除：
 * - 超出时间窗口的；
 * - 桌面、最近任务、系统自带界面、nanoMuse 本身以及 excluded 集合中的应用；
 * - 从其它屏幕挪过来的 (接管等)；
 * - 检查时已不再主屏上的；
 * - 窗口期内用户在主屏有点击操作的 (视为用户自己触发)；
 * - 动作前就在主屏顶层的任务再次回到顶层。
 *
 * 分类：
 * - PullBack: 动作触发的后台/意图启动到主屏，且用户未干预 -> 拉回虚拟屏。
 * - InUse: 用户正在主屏使用的该应用内换了页面，或用户在出现后点击了该应用窗口 -> 不拉回，通知 Agent。
 */
object MainScreenSpill {
    const val MAIN = 0
    const val NEW = -1

    /** 主屏冒到最上面的一个任务；[from] 上一次看到它时所在的屏幕 (之前没见过为 [NEW])，[now] 此刻所在的屏幕 (不在了为 -1)。 */
    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val now: Int,
        val topActivity: String? = null,
    )

    /** 用户在主屏上点了一下：[at] 时刻，[packageName] 为点击所在的包名 (未知为 null)。 */
    data class Tap(val at: Long, val packageName: String?)

    sealed interface Verdict { val arrival: Arrival }
    /** 动作触发溢出到主屏：拉回虚拟屏。 */
    data class PullBack(override val arrival: Arrival) : Verdict
    /** 发生在用户正在主屏使用的应用中或用户正在操作：不拉回，通知模型。 */
    data class InUse(override val arrival: Arrival) : Verdict

    /**
     * [since] 动作开始时刻，[windowMs] 为有效时间窗口；
     * [topBefore] 动作开始时主屏顶层任务；
     * [excluded] 不视为溢出的包名集合 (桌面、自身等)；
     * [userTaps] 用户在主屏的点击记录。
     * 同一个任务只输出一条最新裁决，按时间升序返回。
     */
    fun decide(
        arrivals: List<Arrival>,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap> = emptyList(),
    ): List<Verdict> {
        val out = LinkedHashMap<Int, Verdict>()
        for (a in arrivals.sortedBy { it.at }) {
            val v = verdict(a, since, windowMs, topBefore, excluded, userTaps) ?: continue
            out.remove(a.taskId)
            out[a.taskId] = v
        }
        return out.values.toList()
    }

    enum class Handling {
        /** 拉回虚拟屏 (仅移动任务，保持原始发起方不变)。 */
        Move,
        /** 系统选择器正在询问用户打开哪一个：不移动，留给用户在主屏选择。 */
        LeaveForUser,
    }

    /**
     * 判断开到主屏上的应用如何处理：若是系统选择器或分身选项匹配，留给用户在主屏处理；否则拉回。
     */
    fun handling(systemPackage: Boolean, systemPicker: Boolean, optionsMatch: Boolean): Handling =
        if (systemPackage && (systemPicker || optionsMatch)) Handling.LeaveForUser else Handling.Move

    /**
     * 用户在主屏选择框选完后冒出的应用。
     */
    fun picked(arrivals: List<Arrival>, chooserTask: Int, gone: Long, slackMs: Long, waitMs: Long, topBefore: Int, excluded: Set<String>): Arrival? =
        decide(arrivals.filter { it.taskId != chooserTask }, gone - slackMs, waitMs + slackMs, topBefore, excluded, emptyList())
            .filterIsInstance<PullBack>().lastOrNull()?.arrival

    /**
     * 选出的应用是否需要用户亲手处理 (密码框或防截屏窗口)。
     */
    fun needsUser(passwordField: Boolean, readable: Boolean, secureWindow: Boolean): Boolean = passwordField || (!readable && secureWindow)

    /**
     * 判断是否为无桌面图标的系统认证器服务 (例如账号绑定、登录页面)。
     */
    fun authenticatorUi(pkg: String, authenticators: Set<String>, hasLauncher: Boolean): Boolean =
        pkg in authenticators && !hasLauncher

    private fun verdict(a: Arrival, since: Long, windowMs: Long, topBefore: Int, excluded: Set<String>, userTaps: List<Tap>): Verdict? {
        if (a.at < since || a.at > since + windowMs) return null
        val pkg = a.packageName ?: return null
        if (!a.standard || pkg in excluded) return null
        if (a.from != NEW && a.from != MAIN) return null
        if (a.now != MAIN) return null
        if (userTaps.any { it.at in since..a.at }) return null
        // 用户已经在点它了 (出现之后点在这一个 App 的窗口上)：留给用户，不挪
        if (userTaps.any { it.at >= a.at && it.packageName == pkg }) return InUse(a)
        if (a.taskId == topBefore) return if (a.sameTask) InUse(a) else null
        return PullBack(a)
    }
}
