package com.example.timecostview.overlay.physics

import com.example.timecostview.domain.FallingItem
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class WorldPoint(val x: Float, val y: Float)

data class CollisionEvent(
    val objectId: Long,
    val contactPoint: WorldPoint,
    val normal: WorldPoint,
    val normalImpulse: Float,
    val firstImpact: Boolean
)

/** Rounded static obstacle representing the visible price tag. There is intentionally no floor. */
data class WorldObstacle(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadius: Float
) {
    fun surfaceY(x: Float): Float {
        val r = cornerRadius.coerceAtMost(min((right - left) / 2f, (bottom - top) / 2f))
        val px = x.coerceIn(left, right)
        return when {
            px < left + r -> {
                val dx = px - (left + r)
                top + r - sqrt((r * r - dx * dx).coerceAtLeast(0f))
            }
            px > right - r -> {
                val dx = px - (right - r)
                top + r - sqrt((r * r - dx * dx).coerceAtLeast(0f))
            }
            else -> top
        }
    }
}

/**
 * One-body 2D world. It uses a monotonic real delta as input, but integrates at 120 Hz
 * with at most eight steps per draw. The caller clears the world after a long UI stall.
 */
class FallingWorld(private val density: Float, private val maxBodies: Int = 1) {
    companion object {
        private const val FIXED_STEP_SECONDS = 1f / 120f
        private const val MAX_STEPS_PER_FRAME = 8
        private const val SURFACE_TOLERANCE_PX = 8f
        private const val LOW_IMPACT_SPEED_DP = 55f
    }

    class Body(
        val id: Long,
        val item: FallingItem,
        val shape: ItemShape,
        val sizePx: Float,
        val animated: Boolean,
        val createdAtNanos: Long,
        val staticUntilNanos: Long,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var angle: Float,
        var angularVelocity: Float,
        var onSurface: Boolean = false,
        var sleeping: Boolean = false,
        var impactEmitted: Boolean = false
    )

    private val mutableBodies = ArrayList<Body>(maxBodies)
    private val collisionEvents = ArrayList<CollisionEvent>(maxBodies)
    private var nextId = 1L
    private var accumulator = 0f
    private var obstacle: WorldObstacle? = null
    private val gravityPxPerSecondSquared = 1_800f * density

    val bodies: List<Body> get() = mutableBodies
    val hasBodies: Boolean get() = mutableBodies.isNotEmpty()

    fun setObstacle(value: WorldObstacle?) {
        obstacle = value
        for(body in mutableBodies) if(body.sleeping) body.sleeping = false
    }

    fun add(
        item: FallingItem,
        sizePx: Float,
        animated: Boolean,
        x: Float,
        y: Float,
        vx: Float,
        angle: Float,
        angularVelocity: Float,
        nowNanos: Long,
        staticLifetimeNanos: Long
    ): Boolean {
        if(mutableBodies.size >= maxBodies) return false
        val shape = ItemShapes.forItem(item)
        mutableBodies += Body(
            id = nextId++, item = item, shape = shape, sizePx = sizePx, animated = animated,
            createdAtNanos = nowNanos, staticUntilNanos = nowNanos + staticLifetimeNanos,
            x = x, y = y, vx = vx, vy = 0f, angle = angle, angularVelocity = angularVelocity
        )
        return true
    }

    fun clear() {
        mutableBodies.clear()
        collisionEvents.clear()
        accumulator = 0f
    }

    fun remove(body: Body) {
        mutableBodies.remove(body)
    }

    fun removeExpired(nowNanos: Long, maxLifetimeNanos: Long) {
        val iterator = mutableBodies.iterator()
        while(iterator.hasNext()) {
            val body = iterator.next()
            val expired = if(body.animated) {
                nowNanos - body.createdAtNanos >= maxLifetimeNanos
            } else {
                nowNanos >= body.staticUntilNanos
            }
            if(expired) iterator.remove()
        }
    }

