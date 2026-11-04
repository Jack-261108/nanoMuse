package io.github.nanomuse.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 以 shell 身份 (uid 2000) 运行的特权服务，负责管理虚拟屏幕、任务挪移与事件注入。
 */
class ShellService(
    private val allowedUid: Int = Process.myUid(),
    stateDir: File = File("/data/local/tmp"),
) : INanoMuseShell.Stub() {

    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()
    private val kept = HashMap<Int, String>()
    private val muted = AudioMuteLedger(File(stateDir, "nanomuse_audio_mute_$allowedUid"))
    private val myUser = allowedUid / PER_USER_RANGE
    private val clipboard = ShellClipboard(context)
    private val clipLock = Any()
    private var savedClip: ClipData? = null
    private var clipSaved = false
    private var ourText: String? = null

    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    private val launchLock = Any()

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    private val mainWatch by lazy { MainScreenWatch({ taskManager }, ::taskRows) }

    override fun watchMainScreen(windowMs: Int): Int = try {
        mainWatch.watch(windowMs)
    } catch (t: Throwable) {
        Log.e(TAG, "watchMainScreen failed", t)
        -1
    }

    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = try {
        mainWatch.arrivals(sinceUptimeMs)
    } catch (t: Throwable) {
        Log.e(TAG, "mainArrivals failed", t)
        null
    }

    private val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "nanomuse-display-awake").apply { isDaemon = true } }
        DisplayAwake(::pokeDisplay) { r ->
            val f = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { f.cancel(false) }
        }
    }

    @get:SuppressLint("PrivateApi")
    private val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "userActivity failed, display keep-awake off", t)
            false
        }
    }

    @SuppressLint("PrivateApi")
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (!retryWithoutTrust(t)) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            Log.w(TAG, "createDisplay without trusted display flags, retrying", t)
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (t2: Throwable) {
                Log.e(TAG, "createDisplay failed again", t2)
                -1
            }
        }
    }

    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
        val flags = displayFlags(Build.VERSION.SDK_INT, trusted)
        val vd = displayManager.createVirtualDisplay("nanomuse-agent", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setDisplayImePolicy(id)
        if (DisplayAwake.needed(flags, FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) awake.start(id)
        return id
    }

    private fun retryWithoutTrust(t: Throwable): Boolean {
        var c: Throwable? = t
        while (c != null) {
            if (c is SecurityException) return true
            c = c.cause
        }
        return false
    }

    @SuppressLint("PrivateApi")
    private fun setDisplayImePolicy(displayId: Int) {
        runCatching {
            val wmGlobal = Class.forName("android.view.WindowManagerGlobal")
            val wm = wmGlobal.getMethod("getWindowManagerService").invoke(null)!!
            val method = wm.javaClass.methods.firstOrNull { it.name == "setDisplayImePolicy" }
            method?.invoke(wm, displayId, 0) // DISPLAY_IME_POLICY_LOCAL = 0
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed: ${it.message}") }
    }

    override fun releaseDisplay(displayId: Int) {
        awake.stop(displayId)
        synchronized(displays) { displays.remove(displayId) }?.release()
    }

    override fun startApp(displayId: Int, packageName: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        try {
            if (inUseOnMainScreen(packageName, myUser)) return START_IN_USE_ON_MAIN
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }

        if (resumeKept(displayId, packageName, myUser)) return START_OK

        val intent = launchIntent(packageName, myUser) ?: return START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()

        val ok = try {
            val start = taskManager.javaClass.methods.firstOrNull {
                it.name == "startActivityAsUser" && it.parameterTypes.size == 12
            }
            if (start != null) {
                val res = start.invoke(
                    taskManager,
                    null,
                    ShellContext.SHELL_PACKAGE,
                    null,
                    intent,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    options,
                    myUser,
                ) as Int
                res >= 0
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser failed, falling back to am start", t)
            false
        }

        if (ok) {
            setAudioMuted(packageName, true)
            return START_OK
        }

        val component = intent.component?.flattenToShortString() ?: packageName
        val (code, _) = sh("am", "start", "--user", "$myUser", "--display", "$displayId", "-n", component)
        if (code == 0) {
            setAudioMuted(packageName, true)
            START_OK
        } else {
            START_ERROR
        }
    }

    override fun restartApp(displayId: Int, packageName: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        val decision = try {
            dropKept(packageName, myUser)
            val displayField = TASK_INFO.getField("displayId")
            val taskDisplays = allTasks().filter { packageName in packagesOf(it) }.map { displayField.getInt(it) }
            ColdStart.decide(taskDisplays, displayId, inUseOnMainScreen(packageName, myUser))
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect tasks", t)
            return START_ERROR
        }
        if (launchIntent(packageName, myUser) == null) return START_NOT_FOUND
        when (decision) {
            ColdStart.IN_USE -> START_IN_USE_ON_MAIN
            ColdStart.WARM -> startApp(displayId, packageName)
            ColdStart.RESTART -> {
                exec("am", "force-stop", packageName)
                startApp(displayId, packageName)
            }
        }
    }

    private fun launchIntent(packageName: String, userId: Int): Intent? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val resolve = pm.queryIntentActivities(main, 0)
        val info = resolve.firstOrNull()?.activityInfo ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(info.packageName, info.name))
    }

    private fun inUseOnMainScreen(packageName: String, userId: Int): Boolean {
        val top = tasksOn(Display.DEFAULT_DISPLAY).firstOrNull() ?: return false
        val pkg = packageOfTask(TASK_INFO.getField("taskId").getInt(top)) ?: return false
        return pkg == packageName
    }

    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        return tasks.filterNotNull()
    }

    private fun taskRows(): List<MainScreenLog.Task> {
        val id = TASK_INFO.getField("taskId")
        val display = TASK_INFO.getField("displayId")
        val user = runCatching { TASK_INFO.getField("userId") }.getOrNull()
        val type = runCatching { TASK_INFO.getField("topActivityType") }.getOrNull()
        return allTasks().map { t ->
            val top = TASK_INFO.getField("topActivity").get(t) as? ComponentName
            MainScreenLog.Task(
                taskId = id.getInt(t),
                displayId = display.getInt(t),
                packageName = top?.packageName ?: (TASK_INFO.getField("baseActivity").get(t) as? ComponentName)?.packageName,
                userId = user?.getInt(t) ?: -1,
                topActivity = top?.className,
                standard = type?.getInt(t)?.let { it == ACTIVITY_TYPE_UNDEFINED || it == ACTIVITY_TYPE_STANDARD } ?: true,
            )
        }
    }

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "topTaskId failed", t)
        -1
    }

    override fun topActivity(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null else tasksOn(displayId).firstOrNull()?.let {
            (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.className
        }
    } catch (t: Throwable) {
        Log.e(TAG, "topActivity failed", t)
        null
    }

    override fun moveTask(taskId: Int, displayId: Int): Boolean = try {
        taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
            .invoke(taskManager, taskId, displayId)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "moveRootTaskToDisplay unavailable, falling back to am", t)
        exec("am", "display", "move-stack", "$taskId", "$displayId") == 0
    }

    override fun displayOfTask(taskId: Int): Int = try {
        val tasks = (taskManager.javaClass.getMethod("getTasks", Int::class.java)
            .invoke(taskManager, MAX_TASKS) as List<*>).filterNotNull()
        val idField = TASK_INFO.getField("taskId")
        val target = tasks.firstOrNull { idField.getInt(it) == taskId }
        target?.let { TASK_INFO.getField("displayId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        -1
    }

    override fun packageOfTask(taskId: Int): String? = try {
        val tasks = (taskManager.javaClass.getMethod("getTasks", Int::class.java)
            .invoke(taskManager, MAX_TASKS) as List<*>).filterNotNull()
        val idField = TASK_INFO.getField("taskId")
        val target = tasks.firstOrNull { idField.getInt(it) == taskId } ?: return null
        val top = TASK_INFO.getField("topActivity").get(target) as? ComponentName
        top?.packageName ?: (TASK_INFO.getField("baseActivity").get(target) as? ComponentName)?.packageName
    } catch (t: Throwable) {
        null
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0 }
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0 }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || xs.size != n || ys.size != n || times[0] != 0L) return false
        val down = SystemClock.uptimeMillis()
        fun event(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
            return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    override fun injectText(displayId: Int, text: String): Boolean {
        if (!ownDisplay(displayId)) return false
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = map.getEvents(text.toCharArray())
        if (events != null) {
            var allOk = true
            for (event in events) {
                if (!inject(event, displayId)) {
                    allOk = false
                    break
                }
            }
            if (allOk) return true
        }
        val escaped = text.replace(" ", "%s").replace("'", "\\'")
        return exec("input", "-d", "$displayId", "text", escaped) == 0
    }

    override fun setClipText(text: String): Int = synchronized(clipLock) {
        try {
            if (!clipSaved) {
                savedClip = clipboard.primaryClip
                clipSaved = true
            }
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            suppressOverlay(clip)
            markSensitive(clip)
            clipboard.setPrimaryClip(clip)
            ourText = text
            CLIP_SET
        } catch (t: Throwable) {
            Log.w(TAG, "setClipText failed", t)
            CLIP_FAILED
        }
    }

    override fun restoreClip() = synchronized(clipLock) {
        if (!clipSaved) return
        runCatching {
            val prior = savedClip
            if (prior != null) {
                clipboard.setPrimaryClip(suppressOverlay(prior))
            } else {
                clipboard.clearPrimaryClip()
            }
        }.onFailure { Log.w(TAG, "restoreClip failed", it) }
        savedClip = null
        clipSaved = false
        ourText = null
    }

    override fun injectCtrlKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { false }
    }

    private fun suppressOverlay(clip: ClipData): ClipData {
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
        clip.description.extras = extras
        return clip
    }

    private fun markSensitive(clip: ClipData): ClipData {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return clip
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
        return clip
    }

    private inline fun injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean =
        sendPair({ inject(down, displayId) }, { inject(up, displayId) }, fallback)

    private inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean {
        if (!down()) return fallback()
        return up() || up()
    }

    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        val mode = if (event is KeyEvent) INJECT_MODE_WAIT_FOR_RESULT else INJECT_MODE_ASYNC
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, mode) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    private fun inputManager(): Any = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = synchronized(this.muted) {
        val key = AppAudioKey.of(packageName, null)
        if (muted) {
            if (!this.muted.contains(key)) {
                val (code, out) = appops("get", packageName)
                if (code != 0) return false
                this.muted.put(key, AudioMuteLedger.parseSetMode(out) ?: "allow")
            }
            appops("set", packageName, "ignore").first == 0
        } else {
            val ok = appops("set", packageName, AudioMuteLedger.restoreMode(this.muted[key])).first == 0
            if (ok) this.muted.remove(key)
            ok
        }
    }

    override fun getAudioMode(packageName: String): String? = synchronized(this.muted) {
        val (code, out) = appops("get", packageName)
        if (code != 0) null else AudioMuteLedger.parseSetMode(out)
    }

    override fun setAudioMode(packageName: String, mode: String): Boolean = synchronized(this.muted) {
        val ok = appops("set", packageName, mode).first == 0
        if (ok) this.muted.remove(AppAudioKey.of(packageName, null))
        ok
    }

    private fun appops(op: String, packageName: String, mode: String? = null): Pair<Int, String> {
        val cmd = mutableListOf("appops", op, packageName, "PLAY_AUDIO")
        if (mode != null) cmd.add(mode)
        return sh(*cmd.toTypedArray())
    }

    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return null
        val closed = ArrayList<String>()
        val notes = LinkedHashSet<String>()
        val moved = ArrayList<AppsAfterTask.Fate>()
        try {
            val playing = playingUids()
            if (playing == null) notes += AppsAfterTask.NOTE_PLAYBACK_UNKNOWN
            val rows = tasksOn(displayId)
            val idField = TASK_INFO.getField("taskId")
            val byId = rows.associateBy { idField.getInt(it) }
            val fates = AppsAfterTask.decide(
                rows.map { afterTaskRow(it, playing) },
                AppsAfterTask.normalize(keep, myUser),
                AppsAfterTask.normalize(close, myUser),
            )
            val mainTop = topTaskId(Display.DEFAULT_DISPLAY)
            var supported = true
            for (f in fates) {
                if (!f.keep) {
                    closed += AppsAfterTask.closedEntry(f)
                    continue
                }
                if (!supported) {
                    closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveUnsupported))
                    continue
                }
                when (keepBehind(byId.getValue(f.task.id))) {
                    Behind.OK -> moved += f
                    Behind.UNSUPPORTED -> {
                        supported = false
                        notes += AppsAfterTask.NOTE_MOVE_UNSUPPORTED
                        closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveUnsupported))
                    }
                    Behind.FAILED -> closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveFailed))
                }
            }
            // 挪过去之后又出声的(没标过 keep): 挪回这块屏随屏关掉; 挪不回就直接关
            val playingNow = if (moved.isEmpty()) null else playingUids()
            val nowLoud = moved.map { f -> AppsAfterTask.recheck(f, task(f.task.id)?.let { playingIn(it, playingNow) }) }.filter { !it.keep }
            for (f in nowLoud) {
                if (!moveTask(f.task.id, displayId)) removeTask(f.task.id)
                moved.removeAll { it.task.id == f.task.id }
                closed += AppsAfterTask.closedEntry(f)
            }
            val removed = ensureBehind(moved.map { it.task.id }.toSet(), mainTop)
            moved.filter { it.task.id in removed }.forEach {
                closed += AppsAfterTask.closedEntry(it.copy(keep = false, why = AppsAfterTask.Why.MoveFailed))
            }
            moved.removeAll { it.task.id in removed }
        } catch (t: Throwable) {
            Log.e(TAG, "finishDisplay: cannot inspect tasks, closing the rest with the display", t)
            notes += "error ${t.javaClass.simpleName}"
        }
        synchronized(kept) { moved.forEach { kept[it.task.id] = it.key } }
        releaseDisplay(displayId)
        Bundle().apply {
            putStringArray(AppsAfterTask.KEY_KEPT, moved.map { it.key }.toTypedArray())
            putStringArray(AppsAfterTask.KEY_CLOSED, closed.toTypedArray())
            putString(AppsAfterTask.KEY_NOTE, notes.joinToString("; ").ifEmpty { null })
        }
    }

    private enum class Behind { OK, UNSUPPORTED, FAILED }

    private fun afterTaskRow(t: Any, playing: Map<Int, Set<String>>?): AppsAfterTask.Task {
        val user = runCatching { TASK_INFO.getField("userId").getInt(t) }.getOrDefault(myUser)
        val pkgs = packagesOf(t).distinct()
        val type = runCatching { TASK_INFO.getField("topActivityType").getInt(t) }.getOrNull()
        return AppsAfterTask.Task(
            id = TASK_INFO.getField("taskId").getInt(t),
            keys = pkgs.map { AppsAfterTask.keyOf(it, user, myUser) },
            standard = type == null || type == ACTIVITY_TYPE_UNDEFINED || type == ACTIVITY_TYPE_STANDARD,
            own = pkgs.any { it in ownPackages },
            playing = playingIn(t, playing),
            muted = synchronized(muted) { pkgs.any { muted.contains(AppsAfterTask.keyOf(it, user, myUser)) } },
        )
    }

    private val ownPackages: Set<String> by lazy {
        runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toSet() }.getOrNull().orEmpty()
    }

    private fun playingIn(task: Any, playing: Map<Int, Set<String>>?): Boolean? {
        playing ?: return null
        val user = runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull()
        val pkgs = packagesOf(task)
        return playing.any { (uid, owners) -> (user == null || uid / PER_USER_RANGE == user) && pkgs.any { it in owners } }
    }

    private fun playingUids(): Map<Int, Set<String>>? {
        val uids = runCatching {
            val configs = context.getSystemService(AudioManager::class.java).activePlaybackConfigurations
            val uidOf = AudioPlaybackConfiguration::class.java.getMethod("getClientUid")
            val stateOf = AudioPlaybackConfiguration::class.java.getMethod("getPlayerState")
            val rows = configs.map { (uidOf.invoke(it) as Int) to (stateOf.invoke(it) as Int) }
            if (rows.any { it.first < 0 }) null
            else rows.filter { it.second == AppsAfterTask.PLAYER_STATE_STARTED }.map { it.first }.toSet()
        }.onFailure { Log.w(TAG, "playback configurations unavailable", it) }.getOrNull()
            ?: sh("dumpsys", "audio").takeIf { it.first == 0 }?.second?.let(AppsAfterTask::playingUids)
            ?: return null
        return uids.associateWith { uid ->
            runCatching { context.packageManager.getPackagesForUid(uid)?.toSet() }.getOrNull().orEmpty()
        }
    }

    private fun keepBehind(row: Any): Behind {
        val id = TASK_INFO.getField("taskId").getInt(row)
        val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull()
        if (token == null) {
            return if (moveTask(id, Display.DEFAULT_DISPLAY)) Behind.OK else Behind.FAILED
        }
        if (!transact { wct -> wctCall(wct, "setHidden", token, true) }) {
            return if (moveTask(id, Display.DEFAULT_DISPLAY)) Behind.OK else Behind.UNSUPPORTED
        }
        if (!moveTask(id, Display.DEFAULT_DISPLAY)) {
            transact { wct -> wctCall(wct, "setHidden", token, false) }
            return Behind.UNSUPPORTED
        }
        val behind = transact { wct -> wctCall(wct, "reorder", token, false) }
        if (behind && transact { wct -> wctCall(wct, "setHidden", token, false) }) return Behind.OK
        Log.w(TAG, "task $id could not be put behind on the main screen, removing it")
        removeTask(id)
        return Behind.FAILED
    }

    private fun ensureBehind(moved: Set<Int>, before: Int): Set<Int> {
        if (moved.isEmpty()) return emptySet()
        val removed = HashSet<Int>()
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        fun inFront(): List<Any> = if (visibleField != null) {
            tasksOn(Display.DEFAULT_DISPLAY).filter { TASK_INFO.getField("taskId").getInt(it) in moved && visibleField.getBoolean(it) }
        } else listOfNotNull(tasksOn(Display.DEFAULT_DISPLAY).firstOrNull()?.takeIf { TASK_INFO.getField("taskId").getInt(it) in moved })
        repeat(2) { attempt ->
            val front = inFront()
            if (front.isEmpty()) return removed
            for (row in front) {
                val id = TASK_INFO.getField("taskId").getInt(row)
                Log.w(TAG, "moved task $id is in front on the main screen (top was $before), sending it back")
                val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull()
                if (attempt == 1 || token == null || !transact { wct -> wctCall(wct, "reorder", token, false) }) {
                    removeTask(id)
                    removed += id
                }
            }
        }
        return removed
    }

    private fun removeTask(taskId: Int): Boolean = try {
        taskManager.javaClass.getMethod("removeTask", Int::class.java).invoke(taskManager, taskId) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "removeTask unavailable, falling back to am", t)
        exec("am", "stack", "remove", "$taskId") == 0
    }

    @SuppressLint("PrivateApi")
    private fun transact(build: (Any) -> Unit): Boolean = try {
        val wct = WCT.getConstructor().newInstance()
        build(wct)
        val controller = taskManager.javaClass.getMethod("getWindowOrganizerController").invoke(taskManager)!!
        controller.javaClass.methods.first { it.name == "applyTransaction" && it.parameterTypes.size == 1 }.invoke(controller, wct)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "window container transaction failed", t)
        false
    }

    @SuppressLint("PrivateApi")
    private fun wctCall(wct: Any, method: String, token: Any, flag: Boolean) {
        WCT.getMethod(method, Class.forName("android.window.WindowContainerToken"), Boolean::class.javaPrimitiveType).invoke(wct, token, flag)
    }

    private fun packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
        (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
    }

    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        return tasks.filterNotNull()
    }

    private fun task(taskId: Int): Any? {
        val idField = TASK_INFO.getField("taskId")
        return allTasks().firstOrNull { idField.getInt(it) == taskId }
    }

    private fun keptOnMain(packageName: String, userId: Int): List<Any> {
        val ids = synchronized(kept) { kept.keys.toSet() }
        if (ids.isEmpty()) return emptyList()
        val idField = TASK_INFO.getField("taskId")
        val displayField = TASK_INFO.getField("displayId")
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        val all = allTasks()
        synchronized(kept) { kept.keys.retainAll(all.map { idField.getInt(it) }.toSet()) }
        return all.filter { t ->
            idField.getInt(t) in ids && displayField.getInt(t) == Display.DEFAULT_DISPLAY &&
                packageName in packagesOf(t) && visibleField?.getBoolean(t) != true
        }
    }

    private fun resumeKept(displayId: Int, packageName: String, userId: Int): Boolean {
        val row = runCatching { keptOnMain(packageName, userId).firstOrNull() }.getOrNull() ?: return false
        val id = TASK_INFO.getField("taskId").getInt(row)
        if (!moveTask(id, displayId)) return false
        synchronized(kept) { kept.remove(id) }
        return true
    }

    private fun dropKept(packageName: String, userId: Int) {
        keptOnMain(packageName, userId).forEach { t ->
            val id = TASK_INFO.getField("taskId").getInt(t)
            removeTask(id)
            synchronized(kept) { kept.remove(id) }
        }
    }

    override fun recoverMuted(): Boolean = synchronized(muted) {
        muted.restoreAll { pkg, mode ->
            appops("set", pkg, mode).first == 0
        }
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun releaseAll() {
        restoreClip()
        recoverMuted()
        val ids = synchronized(displays) { displays.keys.toList() }
        ids.forEach(::releaseDisplay)
        runCatching { mainWatch.close() }
    }

    override fun destroy() {
        releaseAll()
    }

    private fun exec(vararg cmd: String): Int = sh(*cmd).first

    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val output = AtomicReference("")
        val reader = Thread {
            output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault(""))
        }.apply { isDaemon = true; start() }

        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return -1 to ""
        }
        reader.join(1000)
        return p.exitValue() to output.get().trim()
    }

    companion object {
        private const val TAG = "NanoMuseShell"
        private const val PER_USER_RANGE = 100_000
        private const val MAX_TASKS = 32
        private const val TAP_MS = 60L
        private const val CMD_TIMEOUT_S = 10L
        private const val VIRTUAL_KEYBOARD = -1

        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3

        private const val ACTIVITY_TYPE_UNDEFINED = 0
        private const val ACTIVITY_TYPE_STANDARD = 1
        private val WCT: Class<*> by lazy { Class.forName("android.window.WindowContainerTransaction") }
        const val CLIP_SET = 0
        const val CLIP_FAILED = 2
        private const val CLIP_LABEL = "nanoMuse"
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }
        private const val INJECT_MODE_ASYNC = 0
        private const val INJECT_MODE_WAIT_FOR_RESULT = 1

        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        internal fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                    FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }
    }
}
