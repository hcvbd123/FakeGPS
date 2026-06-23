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
 *
 * 模拟策略（双重保障）：
 * 方案A：HMS Location Kit 反射调用（华为手机专用）
 *   → FusedLocationProviderClient.setMockMode(true)
 *   → 告诉 HMS 停止使用真实 GPS/WiFi，只接受 mock 位置
 *   → 联网/断网都能生效
 *
 * 方案B：标准 Android mock Provider（兜底）
 *   → GPS + Network + Passive 三个 Provider
 *   → 非华为手机、无 HMS Core 时使用
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

        private val PROVIDERS = arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mockJob: Job? = null
    private lateinit var locationManager: LocationManager
    private val providerLock = ReentrantLock()

    // HMS 反射相关
    private var hmsFusedClient: Any? = null
    private var hmsSetMockModeMethod: java.lang.reflect.Method? = null
    private var hmsSetMockLocationMethod: java.lang.reflect.Method? = null
    private var hmsTaskWaitMethod: java.lang.reflect.Method? = null
    private var hmsHasHms = false

    // 巡航数据
    private var routeLats = doubleArrayOf()
    private var routeLons = doubleArrayOf()
    private var routeNames = arrayOf<String>()
    private var intervalMin = 5
    private var intervalMax = 25

    override fun onCreate() {
        super.onCreate()
        try { createNotificationChannel() } catch (_: Exception) { }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 初始化 HMS FusedLocationProviderClient（反射方式）
        initHms()
    }

    /**
     * 通过反射初始化 HMS Location Kit
     * 不依赖 agconnect-services.json 或任何 Gradle 插件
     */
    private fun initHms() {
        try {
            val locationServicesClass = Class.forName("com.huawei.hms.location.LocationServices")
            val getFusedMethod = locationServicesClass.getMethod(
                "getFusedLocationProviderClient", Context::class.java
            )
            hmsFusedClient = getFusedMethod.invoke(null, this)

            val clientClass = hmsFusedClient?.javaClass
            hmsSetMockModeMethod = clientClass?.getMethod(
                "setMockMode", Boolean::class.javaPrimitiveType
            )
            hmsSetMockLocationMethod = clientClass?.getMethod(
                "setMockLocation", Location::class.java
            )

            // Task.getResult() 用于等待异步结果
            hmsTaskWaitMethod = Class.forName("com.huawei.hmf.tasks.Task")
                .getMethod("getResult")

            hmsHasHms = hmsSetMockModeMethod != null && hmsSetMockLocationMethod != null
        } catch (e: Exception) {
            hmsHasHms = false
        }
    }

    /**
     * 调用 HMS FusedLocationProviderClient.setMockMode(true)
     * 让华为定位服务停止使用真实 GPS/WiFi
     */
    private fun enableHmsMockMode() {
        val client = hmsFusedClient ?: return
        val method = hmsSetMockModeMethod ?: return
        if (!hmsHasHms) return

        try {
            val task = method.invoke(client, true) ?: return
            try {
                hmsTaskWaitMethod?.invoke(task)
            } catch (_: Exception) { }
        } catch (e: Exception) { }
    }

    /**
     * 调用 HMS FusedLocationProviderClient.setMockLocation()
     */
    private fun injectHmsMockLocation(lat: Double, lon: Double) {
        val client = hmsFusedClient ?: return
        val method = hmsSetMockLocationMethod ?: return
        if (!hmsHasHms) return

        try {
            val location = Location(LocationManager.GPS_PROVIDER).apply {
                this.latitude = lat
                this.longitude = lon
                this.accuracy = 4.0f
                this.time = System.currentTimeMillis()
                this.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            val task = method.invoke(client, location) ?: return
            try {
                hmsTaskWaitMethod?.invoke(task)
            } catch (_: Exception) { }
        } catch (e: Exception) { }
    }

    /**
     * 关闭 HMS Mock 模式
     */
    private fun disableHmsMockMode() {
        val client = hmsFusedClient ?: return
        val method = hmsSetMockModeMethod ?: return
        if (!hmsHasHms) return

        try {
            val task = method.invoke(client, false) ?: return
            try {
                hmsTaskWaitMethod?.invoke(task)
            } catch (_: Exception) { }
        } catch (e: Exception) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
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
        } catch (e: Exception) { }
        return START_STICKY
    }

    private fun startRoute() {
        isRunning = true
        setupAllTestProvidersSafely()
        enableHmsMockMode()

        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) { }

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
                        injectAll(lat, lon, 0.0)
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
        enableHmsMockMode()

        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) { }

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

                injectAll(currentLat, currentLon, currentAlt)
                delay(INJECT_INTERVAL_MS)
            }
        }
    }

    /**
     * 核心注入：同时使用 HMS mock + 标准 Provider mock
     */
    private fun injectAll(lat: Double, lon: Double, alt: Double) {
        // 方案A：HMS Location Kit — 华为手机专属，联网也生效
        injectHmsMockLocation(lat, lon)

        // 方案B：标准 Provider — 所有 Android 兜底
        injectToAllProviders(lat, lon, alt)
    }

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
                        this.accuracy = 4.0f
                        this.bearing = currentBearing
                        this.speed = if (joystickActive && !isRouteMode) SPEED_MPS.toFloat() else 0f
                        this.time = time
                        this.elapsedRealtimeNanos = elapsedNs
                    }
                    locationManager.setTestProviderLocation(provider, location)
                } catch (_: Exception) { }
            }
        }
    }

    private fun setupAllTestProvidersSafely() {
        providerLock.withLock {
            for (provider in PROVIDERS) {
                try {
                    try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
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
                try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
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

        // 关闭 HMS mock 模式
        disableHmsMockMode()

        removeAllTestProvidersSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        resetJoystick()
        disableHmsMockMode()
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
