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

/**
 * 模拟定位前台服务 — 针对 HMS 融合引擎优化
 *
 * ⭐ 关键策略：
 * 1. 极高精度（0.1m）— 让系统认为我们的数据比真实GPS更"好"
 * 2. 高频注入（20ms）— 确保每次系统查询位置时，最新的都是我们的mock
 * 3. time 始终超前系统时间5ms — 让系统认为我们的数据是最"新"的
 *    这样当真实GPS数据回来时，系统仍然认为我们的mock时间更新
 * 4. 同时 mock GPS + NETWORK + PASSIVE — 覆盖所有App的定位来源
 * 5. 不做任何 setTestProviderEnabled toggle — 不触发HMS重新扫描
 */
class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "fake_gps_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.fakegps.START_MOCK"
        const val ACTION_STOP = "com.fakegps.STOP_MOCK"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"

        private val GPS_PROVIDER = LocationManager.GPS_PROVIDER
        private val NETWORK_PROVIDER = LocationManager.NETWORK_PROVIDER
        private val PASSIVE_PROVIDER = LocationManager.PASSIVE_PROVIDER
        private val MOCK_PROVIDERS = listOf(PASSIVE_PROVIDER, GPS_PROVIDER, NETWORK_PROVIDER)

        /** 高频注入 20ms — 比真实GPS定位（通常1秒以上）快50倍 */
        private const val INJECT_INTERVAL_MS = 20L

        /** 极高精度 0.1m — 让系统在选择位置时优先选择我们的 */
        private const val MOCK_ACCURACY = 0.1f

        // ---- 反射隐藏 isFromMockProvider ----

        private var sSetIsFromMockProviderMethod: Method? = null

        private fun hideMockFlag(location: Location) {
            try {
                var m = sSetIsFromMockProviderMethod
                if (m == null) {
                    m = Location::class.java.getDeclaredMethod(
                        "setIsFromMockProvider", Boolean::class.javaPrimitiveType
                    )
                    m.isAccessible = true
                    sSetIsFromMockProviderMethod = m
                }
                m!!.invoke(location, false)
            } catch (_: Exception) { }
        }

        // ---- 状态 ----
        var isRunning = false
            private set
        var currentLat = 0.0
            private set
        var currentLon = 0.0
            private set

        fun isMocking(): Boolean = isRunning
        fun getCurrentLocation(): Pair<Double, Double> = Pair(currentLat, currentLon)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mockJob: Job? = null
    private lateinit var locationManager: LocationManager
    private var locationListener: LocationListener? = null

    /** 时间偏移量，每次注入+5ms，确保永远超前于真实时间 */
    private var timeOffset = 0L

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
                    startMocking(lat, lon)
                }
                ACTION_STOP -> stopMocking()
            }
        } catch (_: Exception) { }
        return START_STICKY
    }

    private fun startMocking(lat: Double, lon: Double) {
        currentLat = lat
        currentLon = lon
        timeOffset = 0L
        isRunning = true

        // 1. 设置所有 mock provider
        setupProviders()
        // 2. 注册监听器（确保系统分发位置）
        registerListeners()
        // 3. 前台通知
        try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) { }

        // 4. 注入循环 — 20ms 间隔
        mockJob?.cancel()
        mockJob = scope.launch {
            while (isActive) {
                injectToAllProviders(currentLat, currentLon)
                delay(INJECT_INTERVAL_MS)
            }
        }
    }

    /**
     * 向所有 provider 注入 mock 位置。
     *
     * ⭐ time = now + timeOffset（每次 +5ms）
     *   这样注入的时间永远比真实GPS数据"新"，系统优先采用。
     *   真实 GPS 定位的 time 是 System.currentTimeMillis()，不会超前。
     *
     * ⭐ accuracy = 0.1m
     *   真实 GPS 精度通常 3-10m，我们的 0.1m 会被系统认为"更准确"。
     *   当系统或 App 在多源位置中选择时，会选择精度更高的。
     */
    private fun injectToAllProviders(lat: Double, lon: Double) {
        timeOffset += 5  // 每次 +5ms，永远超前
        val now = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()

        for (provider in MOCK_PROVIDERS) {
            try {
                val location = Location(provider).apply {
                    latitude = lat
                    longitude = lon
                    altitude = 0.0
                    accuracy = MOCK_ACCURACY  // 0.1m — 极高精度
                    bearing = 0f
                    speed = 0f
                    time = now + timeOffset   // 超前当前时间
                    elapsedRealtimeNanos = elapsedNs + timeOffset
                }

                hideMockFlag(location)         // 隐藏模拟标记
                locationManager.setTestProviderLocation(provider, location)
            } catch (_: Exception) {
                // provider 被回收，重建
                try { setupOneProvider(provider) } catch (_: Exception) { }
            }
        }
    }

    private fun setupProviders() {
        for (provider in MOCK_PROVIDERS) { setupOneProvider(provider) }
    }

    private fun setupOneProvider(provider: String) {
        try {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
            val isGPS = (provider == LocationManager.GPS_PROVIDER)
            val isNetwork = (provider == LocationManager.NETWORK_PROVIDER)
            locationManager.addTestProvider(
                provider,
                isNetwork,     // requiresNetwork
                isGPS,         // requiresSatellite
                false,         // requiresCell
                false,         // hasMonetaryCost
                true,          // supportsAltitude
                true,          // supportsSpeed
                true,          // supportsBearing
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(provider, true)
        } catch (_: Exception) { }
    }

    private fun registerListeners() {
        try {
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) { }
                override fun onProviderEnabled(provider: String) { }
                override fun onProviderDisabled(provider: String) { }
            }
            for (provider in MOCK_PROVIDERS) {
                locationManager.requestLocationUpdates(
                    provider, 0L, 0f, locationListener!!, Looper.getMainLooper()
                )
            }
        } catch (_: Exception) { }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("虚拟GPS运行中")
            .setContentText("%.6f, %.6f".format(currentLat, currentLon))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    // ---- 清理 ----

    private fun stopMocking() {
        isRunning = false
        mockJob?.cancel()
        try { locationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        locationListener = null
        for (p in MOCK_PROVIDERS) {
            try { locationManager.removeTestProvider(p) } catch (_: Exception) { }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        try { locationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        locationListener = null
        for (p in MOCK_PROVIDERS) {
            try { locationManager.removeTestProvider(p) } catch (_: Exception) { }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "虚拟GPS", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
