package ru.voicescroll

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.Executors

class ListenService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_RESUME = "resume"
        private const val CHANNEL = "listen"
        private const val NID = 1
        private const val GRAMMAR = "[\"дальше\", \"назад\", \"стоп\", \"[unk]\"]"
        private const val COOLDOWN_MS = 1500L
        private val COMMANDS = setOf("дальше", "назад", "стоп")
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var running = false
    private var stoppedByVoice = false
    private var lastCmdAt = 0L
    private val stateListener: () -> Unit = { evaluate() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Прослушивание команд", NotificationManager.IMPORTANCE_LOW))
        AppState.listeners.add(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_RESUME -> { stoppedByVoice = false; evaluate() }
            else -> start()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        startForeground(NID, notification("Загрузка модели…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (running) return
        running = true; stoppedByVoice = false
        AppState.message = ""
        AppState.setMode(AppState.Mode.LOADING)
        worker.execute {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = Model(prepareModel())
                main.post { model = m; evaluate() }
            } catch (e: Throwable) {
                main.post { fail("Не удалось загрузить модель: ${e.message}") }
            }
        }
    }

    private fun prepareModel(): String {
        val dir = File(filesDir, "model")
        val ok = File(filesDir, "model.ok")
        if (!ok.exists()) {
            dir.deleteRecursively()
            copyAssets("model", dir)
            ok.writeText("1")
        }
        return dir.absolutePath
    }

    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path) ?: emptyArray()
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            list.forEach { copyAssets("$path/$it", File(dest, it)) }
        }
    }

    /** Единая точка: слушаем только если TikTok открыт (или включена проверка) и не было «Стоп». */
    private fun evaluate() {
        if (!running || model == null) return
        val want = !stoppedByVoice && (AppState.tiktok || AppState.testMode)
        if (want && speech == null) startRec() else if (!want && speech != null) stopRec()
        AppState.setMode(when {
            stoppedByVoice -> AppState.Mode.STOPPED
            speech != null -> AppState.Mode.LISTENING
            else -> AppState.Mode.WAITING
        })
        getSystemService(NotificationManager::class.java).notify(NID, notification(
            when (AppState.mode) {
                AppState.Mode.LISTENING -> "Слушаю: «Дальше», «Назад», «Стоп»"
                AppState.Mode.STOPPED -> "Остановлено командой «Стоп»"
                else -> "Ожидание: откройте TikTok"
            }))
    }

    private fun startRec() {
        try {
            val rec = Recognizer(model, 16000f, GRAMMAR)
            rec.setWords(true)
            val ss = SpeechService(rec, 16000f)
            recognizer = rec; speech = ss
            ss.startListening(object : RecognitionListener {
                override fun onResult(h: String?) { if (h != null) handle(h) }
                override fun onPartialResult(h: String?) {}
                override fun onFinalResult(h: String?) {}
                override fun onError(e: Exception?) { fail("Ошибка микрофона: ${e?.message}") }
                override fun onTimeout() {}
            })
        } catch (e: Throwable) {
            stopRec(); fail("Не удалось запустить микрофон: ${e.message}")
        }
    }

    private fun stopRec() {
        try { speech?.stop(); speech?.shutdown() } catch (_: Throwable) {}
        try { recognizer?.close() } catch (_: Throwable) {}
        speech = null; recognizer = null
    }

    private fun handle(json: String) {
        val o = JSONObject(json)
        val text = o.optString("text").trim()
        if (text.isEmpty() || text == "[unk]") return
        val cmd = text.split(" ").firstOrNull { it in COMMANDS } ?: return
        val arr = o.optJSONArray("result")
        var conf = 1.0
        if (arr != null && arr.length() > 0) {
            var s = 0.0
            for (i in 0 until arr.length()) s += arr.getJSONObject(i).optDouble("conf", 1.0)
            conf = s / arr.length()
        }
        if (conf < AppState.threshold(this)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCmdAt < COOLDOWN_MS) return   // защита от повторов
        lastCmdAt = now
        AppState.lastCommand = "«${cmd.replaceFirstChar { it.uppercase() }}» (${(conf * 100).toInt()}%)"
        when (cmd) {
            "стоп" -> { stoppedByVoice = true; evaluate(); return }
            "дальше", "назад" -> {
                if (AppState.tiktok) {
                    val s = SwipeService.instance
                    AppState.message = if (s == null) "Служба спец. возможностей выключена"
                    else if (!s.swipe(cmd == "дальше")) "Свайп не выполнен" else ""
                } else AppState.message = "Проверка: свайп не выполняется вне TikTok"
            }
        }
        AppState.fire()
    }

    private fun fail(msg: String) {
        AppState.message = msg
        shutdown(AppState.Mode.ERROR)
    }

    private fun shutdown(final: AppState.Mode = AppState.Mode.OFF) {
        running = false
        stopRec()
        AppState.setMode(final)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        AppState.listeners.remove(stateListener)
        stopRec()
        model?.close()
        if (AppState.mode != AppState.Mode.ERROR) AppState.setMode(AppState.Mode.OFF)
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        fun act(a: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, ListenService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Голосовой TikTok")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (stoppedByVoice) b.addAction(Notification.Action.Builder(null, "Продолжить", act(ACTION_RESUME, 2)).build())
        b.addAction(Notification.Action.Builder(null, "Выключить", act(ACTION_STOP, 1)).build())
        return b.build()
    }
}
