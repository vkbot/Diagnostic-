package ru.omoda.autopilot

import java.io.File

/** Установка APK через pm install -S: байты идут в stdin, установщику путь к файлу не нужен. */
object PmInstaller {
    fun streamInstall(file: File, onProgress: (String) -> Unit): String {
        val total = file.length()
        val p = ProcessBuilder("sh", "-c", "pm install -r -S $total").redirectErrorStream(true).start()
        var done = 0L
        var lastShown = -1L
        onProgress("передаю APK в установщик: 0%")
        file.inputStream().use { input ->
            p.outputStream.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val percent = if (total > 0) done * 100 / total else 100
                    // Обновляем экран раз в 10 %, а не на каждый кусок
                    if (percent / 10 != lastShown / 10) {
                        lastShown = percent
                        onProgress("передаю APK в установщик: $percent%")
                    }
                }
            }
        }
        onProgress("установщик обрабатывает пакет, подождите...")
        val text = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return text.take(300).ifEmpty { "код возврата ${p.exitValue()}, вывода нет" }
    }
}
