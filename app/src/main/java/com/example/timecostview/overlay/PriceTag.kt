package com.example.timecostview.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.timecostview.MainActivity
import com.example.timecostview.ui.Brand
import com.example.timecostview.domain.FallingItem
import com.example.timecostview.domain.Cost
import com.example.timecostview.domain.Record
import com.example.timecostview.overlay.physics.CollisionEvent
import dev.liquidglass.view.GlassViewController
import dev.liquidglass.view.LiquidGlassProviderLayout
import kotlin.math.abs
import kotlin.math.roundToInt

private class OverlayOpticsBackdrop(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        if(width < 1 || height < 1) return
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = 19f * density

        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(0x42ffffff, 0x08172b27, 0x242f83ff, 0x30ffffff),
            floatArrayOf(0f, 0.38f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(bounds, radius, radius, paint)

        paint.shader = RadialGradient(
            width * 0.86f,
            height * 0.02f,
            width * 0.66f,
            intArrayOf(0x62ffffff, 0x10ffffff, 0x00ffffff),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(bounds, radius, radius, paint)

        paint.shader = RadialGradient(
            width * 0.02f,
            height * 0.96f,
            width * 0.58f,
            intArrayOf(0x2ed6ff3f, 0x061df5d0, 0x001df5d0),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.shader = null
    }
}

/** Measures the optical provider to the content, never to the full overlay window. */
private class OverlayGlassRoot(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val content = getChildAt(1)
        val parentHeightMode = MeasureSpec.getMode(heightMeasureSpec)
        val contentHeightSpec = if(parentHeightMode == MeasureSpec.UNSPECIFIED) {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        } else {
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST)
        }
        content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), contentHeightSpec)
        val measuredHeight = content.measuredHeight
        getChildAt(0).measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for(index in 0 until childCount) {
            getChildAt(index).layout(0, 0, measuredWidth, measuredHeight)
        }
    }
}

private class PriceTagSurface(context: Context) : LinearLayout(context) {
    companion object {
        private const val RISE_NANOS = 40_000_000L
        private const val DECAY_NANOS = 360_000_000L
    }

