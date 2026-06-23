package com.fakegps.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import java.lang.reflect.Method
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 模拟定位前台服务
 *
 * 原理（参考主流的 Lerist Fake Location No-Root 模式）：
 * 1. addTestProvider — 创建 mock GPS + NETWORK provider
 * 2. setTestProviderLocation — 以 100ms 频率同时向两个 provider 注入伪造的 Location
 * 3. setIsFromMockProvider(false) via 反射 — 隐藏模拟位置标记
 *    普通 setTestProviderLocation 注入的 Location 的 isFromMockProvider() 返回 true，
 *    地图/打卡 App 检测到后可能忽略。通过反射把该标记改为 false，让 App 认为是真实位置。
 * 4. requestLocationUpdates + 空 listener — 强制系统分发 mock 位置
 * 5. time = System.currentTimeMillis() — 直接用当前时间，避免远古/未来时间戳
 * 6. 坐标变化时 toggle provider 状态，强制广播位置更新
 * 7. 每 30s 重新检查 provider 状态，防止系统回收
 *
 * ⚠️ 同时 mock GPS + NETWORK 两个 provider：
 *   - GPS_PROVIDER: 地图类 App（高德、百度）主要监听这个
 *   - NETWORK_PROVIDER: 微信、打卡 App 等很多用这个
 *   只 mock 一个时，App 可能从另一个 provider 拿到真实/空位置
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

        // 同时 mock GPS + NETWORK，覆盖所有 App 的定位监听方式
        private val GPS_PROVIDER = LocationManager.GPS_PROVIDER
        private val NETWORK_PROVIDER = LocationManager.NETWORK_PROVIDER
        private val MOCK_PROVIDERS = listOf(GPS_PROVIDER, NETWORK_PROVIDER)

        private const val SPEED_MPS = 15.0
        private const val METER_PER_DEGREE_LAT = 111111.0

        /** 注入频率 100ms，与主流 Fake Location App 一致 */
        private const val INJECT_INTERVAL_MS = 100L

        /** 高精度 0.5m，让系统倾向采用我们的数据 */
        private const val MOCK_ACCURACY = 0.5f

        /** 每 30s 检查一次 provider 状态（不销毁重建），防系统回收 */
        private const val CHECK_INTERVAL_MS = 30_000L

        // ---- 反射隐藏 isFromMockProvider 标记 ----

        private var sSetIsFromMockProviderMethod: Method? = null

        private fun hideMockFlag(location: Location) {
            try {
                var method = sSetIsFromMockProviderMethod
                if (method == null) {
                    method = Location::class.java.getDeclaredMethod(
                        "setIsFromMockProvider", Boolean::class.javaPrimitiveType
                    )
                    method.isAccessible = true
                    sSetIsFromMockProviderMethod = method
                }
                method!!.invoke(location, false)
            } catch (_: Exception) { }
        }

        // ---- 状态变量 ----

        private var currentLat = 0.0
        private var currentLon = 0.0
        private var currentAlt = 0.0
        private var currentBearing = 0f

        private var lastInjectedLat = Double.NaN
        private var lastInjectedLon = Double.NaN

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
    private var checkJob: Job? = null
    private lateinit var locationManager: LocationManager
    private val providerLock = ReentrantLock()

    private var isRegisteredLocationListener = false
    private var locationListener: LocationListener? = null

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
        setupProviders()
        registerLocationUpdates()
        startProviderCheck()
        try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) { }

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
                        injectAllProviders(lat, lon, 0.0)
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
        lastInjectedLat = Double.NaN
        lastInjectedLon = Double.NaN
        isRunning = true
        routeTotal = 0
        routeIndex = 0
        routeName = ""

        setupProviders()
        registerLocationUpdates()
        startProviderCheck()

        try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) { }

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

                injectAllProviders(currentLat, currentLon, currentAlt)
                delay(INJECT_INTERVAL_MS)
            }
        }
    }

    /**
     * 向 GPS + NETWORK 两个 provider 同时注入 mock 位置。
     *
     * 关键要点：
     * - time 直接用 System.currentTimeMillis()，不要取模
     *   （取模后变成 1970 年 1 月的远古时间，系统会忽略）
     * - 坐标变化时 toggle provider 广播位置更新
     * - 每个 Location 都调用 hideMockFlag() 隐藏模拟标记
     */
    private fun injectAllProviders(lat: Double, lon: Double, alt: Double) {
        val now = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()

        providerLock.withLock {
            for (provider in MOCK_PROVIDERS) {
                try {
                    val location = Location(provider).apply {
                        this.latitude = lat
                        this.longitude = lon
                        this.altitude = alt
                        this.accuracy = MOCK_ACCURACY
                        this.bearing = currentBearing
                        this.speed = if (joystickActive && !isRouteMode) SPEED_MPS.toFloat() else 0f
                        this.time = now
                        this.elapsedRealtimeNanos = elapsedNs
                    }

                    hideMockFlag(location)
                    locationManager.setTestProviderLocation(provider, location)

                    // 坐标变化时 toggle，强制系统广播给所有注册了 listener 的 App
                    if (lat != lastInjectedLat || lon != lastInjectedLon) {
                        locationManager.setTestProviderEnabled(provider, false)
                        locationManager.setTestProviderEnabled(provider, true)
                    }
                } catch (_: Exception) {
                    // provider 丢失（系统回收了），尝试重建
                    try { setupOneProvider(provider) } catch (_: Exception) { }
                }
            }
            if (lat != lastInjectedLat || lon != lastInjectedLon) {
                lastInjectedLat = lat
                lastInjectedLon = lon
            }
        }
    }

    /**
     * 向 GPS + NETWORK 注册 LocationListener
     * requestLocationUpdates(0, 0) 触发系统立即分发位置
     */
    private fun registerLocationUpdates() {
        if (isRegisteredLocationListener) return
        try {
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) { }
                override fun onProviderEnabled(provider: String) { }
                override fun onProviderDisabled(provider: String) { }
            }
            val listener = locationListener ?: return
            for (provider in MOCK_PROVIDERS) {
                locationManager.requestLocationUpdates(
                    provider, 0L, 0f, listener, Looper.getMainLooper()
                )
            }
            isRegisteredLocationListener = true
        } catch (e: Exception) {
            isRegisteredLocationListener = false
        }
    }

    /**
     * 初始化两个 mock provider（GPS + NETWORK）。
     * 只调用一次，后续只通过 check 保活，不销毁重建。
     */
    private fun setupProviders() {
        providerLock.withLock {
            for (provider in MOCK_PROVIDERS) {
                setupOneProvider(provider)
            }
        }
    }

    private fun setupOneProvider(provider: String) {
        try {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
            val requiresSatellite = (provider == LocationManager.GPS_PROVIDER)
            val requiresNetwork = (provider != LocationManager.GPS_PROVIDER)
            locationManager.addTestProvider(
                provider,
                requiresNetwork,   // requiresNetwork
                requiresSatellite, // requiresSatellite
                false,    // requiresCell
                false,    // hasMonetaryCost
                true,     // supportsAltitude
                true,     // supportsSpeed
                true,     // supportsBearing
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(provider, true)
        } catch (_: Exception) { }
    }

    /**
     * 每 30s 检查 provider 是否还在，如果被回收了则重建。
     * 不销毁/重建已有 provider，避免破坏 listener 绑定。
     */
    private fun startProviderCheck() {
        checkJob?.cancel()
        checkJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                for (provider in MOCK_PROVIDERS) {
                    try {
                        // 通过 setTestProviderLocation 检查 provider 状态
                        // 如果 provider 已被系统回收，会抛异常
                        val testLoc = Location(provider)
                        locationManager.setTestProviderLocation(provider, testLoc)
                    } catch (_: Exception) {
                        try { setupOneProvider(provider) } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    private fun removeAllTestProviders() {
        providerLock.withLock {
            for (provider in MOCK_PROVIDERS) {
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
        checkJob?.cancel()
        isRegisteredLocationListener = false
        try { locationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        locationListener = null
        removeAllTestProviders()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        resetJoystick()
        isRegisteredLocationListener = false
        try { locationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        locationListener = null
        removeAllTestProviders()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "虚拟GPS服务", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
