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
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 模拟定位前台服务 — v15 卡死修复
 *
 * 核心变更（vs v14）：
 * 1. ❌ 移除 LocationListener 拦截器 — GPS回调频繁导致的爆发注入和锁竞争是卡死主因
 * 2. ❌ 移除 Burst 爆发注入 — 和定时注入功能重复且加重锁竞争
 * 3. 🔧 注入间隔放大到 300~500ms 随机 — 降频后 Binder IPC 不饱和
 * 4. ✅ 防重入锁 — safeInjectAll 用 AtomicBoolean 跳过重叠调用
 * 5. ✅ 启动/停止用独立 CoroutineScope 防泄漏
 *
 * 工作原理（仿 Fake Location）：
 * - 只 mock GPS_PROVIDER（单 Provider 最大兼容）
 * - 定时注入取代拦截器，不再监听任何 provider
 * - setTestProviderLocation 带互斥锁 + 防重入双重保护
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

        // 只 mock GPS_PROVIDER（单 Provider 最大兼容）
        private val MOCK_PROVIDERS = listOf(GPS_PROVIDER)

        // 注入间隔 300~500ms 随机 — 降频后 Binder IPC 不饱和，防卡死
        private const val INJECT_MIN_MS = 300L
        private const val INJECT_MAX_MS = 500L

        private const val MOCK_ACCURACY = 1.2f

        // 反射隐藏 mock 标记（懒加载线程安全）
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

        @Volatile
        var isRunning = false; private set

        private val currentLocationRef = AtomicReference(doubleArrayOf(0.0, 0.0))

        fun isMocking(): Boolean = isRunning
        fun getCurrentLocation(): Pair<Double, Double> {
            val arr = currentLocationRef.get()
            return Pair(arr[0], arr[1])
        }
    }

    // 注入互斥锁
    private val injectLock = Any()
    // 防重入标记
    private val injectInProgress = AtomicBoolean(false)

    // 启动标记
    private val started = AtomicBoolean(false)

    // 独立 scope — stop 时全部取消
    private var mockScope: CoroutineScope? = null
    private var injectJob: Job? = null

    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()
        try { createNotificationChannel() } catch (_: Exception) { }
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (!::locationManager.isInitialized) return START_NOT_STICKY
            when (intent?.action) {
                ACTION_START -> {
                    val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                    val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
                    startMocking(lat, lon)
                }
                ACTION_STOP -> stopMocking()
            }
        } catch (e: Exception) {
            try { stopAll() } catch (_: Exception) { }
        }
        return START_NOT_STICKY
    }

    private fun startMocking(lat: Double, lon: Double) {
        // 已在运行则先停旧
        if (started.getAndSet(true)) {
            stopAll()
        }

        currentLocationRef.set(doubleArrayOf(lat, lon))
        isRunning = true

        // 创建新 scope，旧 scope 自动被 cancel
        mockScope?.cancel()
        mockScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scope = mockScope ?: return

        // 启动前台服务 + 设置 provider + 开始定时注入（全在 IO 协程）
        injectJob = scope.launch {
            // 1. 设置 test provider
            try {
                setupProvider()
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (_: Exception) { }

            if (!started.get()) return@launch

            // 2. 短暂延迟后首次注入
            delay(500)
            if (!started.get()) return@launch
            safeInject()

            // 3. Toggle GPS 一次（激活 test provider）
            try { locationManager.setTestProviderEnabled(GPS_PROVIDER, false) } catch (_: Exception) { }
            delay(200)
            if (!started.get()) return@launch
            try { locationManager.setTestProviderEnabled(GPS_PROVIDER, true) } catch (_: Exception) { }
            delay(300)
            if (!started.get()) return@launch

            // 4. 再次注入确保生效
            safeInject()
            delay(200)
            if (!started.get()) return@launch
            safeInject()

            // 5. 定时注入循环（300~500ms 随机）
            while (isActive && started.get()) {
                safeInject()
                delay(INJECT_MIN_MS + (Math.random() * (INJECT_MAX_MS - INJECT_MIN_MS)).toLong())
            }
        }
    }

    /**
     * 单次注入（带锁 + 防重入）
     * 防重入：如果调用发生时上一轮注入还没完成，跳过本次
     * 这在高频回调场景下防止 Binder IPC 挤爆 system_server
     */
    private fun safeInject() {
        if (!started.get()) return
        if (!injectInProgress.compareAndSet(false, true)) return
        try {
            synchronized(injectLock) {
                doInject()
            }
        } catch (_: Exception) { }
        finally {
            injectInProgress.set(false)
        }
    }

    /**
     * 向 GPS 注入当前坐标
     */
    private fun doInject() {
        val now = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()
        val locArr = currentLocationRef.get()

        // 只 mock GPS_PROVIDER
        try {
            val location = Location(GPS_PROVIDER).apply {
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
            locationManager.setTestProviderLocation(GPS_PROVIDER, location)
        } catch (e: Exception) {
            // provider 失效，重建
            try {
                locationManager.removeTestProvider(GPS_PROVIDER)
            } catch (_: Exception) { }
            try {
                locationManager.addTestProvider(
                    GPS_PROVIDER,
                    false, false, false, false, false, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(GPS_PROVIDER, true)
            } catch (_: Exception) { }
            // 重建后重新注入
            try {
                val location = Location(GPS_PROVIDER).apply {
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
                locationManager.setTestProviderLocation(GPS_PROVIDER, location)
            } catch (_: Exception) { }
        }
    }

    private fun setupProvider() {
        try { locationManager.removeTestProvider(GPS_PROVIDER) } catch (_: Exception) { }
        try {
            locationManager.addTestProvider(
                GPS_PROVIDER,
                false, false, false, false, false, true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(GPS_PROVIDER, true)
        } catch (_: Exception) { }
    }

    fun updateTargetLocation(lat: Double, lon: Double) {
        currentLocationRef.set(doubleArrayOf(lat, lon))
        if (started.get()) {
            mockScope?.launch { safeInject() }
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
        stopAll()
    }

    private fun stopAll() {
        isRunning = false
        injectJob?.cancel()
        mockScope?.cancel()
        mockScope = null

        try { locationManager.removeTestProvider(GPS_PROVIDER) } catch (_: Exception) { }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        started.set(false)
        isRunning = false
        injectJob?.cancel()
        mockScope?.cancel()
        try { locationManager.removeTestProvider(GPS_PROVIDER) } catch (_: Exception) { }
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
