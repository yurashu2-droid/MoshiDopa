package com.example.timecostview

import com.example.timecostview.domain.FallingItem
import com.example.timecostview.overlay.physics.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FallingWorldTest {
    @Test fun launchedItemsBounceTravelAndExitBeforeExpiry() {
        for (density in listOf(1f, 3f)) for (item in FallingItem.values()) for (direction in listOf(-1f, 1f)) {
            val world = FallingWorld(density)
            world.setObstacle(WorldObstacle(100f*density, 300f*density, 312f*density, 420f*density, 8f*density))
            val size = ItemShapes.forItem(item).material.sizeDp*density
            val startX = (206f-direction*24f)*density
            val vx = direction*110f*density
            world.add(item, size, true, startX, 160f*density, vx, 0f, vx/(size*0.34f)*0.7f, 0, 1_200_000_000)
            var events = 0
            var bounced = false
            var exited = false
            repeat(540) { frame ->
                events += world.advance(1f/120f, (frame+1)*8_333_334L).size
                val body = world.bodies.single()
                assertTrue("Unbounded rotation: $item", abs(body.angularVelocity) < 50f)
                if(body.impactEmitted && body.vy < 0) bounced = true
                if(body.impactEmitted && body.y > 470f*density) exited = true
            }
            assertEquals("Exactly one impact: $item", 1, events)
            assertTrue("Bounce: $item", bounced)
            assertTrue("Must fall off before lifetime cleanup: $item density=$density dir=$direction", exited)
        }
    }
}
