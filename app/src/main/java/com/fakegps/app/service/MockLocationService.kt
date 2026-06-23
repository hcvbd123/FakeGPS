package com.fakegps.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 模拟定位前台服务
 *
 * 策略：
 * 1. 向 GPS + Network + Passive 三个 Provider 注入 mock 位置
 * 2. addTestProvider 参数匹配真实 Provider 属性，让系统信任 mock 数据
 * 3. 通过 requestLocationUpdates 强制广播位置（让地图 App 实时刷新）
 * 4. 每 30s 重新 setup provider，防止华为系统清理 mock provider
 * 5. 高频 + 高精度，让融合定位引擎优先采纳 mock 数据
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

        // 高频注入：100ms — 让系统持续收到 mock 位置，减少真实位置覆盖概率
        private const val INJECT_INTERVAL_MS = 100L

        // 高精度：0.5m — 让融合定位认为我们的数据"更好"，倾向选用
        private const val MOCK_ACCURACY = 0.5f

        // 每 30s 重新 setup provider，防华为系统清理
        private const val RE_SETUP_INTERVAL_MS = 30_000L

        private var currentLat = 0.0
        private var currentLon = 0.0
        private var currentAlt = 0.0
        private var currentBearing = 0f

        // 递增序列号，让每次位置都有不同的 time，防止系统去重
        private var sequenceNo = 0L

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
    private var reSetupJob: Job? = null
    private lateinit var locationManager: LocationManager
    private val providerLock = ReentrantLock()

    // requestLocationUpdates 用
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
        try {
            createNotificationChannel()
        } catch (_: Exception) { }
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
        } catch (e: Exception) {
            // 防止闪退
        }
        return START_STICKY
    }

    private fun startRoute() {
        isRunning = true
        setupAllTestProvidersSafely()
        registerLocationUpdates()
        startReSetupTimer()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
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
        sequenceNo = 0L
        isRunning = true
        routeTotal = 0
        routeIndex = 0
        routeName = ""

        setupAllTestProvidersSafely()
        registerLocationUpdates()
        startReSetupTimer()

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
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

    private fun injectAll(lat: Double, lon: Double, alt: Double) {
        sequenceNo++
        val now = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()

        providerLock.withLock {
            for (provider in PROVIDERS) {
                try {
                    val location = Location(provider).apply {
                        this.latitude = lat
                        this.longitude = lon
                        this.altitude = alt
                        this.accuracy = MOCK_ACCURACY
                        this.bearing = currentBearing
                        this.speed = if (joystickActive && !isRouteMode) SPEED_MPS.toFloat() else 0f
                        // 每次注入使用不同的 time，让系统认为每次都是"新位置"
                        this.time = now + sequenceNo
                        this.elapsedRealtimeNanos = elapsedNs + sequenceNo
                    }
                    locationManager.setTestProviderLocation(provider, location)
                    // Force broadcast to all listening apps by toggling provider state
                    locationManager.setTestProviderEnabled(provider, false)
                    locationManager.setTestProviderEnabled(provider, true)
            }
        }
    }

    /**
     * 注册 LocationListener，通过 requestLocationUpdates 0ms 0m
     * 强制系统立即分发 mock 位置到所有监听该 Provider 的 App
     * 确保地图 App 实时刷新显示 mock 位置
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
            for (provider in PROVIDERS) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                } catch (_: Exception) { }
            }
            isRegisteredLocationListener = true
        } catch (e: Exception) {
            isRegisteredLocationListener = false
        }
    }

    /**
     * 每 30s 重新 setup provider
     * 华为系统可能自动清理 mock provider（尤其深度睡眠/省电后）
     */
    private fun startReSetupTimer() {
        reSetupJob?.cancel()
        reSetupJob = scope.launch {
            while (isActive) {
                delay(RE_SETUP_INTERVAL_MS)
                try {
                    setupAllTestProvidersSafely()
                } catch (_: Exception) { }
            }
        }
    }

    private fun setupAllTestProvidersSafely() {
        providerLock.withLock {
            for (provider in PROVIDERS) {
                try {
                    // 先移除旧的，再添加新的
                    try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
                    locationManager.addTestProvider(
                        provider,
                        provider == LocationManager.NETWORK_PROVIDER,  // requiresNetwork
                        provider == LocationManager.GPS_PROVIDER,      // requiresSatellite
                        provider == LocationManager.NETWORK_PROVIDER,  // requiresCell
                        false,                                          // hasMonetaryCost
                        true,                                           // supportsAltitude
                        true,                                           // supportsSpeed
                        true,                                           // supportsBearing
                        Criteria.POWER_LOW,                             // powerRequirement
                        Criteria.ACCURACY_FINE                          // accuracyRequirement
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
        reSetupJob?.cancel()
        isRegisteredLocationListener = false
        removeAllTestProvidersSafely()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        resetJoystick()
        isRegisteredLocationListener = false
        removeAllTestProvidersSafely()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
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
