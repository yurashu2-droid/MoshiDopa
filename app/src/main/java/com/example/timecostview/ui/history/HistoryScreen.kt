package com.example.timecostview.ui.history

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.example.timecostview.domain.Cost
import com.example.timecostview.domain.Record
import com.example.timecostview.domain.HistorySummary
import com.example.timecostview.domain.HistoryWindow
import com.example.timecostview.domain.StatementBuilder
import com.example.timecostview.domain.StatementPeriod
import com.example.timecostview.share.ReceiptRenderer
import com.example.timecostview.ui.Brand
import com.example.timecostview.ui.PageUi
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun historyDateLabel(day: LocalDate): String {
    val weekdays = arrayOf("日", "月", "火", "水", "木", "金", "土")
    return "${day.monthValue}/${day.dayOfMonth}（${weekdays[day.dayOfWeek.value % 7]}）"
}

private fun historyDateShortLabel(day: LocalDate): String {
    val weekdays = arrayOf("日", "月", "火", "水", "木", "金", "土")
    return "${day.monthValue}/${day.dayOfMonth}\n${weekdays[day.dayOfWeek.value % 7]}"
}

private fun historyPickerLabel(day: LocalDate): String {
    val prefix = if(day == LocalDate.now()) "今日・" else ""
    return "$prefix${day.monthValue}月${day.dayOfMonth}日（${arrayOf("日", "月", "火", "水", "木", "金", "土")[day.dayOfWeek.value % 7]}）  ⌄"
}

