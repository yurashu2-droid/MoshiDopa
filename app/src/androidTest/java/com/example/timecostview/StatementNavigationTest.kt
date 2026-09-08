package com.example.timecostview

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.ScrollView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.timecostview.data.Store
import com.example.timecostview.domain.*
import com.example.timecostview.share.ReceiptRenderer
import com.example.timecostview.tracking.TrackingService
import org.hamcrest.Matcher
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class StatementNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun tap(label: String) {
        onView(withText(label)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun getDescription() = "Bring the control above the floating navigation"
            override fun perform(ui: UiController, view: View) {
                var ancestor = view.parent
                var top = view.top
                while(ancestor is View && ancestor !is ScrollView) {
                    top += ancestor.top
                    ancestor = ancestor.parent
                }
                (ancestor as? ScrollView)?.scrollTo(0, (top - 80).coerceAtLeast(0))
                ui.loopMainThreadUntilIdle()
            }
        }, click())
    }

    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    @Test fun renderLongInvestmentDetailsWithoutLossColor() {
        val day = LocalDate.of(2026, 9, 8)
        val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val samples = (1..6).map { index ->
            Record(index.toLong(), "manual", "制作$index", "INVEST", start, start + 3600000,
                3600000, 1234567.6, "プロジェクト$index・個人開発ポートフォリオと作品紹介ページ", "個人開発",
                "画面を実装しスマートフォン向けレイアウトと共有カードを確認しました。".repeat(4))
        }
        val statement = StatementBuilder.day(samples, day, "INVEST")
        val image = ReceiptRenderer.render(statement, false)
        assertEquals(1080, image.width)
        var redPixels = 0
        for(y in 0 until image.height step 3) for(x in 0 until image.width step 3) {
            val pixel = image.getPixel(x, y)
            if(android.graphics.Color.red(pixel) > android.graphics.Color.green(pixel) + 80) redPixels++
        }
        assertEquals("INVEST has no loss-colored strike", 0, redPixels)
        File(context.getExternalFilesDir(null), "passbook-invest-long.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    @Test fun originalHistoryAndSeparateStatementShare() {
        context.stopService(Intent(context, TrackingService::class.java))
        val ids = mutableListOf<Long>()
        val day = LocalDate.now()
        val start = day.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val samples = listOf(
            Record(0, "test.statement.youtube", "YouTube", "SPEND", start, start + 5400000, 5400000, 1800.0),
            Record(0, "test.statement.instagram", "Instagram", "SPEND", start + 5400000, start + 6600000, 1200000, 1800.0),
            Record(0, "test.statement.x", "X", "SPEND", start + 6600000, start + 7200000, 600000, 1800.0),
            Record(0, "manual", "個人開発", "INVEST", start, start + 10800000, 10800000, 1800.0,
                "もしドパ", "個人開発", "日給明細の画面を実装しました。"),
        )
        Store(context).use { store ->
            store.prefs.edit().putBoolean("configured", true).putBoolean("investMode", false).commit()
            samples.forEach { ids += store.save(it) }
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            onView(withContentDescription("履歴")).perform(click())
            onView(withText("その日、\n働いていたら。")).check(matches(isDisplayed()))
            onView(withContentDescription("SPEND / 消費")).check(doesNotExist())
            onView(withContentDescription(containsString("カレンダーを開く"))).check(matches(isDisplayed()))
            capture("restored-history")
            tap("日給・月給明細を作る")
            onView(withText("明細を作る")).check(matches(isDisplayed()))
            tap("通帳明細をプレビュー")
            onView(withContentDescription(containsString("YouTube"))).check(matches(isDisplayed()))
            capture("passbook-day-preview")
            tap("明細を画像で共有")
            assertTrue(File(context.cacheDir, "receipts").listFiles().orEmpty().any { it.length() > 1000 })
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
            }
            instrumentation.waitForIdleSync()
            tap("‹ 明細の設定に戻る")
            tap("月給明細")
            tap("INVEST / 自己投資")
            tap("通帳明細をプレビュー")
            onView(withContentDescription(containsString("自己投資明細"))).check(matches(isDisplayed()))
            capture("passbook-invest-preview")
            tap("‹ 明細の設定に戻る")
            tap("‹ 履歴に戻る")
            onView(withText("その日、\n働いていたら。")).check(matches(isDisplayed()))
            onView(withContentDescription("SPEND / 消費")).check(doesNotExist())

            val cases = listOf(
                "passbook-spend-day" to StatementBuilder.day(samples, day, "SPEND"),
                "passbook-spend-month" to StatementBuilder.month(samples, YearMonth.from(day), "SPEND"),
                "passbook-invest-month" to StatementBuilder.month(samples, YearMonth.from(day), "INVEST"),
            )
            cases.forEach { (name, statement) ->
                val image = ReceiptRenderer.render(statement, true)
                assertEquals(1080, image.width)
                assertEquals(1350, image.height)
                File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            Store(context).use { store -> ids.forEach { store.writableDatabase.delete("records", "id=?", arrayOf(it.toString())) } }
        }
    }
}
