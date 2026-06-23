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
 * ⭐ 核心原则：不 toggle provider。
 *  setTestProviderEnabled(false/true) 会触发 HMS 融合定位引擎
 *  重新扫描所有位置来源（GPS卫星、WiFi、基站），导致真实信号覆盖 mock 位置。
 *
 *  正确做法：只持续调用 setTestProviderLocation()，每次注入都覆盖前一次，
 *  让 mock 位置始终是系统缓存中的最新值。
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

        private val GPS_PROVIDER = LocationManager.GPS_PROVIDER
        private val NETWORK_PROVIDER = LocationManager.NETWORK_PROVIDER
        private val PASSIVE_PROVIDER = LocationManager.PASSIVE_PROVIDER
        private val MOCK_PROVIDERS = listOf(GPS_PROVIDER, NETWORK_PROVIDER, PASSIVE_PROVIDER)

        private const val SPEED_MPS = 15.0
        private const val METER_PER_DEGREE_LAT = 111111.0
        private const val INJECT_INTERVAL_MS = 100L
        private const val MOCK_ACCURACY = 0.5f
        private const val CHECK_INTERVAL_MS = 30_000L

        // ---- 反射隐藏 isFromMockProvider ----

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

        private var sequenceNo = 0L

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
    private var checkJob: Job? = null
    private lateinit var locationManager: LocationManager
    private val providerLock = ReentrantLock()

    @Volatile
    private var locationListener: LocationListener? = null
    private var listenersRegistered = false

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
        } catch (_: Exception) { }
        return START_STICKY
    }

    private fun startMocking(lat: Double, lon: Double, alt: Double) {
        currentLat = lat
        currentLon = lon
        currentAlt = alt
        currentBearing = 0f
        sequenceNo = 0L
        isRunning = true
        routeTotal = 0; routeIndex = 0; routeName = ""

        setupProviders()
        registerListeners()
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

    private fun startRoute() {
        isRunning = true
        sequenceNo = 0L
        setupProviders()
        registerListeners()
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
                currentLat = lat; currentLon = lon
                updateNotification()

                val dwellJob = launch {
                    while (isActive) {
                        injectAllProviders(lat, lon, 0.0)
                        delay(INJECT_INTERVAL_MS)
                    }
                }

                if (i == routeLats.size - 1) { dwellJob.join(); break }
                val delayMs = (intervalMin * 60L +
                    (Math.random() * (intervalMax - intervalMin) * 60).toLong()) * 1000L
                delay(delayMs)
                dwellJob.cancel()
            }
        }
    }

    /**
     * 向 GPS + NETWORK + PASSIVE 三个 provider 同时注入 mock 位置。
     *
     * ⭐ 不调用 setTestProviderEnabled(false/true) 去 toggle provider！
     *    toggle 会触发 HMS 融合引擎重新扫描所有真实位置源（卫星/WiFi/基站），
     *    导致真实信号覆盖 mock 位置，联网时跳回真实位置。
     *
     * ⭐ 只做 setTestProviderLocation() 持续覆盖，保证 mock 数据始终是最新的。
     *
     * time = now + sequenceNo++ 保证每次注入时间戳唯一递增，
     * 避免系统因重复时间戳判重忽略。
     */
    private fun injectAllProviders(lat: Double, lon: Double, alt: Double) {
        sequenceNo++
        val now = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos() + sequenceNo

        providerLock.withLock {
            for (provider in MOCK_PROVIDERS) {
                try {
                    val location = Location(provider).apply {
                        latitude = lat
                        longitude = lon
                        altitude = alt
                        accuracy = MOCK_ACCURACY
                        bearing = currentBearing
                        speed = if (joystickActive && !isRouteMode) SPEED_MPS.toFloat() else 0f
                        time = now + sequenceNo
                        elapsedRealtimeNanos = elapsedNs
                    }

                    hideMockFlag(location)
                    locationManager.setTestProviderLocation(provider, location)
                } catch (_: Exception) {
                    try { setupOneProvider(provider) } catch (_: Exception) { }
                }
            }
        }
    }

    /**
     * 向三个 provider 注册 LocationListener。
     * requestLocationUpdates(0L, 0f) 让系统尽快分发位置给我们的 listener。
     * 我们自己的 listener 不做事，但能让系统保持分发流程活跃。
     */
    private fun registerListeners() {
        if (listenersRegistered) return
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
            listenersRegistered = true
        } catch (_: Exception) { }
    }

    private fun setupProviders() {
        providerLock.withLock {
            for (provider in MOCK_PROVIDERS) { setupOneProvider(provider) }
        }
    }

    private fun setupOneProvider(provider: String) {
        try {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
            val requiresSatellite = (provider == LocationManager.GPS_PROVIDER)
            val requiresNetwork = (provider == LocationManager.NETWORK_PROVIDER)
            val requiresCell = false
            locationManager.addTestProvider(
                provider, requiresNetwork, requiresSatellite,
                requiresCell, false, true, true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(provider, true)
        } catch (_: Exception) { }
    }

    /**
     * 每 30s 检查 provider 是否还在。
     * 用 setTestProviderLocation 试探，如果抛异常则重建。
     * 不调用 setTestProviderEnabled(false/true) — 不触发 HMS 重新扫描。
     */
    private fun startProviderCheck() {
        checkJob?.cancel()
        checkJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                for (provider in MOCK_PROVIDERS) {
                    try {
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
            for (p in MOCK_PROVIDERS) {
                try { locationManager.removeTestProvider(p) } catch (_: Exception) { }
            }
        }
    }

    // ---- 通知 ----

    private fun buildNotification(): Notification {
        val title = if (isRouteMode) "巡航中: ${routeIndex + 1}/${routeTotal}" else "虚拟GPS运行中"
        val content = if (isRouteMode) "📍 ${routeName}  (%.6f, %.6f)".format(currentLat, currentLon)
                      else "%.6f, %.6f".format(currentLat, currentLon)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true).build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) { }
    }

    // ---- 停止/清理 ----

    private fun stopMocking() {
        isRunning = false; isRouteMode = false; resetJoystick()
        mockJob?.cancel(); checkJob?.cancel()
        listenersRegistered = false
        try { locationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        locationListener = null
        removeAllTestProviders()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false; resetJoystick()
        listenersRegistered = false
        try { locationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        locationListener = null
        removeAllTestProviders()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
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
