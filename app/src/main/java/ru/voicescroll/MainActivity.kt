package ru.voicescroll

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var statusV: TextView
    private lateinit var micV: TextView
    private lateinit var appV: TextView
    private lateinit var cmdV: TextView
    private lateinit var warnV: TextView
    private lateinit var mainBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var sensitivityValue: TextView
    private lateinit var swipeValue: TextView
    private val listener: () -> Unit = { render() }

    private val bg = Color.rgb(10, 15, 29)
    private val card = Color.rgb(21, 29, 48)
    private val muted = Color.rgb(157, 169, 193)
    private val white = Color.rgb(241, 245, 255)
    private val accent = Color.rgb(126, 108, 255)
    private val green = Color.rgb(69, 211, 154)
    private val orange = Color.rgb(255, 190, 92)
    private val red = Color.rgb(255, 112, 128)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 20): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun text(value: String, size: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = true
        }

    private fun addCard(parent: LinearLayout): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val lp = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(14)
        }
        parent.addView(box, lp)
        return box
    }

    private fun addGap(parent: LinearLayout, height: Int = 8) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun addSlider(parent: LinearLayout, title: String, initial: Int, value: TextView,
                          onChange: (Int) -> Unit) {
        parent.addView(text(title, 15f, white, true))
        addGap(parent, 4)
        val seek = SeekBar(this).apply {
            max = 100
            progress = initial
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(white)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(seek, LinearLayout.LayoutParams(-1, -2))
        parent.addView(value)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            setBackgroundColor(bg)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }

        page.addView(text("VOICE SCROLL", 12f, accent, true))
        addGap(page, 4)
        page.addView(text("Голосовое управление", 27f, white, true))
        addGap(page, 3)
        page.addView(text("Голосовое управление TikTok и YouTube", 14f, muted))
        addGap(page, 18)

        val state = addCard(page)
        state.addView(text("СОСТОЯНИЕ", 11f, muted, true))
        addGap(state, 8)
        statusV = text("Распознавание: выключено", 18f, white, true)
        state.addView(statusV)
        addGap(state, 7)
        micV = text("Микрофон: выключен", 14f, muted)
        state.addView(micV)
        appV = text("Приложение: не открыто", 14f, muted)
        state.addView(appV)
        addGap(state, 10)
        cmdV = text("Последняя команда: —", 14f, green, true)
        state.addView(cmdV)
        addGap(state, 14)

        mainBtn = Button(this).apply {
            text = "Запустить"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(accent, 16)
            minHeight = dp(52)
            setOnClickListener { onMainButton() }
        }
        state.addView(mainBtn, LinearLayout.LayoutParams(-1, dp(52)))
        addGap(state, 8)
        stopBtn = Button(this).apply {
            text = "Полностью выключить"
            isAllCaps = false
            textSize = 14f
            setTextColor(white)
            background = rounded(Color.rgb(44, 53, 75), 14)
            setOnClickListener {
                startService(Intent(this@MainActivity, ListenService::class.java).setAction(ListenService.ACTION_STOP))
            }
        }
        state.addView(stopBtn, LinearLayout.LayoutParams(-1, dp(46)))

        val settings = addCard(page)
        settings.addView(text("НАСТРОЙКИ", 11f, muted, true))
        addGap(settings, 12)
        sensitivityValue = text("", 13f, muted)
        addSlider(settings, "Чувствительность голоса", AppState.sensitivity(this),
            sensitivityValue) { p ->
            AppState.saveSensitivity(this, p)
            sensitivityValue.text = "Уровень: $p% · выше — легче распознать команду"
        }
        addGap(settings, 18)
        swipeValue = text("", 13f, muted)
        val initialSwipe = AppState.swipeDuration(this)
        addSlider(settings, "Скорость свайпа", ((initialSwipe - 100) / 5).coerceIn(0, 100),
            swipeValue) { p ->
            val ms = 100 + p * 5
            AppState.saveSwipeDuration(this, ms)
            swipeValue.text = swipeDescription(ms)
        }

        val commands = addCard(page)
        commands.addView(text("ГОЛОСОВЫЕ КОМАНДЫ", 11f, muted, true))
        addGap(commands, 10)
        addCommand(commands, "«Дальше»", "Следующее видео · свайп вверх", green)
        addCommand(commands, "«Назад»", "Предыдущее видео · свайп вниз", green)
        addCommand(commands, "«Пауза» / «Продолжить»", "Поставить видео на паузу или возобновить. Микрофон продолжает слушать.", orange)
        addCommand(commands, "«Поиск [текст]»", "Открыть поиск и ввести произнесённый запрос", green)
        addCommand(commands, "«Лайк»", "Поставить отметку «Нравится»", green)
        addCommand(commands, "«Подпишись»", "Нажать видимую кнопку подписки", green)
        addCommand(commands, "«Комментарий [текст]»", "Вставить текст в поле комментария, без отправки", green)
        addCommand(commands, "«Стоп»", "Полностью остановить прослушивание", red)

        val extras = addCard(page)
        extras.addView(text("ТЕСТ И ДОСТУП", 11f, muted, true))
        addGap(extras, 8)
        val test = Switch(this).apply {
            text = "Тест команд без свайпов"
            textSize = 15f
            setTextColor(white)
            isChecked = AppState.testMode
            setOnCheckedChangeListener { _, checked -> AppState.setTestMode(checked) }
        }
        extras.addView(test)
        addGap(extras, 10)
        val a11y = Button(this).apply {
            text = "Настроить специальные возможности"
            isAllCaps = false
            setTextColor(white)
            background = rounded(Color.rgb(44, 53, 75), 14)
            setOnClickListener { showA11yHelp() }
        }
        extras.addView(a11y, LinearLayout.LayoutParams(-1, dp(48)))
        addGap(extras, 8)
        warnV = text("", 12f, orange)
        extras.addView(warnV)

        page.addView(text("Работает офлайн · русский язык · микрофон активен только при работе службы",
            11f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(12)) })

        setContentView(scroll)
        render()
    }

    private fun addCommand(parent: LinearLayout, name: String, description: String, color: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = text("●", 13f, color, true)
        row.addView(dot, LinearLayout.LayoutParams(dp(22), -2))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(name, 15f, white, true))
        col.addView(text(description, 12f, muted))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
        addGap(parent, 12)
    }

    private fun swipeDescription(ms: Int): String {
        val speed = when {
            ms <= 180 -> "очень быстро"
            ms <= 280 -> "быстро"
            ms <= 400 -> "средне"
            else -> "медленно"
        }
        return "$speed · длительность жеста $ms мс (двигай ползунок вправо для замедления)"
    }

    override fun onStart() {
        super.onStart()
        AppState.listeners.add(listener)
        render()
    }

    override fun onStop() {
        super.onStop()
        AppState.listeners.remove(listener)
    }

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/${SwipeService::class.java.name}")
    }

    private fun render() {
        val m = AppState.mode
        statusV.text = when (m) {
            AppState.Mode.OFF -> "● Выключено"
            AppState.Mode.LOADING -> "● Загружаю модель…"
            AppState.Mode.WAITING -> "● Жду открытия TikTok или YouTube"
            AppState.Mode.LISTENING -> "● Слушаю команды"
            AppState.Mode.PAUSED -> "Ⅱ Пауза"
            AppState.Mode.STOPPED -> "■ Остановлено командой «Стоп»"
            AppState.Mode.ERROR -> "● Ошибка"
        }
        statusV.setTextColor(when (m) {
            AppState.Mode.LISTENING -> green
            AppState.Mode.PAUSED, AppState.Mode.WAITING -> orange
            AppState.Mode.ERROR -> red
            else -> white
        })
        micV.text = "Микрофон: " + if (m == AppState.Mode.LISTENING || m == AppState.Mode.PAUSED) "активен" else "выключен"
        appV.text = "Приложение: " + if (AppState.target != AppState.Target.NONE) "${AppState.appLabel} открыто" else "не открыто"
        cmdV.text = "Последняя команда: ${AppState.lastCommand}"
        mainBtn.text = when (m) {
            AppState.Mode.OFF, AppState.Mode.ERROR -> "Запустить"
            AppState.Mode.STOPPED -> "Запустить снова"
            AppState.Mode.PAUSED -> "Возобновить команды"
            else -> "Приостановить команды"
        }
        stopBtn.isEnabled = m != AppState.Mode.OFF
        stopBtn.alpha = if (stopBtn.isEnabled) 1f else 0.5f
        val warnings = mutableListOf<String>()
        if (!a11yEnabled()) warnings += "⚠ Включи службу специальных возможностей — без неё свайпы не выполняются."
        if (AppState.message.isNotEmpty()) warnings += AppState.message
        warnV.text = warnings.joinToString("\n")
        sensitivityValue.text = "Уровень: ${AppState.sensitivity(this)}% · выше — легче распознать команду"
        swipeValue.text = swipeDescription(AppState.swipeDuration(this))
    }

    private fun onMainButton() {
        when (AppState.mode) {
            AppState.Mode.OFF, AppState.Mode.ERROR ->
                startFlow()
            AppState.Mode.STOPPED ->
                startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_RESUME))
            AppState.Mode.PAUSED ->
                startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_RESUME))
            else ->
                startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_PAUSE))
        }
    }

    private fun startFlow() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) launch()
        else AlertDialog.Builder(this)
            .setTitle("Нужны разрешения")
            .setMessage("Микрофон нужен для распознавания русских команд. Распознавание работает на телефоне, без отправки голоса в интернет. Уведомление показывает, когда служба активна.")
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
            .setMessage("Она нужна для свайпов и определения, открыты ли TikTok или YouTube.\n\n1. Настройки → Специальные возможности → Установленные службы → «VoiceScroll» → Включить.\n\nЕсли пункт серый («ограниченная настройка»): Настройки → Приложения → «VoiceScroll» → ⋮ → «Разрешить ограниченные настройки», затем повтори шаг 1.")
            .setPositiveButton("Открыть настройки") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNeutralButton("О приложении") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Закрыть", null).show()
    }
}
