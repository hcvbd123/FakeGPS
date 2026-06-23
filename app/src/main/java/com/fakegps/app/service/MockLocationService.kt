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
 * 模拟定位前台服务 — 简化版，聚焦核心功能
 */
class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "fake_gps_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.fakegps.START_MOCK"
        const val ACTION_STOP = "com.fakegps.STOP_MOCK"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"

        private val MOCK_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        private const val INJECT_INTERVAL_MS = 50L
        private const val MOCK_ACCURACY = 0.5f

        // 反射缓存
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
    private var mockJob: Job? = null
    private lateinit var locationManager: LocationManager
    private var locationListener: LocationListener? = null

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
        registerListeners()
        try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) { }

        // 50ms 注入循环
        mockJob?.cancel()
        mockJob = scope.launch {
            while (isActive) {
                injectToAllProviders(currentLat, currentLon)
                delay(INJECT_INTERVAL_MS)
            }
        }
    }

    private fun injectToAllProviders(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()

        for (provider in MOCK_PROVIDERS) {
            try {
                val location = Location(provider).apply {
                    this.latitude = lat
                    this.longitude = lon
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
                try { locationManager.removeTestProvider(provider); } catch (_: Exception) { }
                try {
                    val isGPS = provider == LocationManager.GPS_PROVIDER
                    locationManager.addTestProvider(
                        provider,
                        !isGPS, isGPS, false, false,
                        true, true, true,
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
                val isGPS = provider == LocationManager.GPS_PROVIDER
                locationManager.addTestProvider(
                    provider,
                    !isGPS, isGPS, false, false,
                    true, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
            } catch (_: Exception) { }
        }
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

    private fun stopMocking() {
        isRunning = false
        mockJob?.cancel()
        try { locationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
        locationListener = null
        for (p in MOCK_PROVIDERS) { try { locationManager.removeTestProvider(p) } catch (_: Exception) { } }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        isRunning = false
        try { locationListener?.let { locationManager.removeUpdates(it) } } catch (_: Exception) { }
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