    /** Returns a reused event list; consume it before the next call. */
    fun advance(realDeltaSeconds: Float, nowNanos: Long): List<CollisionEvent> {
        collisionEvents.clear()
        removeExpired(nowNanos, 5_500_000_000L)
        if(mutableBodies.isEmpty() || realDeltaSeconds <= 0f) return collisionEvents

        accumulator += realDeltaSeconds.coerceAtMost(0.25f)
        var steps = 0
        while(accumulator >= FIXED_STEP_SECONDS && steps < MAX_STEPS_PER_FRAME) {
            step(FIXED_STEP_SECONDS)
            accumulator -= FIXED_STEP_SECONDS
            steps++
        }
        // Do not chase an arbitrarily old frame after the step budget is exhausted.
        if(steps == MAX_STEPS_PER_FRAME && accumulator >= FIXED_STEP_SECONDS) accumulator = 0f
        removeExpired(nowNanos, 5_500_000_000L)
        return collisionEvents
    }

    fun worldPolygons(body: Body): List<List<WorldPoint>> = body.shape.pieces.map { piece ->
        List(piece.pointCount) { index -> transform(body, piece.x(index), piece.y(index)) }
    }

    private fun step(dt: Float) {
        for(body in mutableBodies) {
            if(!body.animated) continue
            if(body.sleeping) {
                keepSleepingBodyOnSurface(body)
                continue
            }
            if(body.onSurface && advanceOnSurface(body, dt)) continue
            advanceFreeFlight(body, dt)
        }
    }

    private fun advanceOnSurface(body: Body, dt: Float): Boolean {
        val currentObstacle = obstacle ?: run { body.onSurface = false; return false }
        val before = bounds(body)
        if(!isSupported(currentObstacle, before, body.sizePx)) {
            body.onSurface = false
            return false
        }

        body.vy = 0f
        // Contact friction damps existing motion; there is no artificial horizontal kick.
        val rollingRadius = max(body.sizePx * 0.34f, 1f)
        // Screen coordinates have +y down: rightward rolling is clockwise (+angle).
        val targetAngularVelocity = body.vx / rollingRadius
        body.angularVelocity += (targetAngularVelocity - body.angularVelocity) * min(1f, dt * 12f)
        body.vx *= exp((-body.shape.material.friction * 0.25f * dt).toDouble()).toFloat()
        body.angularVelocity *= exp((-body.shape.material.friction * 1.15f * dt).toDouble()).toFloat()
        body.x += body.vx * dt
        body.angle += body.angularVelocity * dt

        val after = bounds(body)
        if(after.bottomContactX < currentObstacle.left || after.bottomContactX > currentObstacle.right) {
            body.onSurface = false
            return false
        }
        body.y += currentObstacle.surfaceY(after.bottomContactX) - after.maxY
        if(abs(body.vx) < 5f * density && abs(body.angularVelocity) < 0.10f) {
            body.vx = 0f
            body.angularVelocity = 0f
            body.sleeping = true
        }
        return true
    }

    private fun keepSleepingBodyOnSurface(body: Body) {
        val currentObstacle = obstacle ?: run { body.sleeping = false; body.onSurface = false; return }
        val current = bounds(body)
        if(isSupported(currentObstacle, current, body.sizePx)) {
            body.y += currentObstacle.surfaceY(current.bottomContactX) - current.maxY
        } else {
            body.sleeping = false
            body.onSurface = false
        }
    }

    private fun advanceFreeFlight(body: Body, dt: Float) {
        val currentObstacle = obstacle
        val before = bounds(body)
        body.vy += gravityPxPerSecondSquared * dt
        body.x += body.vx * dt
        body.y += body.vy * dt
        body.angle += body.angularVelocity * dt
        body.vx *= exp((-0.06f * dt).toDouble()).toFloat()
        body.angularVelocity *= exp((-0.12f * dt).toDouble()).toFloat()

        val after = bounds(body)
        if(currentObstacle != null && body.vy >= 0f &&
            before.maxY <= currentObstacle.top + SURFACE_TOLERANCE_PX * density &&
            topContact(currentObstacle, after, body.sizePx)
        ) {
            collideWithSurface(body, currentObstacle, after)
            return
        }

        // Side walls only exist below the rounded top. There is no bottom/floor collision,
        // so an item that rolls past an edge naturally leaves the tag and falls away.
        if(currentObstacle != null) collideWithSideWall(body, currentObstacle, before, after)
    }

    private fun topContact(currentObstacle: WorldObstacle, bounds: WorldBounds, sizePx: Float): Boolean {
        if(bounds.maxX < currentObstacle.left || bounds.minX > currentObstacle.right) return false
        val x = bounds.bottomContactX
        if(x < currentObstacle.left || x > currentObstacle.right) return false
        return bounds.maxY >= currentObstacle.surfaceY(x) - SURFACE_TOLERANCE_PX * density
    }

