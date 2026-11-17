package ru.omoda.autopilot

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** Перехватывает INSTALL_PACKAGE от RuStore: копирует APK из content:// и ставит через pm install. */
class InterceptInstallActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var dots = 0
    private var installing = false

    // Анимация: точки после слов «Идёт установка»
    private val tick = object : Runnable {
        override fun run() {
            if (!installing) return
            dots = (dots + 1) % 4
            status.text = "Идёт установка" + ".".repeat(dots)
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this)
        frame.setBackgroundColor(COLOR_BG)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER
        column.setPadding(dp(32), dp(32), dp(32), dp(32))
        status = labelView("", 24f)
        status.gravity = Gravity.CENTER
        detail = labelView("", 15f, 12)
        detail.gravity = Gravity.CENTER
        column.addView(status)
        column.addView(detail)
        frame.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Кнопка выхода внизу справа на случай, если что-то пойдёт не так
        val exit = actionButton("ВЫХОД") { finish() }
        exit.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) }
        frame.addView(exit)
        setContentView(frame)

        val uri = intent?.data
        if (uri == null) {
            finishWith(false, "Нет данных установки")
            return
        }
        installing = true
        status.text = "Идёт установка"
        handler.post(tick)
        Thread {
            val (ok, msg) = install(uri)
            runOnUiThread { finishWith(ok, msg) }
        }.start()
    }

    private fun finishWith(ok: Boolean, msg: String) {
        installing = false
        handler.removeCallbacks(tick)
        if (ok) {
            status.text = "Установка завершена успешно"
            status.setTextColor(Color.parseColor("#4ADE80"))
            detail.text = ""
        } else {
            status.text = "Ошибка установки"
            status.setTextColor(Color.parseColor("#FF6B6B"))
            detail.text = msg
        }
    }

    override fun onDestroy() {
        installing = false
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    /** Журнал этапов: тег OmodaInstall в logcat и строка в уведомлениях помощника. */
    private fun stage(msg: String) {
        Log.i(TAG, msg)
        SettingsRepository(applicationContext).addEvent("[установка] $msg")
    }

    /**
     * Этапы: получить запрос, скопировать поток, проверить что это APK,
     * сверить версию с установленной, поставить через pm, убрать копию.
     * Возвращает (успех, текст для человека).
     */
    private fun install(uri: Uri): Pair<Boolean, String> {
        val file = File(filesDir, "incoming.apk")
        try {
            stage("1 запрос получен: $uri")
            val input = contentResolver.openInputStream(uri)
                ?: return Pair(false, "нет доступа к файлу: провайдер не открыл поток")
            input.use { i -> file.outputStream().use { i.copyTo(it) } }
            stage("2 скопировано ${file.length()} байт")
            if (file.length() == 0L) return Pair(false, "файл пустой: провайдер отдал 0 байт")

            val info = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: return Pair(false, "это не APK: системный разбор манифеста не прошёл")
            val pkg = info.packageName
            val newVersion = info.longVersionCode
            stage("3 пакет $pkg, версия $newVersion")

            val installed = try {
                packageManager.getPackageInfo(pkg, 0).longVersionCode
            } catch (e: Exception) {
                -1L
            }
            if (installed >= 0 && newVersion == installed) {
                return Pair(true, "уже установлено: $pkg, версия $installed. Ничего не менял")
            }
            if (installed >= 0 && newVersion < installed) {
                return Pair(false, "даунгрейд запрещён: установлена версия $installed, в APK $newVersion")
            }
            val mode = if (installed >= 0) "обновление с $installed" else "новая установка"
            stage("4 $mode")

            val p = ProcessBuilder("sh", "-c", "pm install -r -S ${file.length()}")
                .redirectErrorStream(true).start()
            file.inputStream().use { it.copyTo(p.outputStream) }
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            stage("5 ответ pm: $out")
            return if (out.contains("Success")) {
                Pair(true, "установлено: $pkg, версия $newVersion")
            } else {
                Pair(false, "pm: " + out.ifEmpty { "пустой ответ" })
            }
        } catch (e: Exception) {
            stage("исключение: ${e.javaClass.simpleName}: ${e.message}")
            return Pair(false, "исключение ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            file.delete()
            stage("6 копия удалена")
        }
    }

    companion object {
        private const val TAG = "OmodaInstall"
    }
}
