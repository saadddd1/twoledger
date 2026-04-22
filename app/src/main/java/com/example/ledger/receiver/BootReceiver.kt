package com.example.ledger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.ledger.service.NotificationMonitorService
import com.example.ledger.ui.isNotificationListenerEnabled
import com.example.ledger.ui.isAccessibilityServiceEnabled

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        
        if (intent?.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.intent.action.QUICKBOOT_POWERON"
            )) {
            
            Log.d("BootReceiver", "系统启动，检查自动记账服务状态")
            
            // 检查权限并尝试启动服务
            if (isNotificationListenerEnabled(context)) {
                try {
                    context.startService(Intent(context, NotificationMonitorService::class.java))
                } catch (e: Exception) {
                    Log.e("BootReceiver", "启动通知服务失败", e)
                }
            }
        }
    }
}
