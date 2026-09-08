package com.example.timecostview.overlay

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.timecostview.domain.Record
import com.example.timecostview.domain.SessionReceiptBuilder
import com.example.timecostview.share.SessionReceiptRenderer
import com.example.timecostview.ui.SessionReceiptPalette
import com.example.timecostview.ui.SessionReceiptPaperView
import kotlin.math.min

/** Modal, single-instance receipt shown over the app the user moved to. */
class SessionReceiptOverlay(
    private val context: Context,
    private val onDismiss: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private var attached = false
    private var displayedId = -1L
    private var record: Record? = null
    private var animator: AnimatorSet? = null

    private val paper = SessionReceiptPaperView(context)
    private val paperScroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        elevation = dp(16).toFloat()
        addView(paper, FrameLayout.LayoutParams(-1, -2))
        isClickable = true
        isFocusable = true
        contentDescription = "今回の明細。タップすると印字を完了します"
        setOnClickListener { finishAnimation() }
    }
    private val close = action("閉じる") {
        dismiss()
    }
    private val share = action("共有") {
        finishAnimation()
        record?.let { runCatching { SessionReceiptRenderer.share(context, it, hideTime.isChecked) } }
    }
    private val hideTime = CheckBox(context).apply {
        text = "共有では時間を隠す"
        textSize = 13f
        setTextColor(Color.WHITE)
        buttonTintList = ColorStateList.valueOf(0xffdfff4a.toInt())
        isChecked = true
        minHeight = dp(48)
        setOnCheckedChangeListener { _, checked -> paper.hideTime = checked }
    }
    private val contentColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        addView(paperScroll, LinearLayout.LayoutParams((context.resources.displayMetrics.widthPixels * .9f).toInt(), dp(620)))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(close, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
            addView(share, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        addView(hideTime, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(2) })
    }
    private val root = object : FrameLayout(context) {
        private var outsideGesture = false

        private fun contains(view: View, event: android.view.MotionEvent): Boolean {
            val bounds = Rect()
            return view.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
        }

        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            if(event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                outsideGesture = !contains(paperScroll, event) && !contains(close, event) &&
                    !contains(share, event) && !contains(hideTime, event)
                if(outsideGesture) return true
            }
            if(outsideGesture) {
                if(event.actionMasked == android.view.MotionEvent.ACTION_UP) dismiss()
                if(event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) outsideGesture = false
                return true
            }
            return super.dispatchTouchEvent(event)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val maxPaperHeight = (h - dp(176)).coerceAtLeast(dp(260))
            val paperHeight = (w * .9f * com.example.timecostview.ui.SessionReceiptArt.BASE_HEIGHT /
                com.example.timecostview.ui.SessionReceiptArt.BASE_WIDTH).toInt()
            paperScroll.layoutParams = (paperScroll.layoutParams as LinearLayout.LayoutParams).apply {
                width = (w * .9f).toInt()
                height = min(paperHeight, maxPaperHeight)
            }
        }
    }.apply {
        setBackgroundColor(0x990b0d0b.toInt())
        setPadding(0, dp(28), 0, dp(20))
        addView(contentColumn, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        isClickable = true
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "もしドパ 今回の利用明細"
    }

    private fun action(label: String, block: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isClickable = true
        isFocusable = true
        background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x2fffffff),
            GradientDrawable().apply { setColor(0xcc292b28.toInt()); cornerRadius = dp(24).toFloat() },
            null,
        )
        setOnClickListener { block() }
    }

    fun show(value: Record, motion: Boolean) {
        record = value
        paper.content = SessionReceiptBuilder.from(value)
        paper.hideTime = hideTime.isChecked
        if(!attached) {
            wm.addView(root, params)
            attached = true
        }
        if(displayedId == value.id) return
        displayedId = value.id
        if(!motion) {
            finishAnimation()
            return
        }
        startAnimation()
    }

    private fun startAnimation() {
        animator?.cancel()
        paper.revealProgress = 0f
        paper.edgeFlex = 1f
        contentColumn.translationY = -context.resources.displayMetrics.heightPixels * .78f
        contentColumn.rotation = 1.2f
        contentColumn.scaleY = .93f
        val landing = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620L
            interpolator = PathInterpolator(.16f, .74f, .18f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                contentColumn.translationY = -context.resources.displayMetrics.heightPixels * .78f * (1f - t)
                contentColumn.rotation = 1.2f * (1f - t)
                contentColumn.scaleY = .93f + .07f * t
                paper.edgeFlex = 1f - t
            }
        }
        val print = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1700L
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { paper.revealProgress = it.animatedValue as Float }
        }
        animator = AnimatorSet().apply {
            playTogether(landing, print)
            start()
        }
    }

    fun finishAnimation() {
        animator?.cancel(); animator = null
        contentColumn.translationY = 0f
        contentColumn.rotation = 0f
        contentColumn.scaleY = 1f
        paper.edgeFlex = 0f
        paper.revealProgress = 1f
    }

    fun dismiss() {
        hide()
        onDismiss()
    }

    fun hide() {
        animator?.cancel(); animator = null
        if(attached) {
            runCatching { wm.removeView(root) }
            attached = false
        }
        displayedId = -1L
    }
}
