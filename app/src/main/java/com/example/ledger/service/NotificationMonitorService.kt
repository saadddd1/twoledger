package com.example.ledger.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.ledger.data.AppDatabase
import com.example.ledger.data.AutoBill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationMonitorService : NotificationListenerService() {
    
    private val scope = CoroutineScope(Dispatchers.IO)
    // 用于防抖
    private var lastAmount = 0.0
    private var lastTime = 0L

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
        val titleBig = extras.getString(Notification.EXTRA_TITLE_BIG) ?: ""
        
        // Combine all possible text fields to avoid missing data, removing whitespaces for easier regex matching
        val combinedContent = listOf(title, text, subText, bigText, titleBig)
            .filter { it.isNotBlank() }
            .joinToString(" || ")
        
        val normalizedString = combinedContent.replace(" ", "").replace("\n", "")

        // 我们关注主流支付软件及短消息
        val isPaymentApp = when (packageName) {
            "com.tencent.mm" -> true // 微信
            "com.eg.android.AlipayGphone" -> true // 支付宝
            "com.unionpay" -> true // 云闪付
            "com.android.mms", "com.google.android.apps.messaging", "com.android.messaging" -> true // 短信
            else -> packageName.contains("bank", ignoreCase = true) || packageName.contains("icbc", ignoreCase = true) || packageName.contains("cmb", ignoreCase = true) || packageName.contains("boc", ignoreCase = true) || packageName.contains("abchina", ignoreCase = true) || packageName.contains("ccb", ignoreCase = true)
        }

        if (!isPaymentApp) return

        // 关键字过滤，确保这是一笔支出通知
        val successKeywords = listOf("支付款项", "支出", "交易成功", "支付成功", "付款成功", "消费", "转账", "成功付款", "完成付款", "付款金额", "交易人民币", "扫码付款")
        if (!successKeywords.any { normalizedString.contains(it) } && !normalizedString.contains("凭证")) return

        scope.launch {
            try {
                // 更强大的提取金额逻辑 (兼容10 / 10.0 / 10.00，兼容负号，兼容人民币/¥/￥)
                var amount: Double? = null
                
                // 1. 寻找 ¥20.00 / ￥20.00 / -20.00
                val symbolRegex = Regex("""(?:¥|￥|人民币|-)(\d+(?:\.\d{1,2})?)""")
                val symbolMatch = symbolRegex.find(normalizedString)
                if (symbolMatch != null) {
                    amount = symbolMatch.groupValues[1].toDoubleOrNull()
                }

                // 2. 寻找 支出20元 / 消费20.00元 / 交易20元 (针对短信或银行)
                if (amount == null) {
                    val yuanRegex = Regex("""(?:支出|消费|支付|交易|交易人民币|付款)(\d+(?:\.\d{1,2})?)[元]?""")
                    val yuanMatch = yuanRegex.find(normalizedString)
                    if (yuanMatch != null) {
                        amount = yuanMatch.groupValues[1].toDoubleOrNull()
                    }
                }
                
                // 3. Fallback: 任何包含 "金额"、"付款" 的位置后面紧跟的数字
                if (amount == null) {
                    val amountTextRegex = Regex("""(?:金额|付款|转账给.*?)(?:[：:]?)(\d+(?:\.\d{1,2})?)""")
                    val amountTextMatch = amountTextRegex.find(normalizedString)
                    if (amountTextMatch != null) {
                        amount = amountTextMatch.groupValues[1].toDoubleOrNull()
                    }
                }

                // 4. 终极Fallback：直接找 XX.XX元
                if (amount == null) {
                    val rawYuanRegex = Regex("""(\d+\.\d{2})元""")
                    val rawYuanMatch = rawYuanRegex.find(normalizedString)
                    if (rawYuanMatch != null) {
                        amount = rawYuanMatch.groupValues[1].toDoubleOrNull()
                    }
                }

                val finalAmount = amount ?: return@launch

                // 防抖逻辑：防止同一笔账单因为状态更新导致多条通知
                val now = sbn.postTime
                if (finalAmount == lastAmount && (now - lastTime < 5000)) {
                    return@launch
                }
                lastAmount = finalAmount
                lastTime = now

                val appSource = when (packageName) {
                    "com.tencent.mm" -> "Wechat (微信)"
                    "com.eg.android.AlipayGphone" -> "Alipay (支付宝)"
                    "com.unionpay" -> "UnionPay (云闪付)"
                    "com.android.mms", "com.google.android.apps.messaging", "com.android.messaging" -> "SMS (短信)"
                    else -> "Bank App (银行)"
                }

                // 启发式提取商户名
                var merchantName = "未命名账单"
                
                if (packageName == "com.tencent.mm") {
                   merchantName = "微信支付" // 默认值
                   // 匹配 "支付款项: [商户名] 消费..." 或者 "收款方: [商户名]"
                   val wxMatch1 = Regex("""收款方(?:：|:)?(.*?)(?:\|\||¥|￥|\d)""").find(combinedContent)
                   val wxMatch2 = Regex("""付款给(.*?)(?:\|\||¥|￥|\d)""").find(combinedContent)
                   val wxMatch3 = Regex("""支付给(.*?)(?:\|\||¥|￥|\d)""").find(combinedContent)
                   
                   merchantName = wxMatch1?.groupValues?.get(1)?.trim() 
                         ?: wxMatch2?.groupValues?.get(1)?.trim() 
                         ?: wxMatch3?.groupValues?.get(1)?.trim() 
                         ?: "微信支付"
                         
                } else if (packageName == "com.eg.android.AlipayGphone") {
                   val aliMatch = Regex("""在(.*?)成功支付""").find(combinedContent)
                   if (aliMatch != null) {
                       merchantName = aliMatch.groupValues[1].trim()
                   } else {
                       val aliTransferMatch = Regex("""向(.*?)转账""").find(combinedContent)
                       if (aliTransferMatch != null) merchantName = "转账: " + aliTransferMatch.groupValues[1].trim()
                   }
                }
                
                merchantName = merchantName.replace("交易成功", "").replace("支付成功", "").trim()

                // 兜底逻辑：如果标题有内容且不是纯通用词，就用标题
                if (merchantName == "未命名账单" || merchantName == "微信支付") {
                   if (title.isNotBlank() && title != "微信支付" && title != "支付结果通知" && title != "服务通知" && title != "交易提醒") {
                       merchantName = title.trim()
                   } else if (text.isNotBlank()) {
                       // 进行长文本剥离
                       merchantName = text.replace(Regex("[0-9]|\\.|元|￥|¥|支付成功|成功支付|支出|消费|转账给|付款给|付款|您尾号|账户|人民币|交易|完成通知"), "").chunked(12).firstOrNull() ?: "未命名账单"
                   }
                }

                val db = AppDatabase.getDatabase(applicationContext)
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

                Log.d("NotificationMonitor", "Saved Bill via Notification: $appSource - $finalAmount")

            } catch (e: Exception) {
                Log.e("NotificationMonitor", "Error parsing notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
