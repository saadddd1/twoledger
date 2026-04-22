package com.example.ledger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.ledger.R
import com.example.ledger.data.AppDatabase
import com.example.ledger.data.AutoBill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationMonitorService : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: AppDatabase

    companion object {
        private const val CHANNEL_ID = "ledger_monitor_service"
        private const val FOREGROUND_ID = 1001

        // 2026年最新支付关键词 适配微信8.5.x 支付宝10.6.x
        private val SUCCESS_KEYWORDS = listOf(
            "微信支付付款", "已支付", "支付成功", "完成付款", "付款金额",
            "付款成功", "交易成功", "消费成功", "转账成功", "扫码付款",
            "支付完成", "付款完成", "支付款项", "支出通知", "交易人民币",
            "微信支付收款", "支付凭证", "转账给", "已收钱", "收款到账通知",
            "付款成功。", "支付成功！", "完成付款", "已完成支付"
        )

        // 2026年最新金额匹配规则
        private val PATTERNS = listOf(
            Regex("""(?:¥|￥|人民币|-|支付成功|付款成功)(\d+(?:\.\d{1,2})?)"""),
            Regex("""(?:支出|消费|支付|交易|付款)(\d+(?:\.\d{1,2})?)[元]?"""),
            Regex("""(?:金额|付款|收款方)(?:[：:]?)(\d+(?:\.\d{1,2})?)"""),
            Regex("""(\d+\.\d{2})元""")
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        db = AppDatabase.getDatabase(applicationContext)
        startForegroundService()
        Log.d("NotificationMonitor", "通知监听服务已连接")
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "自动记账服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "自动记账后台运行服务"
                enableVibration(false)
                setSound(null, null)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("账本自动记账")
            .setContentText("正在后台监听支付通知")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        startForeground(FOREGROUND_ID, notification)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val subText = extras.getString(Notification.EXTRA_SUB_TEXT) ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val combinedContent = listOf(title, text, subText, bigText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        // 扩展支持的应用列表
        val isPaymentApp = when (packageName) {
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "com.jingdong.app.mall",
            "com.sankuai.meituan",
            "com.taobao.taobao",
            "com.xunmeng.pinduoduo",
            "com.ss.android.ugc.aweme",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.android.messaging" -> true
            else -> packageName.contains("bank", ignoreCase = true)
        }

        if (!isPaymentApp) return

        if (SUCCESS_KEYWORDS.none { combinedContent.contains(it, ignoreCase = true) }) return

        scope.launch {
            try {
                // 顺序匹配所有规则
                var amount: Double? = null
                for (pattern in PATTERNS) {
                    val match = pattern.find(combinedContent)
                    if (match != null) {
                        amount = match.groupValues[1].toDoubleOrNull()
                        if (amount != null && amount > 0) break
                    }
                }

                val finalAmount = amount ?: return@launch
                val now = sbn.postTime

                // 全局去重
                if (!BillDeduplicator.shouldRecordBill(finalAmount, packageName, now)) {
                    return@launch
                }

                val appSource = when (packageName) {
                    "com.tencent.mm" -> "Wechat (微信)"
                    "com.eg.android.AlipayGphone" -> "Alipay (支付宝)"
                    "com.unionpay" -> "UnionPay (云闪付)"
                    "com.jingdong.app.mall" -> "京东"
                    "com.sankuai.meituan" -> "美团"
                    "com.taobao.taobao" -> "淘宝"
                    "com.xunmeng.pinduoduo" -> "拼多多"
                    "com.ss.android.ugc.aweme" -> "抖音"
                    "com.android.mms", "com.google.android.apps.messaging", "com.android.messaging" -> "SMS (短信)"
                    else -> "Bank App (银行)"
                }

                // 提取商户名
                val merchantName = extractMerchantName(combinedContent, packageName, title)

                db.autoBillDao().insertAutoBill(
                    AutoBill(
                        appSource = appSource,
                        merchantName = merchantName,
                        amount = finalAmount,
                        paymentMethod = "通知读取",
                        fullPayeeName = merchantName,
                        timestampMillis = now
                    )
                )

                Log.d("NotificationMonitor", "捕获账单: $appSource - $finalAmount - $merchantName")

            } catch (e: Exception) {
                Log.e("NotificationMonitor", "解析通知失败", e)
            }
        }
    }

    private fun extractMerchantName(content: String, packageName: String, title: String): String {
        return when (packageName) {
            "com.tencent.mm" -> {
                Regex("""收款方(?:：|:)?(.*?)(?:\s|$)""").find(content)?.groupValues?.get(1)?.trim()
                    ?: Regex("""付款给(.*?)(?:\s|$)""").find(content)?.groupValues?.get(1)?.trim()
                    ?: if (title.isNotBlank() && title !in listOf("微信支付", "支付结果通知", "服务通知")) title.trim() else "微信支付"
            }
            "com.eg.android.AlipayGphone" -> {
                Regex("""收款方(?:：|:)?(.*?)(?:\s|$)""").find(content)?.groupValues?.get(1)?.trim()
                    ?: Regex("""在(.*?)支付成功""").find(content)?.groupValues?.get(1)?.trim()
                    ?: "支付宝支付"
            }
            else -> title.takeIf { it.isNotBlank() } ?: "未命名账单"
        }.replace("交易成功", "").replace("支付成功", "").trim()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}

