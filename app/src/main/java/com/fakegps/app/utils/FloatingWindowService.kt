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
import com.fakegps.app.ui.JoystickView
import kotlinx.coroutines.*

/**
 * 悬浮窗服务
 * 显示当前模拟位置 + 摇杆（拖拽移动模拟点）
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

    private var labelView: TextView? = null
    private var coordsView: TextView? = null
    private var joystickView: JoystickView? = null
    private var joystickContainer: View? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null

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
        startUpdating()

        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.floating_overlay, null)

        labelView = overlayView?.findViewById(R.id.float_label)
        coordsView = overlayView?.findViewById(R.id.float_coords)
        joystickView = overlayView?.findViewById(R.id.joystick_view)
        joystickContainer = overlayView?.findViewById(R.id.joystick_container)

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
            x = 50
            y = 200
        }

        overlayView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var touchStartTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                params.apply {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = x
                            initialY = y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            touchStartTime = System.currentTimeMillis()
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            x = initialX + (event.rawX - initialTouchX).toInt()
                            y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(v, this)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            val dragDist = dx * dx + dy * dy
                            val elapsed = System.currentTimeMillis() - touchStartTime

                            if (dragDist < 400 && elapsed < 300) {
                                toggleJoystick()
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

    private fun toggleJoystick() {
        val container = joystickContainer ?: return
        if (!MockLocationService.isMocking()) return

        isExpanded = !isExpanded
        container.visibility = if (isExpanded) View.VISIBLE else View.GONE
        if (!isExpanded) {
            joystickView?.reset()
        }
    }

    private fun startUpdating() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (isActive) {
                updateDisplay()
                delay(1000)
            }
        }
    }

    private fun updateDisplay() {
        val lbl = labelView ?: return
        val crd = coordsView ?: return

        if (MockLocationService.isMocking()) {
            lbl.text = "📍 模拟中"

            val (lat, lon) = MockLocationService.getCurrentLocation()
            crd.text = "%.5f, %.5f".format(lat, lon)
            crd.visibility = View.VISIBLE

            // 自动展开摇杆
            val container = joystickContainer ?: return
            if (!isExpanded) {
                isExpanded = true
                container.visibility = View.VISIBLE
            }
        } else {
            lbl.text = "📍 GPS"
            crd.visibility = View.GONE
            isExpanded = false
            joystickContainer?.visibility = View.GONE
            joystickView?.reset()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        updateJob?.cancel()
        joystickView?.reset()
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
