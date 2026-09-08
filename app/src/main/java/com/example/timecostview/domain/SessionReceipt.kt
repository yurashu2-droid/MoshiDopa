package com.example.timecostview.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One place to set the public introduction page once it exists.
 * Keep this blank until a real, published URL is available; blank means that
 * both the QR code and its label are omitted from screen and share images.
 */
object AppIntroduction {
    const val URL = ""
}

data class SessionReceiptContent(
    val recordId: Long,
    val spend: Boolean,
    val heading: String,
    val subject: String,
    val amountLabel: String,
    val amount: String,
    val duration: String,
    val rate: String,
    val note: String,
    val issuedAt: String,
    val brandSubtitle: String,
    val disclaimer: String,
    val introductionUrl: String,
)

object SessionReceiptBuilder {
    private val issuedFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm 発行", Locale.JAPAN)

    fun from(record: Record, zone: ZoneId = ZoneId.systemDefault()): SessionReceiptContent {
        val invest = record.mode == "INVEST"
        return SessionReceiptContent(
            recordId = record.id,
            spend = !invest,
            heading = if(invest) "今回の自己投資明細" else "今回の利用明細",
            subject = if(invest) record.project.ifBlank { record.title } else record.title,
            amountLabel = if(invest) "時間の投資" else "働いていたら",
            amount = Cost.money(record.amount),
            duration = durationLabel(record.duration),
            rate = "${if(invest) "設定単価" else "設定時給"} ${Cost.money(record.rate)}",
            note = if(invest) record.note.trim() else "",
            issuedAt = issuedFormatter.format(Instant.ofEpochMilli(record.end).atZone(zone)),
            brandSubtitle = if(invest) "時間の投資を、見える形に。" else "～もしもドパガキが働いたら？～",
            disclaimer = if(invest) {
                "設定した単価と計測時間に基づく時間投資の目安です。売上や作品の評価額ではありません。"
            } else {
                "設定した時給と計測時間に基づく仮定の金額です。"
            },
            introductionUrl = AppIntroduction.URL.trim(),
        )
    }

    fun durationLabel(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds / 60L % 60L
        val seconds = totalSeconds % 60L
        val body = when {
            hours > 0L -> "%d時間%02d分".format(Locale.JAPAN, hours, minutes)
            minutes > 0L -> "%d分%02d秒".format(Locale.JAPAN, minutes, seconds)
            else -> "%d秒".format(Locale.JAPAN, seconds)
        }
        return "$body 利用しました"
    }
}
