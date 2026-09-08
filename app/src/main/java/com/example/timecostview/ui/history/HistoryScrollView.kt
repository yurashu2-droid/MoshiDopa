package com.example.timecostview.ui.history

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

internal class HistoryScrollView(context: Context) : HorizontalScrollView(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startScrollX = 0
    private var downTimeMs = 0L
    private var dragged = false
    private var velocityTracker: VelocityTracker? = null
    private var settleAnimator: ValueAnimator? = null

    var onGestureSettled: ((wasDragged: Boolean, velocityX: Float, durationMs: Long, distancePx: Float) -> Unit)? = null

    private fun beginGesture(event: MotionEvent) {
        settleAnimator?.cancel()
        settleAnimator = null
        downX = event.x
        downY = event.y
        startScrollX = scrollX
        downTimeMs = event.eventTime
        dragged = false
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked == MotionEvent.ACTION_DOWN) beginGesture(event)
        if(event.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = event.x - downX
            val dy = event.y - downY
            if(abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                dragged = true
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(event)
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                dragged = dragged || abs(event.x - downX) > touchSlop
                val maxScroll = ((getChildAt(0)?.measuredWidth ?: 0) - width).coerceAtLeast(0)
                scrollTo((startScrollX + (downX - event.x).roundToInt()).coerceIn(0, maxScroll), 0)
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                val tracker = velocityTracker
                tracker?.computeCurrentVelocity(1000)
                val velocityX = tracker?.xVelocity ?: 0f
                val durationMs = (event.eventTime - downTimeMs).coerceAtLeast(0L)
                val wasDragged = dragged
                onGestureSettled?.invoke(wasDragged, velocityX, durationMs, abs(event.x - downX))
                tracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    /** Slow drags should settle where the finger leaves them; fast swipes are handled by the screen. */
    override fun fling(velocityX: Int) = Unit

    fun settleTo(targetX: Int, onSettled: () -> Unit) {
        settleAnimator?.cancel()
        val startX = scrollX
        if(startX == targetX) {
            onSettled()
            return
        }
        var cancelled = false
        settleAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = (150L + abs(targetX - startX) / 5L).coerceIn(170L, 280L)
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    settleAnimator = null
                    if(!cancelled) onSettled()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        settleAnimator?.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }
}
