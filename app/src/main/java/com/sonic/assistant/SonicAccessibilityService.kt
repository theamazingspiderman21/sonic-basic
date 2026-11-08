package com.sonic.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** S.O.N.I.C's hands and eyes: read the UI tree, tap, type, scroll, screenshot. */
class SonicAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun readScreen(maxChars: Int = 6000): String {
        val root = rootInActiveWindow ?: return "Screen not readable (blank or protected)."
        val sb = StringBuilder("App: ${root.packageName}\n")
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || sb.length > maxChars) return
            val text = n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (text != null || n.isClickable || n.isEditable) {
                val flags = buildString {
                    if (n.isClickable) append("[tap]")
                    if (n.isEditable) append("[input]")
                    if (n.isScrollable) append("[scroll]")
                }
                sb.append("  ".repeat(depth.coerceAtMost(6))).append(text ?: "(unlabelled)").append(' ').append(flags).append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().take(maxChars)
    }

    fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (n in nodes) {
            var cur: AccessibilityNodeInfo? = n
            while (cur != null) {
                if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cur = cur.parent
            }
        }
        // fallback: match contentDescription
        return findByDesc(root, text)?.let { clickUp(it) } ?: false
    }

    private fun findByDesc(n: AccessibilityNodeInfo?, d: String): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.contentDescription?.toString()?.contains(d, true) == true) return n
        for (i in 0 until n.childCount) findByDesc(n.getChild(i), d)?.let { return it }
        return null
    }

    private fun clickUp(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) { if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK); cur = cur.parent }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun firstEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) firstEditable(n.getChild(i))?.let { return it }
        return null
    }

    fun scroll(forward: Boolean): Boolean {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        val s = find(rootInActiveWindow) ?: return false
        return s.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun press(what: String): Boolean = when (what.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        else -> false
    }

    /** JPEG base64, max ~1024px wide. */
    suspend fun screenshotBase64(): String? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                if (hw == null) { result.hardwareBuffer.close(); if (cont.isActive) cont.resume(null); return }
                val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                val scale = 1024f / soft.width
                val bmp = if (scale < 1f) Bitmap.createScaledBitmap(soft, 1024, (soft.height * scale).toInt(), true) else soft
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                if (cont.isActive) cont.resume(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
            override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
        })
    }

    companion object { @Volatile var instance: SonicAccessibilityService? = null }
}
