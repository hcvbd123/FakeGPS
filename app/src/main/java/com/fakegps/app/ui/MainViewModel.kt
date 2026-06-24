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
import java.io.File

data class UiState(
    val kmlName: String = "",
    val placemarks: List<KmlPlacemark> = emptyList(),
    val checkedSet: Set<Int> = emptySet(),
    val activeIndex: Int = -1,
    val errorMessage: String = "",
    val floatingEnabled: Boolean = false,
    val intervalMin: Int = 5,
    val intervalMax: Int = 25,
    val isRouteMode: Boolean = false,
    val routeProgress: String = "",
    val isStarted: Boolean = false,
    val currentLat: Double = 0.0,
    val currentLon: Double = 0.0,
    val kmlFiles: List<String> = emptyList(),
    val isScanning: Boolean = false
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private fun getKmDir(context: Context): File {
        val external = context.getExternalFilesDir("km")
        if (external != null) {
            external.mkdirs()
            return external
        }
        val internal = File(context.filesDir, "km")
        internal.mkdirs()
        return internal
    }

    fun scanKmlFolder(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isScanning = true)
            try {
                val kmDir = getKmDir(context)
                if (!kmDir.exists()) {
                    kmDir.mkdirs()
                    _uiState.value = _uiState.value.copy(kmlFiles = emptyList(), isScanning = false)
                    return@launch
                }
                val files = kmDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".kml", true) }
                    ?.map { it.name }
                    ?.sorted()
                    ?: emptyList()
                _uiState.value = _uiState.value.copy(kmlFiles = files, isScanning = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(kmlFiles = emptyList(), isScanning = false)
            }
        }
    }

    fun loadKmlFromFile(context: Context, filename: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(getKmDir(context), filename)
                if (!file.exists()) {
                    _uiState.value = _uiState.value.copy(errorMessage = "文件不存在: $filename")
                    return@launch
                }
                val inputStream = file.inputStream()
                val kmlData = KmlParser.parse(inputStream)
                inputStream.close()

                _uiState.value = _uiState.value.copy(
                    kmlName = kmlData.name,
                    placemarks = kmlData.placemarks,
                    checkedSet = emptySet(),
                    activeIndex = -1,
                    errorMessage = if (kmlData.placemarks.isEmpty()) "KML 未找到坐标点" else "",
                    isRouteMode = false,
                    routeProgress = "",
                    isStarted = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "解析失败: ${e.message}")
            }
        }
    }

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
                    routeProgress = "",
                    isStarted = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "解析失败: ${e.message}")
            }
        }
    }

    fun toggleCheck(index: Int) {
        val current = _uiState.value.checkedSet.toMutableSet()
        if (current.contains(index)) current.remove(index) else current.add(index)
        _uiState.value = _uiState.value.copy(checkedSet = current)
    }

    fun checkAll() {
        val all = _uiState.value.placemarks.indices.toSet()
        _uiState.value = _uiState.value.copy(checkedSet = all)
    }

    fun uncheckAll() {
        _uiState.value = _uiState.value.copy(checkedSet = emptySet())
    }

    fun setInterval(min: Int, max: Int) {
        _uiState.value = _uiState.value.copy(
            intervalMin = min.coerceIn(1, 60),
            intervalMax = max.coerceIn(min, 60)
        )
    }

    fun startSimulation(context: Context) {
        val state = _uiState.value
        val selected = state.checkedSet.sorted()
        if (selected.isEmpty()) {
            _uiState.value = state.copy(errorMessage = "请先勾选至少一个坐标")
            return
        }

        val firstIdx = selected.first()
        val firstPm = state.placemarks[firstIdx]

        context.stopService(Intent(context, MockLocationService::class.java))

        val intent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START
            putExtra(MockLocationService.EXTRA_LAT, firstPm.latitude)
            putExtra(MockLocationService.EXTRA_LON, firstPm.longitude)
        }
        context.startForegroundService(intent)

        _uiState.value = _uiState.value.copy(
            activeIndex = firstIdx,
            isRouteMode = false,
            routeProgress = "",
            isStarted = true,
            currentLat = firstPm.latitude,
            currentLon = firstPm.longitude
        )
    }

    fun stopSimulation(context: Context) {
        context.stopService(Intent(context, MockLocationService::class.java))
        _uiState.value = _uiState.value.copy(
            isStarted = false,
            isRouteMode = false,
            routeProgress = "",
            activeIndex = -1
        )
    }

    fun toggleFloating() {
        _uiState.value = _uiState.value.copy(
            floatingEnabled = !_uiState.value.floatingEnabled
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = "")
    }
}
