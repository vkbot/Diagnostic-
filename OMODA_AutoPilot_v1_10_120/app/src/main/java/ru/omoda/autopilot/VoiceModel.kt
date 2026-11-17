package ru.omoda.autopilot

import android.content.Context
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

/** Модель Vosk: берём из APK, из Загрузок или скачиваем один раз. Возвращает папку модели. */
object VoiceModel {
    private const val NAME = "vosk-model-small-ru-0.22"
    private const val URL_STR = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val SIZE = 46236750L

    fun path(c: Context, progress: (String) -> Unit): String {
        val dest = File(c.filesDir, "vosk-model")
        if (dest.isDirectory) return dest.absolutePath
        val tmp = File(c.filesDir, "vosk-model.tmp")
        tmp.deleteRecursively()
        val external = File("/sdcard/Download/$NAME")
        if (c.assets.list(NAME)?.contains("am") == true) {
            progress("Копирую модель из приложения...")
            copyAssets(c, NAME, tmp)
        } else if (external.isDirectory) {
            progress("Копирую модель из Загрузок...")
            external.copyRecursively(tmp, overwrite = true)
        } else {
            val zip = File(c.filesDir, "vosk-model.zip")
            progress("Скачиваю модель (около 46 МБ), нужен Wi-Fi...")
            URL(URL_STR).openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
            if (zip.length() != SIZE) {
                zip.delete()
                throw IllegalStateException("архив скачан неполностью: ${zip.length()} байт")
            }
            progress("Распаковываю модель...")
            unzip(zip, tmp)
            zip.delete()
        }
        if (!tmp.isDirectory) throw IllegalStateException("модель не распаковалась")
        tmp.renameTo(dest)
        return dest.absolutePath
    }

    private fun copyAssets(c: Context, path: String, dest: File) {
        val children = c.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            c.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (child in children) copyAssets(c, "$path/$child", File(dest, child))
        }
    }

    /** Распаковывает архив, убирая верхнюю папку. Пути с ".." пропускаются. */
    private fun unzip(zip: File, target: File) {
        ZipInputStream(zip.inputStream()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val rel = entry.name.substringAfter('/', "")
                if (rel.isNotEmpty() && !rel.contains("..")) {
                    val f = File(target, rel)
                    if (entry.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { zin.copyTo(it) }
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
}
