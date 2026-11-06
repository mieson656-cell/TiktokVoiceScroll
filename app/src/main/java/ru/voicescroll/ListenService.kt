package ru.voicescroll

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.*
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class ListenService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_RESUME = "resume"
        const val ACTION_PAUSE = "pause"
        private const val CHANNEL = "listen"
        private const val NID = 1
        private const val COOLDOWN_MS = 1100L
        private const val SAMPLE_RATE = 16000
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var gainControl: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)
    private var running = false
    private var stoppedByVoice = false
    private var playbackPaused = false
    private var lastCmdAt = 0L
    private val stateListener: () -> Unit = { evaluate() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Прослушивание команд", NotificationManager.IMPORTANCE_LOW))
        AppState.listeners.add(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_PAUSE -> AppState.setPaused(true)
            ACTION_RESUME -> {
                if (stoppedByVoice || !running) { stoppedByVoice = false; start() }
                AppState.setPaused(false)
                evaluate()
            }
            else -> start()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        startForeground(NID, notification("Загрузка модели…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (running) { evaluate(); return }
        running = true
        stoppedByVoice = false
        playbackPaused = false
        AppState.setPaused(false)
        AppState.message = ""
        AppState.liveText = "Подготовка микрофона…"
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

    /** Микрофон активен, когда открыто одно из поддерживаемых приложений. */
    private fun evaluate() {
        if (!running || model == null) return
        val want = !stoppedByVoice && (AppState.target != AppState.Target.NONE || AppState.testMode)
        if (want && audioRecord == null) startRec() else if (!want && audioRecord != null) stopRec()
        AppState.setMode(when {
            stoppedByVoice -> AppState.Mode.STOPPED
            audioRecord != null && AppState.paused -> AppState.Mode.PAUSED
            audioRecord != null -> AppState.Mode.LISTENING
            else -> AppState.Mode.WAITING
        })
        getSystemService(NotificationManager::class.java).notify(NID, notification(
            when (AppState.mode) {
                AppState.Mode.LISTENING -> "Слушаю команды · ${AppState.appLabel}"
                AppState.Mode.PAUSED -> "Команды ограничены паузой"
                AppState.Mode.STOPPED -> "Остановлено — нажмите «Продолжить»"
                else -> "Откройте TikTok или YouTube"
            }))
    }

    /**
     * Берём звук напрямую через обычный MIC AudioRecord: на некоторых прошивках Samsung VOICE_RECOGNITION может отдавать тишину. Включаем системные AGC и NoiseSuppressor, если доступны.
     * Это помогает вытянуть тихую речь и подавить постоянный фоновый шум, если устройство поддерживает эффекты.
     */
    private fun startRec() {
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw IllegalStateException("Недоступен формат микрофона 16 кГц")
            val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE * 2)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException("Android не смог открыть микрофон")
            }
            val rec = Recognizer(model, SAMPLE_RATE.toFloat())
            rec.setWords(true)
            audioRecord = recorder
            recognizer = rec

            if (AutomaticGainControl.isAvailable()) {
                try {
                    gainControl = AutomaticGainControl.create(recorder.audioSessionId)?.apply { enabled = true }
                } catch (_: Throwable) { gainControl = null }
            }
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                } catch (_: Throwable) { noiseSuppressor = null }
            }

            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Микрофон не перешёл в режим записи")
            }
            audioRunning.set(true)
            AppState.liveText = buildString {
                append("Микрофон активен · ")
                append(if (gainControl?.enabled == true) "автоусиление включено" else "автоусиление недоступно")
                append(" · ")
                append(if (noiseSuppressor?.enabled == true) "шумоподавление включено" else "шумоподавление недоступно")
            }
            AppState.fire()

            audioThread = Thread({
                val data = ByteArray(2048)
                try {
                    while (audioRunning.get()) {
                        val count = recorder.read(data, 0, data.size)
                        if (count > 0) {
                            var energy = 0.0
                            val samples = count / 2
                            var i = 0
                            while (i + 1 < count) {
                                val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort().toInt()
                                energy += sample.toDouble() * sample.toDouble()
                                i += 2
                            }
                            val rms = if (samples > 0) sqrt(energy / samples).toInt() else 0
                            val db = if (rms > 0) (20.0 * kotlin.math.log10(rms / 32768.0)).toInt() else -90
                            val accepted = rec.acceptWaveForm(data, count)
                            val result = if (accepted) rec.result else rec.partialResult
                            val text = JSONObject(result).optString("text").trim()
                            main.post {
                                AppState.inputLevel = db.coerceIn(-90, 0)
                                if (text.isNotEmpty()) AppState.liveText = "Слышу: $text"
                                AppState.fire()
                            }
                            if (accepted && text.isNotEmpty()) handle(result)
                        } else if (count < 0 && audioRunning.get()) {
                            throw IllegalStateException("Ошибка чтения микрофона: $count")
                        }
                    }
                } catch (e: Throwable) {
                    if (audioRunning.get()) main.post { fail("Ошибка микрофона: ${e.message}") }
                }
            }, "VoiceScroll-Audio").apply { isDaemon = true; start() }
        } catch (e: Throwable) {
            stopRec()
            fail("Не удалось запустить микрофон: ${e.message}")
        }
    }

    private fun stopRec() {
        audioRunning.set(false)
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        try { gainControl?.release() } catch (_: Throwable) {}
        try { noiseSuppressor?.release() } catch (_: Throwable) {}
        try { recognizer?.close() } catch (_: Throwable) {}
        audioRecord = null
        gainControl = null
        noiseSuppressor = null
        recognizer = null
        audioThread = null
        AppState.inputLevel = -90
    }

    private fun handle(json: String) {
        val o = JSONObject(json)
        val spoken = o.optString("text").trim().lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (spoken.isEmpty() || spoken == "[unk]") return
        val arr = o.optJSONArray("result")
        var conf = 1.0
        if (arr != null && arr.length() > 0) {
            var sum = 0.0
            for (i in 0 until arr.length()) sum += arr.getJSONObject(i).optDouble("conf", 1.0)
            conf = sum / arr.length()
        }
        if (conf < AppState.threshold(this)) return

        val words = spoken.split(" ").toSet()
        val cmd = when {
            spoken.startsWith("поиск ") || spoken.startsWith("найди ") -> "поиск"
            spoken.startsWith("напиши комментарий ") || spoken.startsWith("комментарий ") -> "комментарий"
            "дальше" in words || "следующее" in words || "следующий" in words -> "дальше"
            "назад" in words || "предыдущее" in words || "предыдущий" in words -> "назад"
            "пауза" in words || ("останови" in words && "видео" in words) -> "пауза"
            "продолжить" in words || "воспроизведение" in words -> "продолжить"
            "лайк" in words || ("поставь" in words && "нравится" in words) -> "лайк"
            "подпишись" in words || "подписаться" in words -> "подпишись"
            "стоп" in words -> "стоп"
            else -> return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastCmdAt < COOLDOWN_MS) return
        lastCmdAt = now
        AppState.lastCommand = "«${cmd.replaceFirstChar { it.uppercase() }}» (${(conf * 100).toInt()}%)"
        val payload = when {
            spoken.startsWith("поиск ") -> spoken.removePrefix("поиск ").trim()
            spoken.startsWith("найди ") -> spoken.removePrefix("найди ").trim()
            spoken.startsWith("напиши комментарий ") -> spoken.removePrefix("напиши комментарий ").trim()
            spoken.startsWith("комментарий ") -> spoken.removePrefix("комментарий ").trim()
            else -> ""
        }
        main.post { executeCommand(cmd, payload) }
    }

    private fun executeCommand(cmd: String, payload: String) {
        if (cmd == "стоп") {
            stoppedByVoice = true
            AppState.setPaused(false)
            evaluate()
            AppState.fire()
            return
        }
        if (AppState.target == AppState.Target.NONE && !AppState.testMode) return
        if (AppState.paused) {
            AppState.message = "Управление приостановлено кнопкой в приложении"
            AppState.fire()
            return
        }
        val service = SwipeService.instance
        val ok = when (cmd) {
            "пауза" -> {
                if (!playbackPaused) {
                    val done = service?.togglePlayback() == true
                    if (done) playbackPaused = true
                    done
                } else true
            }
            "продолжить" -> {
                if (playbackPaused) {
                    val done = service?.togglePlayback() == true
                    if (done) playbackPaused = false
                    done
                } else true
            }
            "дальше" -> service?.swipe(true) == true
            "назад" -> service?.swipe(false) == true
            "поиск" -> service?.search(payload) == true
            "лайк" -> service?.like() == true
            "подпишись" -> service?.follow() == true
            "комментарий" -> service?.comment(payload) == true
            else -> false
        }
        AppState.message = when {
            ok && cmd == "комментарий" -> "Текст вставлен в комментарий. Самостоятельно проверь и отправь."
            ok && cmd == "поиск" -> "Поисковый запрос введён."
            ok -> ""
            else -> "Не удалось выполнить «$cmd»: элемент интерфейса не найден или действие недоступно."
        }
        AppState.fire()
        evaluate()
    }

    private fun fail(msg: String) {
        AppState.message = msg
        shutdown(AppState.Mode.ERROR)
    }

    private fun shutdown(final: AppState.Mode = AppState.Mode.OFF) {
        running = false
        stoppedByVoice = false
        playbackPaused = false
        AppState.setPaused(false)
        stopRec()
        AppState.setMode(final)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        AppState.listeners.remove(stateListener)
        stopRec()
        model?.close()
        worker.shutdown()
        if (AppState.mode != AppState.Mode.ERROR) AppState.setMode(AppState.Mode.OFF)
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        fun act(a: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, ListenService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VoiceScroll")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (AppState.paused) b.addAction(Notification.Action.Builder(null, "Продолжить", act(ACTION_RESUME, 2)).build())
        else b.addAction(Notification.Action.Builder(null, "Пауза", act(ACTION_PAUSE, 3)).build())
        if (stoppedByVoice) b.addAction(Notification.Action.Builder(null, "Запустить", act(ACTION_RESUME, 2)).build())
        b.addAction(Notification.Action.Builder(null, "Выключить", act(ACTION_STOP, 1)).build())
        return b.build()
    }
}
