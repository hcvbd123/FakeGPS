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
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 模拟定位前台服务 — v10 稳定性优化
 *
 * 核心思路（不变）：
 * 当真实GPS位置到达系统时，拦截器第一时间覆盖为 mock 数据
 *
 * v10 稳定性修复：
 * 1. 注入锁（injectLock）— 防多线程并发注入导致系统卡死
 * 2. 拦截器回调委托到 HandlerThread — 不阻塞主线程
 * 3. 启动/停止原子标记（started atomic）— 防 stop 与启动序列竞态
 * 4. 爆发注入使用单一 Job 防堆积（burstJob 可取消）
 * 5. 前台通知延迟到 IO 线程调用（防 strict mode 警告）
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

        private const val INJECT_BG_MS = 100L
        private const val BURST_COUNT = 5
        private const val BURST_INTERVAL_MS = 10L
        private const val MOCK_ACCURACY = 0.5f

        // 反射隐藏 mock 标记（线程安全 + 懒加载）
        private val hideMockFlagMethod = AtomicReference<Method?>(null)
        private fun hideMockFlag(location: Location) {
            try {
                var m = hideMockFlagMethod.get()
                if (m == null) {
                    m = Location::class.java.getDeclaredMethod(
                        "setIsFromMockProvider", Boolean::class.javaPrimitiveType
                    )
                    m.isAccessible = true
                    hideMockFlagMethod.set(m)
                }
                m!!.invoke(location, false)
            } catch (_: Exception) { }
        }

        // ⭐ 原子标记，所有线程可见
        @Volatile
        var isRunning = false; private set

        // 坐标读写用 AtomicReference 保证可见性
        private val currentLocationRef = AtomicReference(doubleArrayOf(0.0, 0.0))

        fun isMocking(): Boolean = isRunning
        fun getCurrentLocation(): Pair<Double, Double> {
            val arr = currentLocationRef.get()
            return Pair(arr[0], arr[1])
        }
    }

    // ⭐ 注入互斥锁 — 防止多线程同时 setTestProviderLocation
    private val injectLock = Any()

    // ⭐ 启动标记：true 后 toggle 序列才执行后续工作，stopMocking 会将其置 false
    private val started = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bgInjectJob: Job? = null
    private var burstJob: Job? = null
    private var toggleJob: Job? = null

    private lateinit var locationManager: LocationManager
    private var interceptorHandler: android.os.HandlerThread? = null
    private var interceptorHandlerRef: WeakReference<android.os.Handler>? = null
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
        // 如果已经在运行，先停止旧的
        if (started.getAndSet(true)) {
            stopMockingInner()
        }

        currentLocationRef.set(doubleArrayOf(lat, lon))
        isRunning = true

        // ⭐ setup / startForeground 在 IO 协程执行，不阻塞主线程
        scope.launch {
            try {
                setupProviders()
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (_: Exception) { }

            // 检查是否在等待期间被停止
            if (!started.get()) return@launch

            delay(500)
            if (!started.get()) return@launch

            // ⭐ Toggle 序列
            toggleNetworkOnce("toggle-1")
            if (!started.get()) return@launch
            toggleGpsOnce()
            if (!started.get()) return@launch
            toggleNetworkOnce("toggle-2")
            if (!started.get()) return@launch

            // 先预注入
            safeInjectAll()
            delay(100)
            if (!started.get()) return@launch
            safeInjectAll()

            // 注册拦截器
            registerInterceptor()

            // 启动背景注入
            bgInjectJob?.cancel()
            bgInjectJob = scope.launch {
                while (isActive && started.get()) {
                    safeInjectAll()
                    delay(INJECT_BG_MS)
                }
            }
        }
    }

    private suspend fun toggleNetworkOnce(tag: String) {
        try { locationManager.setTestProviderEnabled(NETWORK_PROVIDER, false) } catch (_: Exception) { }
        delayOrCancel(200)
        try { locationManager.setTestProviderEnabled(NETWORK_PROVIDER, true) } catch (_: Exception) { }
        delayOrCancel(300)
    }

    private suspend fun toggleGpsOnce() {
        try { locationManager.setTestProviderEnabled(GPS_PROVIDER, false) } catch (_: Exception) { }
        delayOrCancel(200)
        try { locationManager.setTestProviderEnabled(GPS_PROVIDER, true) } catch (_: Exception) { }
        delayOrCancel(300)
    }

    private suspend fun delayOrCancel(ms: Long) {
        // 分段延迟+检查停止标记
        val step = 50L
        var remaining = ms
        while (remaining > 0 && started.get()) {
            delay(minOf(step, remaining))
            remaining -= step
        }
    }

    // ⭐ 拦截器：回调委托到独立 HandlerThread，不阻塞主线程
    private val interceptor = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!started.get()) return
            safeInjectAll()
            // 爆发注入用一个可取消的 Job
            burstJob?.cancel()
            burstJob = scope.launch {
                for (i in 0 until BURST_COUNT) {
                    if (!started.get()) break
                    safeInjectAll()
                    delay(BURST_INTERVAL_MS)
                }
            }
            safeUpdateNotification()
        }

        override fun onProviderEnabled(provider: String) {
            if (started.get()) safeInjectAll()
        }

        override fun onProviderDisabled(provider: String) { }

        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) { }
    }

    /**
     * 注册拦截器到独立的 HandlerThread，避免阻塞主线程
     */
    private fun registerInterceptor() {
        try {
            // 创建独立线程处理位置回调
            val thread = android.os.HandlerThread("gps-interceptor")
            thread.start()
            interceptorHandler = thread
            val handler = android.os.Handler(thread.looper)
            interceptorHandlerRef = WeakReference(handler)

            realLocationListener?.let { locationManager.removeUpdates(it) }
            realLocationListener = interceptor

            for (provider in MOCK_PROVIDERS) {
                try {
                    locationManager.requestLocationUpdates(
                        provider, 0L, 0f, interceptor, handler.looper
                    )
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    // ⭐ 带锁的注入包装
    private fun safeInjectAll() {
        if (!started.get()) return
        synchronized(injectLock) {
            try {
                doInjectAll()
            } catch (_: Exception) { }
        }
    }

    /**
     * 向所有 mock provider 注入当前位置
     */
    private fun doInjectAll() {
        val now = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()
        val locArr = currentLocationRef.get()

        for (provider in MOCK_PROVIDERS) {
            try {
                val location = Location(provider).apply {
                    latitude = locArr[0]
                    longitude = locArr[1]
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
                // provider 失效，重建
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
                // 重建后重新注入
                try {
                    val location = Location(provider).apply {
                        latitude = locArr[0]
                        longitude = locArr[1]
                        altitude = 0.0
                        accuracy = MOCK_ACCURACY
                        bearing = 0f
                        speed = 0f
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    hideMockFlag(location)
                    locationManager.setTestProviderLocation(provider, location)
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

    // ⭐ 所有更新坐标的入口统一走这里（线程安全）
    fun updateTargetLocation(lat: Double, lon: Double) {
        currentLocationRef.set(doubleArrayOf(lat, lon))
        // 触发立即注入
        if (started.get()) {
            scope.launch { safeInjectAll() }
        }
    }

    private fun buildNotification(): Notification {
        val loc = getCurrentLocation()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("虚拟GPS运行中")
            .setContentText("%.6f, %.6f".format(loc.first, loc.second))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun safeUpdateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) { }
    }

    private fun stopMocking() {
        started.set(false)
        stopMockingInner()
    }

    @Synchronized
    private fun stopMockingInner() {
        isRunning = false
        toggleJob?.cancel()
        bgInjectJob?.cancel()
        burstJob?.cancel()

        try { realLocationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        realLocationListener = null

        // 停止并清理 interceptor HandlerThread
        try {
            interceptorHandler?.quitSafely()
        } catch (_: Exception) { }
        interceptorHandler = null
        interceptorHandlerRef = null

        for (p in MOCK_PROVIDERS) {
            try { locationManager.removeTestProvider(p) } catch (_: Exception) { }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        started.set(false)
        scope.cancel()
        isRunning = false
        try { realLocationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        try {
            interceptorHandler?.quitSafely()
        } catch (_: Exception) { }
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
