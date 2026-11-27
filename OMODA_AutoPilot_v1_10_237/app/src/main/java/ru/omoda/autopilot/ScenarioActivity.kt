package ru.omoda.autopilot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Список профилей: создать, включить/выключить, изменить, проверить, удалить. */
class ScenarioActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply { text = "Сценарии климата"; textSize = 22f })
        root.addView(button("+ Новый профиль") { edit(null) })
        val delayBtn = button("Задержка при запуске приложения: ${Scenarios.launchDelaySec(this)} с") {}
        delayBtn.setOnClickListener {
            val steps = listOf(0, 30, 60, 90, 120, 180)
            val next = steps[(steps.indexOf(Scenarios.launchDelaySec(this)).let { if (it < 0) 0 else it } + 1) % steps.size]
            Scenarios.setLaunchDelaySec(this, next)
            delayBtn.text = "Задержка при запуске приложения: $next с"
        }
        root.addView(delayBtn)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        status = TextView(this).apply { textSize = 14f }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
        redraw()
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            setOnClickListener { onClick() }
        }

    private fun redraw() {
        list.removeAllViews()
        for (pr in Scenarios.all(this)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(this).apply { textSize = 16f; text = describe(pr) })
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(button(if (pr.enabled) "ВЫКЛ" else "ВКЛ") { toggle(pr.id) })
            btns.addView(button("Изменить") { edit(pr) })
            btns.addView(button("Проверить") { check(pr) })
            btns.addView(button("Удалить") { delete(pr.id) })
            row.addView(btns)
            list.addView(row)
        }
        status.text = "Последний результат: " + Scenarios.last(this)
    }

    private fun describe(pr: Scenarios.Profile): String {
        val acts = listOfNotNull(
            if (pr.seats) "сиденья, уровень ${pr.seatLevel}" else null,
            if (pr.hi) "HI" else null,
            if (pr.front) "лобовое" else null,
            if (pr.rear) "заднее" else null,
            if (pr.steer) "руль" else null
        )
        return "${pr.name} [${if (pr.enabled) "ВКЛ" else "выкл"}]\n" +
            "условие: улица ${pr.op} ${pr.threshold} °C\n" +
            "действия: ${if (acts.isEmpty()) "нет" else acts.joinToString(", ")}"
    }

    private fun toggle(id: Long) {
        if (LpLauncher.armed(this)) {
            toast("В скрытом режиме сценарии не работают")
            return
        }
        val all = Scenarios.all(this)
        all.find { it.id == id }?.let { it.enabled = !it.enabled }
        Scenarios.save(this, all)
        syncService(all)
        redraw()
    }

    private fun delete(id: Long) {
        val all = Scenarios.all(this)
        all.removeAll { it.id == id }
        Scenarios.save(this, all)
        syncService(all)
        redraw()
    }

    /** Служба работает, пока включён хотя бы один профиль. */
    private fun syncService(all: List<Scenarios.Profile>) {
        val intent = Intent(this, ScenarioService::class.java)
        if (all.any { it.enabled }) {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } else {
            stopService(intent)
        }
    }

    /** Ручной запуск действий профиля, без проверки условия. */
    private fun check(pr: Scenarios.Profile) {
        val intent = Intent(this, ScenarioService::class.java)
            .setAction(ScenarioService.ACTION_APPLY)
            .putExtra(ScenarioService.EXTRA_ID, pr.id)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        toast("Запущено: ${pr.name}. Результат появится ниже")
    }

    /** Создание или изменение профиля: название, условие, порог, действия. */
    private class CondState(val src: Array<String>, val op: Array<String>, val thr: EditText)

    private fun srcText(s: String) = if (s == "in") "датчик: салон" else "датчик: улица"

    private fun logicText(l: String) = if (l == "or") "логика: ИЛИ (любое)" else "логика: И (все)"

    private fun edit(existing: Scenarios.Profile?) {
        val name = EditText(this).apply { hint = "Название"; setText(existing?.name ?: "Новый профиль") }
        val logic = arrayOf(existing?.logic ?: "and")
        val logicBtn = Button(this).apply { text = logicText(logic[0]) }
        logicBtn.setOnClickListener {
            logic[0] = if (logic[0] == "or") "and" else "or"
            logicBtn.text = logicText(logic[0])
        }

        val condBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val condRows = mutableListOf<Pair<LinearLayout, CondState>>()

        fun addCond(c: Scenarios.Cond?) {
            val src = arrayOf(c?.src ?: "out")
            val op = arrayOf(c?.op ?: "<")
            val srcB = Button(this).apply { text = srcText(src[0]) }
            srcB.setOnClickListener {
                src[0] = if (src[0] == "in") "out" else "in"
                srcB.text = srcText(src[0])
            }
            val opB = Button(this).apply { text = "знак: ${op[0]}" }
            opB.setOnClickListener {
                op[0] = if (op[0] == "<") ">" else "<"
                opB.text = "знак: ${op[0]}"
            }
            val thr = EditText(this).apply {
                hint = "порог, °C"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText((c?.thr ?: 10).toString())
            }
            val del = Button(this).apply { text = "удалить условие" }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(srcB); addView(opB); addView(thr); addView(del)
            }
            condRows.add(row to CondState(src, op, thr))
            condBox.addView(row)
            del.setOnClickListener {
                condBox.removeView(row)
                condRows.removeAll { it.first === row }
            }
        }

        val existingConds = existing?.conds
        if (existingConds.isNullOrEmpty()) addCond(null) else existingConds.forEach { addCond(it) }
        val addBtn = Button(this).apply { text = "+ условие" }
        addBtn.setOnClickListener { addCond(null) }

        val seats = CheckBox(this).apply { text = "подогрев сидений"; isChecked = existing?.seats ?: true }
        val lvl = EditText(this).apply {
            hint = "уровень сидений 1-3"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.seatLevel ?: 3).toString())
        }
        val hi = CheckBox(this).apply { text = "HI 30,5"; isChecked = existing?.hi ?: true }
        val front = CheckBox(this).apply { text = "лобовое (обдув, не подтверждено на машине)"; isChecked = existing?.front ?: false }
        val rear = CheckBox(this).apply { text = "заднее стекло"; isChecked = existing?.rear ?: false }
        val steer = CheckBox(this).apply { text = "обогрев руля (только ВКЛ)"; isChecked = existing?.steer ?: false }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            addView(name); addView(logicBtn); addView(condBox); addView(addBtn)
            addView(seats); addView(lvl); addView(hi); addView(front); addView(rear); addView(steer)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Новый профиль" else "Изменить профиль")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Сохранить") { _, _ ->
                val all = Scenarios.all(this)
                val target = all.find { it.id == existing?.id }
                val level = (lvl.text.toString().toIntOrNull() ?: 3).coerceIn(1, 3)
                val newConds = condRows.map { (_, st) ->
                    Scenarios.Cond(st.src[0], st.op[0], st.thr.text.toString().toIntOrNull()?.coerceIn(-30, 40) ?: 10)
                }.toMutableList()
                if (newConds.isEmpty()) newConds.add(Scenarios.Cond("out", "<", 10))
                val first = newConds[0]
                if (target == null) {
                    val pr = Scenarios.Profile(
                        Scenarios.newId(), name.text.toString().ifBlank { "Профиль" }, false,
                        first.op, first.thr,
                        seats.isChecked, level, hi.isChecked, front.isChecked, rear.isChecked, steer.isChecked,
                        first.src
                    )
                    pr.conds = newConds
                    pr.logic = logic[0]
                    all.add(pr)
                } else {
                    target.name = name.text.toString().ifBlank { "Профиль" }
                    target.op = first.op
                    target.threshold = first.thr
                    target.src = first.src
                    target.conds = newConds
                    target.logic = logic[0]
                    target.seats = seats.isChecked
                    target.seatLevel = level
                    target.hi = hi.isChecked
                    target.front = front.isChecked
                    target.rear = rear.isChecked
                    target.steer = steer.isChecked
                }
                Scenarios.save(this, all)
                redraw()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
