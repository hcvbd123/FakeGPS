package com.fakegps.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*

/**
 * 模拟定位前台服务
 * 支持单点定位 + 多坐标自动巡航 + 摇杆方向控制
 *
 * 摇杆控制：仅在单点模拟模式可用（巡航模式下禁用）
 * 速度：15米/秒
 *
 * 通过 LocationManager.setTestProviderLocation 注入位置
 */
class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "fake_gps_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.fakegps.START_MOCK"
        const val ACTION_STOP = "com.fakegps.STOP_MOCK"
        const val ACTION_START_ROUTE = "com.fakegps.START_ROUTE"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_ALT = "alt"
        const val EXTRA_LATS = "lats"
        const val EXTRA_LONS = "lons"
        const val EXTRA_NAMES = "names"
        const val EXTRA_INTERVAL_MIN = "interval_min"
        const val EXTRA_INTERVAL_MAX = "interval_max"

        // 15 米/秒
        private const val SPEED_MPS = 15.0
        private const val METER_PER_DEGREE_LAT = 111111.0

        private var currentLat = 0.0
        private var currentLon = 0.0
        private var currentAlt = 0.0
        private var currentBearing = 0f

        var routeTotal = 0
            private set
        var routeIndex = 0
            private set
        var routeName = ""
            private set
        var isRunning = false
            private set
        var isRouteMode = false
            private set

        private var joystickActive = false
        private var joystickAngle = 0.0

        fun isMocking(): Boolean = isRunning
        fun getCurrentLocation(): Pair<Double, Double> = Pair(currentLat, currentLon)
        fun isJoystickActive(): Boolean = joystickActive && isRunning && !isRouteMode

        fun setJoystickDirection(angle: Double, active: Boolean) {
            joystickActive = active
            joystickAngle = angle
        }

        fun resetJoystick() {
            joystickActive = false
            joystickAngle = 0.0
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mockJob: Job? = null
    private lateinit var locationManager: LocationManager

    // 巡航数据
    private var routeLats = doubleArrayOf()
    private var routeLons = doubleArrayOf()
    private var routeNames = arrayOf<String>()
    private var intervalMin = 5
    private var intervalMax = 25

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        setupTestProvider()
    }

    /**
     * 设置测试位置提供者
     * 如果 GPS 提供者已存在则先移除再添加
     */
    private fun setupTestProvider() {
        try {
            if (locationManager.getProvider(LocationManager.GPS_PROVIDER) != null) {
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            }
        } catch (_: Exception) { }
        try {
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false,    // requiresNetwork
                false,    // requiresSatellite
                false,    // requiresCell
                false,    // hasMonetaryCost
                false,    // supportsAltitude
                true,     // supportsSpeed
                true,     // supportsBearing
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        } catch (e: Exception) {
            // 可能未授权 ACCESS_MOCK_LOCATION 权限
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
                val alt = intent.getDoubleExtra(EXTRA_ALT, 0.0)
                isRouteMode = false
                resetJoystick()
                startMocking(lat, lon, alt)
            }
            ACTION_START_ROUTE -> {
                routeLats = intent.getDoubleArrayExtra(EXTRA_LATS) ?: doubleArrayOf()
                routeLons = intent.getDoubleArrayExtra(EXTRA_LONS) ?: doubleArrayOf()
                routeNames = intent.getStringArrayExtra(EXTRA_NAMES) ?: arrayOf()
                intervalMin = intent.getIntExtra(EXTRA_INTERVAL_MIN, 5)
                intervalMax = intent.getIntExtra(EXTRA_INTERVAL_MAX, 25)
                if (routeLats.isNotEmpty()) {
                    isRouteMode = true
                    resetJoystick()
                    routeTotal = routeLats.size
                    routeIndex = 0
                    startRoute()
                }
            }
            ACTION_STOP -> stopMocking()
        }
        return START_STICKY
    }

    private fun startRoute() {
        isRunning = true
        setupTestProvider() // 确保测试提供者就绪

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        mockJob?.cancel()
        mockJob = scope.launch {
            for (i in routeLats.indices) {
                if (!isActive) break

                routeIndex = i
                val lat = routeLats[i]
                val lon = routeLons[i]
                routeName = routeNames.getOrElse(i) { "点${i + 1}" }
                currentLat = lat
                currentLon = lon

                // 更新通知栏
                updateNotification(notification)

                // 持续上报位置（每秒一次）
                val dwellJob = launch {
                    while (isActive) {
                        injectMockLocation(lat, lon, 0.0, 18.0f)
                        delay(1000)
                    }
                }

                // 最后一个点持续等待（直到手动停止）
                if (i == routeLats.size - 1) {
                    dwellJob.join()
                    break
                }

                // 随机延迟 5~25 分钟（转换为毫秒）
                val delayMs = (intervalMin * 60L + 
                    (Math.random() * (intervalMax - intervalMin) * 60).toLong()) * 1000L
                delay(delayMs)
                dwellJob.cancel()
            }
        }
    }

    private fun startMocking(lat: Double, lon: Double, alt: Double) {
        currentLat = lat
        currentLon = lon
        currentAlt = alt
        currentBearing = 0f
        isRunning = true
        routeTotal = 0
        routeIndex = 0
        routeName = ""

        setupTestProvider()

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        mockJob?.cancel()
        mockJob = scope.launch {
            while (isActive) {
                // 摇杆方向移动
                if (joystickActive && !isRouteMode) {
                    val rad = Math.toRadians(joystickAngle)
                    val dyMeters = SPEED_MPS * Math.cos(rad)
                    val dxMeters = SPEED_MPS * Math.sin(rad)
                    val latDelta = dyMeters / METER_PER_DEGREE_LAT
                    val lonDelta = dxMeters / (METER_PER_DEGREE_LAT *
                        Math.cos(Math.toRadians(currentLat)))
                    currentLat += latDelta
                    currentLon += lonDelta
                    currentBearing = joystickAngle.toFloat()
                }

                injectMockLocation(currentLat, currentLon, currentAlt, 18.0f)
                delay(1000)
            }
        }
    }

    /**
     * 核心方法：将位置注入系统
     * 通过 LocationManager.setTestProviderLocation 通知所有监听此 Provider 的 App
     */
    private fun injectMockLocation(lat: Double, lon: Double, alt: Double, accuracy: Float) {
        try {
            val location = Location(LocationManager.GPS_PROVIDER).apply {
                this.latitude = lat
                this.longitude = lon
                this.altitude = alt
                this.accuracy = accuracy
                this.bearing = currentBearing
                this.speed = if (isJoystickActive()) SPEED_MPS.toFloat() else 0f
                this.time = System.currentTimeMillis()
                this.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            // ✅ 关键：注入到系统位置提供者
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        } catch (e: SecurityException) {
            // 未获得模拟位置权限
        } catch (e: IllegalArgumentException) {
            // 测试提供者未设置 → 重新设置
            setupTestProvider()
        } catch (e: Exception) {
            // 其他异常
        }
    }

    private fun buildNotification(): Notification {
        val title = if (isRouteMode) {
            "巡航中: ${routeIndex + 1}/${routeTotal}"
        } else {
            "虚拟GPS运行中"
        }
        val content = if (isRouteMode) {
            "📍 ${routeName}  (%.5f, %.5f)".format(currentLat, currentLon)
        } else {
            "%.5f, %.5f".format(currentLat, currentLon)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) { }
    }

    private fun stopMocking() {
        isRunning = false
        isRouteMode = false
        resetJoystick()
        mockJob?.cancel()
        // 清理测试提供者
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        resetJoystick()
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "虚拟GPS服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
