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
 * 模拟定位前台服务 — v19: 平滑过渡 + 广播唤醒 + 保活降频
 *
 * v18 → v19 核心变更：
 * 1. ⏱ 注入间隔 150~300ms → 800~1500ms（前台Service保活，降频省电）
 * 2. 📡 注入后发送系统广播（PROVIDERS_CHANGED + GPS_FIX_CHANGE），强制唤醒地图SDK的LocationListener
 * 3. 🚶 坐标变更时微小平滑过渡（±0.0001度步进，每步200ms），地图SDK识别为连续移动自动刷新
 * 4. 💾 应用缓存 lastKnownLocation — 定时注入充当缓存刷新，地图APP打开时读到最新坐标
 * 5. 🎯 核心 Provider：GPS + NETWORK（两套），PASSIVE + FUSED 保持 setup 但不主动推送
 * 6. 精度随机 1.2~12.0m
 * 7. 反射隐藏 mock 标记
 * 8. 防重入锁 + 互斥锁双重保护
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

        // 注入目标：仅 GPS + NETWORK（两套测试Provider，多数地图SDK只监听这两个）
        private val PUSH_PROVIDERS = listOf(GPS_PROVIDER, NETWORK_PROVIDER)
        // 全量 Provider：用于 setup / cleanup
        private val ALL_PROVIDERS = listOf(
            GPS_PROVIDER, NETWORK_PROVIDER,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else "fused",
            LocationManager.PASSIVE_PROVIDER
        )

        // Toggle 序列顺序
        private val TOGGLE_SEQUENCE = listOf(
            NETWORK_PROVIDER, GPS_PROVIDER, NETWORK_PROVIDER,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else "fused"
        )

        // 注入间隔 800~1500ms 随机 — 前台Service保活，降频省电
        private const val INJECT_MIN_MS = 800L
        private const val INJECT_MAX_MS = 1500L

        // 平滑过渡步数（坐标变更时分步推送，地图SDK识别为连续移动）
        private const val SMOOTH_STEPS = 10
        // 每步间隔
        private const val STEP_DELAY_MS = 200L
        // 步进幅度：±0.0001 度（约11米）
        private const val STEP_RANGE = 0.0001

        // 精度随机 1.2~12.0m，小数点后一位
        private fun randomAccuracy(): Float {
            return (12 + (Math.random() * 108).toInt()) / 10f
        }

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
    // 独立 scope
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
        if (started.getAndSet(true)) {
            stopAll()
        }

        currentLocationRef.set(doubleArrayOf(lat, lon))
        isRunning = true

        mockScope?.cancel()
        mockScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scope = mockScope ?: return

        injectJob = scope.launch {
            // 1. 设置 test provider（全量 4 个）
            try {
                setupAllProviders()
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (_: Exception) { }

            if (!started.get()) return@launch

            // 2. 首次注入
            delay(500)
            if (!started.get()) return@launch
            pushMockLoc()
            delay(200)
            if (!started.get()) return@launch
            pushMockLoc()

            // 3. Toggle 序列（唤醒地图SDK的provider监听）
            for (p in TOGGLE_SEQUENCE) {
                if (!started.get()) return@launch
                try { locationManager.setTestProviderEnabled(p, false) } catch (_: Exception) { }
                delay(200)
                if (!started.get()) return@launch
                try { locationManager.setTestProviderEnabled(p, true) } catch (_: Exception) { }
                delay(300)
                if (!started.get()) return@launch
            }

            // 4. 再次注入
            pushMockLoc()
            delay(200)
            if (!started.get()) return@launch
            pushMockLoc()

            // 5. 定时注入循环（800~1500ms 随机 — 缓存lastKnownLocation + 保活）
            while (isActive && started.get()) {
                pushMockLoc()
                delay(INJECT_MIN_MS + (Math.random() * (INJECT_MAX_MS - INJECT_MIN_MS)).toLong())
            }
        }
    }

    /**
     * pushMockLoc — 向 PUSH_PROVIDERS（GPS + NETWORK）注入坐标
     * 附带：
     * 1. 反射隐藏 mock 标记
     * 2. 主动广播 PROVIDERS_CHANGED + GPS_FIX_CHANGE
     * 3. 精度随机
     */
    private fun pushMockLoc() {
        if (!started.get()) return
        if (!injectInProgress.compareAndSet(false, true)) return
        try {
            synchronized(injectLock) {
                val now = System.currentTimeMillis()
                val elapsedNs = SystemClock.elapsedRealtimeNanos()
                val locArr = currentLocationRef.get()

                for (provider in PUSH_PROVIDERS) {
                    try {
                        val location = Location(provider).apply {
                            latitude = locArr[0]
                            longitude = locArr[1]
                            altitude = 0.0
                            accuracy = randomAccuracy()
                            bearing = 0f
                            speed = 0f
                            time = now
                            elapsedRealtimeNanos = elapsedNs
                        }
                        hideMockFlag(location)
                        locationManager.setTestProviderLocation(provider, location)
                        // 主动广播，强制唤醒地图SDK的 LocationListener
                        sendLocationBroadcast()
                    } catch (e: Exception) {
                        // provider 失效，重建
                        rebuildProvider(provider, locArr[0], locArr[1])
                    }
                }
            }
        } catch (_: Exception) { }
        finally {
            injectInProgress.set(false)
        }
    }

    /**
     * 发送系统位置广播，主动触发地图APP的 onLocationChanged 回调
     */
    private fun sendLocationBroadcast() {
        try {
            // 方案1：PROVIDERS_CHANGED — 通知所有APP位置提供者状态变化
            sendBroadcast(Intent("android.location.PROVIDERS_CHANGED"))
            // 方案2：GPS_FIX_CHANGE — 通知GPS定位已更新
            val gpsIntent = Intent("android.location.GPS_FIX_CHANGE")
            gpsIntent.putExtra("enabled", true)
            sendBroadcast(gpsIntent)
        } catch (_: Exception) { }
    }

    /**
     * 重建失效的 test provider
     */
    private fun rebuildProvider(provider: String, lat: Double, lon: Double) {
        try {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
            locationManager.addTestProvider(
                provider,
                false, false, false, false, false, true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(provider, true)
            val location = Location(provider).apply {
                this.latitude = lat
                this.longitude = lon
                altitude = 0.0
                accuracy = randomAccuracy()
                bearing = 0f
                speed = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            hideMockFlag(location)
            locationManager.setTestProviderLocation(provider, location)
        } catch (_: Exception) { }
    }

    /**
     * 设置全量 test provider（GPS + NETWORK + PASSIVE + FUSED）
     */
    private fun setupAllProviders() {
        for (provider in ALL_PROVIDERS) {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
            try {
                locationManager.addTestProvider(
                    provider,
                    false, false, false, false, false, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
            } catch (_: Exception) { }
        }
    }

    /**
     * 更新坐标 — 带平滑过渡
     *
     * 方案2：坐标变更时不做一次性跳变，而是分 SMOOTH_STEPS 步
     * 逐步从当前位置过渡到目标位置，地图SDK识别为连续移动自动刷新
     */
    fun updateTargetLocation(lat: Double, lon: Double) {
        val current = currentLocationRef.get()
        val oldLat = current[0]
        val oldLon = current[1]

        // 立即更新缓存坐标，后续定时注入会延续终点
        currentLocationRef.set(doubleArrayOf(lat, lon))

        // 如果没在运行或距离太近，直接注入一次
        if (!started.get() || (Math.abs(lat - oldLat) < 0.00001 && Math.abs(lon - oldLon) < 0.00001)) {
            mockScope?.launch { pushMockLoc() }
            return
        }

        // 平滑过渡：分步从旧坐标走向新坐标
        val scope = mockScope
        if (scope != null) {
            scope.launch {
                // 加入微小抖动，让每一步看起来像「自然走动」
                for (step in 1..SMOOTH_STEPS) {
                    if (!started.get()) return@launch
                    val t = step.toDouble() / SMOOTH_STEPS
                    // 线性插值 + 微小随机抖动
                    val jitterLat = (Math.random() - 0.5) * STEP_RANGE
                    val jitterLon = (Math.random() - 0.5) * STEP_RANGE
                    val stepLat = oldLat + (lat - oldLat) * t + jitterLat
                    val stepLon = oldLon + (lon - oldLon) * t + jitterLon
                    currentLocationRef.set(doubleArrayOf(stepLat, stepLon))
                    pushMockLoc()
                    delay(STEP_DELAY_MS)
                }
                // 最后推一次精确终点
                if (started.get()) {
                    currentLocationRef.set(doubleArrayOf(lat, lon))
                    pushMockLoc()
                }
            }
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

        for (p in ALL_PROVIDERS) {
            try { locationManager.removeTestProvider(p) } catch (_: Exception) { }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        started.set(false)
        isRunning = false
        injectJob?.cancel()
        mockScope?.cancel()
        for (p in ALL_PROVIDERS) {
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
