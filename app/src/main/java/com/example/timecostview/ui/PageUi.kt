package com.example.timecostview.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.*
import com.example.timecostview.overlay.PayslipAmount

/** Shared visual primitives, with no navigation or data access. */
internal class PageUi(private val viewContext: Context, private val container: LinearLayout) {
    private val textColor = Brand.text
    private val secondaryColor = Brand.muted
    private val highlightColor = Brand.lime
    private fun px(value: Int) = (value * viewContext.resources.displayMetrics.density).toInt()
    fun text(value: String, size: Float = 15f, color: Int = textColor, bold: Boolean = false) = TextView(viewContext).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(px(4).toFloat(), 1f)
        if(bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    fun space(height: Int = 20) { container.addView(View(viewContext), LinearLayout.LayoutParams(1, px(height))) }
    fun label(value: String) { container.addView(text(value, 12f, secondaryColor, true)) }
    fun paragraph(value: String) { container.addView(text(value, 14f, secondaryColor)) }
    fun button(value: String, primary: Boolean = false, action: () -> Unit): Button = Button(viewContext).apply {
        text = value; isAllCaps = false; textSize = 16f; setTextColor(if(primary) Brand.background else textColor)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val shape = GradientDrawable().apply { setColor(if(primary) highlightColor else Brand.surface); cornerRadius = px(24).toFloat() }
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if(primary) 0x33000000 else 0x33ffffff), shape, null)
        minHeight = px(60); setPadding(px(20), px(14), px(20), px(14))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) }
    }
    fun field(hintValue: String, value: String, numeric: Boolean = false) = EditText(viewContext).apply {
        hint = hintValue; setText(value); textSize = 22f; setTextColor(textColor); setHintTextColor(secondaryColor)
        inputType = if(numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        setSingleLine(); minHeight = px(60)
        setPadding(px(18), px(16), px(18), px(16))
        background = GradientDrawable().apply { setColor(Brand.surface); cornerRadius = px(20).toFloat(); setStroke(px(1), 0xff515646.toInt()) }
        filters = arrayOf(android.text.InputFilter.LengthFilter(if(numeric) 14 else 60))
    }
    fun bigMoney(value: String): PayslipAmount = PayslipAmount(viewContext).apply {
        text = value; textSize = 60f; setTextColor(textColor)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setSingleLine(); androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 22, 64, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        fontFeatureSettings = "tnum"; minHeight = px(105)
    }
    fun line() { container.addView(View(viewContext).apply { setBackgroundColor(0xff3b3f32.toInt()) }, LinearLayout.LayoutParams(-1, px(1)).apply { topMargin = px(24); bottomMargin = px(24) }) }
}
