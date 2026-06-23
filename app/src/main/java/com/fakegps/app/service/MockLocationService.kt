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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 模拟定位前台服务
 * 支持单点定位 + 多坐标自动巡航 + 摇杆方向控制
 *
 * 模拟策略：
 * 1. 同时向 GPS + Network + Passive 三个 Provider 注入位置
 * 2. 高频率（200ms）+ 高精度（4m），让融合定位引擎倾向使用我们的数据
 * 3. 完整模拟 Location 属性（精度、速度、方向、时间戳、海拔）
 * 4. 使用锁保护 Provider 操作，防止并发闪退
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

        private const val SPEED_MPS = 15.0
        private const val METER_PER_DEGREE_LAT = 111111.0
        // 注入间隔（毫秒）——高频持续注入让系统更倾向于使用我们的数据
        private const val INJECT_INTERVAL_MS = 200L

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

        /** 需要 mock 的 Provider */
        private val PROVIDERS = arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mockJob: Job? = null
    private lateinit var locationManager: LocationManager

    /** 保护 testProvider 操作，防止并发调 add/remove 导致的闪退 */
    private val providerLock = ReentrantLock()

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
        setupAllTestProvidersSafely()

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

                updateNotification()

                val dwellJob = launch {
                    while (isActive) {
                        injectToAllProviders(lat, lon, 0.0)
                        delay(INJECT_INTERVAL_MS)
                    }
                }

                if (i == routeLats.size - 1) {
                    dwellJob.join()
                    break
                }

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

        setupAllTestProvidersSafely()

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        mockJob?.cancel()
        mockJob = scope.launch {
            while (isActive) {
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

                injectToAllProviders(currentLat, currentLon, currentAlt)
                delay(INJECT_INTERVAL_MS)
            }
        }
    }

    /**
     * 核心方法：同时向三个 Provider 高速注入高精度位置
     *
     * 策略说明：
     * - 精度设为 4 米（比真实 GPS 3-8 米还略好），让融合定位引擎优先选用
     * - 频率 200ms（比默认定位周期快），保证位置始终"新鲜"
     * - 三个 Provider 都注入同一坐标，消除数据源冲突
     */
    private fun injectToAllProviders(lat: Double, lon: Double, alt: Double) {
        val time = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()

        providerLock.withLock {
            for (provider in PROVIDERS) {
                try {
                    val location = Location(provider).apply {
                        this.latitude = lat
                        this.longitude = lon
                        this.altitude = alt
                        // 精度 4 米——比真实 GPS 还好，让融合定位引擎更倾向于使用我们的数据
                        this.accuracy = 4.0f
                        this.bearing = currentBearing
                        this.speed = if (joystickActive && !isRouteMode) SPEED_MPS.toFloat() else 0f
                        this.time = time
                        this.elapsedRealtimeNanos = elapsedNs
                    }
                    locationManager.setTestProviderLocation(provider, location)
                } catch (_: Exception) {
                    // Provider 可能被移除，忽略静默
                }
            }
        }
    }

    /**
     * 使用锁保护的 Provider 设置
     * 防止快速开始/停止导致并发 add/remove 闪退
     */
    private fun setupAllTestProvidersSafely() {
        providerLock.withLock {
            for (provider in PROVIDERS) {
                try {
                    try {
                        locationManager.removeTestProvider(provider)
                    } catch (_: Exception) { /* 没有这个 provider，忽略 */ }
                    locationManager.addTestProvider(
                        provider,
                        false, false, false, false,
                        true, true, true,
                        android.location.Criteria.POWER_LOW,
                        android.location.Criteria.ACCURACY_FINE
                    )
                    locationManager.setTestProviderEnabled(provider, true)
                } catch (_: Exception) { }
            }
        }
    }

    private fun removeAllTestProvidersSafely() {
        providerLock.withLock {
            for (provider in PROVIDERS) {
                try {
                    locationManager.removeTestProvider(provider)
                } catch (_: Exception) { }
            }
        }
    }

    private fun buildNotification(): Notification {
        val title = if (isRouteMode) {
            "巡航中: ${routeIndex + 1}/${routeTotal}"
        } else {
            "虚拟GPS运行中"
        }
        val content = if (isRouteMode) {
            "📍 ${routeName}  (%.6f, %.6f)".format(currentLat, currentLon)
        } else {
            "%.6f, %.6f".format(currentLat, currentLon)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
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
        removeAllTestProvidersSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        resetJoystick()
        removeAllTestProvidersSafely()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "虚拟GPS服务", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
