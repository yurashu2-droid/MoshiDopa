package com.example.timecostview.overlay.physics

import com.example.timecostview.domain.FallingItem

/** A small convex polygon expressed in the drawable's normalized local space. */
data class ConvexPolygon(val coordinates: FloatArray) {
    init {
        require(coordinates.size >= 6 && coordinates.size % 2 == 0) {
            "A convex polygon needs at least three x/y points"
        }
    }

    val pointCount: Int get() = coordinates.size / 2
    fun x(index: Int): Float = coordinates[index * 2]
    fun y(index: Int): Float = coordinates[index * 2 + 1]
}

/** Material parameters use a consistent arbitrary mass unit and px/s simulation units. */
data class ItemMaterial(
    val sizeDp: Float,
    val mass: Float,
    val momentOfInertia: Float,
    val restitution: Float,
    val friction: Float
)

data class ItemShape(val pieces: List<ConvexPolygon>, val material: ItemMaterial)

/**
 * Deliberately simplified colliders for the fixed transparent vector assets.
 * Transparent padding, steam, shadow, and the candy wrapper's fine zig-zag are excluded.
 */
object ItemShapes {
    fun forItem(item: FallingItem): ItemShape = when(item) {
        FallingItem.CANDY -> ItemShape(
            pieces = listOf(
                polygon(-0.30f, -0.27f, 0.30f, -0.27f, 0.40f, 0f, 0.30f, 0.27f, -0.30f, 0.27f, -0.40f, 0f),
                polygon(-0.50f, -0.20f, -0.30f, -0.27f, -0.30f, 0.27f, -0.50f, 0.20f),
                polygon(0.30f, -0.27f, 0.50f, -0.20f, 0.50f, 0.20f, 0.30f, 0.27f)
            ),
            material = ItemMaterial(sizeDp = 34f, mass = 0.70f, momentOfInertia = 0.35f, restitution = 0.42f, friction = 0.40f)
        )
        FallingItem.CHOCOLATE -> ItemShape(
            pieces = listOf(
                polygon(-0.39f, -0.34f, 0.39f, -0.34f, 0.39f, 0.34f, -0.39f, 0.34f)
            ),
            material = ItemMaterial(sizeDp = 38f, mass = 0.90f, momentOfInertia = 0.45f, restitution = 0.32f, friction = 0.55f)
        )
        FallingItem.COFFEE -> ItemShape(
            pieces = listOf(
                polygon(-0.36f, -0.32f, 0.28f, -0.32f, 0.35f, 0.18f, 0.16f, 0.34f, -0.22f, 0.34f, -0.34f, 0.10f),
                polygon(0.27f, -0.20f, 0.47f, -0.18f, 0.50f, 0.08f, 0.37f, 0.22f, 0.28f, 0.18f)
            ),
            material = ItemMaterial(sizeDp = 44f, mass = 1.80f, momentOfInertia = 1.40f, restitution = 0.28f, friction = 0.65f)
        )
    }

    private fun polygon(vararg coordinates: Float): ConvexPolygon = ConvexPolygon(coordinates)
}
