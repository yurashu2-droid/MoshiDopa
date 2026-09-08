package com.example.timecostview.domain

import java.util.Locale

object Cost {
    // 仮データ。購入アイテムのデータセットを作るときはここをカタログへ置き換える。
    val demoPurchase = PurchaseItem("コーヒー", 200.0)

    fun hourly(amount: Double, monthly: Boolean, hours: Double): Double {
        require(amount.isFinite() && amount > 0 && amount <= 1_000_000_000)
        require(!monthly || (hours.isFinite() && hours > 0 && hours <= 744))
        return if (monthly) amount / hours else amount
    }
    fun yen(elapsedMs: Long, hourly: Double): Double = elapsedMs.coerceAtLeast(0) / 3_600_000.0 * hourly
    fun money(value: Double): String = String.format(Locale.JAPAN, "¥%,.2f", value)
    fun purchaseMessage(amount: Double, item: PurchaseItem = demoPurchase): String =
        if(amount >= item.price) "${item.name}が買えました" else "${item.name}まであと${money(item.price - amount)}"
    fun time(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.JAPAN, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }
}

data class PurchaseItem(val name: String, val price: Double)

data class Record(val id: Long, val app: String, val title: String, val mode: String,
    val start: Long, val end: Long, val duration: Long, val rate: Double,
    val project: String = "", val category: String = "", val note: String = "") {
    val amount get() = Cost.yen(duration, rate)
}
