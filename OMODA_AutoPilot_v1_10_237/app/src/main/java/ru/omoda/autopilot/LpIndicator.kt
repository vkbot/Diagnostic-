package ru.omoda.autopilot

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * Экранный индикатор голоса: пульсирующий круг и подпись поверх любого приложения.
 * Касания не перехватывает. Без права «поверх других окон» тихо не показывается.
 */
object LpIndicator {
    private val main = Handler(Looper.getMainLooper())
    private var view: PulseView? = null
    private var wm: WindowManager? = null
    private val hideTask = Runnable { hideNow() }

    /** Показать или обновить подпись. autoHideMs > 0 — скрыть через столько миллисекунд. */
    fun show(c: Context, text: String, autoHideMs: Long = 0L) {
        if (LpLauncher.armed(c)) return // скрыто: никаких плашек на экране
        val app = c.applicationContext
        main.post {
            if (!Settings.canDrawOverlays(app)) return@post
            val current = view
            if (current != null) {
                current.text = text
            } else {
                val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val density = app.resources.displayMetrics.density
                val v = PulseView(app, density, text)
                val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                val lp = WindowManager.LayoutParams(
                    (260 * density).toInt(), (180 * density).toInt(), type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = (40 * density).toInt()
                try {
                    manager.addView(v, lp)
                    view = v
                    wm = manager
                } catch (e: Exception) {
                    return@post
                }
            }
            main.removeCallbacks(hideTask)
            if (autoHideMs > 0) main.postDelayed(hideTask, autoHideMs)
        }
    }

    fun hide() {
        main.post { hideNow() }
    }

    private fun hideNow() {
        val v = view ?: return
        try {
            wm?.removeViewImmediate(v)
        } catch (e: Exception) {
            // окно уже снято системой
        }
        view = null
        wm = null
    }

    /** Карточка с пульсирующим кругом и подписью. */
    class PulseView(c: Context, private val density: Float, initial: String) : View(c) {
        var text: String = initial
            set(value) {
                field = value
                invalidate()
            }
        private var phase = 0f
        private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#29D3FF") }
        private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E0161D26") }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E8EEF5")
            textSize = 18 * density
            textAlign = Paint.Align.CENTER
        }
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            anim.start()
        }

        override fun onDetachedFromWindow() {
            anim.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRoundRect(RectF(0f, 0f, w, h), 24 * density, 24 * density, card)
            val pulse = sin(phase * PI).toFloat()
            val cx = w / 2f
            val cy = h * 0.42f
            val r = 26 * density + 8 * density * pulse
            circle.alpha = (90 + 120 * pulse).toInt()
            canvas.drawCircle(cx, cy, r + 10 * density, circle)
            circle.alpha = 255
            canvas.drawCircle(cx, cy, r, circle)
            canvas.drawText(text, cx, h * 0.86f, label)
        }
    }
}
