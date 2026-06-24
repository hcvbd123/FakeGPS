package com.fakegps.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 模拟定位前台服务 — v20: 行为日志 + fused回满 + requestSingleUpdate
 *
 * v19 → v20：
 * 1. FUSED 加回 PUSH_PROVIDERS（之前只推 GPS+NETWORK 导致 fused 空白）
 * 2. 新增 BehaviorLog — 全局行为日志，记录每个 provider 操作的时间戳和结果
 * 3. 注入后调用 requestSingleUpdate 直接唤醒系统 dispatch 链
 * 4. 静态方法 getBehaviorLog() / clearBehaviorLog() 供诊断界面读取
 * 5. PUSH_PROVIDERS = [GPS, NETWORK, FUSED]（三个都推，PASSIVE 仅 setup）
 * 6. 保持 800~1500ms 保活降频、平滑过渡、广播唤醒
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
        private val FUSED_PROVIDER = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            LocationManager.FUSED_PROVIDER
        } else {
            "fused"
        }
        private val PASSIVE_PROVIDER = LocationManager.PASSIVE_PROVIDER

        /** 主动推送的目标（三个：GPS + NETWORK + FUSED 全推） */
        private val PUSH_PROVIDERS = listOf(GPS_PROVIDER, NETWORK_PROVIDER, FUSED_PROVIDER)
        /** 全量 Provider：用于 setup / cleanup */
        private val ALL_PROVIDERS = listOf(
            GPS_PROVIDER, NETWORK_PROVIDER, FUSED_PROVIDER, PASSIVE_PROVIDER
        )

        /** Toggle 序列 */
        private val TOGGLE_SEQUENCE = listOf(
            NETWORK_PROVIDER, GPS_PROVIDER, NETWORK_PROVIDER, FUSED_PROVIDER
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

        // 精度随机
        private fun randomAccuracy(): Float {
            return (12 + (Math.random() * 108).toInt()) / 10f
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

    override fun onCreate() {
        super.onCreate()
        addBehaviorLog("📱 Service onCreate")
        try { createNotificationChannel() } catch (_: Exception) { }
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
     * pushMockLoc — 向 PUSH_PROVIDERS（GPS + NETWORK + FUSED）注入坐标
     * 附带：
     * 1. 广播 PROVIDERS_CHANGED + GPS_FIX_CHANGE
     * 2. requestSingleUpdate 直接唤醒系统 dispatch
     * 3. 精度随机、反射隐藏 mock
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
                            accuracy = randomAccuracy()
                            bearing = (Math.random() * 360).toFloat()
                            speed = (5 + (Math.random() * 25).toInt()) / 10f
                            time = now
                            elapsedRealtimeNanos = elapsedNs
                        }
                        hideMockFlag(location)
                        locationManager.setTestProviderLocation(provider, location)
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
                accuracy = randomAccuracy()
                bearing = (Math.random() * 360).toFloat()
                speed = (5 + (Math.random() * 25).toInt()) / 10f  // 有速度更像真实移动
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            hideMockFlag(location)

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
                accuracy = randomAccuracy()
                bearing = (Math.random() * 360).toFloat()
                speed = (5 + (Math.random() * 25).toInt()) / 10f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            hideMockFlag(location)
            locationManager.setTestProviderLocation(provider, location)
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
