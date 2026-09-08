package com.example.timecostview

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import com.example.timecostview.data.Store
import com.example.timecostview.tracking.TrackingService
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MvpFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun waitFor(timeoutMs: Long = 12000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while(!check() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("State did not arrive within ${timeoutMs / 1000} seconds", check())
    }
    /** The app keeps a floating bottom navigation above the scroll surface. A real swipe is more reliable than
     * Espresso's precondition-heavy scrollTo() when the target starts underneath that sibling. */
    private fun reveal(matcher: Matcher<View>) {
        repeat(6) {
            try {
                onView(matcher).check(matches(isDisplayingAtLeast(90)))
                return
            } catch (_: Throwable) {
                onView(isAssignableFrom(ScrollView::class.java)).perform(object : ViewAction {
                    override fun getConstraints(): Matcher<View> = isAssignableFrom(ScrollView::class.java)
                    override fun getDescription(): String = "scroll the main surface to its end"
                    override fun perform(uiController: UiController, view: View) {
                        val scroll = view as ScrollView
                        scroll.scrollTo(0, scroll.getChildAt(0)?.height ?: 0)
                        uiController.loopMainThreadUntilIdle()
                    }
                })
            }
        }
        onView(matcher).check(matches(isDisplayingAtLeast(90)))
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
    }
    private fun launch(): MainActivity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun manualSetupStopAndReceiptShare() {
        context.stopService(Intent(context, TrackingService::class.java))
        waitFor { !TrackingService.state.running }
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        Store(context).use { it.prefs.edit().putInt("onboardingStep", 0).putBoolean("configured", false).putBoolean("monthly", false).putBoolean("investMode", false).putString("inputAmount", "1800").commit() }
        val activity = launch()
        screenshot("brand-welcome")
        onView(withText("試してみる  →")).perform(scrollTo(), click())
        screenshot("brand-rate")
        onView(withText("自動計測はあとで設定してホームへ")).perform(scrollTo(), click())
        screenshot("brand-home")
        reveal(withText("START SPEND"))
        onView(withText("START SPEND")).perform(click())
        waitFor { TrackingService.state.running && TrackingService.state.elapsed >= 1300 }
        assertTrue(TrackingService.state.amount > 0)
        screenshot("manual-live")
        onView(withText("STOP  /  計測を終了")).perform(scrollTo(), click())
        waitFor { !TrackingService.state.running && TrackingService.state.result != null }
        onView(withContentDescription(org.hamcrest.Matchers.containsString("今回の利用明細"))).check(matches(isDisplayed()))
        screenshot("receipt")
        onView(withText("共有")).perform(click())
        assertTrue(File(context.cacheDir, "receipts").listFiles()?.any { it.length() > 1000 } == true)
        val receiptId = TrackingService.state.result!!.id
        File(context.cacheDir, "receipts/session-$receiptId.png").copyTo(File(context.getExternalFilesDir(null), "share-card.png"), overwrite = true)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun brandOnboardingPermissionGatesAndResume() {
        context.stopService(Intent(context, TrackingService::class.java))
        waitFor { !TrackingService.state.running }
        shell("appops set ${context.packageName} GET_USAGE_STATS deny")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny")
        Store(context).use { it.prefs.edit().putBoolean("configured", false).putInt("onboardingStep", 0)
            .putBoolean("monthly", false).putString("inputAmount", "1226").putString("rate", "1226")
            .putStringSet("targets", setOf("com.google.android.deskclock")).commit() }
        var activity = launch()
        screenshot("onboarding-welcome")
        onView(withText("試してみる  →")).perform(scrollTo(), click())
        screenshot("onboarding-rate")
        onView(withText("この金額で進む  →")).perform(scrollTo(), click())
        onView(withText("使用状況の設定を開く")).check(matches(isDisplayed()))
        screenshot("onboarding-usage")
        instrumentation.runOnMainSync { activity.finish() }
        activity = launch()
        onView(withText("使用状況の設定を開く")).check(matches(isDisplayed()))
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        instrumentation.runOnMainSync { activity.finish() }; activity = launch()
        onView(withText("次へ  →")).perform(scrollTo(), click())
        screenshot("onboarding-apps")
        onView(withText("選んだアプリで進む  →")).perform(scrollTo(), click())
        onView(withText("重ねて表示を許可する")).check(matches(isDisplayed()))
        screenshot("onboarding-overlay")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        instrumentation.runOnMainSync { activity.finish() }; activity = launch()
        onView(withText("表示を確認する  →")).perform(scrollTo(), click())
        screenshot("onboarding-ready")
        onView(withText("ホームへ")).perform(scrollTo(), click())
        Store(context).use { assertTrue(it.prefs.getBoolean("configured", false)); assertEquals(1226.0, it.rate, 0.0) }
        screenshot("onboarding-home")
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun autoAppOverlayAndScreenOff() {
        context.stopService(Intent(context, TrackingService::class.java))
        waitFor { !TrackingService.state.running }
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val target = "com.google.android.deskclock"
        Store(context).use { it.prefs.edit().putBoolean("configured", true).putString("rate", "1800").putStringSet("targets", setOf(target)).commit() }
        val activity = launch()
        instrumentation.runOnMainSync { activity.startForegroundService(Intent(context, TrackingService::class.java).setAction(TrackingService.AUTO)) }
        waitFor { TrackingService.state.running }
        onView(withText("● 自動計測 ON")).check(matches(isDisplayed()))
        screenshot("auto-ready-redesign")
        context.startActivity(context.packageManager.getLaunchIntentForPackage(target)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor { TrackingService.state.title.isNotEmpty() && TrackingService.state.elapsed > 1800 }
        val amount = TrackingService.state.today
        assertTrue(amount > 0)
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = info
        screenshot("auto-overlay")
        waitFor { instrumentation.uiAutomation.windows.any { it.root?.packageName?.toString() == context.packageName } }
        screenshot("auto-overlay")
        shell("input keyevent KEYCODE_HOME")
        waitFor { TrackingService.state.title.isEmpty() }
        context.startActivity(context.packageManager.getLaunchIntentForPackage(target)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor { TrackingService.state.title.isNotEmpty() && TrackingService.state.elapsed > 800 }
        assertTrue("Daily amount survives app switching", TrackingService.state.today >= amount)
        shell("input keyevent KEYCODE_HOME")
        waitFor { TrackingService.state.title.isEmpty() }
        waitFor { TrackingService.state.result != null }
        waitFor { instrumentation.uiAutomation.windows.any { it.root?.packageName?.toString() == context.packageName } }
        screenshot("session-receipt-overlay")
        val root = instrumentation.uiAutomation.windows.first { it.root?.packageName?.toString() == context.packageName }.root!!
        val bounds = android.graphics.Rect(); root.getBoundsInScreen(bounds)
        // The modal sheet closes on an outside tap and consumes that tap.
        shell("input tap ${bounds.left + 8} ${bounds.bottom - 8}")
        waitFor { instrumentation.uiAutomation.windows.none { it.root?.packageName?.toString() == context.packageName } }
        val receiptId = TrackingService.state.result!!.id
        context.startActivity(Intent(context, MainActivity::class.java)
            .putExtra("receiptId", receiptId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        waitFor { MainActivity.isVisible }
        onView(withContentDescription(org.hamcrest.Matchers.containsString("今回の利用明細"))).check(matches(isDisplayed()))
        context.startActivity(context.packageManager.getLaunchIntentForPackage(target)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor { TrackingService.state.title.isNotEmpty() }
        shell("input keyevent KEYCODE_SLEEP")
        waitFor { TrackingService.state.title.isEmpty() }
        context.stopService(Intent(context, TrackingService::class.java))
        waitFor { !TrackingService.state.running }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        Store(context).use { assertTrue(it.records().any { r -> r.app == target && r.duration > 0 }) }
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun groupedHistoryAndNavigation() {
        context.stopService(Intent(context, TrackingService::class.java))
        waitFor { !TrackingService.state.running }
        val ids = mutableListOf<Long>()
        val start = java.time.LocalDate.now().atTime(8,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        Store(context).use { store ->
            store.prefs.edit().putBoolean("configured", true).commit()
            repeat(3) { i -> ids += store.save(com.example.timecostview.domain.Record(0, "test.history", "履歴テスト", "SPEND",
                start+i*3600000, start+i*3600000+600000, 600000, 1800.0)) }
        }
        val activity = launch()
        try {
            onView(withContentDescription("履歴")).perform(click())
            screenshot("history-grouped")
            reveal(withText(org.hamcrest.Matchers.containsString("▸ 履歴テスト")))
            onView(withText(org.hamcrest.Matchers.containsString("▸ 履歴テスト"))).perform(click())
            reveal(withText("このアプリの1日分を共有"))
            screenshot("history-expanded")
            val firstTime = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.JAPAN).format(java.util.Date(start))
            reveal(withText("$firstTime  ·  00:10:00\n¥300.00  明細を見る"))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            Store(context).use { store -> ids.forEach { store.writableDatabase.delete("records", "id=?", arrayOf(it.toString())) } }
        }
    }

    @Test fun investMonthlyStatementShowsProjectAndNotes() {
        context.stopService(Intent(context, TrackingService::class.java))
        waitFor { !TrackingService.state.running }
        val ids = mutableListOf<Long>()
        val start = java.time.LocalDate.now().atTime(11, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        Store(context).use { store ->
            store.prefs.edit().putBoolean("configured", true).commit()
            ids += store.save(com.example.timecostview.domain.Record(
                0, "manual", "個人開発テスト", "INVEST", start, start + 20 * 60 * 1000L,
                20 * 60 * 1000L, 1800.0, "もしドパ", "個人開発", "月次明細を実装"
            ))
        }
        val activity = launch()
        try {
            onView(withContentDescription("履歴")).perform(click())
            onView(withText("日給・月給明細を作る")).perform(scrollTo(), click())
            onView(withText("INVEST / 自己投資")).perform(scrollTo(), click())
            onView(withText("月給明細")).perform(scrollTo(), click())
            onView(withText("通帳明細をプレビュー")).perform(scrollTo(), click())
            onView(withContentDescription(org.hamcrest.Matchers.containsString("自己投資明細")))
                .check(matches(isDisplayed()))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            Store(context).use { store -> ids.forEach { store.writableDatabase.delete("records", "id=?", arrayOf(it.toString())) } }
        }
    }

    /** Dedicated-AVD smoke: the first 10-yen amount threshold creates one candy drop. */
    @Test fun autoOverlayDropsAtTwentyYen() {
        context.stopService(Intent(context, TrackingService::class.java))
        waitFor { !TrackingService.state.running }
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val target = "com.google.android.youtube"
        Store(context).use {
            it.prefs.edit()
                .putBoolean("configured", true)
                .putString("rate", "1800")
                .putStringSet("targets", setOf(target))
                .putBoolean("milestones", true)
                .putBoolean("motion", true)
                .commit()
        }
        instrumentation.runOnMainSync {
            context.startForegroundService(Intent(context, TrackingService::class.java).setAction(TrackingService.AUTO))
        }
        waitFor { TrackingService.state.running }
        shell("input keyevent KEYCODE_HOME")
        shell("monkey -p $target 1")
        waitFor(25_000) { TrackingService.state.title.isNotEmpty() && TrackingService.state.elapsed >= 20_500 }
        assertTrue("The first amount threshold should be around ten yen", TrackingService.state.amount in 9.0..12.0)
        screenshot("auto-candy-20yen")
        shell("input keyevent KEYCODE_HOME")
        waitFor { TrackingService.state.title.isEmpty() }
        context.stopService(Intent(context, TrackingService::class.java))
        waitFor { !TrackingService.state.running }
    }

    /** Dedicated-AVD smoke: a first observed 100-yen threshold uses the chocolate item. */
    @Test fun autoOverlayShowsChocolateAt100Yen() {
        context.stopService(Intent(context, TrackingService::class.java))
        waitFor { !TrackingService.state.running }
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val target = "com.google.android.youtube"
        Store(context).use {
            it.prefs.edit()
                .putBoolean("configured", true)
                .putString("rate", "360000")
                .putStringSet("targets", setOf(target))
                .putBoolean("milestones", true)
                .putBoolean("motion", true)
                .commit()
        }
        instrumentation.runOnMainSync {
            context.startForegroundService(Intent(context, TrackingService::class.java).setAction(TrackingService.AUTO))
        }
        waitFor { TrackingService.state.running }
        shell("input keyevent KEYCODE_HOME")
        shell("monkey -p $target 1")
        waitFor { TrackingService.state.title.isNotEmpty() && TrackingService.state.elapsed >= 1_500 }
        assertTrue("The first observed threshold should be between 100 and 200 yen", TrackingService.state.amount in 100.0..199.0)
        screenshot("auto-chocolate-100yen")
        shell("input keyevent KEYCODE_HOME")
        waitFor { TrackingService.state.title.isEmpty() }
        context.stopService(Intent(context, TrackingService::class.java))
        waitFor { !TrackingService.state.running }
    }

    @Test fun milestoneMotionFrames() {
        shell("input keyevent KEYCODE_HOME")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        val tag = com.example.timecostview.overlay.PriceTag(context)
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = info
        instrumentation.runOnMainSync {
            tag.show("YouTube · 今回", "¥12.00", "¥200.00")
        }
        // Let the overlay window measure before starting physics, then sample each motion phase.
        waitFor { instrumentation.uiAutomation.windows.any { it.root?.packageName?.toString() == context.packageName } }
        instrumentation.runOnMainSync { tag.drop(com.example.timecostview.domain.FallingItem.CHOCOLATE, true) }
        waitFor { tag.hasActiveDropAnimation }
        Thread.sleep(100); screenshot("milestone-fall")
        Thread.sleep(300); screenshot("milestone-contact")
        Thread.sleep(350); screenshot("milestone-hold")
        Thread.sleep(2200); screenshot("milestone-quiet")
        instrumentation.runOnMainSync { tag.hide() }
    }

    @Test fun collisionActuallyTintsTagDuringLiveUpdates() {
        shell("input keyevent KEYCODE_HOME")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        val tag = com.example.timecostview.overlay.PriceTag(context)
        val field = tag.javaClass.getDeclaredField("root").apply { isAccessible = true }
        val root = field.get(tag) as android.view.View
        try {
            instrumentation.runOnMainSync { tag.show("YouTube · 今回", "¥12.00", "¥200.00") }
            waitFor { root.width > 0 }
            screenshot("impact-before")
            instrumentation.runOnMainSync { tag.drop(com.example.timecostview.domain.FallingItem.CANDY, true) }
            var reddest: Bitmap? = null
            var maxRedDifference = 0
            repeat(75) { index ->
                instrumentation.runOnMainSync {
                    if(index % 6 == 0) tag.show("YouTube · 今回", "¥12.${index.toString().padStart(2, '0')}", "¥200.00")
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    val pixel = bitmap.getPixel(root.width/2, root.height-12)
                    val difference = android.graphics.Color.red(pixel)-android.graphics.Color.green(pixel)
                    if(difference > maxRedDifference) {
                        reddest?.recycle(); reddest = bitmap; maxRedDifference = difference
                    } else bitmap.recycle()
                }
                Thread.sleep(16)
            }
            assertTrue("Collision must visibly tint paper, including during show() updates: $maxRedDifference", maxRedDifference >= 20)
            reddest!!.let { bitmap ->
                File(context.getExternalFilesDir(null), "impact-peak.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            Thread.sleep(3000)
            screenshot("impact-resolution")
        } finally { instrumentation.runOnMainSync { tag.hide() } }
    }

    @Test fun priceTagRemainsDraggableWithFallingLayer() {
        shell("input keyevent KEYCODE_HOME")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        // Repeated runs must not start at the right screen edge after the previous drag.
        context.getSharedPreferences("overlay", android.content.Context.MODE_PRIVATE)
            .edit().putInt("x", 24).putInt("y", 300).commit()
        val tag = com.example.timecostview.overlay.PriceTag(context)
        instrumentation.runOnMainSync { tag.show("YouTube · 今回", "¥12.00", "¥200.00") }
        val info = instrumentation.uiAutomation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = info
        fun bounds(): android.graphics.Rect? = instrumentation.uiAutomation.windows
            .firstOrNull { it.root?.packageName?.toString() == context.packageName }
            ?.root?.let { node -> android.graphics.Rect().also { node.getBoundsInScreen(it) } }
        waitFor { bounds()?.width() ?: 0 > 0 }
        val before = requireNotNull(bounds())
        shell("input swipe ${before.centerX()} ${before.centerY()} ${before.centerX() + 80} ${before.centerY() + 60} 400")
        waitFor { (bounds()?.left ?: before.left) >= before.left + 40 && (bounds()?.top ?: before.top) >= before.top + 20 }
        screenshot("overlay-drag")
        instrumentation.runOnMainSync { tag.hide() }
    }
}
