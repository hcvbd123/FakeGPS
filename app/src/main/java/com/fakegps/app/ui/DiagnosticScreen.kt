package com.fakegps.app.ui

import android.location.LocationManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
fun DiagnosticScreen(
    onClose: () -> Unit,
    diagViewModel: DiagnosticViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by diagViewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 自动滚动到最新日志
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) {
            listState.animateScrollToItem(state.log.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题栏
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("诊断模式", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                // 开始/停止
                if (state.isMonitoring) {
                    FilledTonalButton(
                        onClick = { diagViewModel.stopMonitoring() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("停止监听")
                    }
                } else {
                    Button(onClick = { diagViewModel.startMonitoring(context) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("开始监听")
                    }
                }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭诊断")
                }
            }
        }

        if (state.isMonitoring) {
            // Provider 状态
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("📡 Provider 状态", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    for (p in state.providers) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val color = if (p.enabled)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                            Icon(
                                if (p.enabled) Icons.Default.CheckCircle
                                else Icons.Default.Cancel,
                                contentDescription = null,
                                tint = color, modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(p.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(80.dp))
                            Text(
                                if (p.enabled) "启用" else "禁用",
                                fontSize = 12.sp, color = color
                            )
                            if (p.location != "无") {
                                Spacer(Modifier.width(4.dp))
                                Text(p.location.take(40), fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        } else {
            // 提示信息
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📖 使用说明", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("1. 点「开始监听」", fontSize = 14.sp)
                    Text("2. 然后在平板上运行 Fake Location", fontSize = 14.sp)
                    Text("3. 在 Fake Location 中设坐标、点模拟", fontSize = 14.sp)
                    Text("4. 查看下方实时日志，留意 provider 变化", fontSize = 14.sp)
                    Text("5. 尝试打开/关闭数据流量观察差异", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("提示：记录「📍」开头的就是位置更新事件",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // 提供者状态表格
        if (state.providers.isNotEmpty() && state.isMonitoring) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("📍 最新位置", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    for (p in state.providers) {
                        if (p.location != "无" && p.enabled) {
                            Text(
                                "${p.name}: ${p.location.take(60)}",
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                color = if (p.name == LocationManager.GPS_PROVIDER)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }

        // 日志列表
        Text(
            "${state.log.size} 条事件",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(state.log) { entry ->
                val color = when {
                    entry.contains("📍") && !entry.contains("FROM_MOCK") ->
                        MaterialTheme.colorScheme.primary
                    entry.contains("FROM_MOCK") ->
                        MaterialTheme.colorScheme.error
                    entry.contains("启用") ->
                        MaterialTheme.colorScheme.tertiary
                    entry.contains("禁用") ->
                        MaterialTheme.colorScheme.error
                    else ->
                        MaterialTheme.colorScheme.onSurface
                }

                val bgColor = when {
                    entry.contains("═══") ->
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    entry.contains("📍") ->
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                    else ->
                        MaterialTheme.colorScheme.surface
                }

                Surface(
                    color = bgColor,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        entry,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}
