package com.fakegps.app.ui

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fakegps.app.service.MockLocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ProviderState(
    val name: String,
    val enabled: Boolean,
    val location: String = "无",
    val time: String = ""
)

data class DiagUiState(
    val log: List<String> = emptyList(),
    val providers: List<ProviderState> = emptyList(),
    val isMonitoring: Boolean = false,
    val lastRealLocation: String = "无",
    val testProviders: List<String> = emptyList()
)

class DiagnosticViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DiagUiState())
    val uiState: StateFlow<DiagUiState> = _uiState

    private var monitorJob: Job? = null
    private var locationManager: LocationManager? = null
    private var currentListener: LocationListener? = null

    private fun addLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        val current = _uiState.value.log
        val newLog = current + "[$ts] $msg"
        _uiState.value = _uiState.value.copy(
            log = if (newLog.size > 500) newLog.drop(newLog.size - 500) else newLog
        )
    }

    fun startMonitoring(context: Context) {
        if (_uiState.value.isMonitoring) return

        try {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        } catch (e: Exception) {
            addLog("❌ 获取 LocationManager 失败: ${e.message}")
            return
        }
        val lm = locationManager ?: return

        _uiState.value = _uiState.value.copy(isMonitoring = true, log = emptyList())
        addLog("═══ 诊断开始 ═══")

        val allProviders = lm.getAllProviders() ?: emptyList()
        addLog("系统所有 Provider: ${allProviders.joinToString(", ")}")

        addLog("正在监听所有位置来源...")

        currentListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val isMock = try { location.isFromMockProvider } catch (_: Exception) { false }
                val mockTag = if (isMock) " ⚠️FROM_MOCK" else ""

                // ===== 完整 Location 字段分析 =====
                val hasAlt = location.hasAltitude()
                val altitude = if (hasAlt) "%.1f".format(location.altitude) else "无"
                val hasSpeed = location.hasSpeed()
                val speed = if (hasSpeed) "%.2f".format(location.speed) else "无"
                val hasBearing = location.hasBearing()
                val bearing = if (hasBearing) "%.1f".format(location.bearing) else "无"
                val hasAcc = location.hasAccuracy()
                val accuracyH = if (hasAcc) "%.1f".format(location.accuracy) else "无"

                val hasVertAcc = Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()
                val verticalAccuracy = if (hasVertAcc) "%.1f".format(location.verticalAccuracyMeters) else "无(API<26/不可用)"

                val hasSpeedAcc = Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()
                val speedAccuracy = if (hasSpeedAcc) "%.2f".format(location.speedAccuracyMetersPerSecond) else "无(API<26/不可用)"

                val hasBearAcc = Build.VERSION.SDK_INT >= 26 && location.hasBearingAccuracy()
                val bearingAccuracy = if (hasBearAcc) "%.1f".format(location.bearingAccuracyDegrees) else "无(API<26/不可用)"

                val elapsed = location.elapsedRealtimeNanos / 1000000L
                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(location.time))

                val extrasRaw = location.extras
                val extrasSummary = if (extrasRaw != null) {
                    val keys = extrasRaw.keySet()
                    val sb = StringBuilder()
                    sb.append("Bundle有${keys.size}项: ")
                    keys.forEach { key ->
                        val value = try { extrasRaw.get(key)?.toString()?.take(50) } catch (_: Exception) { "??" }
                        sb.append("$key=$value, ")
                    }
                    sb.toString().take(300)
                } else {
                    "Bundle: null"
                }

                // ===== 汇总打印 =====
                addLog("📍 ${location.provider}: (${"%.6f".format(location.latitude)}, ${"%.6f".format(location.longitude)})${mockTag}")
                addLog("  ─── ${location.provider} 完整字段对比 ───")
                addLog("  纬度/经度: ${"%.7f".format(location.latitude)}, ${"%.7f".format(location.longitude)}")
                addLog("  海拔:         $altitude m    (hasAltitude=${hasAlt})")
                addLog("  水平精度:     $accuracyH m  (hasAccuracy=${hasAcc})")
                addLog("  垂直精度:     $verticalAccuracy m   (hasVerticalAccuracy=${hasVertAcc})")
                addLog("  速度:         $speed m/s    (hasSpeed=${hasSpeed})")
                addLog("  速度精度:     $speedAccuracy m/s    (hasSpeedAccuracy=${hasSpeedAcc})")
                addLog("  方向:         $bearing deg  (hasBearing=${hasBearing})")
                addLog("  方向精度:     $bearingAccuracy deg (hasBearingAccuracy=${hasBearAcc})")
                addLog("  时间戳:       ${timeStr}")
                addLog("  启动后时间:   ${elapsed}ms (elapsedRealtimeNanos)")
                addLog("  模拟标记:     ${isMock} (isFromMockProvider)")
                addLog("  $extrasSummary")
                addLog("  toString(): ${location.toString().take(400)}")

                updateProviderStates(lm)
            }

            override fun onProviderEnabled(provider: String) {
                addLog("🟢 Provider 启用: $provider")
                updateProviderStates(lm)
            }

            override fun onProviderDisabled(provider: String) {
                addLog("🔴 Provider 禁用: $provider")
                updateProviderStates(lm)
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                val statusStr = when (status) {
                    0 -> "OUT_OF_SERVICE"
                    1 -> "TEMPORARILY_UNAVAILABLE"
                    2 -> "AVAILABLE"
                    else -> status.toString()
                }
                addLog("📡 $provider 状态: $statusStr")
                updateProviderStates(lm)
            }
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            "fused"
        )

        var successCount = 0
        for (provider in providers) {
            try {
                lm.requestLocationUpdates(provider, 1000L, 0f, currentListener!!, Looper.getMainLooper())
                successCount++
                addLog("  ✅ 成功监听 $provider")
            } catch (e: Exception) {
                addLog("  ⚠️ $provider 监听失败: ${e.message}")
            }
        }

        if (successCount == 0) {
            addLog("❌ 所有 provider 都无法监听 (UID ${android.os.Process.myUid()})")
            addLog("   提示：确认已授予位置权限")
        } else {
            addLog("✅ 成功监听 $successCount 个 provider")
        }

        try {
            val hmsClass = Class.forName("com.huawei.hms.location.FusedLocationProviderClient")
            addLog("  设备有 HMS FusedLocation")
        } catch (_: ClassNotFoundException) {
            addLog("  设备无 HMS FusedLocation")
        }

        updateProviderStates(lm)

        monitorJob?.cancel()
        monitorJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000)
                try {
                    lm?.let { updateProviderStates(it) }
                    syncBehaviorLog()
                } catch (_: Exception) { }
            }
        }
    }

    private fun updateProviderStates(lm: LocationManager) {
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
                "fused"
            )

            val states = providers.map { name ->
                val enabled = try { lm.isProviderEnabled(name) } catch (_: Exception) { false }
                val loc = try { lm.getLastKnownLocation(name) } catch (_: Exception) { null }
                val locStr = if (loc != null) {
                    "%.6f,%.6f 精度=${loc.accuracy}m 时间=%tT".format(loc.latitude, loc.longitude, loc.time)
                } else {
                    "无"
                }
                val timeStr = if (loc != null) "%tT".format(loc.time) else ""
                ProviderState(name, enabled, locStr, timeStr)
            }

            _uiState.value = _uiState.value.copy(providers = states)
        } catch (_: Exception) { }
    }

    private fun syncBehaviorLog() {
        try {
            val serviceLog = MockLocationService.getBehaviorLog()
            if (serviceLog.isEmpty()) return
            val currentLog = _uiState.value.log
            if (currentLog.contains(serviceLog.last())) return
            val toAdd = serviceLog.filter { !currentLog.contains(it) }
            if (toAdd.isEmpty()) return
            val newLog = currentLog + toAdd
            _uiState.value = _uiState.value.copy(
                log = if (newLog.size > 500) newLog.drop(newLog.size - 500) else newLog
            )
        } catch (_: Exception) { }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        try {
            currentListener?.let { locationManager?.removeUpdates(it) }
        } catch (_: Exception) { }
        currentListener = null
        locationManager = null
        _uiState.value = _uiState.value.copy(isMonitoring = false)
        addLog("═══ 诊断结束 ═══")
    }

    override fun onCleared() {
        stopMonitoring()
        super.onCleared()
    }
}
