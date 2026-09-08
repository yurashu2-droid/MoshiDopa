package com.example.timecostview.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import com.example.timecostview.domain.SessionReceiptContent
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

object SessionReceiptPalette {
    const val world = 0xffe9e5dc.toInt()
    const val paper = 0xfffffbef.toInt()
    const val ink = 0xff25231f.toInt()
    const val muted = 0xff716d64.toInt()
    const val vermilion = 0xffb33f32.toInt()
    const val rule = 0xffd8d0bf.toInt()
}

/** A single drawing definition used by the in-app preview, overlay, and PNG export. */
object SessionReceiptArt {
    const val BASE_WIDTH = 1000f
    const val BASE_HEIGHT = 1390f
    const val SHARE_WIDTH = 1080
    const val SHARE_HEIGHT = 1500

    private fun phase(progress: Float, start: Float, end: Float): Float =
        ((progress - start) / (end - start)).coerceIn(0f, 1f)

    private fun wrap(paint: Paint, value: String, maxWidth: Float, maxLines: Int): List<String> {
        if(value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        value.replace("\r\n", "\n").split('\n').forEach { paragraph ->
            var line = ""
            paragraph.forEach { character ->
                val candidate = line + character
                if(line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                    result += line
                    line = character.toString()
                } else line = candidate
            }
            if(line.isNotEmpty()) result += line
        }
        if(result.size <= maxLines) return result
        val clipped = result.take(maxLines).toMutableList()
        var last = clipped.last()
        while(last.isNotEmpty() && paint.measureText("$last…") > maxWidth) last = last.dropLast(1)
        clipped[clipped.lastIndex] = "$last…"
        return clipped
    }

    private fun qr(value: String, size: Int): Bitmap? {
        if(value.isBlank()) return null
        return runCatching {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.MARGIN, 4)
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
            }
            val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size, hints)
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(size * size)
                for(y in 0 until size) for(x in 0 until size) {
                    pixels[y * size + x] = if(matrix[x, y]) SessionReceiptPalette.ink else Color.WHITE
                }
                setPixels(pixels, 0, size, 0, 0, size, size)
            }
        }.getOrNull()
    }

    fun render(content: SessionReceiptContent, hideTime: Boolean): Bitmap =
        Bitmap.createBitmap(SHARE_WIDTH, SHARE_HEIGHT, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawColor(SessionReceiptPalette.world)
            val canvas = Canvas(bitmap)
            val scale = SHARE_WIDTH / BASE_WIDTH
            canvas.save()
            canvas.scale(scale, scale)
            draw(canvas, content, hideTime, 1f, 0f)
            canvas.restore()
        }

    fun draw(canvas: Canvas, content: SessionReceiptContent, hideTime: Boolean, progress: Float, edgeFlex: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        val left = 82f
        val right = BASE_WIDTH - 82f

        paint.color = 0x24000000
        canvas.drawRoundRect(RectF(18f, 24f, BASE_WIDTH - 10f, BASE_HEIGHT - 3f), 14f, 14f, paint)
        paint.color = SessionReceiptPalette.paper
        val paperPath = Path().apply {
            moveTo(10f, 8f); lineTo(BASE_WIDTH - 18f, 8f); lineTo(BASE_WIDTH - 18f, BASE_HEIGHT - 22f)
            var x = BASE_WIDTH - 18f
            var up = false
            while(x > 10f) {
                val next = (x - 18f).coerceAtLeast(10f)
                val tooth = if(up) 10f else 21f + edgeFlex * 10f
                lineTo(next, BASE_HEIGHT - tooth)
                up = !up; x = next
            }
            close()
        }
        canvas.drawPath(paperPath, paint)

        // Fine deterministic fibers: visible up close, quiet enough behind text.
        paint.strokeWidth = 1f
        for(i in 0 until 92) {
            val x = 25f + ((i * 73) % 930)
            val y = 28f + ((i * 137) % 1300)
            paint.color = if(i % 3 == 0) 0x0e8a7658 else 0x0bffffff
            canvas.drawLine(x, y, x + 11f + i % 17, y + (i % 5 - 2), paint)
        }

        fun setType(size: Float, bold: Boolean = false, mono: Boolean = false) {
            paint.textSize = size
            paint.typeface = Typeface.create(if(mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, if(bold) Typeface.BOLD else Typeface.NORMAL)
        }
        fun text(value: String, x: Float, baseline: Float, size: Float, color: Int = SessionReceiptPalette.ink,
                 bold: Boolean = false, mono: Boolean = false, alpha: Float = 1f) {
            setType(size, bold, mono)
            paint.color = color
            paint.alpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt()
            canvas.drawText(value, x, baseline + (1f - alpha) * 9f, paint)
            paint.alpha = 255
        }
        fun centered(value: String, baseline: Float, size: Float, color: Int = SessionReceiptPalette.ink,
                     bold: Boolean = false, mono: Boolean = false, alpha: Float = 1f) {
            setType(size, bold, mono)
            text(value, (BASE_WIDTH - paint.measureText(value)) / 2f, baseline, size, color, bold, mono, alpha)
        }
        fun wrapped(value: String, x: Float, baseline: Float, size: Float, width: Float, maxLines: Int,
                    color: Int, lineHeight: Float, bold: Boolean = false, alpha: Float = 1f): Float {
            setType(size, bold)
            val lines = wrap(paint, value, width, maxLines)
            lines.forEachIndexed { index, line -> text(line, x, baseline + index * lineHeight, size, color, bold, alpha = alpha) }
            return baseline + max(0, lines.size - 1) * lineHeight
        }

        val identity = phase(progress, .18f, .36f)
        val amountPhase = phase(progress, .34f, .55f)
        val detail = phase(progress, .52f, .70f)
        val verdict = phase(progress, .70f, .88f)
        val footer = phase(progress, .84f, 1f)

        centered(content.heading, 104f, 27f, SessionReceiptPalette.muted, bold = true, alpha = identity)
        paint.color = SessionReceiptPalette.rule; paint.alpha = (255 * identity).roundToInt()
        canvas.drawLine(left, 136f, right, 136f, paint); paint.alpha = 255
        wrapped(content.subject, left, 205f, 49f, right - left, 2, SessionReceiptPalette.ink, 57f, true, identity)

        centered(content.amountLabel, 350f, 27f, SessionReceiptPalette.muted, bold = true, alpha = amountPhase)
        setType(102f, bold = true, mono = true)
        val amountSize = if(paint.measureText(content.amount) <= right - left) 102f else 72f
        centered(content.amount, 472f, amountSize, SessionReceiptPalette.ink, bold = true, mono = true, alpha = amountPhase)

        if(!hideTime) {
            centered(content.duration, 555f, 29f, SessionReceiptPalette.ink, alpha = detail)
            centered(content.rate, 608f, 24f, SessionReceiptPalette.muted, alpha = detail)
        }

        if(content.spend) {
            setType(amountSize, bold = true, mono = true)
            val amountWidth = paint.measureText(content.amount)
            val amountLeft = (BASE_WIDTH - amountWidth) / 2f
            paint.color = SessionReceiptPalette.vermilion
            paint.strokeWidth = 3f
            paint.alpha = (255 * verdict).roundToInt()
            val strikeY = 435f
            canvas.drawLine(amountLeft - 7f, strikeY, amountLeft + amountWidth + 7f, strikeY - 3f, paint)
            paint.alpha = 255
            canvas.save()
            canvas.rotate(-2.4f, BASE_WIDTH / 2f, 710f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = SessionReceiptPalette.vermilion
            paint.alpha = (225 * verdict).roundToInt()
            canvas.drawRoundRect(RectF(225f, 658f, 775f, 752f), 8f, 8f, paint)
            paint.style = Paint.Style.FILL; paint.alpha = 255
            centered("なお、振込はありません", 720f, 35f, SessionReceiptPalette.vermilion, bold = true, alpha = verdict)
            canvas.restore()
        } else if(content.note.isNotBlank()) {
            text("制作メモ", left, 696f, 23f, SessionReceiptPalette.muted, bold = true, alpha = verdict)
            wrapped(content.note, left, 742f, 28f, right - left, 4, SessionReceiptPalette.ink, 39f, alpha = verdict)
        }

        val issueY = if(content.spend) 842f else 902f
        text(content.issuedAt, left, issueY, 23f, SessionReceiptPalette.muted, alpha = footer)
        paint.color = SessionReceiptPalette.rule; paint.alpha = (255 * footer).roundToInt()
        canvas.drawLine(left, issueY + 38f, right, issueY + 38f, paint); paint.alpha = 255

        val brandY = issueY + 106f
        text("もしドパ", left, brandY, 39f, SessionReceiptPalette.ink, bold = true, alpha = footer)
        wrapped(content.brandSubtitle, left, brandY + 43f, 23f,
            if(content.introductionUrl.isBlank()) right - left else 560f, 2,
            SessionReceiptPalette.muted, 31f, alpha = footer)

        if(content.introductionUrl.isNotBlank() && footer > .01f) {
            qr(content.introductionUrl, 205)?.let { bitmap ->
                paint.alpha = (255 * footer).roundToInt()
                canvas.drawBitmap(bitmap, null, RectF(705f, brandY - 57f, 885f, brandY + 123f), paint)
                paint.alpha = 255
                setType(20f)
                val qrLabel = "アプリはこちら"
                text(qrLabel, 795f - paint.measureText(qrLabel) / 2f, brandY + 158f, 20f,
                    SessionReceiptPalette.muted, alpha = footer)
                bitmap.recycle()
            }
        }

        val disclaimerY = BASE_HEIGHT - 106f
        wrapped(content.disclaimer, left, disclaimerY, 21f, right - left, 3,
            SessionReceiptPalette.muted, 29f, alpha = footer)
    }
}

class SessionReceiptPaperView(context: Context) : View(context) {
    var content: SessionReceiptContent? = null
        set(value) {
            field = value
            contentDescription = value?.let {
                "${it.heading}。${it.subject}。${it.amountLabel} ${it.amount}。${it.duration}。${it.disclaimer}"
            }
            requestLayout(); invalidate()
        }
    var hideTime: Boolean = true
        set(value) { field = value; invalidate() }
    var revealProgress: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var edgeFlex: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val height = ceil(width * SessionReceiptArt.BASE_HEIGHT / SessionReceiptArt.BASE_WIDTH).toDouble().toInt()
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = content ?: return
        val scale = width / SessionReceiptArt.BASE_WIDTH
        canvas.save()
        canvas.scale(scale, scale)
        SessionReceiptArt.draw(canvas, value, hideTime, revealProgress, edgeFlex)
        canvas.restore()
    }
}
