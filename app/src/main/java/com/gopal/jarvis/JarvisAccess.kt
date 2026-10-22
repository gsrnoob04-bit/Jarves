package com.gopal.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccess : AccessibilityService() {
    companion object {
        @Volatile var inst: JarvisAccess? = null
    }

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Screen ka text snapshot jo Gemini ko bheja jata hai */
    fun dump(): String {
        val root = rootInActiveWindow ?: return "(screen empty / secure)"
        val sb = StringBuilder("app: ${root.packageName}\n")
        var n = 0
        fun walk(x: AccessibilityNodeInfo?) {
            if (x == null || n > 120) return
            val t = (x.text ?: x.contentDescription)?.toString()
            if (!t.isNullOrBlank() || x.isEditable) {
                val tag = if (x.isEditable) "[input] " else if (x.isClickable) "[btn] " else ""
                sb.append(tag).append(t?.take(80) ?: "").append("\n")
                n++
            }
            for (i in 0 until x.childCount) walk(x.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    fun clickText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(t)) {
            var x: AccessibilityNodeInfo? = n
            while (x != null && !x.isClickable) x = x.parent
            if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) findScrollable(n.getChild(i))?.let { return it }
        return null
    }

    fun typeText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun enter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun scroll(dir: String): Boolean {
        val s = findScrollable(rootInActiveWindow) ?: return false
        return s.performAction(
            if (dir == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        )
    }

    fun global(name: String): Boolean = performGlobalAction(
        when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            else -> GLOBAL_ACTION_HOME
        }
    )

    fun tap(x: Float, y: Float) {
        val dm = resources.displayMetrics
        val p = Path()
        p.moveTo(x * dm.widthPixels, y * dm.heightPixels)
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 50)).build(),
            null, null
        )
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val dm = resources.displayMetrics
        val p = Path()
        p.moveTo(x1 * dm.widthPixels, y1 * dm.heightPixels)
        p.lineTo(x2 * dm.widthPixels, y2 * dm.heightPixels)
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(),
            null, null
        )
    }
}
