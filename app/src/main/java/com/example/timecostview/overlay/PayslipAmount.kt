package com.example.timecostview.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.appcompat.widget.AppCompatTextView

class PayslipAmount(context: Context) : AppCompatTextView(context) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        val value = text?.toString().orEmpty()
        val dot = value.lastIndexOf('.')
        val styled = if(value.startsWith("¥") && dot > 0) android.text.SpannableString(value).apply {
            setSpan(android.text.style.RelativeSizeSpan(0.64f), dot, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else text
        super.setText(styled, type)
    }
    var cancelled = false
        set(value) { field = value; invalidate() }
    private val strike = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.example.timecostview.ui.Brand.red }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(cancelled) {
            strike.strokeWidth = resources.displayMetrics.density * 1.2f
            val y = baseline + paint.fontMetrics.ascent * .36f
            canvas.drawLine(compoundPaddingLeft.toFloat(), y,
                minOf(width - compoundPaddingRight.toFloat(), compoundPaddingLeft + (layout?.getLineWidth(0) ?: paint.measureText(text.toString()))), y, strike)
        }
    }
}
