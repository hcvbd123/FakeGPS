package com.fakegps.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启恢复模拟（如果有持久化状态，可以在这里恢复）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 可扩展：从 SharedPreferences 读取上次的坐标，恢复模拟
    }
}
