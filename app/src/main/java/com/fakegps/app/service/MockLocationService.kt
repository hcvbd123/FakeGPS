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
 * 模拟定位前台服务 — v9 拦截器策略
 *
 * ⭐ 核心思路：
 * 当真实GPS位置到达系统时，我们的 LocationListener 第一时间收到回调，
 * 立即在回调中注入 mock 位置覆盖掉真实数据。
 * 这样就算 HMS 融合引擎推送了真实坐标，我们在它到达 App 之前就替换了。
 *
 * 策略组合：
 * 1. isDebuggable = true（还原，否则HarmonyOS不认mock）
 * 2. 拦截器监听器 — 实时监控所有 provider，发现真实位置立即覆盖
 * 3. 背景注入 100ms（保持持续覆盖）
 * 4. 真实位置抵达瞬间，额外再以 10ms 快速连续注入 5 次（确保覆盖推送）
 * 5. setIsFromMockProvider(false) 反射隐藏mock标记
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

        private const val INJECT_BG_MS = 100L       // 背景注入间隔
        private const val BURST_COUNT = 5            // 拦截后爆发注入次数
        private const val BURST_INTERVAL_MS = 10L    // 爆发注入间隔
        private const val MOCK_ACCURACY = 0.5f

        // 反射隐藏 mock 标记
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

        var isRunning = false; private set
        var currentLat = 0.0; private set
        var currentLon = 0.0; private set

        fun isMocking(): Boolean = isRunning
        fun getCurrentLocation(): Pair<Double, Double> = Pair(currentLat, currentLon)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bgInjectJob: Job? = null
    private lateinit var locationManager: LocationManager
    private var realLocationListener: LocationListener? = null

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
        currentLat = lat; currentLon = lon
        isRunning = true

        setupProviders()

        // 前台通知
        try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) { }

        // 0. ⭐ Fake Location 式 Provider Toggle 序列
        // 关开 network → 关开 gps → 关开 network（复刻 Fake Location 的启动模式）
        scope.launch {
            // 等 setupProviders 稳定
            delay(500)

            // 关开 network
            try { locationManager.setTestProviderEnabled(NETWORK_PROVIDER, false) } catch (_: Exception) { }
            delay(200)
            try { locationManager.setTestProviderEnabled(NETWORK_PROVIDER, true) } catch (_: Exception) { }
            delay(300)

            // 关开 gps
            try { locationManager.setTestProviderEnabled(GPS_PROVIDER, false) } catch (_: Exception) { }
            delay(200)
            try { locationManager.setTestProviderEnabled(GPS_PROVIDER, true) } catch (_: Exception) { }
            delay(300)

            // 再关开 network
            try { locationManager.setTestProviderEnabled(NETWORK_PROVIDER, false) } catch (_: Exception) { }
            delay(200)
            try { locationManager.setTestProviderEnabled(NETWORK_PROVIDER, true) } catch (_: Exception) { }
            delay(300)

            // ⭐ Toggle 完成后：先注入一次，再注册拦截器，再启动背景注入
            injectToAllProviders()
            delay(100)
            injectToAllProviders()

            registerInterceptor()

            bgInjectJob?.cancel()
            bgInjectJob = scope.launch {
                while (isActive) {
                    injectToAllProviders()
                    delay(INJECT_BG_MS)
                }
            }
        }
    }

    /**
     * 拦截器监听器。
     * 当任何 provider 推送了真实位置，我们立即收到 onLocationChanged。
     * 在这个回调中，我们立刻注入 mock 位置，不让真实数据扩散到 App。
     *
     * 额外效果：爆发注入 5 次（10ms间隔）确保覆盖所有 App 的位置缓存。
     */
    private val interceptor = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // 不管收到什么位置，立刻注入我们的 mock
            injectToAllProviders()
            // 爆发注入 5 次，10ms 间隔
            scope.launch {
                for (i in 0 until BURST_COUNT) {
                    injectToAllProviders()
                    delay(BURST_INTERVAL_MS)
                }
            }
            updateNotification()
        }
        override fun onProviderEnabled(provider: String) {
            // provider 被启用，重新注入确保我们的 mock 最新
            injectToAllProviders()
        }
        override fun onProviderDisabled(provider: String) { }
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) { }
    }

    private fun registerInterceptor() {
        try {
            realLocationListener?.let { locationManager.removeUpdates(it) }
            realLocationListener = interceptor

            for (provider in MOCK_PROVIDERS) {
                // 0ms minTime — 有变化马上通知我们
                locationManager.requestLocationUpdates(
                    provider, 0L, 0f, interceptor, Looper.getMainLooper()
                )
            }
        } catch (_: Exception) { }
    }

    /**
     * 向所有 mock provider 注入当前位置
     */
    private fun injectToAllProviders() {
        val now = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()

        for (provider in MOCK_PROVIDERS) {
            try {
                val location = Location(provider).apply {
                    latitude = currentLat
                    longitude = currentLon
                    altitude = 0.0
                    accuracy = MOCK_ACCURACY
                    bearing = 0f
                    speed = 0f
                    time = now
                    elapsedRealtimeNanos = elapsedNs
                }
                hideMockFlag(location)
                locationManager.setTestProviderLocation(provider, location)
            } catch (_: Exception) {
                // provider 失效，重建（但只做 addTestProvider，不做 toggle）
                try {
                    locationManager.removeTestProvider(provider)
                } catch (_: Exception) { }
                try {
                    locationManager.addTestProvider(
                        provider,
                        provider == NETWORK_PROVIDER,
                        provider == GPS_PROVIDER,
                        false, false, true, true, true,
                        android.location.Criteria.POWER_LOW,
                        android.location.Criteria.ACCURACY_FINE
                    )
                    locationManager.setTestProviderEnabled(provider, true)
                } catch (_: Exception) { }
            }
        }
    }

    private fun setupProviders() {
        for (provider in MOCK_PROVIDERS) {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
            try {
                locationManager.addTestProvider(
                    provider,
                    provider == NETWORK_PROVIDER,
                    provider == GPS_PROVIDER,
                    false, false, true, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
            } catch (_: Exception) { }
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("虚拟GPS运行中")
            .setContentText("%.6f, %.6f".format(currentLat, currentLon))
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
        bgInjectJob?.cancel()
        try { realLocationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        realLocationListener = null
        for (p in MOCK_PROVIDERS) { try { locationManager.removeTestProvider(p) } catch (_: Exception) { } }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        try { realLocationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        for (p in MOCK_PROVIDERS) { try { locationManager.removeTestProvider(p) } catch (_: Exception) { } }
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
