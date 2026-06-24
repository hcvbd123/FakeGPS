package com.fakegps.app.ui

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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

    /** 添加日志 */
    private fun addLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        val current = _uiState.value.log
        val newLog = current + "[$ts] $msg"
        // 只保留最后 200 条
        _uiState.value = _uiState.value.copy(
            log = if (newLog.size > 200) newLog.drop(newLog.size - 200) else newLog
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

        // 列出所有 provider
        val allProviders = lm.getAllProviders() ?: emptyList()
        addLog("系统所有 Provider: ${allProviders.joinToString(", ")}")

        // 列出测试 provider
        // 注意：getTestProvider 相关 API 在非 debuggable 下不可用
        // 我们可以通过检查 provider 是否存在来判断测试 provider
        addLog("正在监听所有位置来源...")

        // 注册监听器
        currentListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val isMock = try { location.isFromMockProvider } catch (_: Exception) { false }
                val mockTag = if (isMock) " ⚠️FROM_MOCK" else ""
                addLog("📍 ${location.provider}: (${"%.6f".format(location.latitude)}, ${"%.6f".format(location.longitude)}) 精度=${location.accuracy}m${mockTag}")

                // 更新实时状态
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

        // 尝试 Fused location provider（GMS）
        try {
            val fusedClass = Class.forName("com.google.android.gms.location.FusedLocationProviderClient")
            addLog("  设备有 GMS FusedLocation，但需要额外 API")
        } catch (_: ClassNotFoundException) {
            addLog("  设备无 GMS FusedLocation")
        }

        // 尝试 HMS FusedLocation
        try {
            val hmsClass = Class.forName("com.huawei.hms.location.FusedLocationProviderClient")
            addLog("  设备有 HMS FusedLocation")
        } catch (_: ClassNotFoundException) {
            addLog("  设备无 HMS FusedLocation")
        }

        // 首次扫描 provider 状态
        updateProviderStates(lm)

        // 每秒刷新 provider 状态 + 读取行为日志
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000)
                try {
                    lm?.let { updateProviderStates(it) }
                    // 从 Service 读取行为日志
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

    /**
     * 从 MockLocationService 同步行为日志到诊断 UI
     */
    private fun syncBehaviorLog() {
        try {
            val serviceLog = MockLocationService.getBehaviorLog()
            if (serviceLog.isEmpty()) return
            val currentLog = _uiState.value.log
            // 只追加新日志（去重：用 Service 日志的最后一条作为边界）
            val lastCurrent = if (currentLog.isNotEmpty()) currentLog.last() else ""
            // 如果最后一条已经存在就不追加
            if (currentLog.contains(serviceLog.last())) return
            // 追加所有不在当前日志中的 Service 日志
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
