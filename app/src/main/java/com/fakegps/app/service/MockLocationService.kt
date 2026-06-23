package com.fakegps.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*

/**
 * 模拟定位前台服务
 * 支持单点定位 + 多坐标自动巡航
 *
 * 巡航模式：按选定顺序依次模拟，每个坐标停留 5~25 分钟随机
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
        const val EXTRA_LATS = "lats"       // double array
        const val EXTRA_LONS = "lons"       // double array
        const val EXTRA_NAMES = "names"     // string array
        const val EXTRA_INTERVAL_MIN = "interval_min"
        const val EXTRA_INTERVAL_MAX = "interval_max"

        private var currentLat = 0.0
        private var currentLon = 0.0
        private var currentAlt = 0.0

        /** 巡航状态 */
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

        fun isMocking(): Boolean = isRunning

        fun getCurrentLocation(): Pair<Double, Double> = Pair(currentLat, currentLon)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mockJob: Job? = null

    // 巡航数据
    private var routeLats = doubleArrayOf()
    private var routeLons = doubleArrayOf()
    private var routeNames = arrayOf<String>()
    private var intervalMin = 5
    private var intervalMax = 25

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
                val alt = intent.getDoubleExtra(EXTRA_ALT, 0.0)
                isRouteMode = false
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
                    routeTotal = routeLats.size
                    routeIndex = 0
                    startRoute()
                }
            }
            ACTION_STOP -> stopMocking()
        }
        return START_STICKY
    }

    private fun startRoute() {
        isRunning = true
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

                // 通知栏显示当前状态
                val notification = buildNotification()
                startForeground(NOTIFICATION_ID, notification)

                // 持续上报位置（每秒一次）
                val dwellJob = launch {
                    while (isActive) {
                        reportMockLocation(lat, lon, 0.0, 18.0f)
                        delay(1000)
                    }
                }

                // 停留时间（最后一个点停留到手动停止）
                if (i == routeLats.size - 1) {
                    dwellJob.join()
                    break
                }

                // 随机延迟 5~25 分钟
                val delayMs = (intervalMin * 60 + Math.random() * (intervalMax - intervalMin) * 60).toLong() * 1000
                delay(delayMs)
                dwellJob.cancel()
            }
        }
    }

    private fun startMocking(lat: Double, lon: Double, alt: Double) {
        currentLat = lat
        currentLon = lon
        currentAlt = alt
        isRunning = true
        routeTotal = 0
        routeIndex = 0
        routeName = ""

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        mockJob?.cancel()
        mockJob = scope.launch {
            while (isActive) {
                reportMockLocation(lat, lon, alt, 18.0f)
                delay(1000)
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
            "📍 ${routeName}  (%.5f, %.5f)".format(currentLat, currentLon)
        } else {
            "纬度: %.5f  经度: %.5f".format(currentLat, currentLon)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun stopMocking() {
        isRunning = false
        isRouteMode = false
        mockJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reportMockLocation(lat: Double, lon: Double, alt: Double, accuracy: Float) {
        try {
            val location = Location("gps").apply {
                this.latitude = lat
                this.longitude = lon
                this.altitude = alt
                this.accuracy = accuracy
                this.time = System.currentTimeMillis()
                this.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            Location::class.java.getMethod(
                "makeComplete", Location::class.java
            )?.invoke(null, location)
        } catch (_: Exception) {
            // 某些系统可能不支持 makeComplete
        }
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
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
