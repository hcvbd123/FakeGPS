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
 * 原理（参考主流 Fake Location App 实现）：
 * 1. addTestProvider — 创建 mock GPS provider（仅 GPS_PROVIDER）
 * 2. setTestProviderLocation — 以 100ms 频率注入伪造的 Location 对象
 * 3. setIsFromMockProvider(false) via 反射 — 隐藏模拟位置标记
 *    普通 setTestProviderLocation 注入的 Location 的 isFromMockProvider() 返回 true，
 *    地图/打卡 App 检测到后可能忽略。通过反射把该标记改为 false，让 App 认为是真实位置。
 * 4. requestLocationUpdates + 空 listener — 强制系统分发 mock 位置给所有监听的 App
 * 5. time = now % 1_000_000_000L — 防止序列号无限增长导致 future 时间戳被系统忽略
 *
 * ⚠️ 注意：仅注入 GPS_PROVIDER（不操作 NETWORK/PASSIVE），因为：
 *   - 多个 provider 同时 mock 增加了系统检测到异常的风险
 *   - GPS_PROVIDER 在注入时系统会广播给所有监听位置变化的 App
 *   - 不需要 toggle provider 状态（setTestProviderEnabled false/true）
 *     频繁 toggle 会清空 mock 数据缓存，导致地图跳到未知位置
 *   - 不需要 30s 重设定时器：removeTestProvider + addTestProvider 会破坏 listener 绑定
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

        // 只 mock GPS_PROVIDER — 足够了，系统会广播给所有位置监听者
        private val MOCK_PROVIDER = LocationManager.GPS_PROVIDER

        private const val SPEED_MPS = 15.0
        private const val METER_PER_DEGREE_LAT = 111111.0

        /** 注入频率 100ms，与主流 Fake Location App 一致 */
        private const val INJECT_INTERVAL_MS = 100L

        /** 高精度 0.5m，让系统倾向采用我们的数据 */
        private const val MOCK_ACCURACY = 0.5f

        // ---- 反射隐藏 isFromMockProvider 标记 ----

        /** 反射查找的 setIsFromMockProvider 方法缓存 */
        private var sSetIsFromMockProviderMethod: Method? = null

        /**
         * 通过反射将 location 的 isFromMockProvider 标记设为 false。
         * 这样地图/打卡 App 收到的就是一个"真实位置"，不会检测为模拟。
         *
         * 此方法只反射查找一次，后续复用缓存，性能损失忽略不计。
         */
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
                // method 在 if 块后已确保非空，但 Kotlin 无法跨 try 推导，加 !! 断言
                method!!.invoke(location, false)
            } catch (_: Exception) {
                // 反射失败时静默忽略 — 位置仍然能注入，只是标记可能为 true
            }
        }

        // ---- 状态变量 ----

        private var currentLat = 0.0
        private var currentLon = 0.0
        private var currentAlt = 0.0
        private var currentBearing = 0f

        // 上次注入的坐标，用于判断坐标是否变化
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
        setupProviderOnce()
        registerLocationUpdates()
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
                        injectLocation(lat, lon, 0.0)
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

        setupProviderOnce()
        registerLocationUpdates()

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

                injectLocation(currentLat, currentLon, currentAlt)
                delay(INJECT_INTERVAL_MS)
            }
        }
    }

    /**
     * 注入一个 mock 位置。
     *
     * 关键步骤：
     * 1. 创建 Location 对象，填入目标坐标
     * 2. 反射调用 setIsFromMockProvider(false) — 隐藏模拟标记
     * 3. time = now % 1_000_000_000L — 保证时间戳不超出合理范围
     *    避免 sequenceNo 无限增长导致系统认为"未来时间"而忽略
     * 4. 坐标变化时 toggle provider（false→true）强制触发广播
     *    坐标没变化时不做 toggle，避免清空 mock 数据
     *
     * 仅注入 GPS_PROVIDER。NETWORK/PASSIVE 不注入，因为 GPS_PROVIDER
     * 的 setTestProviderLocation 足以让系统广播给所有位置监听者。
     */
    private fun injectLocation(lat: Double, lon: Double, alt: Double) {
        val now = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()

        providerLock.withLock {
            try {
                val location = Location(MOCK_PROVIDER).apply {
                    this.latitude = lat
                    this.longitude = lon
                    this.altitude = alt
                    this.accuracy = MOCK_ACCURACY
                    this.bearing = currentBearing
                    this.speed = if (joystickActive && !isRouteMode) SPEED_MPS.toFloat() else 0f
                    // 用当前时间取模，防止时间戳变成"未来时间"
                    // 系统可能忽略 future 时间戳的位置更新
                    this.time = now % 1_000_000_000L
                    this.elapsedRealtimeNanos = elapsedNs
                }

                // ⭐ 核心：反射隐藏 isFromMockProvider 标记
                // Fake_Location 用的就是同一技术 — 让地图/打卡 App 认为这是真实位置
                hideMockFlag(location)

                locationManager.setTestProviderLocation(MOCK_PROVIDER, location)

                // 坐标真正变化时，toggle provider 来强制广播
                // 坐标没变时保持不动，避免清空 mock 数据
                if (lat != lastInjectedLat || lon != lastInjectedLon) {
                    locationManager.setTestProviderEnabled(MOCK_PROVIDER, false)
                    locationManager.setTestProviderEnabled(MOCK_PROVIDER, true)
                    lastInjectedLat = lat
                    lastInjectedLon = lon
                }
            } catch (_: Exception) {
                // provider 丢失（系统清理了 mock provider），尝试重建
                try { setupProviderOnce() } catch (_: Exception) { }
            }
        }
    }

    /**
     * 注册 LocationListener — 通过 requestLocationUpdates(0L, 0f)
     * 强制系统立即分发 mock 位置给所有监听该 Provider 的 App。
     *
     * 这是 Fake_Location 类 App 的标准做法：
     * 不加这个 listener，地图 App 不会实时收到位置更新广播。
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
            locationManager.requestLocationUpdates(
                MOCK_PROVIDER,
                0L,
                0f,
                listener,
                Looper.getMainLooper()
            )
            isRegisteredLocationListener = true
        } catch (e: Exception) {
            isRegisteredLocationListener = false
        }
    }

    /**
     * 初始化 mock provider — 只调用一次。
     * 后续不销毁重建，避免破坏 listener 绑定。
     * 如果 injectLocation 的 try-catch 捕获到 provider 丢失，
     * 会兜底重建。
     */
    private fun setupProviderOnce() {
        providerLock.withLock {
            try {
                // 先尝试移除旧 provider（可能不存在，忽略异常）
                try {
                    locationManager.removeTestProvider(MOCK_PROVIDER)
                } catch (_: Exception) { }

                locationManager.addTestProvider(
                    MOCK_PROVIDER,
                    false,   // requiresNetwork
                    true,    // requiresSatellite — GPS 需要卫星
                    false,   // requiresCell
                    false,   // hasMonetaryCost
                    true,    // supportsAltitude
                    true,    // supportsSpeed
                    true,    // supportsBearing
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(MOCK_PROVIDER, true)
            } catch (_: Exception) { }
        }
    }

    private fun removeTestProviderSafely() {
        providerLock.withLock {
            try {
                locationManager.removeTestProvider(MOCK_PROVIDER)
            } catch (_: Exception) { }
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
        isRegisteredLocationListener = false
        // 先 removeUpdates 再 removeTestProvider
        try {
            locationListener?.let { locationManager.removeUpdates(it) }
        } catch (_: Exception) { }
        locationListener = null
        removeTestProviderSafely()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        resetJoystick()
        isRegisteredLocationListener = false
        try {
            locationListener?.let { locationManager.removeUpdates(it) }
        } catch (_: Exception) { }
        locationListener = null
        removeTestProviderSafely()
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
