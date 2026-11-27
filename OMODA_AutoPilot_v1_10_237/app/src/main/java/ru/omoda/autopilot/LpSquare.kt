package ru.omoda.autopilot

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Невидимый квадрат в правом верхнем углу. Пять нажатий подряд возвращают ярлыки. */
object LpSquare {
    private const val SIZE_DP = 60
    private const val TAP_WINDOW_MS = 1500L
    private const val TAPS_TO_RESTORE = 5

    private var view: View? = null
    private var wm: WindowManager? = null
    private var count = 0
    private var lastTap = 0L

    fun canDraw(c: Context): Boolean = Settings.canDrawOverlays(c)

    /** Проверка до скрытия: окно реально добавляется и убирается. Возвращает текст ошибки или null. */
    fun probe(c: Context): String? {
        if (!canDraw(c)) return "нет права «поверх других окон»"
        return try {
            val manager = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(c)
            manager.addView(v, params(c, 10, 10, 0, 0))
            manager.removeViewImmediate(v)
            null
        } catch (e: Exception) {
            "оверлей не добавляется: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun show(c: Context) {
        if (view != null || !canDraw(c)) return
        val manager = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = c.resources.displayMetrics
        val size = (SIZE_DP * dm.density).toInt()
        val x = (dm.widthPixels - size).coerceAtLeast(0)
        val v = View(c)
        v.setBackgroundColor(0x01000000)
        val app = c.applicationContext
        v.setOnClickListener { tap(app) }
        try {
            manager.addView(v, params(c, size, size, x, 0))
            view = v
            wm = manager
        } catch (e: Exception) {
            view = null
        }
    }

    private fun tap(c: Context) {
        val now = SystemClock.elapsedRealtime()
        count = if (now - lastTap > TAP_WINDOW_MS) 1 else count + 1
        lastTap = now
        if (count >= TAPS_TO_RESTORE) {
            count = 0
            LpLauncher.restore(c)
        }
    }

    fun remove() {
        val v = view ?: return
        try {
            wm?.removeViewImmediate(v)
        } catch (e: Exception) {
            // окно уже снято системой
        }
        view = null
        wm = null
    }

    private fun params(c: Context, w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        return lp
    }
}
