package com.example.timecostview.domain

import java.util.ArrayDeque

/** One live visual event. It is never converted into a purchase or receipt entry. */
data class MoneyDrop(val thresholdYen: Int, val item: FallingItem)

/**
 * Returns the highest amount threshold reached by a session.
 *
 * The threshold bands intentionally avoid expanding a large list into memory:
 * 10..90 by 10, 100..900 by 100, and 1000+ by 200.
 */
object MoneyDropThresholds {
    fun highestReached(amount: Double): Int {
        if(!amount.isFinite() || amount < 10.0) return 0
        return when {
            amount < 100.0 -> (amount / 10.0).toInt() * 10
            amount < 1_000.0 -> (amount / 100.0).toInt() * 100
            else -> 1_000 + ((amount - 1_000.0) / 200.0).toInt() * 200
        }
    }
}

/**
 * Session-scoped one-shot scheduler for live visual feedback.
 *
 * A threshold is consumed once per session. If another object is still falling,
 * the newly reached threshold waits in the queue and is emitted as soon as the
 * active object has left. There is intentionally no time-based cooldown: amount
 * thresholds should not disappear just because they were reached close together.
 */
class MoneyDropScheduler {
    private var sessionId: Long? = null
    private var processedThresholdYen = 0
    private val pendingThresholds = ArrayDeque<Int>()

    @Suppress("UNUSED_PARAMETER")
    fun reset(newSessionId: Long, currentAmount: Double = 0.0, nowMs: Long = 0L) {
        sessionId = newSessionId
        processedThresholdYen = MoneyDropThresholds.highestReached(currentAmount)
        pendingThresholds.clear()
    }

    fun clear() {
        sessionId = null
        processedThresholdYen = 0
        pendingThresholds.clear()
    }

    /** Restore a session without replaying thresholds already represented by its amount. */
    fun restore(newSessionId: Long, currentAmount: Double) {
        reset(newSessionId, currentAmount)
    }

    @Suppress("UNUSED_PARAMETER")
    fun observe(
        newSessionId: Long,
        amount: Double,
        nowMs: Long,
        enabled: Boolean,
        overlayVisible: Boolean,
        existingObject: Boolean,
        dragging: Boolean
    ): MoneyDrop? {
        if(sessionId != newSessionId) reset(newSessionId)

        val reached = MoneyDropThresholds.highestReached(amount)
        if(reached > processedThresholdYen) {
            processedThresholdYen = reached
            if(enabled && overlayVisible && !dragging) pendingThresholds.addLast(reached)
        }

        // Settings, visibility, and direct manipulation are not a reason to
        // replay old effects. Only an active falling object causes waiting.
        if(!enabled || !overlayVisible || dragging || existingObject) {
            if(!enabled || !overlayVisible || dragging) pendingThresholds.clear()
            return null
        }

        val nextThreshold = pendingThresholds.pollFirst() ?: return null

        return MoneyDrop(nextThreshold, FallingCatalog.forThreshold(nextThreshold).item)
    }

    internal fun processedThresholdForTest(): Int = processedThresholdYen
}
