@file:Suppress("SetTextI18n")
package uk.co.simplyben.rawplus

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Window
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.TextView

object Ui {
    fun ink(context: Context) = context.getColor(R.color.ink)
    fun muted(context: Context) = context.getColor(R.color.muted)
    fun green(context: Context) = context.getColor(R.color.primary)
    fun paper(context: Context) = context.getColor(R.color.paper)
    fun surface(context: Context) = context.getColor(R.color.surface)
    fun secondary(context: Context) = context.getColor(R.color.secondary)
    fun onPrimary(context: Context) = context.getColor(R.color.on_primary)
    fun recording(context: Context) = context.getColor(R.color.recording)
    fun hint(context: Context) = context.getColor(R.color.hint)
    fun isDark(context: Context) = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Call again when a service recreates its input view after a theme change. */
    @Suppress("DEPRECATION") // Still colors the 3-button navigation background; gesture navigation is system-managed.
    fun applyWindowAppearance(window: Window, context: Context) {
        window.navigationBarColor = paper(context)
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (isDark(context)) 0 else mask, mask)
    }

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun background(color: Int, context: Context) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, 12).toFloat()
    }
    fun controlBackground(color: Int, context: Context) = RippleDrawable(
        ColorStateList.valueOf(context.getColor(R.color.control_highlight)),
        background(color, context), background(android.graphics.Color.WHITE, context)
    )
    fun iconButton(context: Context, icon: Int, label: String, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(ink(context))
        contentDescription = label
        tooltipText = label
        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        background = controlBackground(android.graphics.Color.TRANSPARENT, context)
        setOnClickListener { click() }
    }
    fun text(context: Context, value: String, size: Float = 15f) = TextView(context).apply {
        text = value; textSize = size; setTextColor(ink(context))
        setHintTextColor(hint(context))
    }
    fun button(context: Context, value: String, primary: Boolean = false, click: () -> Unit) = Button(context).apply {
        text = value; textSize = 14f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = dp(context, 44); minimumHeight = dp(context, 44)
        setPadding(dp(context, 6), 0, dp(context, 6), 0)
        setTextColor(if (primary) onPrimary(context) else ink(context))
        background = controlBackground(if (primary) green(context) else secondary(context), context)
        stateListAnimator = null
        setOnClickListener { click() }
    }
    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun heading(context: Context, value: String, size: Float = 22f) = text(context, value, size).apply {
        setTypeface(typeface, Typeface.BOLD)
    }
}
