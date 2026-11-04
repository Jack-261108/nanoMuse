package io.github.nanomuse.shell

/**
 * 主屏 (0 号屏) 上「冒到最上面」的任务记录。内置助手每次读取任务列表时同步更新 ([scan])。
 * 只记事实，不判断是否由 Agent 动作引起：
 * 主屏最上面换了一个任务记一条；还是那个任务、但其最顶层页面换了也记一条 ([Arrival.sameTask])。
 * 每条记录带上该任务上一次被看到时所在的屏幕 ([Arrival.from]，之前没见过为 [NEW])。
 * 纯逻辑，时钟由调用方提供。
 */
class MainScreenLog(private val keepMs: Long = KEEP_MS) {
    /** 观察到的一个任务：[topActivity] 为顶层 Activity 完整类名，[standard] 表示普通应用页面 (非桌面、最近任务、助理等)。 */
    data class Task(
        val taskId: Int,
        val displayId: Int,
        val packageName: String?,
        val userId: Int,
        val topActivity: String?,
        val standard: Boolean = true,
    )

    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val topActivity: String? = null,
    )

    private var top: Task? = null
    private var displays: Map<Int, Int> = emptyMap()
    private val arrivals = ArrayDeque<Arrival>()

    /**
     * 传入一次任务列表扫描结果 ([tasks]: 所有屏幕上的任务，按最近使用排序)。
     * [record] 为 false 时仅记录此刻状态作为基准，不生成冒出记录。
     */
    fun scan(tasks: List<Task>, now: Long, record: Boolean) {
        val mainTop = tasks.firstOrNull { it.displayId == MAIN }
        val last = top
        if (record && mainTop != null && last != null) {
            when {
                mainTop.taskId != last.taskId -> add(mainTop, now, displays[mainTop.taskId] ?: NEW, sameTask = false)
                mainTop.topActivity != last.topActivity -> add(mainTop, now, MAIN, sameTask = true)
            }
        }
        top = mainTop
        displays = tasks.associate { it.taskId to it.displayId }
        while (arrivals.isNotEmpty() && now - arrivals.first().at > keepMs) arrivals.removeFirst()
    }

    private fun add(t: Task, now: Long, from: Int, sameTask: Boolean) {
        arrivals.addLast(Arrival(t.taskId, t.packageName, t.userId, now, from, sameTask, t.standard, t.topActivity))
    }

    /** 主屏此刻最上面的任务；未扫描或主屏无任务时为 -1。 */
    val topTaskId: Int get() = top?.taskId ?: -1

    /** [since] (含) 之后的记录，按时间升序。 */
    fun since(since: Long): List<Arrival> = arrivals.filter { it.at >= since }

    /** 该任务最近一次被观察到的屏幕；不存在则为 -1。 */
    fun displayOf(taskId: Int): Int = displays[taskId] ?: -1

    companion object {
        const val MAIN = 0
        const val NEW = -1
        const val KEEP_MS = 60_000L
    }
}
