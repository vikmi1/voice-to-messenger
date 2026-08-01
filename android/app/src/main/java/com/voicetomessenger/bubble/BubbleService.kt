package com.voicetomessenger.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.voicetomessenger.MainActivity
import com.voicetomessenger.R
import com.voicetomessenger.appContainer
import com.voicetomessenger.capture.CaptureState
import com.voicetomessenger.capture.VoicePipeline
import com.voicetomessenger.localizedContext
import com.voicetomessenger.localizedString
import com.voicetomessenger.ui.copyToClipboard
import com.voicetomessenger.ui.shareText
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Кнопка микрофона поверх других приложений.
 *
 * Держит тот же [VoicePipeline], что и главный экран, поэтому настройки распознавания
 * и обработки одни на всё приложение. Вставить текст прямо в чужое поле ввода Android
 * без своей клавиатуры или Accessibility-сервиса не даёт, поэтому итог кладётся в буфер
 * обмена — вставку делает сам пользователь.
 */
class BubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var pipeline: VoicePipeline

    private var bubbleView: View? = null
    private var panelView: View? = null

    private val bubbleParams = overlayParams().apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 400
    }

    // Панель сверху: снизу экрана мессенджер держит поле ввода и клавиатуру.
    private val panelParams = overlayParams().apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 80
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = checkNotNull(getSystemService<WindowManager>())
        pipeline = appContainer.newPipeline(scope)

        startForegroundWithNotification()
        showBubble()

        scope.launch { pipeline.state.collect(::render) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * Совсем выключить пузырь — и снять галочку в настройках, иначе он вернётся.
     *
     * Настройку пишем в appScope: свой scope умрёт вместе с сервисом на [stopSelf],
     * а запись в DataStore приостанавливается и не успела бы дойти.
     */
    private fun shutDown() {
        appContainer.appScope.launch { appContainer.settings.setBubbleEnabled(false) }
        stopSelf()
    }

    override fun onDestroy() {
        removePanel()
        removeBubble()
        pipeline.release()
        scope.cancel()
        super.onDestroy()
    }

    // --- Пузырь -----------------------------------------------------------

    private fun showBubble() {
        if (bubbleView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        view.setOnClickListener { pipeline.toggle() }
        makeDraggable(view, bubbleParams)
        runCatching { windowManager.addView(view, bubbleParams) }
            .onSuccess { bubbleView = view }
            .onFailure { stopSelf() } // разрешение отозвали, пока сервис работал
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    /** Перетаскивание пузыря; короткое касание без сдвига считается кликом. */
    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false

        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(touched, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) touched.performClick()
                    true
                }

                else -> false
            }
        }
    }

    // --- Панель результата ------------------------------------------------

    private fun render(state: CaptureState) {
        val recording = state is CaptureState.Listening
        bubbleView?.setBackgroundResource(
            if (recording) R.drawable.bubble_recording_background else R.drawable.bubble_background,
        )
        // По одному цвету непонятно, что делать дальше — меняем и саму иконку.
        bubbleView?.findViewById<ImageView>(R.id.bubble_icon)
            ?.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic)

        when (state) {
            CaptureState.Idle -> removePanel()

            is CaptureState.Listening -> showPanel(
                status = localizedString(
                    if (state.paused) R.string.bubble_status_paused else R.string.bubble_status_listening,
                ),
                text = state.partial,
                actionsEnabled = false,
            )

            CaptureState.Transcribing -> showPanel(
                status = localizedString(R.string.status_transcribing),
                text = "",
                actionsEnabled = false,
            )

            // Предел длины: панель ждёт решения. «В заметки» на это время становится
            // «Отправить» — отдельной кнопки в разметке нет, а решение нужно принять здесь
            // же; «Закрыть» и так отменяет запись через finishSession().
            is CaptureState.LimitReached -> showPanel(
                status = localizedString(R.string.status_limit_reached),
                text = state.raw,
                actionsEnabled = false,
                sendAfterLimit = true,
            )

            is CaptureState.Processing -> showPanel(
                status = localizedString(R.string.status_processing),
                text = state.text,
                actionsEnabled = false,
            )

            is CaptureState.Done -> showPanel(
                status = localizedString(R.string.bubble_status_done),
                text = state.text,
                actionsEnabled = true,
            )

            is CaptureState.Failed -> showPanel(
                status = state.message,
                text = "",
                actionsEnabled = false,
            )
        }
    }

    private fun showPanel(
        status: String,
        text: String,
        actionsEnabled: Boolean,
        /** Перевести кнопку заметки в режим «Отправить» — см. ветку LimitReached в [render]. */
        sendAfterLimit: Boolean = false,
    ) {
        val view = panelView ?: inflatePanel() ?: return
        view.findViewById<TextView>(R.id.panel_status).text = status
        view.findViewById<TextView>(R.id.panel_text).text = text

        listOf(R.id.panel_copy, R.id.panel_share).forEach { id ->
            view.findViewById<Button>(id).isEnabled = actionsEnabled
        }

        // Кнопка заметки — единственная свободная под решение о пределе, поэтому она
        // подменяется целиком: и подпись, и что делает. Обработчик ставится здесь, а не в
        // inflatePanel(), потому что панель переиспользуется между состояниями.
        view.findViewById<Button>(R.id.panel_note).apply {
            isEnabled = actionsEnabled || sendAfterLimit
            // this.text, а не text: одноимённый параметр showPanel() перекрыл бы свойство кнопки.
            this.text =
                localizedString(if (sendAfterLimit) R.string.action_send else R.string.action_save_note)
            setOnClickListener {
                if (sendAfterLimit) {
                    pipeline.sendAfterLimit()
                } else {
                    currentText()?.let { ready ->
                        scope.launch { appContainer.notes.add(ready) }
                        finishSession()
                    }
                }
            }
        }
    }

    private fun inflatePanel(): View? {
        // Разметку раздуваем из контекста с выбранной локалью: у сервиса своей нет,
        // и подписи кнопок в XML пришли бы по системному языку.
        val view = LayoutInflater.from(localizedContext()).inflate(R.layout.overlay_panel, null)

        view.findViewById<Button>(R.id.panel_copy).setOnClickListener {
            currentText()?.let {
                copyToClipboard(it, toastMessage = localizedString(R.string.bubble_copy_toast))
                finishSession()
            }
        }
        view.findViewById<Button>(R.id.panel_share).setOnClickListener {
            currentText()?.let {
                shareText(it)
                finishSession()
            }
        }
        // panel_note намеренно не настраивается здесь: его подпись и действие зависят от
        // состояния и ставятся в showPanel() при каждой отрисовке.
        view.findViewById<Button>(R.id.panel_close).setOnClickListener { finishSession() }
        view.findViewById<ImageButton>(R.id.panel_power).setOnClickListener { shutDown() }

        return runCatching { windowManager.addView(view, panelParams) }
            .onSuccess { panelView = view }
            .map { view }
            .getOrNull()
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
    }

    private fun currentText(): String? =
        (pipeline.state.value as? CaptureState.Done)?.text?.takeIf { it.isNotBlank() }

    private fun finishSession() {
        pipeline.reset() // render(Idle) сам уберёт панель
    }

    // --- Уведомление ------------------------------------------------------

    private fun startForegroundWithNotification() {
        val manager = checkNotNull(getSystemService<NotificationManager>())
        // Канал пересоздаём всегда: повторный вызов лишь обновляет имя, зато после
        // смены языка оно не остаётся на прежнем в системных настройках уведомлений.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                localizedString(R.string.bubble_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(localizedString(R.string.bubble_notification_title))
            .setContentText(localizedString(R.string.bubble_notification_text))
            .setContentIntent(openApp)
            .addAction(0, localizedString(R.string.bubble_stop), stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Не забираем фокус у мессенджера — клавиатура под нами продолжает работать.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    )

    companion object {
        private const val CHANNEL_ID = "bubble"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.voicetomessenger.BUBBLE_STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }
}
