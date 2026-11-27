package ru.omoda.autopilot

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Vector

/**
 * Обновление по SFTP. Номер версии берётся из имени файла: OMODA_AutoPilot-signed-<versionCode>-ГГГГММДД-ЧЧММ.apk.
 * Сравнение с установленной версией — до скачивания. Установка только по подтверждению.
 */
object UpdateChecker {
    private const val CFG_ASSET = "sftp_config.txt"
    private const val KEY_ASSET = "sftp_read_key.b64"
    private val NAME = Regex("""OMODA_AutoPilot-signed-(\d+)-\d{8}-\d{4}\.apk""")

    class Remote(val name: String, val versionCode: Int)

    private class Cfg(val host: String, val port: Int, val user: String, val knownHosts: String)

    private fun cfg(c: Context): Cfg {
        val text = c.assets.open(CFG_ASSET).use { it.readBytes() }.toString(Charsets.UTF_8)
        val m = text.lines().filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val knownHosts = String(Base64.decode(m.getValue("known_hosts_b64"), Base64.DEFAULT), Charsets.UTF_8).trim()
        return Cfg(m.getValue("host"), m.getValue("port").toInt(), m.getValue("user"), knownHosts)
    }

    /** Android 11 не проверяет Ed25519 своими средствами: ставим BouncyCastle первым. */
    private fun ensureEd25519() {
        val current = java.security.Security.getProvider("BC")
        if (current == null || current.javaClass.name != "org.bouncycastle.jce.provider.BouncyCastleProvider") {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        }
    }

    private fun connect(c: Context): Pair<Session, ChannelSftp> {
        ensureEd25519()
        val keyB64 = c.assets.open(KEY_ASSET).use { it.readBytes() }.toString(Charsets.US_ASCII).trim()
        var key = Base64.decode(keyB64, Base64.DEFAULT)
        if (!String(key, Charsets.US_ASCII).contains("PRIVATE KEY")) {
            key = Base64.decode(String(key, Charsets.US_ASCII).trim(), Base64.DEFAULT)
        }
        if (!String(key, Charsets.US_ASCII).contains("PRIVATE KEY")) {
            throw IllegalStateException("ключ не распознан: нужен приватный ключ OpenSSH")
        }
        val k = cfg(c)
        val jsch = JSch()
        jsch.setKnownHosts(ByteArrayInputStream(k.knownHosts.toByteArray()))
        try {
            jsch.addIdentity("omoda-read", key, null, null)
        } catch (e: Exception) {
            val text = String(key, Charsets.US_ASCII)
            val header = text.lines().firstOrNull()?.trim() ?: ""
            throw IllegalStateException(
                "${e.message}; заголовок='$header', строк=${text.lines().size}, CR=${text.contains('\r')}"
            )
        }
        val session = jsch.getSession(k.user, k.host, k.port)
        session.setConfig("server_host_key", "ssh-ed25519")
        session.connect(15_000)
        val ch = session.openChannel("sftp") as ChannelSftp
        ch.connect(10_000)
        return Pair(session, ch)
    }

    fun currentCode(c: Context): Int = c.packageManager.getPackageInfo(c.packageName, 0).versionCode

    /** Самая новая версия на сервере по номеру в имени. Null — подходящих файлов нет. */
    fun latestRemote(c: Context): Remote? {
        val (session, ch) = connect(c)
        try {
            var best: Remote? = null
            @Suppress("UNCHECKED_CAST")
            val entries = ch.ls(".") as Vector<ChannelSftp.LsEntry>
            for (e in entries) {
                val m = NAME.matchEntire(e.filename) ?: continue
                val r = Remote(e.filename, m.groupValues[1].toInt())
                if (best == null || r.versionCode > best.versionCode) best = r
            }
            return best
        } finally {
            ch.disconnect()
            session.disconnect()
        }
    }

    /** Сравнение ДО скачивания: версия на сервере больше установленной. */
    fun isNewer(c: Context, r: Remote): Boolean = r.versionCode > currentCode(c)

    /** Скачивание с прогрессом: onProgress получает строку для показа. */
    fun download(c: Context, r: Remote, onProgress: (String) -> Unit): File {
        val file = File(c.cacheDir, "update.apk")
        val (session, ch) = connect(c)
        try {
            val total = ch.stat(r.name).size
            val monitor = object : SftpProgressMonitor {
                private var done = 0L
                override fun init(op: Int, src: String?, dest: String?, max: Long) {}
                override fun count(count: Long): Boolean {
                    done += count
                    onProgress(if (total > 0) "скачивание ${done * 100 / total}% (${done / 1024} КБ из ${total / 1024})"
                               else "скачивание ${done / 1024} КБ")
                    return true
                }
                override fun end() {
                    onProgress("скачано ${done / 1024} КБ, проверка...")
                }
            }
            ch.get(r.name, file.absolutePath, monitor)
        } finally {
            ch.disconnect()
            session.disconnect()
        }
        return file
    }

    /** Проверка пакета и установка через pm install. Перед установкой номер версии сверяется с именем файла. */
    fun install(c: Context, file: File, r: Remote, onProgress: (String) -> Unit = {}): String {
        val pi = c.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (pi == null || pi.packageName != c.packageName || pi.versionCode != r.versionCode || pi.versionCode <= currentCode(c)) {
            file.delete()
            return "пакет не совпадает с приложением или версия не новее"
        }
        InstallNotice.expect(c, r.name, pi.versionCode)
        // APK передаётся через stdin (см. PmInstaller): установщик не открывает файл в каталоге приложения
        return PmInstaller.streamInstall(file, onProgress)
    }
}
