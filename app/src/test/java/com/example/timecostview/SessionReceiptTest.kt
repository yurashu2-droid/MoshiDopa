package com.example.timecostview

import com.example.timecostview.domain.Record
import com.example.timecostview.domain.SessionReceiptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SessionReceiptTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test fun spendUsesOnlySessionLanguageAndRealStoredRate() {
        val content = SessionReceiptBuilder.from(
            Record(7, "youtube", "YouTube", "SPEND", 0, 7_380_000, 7_380_000, 1200.0),
            zone,
        )
        assertEquals("今回の利用明細", content.heading)
        assertEquals("YouTube", content.subject)
        assertEquals("働いていたら", content.amountLabel)
        assertEquals("2時間03分 利用しました", content.duration)
        assertEquals("設定時給 ¥1,200.00", content.rate)
        assertTrue(content.spend)
        assertTrue(content.introductionUrl.isBlank())
    }

    @Test fun investDoesNotInheritSpendVerdictAndUsesOptionalProjectAndMemo() {
        val content = SessionReceiptBuilder.from(
            Record(8, "manual", "開発", "INVEST", 0, 61_000, 61_000, 1800.0,
                project = "もしドパ", note = "共有画面を整えた"),
            zone,
        )
        assertEquals("今回の自己投資明細", content.heading)
        assertEquals("もしドパ", content.subject)
        assertEquals("時間の投資", content.amountLabel)
        assertEquals("1分01秒 利用しました", content.duration)
        assertEquals("共有画面を整えた", content.note)
        assertFalse(content.spend)
        assertTrue(content.disclaimer.contains("売上や作品の評価額ではありません"))
    }

    @Test fun shortAndLongDurationsStayHumanReadable() {
        assertEquals("9秒 利用しました", SessionReceiptBuilder.durationLabel(9_999))
        assertEquals("59分05秒 利用しました", SessionReceiptBuilder.durationLabel(3_545_000))
        assertEquals("12時間00分 利用しました", SessionReceiptBuilder.durationLabel(43_200_000))
    }
}
