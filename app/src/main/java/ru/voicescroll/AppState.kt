package ru.voicescroll

import android.content.Context

/** Общее состояние. Всё вызывается из главного потока. */
object AppState {
    enum class Mode { OFF, LOADING, WAITING, LISTENING, PAUSED, STOPPED, ERROR }
    enum class Target { NONE, TIKTOK, YOUTUBE }

    val listeners = LinkedHashSet<() -> Unit>()
    var mode = Mode.OFF; private set
    var target = Target.NONE; private set
    var testMode = false; private set
    var paused = false; private set
    var lastCommand = "—"
    var message = ""
    var liveText = "Ожидание микрофона…"
    var inputLevel = -90

    val appLabel: String get() = when (target) {
        Target.TIKTOK -> "TikTok"
        Target.YOUTUBE -> "YouTube"
        Target.NONE -> "не поддерживается"
    }

    fun setMode(m: Mode) { if (m != mode) { mode = m; fire() } }
    fun setTarget(v: Target) { if (v != target) { target = v; fire() } }
    fun setTestMode(v: Boolean) { if (v != testMode) { testMode = v; fire() } }
    fun setPaused(v: Boolean) { if (v != paused) { paused = v; fire() } }
    fun fire() { listeners.toList().forEach { it() } }

    fun sensitivity(ctx: Context) =
        ctx.getSharedPreferences("p", Context.MODE_PRIVATE).getInt("sens", 50)
    fun saveSensitivity(ctx: Context, v: Int) =
        ctx.getSharedPreferences("p", Context.MODE_PRIVATE).edit().putInt("sens", v).apply()
    /** На максимуме принимаем менее уверенные варианты; команда дополнительно проверяется по словам. */
    fun threshold(ctx: Context) = 0.85 - 0.73 * (sensitivity(ctx) / 100.0)

    fun swipeDuration(ctx: Context) =
        ctx.getSharedPreferences("p", Context.MODE_PRIVATE).getInt("swipe_ms", 220)
    fun saveSwipeDuration(ctx: Context, v: Int) =
        ctx.getSharedPreferences("p", Context.MODE_PRIVATE).edit().putInt("swipe_ms", v.coerceIn(100, 600)).apply()
}
