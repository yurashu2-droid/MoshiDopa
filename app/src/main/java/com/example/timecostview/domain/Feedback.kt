package com.example.timecostview.domain

/** Item used only by the live amount-threshold drop animation. */
enum class FallingItem {
    CANDY,
    CHOCOLATE,
    COFFEE
}

/** The small, illustrative catalog is separate from the stored receipt amount. */
data class FallingCatalogItem(
    val item: FallingItem,
    val priceYen: Int,
    val icon: String,
    val name: String,
    val unit: String = "個"
)

object FallingCatalog {
    val items = listOf(
        FallingCatalogItem(FallingItem.CANDY, 10, "🍬", "駄菓子"),
        FallingCatalogItem(FallingItem.CHOCOLATE, 100, "🍫", "チョコレート"),
        FallingCatalogItem(FallingItem.COFFEE, 200, "☕", "コーヒー", "杯")
    )

    fun forThreshold(thresholdYen: Int): FallingCatalogItem =
        items.last { it.priceYen <= thresholdYen }
}

data class Milestone(val yen: Int, val icon: String, val name: String, val unit: String = "個")
object Milestones {
    /** Receipt/comparison copy remains independent from live drop scheduling. */
    val items = FallingCatalog.items.map { Milestone(it.priceYen, it.icon, it.name, it.unit) }
    fun reached(amount: Double) = items.lastOrNull { amount >= it.yen }
    fun crossing(previous: Double, current: Double) = items.lastOrNull { previous < it.yen && current >= it.yen }
    fun comparison(amount: Double): String = reached(amount)?.let {
        "${it.icon} ${it.name}約${(amount / it.yen).toInt()}${it.unit}分の時間（${it.yen}円換算）"
    } ?: "設定した時間単価による換算額です。"
}

/** Completion is shown as soon as the tracking service observes that a segment ended. */
class CompletionGate {
    var record: Record? = null; private set
    private var shownAt: Long? = null
    fun leave(value: Record) { record = value; shownAt = null }
    fun ready(): Record? = record
    fun preview(now: Long): Record? {
        val r = record ?: return null
        val first = shownAt ?: now.also { shownAt = it }
        return r.takeIf { now - first < 8000 }
    }
    fun dismiss() { record = null; shownAt = null }
}
