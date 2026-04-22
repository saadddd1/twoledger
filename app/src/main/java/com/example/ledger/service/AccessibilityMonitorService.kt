package com.example.ledger.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.example.ledger.data.AppDatabase
import com.example.ledger.data.AutoBill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class AccessibilityMonitorService : AccessibilityService() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var db: AppDatabase

    companion object {
        private val TARGET_PACKAGES = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "com.jingdong.app.mall",
            "com.sankuai.meituan",
            "com.taobao.taobao"
        )

        // 2026 年最新支付成功页面关键词
        private val PAY_SUCCESS_KEYWORDS = listOf(
            "支付成功", "付款成功", "完成支付", "交易成功",
            "已付款", "支付完成", "付款完成", "转账成功",
            "付款成功。", "支付成功！", "完成付款"
        )

        private val AMOUNT_PATTERN = Pattern.compile("""[¥￥]?\s*(\d+(?:\.\d{1,2})?)""")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = AppDatabase.getDatabase(applicationContext)
        Log.d("AccessibilityMonitor", "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in TARGET_PACKAGES) return

        try {
            val root = rootInActiveWindow ?: return
            processPage(root, packageName)
        } catch (e: Exception) {
            Log.e("AccessibilityMonitor", "处理页面失败", e)
        }
    }

    private fun processPage(root: AccessibilityNodeInfo, packageName: String) {
        val allText = mutableListOf<String>()
        traverseNode(root, allText)

        val pageContent = allText.joinToString(" ")
        if (PAY_SUCCESS_KEYWORDS.none { pageContent.contains(it) }) {
            return
        }

        // 提取金额
        var amount: Double? = null
        val matcher = AMOUNT_PATTERN.matcher(pageContent)
        while (matcher.find()) {
            val value = matcher.group(1)?.toDoubleOrNull()
            if (value != null && value > 0) {
                amount = value
                break
            }
        }

        if (amount == null) return

        // 提取商户名
        val merchantName = extractMerchantName(pageContent, packageName)

        // 去重
        if (!BillDeduplicator.shouldRecordBill(amount, packageName, System.currentTimeMillis())) {
            return
        }

        scope.launch {
            val appSource = when (packageName) {
                "com.tencent.mm" -> "Wechat (微信)"
                "com.eg.android.AlipayGphone" -> "Alipay (支付宝)"
                "com.unionpay" -> "UnionPay (云闪付)"
                "com.jingdong.app.mall" -> "京东"
                "com.sankuai.meituan" -> "美团"
                "com.taobao.taobao" -> "淘宝"
                else -> "Other"
            }

            db.autoBillDao().insertAutoBill(
                AutoBill(
                    appSource = appSource,
                    merchantName = merchantName,
                    amount = amount,
                    paymentMethod = "无障碍服务",
                    fullPayeeName = merchantName,
                    timestampMillis = System.currentTimeMillis()
                )
            )

            Log.d("AccessibilityMonitor", "捕获账单: $appSource - $amount - $merchantName")
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.let {
            if (it.isNotBlank()) {
                result.add(it.toString())
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            child?.let { traverseNode(it, result) }
        }
    }

    private fun extractMerchantName(content: String, packageName: String): String {
        return when (packageName) {
            "com.tencent.mm" -> {
                val match = Regex("""收款方[:：]?\s*(.*?)(?:\s|$)""").find(content)
                match?.groupValues?.get(1)?.trim() ?: "微信支付"
            }
            "com.eg.android.AlipayGphone" -> {
                val match = Regex("""收款方[:：]?\s*(.*?)(?:\s|$)""").find(content)
                    ?: Regex("""在(.*?)支付成功""").find(content)
                match?.groupValues?.get(1)?.trim() ?: "支付宝支付"
            }
            else -> "未命名账单"
        }
    }

    override fun onInterrupt() {}
}
