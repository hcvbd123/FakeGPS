package com.fakegps.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fakegps.app.models.KmlParser
import com.fakegps.app.models.KmlPlacemark
import com.fakegps.app.service.MockLocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class UiState(
    val kmlName: String = "",
    val placemarks: List<KmlPlacemark> = emptyList(),
    val checkedSet: Set<Int> = emptySet(),       // 勾选的序号
    val activeIndex: Int = -1,
    val errorMessage: String = "",
    val floatingEnabled: Boolean = false,
    val intervalMin: Int = 5,                    // 最短停留分钟
    val intervalMax: Int = 25,                   // 最长停留分钟
    val isRouteMode: Boolean = false,            // 是否巡航模式
    val routeProgress: String = ""               // "2/5"
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    fun loadKml(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("无法打开文件")

                val kmlData = KmlParser.parse(inputStream)
                inputStream.close()

                _uiState.value = _uiState.value.copy(
                    kmlName = kmlData.name,
                    placemarks = kmlData.placemarks,
                    checkedSet = emptySet(),
                    activeIndex = -1,
                    errorMessage = if (kmlData.placemarks.isEmpty()) "KML 未找到坐标点" else "",
                    isRouteMode = false,
                    routeProgress = ""
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "解析失败: ${e.message}"
                )
            }
        }
    }

    /** 切换勾选 */
    fun toggleCheck(index: Int) {
        val current = _uiState.value.checkedSet.toMutableSet()
        if (current.contains(index)) current.remove(index) else current.add(index)
        _uiState.value = _uiState.value.copy(checkedSet = current)
    }

    /** 勾选全部 */
    fun checkAll() {
        val all = _uiState.value.placemarks.indices.toSet()
        _uiState.value = _uiState.value.copy(checkedSet = all)
    }

    /** 取消全选 */
    fun uncheckAll() {
        _uiState.value = _uiState.value.copy(checkedSet = emptySet())
    }

    /** 设置间隔 */
    fun setInterval(min: Int, max: Int) {
        _uiState.value = _uiState.value.copy(
            intervalMin = min.coerceIn(1, 60),
            intervalMax = max.coerceIn(min, 60)
        )
    }

    /** 启动巡航 */
    fun startRoute(context: Context) {
        val state = _uiState.value
        val selected = state.checkedSet.sorted()
        if (selected.isEmpty()) return

        // 停止旧模拟
        context.stopService(Intent(context, MockLocationService::class.java))

        val lats = selected.map { state.placemarks[it].latitude }.toDoubleArray()
        val lons = selected.map { state.placemarks[it].longitude }.toDoubleArray()
        val names = selected.map { state.placemarks[it].name }.toTypedArray()

        val intent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
            putExtra(MockLocationService.EXTRA_LATS, lats)
            putExtra(MockLocationService.EXTRA_LONS, lons)
            putExtra(MockLocationService.EXTRA_NAMES, names)
            putExtra(MockLocationService.EXTRA_INTERVAL_MIN, state.intervalMin)
            putExtra(MockLocationService.EXTRA_INTERVAL_MAX, state.intervalMax)
        }
        context.startForegroundService(intent)

        _uiState.value = _uiState.value.copy(
            activeIndex = selected.first(),
            isRouteMode = true,
            routeProgress = "1/${selected.size}"
        )
    }

    /** 模拟单点（点击某行） */
    fun selectPlacemark(context: Context, index: Int, placemark: KmlPlacemark) {
        context.stopService(Intent(context, MockLocationService::class.java))

        val intent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START
            putExtra(MockLocationService.EXTRA_LAT, placemark.latitude)
            putExtra(MockLocationService.EXTRA_LON, placemark.longitude)
            putExtra(MockLocationService.EXTRA_ALT, placemark.altitude)
        }
        context.startForegroundService(intent)

        _uiState.value = _uiState.value.copy(
            activeIndex = index,
            isRouteMode = false,
            routeProgress = ""
        )
    }

    fun toggleFloating() {
        _uiState.value = _uiState.value.copy(
            floatingEnabled = !_uiState.value.floatingEnabled
        )
    }
}
