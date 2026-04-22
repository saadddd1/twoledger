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

        val fingerprint = BillFingerprint(amount, appSource, 0)

        // 检查 30 秒内是否有相同金额 + 相同来源的账单
        val existing = recentBills.keys.find {
            it.amount == amount && it.appSource == appSource &&
                    Math.abs(timestamp - recentBills[it]!!) < DEDUPLICATION_WINDOW_MS
        }

        return if (existing != null) {
            // 重复账单，跳过
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
