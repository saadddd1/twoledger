package com.example.ledger.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object BillDeduplicator {
    private data class BillFingerprint(
        val amount: Double,
        val appSource: String,
        val timestamp: Long
    )

    private val recentBills = ConcurrentHashMap<BillFingerprint, Long>()
    private val DEDUPLICATION_WINDOW_MS = TimeUnit.SECONDS.toMillis(30)

    @Synchronized
    fun shouldRecordBill(amount: Double, appSource: String, timestamp: Long): Boolean {
        cleanupExpired()

        // 跨引擎去重：只要是同一个应用+相同金额，30秒内只记录一次
        // 不管是通知引擎还是无障碍引擎捕获的
        val existing = recentBills.keys.find {
            it.amount == amount && it.appSource == appSource &&
                    Math.abs(timestamp - recentBills[it]!!) < DEDUPLICATION_WINDOW_MS
        }

        return if (existing != null) {
            // 重复账单，跳过（双引擎互斥）
            false
        } else {
            recentBills[BillFingerprint(amount, appSource, timestamp)] = timestamp
            true
        }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        recentBills.entries.removeIf { (_, time) ->
            now - time > DEDUPLICATION_WINDOW_MS
        }
    }

    fun clear() {
        recentBills.clear()
    }
}
