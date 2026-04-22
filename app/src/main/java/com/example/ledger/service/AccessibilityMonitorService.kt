package com.example.ledger.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.example.ledger.R
import com.example.ledger.data.AppDatabase
import com.example.ledger.data.AutoBill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class AccessibilityMonitorService : AccessibilityService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase

    companion object {
        private const val CHANNEL_ID = "ledger_accessibility_service"
        private const val FOREGROUND_ID = 1002

        private val TARGET_PACKAGES = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "com.jingdong.app.mall",
            "com.sankuai.meituan",
            "com.taobao.taobao"
        )

        // 2026年微信8.5.x/支付宝10.6.x 最新匹配规则
        private val PAY_SUCCESS_KEYWORDS = listOf(
            "支付成功", "付款成功", "完成支付", "交易成功",
            "已付款", "支付完成", "付款完成", "转账成功",
            "付款成功。", "支付成功！", "完成付款", "支付成功 ",
            "微信支付", "已支付", "支付凭证", "转账给你",
            "收钱到账", "收款到账", "付款给", "向你付款"
        )

        // 2026最新金额规则 - 适配微信支付宝最新UI格式
        private val AMOUNT_PATTERN = Pattern.compile("""[¥￥]\s*(\d+(?:\.\d{1,2})?)""")
        private val MERCHANT_PATTERNS = listOf(
            Regex("""收款方[:：]?\s*(.+?)(?:\s|$)"""),
            Regex("""付款给[:：]?\s*(.+?)(?:\s|$)"""),
            Regex("""收款方：\s*([^\n]+)"""),
            Regex("""在(.+?)消费"""),
            Regex("""商户全称[:：]?\s*(.+?)(?:\s|$)""")
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = AppDatabase.getDatabase(applicationContext)
        startForegroundService()
        Log.d("AccessibilityMonitor", "无障碍服务已连接")
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "无障碍记账服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "无障碍自动记账后台服务"
                enableVibration(false)
                setSound(null, null)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("账本无障碍服务")
            .setContentText("正在后台监控支付页面")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_ID, notification, FOREGROUND_SERVICE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    override fun onInterrupt() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
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
        for (pattern in MERCHANT_PATTERNS) {
            val match = pattern.find(content)
            if (match != null) {
                val name = match.groupValues[1].trim()
                if (name.isNotBlank() && name.length < 50) {
                    return name
                }
            }
        }
        return when (packageName) {
            "com.tencent.mm" -> "微信支付"
            "com.eg.android.AlipayGphone" -> "支付宝支付"
            else -> "未命名账单"
        }
    }

    override fun onInterrupt() {}
}
