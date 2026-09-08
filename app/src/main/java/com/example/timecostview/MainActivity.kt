package com.example.timecostview

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.example.timecostview.data.Store
import com.example.timecostview.domain.Cost
import com.example.timecostview.domain.HistoryWindow
import com.example.timecostview.domain.Record
import com.example.timecostview.domain.Milestones
import com.example.timecostview.domain.Statement
import com.example.timecostview.domain.StatementBuilder
import com.example.timecostview.domain.StatementPeriod
import com.example.timecostview.overlay.PayslipAmount
import com.example.timecostview.share.ReceiptRenderer
import com.example.timecostview.share.SessionReceiptRenderer
import com.example.timecostview.tracking.Access
import com.example.timecostview.tracking.TrackingService
import com.example.timecostview.ui.Brand
import com.example.timecostview.ui.SessionReceiptPalette
import com.example.timecostview.ui.SessionReceiptPaperView
import com.example.timecostview.domain.SessionReceiptBuilder
import dev.liquidglass.view.LiquidGlassProviderLayout
import dev.liquidglass.view.LiquidGlassView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private class HistoryScrollView(context: Context) : HorizontalScrollView(context) {
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

private fun historyWeekStart(day: LocalDate): LocalDate =
    HistoryWindow.weekStart(day)

private fun historyDateLabel(day: LocalDate): String {
    val weekdays = arrayOf("日", "月", "火", "水", "木", "金", "土")
    return "${day.monthValue}/${day.dayOfMonth}（${weekdays[day.dayOfWeek.value % 7]}）"
}

private fun historyDateShortLabel(day: LocalDate): String {
    val weekdays = arrayOf("日", "月", "火", "水", "木", "金", "土")
    return "${day.monthValue}/${day.dayOfMonth}\n${weekdays[day.dayOfWeek.value % 7]}"
}

private fun historyPickerLabel(day: LocalDate): String {
    val prefix = if(day == LocalDate.now()) "今日・" else ""
    return "$prefix${day.monthValue}月${day.dayOfMonth}日（${arrayOf("日", "月", "火", "水", "木", "金", "土")[day.dayOfWeek.value % 7]}）  ⌄"
}

class MainActivity : ComponentActivity() {
    companion object { @Volatile var isVisible = false; private set }
    private val ink = Brand.text
    private val muted = Brand.muted
    private val todayMuted = Brand.muted
    private val paper = Brand.background
    private val accent = Brand.lime
    private var demoStarted = 0L
    private var demoAmount: TextView? = null
    private lateinit var store: Store
    private lateinit var page: LinearLayout
    private lateinit var shell: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var tab = "home"
    private var historyDay = LocalDate.now()
    private var historyAnchorDay = historyWeekStart(historyDay)
    private var historyMonth = YearMonth.now()
    private var historyPeriod = StatementPeriod.DAY
    private var historyMode = "SPEND"
    private var expandedGroup: String? = null
    private var amountView: TextView? = null
    private var todayView: TextView? = null
    private var elapsedView: TextView? = null
    private var activityView: TextView? = null
    private var runningBefore = false
    private var lastResult = -1L
    private var result: Record? = null
    private var statement: Statement? = null
    private var statementDay = LocalDate.now()
    private var statementProject = ""
    private var statementHideTime = true
    private var statementReturnTab = "statements"
    private var sessionHideTime = true
    private var receiptAnimationPlayedId = -1L
    private var mode = "SPEND"
    private var activityDraft = ""
    private var projectDraft = ""
    private var categoryDraft = "個人開発"
    private var noteDraft = ""
    private var pendingPicker = false
    private var navIndicatorPosition = Float.NaN
    private var navIndicatorScale = 1f
    private var navIndicatorAnimationGeneration = 0L
    private var animateNavIndicator = false
    private var firstResume = true
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun navIndex(value: String): Int = when(value) {
        "history", "result", "statements", "statementPreview" -> 1
        "settings" -> 2
        else -> 0
    }
    private fun modeLabel(value: String): String = if(value == "INVEST") "自己投資" else "もしも給与"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when(tab) {
                    "statementPreview" -> { tab = statementReturnTab; render() }
                    "statements" -> { tab = "history"; render() }
                    "result" -> { tab = "history"; statement = null; render() }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        if(Build.VERSION.SDK_INT >= 27) {
            window.navigationBarColor = paper
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        }
        store = Store(this)
        // Defaults apply only to new installs; never replace an existing salary or record rate.
        if(!store.prefs.contains("inputAmount") && !store.prefs.contains("rate")) {
            store.prefs.edit().putString("inputAmount", "1226").putString("rate", "1226").apply()
        }
        savedInstanceState?.getString("historyDay")?.let { historyDay = java.time.LocalDate.parse(it) }
        savedInstanceState?.getString("historyAnchorDay")?.let { historyAnchorDay = LocalDate.parse(it) }
            ?: run { historyAnchorDay = historyWeekStart(historyDay) }
        savedInstanceState?.getString("historyMonth")?.let { historyMonth = YearMonth.parse(it) }
        historyPeriod = savedInstanceState?.getString("historyPeriod")?.let { runCatching { StatementPeriod.valueOf(it) }.getOrNull() } ?: StatementPeriod.DAY
        historyMode = savedInstanceState?.getString("historyMode") ?: "SPEND"
        expandedGroup = savedInstanceState?.getString("expandedGroup")
        mode = savedInstanceState?.getString("mode")
            ?: if(store.prefs.getBoolean("investMode", false)) "INVEST" else "SPEND"
        activityDraft = savedInstanceState?.getString("draft") ?: ""
        projectDraft = savedInstanceState?.getString("projectDraft") ?: ""
        categoryDraft = savedInstanceState?.getString("categoryDraft") ?: "個人開発"
        noteDraft = savedInstanceState?.getString("noteDraft") ?: ""
        tab = savedInstanceState?.getString("tab") ?: "home"
        statementDay = savedInstanceState?.getString("statementDay")?.let(LocalDate::parse) ?: historyDay
        statementProject = savedInstanceState?.getString("statementProject") ?: ""
        statementHideTime = savedInstanceState?.getBoolean("statementHideTime", true) ?: true
        statementReturnTab = savedInstanceState?.getString("statementReturnTab") ?: "statements"
        sessionHideTime = savedInstanceState?.getBoolean("sessionHideTime", true) ?: true
        receiptAnimationPlayedId = savedInstanceState?.getLong("receiptAnimationPlayedId", -1L) ?: -1L
        @Suppress("DEPRECATION")
        val savedStatement = savedInstanceState?.getSerializable("statementSnapshot") as? Statement
        statement = savedStatement
        lastResult = savedInstanceState?.getLong("lastResult", -1L) ?: -1L
        val resultId = savedInstanceState?.getLong("resultId", -1L) ?: -1L
        result = store.records().firstOrNull { it.id == resultId }
        if(savedInstanceState?.containsKey("resultSliceStart") == true) result = result?.copy(
            start = savedInstanceState.getLong("resultSliceStart"),
            end = savedInstanceState.getLong("resultSliceStart") + savedInstanceState.getLong("resultSliceDuration"),
            duration = savedInstanceState.getLong("resultSliceDuration"))
        openReceiptIntent(intent)
        render()
    }
    private fun openReceiptIntent(value: Intent) {
        val id = value.getLongExtra("receiptId", -1)
        if(id > 0) store.records().firstOrNull { it.id == id }?.let { result = it; statement = null; lastResult = id; tab = "result" }
        value.removeExtra("receiptId")
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); openReceiptIntent(intent); render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("historyDay", historyDay.toString()); outState.putString("historyAnchorDay", historyAnchorDay.toString())
        outState.putString("historyMonth", historyMonth.toString()); outState.putString("historyPeriod", historyPeriod.name); outState.putString("historyMode", historyMode)
        outState.putString("expandedGroup", expandedGroup)
        outState.putString("mode", mode); outState.putString("draft", activityDraft); outState.putString("tab", tab)
        outState.putString("projectDraft", projectDraft); outState.putString("categoryDraft", categoryDraft); outState.putString("noteDraft", noteDraft)
        outState.putBoolean("sessionHideTime", sessionHideTime); outState.putLong("receiptAnimationPlayedId", receiptAnimationPlayedId)
        outState.putLong("lastResult", lastResult); outState.putLong("resultId", result?.id ?: -1L)
        result?.let { outState.putLong("resultSliceStart", it.start); outState.putLong("resultSliceDuration", it.duration) }
        outState.putString("statementDay", statementDay.toString())
        outState.putString("statementProject", statementProject)
        outState.putBoolean("statementHideTime", statementHideTime)
        outState.putString("statementReturnTab", statementReturnTab)
        outState.putSerializable("statementSnapshot", statement)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume(); isVisible = true
        if(firstResume) firstResume = false else if(::store.isInitialized) render()
        handler.post(tick)
    }
    override fun onPause() { isVisible = false; handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { store.close(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode == 1 && !isFinishing && !isDestroyed) render()
    }

    private val tick = object : Runnable {
        override fun run() {
            demoAmount?.text = Cost.money(Cost.yen(SystemClock.elapsedRealtime() - demoStarted, store.rate))
            val s = TrackingService.state
            if(!s.running && s.result != null && s.result.id != lastResult) {
                lastResult = s.result.id; result = s.result; statement = null; tab = "result"; render()
            } else if(runningBefore != s.running) render()
            runningBefore = s.running
            if(s.running) {
                amountView?.text = Cost.money(s.amount)
                todayView?.text = "今日のトータル  ${Cost.money(s.today)}"
                elapsedView?.text = if(s.title.isEmpty()) "対象アプリを開いてください" else "${Cost.time(s.elapsed)}  ·  ${if(s.manual) "今回" else "今回の利用時間"}"
                activityView?.text = if(s.title.isEmpty()) "READY / 自動計測中" else "${s.title}  /  ${if(s.manual) modeLabel(s.mode) else "今日のトータル"}"
            }
            handler.postDelayed(this, 100)
        }
    }
    private fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(4).toFloat(), 1f)
        if(bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun space(height: Int = 20) { page.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun label(value: String) { page.addView(text(value, 12f, muted, true)) }
    private fun paragraph(value: String) { page.addView(text(value, 14f, muted)) }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = value; isAllCaps = false; textSize = 16f; setTextColor(if(primary) Brand.background else ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val shape = GradientDrawable().apply { setColor(if(primary) accent else Brand.surface); cornerRadius = dp(24).toFloat() }
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if(primary) 0x33000000 else 0x33ffffff), shape, null)
        minHeight = dp(60); setPadding(dp(20), dp(14), dp(20), dp(14))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun field(hintValue: String, value: String, numeric: Boolean = false) = EditText(this).apply {
        hint = hintValue; setText(value); textSize = 22f; setTextColor(ink); setHintTextColor(muted)
        inputType = if(numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        setSingleLine(); minHeight = dp(60)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { setColor(Brand.surface); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xff515646.toInt()) }
        filters = arrayOf(android.text.InputFilter.LengthFilter(if(numeric) 14 else 60))
    }
    private fun bigMoney(value: String): PayslipAmount = PayslipAmount(this).apply {
        text = value; textSize = 60f; setTextColor(ink)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setSingleLine(); androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 22, 64, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        fontFeatureSettings = "tnum"; minHeight = dp(105)
    }
    private fun line() { page.addView(View(this).apply { setBackgroundColor(0xff3b3f32.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(24); bottomMargin = dp(24) }) }
    private fun render() {
        amountView = null; elapsedView = null; activityView = null
        todayView = null
        demoAmount = null
        val configured = store.prefs.getBoolean("configured", false)
        if(configured && tab == "result" && statement == null && result != null) {
            renderSessionReceiptWorld(requireNotNull(result))
            return
        }
        val appRoot = FrameLayout(this).apply { setBackgroundColor(paper) }
        val backdrop = LiquidGlassProviderLayout(this)
        shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        ViewCompat.setOnApplyWindowInsetsListener(appRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        backdrop.addView(shell, FrameLayout.LayoutParams(-1, -1))
        appRoot.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        setContentView(appRoot)
        androidx.core.view.WindowCompat.getInsetsController(window, appRoot).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            // The floating bottom navigation is a sibling above the scroll
            // surface. Reserve its hit area so scrollTo()/accessibility focus
            // never leaves a final CTA underneath the glass bar.
            setPadding(0, 0, 0, dp(104))
            // Let the content travel through the reserved inset. This keeps
            // the last CTA revealable above the floating bar after a swipe.
            clipToPadding = false
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(if(configured) 116 else 32))
        }
        scroll.addView(page); shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val brandRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brandRow.addView(text("もしドパ", 25f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        brandRow.addView(text("SCROLL HAS A PRICE", 9f, muted, true))
        page.addView(brandRow)
        space(28)
        if(!configured) setup(true) else {
            when(tab) {
                "settings" -> setup(false)
                "history" -> history()
                "statements" -> statementComposer()
                "statementPreview" -> statementPreview()
                "result" -> receipt()
                else -> home()
            }
            val nav = LiquidGlassView(this).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                provider = backdrop
                cornerRadiusDp = 28f
                blurRadiusDp = 22f
                refractionHeightDp = 11f
                refractionAmountDp = -12f
                saturation = 1.45f
                glassTintColor = 0x4d394238
                fallbackScrimColor = 0xe62d342d.toInt()
                chromaticAberration = 0.16f
                noiseAlpha = 0.016f
                highlightAlpha = 0.68f
                highlightWidthDp = 1.35f
                lightAngleDegrees = 235f
                isGlassInteractive = true
                elevation = dp(12).toFloat()
            }
            val navItems = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val selectedIndex = navIndex(tab)
            if(navIndicatorPosition.isNaN()) navIndicatorPosition = selectedIndex.toFloat()
            val indicatorStart = navIndicatorPosition
            val shouldAnimateIndicator = animateNavIndicator
            val indicatorScaleStart = if(shouldAnimateIndicator) navIndicatorScale else 1f
            animateNavIndicator = false
            val navIndicator = LiquidGlassView(this).apply {
                provider = backdrop
                cornerRadiusDp = 19f
                blurRadiusDp = 18f
                refractionHeightDp = 9f
                refractionAmountDp = -9f
                saturation = 1.45f
                glassTintColor = 0x9fe4ff43.toInt()
                fallbackScrimColor = 0xcce4ff43.toInt()
                chromaticAberration = 0.22f
                noiseAlpha = 0.018f
                highlightAlpha = 0.95f
                highlightWidthDp = 1.7f
                lightAngleDegrees = 225f
                isGlassInteractive = false
                scaleX = indicatorScaleStart
                scaleY = indicatorScaleStart
            }
            nav.addView(navIndicator, FrameLayout.LayoutParams(0, -1))
            nav.addView(navItems, FrameLayout.LayoutParams(-1, -1))
            listOf("home" to "計測", "history" to "履歴", "settings" to "設定").forEach { (id, title) ->
                val selected = id == tab || (id == "history" && tab == "result")
                val iconId = when(id) { "home" -> R.drawable.ic_nav_timer; "history" -> R.drawable.ic_nav_history; else -> R.drawable.ic_nav_settings }
                navItems.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    minimumHeight = dp(56); setPadding(dp(4), dp(4), dp(4), dp(4))
                    isClickable = true; isFocusable = true; contentDescription = title; isSelected = selected
                    foreground = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(0x33ffffff),
                        null,
                        GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = dp(19).toFloat()
                        },
                    )
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(iconId); setColorFilter(if(selected) Brand.background else muted)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(26), dp(26)))
                    addView(text(title, 11f, if(selected) Brand.background else muted, true).apply {
                        gravity = Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    setOnClickListener {
                        val segmentWidth = (nav.width - nav.paddingLeft - nav.paddingRight) / 3f
                        navIndicatorPosition = if(segmentWidth > 0f) {
                            navIndicator.translationX / segmentWidth
                        } else {
                            navIndex(tab).toFloat()
                        }
                        navIndicatorScale = navIndicator.scaleX.coerceIn(0.9f, 1.12f)
                        navIndicatorAnimationGeneration += 1
                        animateNavIndicator = true
                        tab = id
                        render()
                    }
                }, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            appRoot.addView(nav, FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM).apply {
                setMargins(dp(12), 0, dp(12), dp(12))
            })
            nav.post {
                if(isFinishing || isDestroyed) return@post
                val segmentWidth = (nav.width - nav.paddingLeft - nav.paddingRight) / 3f
                if(segmentWidth <= 0f) return@post
                navIndicator.layoutParams = (navIndicator.layoutParams as FrameLayout.LayoutParams).apply {
                    width = segmentWidth.roundToInt()
                }
                navIndicator.pivotX = navIndicator.width / 2f
                navIndicator.pivotY = navIndicator.height / 2f
                val startX = indicatorStart * segmentWidth
                val targetX = selectedIndex * segmentWidth
                navIndicator.translationX = startX
                if(shouldAnimateIndicator && abs(targetX - startX) > 0.5f) {
                    val animationGeneration = navIndicatorAnimationGeneration
                    var arrived = false
                    var dipped = false
                    var restoreStarted = false
                    fun springScale(target: Float, stiffness: Float, damping: Float, onEnd: () -> Unit = {}) {
                        SpringAnimation(navIndicator, DynamicAnimation.SCALE_X, target).apply {
                            spring = SpringForce(target).apply {
                                dampingRatio = damping
                                this.stiffness = stiffness
                            }
                            addUpdateListener { _, value, _ ->
                                navIndicator.scaleY = value
                                if(animationGeneration == navIndicatorAnimationGeneration) navIndicatorScale = value
                            }
                            addEndListener { _, canceled, _, _ -> if(!canceled) onEnd() }
                            start()
                        }
                    }
                    fun restoreScale() {
                        if(restoreStarted) return
                        restoreStarted = true
                        springScale(1f, 900f, SpringForce.DAMPING_RATIO_NO_BOUNCY) {
                            if(animationGeneration == navIndicatorAnimationGeneration) navIndicatorScale = 1f
                        }
                    }
                    springScale(1.1f, 1050f, 0.72f) {
                        springScale(0.94f, 950f, SpringForce.DAMPING_RATIO_NO_BOUNCY) {
                            dipped = true
                            if(arrived) restoreScale()
                        }
                    }
                    SpringAnimation(navIndicator, DynamicAnimation.TRANSLATION_X, targetX).apply {
                        spring = SpringForce(targetX).apply {
                            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                            stiffness = 700f
                        }
                        addUpdateListener { _, value, _ ->
                            navIndicatorPosition = value / segmentWidth
                        }
                        addEndListener { _, canceled, _, _ ->
                            if(!canceled) {
                                navIndicatorPosition = selectedIndex.toFloat()
                                arrived = true
                                if(dipped) restoreScale()
                            }
                        }
                        start()
                    }
                } else {
                    navIndicator.translationX = targetX
                    navIndicatorPosition = selectedIndex.toFloat()
                    navIndicator.scaleX = 1f
                    navIndicator.scaleY = 1f
                    navIndicatorScale = 1f
                }
            }
        }
        runningBefore = TrackingService.state.running
    }
    private fun setup(first: Boolean) {
        if(first) {
            onboarding()
            return
        }
        page.addView(text("使い方・初期設定", 28f, ink, true))
        page.addView(button("オンボーディングを見る", true) {
            if(TrackingService.state.running) { toast("計測を終了してから開いてください"); return@button }
            store.prefs.edit().putBoolean("configured", false).putInt("onboardingStep", 0).apply(); render()
        })
        line()
        page.addView(text("計算に使う時給", 28f, ink, true))
        space(12); paragraph("ここでの金額は、あなたが決めた時間価値の目安です。実際の損失や収入ではありません。")
        space(24)
        val choices = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val hourly = RadioButton(this).apply { id = View.generateViewId(); text = "時給"; setTextColor(ink) }
        val monthly = RadioButton(this).apply { id = View.generateViewId(); text = "月給"; setTextColor(ink) }
        choices.addView(hourly); choices.addView(monthly)
        choices.check(if(store.prefs.getBoolean("monthly", false)) monthly.id else hourly.id)
        page.addView(choices)
        val amount = field("金額（円）", store.prefs.getString("inputAmount", "1800")!!, true)
        val hours = field("月の労働時間", store.prefs.getString("inputHours", "160")!!, true)
        page.addView(amount)
        val hoursLabel = text("月間労働時間（時間）", 12f, muted)
        page.addView(hoursLabel); page.addView(hours)
        fun showHours() { hours.visibility = if(monthly.isChecked) View.VISIBLE else View.GONE; hoursLabel.visibility = hours.visibility }
        showHours(); choices.setOnCheckedChangeListener { _, _ -> showHours() }
        page.addView(button("単価を保存", true) { saveRate(amount, hours, monthly.isChecked) { tab = "home"; render() } })
        space(12); paragraph("時給 ${Cost.money(store.rate)}\n0.1秒あたり ${Cost.money(Cost.yen(100, store.rate))}\n保存済みの記録は、計測時の単価を保持します。")
        line(); permissions()
        line(); paragraph("プライバシー\n使用アプリ名・時間・設定した単価を端末内に保存します。閲覧内容は読み取りません。ネット送信・ログイン・広告SDKはありません。\n\nYouTubeはアプリ全体を計測します。Shortsだけの判別、分割画面・PiPの正確な判定は未対応です。")
    }

    private fun onboarding() {
        val step = store.prefs.getInt("onboardingStep", 0).coerceIn(0, 5)
        fun go(next: Int) { store.prefs.edit().putInt("onboardingStep", next).apply(); render() }
        fun finish() { store.prefs.edit().putBoolean("configured", true).putBoolean("setupDashboardSkipped", true).apply(); tab = "home"; render() }
        val progress = LinearLayout(this)
        repeat(6) { index -> progress.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(if(index <= step) accent else Brand.surface); cornerRadius = dp(3).toFloat() }
        }, LinearLayout.LayoutParams(0, dp(4), 1f).apply { marginEnd = dp(6) }) }
        page.addView(progress); space(24); label("SETUP  /  0${step + 1}"); space(14)
        when(step) {
            0 -> {
                page.addView(text("そのスクロール、\nもし働いてたら？", 36f, ink, true))
                space(16); paragraph("もしもドパガキが働いたら。\n見るのは禁止しない。ただ、時間に値札を。")
                space(32)
                val preview = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(22), dp(24), dp(22))
                    background = GradientDrawable().apply { setColor(Brand.surface); cornerRadius = dp(28).toFloat() }
                }
                preview.addView(text("●  DEMO  /  金額の表示見本", 12f, accent, true))
                demoStarted = SystemClock.elapsedRealtime()
                demoAmount = bigMoney("¥0.00")
                preview.addView(demoAmount)
                preview.addView(text("時給 ${Cost.money(store.rate)}で換算 · 記録されません", 12f, muted))
                page.addView(preview); space(24)
                page.addView(button("試してみる  →", true) { go(1) })
                space(12); paragraph("データは端末内に保存。ログイン・広告なし。")
            }
            1 -> {
                page.addView(text("時給、いくらで\n計算する？", 36f, ink, true)); space(24)
                val choices = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
                val hourly = RadioButton(this).apply { id = View.generateViewId(); text = "時給"; setTextColor(ink); minHeight = dp(48) }
                val monthly = RadioButton(this).apply { id = View.generateViewId(); text = "月給"; setTextColor(ink); minHeight = dp(48) }
                choices.addView(hourly); choices.addView(monthly)
                choices.check(if(store.prefs.getBoolean("monthly", false)) monthly.id else hourly.id)
                page.addView(choices); space(12); label("換算に使う金額（円）"); space(8)
                val amount = field("金額", store.prefs.getString("inputAmount", "1226")!!, true)
                page.addView(amount); rememberDraft(amount, "inputAmount")
                val note = text("", 12f, muted); page.addView(note)
                val hoursLabel = text("月間労働時間（時間）", 12f, muted)
                val hours = field("月の労働時間", store.prefs.getString("inputHours", "160")!!, true)
                page.addView(hoursLabel); page.addView(hours); rememberDraft(hours, "inputHours")
                fun refresh() {
                    hours.visibility = if(monthly.isChecked) View.VISIBLE else View.GONE; hoursLabel.visibility = hours.visibility
                    note.text = if(!monthly.isChecked && amount.text.toString() == "1226") "※2026年9月7日時点の東京都最低賃金を初期値にしています。変更できます。" else "あなたが指定した金額で換算します。実際の収入ではありません。"
                }
                choices.setOnCheckedChangeListener { _, _ -> store.prefs.edit().putBoolean("monthly", monthly.isChecked).apply(); refresh() }
                amount.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
                    override fun afterTextChanged(s: android.text.Editable?) = Unit
                }); refresh(); space(24)
                page.addView(button("この金額で進む  →", true) { saveRate(amount, hours, monthly.isChecked) { go(2) } })
                page.addView(button("自動計測はあとで設定してホームへ") { saveRate(amount, hours, monthly.isChecked) { finish() } })
            }
            2 -> {
                page.addView(text("使っているアプリを、\n見つけるために。", 32f, ink, true)); space(20)
                paragraph("「使用状況へのアクセス」を許可すると、選んだアプリの利用時間を計測できます。動画やメッセージの内容は読み取りません。")
                line(); label(if(Access.usage(this)) "✓  許可されています" else "○  まだ許可されていません")
                page.addView(button(if(Access.usage(this)) "次へ  →" else "使用状況の設定を開く", true) {
                    if(Access.usage(this)) go(3) else launchSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
                })
                paragraph("設定画面で「もしドパ」を選んで許可し、戻ってください。")
            }
            3 -> {
                page.addView(text("どのアプリに、\n値札をつける？", 36f, ink, true)); space(20)
                paragraph("つい見続けてしまうアプリだけで大丈夫。あとから変更できます。")
                space(24); page.addView(button("対象アプリを選ぶ  ·  ${store.targets.size}件") { chooseApps() })
                page.addView(button("選んだアプリで進む  →", true) { if(store.targets.isEmpty()) toast("1つ以上選んでください") else go(4) })
                space(16); paragraph("YouTubeはアプリ全体を計測します。Shortsだけの判別はできません。")
            }
            4 -> {
                page.addView(text("動画の横に、\n小さな値札を。", 36f, ink, true)); space(20)
                paragraph("「他のアプリの上に表示」を許可してください。値札はドラッグで移動でき、計測はいつでも終了できます。")
                space(24); page.addView(bigMoney("¥842.35")); label("表示イメージ  /  実際の支出ではありません")
                space(24); page.addView(button(if(Settings.canDrawOverlays(this)) "表示を確認する  →" else "重ねて表示を許可する", true) {
                    if(Settings.canDrawOverlays(this)) go(5) else launchSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                })
            }
            5 -> {
                page.addView(text("準備できました。\nいつものアプリへ。", 34f, ink, true)); space(20)
                paragraph("自動計測をはじめて、選んだアプリを開きます。アプリを離れると今回の明細が表示されます。")
                if(Build.VERSION.SDK_INT >= 33 && !notificationsAllowed()) page.addView(button("通知も許可する（任意）") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) })
                space(24); page.addView(button("計測して対象アプリを開く  ↗", true) {
                    if(setupMissing().isNotEmpty()) { toast("必要な権限・対象アプリを確認してください"); go(2); return@button }
                    val launch = store.targets.sorted().firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
                    finish(); startTracking(TrackingService.AUTO)
                    if(launch != null) runCatching { startActivity(launch) }.onFailure { toast("対象アプリを手動で開いてください") }
                })
                page.addView(button("ホームへ") { finish() })
            }
        }
        if(step > 0) { space(12); page.addView(button("←  戻る") { go(step - 1) }) }
        if(step >= 2) page.addView(button("自動計測はあとで設定する") { finish() })
    }

    private fun rememberDraft(edit: EditText, key: String) {
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                store.prefs.edit().putString(key, s?.toString().orEmpty()).apply()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun saveRate(amount: EditText, hours: EditText, monthly: Boolean, onSuccess: () -> Unit) {
        if(TrackingService.state.running) { toast("単価を変更する前に計測を終了してください"); return }
        val rate = runCatching { Cost.hourly(amount.text.toString().toDouble(), monthly, hours.text.toString().toDoubleOrNull() ?: 0.0) }.getOrNull()
        if(rate == null || rate > 1_000_000_000) { amount.error = "正しい金額と労働時間を入力してください"; return }
        store.prefs.edit().putBoolean("monthly", monthly)
            .putString("inputAmount", amount.text.toString()).putString("inputHours", hours.text.toString()).putString("rate", rate.toString()).apply()
        onSuccess()
    }

    private fun setupMissing(): List<String> = buildList {
        if(store.targets.isEmpty()) add("対象アプリ")
        if(!Access.usage(this@MainActivity)) add("使用状況へのアクセス")
        if(!Settings.canDrawOverlays(this@MainActivity)) add("重ねて表示")
    }

    private fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun permissions() {
        page.addView(Switch(this).apply {
            text = "アプリ終了時に明細を表示"; setTextColor(ink); minHeight = dp(48)
            isChecked = store.prefs.getBoolean("showSessionReceipt", true)
            setOnCheckedChangeListener { _, value -> store.prefs.edit().putBoolean("showSessionReceipt", value).apply() }
        })
        paragraph("使った時間の金額を、紙の明細で表示します")
        val milestones = Switch(this).apply {
            text = "10円・100円単位で品物を表示"; setTextColor(ink); minHeight = dp(48)
            isChecked = store.prefs.getBoolean("milestones", true)
            setOnCheckedChangeListener { _, value -> store.prefs.edit().putBoolean("milestones", value).apply() }
        }
        page.addView(milestones)
        page.addView(Switch(this).apply {
            text = "落下・着地アニメーション"; setTextColor(ink); minHeight = dp(48)
            isChecked = store.prefs.getBoolean("motion", true)
            setOnCheckedChangeListener { _, value -> store.prefs.edit().putBoolean("motion", value).apply() }
        })
        paragraph("今回の換算金額が10〜90円では10円ごと、100〜900円では100円ごと、1000円以降は200円ごとに品物を1つ表示します。落下中に次の金額へ到達した場合は、落下完了後に順番に表示します。購入個数や実際の支出ではありません。")
        label("AUTO TRACKING / 必要な許可")
        paragraph("選んだアプリを使っている間だけ、金額の値札を重ねて表示します。いつでも通知から終了できます。")
        page.addView(button("${if(Access.usage(this)) "✓" else "1."} 使用状況へのアクセス") {
            launchSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
        })
        page.addView(button("${if(Settings.canDrawOverlays(this)) "✓" else "2."} 他のアプリの上に表示") {
            launchSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            page.addView(button("3. 停止操作のために通知を許可") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) })
        }
    }
    private fun home() {
        val s = TrackingService.state
        if(s.running && !s.manual) {
            page.addView(text("もしもドパガキが\n働いたら？", 36f, ink, true))
            space(28); page.addView(text("● 自動計測 ON", 18f, accent, true))
            space(14); paragraph("準備できています。\n対象アプリを開くと、使っている時間の値段が表示されます。")
            space(20); paragraph("値札はドラッグで移動できます。\n対象アプリを離れると、今回の明細が表示されます。")
            space(20); page.addView(button("STOP  /  計測を終了", true) { startService(Intent(this, TrackingService::class.java).setAction(TrackingService.STOP)) })
            return
        }
        if(s.running) {
            activityView = text(if(s.manual) "${s.title} / ${modeLabel(s.mode)}" else "AUTO TRACKING / LIVE", 16f, muted)
            page.addView(activityView)
            space(28); amountView = bigMoney(Cost.money(s.amount)); page.addView(amountView)
            todayView = text("今日のトータル  ${Cost.money(s.today)}", 14f, todayMuted).apply {
                setPadding(0, dp(2), 0, 0)
            }
            page.addView(todayView)
            elapsedView = text(Cost.time(s.elapsed), 18f, muted); page.addView(elapsedView)
            space(12); label("時給 ${Cost.money(store.rate)}")
            space(30); paragraph(if(s.manual) "あなたの時間が、金額になる。\n画面を閉じても手動計測は続きます。" else "対象のアプリを開いてください。\n今回のセッション額と、今日のトータルを表示します。値札はドラッグで移動できます。")
            space(20); page.addView(button("STOP  /  計測を終了", true) { startService(Intent(this, TrackingService::class.java).setAction(TrackingService.STOP)) })
            return
        }
        page.addView(text("スクロールに、\n値札を。", 38f, ink, true))
        space(24); label("TODAY  /  今日、この時間働いていたら")
        val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        page.addView(bigMoney(Cost.money(store.records().filter { it.start >= todayStart }.sumOf { it.amount })))
        space(18); paragraph("時給 ${Cost.money(store.rate)}  /  0.1秒 ${Cost.money(Cost.yen(100, store.rate))}")
        line(); label("01 / スマホの時間を自動で計測")
        space(8); paragraph("アプリを開くと、今回のセッション額と今日のトータルが増えていきます。")
        page.addView(button("対象アプリを選ぶ  ·  ${store.targets.size}件") { chooseApps() })
        if(!Access.usage(this) || !Settings.canDrawOverlays(this)) {
            space(14); paragraph("自動計測には「使用状況」と「重ねて表示」の許可が必要です。")
            page.addView(button("自動計測を設定する") { store.prefs.edit().putBoolean("configured", false).putInt("onboardingStep", 2).apply(); render() })
        }
        page.addView(button("自動計測をはじめる  ↗", true) {
            if(store.targets.isEmpty()) { toast("まず対象アプリを選んでください"); chooseApps(); return@button }
            if(!Access.usage(this) || !Settings.canDrawOverlays(this)) { toast("2つの許可を設定してください"); return@button }
            startTracking(TrackingService.AUTO)
        })
        if(s.message.isNotBlank()) { space(12); paragraph(s.message) }
        line(); label("02 / スマホの外の時間を手動で計測")
        val investMode = mode == "INVEST"
        val modeToggle = SwitchMaterial(this).apply {
            isChecked = investMode
            contentDescription = "自己投資モード ${if(investMode) "オン" else "オフ"}"
            minimumWidth = dp(56)
            minHeight = dp(48)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, 0xffb4b8aa.toInt()),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x99d6ff3f.toInt(), 0x6651584d),
            )
        }
        val modeLabelView = text("自己投資モード", 14f, if(investMode) accent else muted, true)
        val modeRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = true
            foreground = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33ffffff), null,
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() },
            )
            addView(modeLabelView, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(modeToggle, LinearLayout.LayoutParams(dp(64), dp(48)))
            setOnClickListener { modeToggle.toggle() }
        }
        page.addView(modeRow, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4); bottomMargin = dp(2) })
        val input = field(if(mode == "SPEND") "例：ぼーっとする" else "例：個人開発", activityDraft)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { activityDraft = s.toString() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        page.addView(input)
        val investFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if(investMode) View.VISIBLE else View.GONE
        }
        investFields.addView(text("CATEGORY  /  分類", 12f, muted, true))
        val categories = listOf("個人開発", "ポートフォリオ", "学習", "創作", "その他")
        val categoryRow = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val categoryButtons = categories.map { category ->
            RadioButton(this).apply {
                id = View.generateViewId(); text = category; setTextColor(ink); textSize = 13f; minHeight = dp(44)
                isChecked = category == categoryDraft
                setOnCheckedChangeListener { _, checked -> if(checked) categoryDraft = category }
            }
        }
        categoryButtons.forEach { categoryRow.addView(it, RadioGroup.LayoutParams(-2, dp(44))) }
        investFields.addView(categoryRow)
        val project = field("プロジェクト名（任意）", projectDraft)
        project.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { projectDraft = s?.toString().orEmpty() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        investFields.addView(project, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val note = field("今日やったこと（任意）", noteDraft)
        note.setSingleLine(false); note.minLines = 2; note.maxLines = 4; note.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        note.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { noteDraft = s?.toString().orEmpty() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        investFields.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        page.addView(investFields, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val startButton = button(if(mode == "SPEND") "START SPEND" else "START INVEST", true) {
            startTracking(
                TrackingService.MANUAL,
                activityDraft.ifBlank { if(mode == "SPEND") "ぼーっとする" else categoryDraft },
                project = if(mode == "INVEST") projectDraft else "",
                category = if(mode == "INVEST") categoryDraft else "",
                note = if(mode == "INVEST") noteDraft else "",
            )
        }
        page.addView(startButton)
        modeToggle.setOnCheckedChangeListener { _, checked ->
            mode = if(checked) "INVEST" else "SPEND"
            store.prefs.edit().putBoolean("investMode", checked).apply()
            modeLabelView.setTextColor(if(checked) accent else muted)
            modeToggle.contentDescription = "自己投資モード ${if(checked) "オン" else "オフ"}"
            input.hint = if(checked) "例：個人開発" else "例：ぼーっとする"
            investFields.visibility = if(checked) View.VISIBLE else View.GONE
            startButton.text = if(checked) "START INVEST" else "START SPEND"
        }
    }
    private fun startTracking(
        action: String,
        title: String = "",
        project: String = "",
        category: String = "",
        note: String = "",
    ) {
        runCatching {
            startForegroundService(Intent(this, TrackingService::class.java).setAction(action)
                .putExtra("title", title).putExtra("mode", mode)
                .putExtra("project", project).putExtra("category", category).putExtra("note", note))
        }.onFailure { toast("計測を開始できませんでした。権限を確認してください。") }
        handler.postDelayed({ if(!isDestroyed) render() }, 200)
    }
    @Suppress("DEPRECATION")
    private fun chooseApps() {
        if(TrackingService.state.running) { toast("対象アプリの変更は計測終了後に行ってください"); return }
        if(pendingPicker) return
        pendingPicker = true; toast("アプリ一覧を読み込み中…")
        Thread {
            val apps = runCatching {
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
                    .distinctBy { it.first }.sortedBy { it.second.lowercase() }
            }.getOrDefault(emptyList())
            runOnUiThread {
                pendingPicker = false
                if(isFinishing || isDestroyed) return@runOnUiThread
                val chosen = store.targets.toMutableSet()
                val dialog = AlertDialog.Builder(this).setTitle("金額を表示するアプリ（${chosen.size}件）")
                    .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), apps.map { it.first in chosen }.toBooleanArray()) { d, i, checked ->
                        if(checked) chosen.add(apps[i].first) else chosen.remove(apps[i].first)
                        (d as AlertDialog).setTitle("金額を表示するアプリ（${chosen.size}件）")
                        d.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "保存（${chosen.size}件）"
                    }
                    .setPositiveButton("保存（${chosen.size}件）") { _, _ -> store.prefs.edit().putStringSet("targets", chosen).apply(); render() }
                    .setNeutralButton("全選択", null)
                    .setNegativeButton("キャンセル", null)
                    .create()
                dialog.setOnShowListener {
                    val allSelected = { apps.isNotEmpty() && apps.all { it.first in chosen } }
                    val updateButtons = {
                        dialog.setTitle("金額を表示するアプリ（${chosen.size}件）")
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "保存（${chosen.size}件）"
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text = if(allSelected()) "全解除" else "全選択"
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = apps.isNotEmpty()
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        if(allSelected()) chosen.clear() else { chosen.clear(); chosen.addAll(apps.map { it.first }) }
                        apps.indices.forEach { dialog.listView.setItemChecked(it, allSelected()) }
                        updateButtons()
                    }
                    updateButtons()
                }
                dialog.show()
            }
        }.start()
    }
    private fun history() {
        val days = com.example.timecostview.domain.HistorySummary.days(store.records())
        val visibleDates = (0..6).map { historyAnchorDay.plusDays(it.toLong()) }
        if(historyDay.isBefore(visibleDates.first()) || historyDay.isAfter(visibleDates.last())) {
            historyDay = visibleDates.first()
        }
        val records = days[historyDay].orEmpty()
        val selectedAmount = records.sumOf { it.amount }

        page.addView(text("その日、\n働いていたら。", 32f, ink, true)); space(6)
        // The selected day's amount is the first thing the eye meets, before the chart.
        page.addView(bigMoney(Cost.money(selectedAmount)))
        paragraph("合計 ${Cost.time(records.sumOf { it.duration })}  ·  ${records.size}件")
        space(10)

        val dateButton = text(historyPickerLabel(historyDay), 13f, ink, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isClickable = true
            isFocusable = true
            contentDescription = "カレンダーを開く。選択中は${historyDateLabel(historyDay)}"
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x2bffffff),
                null,
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(22).toFloat()
                },
            )
            setOnClickListener {
                android.app.DatePickerDialog(this@MainActivity, { _, y, m, d ->
                    val selected = LocalDate.of(y, m + 1, d)
                    historyDay = selected
                    if(selected.isBefore(historyAnchorDay) || selected.isAfter(historyAnchorDay.plusDays(6))) {
                        historyAnchorDay = historyWeekStart(selected)
                    }
                    expandedGroup = null
                    render()
                }, historyDay.year, historyDay.monthValue - 1, historyDay.dayOfMonth).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                }.show()
            }
        }
        page.addView(dateButton, LinearLayout.LayoutParams(-2, dp(44)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // A broad off-screen buffer keeps direct manipulation continuous while
        // still allowing a fast gesture to settle on a calendar-week boundary.
        val chartScroll = HistoryScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val chart = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        val chartStart = historyAnchorDay.minusDays(14)
        val chartDates = (0..34).map { chartStart.plusDays(it.toLong()) }
        val maximum = chartDates.maxOf { day -> days[day].orEmpty().sumOf { it.amount } }.coerceAtLeast(1.0)
        val columnWidth = dp(44)
        chartDates.forEach { day ->
            val total = days[day].orEmpty().sumOf { it.amount }
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                minimumWidth = columnWidth; isClickable = true; isFocusable = true
                contentDescription = "$day、${Cost.money(total)}"; isSelected = day == historyDay
                val track = FrameLayout(this@MainActivity)
                track.addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(if(day == historyDay) accent else 0xff78834f.toInt())
                        cornerRadius = dp(6).toFloat()
                    }
                }, FrameLayout.LayoutParams(
                    dp(24),
                    if(total > 0) dp((total / maximum * 90).toInt().coerceIn(3, 90)) else dp(1),
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ))
                addView(track, LinearLayout.LayoutParams(-1, dp(100)))
                addView(text(historyDateShortLabel(day), 11f, if(day == historyDay) accent else muted).apply { gravity = Gravity.CENTER })
                setOnClickListener {
                    historyDay = day
                    if(day.isBefore(historyAnchorDay)) historyAnchorDay = day
                    if(day.isAfter(historyAnchorDay.plusDays(6))) historyAnchorDay = day.minusDays(6)
                    expandedGroup = null
                    render()
                }
            }
            chart.addView(column, LinearLayout.LayoutParams(columnWidth, dp(136)))
        }
        chartScroll.addView(chart, LinearLayout.LayoutParams(-2, dp(152)))
        page.addView(chartScroll, LinearLayout.LayoutParams(-1, dp(152)))
        chartScroll.post { chartScroll.scrollTo(columnWidth * 14, 0) }

        chartScroll.onGestureSettled = { wasDragged, velocityX, durationMs, distancePx ->
            if(wasDragged) {
                chartScroll.post {
                    if(isFinishing || isDestroyed) return@post
                    val nearestIndex = (chartScroll.scrollX.toFloat() / columnWidth)
                        .roundToInt()
                        .coerceIn(0, chartDates.size - 7)
                    val nearestStart = chartStart.plusDays(nearestIndex.toLong())
                    val requestedAnchor = HistoryWindow.settleAnchor(
                        nearestVisibleStart = nearestStart,
                        velocityX = velocityX,
                        durationMs = durationMs,
                        distancePx = distancePx,
                        columnWidthPx = columnWidth,
                        fastVelocityPx = 900,
                    )
                    val targetIndex = ChronoUnit.DAYS.between(chartStart, requestedAnchor)
                        .toInt()
                        .coerceIn(0, chartDates.size - 7)
                    val targetAnchor = chartStart.plusDays(targetIndex.toLong())
                    chartScroll.settleTo(targetIndex * columnWidth) {
                        if(isFinishing || isDestroyed) return@settleTo
                        historyAnchorDay = targetAnchor
                        val end = historyAnchorDay.plusDays(6)
                        if(historyDay.isBefore(historyAnchorDay)) historyDay = historyAnchorDay
                        if(historyDay.isAfter(end)) historyDay = end
                        expandedGroup = null
                        render()
                    }
                }
            }
        }

        space(16)
        label("この日の記録")
        if(records.isEmpty()) { space(24); paragraph("この日の記録はありません。日付を選んで確認できます。") }
        com.example.timecostview.domain.HistorySummary.groups(records).forEach { group ->
            val expanded = expandedGroup == group.key
            page.addView(button("${if(expanded) "▾" else "▸"} ${group.title}  ·  ${modeLabel(group.mode)}\n${Cost.money(group.amount)}  /  ${Cost.time(group.duration)}  /  ${group.records.size}回") {
                expandedGroup = if(expanded) null else group.key; render()
            })
            if(expanded) {
                page.addView(button("このアプリの1日分を共有") {
                    val hide = CheckBox(this).apply { text = "時間を隠す"; isChecked = true; setPadding(dp(24), dp(12), dp(24), dp(12)) }
                    AlertDialog.Builder(this).setTitle("1日分の明細を共有").setView(hide)
                        .setMessage("時間と金額を両方載せると、計算に使った時給を推測できます。")
                        .setPositiveButton("共有") { _, _ ->
                            runCatching { ReceiptRenderer.share(this, StatementBuilder.group(group.records, StatementPeriod.DAY, group.mode), hide.isChecked) }.onFailure { toast("共有できませんでした") }
                        }.setNegativeButton("キャンセル", null).show()
                })
                group.records.forEach { r ->
                    page.addView(button("${date(r.start)}  ·  ${Cost.time(r.duration)}\n${Cost.money(r.amount)}  明細を見る") {
                        result = r; statement = null; tab = "result"; render()
                    })
                }
            }
        }
        page.addView(button("日給・月給明細を作る") {
            statementDay = historyDay
            historyMonth = YearMonth.from(historyDay)
            historyPeriod = StatementPeriod.DAY
            historyMode = mode
            statementProject = ""
            statement = null
            tab = "statements"
            render()
        })
        space(16); paragraph("日をまたぐ記録は日別に分けて表示します。金額はそれぞれの記録に保存された時給で計算します。")
    }

    /** Statements are an optional destination; history keeps its original chart and gestures. */
    private fun statementComposer() {
        page.addView(button("‹ 履歴に戻る") { tab = "history"; render() })
        space(18)
        page.addView(text("明細を作る", 30f, ink, true))
        paragraph("期間を選んで、通帳形式の明細を共有できます。")
        space(16)
        fun choiceRow(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            options.forEachIndexed { index, (title, selected) ->
                row.addView(button(title, selected) { onSelect(index) }.apply {
                    textSize = 14f
                    contentDescription = title
                    isSelected = selected
                }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if(index > 0) marginStart = dp(8)
                })
            }
            page.addView(row)
        }
        choiceRow(listOf("日給明細" to (historyPeriod == StatementPeriod.DAY),
            "月給明細" to (historyPeriod == StatementPeriod.MONTH))) {
            historyPeriod = if(it == 0) StatementPeriod.DAY else StatementPeriod.MONTH
            render()
        }
        space(10)
        choiceRow(listOf("SPEND / 消費" to (historyMode == "SPEND"),
            "INVEST / 自己投資" to (historyMode == "INVEST"))) {
            historyMode = if(it == 0) "SPEND" else "INVEST"
            statementProject = ""
            render()
        }
        space(12)
        page.addView(button(if(historyPeriod == StatementPeriod.MONTH)
            "${historyMonth.year}年${historyMonth.monthValue}月  ▾"
            else "${statementDay.year}年${statementDay.monthValue}月${statementDay.dayOfMonth}日  ▾") {
            if(historyPeriod == StatementPeriod.MONTH) {
                val months = (com.example.timecostview.domain.HistorySummary.months(store.records()).keys
                    + listOf(YearMonth.now(), historyMonth)).distinct().sortedDescending()
                AlertDialog.Builder(this).setTitle("月を選択")
                    .setItems(months.map { "${it.year}年${it.monthValue}月" }.toTypedArray()) { _, index ->
                        historyMonth = months[index]; render()
                    }.setNegativeButton("キャンセル", null).show()
            } else {
                android.app.DatePickerDialog(this, { _, y, m, d ->
                    statementDay = LocalDate.of(y, m + 1, d)
                    historyMonth = YearMonth.from(statementDay)
                    render()
                }, statementDay.year, statementDay.monthValue - 1, statementDay.dayOfMonth).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                }.show()
            }
        })
        val records = store.records()
        val projects = records.filter { it.mode == "INVEST" }.map { it.project }.filter { it.isNotBlank() }.distinct().sorted()
        if(historyMode == "INVEST" && projects.isNotEmpty()) {
            page.addView(button("プロジェクト：${statementProject.ifBlank { "すべて" }}  ▾") {
                val values = listOf("") + projects
                AlertDialog.Builder(this).setTitle("プロジェクト")
                    .setItems(values.map { it.ifBlank { "すべて" } }.toTypedArray()) { _, index ->
                        statementProject = values[index]; render()
                    }.setNegativeButton("キャンセル", null).show()
            })
        }
        val selectedRecords = records.filter { statementProject.isBlank() || it.project == statementProject }
        val selected = if(historyPeriod == StatementPeriod.MONTH)
            StatementBuilder.month(selectedRecords, historyMonth, historyMode)
        else StatementBuilder.day(selectedRecords, statementDay, historyMode)
        space(20)
        paragraph(selected.rangeLabel)
        paragraph("${selected.lines.size}項目  ·  ${Cost.time(selected.duration)}")
        if(selected.lines.isEmpty()) {
            paragraph("この期間の記録はありません。")
        } else {
            page.addView(button("通帳明細をプレビュー", true) {
                statement = selected
                statementHideTime = true
                statementReturnTab = "statements"
                tab = "statementPreview"
                render()
            })
        }
    }

    /** The preview uses the exact bitmap renderer used by Android's share sheet. */
    private fun statementPreview() {
        val value = statement ?: run { tab = "statements"; statementComposer(); return }
        page.addView(button("‹ ${if(statementReturnTab == "history") "履歴" else if(statementReturnTab == "result") "結果" else "明細の設定"}に戻る") {
            tab = statementReturnTab
            render()
        })
        space(16)
        val preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "${value.title}。\n" + value.lines.joinToString("\n") {
                "${it.label}、${Cost.money(it.amount)}"
            } + "\n合計 ${Cost.money(value.amount)}"
        }
        var bitmap = ReceiptRenderer.render(value, statementHideTime)
        preview.setImageBitmap(bitmap)
        page.addView(preview, LinearLayout.LayoutParams(-1, -2))
        space(12)
        val hide = CheckBox(this).apply {
            text = "シェア画像では時間を隠す"
            setTextColor(ink)
            minHeight = dp(48)
            isChecked = statementHideTime
            setOnCheckedChangeListener { _, checked ->
                statementHideTime = checked
                val old = bitmap
                bitmap = ReceiptRenderer.render(value, checked)
                preview.setImageBitmap(bitmap)
                old.recycle()
            }
        }
        page.addView(hide)
        paragraph("この画像がそのまま共有されます。")
        page.addView(button("明細を画像で共有", true) {
            runCatching { ReceiptRenderer.share(this, value, statementHideTime) }
                .onFailure { toast("画像を共有できませんでした") }
        })
    }

    private fun renderSessionReceiptWorld(record: Record) {
        window.statusBarColor = SessionReceiptPalette.world
        window.navigationBarColor = SessionReceiptPalette.world
        val root = FrameLayout(this).apply { setBackgroundColor(SessionReceiptPalette.world) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.core.view.WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        fun outsideAction(label: String, primary: Boolean = false, action: () -> Unit) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if(primary) Color.WHITE else SessionReceiptPalette.ink)
            minHeight = dp(48); isClickable = true; isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(if(primary) 0x32ffffff else 0x22000000),
                GradientDrawable().apply {
                    setColor(if(primary) 0xff282723.toInt() else 0xfff7f3e9.toInt())
                    cornerRadius = dp(24).toFloat()
                    if(!primary) setStroke(dp(1), 0xffcfc8b8.toInt())
                }, null,
            )
            setOnClickListener { action() }
        }

        val paperView = SessionReceiptPaperView(this).apply {
            content = SessionReceiptBuilder.from(record)
            hideTime = sessionHideTime
            revealProgress = 1f
            edgeFlex = 0f
        }
        val paperScroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            elevation = dp(12).toFloat()
            addView(paperView, FrameLayout.LayoutParams(-1, -2))
        }
        var animation: ValueAnimator? = null
        fun finishPrint() {
            animation?.cancel(); animation = null
            paperScroll.translationY = 0f; paperScroll.rotation = 0f; paperScroll.scaleY = 1f
            paperView.edgeFlex = 0f; paperView.revealProgress = 1f
            receiptAnimationPlayedId = record.id
        }
        paperView.isClickable = true
        paperView.setOnClickListener { finishPrint() }

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(outsideAction("閉じる") { tab = "history"; statement = null; render() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
            addView(outsideAction("共有", true) {
                finishPrint()
                runCatching { SessionReceiptRenderer.share(this@MainActivity, record, sessionHideTime) }
                    .onFailure { toast("画像を共有できませんでした") }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        }
        val hide = CheckBox(this).apply {
            text = "共有では時間を隠す"; textSize = 13f; setTextColor(SessionReceiptPalette.ink)
            minHeight = dp(48); isChecked = sessionHideTime
            buttonTintList = ColorStateList.valueOf(0xff31302b.toInt())
            setOnCheckedChangeListener { _, checked -> sessionHideTime = checked; paperView.hideTime = checked }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(14), dp(20), dp(12)); isClickable = true
            addView(controls, LinearLayout.LayoutParams(-1, dp(48)))
            addView(paperScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
            addView(hide, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(4) })
        }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        root.isClickable = true
        root.setOnClickListener { tab = "history"; statement = null; render() }
        setContentView(root)

        val motion = store.prefs.getBoolean("motion", true) && ValueAnimator.areAnimatorsEnabled()
        if(motion && receiptAnimationPlayedId != record.id) {
            paperView.revealProgress = 0f; paperView.edgeFlex = 1f
            paperScroll.translationY = -resources.displayMetrics.heightPixels * .72f
            paperScroll.rotation = 1.2f; paperScroll.scaleY = .93f
            animation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1700L
                interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
                addUpdateListener {
                    val t = it.animatedValue as Float
                    val landing = (t / .37f).coerceIn(0f, 1f)
                    paperScroll.translationY = -resources.displayMetrics.heightPixels * .72f * (1f - landing)
                    paperScroll.rotation = 1.2f * (1f - landing)
                    paperScroll.scaleY = .93f + .07f * landing
                    paperView.edgeFlex = 1f - landing
                    paperView.revealProgress = t
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        receiptAnimationPlayedId = record.id
                    }
                })
                start()
            }
        } else finishPrint()
    }

    private fun receipt() {
        tab = "history"
        history()
    }
    private fun date(time: Long) = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.JAPAN).format(Date(time))
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun launchSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast("端末の設定から権限を変更してください") }
    }
}
