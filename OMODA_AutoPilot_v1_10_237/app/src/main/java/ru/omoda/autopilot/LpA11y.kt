package ru.omoda.autopilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Нажимает «Установить» в системном установщике через ACTION_CLICK. Работает только во время взвода. */
class LpA11y : AccessibilityService() {

    companion object {
        @Volatile var armedUntil = 0L
        @Volatile var clicks = 0
        @Volatile var lastInfo = ""
        @Volatile var connected = false
        @Volatile var instance: LpA11y? = null
        const val RUSTORE = "ru.vk.store"
        const val RUSTORE_DIALOG = "Установить это приложение"
        val PKGS = setOf("com.android.packageinstaller", "com.google.android.packageinstaller", "com.miui.packageinstaller")
        val TEXTS = setOf("Установить", "Обновить", "Install", "Update", "安装", "更新")
        val DENY = setOf("Отмена", "Cancel", "Не разрешать", "Deny", "Запретить")
        val ID_SUFFIXES = listOf("ok_button", "install_button", "done_button", "launch_button")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickMs = 0L
    private var retries = 0

    override fun onServiceConnected() {
        connected = true
        instance = this
        // Без этого флага поиск по id и дерево окон не работают
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (LpLauncher.armed(this)) return // Скрыто: работает только восстановление
        if (System.currentTimeMillis() > armedUntil) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in PKGS && pkg != RUSTORE) return
        retries = 0
        tryInstall()
    }

    override fun onInterrupt() {}

    /** Проверяет текущее окно сразу, не дожидаясь нового события. Вызывается при взводе. */
    fun kick() {
        if (LpLauncher.armed(this)) return // Скрыто: работает только восстановление
        handler.post { retries = 0; tryInstall() }
    }

    private fun hasText(node: AccessibilityNodeInfo, part: String): Boolean {
        if (node.text?.toString()?.contains(part) == true) return true
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (hasText(c, part)) return true
        }
        return false
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        instance = null
        // Не оставляем отложенные повторы после отключения службы
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    private fun tryInstall() {
        if (System.currentTimeMillis() > armedUntil) return
        if (System.currentTimeMillis() - lastClickMs < 1500) return
        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString()
        if (rootPkg !in PKGS && rootPkg != RUSTORE) return
        // В RuStore нажимаем только свой диалог подтверждения, не другие кнопки «Установить»
        if (rootPkg == RUSTORE && !hasText(root, RUSTORE_DIALOG)) {
            lastInfo = "RuStore: диалога подтверждения нет"
            return
        }

        // Кнопка «Отмена» есть, но заблокирована: диалог ещё не готов, ждём
        val neg = root.findAccessibilityNodeInfosByViewId("android:id/button2").firstOrNull()
        if (neg != null && !neg.isEnabled) {
            lastInfo = "ждём, Отмена заблокирована"
            scheduleRetry()
            return
        }

        val target = findPositive(root)
        if (target == null) {
            lastInfo = "кнопка не найдена"
            scheduleRetry()
            return
        }
        // Один клик. Повтор только через проверку окна, а не сразу по тому же узлу
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        lastClickMs = System.currentTimeMillis()
        if (ok) {
            clicks++
            lastInfo = "клик по «${target.text ?: "кнопке"}» в ${root.packageName}"
            SettingsRepository(applicationContext).addEvent("A11y: $lastInfo")
        } else {
            lastInfo = "клик не прошёл"
            scheduleRetry()
        }
    }

    /** До 15 повторов по 500 мс, потом стоп. */
    private fun scheduleRetry() {
        if (retries >= 15) return
        retries++
        handler.postDelayed({ tryInstall() }, 500)
    }

    private fun findPositive(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = root.packageName?.toString() ?: return null
        val ids = listOf("android:id/button1") + ID_SUFFIXES.map { "$pkg:id/$it" }
        for (id in ids) {
            for (n in root.findAccessibilityNodeInfosByViewId(id)) {
                val t = n.text?.toString()
                if (t != null && t in DENY) continue
                clickable(n)?.let { return it }
            }
        }
        return findByText(root)
    }

    private fun findByText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()
        if (text != null && text in TEXTS && text !in DENY && node.className == "android.widget.Button") {
            clickable(node)?.let { return it }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findByText(c)?.let { return it }
        }
        return null
    }

    /** Поднимается к кликабельному предку, не выше 6 уровней. */
    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth <= 6) {
            if (cur.isClickable && cur.isEnabled && cur.isVisibleToUser) return cur
            cur = cur.parent
            depth++
        }
        return null
    }
}
