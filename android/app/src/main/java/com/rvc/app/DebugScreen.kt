package com.rvc.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * P2.5:调试页(全屏独立页,叠在三 tab 之上)。
 *
 * - 版本号 5 连击从设置页进入;返回(back/左上箭头)即退出并重置计数
 * - 参数面板:profile / key / rms / idx / prot / f0 / brightness
 * - 动作按钮:设备检测 / 3模型全检 / 全链路 / ②拆分 / iSTFT / 模拟 / 内存链路 / 实时 / 测延迟
 * - 延迟显示(prefs 持久化)+ 日志流(StateFlow,cap 200,反向填充)
 */
@Composable
fun DebugScreen(
    onClose: () -> Unit,
    vm: RvcViewModel = viewModel(),
) {
    val profile by vm.debugProfile.collectAsState()
    val key by vm.debugKey.collectAsState()
    val rms by vm.debugRms.collectAsState()
    val idx by vm.debugIdx.collectAsState()
    val prot by vm.debugProt.collectAsState()
    val f0 by vm.debugF0.collectAsState()
    val brightness by vm.debugBrightness.collectAsState()
    val latencyMs by vm.latencyMs.collectAsState()
    val logLines by vm.logLines.collectAsState()

    // f0 选择弹窗
    var showF0Dialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshLatency()
        vm.ensureF0(vm.debugF0.value)
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
            // ---- 参数面板 ----
            item(key = "param-title") { SmallTitle(text = "参数(重启 HAL/跑链路时生效)") }

            item(key = "param-profile") {
                SwitchPreference(
                    checked = profile,
                    onCheckedChange = { vm.setDebugProfile(it) },
                    title = "profile(高性能模式)",
                    summary = "开启后 HTP 用 burst 配置跑全链路",
                )
            }

            item(key = "param-key") {
                ParamRow(label = "key 变调(半音)", value = key, onChange = { vm.setDebugKey(it) })
            }
            item(key = "param-rms") {
                ParamRow(label = "rms_mix_rate", value = rms, onChange = { vm.setDebugRms(it) })
            }
            item(key = "param-idx") {
                ParamRow(label = "index_rate", value = idx, onChange = { vm.setDebugIdx(it) })
            }
            item(key = "param-prot") {
                ParamRow(label = "protect 0-0.5", value = prot, onChange = { vm.setDebugProt(it) })
            }

            item(key = "param-f0") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                    onClick = { showF0Dialog = true },
                ) {
                    Text(
                        text = "F0 提取器: $f0(点击选择)",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            item(key = "param-bright") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Text(
                        text = "亮度 $brightness / 100",
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                    )
                    Slider(
                        value = brightness.toFloat(),
                        onValueChange = { vm.setDebugBrightness(it.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                    )
                }
            }

            // ---- 动作按钮 ----
            item(key = "action-title") { SmallTitle(text = "动作") }

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

    // f0 选择弹窗
    if (showF0Dialog) {
        OverlayDialog(
            show = showF0Dialog,
            title = "选择 F0 提取器",
            onDismissRequest = { showF0Dialog = false },
        ) {
            listOf("fcpe", "rmvpe", "gf_ref").forEach { m ->
                TextButton(
                    text = m + if (m == f0) "(当前)" else "",
                    onClick = {
                        vm.setDebugF0(m)
                        vm.ensureF0(m)
                        showF0Dialog = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 参数输入行:标签 + TextField。 */
@Composable
private fun ParamRow(label: String, value: String, onChange: (String) -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, modifier = Modifier.weight(1f))
            TextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.width(140.dp),
            )
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
