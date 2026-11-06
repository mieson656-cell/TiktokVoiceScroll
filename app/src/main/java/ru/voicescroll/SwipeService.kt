package ru.voicescroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

class SwipeService : AccessibilityService() {
    companion object {
        var instance: SwipeService? = null
        private val TIKTOK = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme")
        private const val YOUTUBE = "com.google.android.youtube"
    }

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("honeyboard")) return
        when {
            pkg in TIKTOK -> AppState.setTarget(AppState.Target.TIKTOK)
            pkg == YOUTUBE -> AppState.setTarget(AppState.Target.YOUTUBE)
            e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> AppState.setTarget(AppState.Target.NONE)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        AppState.setTarget(AppState.Target.NONE)
        return super.onUnbind(intent)
    }

    fun swipe(up: Boolean): Boolean {
        val m = resources.displayMetrics
        val x = m.widthPixels * 0.5f
        val a = m.heightPixels * 0.72f
        val b = m.heightPixels * 0.28f
        val path = Path().apply { if (up) { moveTo(x,a); lineTo(x,b) } else { moveTo(x,b); lineTo(x,a) } }
        val duration = AppState.swipeDuration(this).toLong()
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,duration)).build()
        return dispatchGesture(g,null,null)
    }

    private fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 70)).build()
        return dispatchGesture(g, null, null)
    }

    fun togglePlayback(): Boolean {
        val m = resources.displayMetrics
        // Центральное касание переключает воспроизведение в плеерах TikTok и YouTube.
        return tap(m.widthPixels * 0.5f, m.heightPixels * 0.48f)
    }

    private fun allNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return out
    }

    private fun clickText(vararg terms: String): Boolean {
        val nodes = allNodes(rootInActiveWindow)
        val target = nodes.firstOrNull { n ->
            val s = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" ").lowercase()
            val unsafeLikeToggle = terms.any { it == "нравится" || it == "мне нравится" || it == "like" } &&
                listOf("убрать", "не нравится", "remove", "dislike").any { s.contains(it) }
            n.isVisibleToUser && n.isEnabled && !unsafeLikeToggle && terms.any { s.contains(it.lowercase()) }
        } ?: return false
        var n: AccessibilityNodeInfo? = target
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        return false
    }

    private fun setTextOnField(value: String, terms: List<String>): Boolean {
        val nodes = allNodes(rootInActiveWindow)
        val field = nodes.firstOrNull { n ->
            n.isVisibleToUser && (n.isEditable || n.className?.toString()?.contains("EditText", true) == true) &&
                (terms.isEmpty() || listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString())
                    .joinToString(" ").lowercase().let { s -> terms.any { s.contains(it) } })
        } ?: nodes.firstOrNull { it.isVisibleToUser && it.isEditable } ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })
    }

    fun search(query: String): Boolean {
        if (query.isBlank()) return false
        // Открываем экран поиска, затем ждём появления поля и клавиатуры.
        if (!clickText("поиск", "search", "найти")) return false
        android.os.Handler(mainLooper).postDelayed({
            setTextOnField(query, listOf("поиск", "search")) || setTextOnField(query, emptyList())
        }, 350)
        return true
    }

    fun like(): Boolean = clickText("нравится", "мне нравится", "like")

    fun follow(): Boolean {
        // Нажимаем только кнопку подписки, не трогая уже активные «Вы подписаны».
        return clickText("подписаться", "подписаться на", "subscribe")
    }

    fun comment(text: String): Boolean {
        if (text.isBlank()) return false
        if (!clickText("добавить комментарий", "комментарий", "комментировать", "add a comment")) return false
        android.os.Handler(mainLooper).postDelayed({
            setTextOnField(text, listOf("комментар", "comment")) || setTextOnField(text, emptyList())
        }, 350)
        return true
    }
}