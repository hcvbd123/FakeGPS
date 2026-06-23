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
 * 显示当前模拟位置 + 全方向摇杆（仅单点模拟时可用）
 *
 * 摇杆逻辑：
 * - 单点模拟时：显示摇杆，拖拽控制移动方向（15米/秒）
 * - 巡航模式时：隐藏摇杆
 * - 未模拟时：隐藏摇杆
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

    // 控件引用
    private var labelView: TextView? = null
    private var coordsView: TextView? = null
    private var joystickView: JoystickView? = null
    private var joystickContainer: View? = null

    // 刷新协程
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

        // 启动定时刷新（每秒更新坐标显示+摇杆可见性）
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

        // 设置摇杆回调 → 转发方向到 MockLocationService
        joystickView?.onJoystickMove = { angle, active ->
            MockLocationService.setJoystickDirection(angle, active)
        }

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

        // 拖拽移动整个悬浮窗
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

                            // 短点击（<300ms 且移动 < 20px）→ 折叠/展开摇杆
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
        val isSimulating = MockLocationService.isRunning && !MockLocationService.isRouteMode

        if (!isSimulating) {
            // 未模拟时不应展开摇杆
            return
        }

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
        val container = joystickContainer ?: return
        val lbl = labelView ?: return
        val crd = coordsView ?: return
        val js = joystickView ?: return

        val isSimulating = MockLocationService.isRunning
        val isRoute = MockLocationService.isRouteMode
        val isJoystickMode = isSimulating && !isRoute

        // 更新标题
        if (isRoute) {
            lbl.text = "🚗 巡航 ${MockLocationService.routeIndex + 1}/${MockLocationService.routeTotal}"
        } else if (isSimulating) {
            lbl.text = "📍 模拟中"
        } else {
            lbl.text = "📍 GPS"
        }

        // 更新坐标
        val (lat, lon) = MockLocationService.getCurrentLocation()
        if (isSimulating) {
            crd.text = "%.5f, %.5f".format(lat, lon)
            crd.visibility = View.VISIBLE

            // 摇杆：仅单点模拟时可见
            if (isJoystickMode) {
                if (!isExpanded) {
                    // 自动展开摇杆
                    isExpanded = true
                    container.visibility = View.VISIBLE
                }
            } else {
                // 巡航模式：隐藏并重置摇杆
                isExpanded = false
                container.visibility = View.GONE
                js.reset()
            }
        } else {
            crd.visibility = View.GONE
            isExpanded = false
            container.visibility = View.GONE
            js.reset()
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
