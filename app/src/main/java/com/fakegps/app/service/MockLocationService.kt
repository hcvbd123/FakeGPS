package com.fakegps.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import kotlinx.coroutines.*
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 模拟定位前台服务 — v24: 全4 provider推送 + 按provider区分精度
 *
 * v24 关键修改：
 * 1. PUSH_PROVIDERS = [GPS, NETWORK, FUSED, PASSIVE]（4个全推）
 * 2. 精度策略：GPS=3~10m, NETWORK=180~350m, FUSED=8~25m, PASSIVE=6~20m
 * 3. 垂直精度也按provider区分
 * 4. Toggle序列覆盖4个provider
 */
class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "fake_gps_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.fakegps.START_MOCK"
        const val ACTION_STOP = "com.fakegps.STOP_MOCK"
        const val ACTION_FOREGROUND = "com.fakegps.FOREGROUND"
        const val ACTION_BACKGROUND = "com.fakegps.BACKGROUND"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"

        private val GPS_PROVIDER = LocationManager.GPS_PROVIDER
        private val NETWORK_PROVIDER = LocationManager.NETWORK_PROVIDER
        private val FUSED_PROVIDER = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            LocationManager.FUSED_PROVIDER
        } else {
            "fused"
        }
        private val PASSIVE_PROVIDER = LocationManager.PASSIVE_PROVIDER

        /** 主动推送的目标（GPS + NETWORK + FUSED + PASSIVE — 非 root 必须4个全推） */
        private val PUSH_PROVIDERS = listOf(GPS_PROVIDER, NETWORK_PROVIDER, FUSED_PROVIDER, PASSIVE_PROVIDER)
        /** 全量 Provider：用于 setup / cleanup */
        private val ALL_PROVIDERS = PUSH_PROVIDERS

        /** Toggle 序列 — 覆盖所有 4 个 provider 刷新系统状态 */
        private val TOGGLE_SEQUENCE = listOf(
            NETWORK_PROVIDER, GPS_PROVIDER, FUSED_PROVIDER, PASSIVE_PROVIDER
        )

        // 注入间隔 800~1500ms — 前台 Service 保活
        private const val INJECT_MIN_MS = 800L
        private const val INJECT_MAX_MS = 1500L

        // 平滑过渡参数
        private const val SMOOTH_STEPS = 10
        private const val STEP_DELAY_MS = 200L
        private const val STEP_RANGE = 0.0001

        // ===== 行为日志（全局静态，诊断界面可读） =====
        private val behaviorLog = ConcurrentLinkedDeque<String>()
        private const val MAX_LOG_LINES = 500

        /** 添加行为日志（供 Service 内部调用） */
        @JvmStatic
        fun addBehaviorLog(msg: String) {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            behaviorLog.addLast("[$ts] $msg")
            if (behaviorLog.size > MAX_LOG_LINES) {
                behaviorLog.pollFirst()
            }
        }

        /** 获取行为日志快照（供诊断界面读取） */
        @JvmStatic
        fun getBehaviorLog(): List<String> {
            return ArrayList(behaviorLog)
        }

        /** 清空行为日志 */
        @JvmStatic
        fun clearBehaviorLog() {
            behaviorLog.clear()
        }
        // ===========================================

        // 精度随机 — 按 provider 类型模拟真实精度特征
        private fun accuracyForProvider(provider: String): Float {
            return 1.0f // 全部 1 米精度
        }

        // 反射隐藏 mock 标记
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

        // 反射设置垂直精度（API 26+）
        private val vertAccMethod = AtomicReference<Method?>(null)
        private fun setVerticalAccuracy(loc: Location, meters: Float) {
            if (Build.VERSION.SDK_INT < 26) return
            try {
                var m = vertAccMethod.get()
                if (m == null) {
                    m = Location::class.java.getDeclaredMethod(
                        "setVerticalAccuracyMeters", Float::class.javaPrimitiveType
                    )
                    m.isAccessible = true
                    vertAccMethod.set(m)
                }
                m!!.invoke(loc, meters)
            } catch (_: Exception) { }
        }

        // 反射设置速度精度（API 26+）
        private val speedAccMethod = AtomicReference<Method?>(null)
        private fun setSpeedAccuracy(loc: Location, mps: Float) {
            if (Build.VERSION.SDK_INT < 26) return
            try {
                var m = speedAccMethod.get()
                if (m == null) {
                    m = Location::class.java.getDeclaredMethod(
                        "setSpeedAccuracyMetersPerSecond", Float::class.javaPrimitiveType
                    )
                    m.isAccessible = true
                    speedAccMethod.set(m)
                }
                m!!.invoke(loc, mps)
            } catch (_: Exception) { }
        }

        // 反射设置方向精度（API 26+）
        private val bearAccMethod = AtomicReference<Method?>(null)
        private fun setBearingAccuracy(loc: Location, deg: Float) {
            if (Build.VERSION.SDK_INT < 26) return
            try {
                var m = bearAccMethod.get()
                if (m == null) {
                    m = Location::class.java.getDeclaredMethod(
                        "setBearingAccuracyDegrees", Float::class.javaPrimitiveType
                    )
                    m.isAccessible = true
                    bearAccMethod.set(m)
                }
                m!!.invoke(loc, deg)
            } catch (_: Exception) { }
        }

        // 对一个 Location 应用所有精度字段（全部 1 米）
        private fun applyFullAccuracyFields(loc: Location, provider: String) {
            loc.accuracy = 1.0f
            setVerticalAccuracy(loc, 1.0f)
            setSpeedAccuracy(loc, 0.1f)
            setBearingAccuracy(loc, 1.0f)
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

    private val injectLock = Any()
    private val injectInProgress = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var mockScope: CoroutineScope? = null
    private var injectJob: Job? = null

    private lateinit var locationManager: LocationManager
    private var powerManager: PowerManager? = null
    // 前台标记 — 后台时使用爆发注入模式
    private val isForeground = AtomicBoolean(true)

    override fun onCreate() {
        super.onCreate()
        addBehaviorLog("📱 Service onCreate")
        try { createNotificationChannel() } catch (_: Exception) { }
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            addBehaviorLog("✅ 获取 LocationManager 成功")
        } catch (e: Exception) {
            addBehaviorLog("❌ 获取 LocationManager 失败: ${e.message}")
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
                    addBehaviorLog("▶️ 收到 START 指令: %.6f, %.6f".format(lat, lon))
                    startMocking(lat, lon)
                }
                ACTION_STOP -> {
                    addBehaviorLog("⏹ 收到 STOP 指令")
                    stopMocking()
                }
            }
        } catch (e: Exception) {
            addBehaviorLog("❌ onStartCommand 异常: ${e.message}")
            try { stopAll() } catch (_: Exception) { }
        }
        return START_NOT_STICKY
    }

    private fun startMocking(lat: Double, lon: Double) {
        if (started.getAndSet(true)) {
            addBehaviorLog("⏹ 正在运行，先停止旧服务")
            stopAll()
        }

        currentLocationRef.set(doubleArrayOf(lat, lon))
        isRunning = true
        addBehaviorLog("🚀 启动模拟定位: %.6f, %.6f".format(lat, lon))

        mockScope?.cancel()
        mockScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scope = mockScope ?: return

        injectJob = scope.launch {
            // 1. Setup
            try {
                addBehaviorLog("🔧 Setup 全量 Provider...")
                setupAllProviders()
                startForeground(NOTIFICATION_ID, buildNotification())
                addBehaviorLog("✅ 前台服务 + Provider 设置完成")
            } catch (e: Exception) {
                addBehaviorLog("❌ Setup 异常: ${e.message}")
            }

            if (!started.get()) return@launch

            // 2. 首次注入
            delay(500)
            if (!started.get()) return@launch
            pushMockLoc()
            delay(200)
            if (!started.get()) return@launch
            pushMockLoc()

            // 3. Toggle 序列
            addBehaviorLog("🔄 Toggle 序列开始: ${TOGGLE_SEQUENCE.joinToString("→")}")
            for (p in TOGGLE_SEQUENCE) {
                if (!started.get()) return@launch
                try {
                    locationManager.setTestProviderEnabled(p, false)
                    addBehaviorLog("  ⬇️ 禁用 $p")
                } catch (e: Exception) {
                    addBehaviorLog("  ⚠️ 禁用 $p 失败: ${e.message}")
                }
                delay(200)
                if (!started.get()) return@launch
                try {
                    locationManager.setTestProviderEnabled(p, true)
                    addBehaviorLog("  ⬆️ 启用 $p")
                } catch (e: Exception) {
                    addBehaviorLog("  ⚠️ 启用 $p 失败: ${e.message}")
                }
                delay(300)
                if (!started.get()) return@launch
            }
            addBehaviorLog("✅ Toggle 序列完成")

            // 4. 再推一次
            pushMockLoc()
            delay(200)
            if (!started.get()) return@launch
            pushMockLoc()

            // 5. 定时注入
            addBehaviorLog("🔁 定时注入开始 (${INJECT_MIN_MS}~${INJECT_MAX_MS}ms)")
            while (isActive && started.get()) {
                pushMockLoc()
                delay(INJECT_MIN_MS + (Math.random() * (INJECT_MAX_MS - INJECT_MIN_MS)).toLong())
            }
            addBehaviorLog("⏹ 定时注入结束")
        }
    }

    /**
     * pushMockLoc — 向 PUSH_PROVIDERS（GPS + NETWORK + FUSED + PASSIVE）注入坐标
     * 附带：
     * 1. 广播 PROVIDERS_CHANGED + GPS_FIX_CHANGE
     * 2. requestSingleUpdate 直接唤醒系统 dispatch
     * 3. 按 provider 区分精度策略
     * 4. 反射隐藏 mock
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
                            altitude = 50.0 + Math.random() * 450.0
                            accuracy = accuracyForProvider(provider)
                            bearing = (Math.random() * 360).toFloat()
                            speed = (5 + (Math.random() * 25).toInt()) / 10f
                            time = now
                            elapsedRealtimeNanos = elapsedNs
                        }
                        hideMockFlag(location)
                        applyFullAccuracyFields(location, provider)
                        locationManager.setTestProviderLocation(provider, location)
                        injectSatelliteData(provider)
                        // 触发系统位置广播
                        sendLocationBroadcast(provider)
                        // 直接请求单次更新，强制 dispatch 给所有监听器
                        triggerSingleUpdate(provider)
                    } catch (e: Exception) {
                        addBehaviorLog("⚠️ setTestProviderLocation($provider) 失败: ${e.message}")
                        // 重建
                        rebuildProvider(provider, locArr[0], locArr[1])
                    }
                }
            }
        } catch (e: Exception) {
            addBehaviorLog("❌ pushMockLoc 异常: ${e.message}")
        } finally {
            injectInProgress.set(false)
        }
    }

    /**
     * 发送系统位置广播
     */
    private fun sendLocationBroadcast(provider: String) {
        try {
            sendBroadcast(Intent("android.location.PROVIDERS_CHANGED"))
            if (provider == LocationManager.GPS_PROVIDER) {
                val gpsIntent = Intent("android.location.GPS_FIX_CHANGE")
                gpsIntent.putExtra("enabled", true)
                sendBroadcast(gpsIntent)
            }
        } catch (_: Exception) { }
    }

    /**
     * 调用 requestSingleUpdate 直接触发系统 dispatch
     * 这比广播更可靠——它会直接走系统 Binder 路径 dispatch 给已注册的 LocationListener
     */
    private fun triggerSingleUpdate(provider: String) {
        try {
            val locArr = currentLocationRef.get()
            val location = Location(provider).apply {
                latitude = locArr[0]
                longitude = locArr[1]
                altitude = 50.0 + Math.random() * 450.0
                accuracy = accuracyForProvider(provider)
                bearing = (Math.random() * 360).toFloat()
                speed = (5 + (Math.random() * 25).toInt()) / 10f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            hideMockFlag(location)
            applyFullAccuracyFields(location, provider)

            // requestSingleUpdate 参数：provider, criteria, pendingIntent 或 listener
            // 这里我们用一个临时 listener 来触发 dispatch
            val tmpListener = object : android.location.LocationListener {
                override fun onLocationChanged(loc: Location) { }
                override fun onProviderEnabled(p: String) { }
                override fun onProviderDisabled(p: String) { }
            }
            locationManager.requestSingleUpdate(provider, tmpListener, Looper.getMainLooper())
        } catch (_: Exception) { }
    }

    /**
     * 注入 GPS 卫星数据 — 模拟真实 GPS 信号，非 root 可用
     * setTestProviderStatus 是 Android 官方测试 API，在设置了 mock location app 后可直接调用
     * 注入：卫星数量、PRN编号、信噪比，模拟 GPS 信号锁定状态
     */
    private fun injectSatelliteData(provider: String) {
        try {
            val extras = Bundle()
            // 模拟 8~16 颗可见卫星
            val satCount = 8 + (Math.random() * 8).toInt()
            extras.putInt("satellites", satCount)
            extras.putInt("maxSatellites", 24)
            // 用于定位的卫星数（约60%~80%）
            val usedInFix = (satCount * (0.6 + Math.random() * 0.2)).toInt()
            extras.putInt("usedInFix", usedInFix)
            
            // 模拟卫星 PRN 编号（GPS卫星编号范围 1~32）
            val prns = IntArray(satCount) { 1 + (Math.random() * 31).toInt() }
            extras.putIntArray("satPrns", prns)
            
            // 模拟卫星信噪比 SNR（30~50 dBHz 为正常锁定范围）
            val snrs = FloatArray(satCount) { 30f + (Math.random() * 20).toFloat() }
            extras.putFloatArray("satSnrs", snrs)
            
            // 模拟卫星方向角和仰角
            val azimuths = FloatArray(satCount) { (Math.random() * 360).toFloat() }
            extras.putFloatArray("satAzimuths", azimuths)
            val elevations = FloatArray(satCount) { 5f + (Math.random() * 85).toFloat() }
            extras.putFloatArray("satElevations", elevations)
            
            // 状态: 2 = AVAILABLE
            locationManager.setTestProviderStatus(provider, 2, extras, System.currentTimeMillis())
        } catch (_: Exception) { }
    }


    /**
     * 重建失效的 test provider
     */
    private fun rebuildProvider(provider: String, lat: Double, lon: Double) {
        addBehaviorLog("🔄 重建 Provider: $provider")
        try {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
            locationManager.addTestProvider(
                provider,
                false, false, false, false, false, true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(provider, true)
            addBehaviorLog("  ✅ addTestProvider($provider) 成功")

            val location = Location(provider).apply {
                this.latitude = lat
                this.longitude = lon
                altitude = 50.0 + Math.random() * 450.0
                accuracy = accuracyForProvider(provider)
                bearing = (Math.random() * 360).toFloat()
                speed = (5 + (Math.random() * 25).toInt()) / 10f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            hideMockFlag(location)
            applyFullAccuracyFields(location, provider)
            locationManager.setTestProviderLocation(provider, location)
            injectSatelliteData(provider)
            sendLocationBroadcast(provider)
            triggerSingleUpdate(provider)
        } catch (e: Exception) {
            addBehaviorLog("  ❌ 重建 $provider 失败: ${e.message}")
        }
    }

    /**
     * 设置全量 test provider（GPS + NETWORK + FUSED + PASSIVE）
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
                addBehaviorLog("  ✅ $provider 设置成功")
            } catch (e: Exception) {
                addBehaviorLog("  ⚠️ $provider 设置失败: ${e.message}")
            }
        }
    }

    /**
     * 更新坐标 — 带平滑过渡
     */
    fun updateTargetLocation(lat: Double, lon: Double) {
        val current = currentLocationRef.get()
        val oldLat = current[0]
        val oldLon = current[1]
        addBehaviorLog("🎯 更新坐标: %.6f,%.6f (旧: %.6f,%.6f)".format(lat, lon, oldLat, oldLon))

        currentLocationRef.set(doubleArrayOf(lat, lon))

        if (!started.get() || (Math.abs(lat - oldLat) < 0.00001 && Math.abs(lon - oldLon) < 0.00001)) {
            mockScope?.launch { pushMockLoc() }
            addBehaviorLog("  → 距离太近，直接注入")
            return
        }

        val scope = mockScope
        if (scope != null) {
            scope.launch {
                addBehaviorLog("  → 平滑过渡开始: ${SMOOTH_STEPS}步, 每步${STEP_DELAY_MS}ms")
                for (step in 1..SMOOTH_STEPS) {
                    if (!started.get()) return@launch
                    val t = step.toDouble() / SMOOTH_STEPS
                    val jitterLat = (Math.random() - 0.5) * STEP_RANGE
                    val jitterLon = (Math.random() - 0.5) * STEP_RANGE
                    val stepLat = oldLat + (lat - oldLat) * t + jitterLat
                    val stepLon = oldLon + (lon - oldLon) * t + jitterLon
                    currentLocationRef.set(doubleArrayOf(stepLat, stepLon))
                    pushMockLoc()
                    delay(STEP_DELAY_MS)
                }
                if (started.get()) {
                    addBehaviorLog("  → 平滑过渡完成，推终点")
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
        addBehaviorLog("⏹ 停止模拟定位")
        started.set(false)
        stopAll()
    }

    private fun stopAll() {
        addBehaviorLog("⏹ stopAll: 清理 Provider + 停止前台服务")
        isRunning = false
        injectJob?.cancel()
        mockScope?.cancel()
        mockScope = null

        for (p in ALL_PROVIDERS) {
            try {
                locationManager.removeTestProvider(p)
                addBehaviorLog("  🗑 removeTestProvider($p)")
            } catch (_: Exception) { }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        addBehaviorLog("📱 Service onDestroy")
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
