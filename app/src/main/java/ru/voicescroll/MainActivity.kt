package ru.voicescroll

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private lateinit var statusV: TextView
    private lateinit var micV: TextView
    private lateinit var cmdV: TextView
    private lateinit var warnV: TextView
    private lateinit var btn: Button
    private val listener: () -> Unit = { render() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(24)) }
        fun tv(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextView(this).apply {
            textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }.also { root.addView(it) }

        tv(24f, true).text = "Голосовой TikTok"
        statusV = tv(17f); micV = tv(17f); cmdV = tv(17f, true)
        warnV = tv(14f, color = Color.rgb(200, 40, 40))
        btn = Button(this).also { root.addView(it) }
        btn.setOnClickListener { onMainButton() }

        tv(16f, true).text = "Чувствительность"
        val sb = SeekBar(this).apply {
            max = 100; progress = AppState.sensitivity(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { AppState.saveSensitivity(this@MainActivity, p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(sb)
        tv(12f, color = Color.GRAY).text = "Выше — команды ловятся легче, но возможны ложные срабатывания."

        val sw = Switch(this).apply {
            text = "Проверка команд без TikTok (свайпы не выполняются)"
            setOnCheckedChangeListener { _, c -> AppState.setTestMode(c) }
        }
        root.addView(sw)

        Button(this).apply { text = "Включить службу спец. возможностей"; setOnClickListener { showA11yHelp() } }.also { root.addView(it) }
        tv(12f, color = Color.GRAY).text = "Команды: «Дальше» — следующее видео, «Назад» — предыдущее, «Стоп» — остановить прослушивание. Прослушивание начинается само при открытии TikTok и прекращается при его закрытии."

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onStart() { super.onStart(); AppState.listeners.add(listener); render() }
    override fun onStop() { super.onStop(); AppState.listeners.remove(listener) }

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/${SwipeService::class.java.name}")
    }

    private fun render() {
        val m = AppState.mode
        statusV.text = "Распознавание: " + when (m) {
            AppState.Mode.OFF -> "выключено"
            AppState.Mode.LOADING -> "загрузка модели…"
            AppState.Mode.WAITING -> "ждёт открытия TikTok"
            AppState.Mode.LISTENING -> "слушает"
            AppState.Mode.STOPPED -> "остановлено командой «Стоп»"
            AppState.Mode.ERROR -> "ошибка"
        }
        micV.text = "Микрофон: " + if (m == AppState.Mode.LISTENING) "включён" else "выключен"
        cmdV.text = "Последняя команда: ${AppState.lastCommand}"
        btn.text = when (m) {
            AppState.Mode.OFF, AppState.Mode.ERROR -> "Запустить"
            AppState.Mode.STOPPED -> "Продолжить"
            else -> "Остановить"
        }
        val w = mutableListOf<String>()
        if (!a11yEnabled()) w += "⚠ Служба специальных возможностей выключена — свайпы не работают."
        if (AppState.message.isNotEmpty()) w += AppState.message
        warnV.text = w.joinToString("\n")
    }

    private fun onMainButton() {
        when (AppState.mode) {
            AppState.Mode.OFF, AppState.Mode.ERROR -> startFlow()
            AppState.Mode.STOPPED -> startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_RESUME))
            else -> startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_STOP))
        }
    }

    private fun startFlow() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) launch()
        else AlertDialog.Builder(this)
            .setTitle("Нужны разрешения")
            .setMessage("Микрофон — чтобы слышать команды (распознавание полностью на телефоне, без интернета).\nУведомления — чтобы вы всегда видели, что микрофон слушает.")
            .setPositiveButton("Продолжить") { _, _ -> requestPermissions(need.toTypedArray(), 1) }
            .setNegativeButton("Отмена", null).show()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launch()
        else Toast.makeText(this, "Без доступа к микрофону прослушивание не запустится", Toast.LENGTH_LONG).show()
    }

    private fun launch() {
        startForegroundService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_START))
        if (!a11yEnabled()) showA11yHelp()
    }

    private fun showA11yHelp() {
        AlertDialog.Builder(this)
            .setTitle("Служба специальных возможностей")
            .setMessage("Она нужна только для свайпов и чтобы узнавать, открыт ли TikTok. Текст экрана не читается.\n\n" +
                "1. Настройки → Специальные возможности → Установленные службы → «Голосовой TikTok» → Включить.\n\n" +
                "Если пункт серый («ограниченная настройка»): Настройки → Приложения → «Голосовой TikTok» → ⋮ (справа сверху) → «Разрешить ограниченные настройки», затем повторите шаг 1.")
            .setPositiveButton("Открыть спец. возможности") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNeutralButton("О приложении") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Закрыть", null).show()
    }
}
