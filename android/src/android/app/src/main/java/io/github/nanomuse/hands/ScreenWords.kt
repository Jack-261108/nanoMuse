package io.github.nanomuse.hands

import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openminis.app.accessibility.MinisAccessibilityService

/**
 * What the accessibility tree says is under a point: the text and description of the
 * deepest node whose bounds contain it, with its ancestors' labels up to the nearest
 * clickable one (a pay button is often a container with the label in a child). The
 * approval for a tap reads this next to the label the screen model reported, so a model
 * that misnames a button cannot talk its way past the card.
 */
object ScreenWords {
    private const val MAX_UP = 3

    fun windowsOn(svc: MinisAccessibilityService, displayId: Int): List<AccessibilityWindowInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching { svc.windowsOnAllDisplays?.get(displayId) }.getOrNull().orEmpty()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching { svc.windows?.filter { it.displayId == displayId } }.getOrNull().orEmpty()
        }
        return if (displayId == Display.DEFAULT_DISPLAY) runCatching { svc.windows }.getOrNull().orEmpty() else emptyList()
    }

    fun rootsOn(svc: MinisAccessibilityService, displayId: Int): List<AccessibilityNodeInfo> {
        val windows = windowsOn(svc, displayId)
        val out = ArrayList<AccessibilityNodeInfo>()
        for (w in windows) {
            w.root?.let { out.add(it) }
        }
        if (out.isEmpty() && displayId == Display.DEFAULT_DISPLAY) {
            runCatching { svc.rootInActiveWindow?.let { out.add(it) } }
        }
        return out
    }

    fun at(svc: MinisAccessibilityService, displayId: Int, x: Int, y: Int): String? {
        val words = LinkedHashSet<String>()
        try {
            for (root in rootsOn(svc, displayId)) {
                val leaf = deepestAt(root, x, y, 0) ?: continue
                var node: AccessibilityNodeInfo? = leaf
                var up = 0
                while (node != null && up <= MAX_UP) {
                    label(node)?.let { words.add(it) }
                    if (node.isClickable && up > 0) break
                    node = node.parent
                    up++
                }
                if (words.isNotEmpty()) break
            }
        } catch (_: Throwable) {
            // a tree that changed under us is no reason to fail the tap; the model's label stands
        }
        return words.joinToString(" ").trim().take(200).ifBlank { null }
    }

    fun at(svc: MinisAccessibilityService, x: Int, y: Int): String? = at(svc, Display.DEFAULT_DISPLAY, x, y)

    private fun label(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        return listOf(text, desc).filter { it.isNotEmpty() }.joinToString(" ").ifBlank { null }
    }

    private fun deepestAt(node: AccessibilityNodeInfo, x: Int, y: Int, depth: Int): AccessibilityNodeInfo? {
        if (depth > 60) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            deepestAt(child, x, y, depth + 1)?.let { return it }
        }
        return node
    }
}
