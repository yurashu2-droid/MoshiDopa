package com.example.timecostview.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.content.pm.ApplicationInfo
import com.example.timecostview.R
import com.example.timecostview.domain.FallingItem
import com.example.timecostview.overlay.physics.CollisionEvent
import com.example.timecostview.overlay.physics.FallingWorld
import com.example.timecostview.overlay.physics.ItemShapes
import com.example.timecostview.overlay.physics.WorldObstacle
import kotlin.math.roundToInt
import java.util.EnumMap
import java.util.Random

/**
 * Full-screen transparent, non-interactive physics layer for live price-tag feedback.
 * The price tag remains in a separate window above this layer and owns all input.
 */
class MilestoneView(context: Context) : View(context) {
    companion object {
        private const val MAX_OBJECTS = 1
        private const val STATIC_LIFETIME_NANOS = 1_200_000_000L
    }

    private val density = resources.displayMetrics.density
    private val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val random = Random(0x54494D45L)
    private val world = FallingWorld(density, MAX_OBJECTS)
    private val drawableCache = EnumMap<FallingItem, Drawable>(FallingItem::class.java)
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xffd16b58.toInt()
    }
    private val debugPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xffb4493e.toInt()
    }
    private var obstacle: WorldObstacle? = null
    private var framePosted = false
    private var lastFrameNanos = 0L
    private var debugOverlay = false
    var onCollision: ((CollisionEvent) -> Unit)? = null

    private val frame = object : Runnable {
        override fun run() {
            framePosted = false
            advance()
        }
    }

    init {
        visibility = GONE
        contentDescription = "時間の経過を表す落下演出"
        setWillNotDraw(false)
    }

    val hasActiveObjects: Boolean get() = world.hasBodies
    val activeObjectCount: Int get() = world.bodies.size

    /** Set the current rounded price-tag obstacle in this view's local coordinates. */
    fun setObstacle(left: Float, top: Float, right: Float, bottom: Float) {
        obstacle = if(right > left && bottom > top) {
            WorldObstacle(left, top, right, bottom, 8f * density)
        } else null
        world.setObstacle(obstacle)
    }

    /** Debug-only geometry overlay; it is off for normal debug and release output. */
    fun setDebugOverlay(enabled: Boolean) {
        debugOverlay = enabled && isDebuggable
        invalidate()
    }

    /** Add one object. The scheduler owns threshold and pending-queue decisions. */
    fun drop(item: FallingItem, motion: Boolean) {
        if(world.hasBodies) return

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val shape = ItemShapes.forItem(item)
        val size = shape.material.sizeDp * density
        val animated = motion && ValueAnimator.areAnimatorsEnabled()
        val currentObstacle = obstacle
        // Launch toward the side with room on screen. Initial momentum, not a surface motor.
        val direction = if((currentObstacle?.let { (it.left + it.right) / 2f } ?: 0f) < screenWidth() / 2f) 1f else -1f
        val centerX = currentObstacle?.let {
            (it.left + it.right) / 2f - direction * 24f * density
        } ?: screenWidth() / 2f
        val launchSpeed = (105f + random.nextFloat() * 15f) * density * direction
        val maxLocalY = shape.pieces.maxOf { piece ->
            (0 until piece.pointCount).maxOf { piece.y(it) }
        }
        val surfaceY = currentObstacle?.surfaceY(centerX) ?: size
        val startY = if(animated) {
            // Spawn 100–160dp above the tag, not at a fixed screen coordinate.
            surfaceY - (100f + random.nextFloat() * 60f) * density - maxLocalY * size
        } else {
            surfaceY - maxLocalY * size
        }
        val added = world.add(
            item = item,
            sizePx = size,
            animated = animated,
            x = centerX,
            y = startY,
            vx = if(animated) launchSpeed else 0f,
            angle = if(animated) (random.nextFloat() - 0.5f) * 0.10f else 0f,
            angularVelocity = if(animated) launchSpeed / (size * 0.34f) * 0.7f else 0f,
            nowNanos = nowNanos,
            staticLifetimeNanos = STATIC_LIFETIME_NANOS
        )
        if(!added) return
        visibility = VISIBLE
        lastFrameNanos = nowNanos
        invalidate()
        scheduleFrame()
    }

    fun clear() {
        removeCallbacks(frame)
        framePosted = false
        lastFrameNanos = 0L
        world.clear()
        visibility = GONE
        invalidate()
    }

    private fun drawableFor(item: FallingItem): Drawable = drawableCache.getOrPut(item) {
        requireNotNull(context.getDrawable(
            when(item) {
                FallingItem.CANDY -> R.drawable.ic_candy
                FallingItem.CHOCOLATE -> R.drawable.ic_chocolate
                FallingItem.COFFEE -> R.drawable.ic_coffee
            }
        )).mutate()
    }

    private fun screenWidth(): Float =
        (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()

    private fun spawnX(size: Float, currentObstacle: WorldObstacle): Float {
        val half = size * 0.52f
        val left = currentObstacle.left + half
        val right = currentObstacle.right - half
        return if(right <= left) (currentObstacle.left + currentObstacle.right) / 2f else
            left + random.nextFloat() * (right - left)
    }

    private fun scheduleFrame() {
        if(!framePosted && world.hasBodies) {
            framePosted = true
            postOnAnimation(frame)
        }
    }

    private fun advance() {
        if(!world.hasBodies) {
            visibility = GONE
            return
        }

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val rawDelta = if(lastFrameNanos == 0L) 0f else
            ((nowNanos - lastFrameNanos).coerceAtLeast(0L) / 1_000_000_000f)
        lastFrameNanos = nowNanos

        // A suspended UI must not teleport an item or replay a backlog of physics steps.
        if(rawDelta > 0.25f) {
            clear()
            return
        }
        val events = world.advance(rawDelta.coerceAtMost(0.05f), nowNanos)
        events.forEach { event -> onCollision?.invoke(event) }

        val iterator = world.bodies.toList().iterator()
        while(iterator.hasNext()) {
            val body = iterator.next()
            val offscreen = body.y - body.sizePx > height + body.sizePx * 2f ||
                body.x < -body.sizePx * 3f || body.x > width + body.sizePx * 3f
            if(offscreen) world.remove(body)
        }

        if(!world.hasBodies) {
            visibility = GONE
            invalidate()
        } else {
            invalidate()
            scheduleFrame()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for(body in world.bodies) {
            val drawable = drawableFor(body.item)
            val half = body.sizePx / 2f
            drawable.setBounds(
                (body.x - half).roundToInt(),
                (body.y - half).roundToInt(),
                (body.x + half).roundToInt(),
                (body.y + half).roundToInt()
            )
            canvas.save()
            canvas.rotate(Math.toDegrees(body.angle.toDouble()).toFloat(), body.x, body.y)
            drawable.draw(canvas)
            canvas.restore()
            if(debugOverlay) drawDebugGeometry(canvas, body)
        }
        if(debugOverlay) obstacle?.let { tag ->
            debugPaint.color = 0xff6b7065.toInt()
            canvas.drawRoundRect(tag.left, tag.top, tag.right, tag.bottom, tag.cornerRadius, tag.cornerRadius, debugPaint)
        }
    }

    private fun drawDebugGeometry(canvas: Canvas, body: FallingWorld.Body) {
        world.worldPolygons(body).forEach { points ->
            if(points.isEmpty()) return@forEach
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            canvas.drawPath(path, debugPaint)
        }
        canvas.drawCircle(body.x, body.y, 2.5f * density, debugPointPaint)
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }
}
