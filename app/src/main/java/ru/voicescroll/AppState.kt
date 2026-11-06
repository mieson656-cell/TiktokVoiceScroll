package ru.voicescroll

import android.content.Context

/** Общее состояние. Всё вызывается из главного потока. */
object AppState {
    enum class Mode { OFF, LOADING, WAITING, LISTENING, PAUSED, STOPPED, ERROR }

    val listeners = LinkedHashSet<() -> Unit>()
    var mode = Mode.OFF; private set
    var tiktok = false; private set
    var testMode = false; private set
    var paused = false; private set
    var lastCommand = "—"
    var message = ""

    fun setMode(m: Mode) { if (m != mode) { mode = m; fire() } }
    fun setTikTok(v: Boolean) { if (v != tiktok) { tiktok = v; fire() } }
    fun setTestMode(v: Boolean) { if (v != testMode) { testMode = v; fire() } }
    fun setPaused(v: Boolean) { if (v != paused) { paused = v; fire() } }
    fun fire() { listeners.toList().forEach { it() } }

    fun sensitivity(ctx: Context) =
        ctx.getSharedPreferences("p", Context.MODE_PRIVATE).getInt("sens", 50)
    fun saveSensitivity(ctx: Context, v: Int) =
        ctx.getSharedPreferences("p", Context.MODE_PRIVATE).edit().putInt("sens", v).apply()
    /** Минимальная уверенность распознавания: чувствительность 0 -> 0.85, 100 -> 0.25 */
    fun threshold(ctx: Context) = 0.85 - 0.6 * (sensitivity(ctx) / 100.0)

    fun swipeDuration(ctx: Context) =
        ctx.getSharedPreferences("p", Context.MODE_PRIVATE).getInt("swipe_ms", 220)
    fun saveSwipeDuration(ctx: Context, v: Int) =
        ctx.getSharedPreferences("p", Context.MODE_PRIVATE).edit().putInt("swipe_ms", v.coerceIn(100, 600)).apply()
}
