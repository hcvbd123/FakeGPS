package com.fakegps.app.utils

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.TextView
import com.fakegps.app.R
import com.fakegps.app.service.MockLocationService

/**
 * 悬浮窗服务
 * 在屏幕上方显示当前模拟状态，点击快速切换坐标
 */
class FloatingWindowService : Service() {

    companion object {
        const val CHANNEL_ID = "float_gps_channel"
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showOverlay()
        isRunning = true

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("虚拟GPS")
            .setContentText("悬浮窗已开启")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        startForeground(1002, notification)
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.floating_overlay, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                params.apply {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = x
                            initialY = y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            x = initialX + (event.rawX - initialTouchX).toInt()
                            y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(v, this)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            // 点击事件：切换模拟状态
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (dx * dx + dy * dy < 100) {
                                v.performClick()
                            }
                            return true
                        }
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        isRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
