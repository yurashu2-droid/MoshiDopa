package com.example.timecostview.share

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.example.timecostview.domain.Cost
import com.example.timecostview.domain.Record
import com.example.timecostview.domain.Statement
import com.example.timecostview.domain.StatementBuilder
import com.example.timecostview.domain.StatementLine
import com.example.timecostview.domain.StatementPeriod
import com.example.timecostview.ui.Brand
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlin.math.round

/** Renders the share image. The returned bitmap is owned by the caller. */
object ReceiptRenderer {
    const val IMAGE_WIDTH = 1080
    const val IMAGE_HEIGHT = 1350

    private const val LEFT = 88f
    private const val RIGHT = 992f
    private const val DATE_X = LEFT
    private const val LABEL_X = 242f
    private const val AMOUNT_RIGHT = RIGHT
    private const val LABEL_RIGHT = 724f
    private const val PAPER = 0xfffffcf4.toInt()
    private const val RULE = 0xffc9c7bc.toInt()
    private const val MUTED = 0xff66685e.toInt()
    private const val SOFT = 0xff8b8d82.toInt()

    private data class DisplayLine(val label: String, val amount: Double)

    fun share(activity: Activity, record: Record, hideTime: Boolean) {
        share(activity, StatementBuilder.fromRecord(record), hideTime, "time-cost-${record.id}")
    }

    fun share(activity: Activity, statement: Statement, hideTime: Boolean) {
        val key = "statement-${statement.start}-${statement.amount.toBits()}-${statement.period.name.lowercase(Locale.US)}"
        share(activity, statement, hideTime, key)
    }

    /**
     * Creates the same bitmap that [share] sends to another app.
     * No file is written and the caller must recycle the bitmap when finished.
     */
    fun render(statement: Statement, hideTime: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            strokeCap = Paint.Cap.SQUARE
        }

        canvas.drawColor(PAPER)
        paint.style = Paint.Style.STROKE
        paint.color = 0xffe1ded2.toInt()
        paint.strokeWidth = 2f
        canvas.drawRoundRect(RectF(42f, 34f, 1038f, 1316f), 18f, 18f, paint)
        paint.style = Paint.Style.FILL