/** Renders one snapshot; user actions flow back to the screen owner. */
internal class HistoryScreen(
    private val context: Activity,
    private val page: LinearLayout,
    private val sourceRecords: List<Record>,
    private val historyDay: LocalDate,
    private val historyAnchorDay: LocalDate,
    private val expandedGroup: String?,
    private val onSelectWindow: (LocalDate, LocalDate) -> Unit,
    private val onExpandGroup: (String?) -> Unit,
    private val onOpenRecord: (Record) -> Unit,
    private val onComposeStatement: (LocalDate) -> Unit,
) {
    private val ink = Brand.text
    private val muted = Brand.muted
    private val accent = Brand.lime
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun modeLabel(value: String) = if(value == "INVEST") "自己投資" else "もしも給与"
    private fun date(time: Long) = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.JAPAN).format(Date(time))
    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun render() = with(PageUi(context, page)) {
        val days = HistorySummary.days(sourceRecords)
        val records = days[historyDay].orEmpty()
        val selectedAmount = records.sumOf { it.amount }

        page.addView(text("その日、\n働いていたら。", 32f, ink, true)); space(6)
        // The selected day's amount is the first thing the eye meets, before the chart.
        page.addView(bigMoney(Cost.money(selectedAmount)))
        paragraph("合計 ${Cost.time(records.sumOf { it.duration })}  ·  ${records.size}件")
        space(10)

        val dateButton = text(historyPickerLabel(historyDay), 13f, ink, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isClickable = true
            isFocusable = true
            contentDescription = "カレンダーを開く。選択中は${historyDateLabel(historyDay)}"
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x2bffffff),
                null,
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(22).toFloat()
                },
            )
            setOnClickListener {
                android.app.DatePickerDialog(context, { _, y, m, d ->
                    val selected = LocalDate.of(y, m + 1, d)
                    val anchor = if(selected.isBefore(historyAnchorDay) || selected.isAfter(historyAnchorDay.plusDays(6)))
                        HistoryWindow.weekStart(selected) else historyAnchorDay
                    onSelectWindow(selected, anchor)
                }, historyDay.year, historyDay.monthValue - 1, historyDay.dayOfMonth).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                }.show()
            }
        }
        page.addView(dateButton, LinearLayout.LayoutParams(-2, dp(44)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // A broad off-screen buffer keeps direct manipulation continuous while
        // still allowing a fast gesture to settle on a calendar-week boundary.
        val chartScroll = HistoryScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val chart = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        val chartStart = historyAnchorDay.minusDays(14)
        val chartDates = (0..34).map { chartStart.plusDays(it.toLong()) }
        val maximum = chartDates.maxOf { day -> days[day].orEmpty().sumOf { it.amount } }.coerceAtLeast(1.0)
        val columnWidth = dp(44)
        chartDates.forEach { day ->
            val total = days[day].orEmpty().sumOf { it.amount }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                minimumWidth = columnWidth; isClickable = true; isFocusable = true
                contentDescription = "$day、${Cost.money(total)}"; isSelected = day == historyDay
                val track = FrameLayout(context)
                track.addView(View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(if(day == historyDay) accent else 0xff78834f.toInt())
                        cornerRadius = dp(6).toFloat()
                    }
                }, FrameLayout.LayoutParams(
                    dp(24),
                    if(total > 0) dp((total / maximum * 90).toInt().coerceIn(3, 90)) else dp(1),
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ))
                addView(track, LinearLayout.LayoutParams(-1, dp(100)))
                addView(text(historyDateShortLabel(day), 11f, if(day == historyDay) accent else muted).apply { gravity = Gravity.CENTER })
                setOnClickListener {
                    val anchor = when {
                        day.isBefore(historyAnchorDay) -> day
                        day.isAfter(historyAnchorDay.plusDays(6)) -> day.minusDays(6)
                        else -> historyAnchorDay
                    }
                    onSelectWindow(day, anchor)
                }
            }
            chart.addView(column, LinearLayout.LayoutParams(columnWidth, dp(136)))
        }
        chartScroll.addView(chart, LinearLayout.LayoutParams(-2, dp(152)))
        page.addView(chartScroll, LinearLayout.LayoutParams(-1, dp(152)))
        chartScroll.post { chartScroll.scrollTo(columnWidth * 14, 0) }

        chartScroll.onGestureSettled = { wasDragged, velocityX, durationMs, distancePx ->
            if(wasDragged) {
                chartScroll.post {
                    if(context.isFinishing || context.isDestroyed || !chartScroll.isAttachedToWindow) return@post
                    val nearestIndex = (chartScroll.scrollX.toFloat() / columnWidth)
                        .roundToInt()
                        .coerceIn(0, chartDates.size - 7)
                    val nearestStart = chartStart.plusDays(nearestIndex.toLong())
                    val requestedAnchor = HistoryWindow.settleAnchor(
                        nearestVisibleStart = nearestStart,
                        velocityX = velocityX,
                        durationMs = durationMs,
                        distancePx = distancePx,
                        columnWidthPx = columnWidth,
                        fastVelocityPx = 900,
                    )
                    val targetIndex = ChronoUnit.DAYS.between(chartStart, requestedAnchor)
                        .toInt()
                        .coerceIn(0, chartDates.size - 7)
                    val targetAnchor = chartStart.plusDays(targetIndex.toLong())
                    chartScroll.settleTo(targetIndex * columnWidth) {
                        if(context.isFinishing || context.isDestroyed || !chartScroll.isAttachedToWindow) return@settleTo
                        val selected = historyDay.coerceIn(targetAnchor, targetAnchor.plusDays(6))
                        onSelectWindow(selected, targetAnchor)
                    }
                }
            }
        }

        space(16)
        label("この日の記録")
        if(records.isEmpty()) { space(24); paragraph("この日の記録はありません。日付を選んで確認できます。") }
        HistorySummary.groups(records).forEach { group ->
            val expanded = expandedGroup == group.key
            page.addView(button("${if(expanded) "▾" else "▸"} ${group.title}  ·  ${modeLabel(group.mode)}\n${Cost.money(group.amount)}  /  ${Cost.time(group.duration)}  /  ${group.records.size}回") {
                onExpandGroup(if(expanded) null else group.key)
            })
            if(expanded) {
                page.addView(button("このアプリの1日分を共有") {
                    val hide = CheckBox(context).apply { text = "時間を隠す"; isChecked = true; setPadding(dp(24), dp(12), dp(24), dp(12)) }
                    AlertDialog.Builder(context).setTitle("1日分の明細を共有").setView(hide)
                        .setMessage("時間と金額を両方載せると、計算に使った時給を推測できます。")
                        .setPositiveButton("共有") { _, _ ->
                            runCatching { ReceiptRenderer.share(context, StatementBuilder.group(group.records, StatementPeriod.DAY, group.mode), hide.isChecked) }.onFailure { toast("共有できませんでした") }
                        }.setNegativeButton("キャンセル", null).show()
                })
                group.records.forEach { r ->
                    page.addView(button("${date(r.start)}  ·  ${Cost.time(r.duration)}\n${Cost.money(r.amount)}  明細を見る") {
                        onOpenRecord(r)
                    })
                }
            }
        }
        page.addView(button("日給・月給明細を作る") {
            onComposeStatement(historyDay)
        })
        space(16); paragraph("日をまたぐ記録は日別に分けて表示します。金額はそれぞれの記録に保存された時給で計算します。")
    }

}