    private fun collideWithSurface(body: Body, currentObstacle: WorldObstacle, after: WorldBounds) {
        val contactX = after.bottomContactX.coerceIn(currentObstacle.left, currentObstacle.right)
        val contactY = currentObstacle.surfaceY(contactX)
        body.y += contactY - after.maxY

        val impactSpeed = body.vy
        val material = body.shape.material
        val normalImpulse = material.mass * impactSpeed * (1f + material.restitution)
        val leverX = contactX - body.x
        val leverY = contactY - body.y
        val tangentSpeed = body.vx - body.angularVelocity * leverY
        // Shape inertia is normalized; impulses and lever arms here are in pixels.
        val inertia = material.momentOfInertia * body.sizePx * body.sizePx
        val tangentImpulse = (-tangentSpeed / (1f / material.mass + leverY * leverY / inertia))
            .coerceIn(-material.friction * normalImpulse, material.friction * normalImpulse)

        body.vx += tangentImpulse / material.mass
        body.angularVelocity += (-leverY * tangentImpulse - leverX * normalImpulse) / inertia
        if(impactSpeed <= LOW_IMPACT_SPEED_DP * density) {
            body.vy = 0f
            body.onSurface = true
        } else {
            body.vy = -impactSpeed * material.restitution
            body.onSurface = false
        }

        if(!body.impactEmitted) {
            body.impactEmitted = true
            collisionEvents += CollisionEvent(
                objectId = body.id,
                contactPoint = WorldPoint(contactX, contactY),
                normal = WorldPoint(0f, -1f),
                normalImpulse = normalImpulse,
                firstImpact = true
            )
        }
    }

    private fun collideWithSideWall(body: Body, currentObstacle: WorldObstacle, before: WorldBounds, after: WorldBounds) {
        val wallTop = currentObstacle.top + currentObstacle.cornerRadius
        val wallBottom = currentObstacle.bottom - currentObstacle.cornerRadius
        if(after.maxY <= wallTop || after.minY >= wallBottom) return

        if(before.maxX <= currentObstacle.left && after.maxX > currentObstacle.left && body.vx > 0f) {
            body.x += currentObstacle.left - after.maxX
            body.vx = -body.vx * body.shape.material.restitution
        } else if(before.minX >= currentObstacle.right && after.minX < currentObstacle.right && body.vx < 0f) {
            body.x += currentObstacle.right - after.minX
            body.vx = -body.vx * body.shape.material.restitution
        }
    }

    private fun isSupported(currentObstacle: WorldObstacle, bounds: WorldBounds, sizePx: Float): Boolean {
        if(bounds.bottomContactX < currentObstacle.left || bounds.bottomContactX > currentObstacle.right) return false
        val distance = abs(bounds.maxY - currentObstacle.surfaceY(bounds.bottomContactX))
        return distance <= max(SURFACE_TOLERANCE_PX * density, sizePx * 0.18f)
    }

    private data class WorldBounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val bottomContactX: Float
    )

    private fun bounds(body: Body): WorldBounds {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        val bottomXs = ArrayList<Float>(body.shape.pieces.sumOf { it.pointCount })
        for(piece in body.shape.pieces) {
            for(index in 0 until piece.pointCount) {
                val point = transform(body, piece.x(index), piece.y(index))
                minX = min(minX, point.x); maxX = max(maxX, point.x)
                minY = min(minY, point.y); maxY = max(maxY, point.y)
            }
        }
        val tolerance = body.sizePx * 0.08f
        for(piece in body.shape.pieces) {
            for(index in 0 until piece.pointCount) {
                val point = transform(body, piece.x(index), piece.y(index))
                if(point.y >= maxY - tolerance) bottomXs += point.x
            }
        }
        val contactX = if(bottomXs.isEmpty()) body.x else bottomXs.average().toFloat()
        return WorldBounds(minX, maxX, minY, maxY, contactX)
    }

    private fun transform(body: Body, localX: Float, localY: Float): WorldPoint {
        val x = localX * body.sizePx
        val y = localY * body.sizePx
        val c = cos(body.angle)
        val s = sin(body.angle)
        return WorldPoint(body.x + x * c - y * s, body.y + x * s + y * c)
    }
}
