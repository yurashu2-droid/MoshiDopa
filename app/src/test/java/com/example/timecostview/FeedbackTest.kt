package com.example.timecostview

import com.example.timecostview.domain.*
import org.junit.Assert.*
import org.junit.Test

class FeedbackTest {
    private val record = Record(1, "video", "Video", "SPEND", 0, 20000, 20000, 1800.0)
    @Test fun isReadyImmediately() {
        val gate = CompletionGate(); gate.leave(record)
        assertEquals(record, gate.ready())
    }
    @Test fun previewExpiresAndDismisses() {
        val gate = CompletionGate(); gate.leave(record)
        assertEquals(record, gate.preview(100)); assertEquals(record, gate.preview(8099)); assertNull(gate.preview(8100))
        gate.dismiss(); assertNull(gate.preview(9000))
    }
    @Test fun thresholdsAreOneShotNotRepeating() {
        assertEquals(10, Milestones.crossing(9.99, 10.0)?.yen)
        assertNull(Milestones.crossing(10.0, 20.0))
        assertEquals(100, Milestones.crossing(99.9, 100.0)?.yen)
        assertEquals(200, Milestones.crossing(9.0, 201.0)?.yen)
        assertNull(Milestones.crossing(201.0, 400.0))
    }
    @Test fun moneyDropThresholdBandsUseAmountNotElapsedTime() {
        assertEquals(0, MoneyDropThresholds.highestReached(9.99))
        assertEquals(10, MoneyDropThresholds.highestReached(10.0))
        assertEquals(90, MoneyDropThresholds.highestReached(99.99))
        assertEquals(100, MoneyDropThresholds.highestReached(100.0))
        assertEquals(900, MoneyDropThresholds.highestReached(999.99))
        assertEquals(1_000, MoneyDropThresholds.highestReached(1_000.0))
        assertEquals(1_200, MoneyDropThresholds.highestReached(1_299.99))
    }

    @Test fun amountThresholdsChooseTheIllustrativeItem() {
        val scheduler = MoneyDropScheduler()
        assertEquals(MoneyDrop(10, FallingItem.CANDY), scheduler.observe(1, 10.0, 0, true, true, false, false))
        val next = MoneyDropScheduler()
        assertEquals(MoneyDrop(100, FallingItem.CHOCOLATE), next.observe(2, 100.0, 0, true, true, false, false))
        val late = MoneyDropScheduler()
        assertEquals(MoneyDrop(200, FallingItem.COFFEE), late.observe(3, 205.0, 0, true, true, false, false))
    }

    @Test fun schedulerEmitsEveryAmountThresholdWithoutCooldown() {
        val scheduler = MoneyDropScheduler()
        val thresholds = listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 200)

        thresholds.forEachIndexed { index, threshold ->
            val expectedItem = when {
                threshold < 100 -> FallingItem.CANDY
                threshold < 200 -> FallingItem.CHOCOLATE
                else -> FallingItem.COFFEE
            }
            assertEquals(
                MoneyDrop(threshold, expectedItem),
                scheduler.observe(1, threshold.toDouble(), index.toLong(), true, true, false, false)
            )
        }
    }

    @Test fun schedulerQueuesOnlyWhileAnObjectIsFalling() {
        val scheduler = MoneyDropScheduler()
        assertEquals(MoneyDrop(10, FallingItem.CANDY), scheduler.observe(4, 10.0, 1_000, true, true, false, false))
        assertNull(scheduler.observe(4, 20.0, 2_000, true, true, true, false))
        assertNull(scheduler.observe(4, 30.0, 3_000, true, true, true, false))
        assertEquals(MoneyDrop(20, FallingItem.CANDY), scheduler.observe(4, 30.0, 4_000, true, true, false, false))
        assertEquals(MoneyDrop(30, FallingItem.CANDY), scheduler.observe(4, 30.0, 5_000, true, true, false, false))
    }

    @Test fun schedulerDoesNotReplayThresholdsSkippedWhileUnavailable() {
        val scheduler = MoneyDropScheduler()
        assertNull(scheduler.observe(1, 10.0, 0, enabled = false, overlayVisible = true, existingObject = false, dragging = false))
        assertNull(scheduler.observe(1, 20.0, 20_000, enabled = false, overlayVisible = true, existingObject = false, dragging = false))
        assertNull(scheduler.observe(1, 20.0, 30_000, enabled = true, overlayVisible = true, existingObject = false, dragging = false))
    }

    @Test fun schedulerRestoreAndSessionChangeDoNotReplayOldAmount() {
        val scheduler = MoneyDropScheduler()
        scheduler.restore(10, 205.0)
        assertNull(scheduler.observe(10, 205.0, 0, true, true, false, false))
        assertEquals(MoneyDrop(10, FallingItem.CANDY), scheduler.observe(11, 10.0, 0, true, true, false, false))
    }
}
