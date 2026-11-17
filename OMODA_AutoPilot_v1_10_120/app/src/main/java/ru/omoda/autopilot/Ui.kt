package ru.omoda.autopilot

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// Палитра: тёмный фон, карточки, акцент голубой
val COLOR_BG: Int = Color.parseColor("#0B0F14")
val COLOR_CARD: Int = Color.parseColor("#161D26")
val COLOR_STROKE: Int = Color.parseColor("#24364A")
val COLOR_ACCENT: Int = Color.parseColor("#29D3FF")
val COLOR_TEXT: Int = Color.parseColor("#E8EEF5")

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.labelView(value: String, size: Float = 15f, topPadDp: Int = 0): TextView {
    val view = TextView(this)
    view.text = value
    view.textSize = size
    if (size >= 16f) {
        // заголовки: акцентный цвет и жирный шрифт
        view.setTextColor(COLOR_ACCENT)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    } else {
        view.setTextColor(COLOR_TEXT)
    }
    view.setPadding(0, dp(topPadDp), 0, dp(4))
    return view
}

/** Кнопка-карточка: скруглённый тёмный фон, тонкая рамка, подсветка при нажатии. */
fun Context.actionButton(caption: String, onClick: () -> Unit): Button {
    val button = Button(this)
    button.text = caption
    button.setAllCaps(false)
    button.textSize = 15f
    button.setTextColor(COLOR_TEXT)
    button.setPadding(dp(18), dp(14), dp(18), dp(14))
    val shape = GradientDrawable().apply {
        setColor(COLOR_CARD)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), COLOR_STROKE)
    }
    button.background = RippleDrawable(
        ColorStateList.valueOf(Color.argb(70, 41, 211, 255)), shape, null
    )
    button.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(10)) }
    button.setOnClickListener { onClick() }
    return button
}

/** Кнопка с иконкой слева. Иконка окрашивается в акцентный цвет. */
fun Context.actionButtonIcon(caption: String, iconRes: Int, onClick: () -> Unit): Button {
    val button = actionButton(caption, onClick)
    tinted(iconRes, COLOR_ACCENT)?.let { icon ->
        icon.setBounds(0, 0, dp(22), dp(22))
        button.setCompoundDrawables(icon, null, null, null)
        button.compoundDrawablePadding = dp(14)
    }
    button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    return button
}

/** Векторная иконка нужного цвета. Копия, чтобы не менять общий ресурс. */
fun Context.tinted(resId: Int, color: Int): Drawable? {
    val d = getDrawable(resId)?.mutate() ?: return null
    d.setTint(color)
    return d
}

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** Тёмная тема для готового экрана: фон и цвет текста во всех элементах. */
fun View.applyDarkTheme() {
    if (this is TextView && this !is Button) {
        setHintTextColor(Color.parseColor("#5F7187"))
        if (textSize < 16f * resources.displayMetrics.scaledDensity) setTextColor(COLOR_TEXT)
        else setTextColor(COLOR_ACCENT)
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).applyDarkTheme()
    }
}
