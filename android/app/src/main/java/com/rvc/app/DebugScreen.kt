package com.rvc.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar

/**
 * P2.7:调试页(全屏独立页,叠在三 tab 之上)。
 *
 * 只放测试相关:动作按钮 + 延迟显示 + 日志流。
 * 参数面板已移回首页「变声」(测试动作仍读取首页设置的参数,状态共用)。
 * 入口:设置页版本号 5 连击;返回(back/左上箭头)即退出并重置计数。
 */
@Composable
fun DebugScreen(
    onClose: () -> Unit,
    vm: RvcViewModel = viewModel(),
) {
    val latencyMs by vm.latencyMs.collectAsState()
    val logLines by vm.logLines.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshLatency()
        vm.debugLog("调试页已打开")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "调试",
                navigationIcon = {
                    TextButton(
                        text = "‹ 返回",
                        onClick = onClose,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            // ---- 参数提示 ----
            item(key = "param-hint") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Text(
                        text = "参数在首页「变声」调整(测试动作读取当前参数)",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // ---- 动作按钮 ----
            item(key = "action-title") { SmallTitle(text = "测试") }

            item(key = "action-row1") {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ActionButton("设备检测", { vm.runDeviceCheckDebug() }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ActionButton("3模型全检", { vm.runAllModels() }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ActionButton("全链路", { vm.runFullChain() }, Modifier.weight(1f))
                }
            }
            item(key = "action-row2") {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ActionButton("②拆分", { vm.runRoute2() }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ActionButton("iSTFT", { vm.runRoute2Istft() }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ActionButton("内存链路", { vm.runLiveIo() }, Modifier.weight(1f))
                }
            }
            item(key = "action-row3") {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ActionButton("模拟", { vm.runSim() }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ActionButton("实时", { vm.runLive() }, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ActionButton("测延迟", { vm.runLatencyTest() }, Modifier.weight(1f))
                }
            }

            // ---- 延迟显示 ----
            item(key = "latency-title") { SmallTitle(text = "延迟") }
            item(key = "latency") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Text(
                        text = latencyMs?.let { "实测延迟: $it ms(块时长预算 370ms,实时需 ≤370)" }
                            ?: "实测延迟: 未测(点「测延迟」)",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // ---- 日志流(反向填充:最新在最上) ----
            item(key = "log-title") { SmallTitle(text = "日志(最多 200 行)") }
            item(key = "log-clear") {
                TextButton(
                    text = "清空日志",
                    onClick = { vm.clearLog() },
                )
            }
            items(logLines.size, key = { logLines.size - 1 - it }) { i ->
                val line = logLines[logLines.size - 1 - i]
                Text(
                    text = line,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 动作按钮(等宽,填满一行 1/3)。 */
@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.padding(bottom = 8.dp),
    ) {
        Text(label)
    }
}