        fun typeface(size: Float, mono: Boolean = false, bold: Boolean = false) {
            paint.textSize = size
            paint.typeface = if(mono) {
                Typeface.create(Typeface.MONOSPACE, if(bold) Typeface.BOLD else Typeface.NORMAL)
            } else {
                Typeface.create("sans-serif", if(bold) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        fun drawText(value: String, x: Float, baseline: Float, size: Float,
            color: Int = Brand.ink, mono: Boolean = false, bold: Boolean = false) {
            typeface(size, mono, bold)
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawText(value, x, baseline, paint)
        }

        fun drawRight(value: String, right: Float, baseline: Float, size: Float,
            color: Int = Brand.ink, mono: Boolean = false, bold: Boolean = false) {
            typeface(size, mono, bold)
            drawText(value, right - paint.measureText(value), baseline, size, color, mono, bold)
        }

        fun wrap(value: String, size: Float, maxWidth: Float, mono: Boolean = false,
            bold: Boolean = false, maxLines: Int = Int.MAX_VALUE): List<String> {
            if(value.isBlank() || maxLines <= 0) return emptyList()
            typeface(size, mono, bold)
            val result = mutableListOf<String>()
            value.replace("\r\n", "\n").split('\n').forEach { paragraph ->
                var current = ""
                paragraph.forEach { char ->
                    val candidate = current + char
                    if(current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                        result += current
                        current = char.toString()
                    } else {
                        current = candidate
                    }
                }
                if(current.isNotEmpty() || paragraph.isEmpty()) result += current
            }
            if(result.size <= maxLines) return result
            val clipped = result.take(maxLines).toMutableList()
            var last = clipped.lastOrNull().orEmpty()
            while(last.isNotEmpty() && paint.measureText("$last…") > maxWidth) last = last.dropLast(1)
            clipped[clipped.lastIndex] = "$last…"
            return clipped
        }

        fun drawWrapped(value: String, x: Float, baseline: Float, size: Float, maxWidth: Float,
            color: Int = Brand.ink, lineHeight: Float = size * 1.28f,
            mono: Boolean = false, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE): Float {
            val lines = wrap(value, size, maxWidth, mono, bold, maxLines)
            lines.forEachIndexed { index, line ->
                drawText(line, x, baseline + index * lineHeight, size, color, mono, bold)
            }
            return if(lines.isEmpty()) baseline else baseline + (lines.size - 1) * lineHeight
        }

        fun rule(y: Float, color: Int = RULE, width: Float = 1.5f) {
            paint.style = Paint.Style.STROKE
            paint.color = color
            paint.strokeWidth = width
            canvas.drawLine(LEFT, y, RIGHT, y, paint)
            paint.style = Paint.Style.FILL
        }

        fun fullDateLabel(): String {
            val source = statement.periodLabel.ifBlank { statement.rangeLabel }
            val date = source.substringBefore('（').substringBefore(" ").trim()
            return date.ifBlank { "—" }
        }

        fun rowDateLabel(fullDate: String): String {
            val monthDay = Regex("(?:\\d{4}年)?(\\d{1,2})月(\\d{1,2})日").find(fullDate)
            return when {
                statement.period == StatementPeriod.MONTH -> {
                    Regex("(?:\\d{4}年)?(\\d{1,2})月").find(fullDate)?.let { "${it.groupValues[1]}月集計" } ?: fullDate
                }
                monthDay != null -> "${monthDay.groupValues[1]}/${monthDay.groupValues[2]}"
                else -> fullDate
            }
        }

        fun amountText(value: Double, integerYen: Boolean, signed: Boolean): String {
            val body = if(integerYen) {
                String.format(Locale.JAPAN, "¥%,d", round(value).toLong())
            } else {
                Cost.money(value)
            }
            return if(signed) "− $body" else body
        }

        fun fittedMoneySize(value: String, preferred: Float, minimum: Float, maxWidth: Float): Float {
            typeface(preferred, mono = true, bold = true)
            val measured = paint.measureText(value)
            return if(measured <= maxWidth) preferred
            else (preferred * maxWidth / measured).coerceAtLeast(minimum)
        }

        fun roundedLines(): Pair<List<DisplayLine>, Long> {
            val source = statement.lines.sortedByDescending { it.amount }
            val selected = if(source.size <= 5) {
                source
            } else {
                val top = source.take(4)
                top + StatementLine("その他 ${source.size - 4}件", statement.amount - top.sumOf { it.amount }, 0L)
            }
            val integerYen = statement.period == StatementPeriod.DAY || statement.period == StatementPeriod.MONTH
            if(!integerYen) {
                return selected.map { DisplayLine(it.label.ifBlank { "名称なし" }, it.amount) } to 0L
            }
            val target = round(statement.amount).toLong()
            val floors = selected.map { floor(it.amount.coerceAtLeast(0.0)).toLong() }
            val values = floors.toMutableList()
            var remainder = (target - values.sum()).coerceAtLeast(0L)
            val largestRemainders = selected.indices.sortedByDescending { index ->
                selected[index].amount.coerceAtLeast(0.0) - floors[index]
            }
            var cursor = 0
            while(remainder > 0L && largestRemainders.isNotEmpty()) {
                values[largestRemainders[cursor % largestRemainders.size]] += 1L
                remainder -= 1L
                cursor += 1
            }
            var deficit = (values.sum() - target).coerceAtLeast(0L)
            largestRemainders.asReversed().forEach { index ->
                while(deficit > 0L && values[index] > 0L) {
                    values[index] -= 1L
                    deficit -= 1L
                }
            }
            return selected.mapIndexed { index, line ->
                DisplayLine(line.label.ifBlank { "名称なし" }, values.getOrElse(index) { 0L }.toDouble())
            } to target
        }

        val integerYen = statement.period == StatementPeriod.DAY || statement.period == StatementPeriod.MONTH
        val (lines, roundedTotal) = roundedLines()
        val total = if(integerYen) roundedTotal.toDouble() else statement.amount
        val date = fullDateLabel()
        val rowDate = rowDateLabel(date)

        drawText("もしドパ", LEFT, 86f, 34f, Brand.ink, bold = true)
        drawRight("TIME COST  /  明細", RIGHT, 84f, 21f, MUTED, bold = true)
        val title = statement.title.ifBlank { if(statement.spend) "もしも給与明細" else "自己投資明細" }
        drawWrapped(title, LEFT, 152f, 42f, RIGHT - LEFT, Brand.ink, lineHeight = 43f, bold = true, maxLines = 1)
        drawText(if(statement.spend) "働いていたら得られた金額" else "制作 / 自己投資の時間明細", LEFT, 190f, 25f, MUTED)
        drawWrapped(
            statement.rangeLabel.trim().ifBlank { date },
            LEFT, 229f, 26f, if(hideTime) RIGHT - LEFT else 640f, Brand.ink,
            lineHeight = 29f, maxLines = 2,
        )
        if(!hideTime) drawRight("計測 ${Cost.time(statement.duration)}", RIGHT, 229f, 23f, MUTED, mono = true)
        rule(266f, 0xffaaa99f.toInt(), 2f)

        drawText("日付", DATE_X, 307f, 22f, MUTED, bold = true)
        drawText("摘要 / アプリ", LABEL_X, 307f, 22f, MUTED, bold = true)
        drawRight("金額", AMOUNT_RIGHT, 307f, 22f, MUTED, bold = true)
        rule(325f)

        var y = 365f
        lines.forEach { line ->
            val amount = amountText(line.amount, integerYen, statement.spend)
            val amountSize = fittedMoneySize(amount, 29f, 18f, RIGHT - LABEL_X - 24f - 180f)
            typeface(amountSize, mono = true, bold = true)
            val labelWidth = (AMOUNT_RIGHT - paint.measureText(amount) - 24f - LABEL_X).coerceAtLeast(130f)
            val labelLines = wrap(line.label, 29f, labelWidth, bold = true, maxLines = 2)
            val rowHeight = (labelLines.size * 34f + 25f).coerceAtLeast(62f)
            labelLines.forEachIndexed { index, text ->
                drawText(text, LABEL_X, y + index * 34f, 29f, Brand.ink, bold = true)
            }
            drawText(rowDate, DATE_X, y, 21f, MUTED)
            drawRight(amount, AMOUNT_RIGHT, y, amountSize, Brand.ink, mono = true, bold = true)
            rule(y + (labelLines.size - 1).coerceAtLeast(0) * 34f + 16f, 0xffdedbd0.toInt(), 1f)
            y += rowHeight
        }

        if(lines.isEmpty()) {
            drawText("明細なし", LABEL_X, y, 27f, SOFT)
            drawText(rowDate, DATE_X, y, 21f, MUTED)
            rule(y + 35f, 0xffdedbd0.toInt(), 1f)
            y += 60f
        }

        y += 16f
        rule(y, 0xffaaa99f.toInt(), 2f)
        val totalBaseline = y + 62f
        drawText("合計", LEFT, totalBaseline, 28f, Brand.ink, bold = true)
        val totalText = amountText(total, integerYen, statement.spend)
        val totalSize = fittedMoneySize(totalText, 49f, 32f, RIGHT - LEFT - 120f)
        drawRight(totalText, AMOUNT_RIGHT, totalBaseline, totalSize, Brand.ink, mono = true, bold = true)
        if(statement.spend) {
            typeface(totalSize, mono = true, bold = true)
            val totalLeft = AMOUNT_RIGHT - paint.measureText(totalText)
            paint.color = Brand.red
            paint.strokeWidth = 3f
            val strikeY = totalBaseline - totalSize * 0.37f
            canvas.drawLine(totalLeft - 5f, strikeY, AMOUNT_RIGHT + 2f, strikeY, paint)
        }
        y = totalBaseline + 65f

        val projects = (listOf(statement.project) + statement.lines.map { it.project })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val notes = (statement.notes + statement.lines.flatMap { it.notes })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val detailValues = buildList {
            projects.joinToString(" / ").takeIf { it.isNotEmpty() }?.let { add("プロジェクト: $it") }
            statement.category.trim().takeIf { it.isNotEmpty() }?.let { add("分類: $it") }
            notes.joinToString(" / ").takeIf { it.isNotEmpty() }?.let { add("メモ: $it") }
        }

        if(detailValues.isNotEmpty()) {
            drawText(if(statement.spend) "補足" else "制作メモ / プロジェクト", LEFT, y, 21f, MUTED, bold = true)
            y += 34f
            val detailBottom = 1110f
            val available = (detailBottom - y).coerceAtLeast(0f)
            val detailSize = when {
                available >= 190f -> 23f
                available >= 125f -> 20f
                else -> 20f
            }
            val detailLineHeight = detailSize + 6f
            val slot = if(detailValues.isEmpty()) 0f else available / detailValues.size
            val linesPerDetail = ((slot - 7f) / detailLineHeight).toInt().coerceIn(1, 3)
            detailValues.forEach { detail ->
                if(y + detailLineHeight > detailBottom) return@forEach
                val last = drawWrapped("・$detail", LEFT, y, detailSize, RIGHT - LEFT, Brand.ink,
                    lineHeight = detailLineHeight, maxLines = linesPerDetail)
                y = last + detailLineHeight + 7f
            }
        }

        val disclaimer = if(statement.spend) {
            if(hideTime) "※設定した時給に基づく仮定額です。実際の支給・引き落としはありません。"
            else "※設定した時給と計測時間に基づく仮定額です。実際の支給・引き落としはありません。"
        } else {
            "※設定した単価による時間投資の目安です。売上や作品評価額ではありません。"
        }
        // Detail content is budgeted against 1110f above; keep the note at a fixed,
        // measured baseline so it can never be pushed into the footer or off paper.
        val disclaimerY = 1130f
        drawWrapped(disclaimer, LEFT, disclaimerY, 21f, RIGHT - LEFT, MUTED, lineHeight = 28f, maxLines = 3)
        rule(1224f, 0xffdedbd0.toInt(), 1f)
        drawText("もしドパ", LEFT, 1276f, 27f, Brand.ink, bold = true)
        drawRight(if(statement.spend) "スクロールに、値札を。" else "時間の投資を、見える形に。", RIGHT, 1276f, 22f, MUTED)

        return bitmap
    }

    private fun share(activity: Activity, statement: Statement, hideTime: Boolean, fileKey: String) {
        val bitmap = render(statement, hideTime)
        try {
            val dir = File(activity.cacheDir, "receipts").apply { mkdirs() }
            val file = File(dir, "$fileKey.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.receipts", file)
            val shareText = shareText(statement, hideTime)
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, shareText)
                clipData = ClipData.newUri(activity.contentResolver, "もしドパ", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "明細を共有"))
        } finally {
            bitmap.recycle()
        }
    }

    private fun shareText(statement: Statement, hideTime: Boolean): String {
        val amount = if(statement.period == StatementPeriod.DAY || statement.period == StatementPeriod.MONTH) {
            String.format(Locale.JAPAN, "¥%,d", round(statement.amount).toLong())
        } else {
            Cost.money(statement.amount)
        }
        val date = when(statement.period) {
            StatementPeriod.SESSION -> statement.periodLabel.substringBefore(' ').ifBlank { statement.rangeLabel }
            else -> statement.rangeLabel.ifBlank { statement.periodLabel.substringBefore(' ') }
        }
        val time = if(hideTime) "" else "（${Cost.time(statement.duration)}）"
        return if(statement.spend) {
            "${date} · もしも給与明細：− ${amount}${time}。仮定額で、実際の支給・引き落としはありません。 #もしドパ"
        } else {
            val subject = statement.project.ifBlank { statement.category.ifBlank { "制作 / 自己投資" } }
            "${date} · ${subject} / 自己投資：${amount}${time}。時間投資の目安です。 #もしドパ"
        }
    }
}
