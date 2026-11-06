package ru.voicescroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class SwipeService : AccessibilityService() {
    companion object {
        var instance: SwipeService? = null
        val TIKTOK = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme")
    }

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        // Шторка, клавиатура и т.п. не означают закрытие TikTok
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("honeyboard")) return
        AppState.setTikTok(pkg in TIKTOK)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        AppState.setTikTok(false)
        return super.onUnbind(intent)
    }

    fun swipe(up: Boolean): Boolean {
        val m = resources.displayMetrics
        val x = m.widthPixels * 0.5f
        val a = m.heightPixels * 0.72f
        val b = m.heightPixels * 0.28f
        val path = Path().apply { if (up) { moveTo(x, a); lineTo(x, b) } else { moveTo(x, b); lineTo(x, a) } }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 220)).build()
        return dispatchGesture(g, null, null)
    }
}
