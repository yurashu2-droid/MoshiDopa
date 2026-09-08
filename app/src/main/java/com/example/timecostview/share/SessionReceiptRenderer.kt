package com.example.timecostview.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.timecostview.domain.Record
import com.example.timecostview.domain.SessionReceiptBuilder
import com.example.timecostview.ui.SessionReceiptArt
import java.io.File

/** Session-only paper receipt. Day/month statements continue to use ReceiptRenderer. */
object SessionReceiptRenderer {
    fun share(context: Context, record: Record, hideTime: Boolean) {
        val bitmap = SessionReceiptArt.render(SessionReceiptBuilder.from(record), hideTime)
        try {
            val directory = File(context.cacheDir, "receipts").apply { mkdirs() }
            val file = File(directory, "session-${record.id}.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.receipts", file)
            val content = SessionReceiptBuilder.from(record)
            val time = if(hideTime) "" else "（${content.duration.removeSuffix(" 利用しました")}）"
            val text = if(content.spend) {
                "${content.subject} · 今回の利用明細：${content.amount}$time。働いていたら、という仮定の金額です。 #もしドパ"
            } else {
                "${content.subject} · 今回の自己投資明細：${content.amount}$time。時間の投資の目安です。 #もしドパ"
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                clipData = ClipData.newUri(context.contentResolver, "もしドパ", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "今回の明細を共有").apply {
                if(context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } finally {
            bitmap.recycle()
        }
    }
}