    private val density = resources.displayMetrics.density
    private val glass = GlassViewController(this).apply {
        cornerRadiusDp = 19f
        blurRadiusDp = 29f
        refractionHeightDp = 17f
        refractionAmountDp = -25f
        saturation = 1.15f
        glassTintColor = 0xd0080b09.toInt()
        fallbackScrimColor = 0xeb080b09.toInt()
        chromaticAberration = 0.46f
        noiseAlpha = 0.026f
        highlightAlpha = 0.96f
        highlightWidthDp = 2.2f
        lightAngleDegrees = 225f
        isGlassInteractive = true
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
    }
    private val glassRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.15f * density
    }
    private var flashStartedAt = 0L
    private var contactX = 0f
    private val flashFrame = object : Runnable {
        override fun run() {
            if(flashStartedAt == 0L) return
            invalidate()
            val elapsed = SystemClock.elapsedRealtimeNanos() - flashStartedAt
            if(elapsed < RISE_NANOS + DECAY_NANOS) postOnAnimation(this)
            else flashStartedAt = 0L
        }
    }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setGlassProvider(provider: LiquidGlassProviderLayout) {
        glass.provider = provider
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.onHostAttached()
    }

    override fun onDetachedFromWindow() {
        glass.onHostDetached()
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        glass.handleTouch(event)
        return super.dispatchTouchEvent(event)
    }

    fun flash(event: CollisionEvent) {
        contactX = event.contactPoint.x.coerceIn(0f, width.toFloat())
        flashStartedAt = SystemClock.elapsedRealtimeNanos()
        removeCallbacks(flashFrame)
        postOnAnimation(flashFrame)
        invalidate()
    }

    fun clearImpactReaction() {
        flashStartedAt = 0L
        removeCallbacks(flashFrame)
        invalidate()
    }

    private fun intensity(now: Long): Float {
        val elapsed = now - flashStartedAt
        if(elapsed <= 0L) return 0f
        return if(elapsed < RISE_NANOS) {
            (elapsed.toFloat() / RISE_NANOS).coerceIn(0f, 1f)
        } else {
            (1f - (elapsed - RISE_NANOS).toFloat() / DECAY_NANOS).coerceIn(0f, 1f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        glass.draw(canvas)
        if(flashStartedAt == 0L) return
        val strength = intensity(SystemClock.elapsedRealtimeNanos())
        if(strength <= 0f) return
        flashPaint.color = Color.argb((130f * strength).roundToInt(), 255, 98, 90)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 8f * density, 8f * density, flashPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val rimInset = 1.35f * density
        glassRimPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(0x9affffff.toInt(), 0x1223ffe0, 0x381e78ff, 0x70ffffff),
            floatArrayOf(0f, 0.43f, 0.76f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(
            RectF(rimInset, rimInset, width - rimInset, height - rimInset),
            18f * density,
            18f * density,
            glassRimPaint,
        )
        glassRimPaint.shader = null
        if(flashStartedAt == 0L) return
        val strength = intensity(SystemClock.elapsedRealtimeNanos())
        if(strength <= 0f) return
        edgePaint.color = Color.argb((210f * strength).roundToInt(), 255, 98, 90)
        val halfLength = 13f * density
        canvas.drawLine(
            (contactX - halfLength).coerceAtLeast(3f * density),
            1.5f * density,
            (contactX + halfLength).coerceAtMost(width - 3f * density),
            1.5f * density,
            edgePaint
        )
    }
}

class PriceTag(private val context: Context, private val onDismiss: () -> Unit = {}) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private var receiptId: Long? = null
    private val caption = TextView(context).apply {
        setTextColor(0xffe5ebe7.toInt()); textSize = 10f; maxLines = 2
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ellipsize = android.text.TextUtils.TruncateAt.END
        setShadowLayer(4f, 0f, 1f, 0xb8000000.toInt())
    }
    private val amount = PayslipAmount(context).apply {
        setTextColor(Color.WHITE); textSize = 29f; typeface = Typeface.create("sans-serif", Typeface.BOLD); fontFeatureSettings = "tnum"; maxLines = 1
        setShadowLayer(7f, 0f, 1.5f, 0xe0000000.toInt())
        minHeight = dp(38); androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 12, 29, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private val todayTotal = TextView(context).apply {
        textSize = 11f; setTextColor(0xffd9e0db.toInt()); maxLines = 1
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(4f, 0f, 1f, 0xb8000000.toInt())
    }
    private val footer = TextView(context).apply { textSize = 10f; setTextColor(Brand.muted) }
    /**
     * The falling objects live in a separate transparent window behind the tag.
     * It is deliberately non-touchable: only the tag window below owns input.
     */
    private val effect = MilestoneView(context)
    private val opticsProvider = LiquidGlassProviderLayout(context).apply {
        addView(OverlayOpticsBackdrop(context), FrameLayout.LayoutParams(-1, -1))
    }
    private val tagSurface = PriceTagSurface(context).apply {
        setGlassProvider(opticsProvider)
    }
    private var impactReactionEnabled = true
    private var dragging = false
    private val close = TextView(context).apply {
        text = "閉じる"; textSize = 12f; setTextColor(Brand.muted); gravity = Gravity.CENTER
        minHeight = dp(48); visibility = android.view.View.GONE
        setOnClickListener { onDismiss(); hide() }
    }
    private val root = OverlayGlassRoot(context).apply {
        tagSurface.apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xe70d110f.toInt(), 0xe3161a17.toInt(), 0xdc020403.toInt()),
            ).apply { cornerRadius = dp(19).toFloat() }
            elevation = dp(12).toFloat()
            addView(caption); addView(amount); addView(todayTotal); addView(footer)
            addView(close)
            contentDescription = "時間の金額。ドラッグで移動、タップでもしドパを開く"
        }
        addView(opticsProvider, FrameLayout.LayoutParams(-1, -1))
        addView(tagSurface, FrameLayout.LayoutParams(-1, -2))
    }
    private val params = WindowManager.LayoutParams(dp(212), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = "TIME COST price tag"
        x = prefs.getInt("x", dp(12)); y = prefs.getInt("y", dp(100))
    }
    private val effectParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = "TIME COST falling items"
        // Android 12+ limits pass-through touches through non-trusted overlays
        // by their window opacity. Keep this layer below the default 0.8 cap.
        alpha = 0.75f
    }
    private var attached = false
    private var effectAttached = false

    val hasActiveDropAnimation: Boolean get() = effect.hasActiveObjects
    val isDragging: Boolean get() = dragging

    init {
        effect.onCollision = { event ->
            if(impactReactionEnabled && event.firstImpact && attached) {
                val rootLocation = IntArray(2); root.getLocationOnScreen(rootLocation)
                val effectLocation = IntArray(2); effect.getLocationOnScreen(effectLocation)
                tagSurface.flash(event.copy(contactPoint = event.contactPoint.copy(
                    x = event.contactPoint.x + effectLocation[0] - rootLocation[0],
                    y = event.contactPoint.y + effectLocation[1] - rootLocation[1]
                )))
            }
        }
        tagSurface.setOnClickListener {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            receiptId?.let { intent.putExtra("receiptId", it) }
            onDismiss(); hide(); context.startActivity(intent)
        }
        var sx = 0f; var sy = 0f; var x = 0; var y = 0; var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        tagSurface.setOnTouchListener { _, event ->
            when(event.action) {
                MotionEvent.ACTION_DOWN -> { sx = event.rawX; sy = event.rawY; x = params.x; y = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    moved = moved || abs(event.rawX - sx) > slop || abs(event.rawY - sy) > slop
                    if(moved) {
                        if(!dragging) {
                            dragging = true
                            clearDropAnimation()
                        }
                        params.x = (x + event.rawX - sx).toInt().coerceIn(0, (context.resources.displayMetrics.widthPixels - dp(212)).coerceAtLeast(0))
                        params.y = (y + event.rawY - sy).toInt().coerceIn(0, (context.resources.displayMetrics.heightPixels - dp(120)).coerceAtLeast(0))
                        if(attached) wm.updateViewLayout(root, params)
                        updateEffectBounds()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    if(!moved) tagSurface.performClick()
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }
    }
    fun show(label: String, value: String, todayValue: String, spend: Boolean = true, motion: Boolean = true) {
        receiptId = null; amount.cancelled = false; impactReactionEnabled = spend && motion
        if(!impactReactionEnabled) tagSurface.clearImpactReaction()
        todayTotal.visibility = android.view.View.VISIBLE; close.visibility = android.view.View.GONE
        footer.setTextColor(Brand.lime)
        footer.text = "● LIVE · もしドパ / 今回"
        caption.text = label; amount.text = value; todayTotal.text = "今日のトータル  $todayValue"
        attach()
    }
    fun showReceipt(record: Record) {
        effect.clear(); detachEffect()
        tagSurface.clearImpactReaction(); impactReactionEnabled = false
        receiptId = record.id; amount.cancelled = record.mode == "SPEND"
        todayTotal.text = ""; todayTotal.visibility = android.view.View.GONE
        caption.text = "もしも給与明細 · 今回\n${record.title}"
        amount.text = Cost.money(record.amount)
        footer.text = "${Cost.time(record.duration)}  ·  タップで明細\n実際の給与・損失額ではありません"
        close.visibility = android.view.View.VISIBLE
        attach(withEffect = false)
    }
    fun drop(item: FallingItem, motion: Boolean) {
        if(!attached) attach(withEffect = true)
        if(!effectAttached) attachEffect()
        val start = {
            if(attached && effectAttached) {
                updateEffectBounds()
                effect.drop(item, motion)
            }
        }
        if(root.width <= 0 || root.height <= 0) root.post(start) else start()
    }
    fun clearDropAnimation() { effect.clear(); tagSurface.clearImpactReaction() }
    private fun attach(withEffect: Boolean = true) {
        val oldX = params.x; val oldY = params.y
        params.x = params.x.coerceIn(0, (context.resources.displayMetrics.widthPixels - dp(212)).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (context.resources.displayMetrics.heightPixels - dp(220)).coerceAtLeast(0))
        if(attached && (oldX != params.x || oldY != params.y)) wm.updateViewLayout(root, params)
        if(withEffect) attachEffect()
        if(!attached) { wm.addView(root, params); attached = true }
        if(withEffect) root.post { updateEffectBounds() }
    }
    private fun attachEffect() {
        if(!effectAttached) { wm.addView(effect, effectParams); effectAttached = true }
    }
    private fun detachEffect() {
        if(effectAttached) { wm.removeView(effect); effectAttached = false }
    }
    private fun updateEffectBounds() {
        if(!effectAttached || !attached || root.width <= 0 || root.height <= 0) return
        val rootLocation = IntArray(2); root.getLocationOnScreen(rootLocation)
        val effectLocation = IntArray(2); effect.getLocationOnScreen(effectLocation)
        effect.setObstacle(
            (rootLocation[0] - effectLocation[0]).toFloat(),
            (rootLocation[1] - effectLocation[1]).toFloat(),
            (rootLocation[0] - effectLocation[0] + root.width).toFloat(),
            (rootLocation[1] - effectLocation[1] + root.height).toFloat()
        )
    }
    fun hide() {
        dragging = false; clearDropAnimation(); detachEffect()
        if(attached) { wm.removeView(root); attached = false }
    }
}
