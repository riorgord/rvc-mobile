package com.rvc.app

import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * P2.7:变声首页(默认 tab)。
 *
 * - 大开关:开启/关闭变声(HAL 桥接)——日常唯一入口
 * - 当前角色 + 快速切换卡片(点击切换;桥接运行中切角色自动停止并提示)
 * - 参数面板(key/rms/idx/prot/F0/亮度)直接从首页调,下次开启变声时生效
 */
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    vm: RvcViewModel = viewModel(),
) {
    val halActive by vm.halActive.collectAsState()
    val roles by vm.roles.collectAsState()
    val currentRoleId by vm.currentRoleId.collectAsState()
    val notice by vm.notice.collectAsState()

    val profile by vm.debugProfile.collectAsState()
    val key by vm.debugKey.collectAsState()
    val rms by vm.debugRms.collectAsState()
    val idx by vm.debugIdx.collectAsState()
    val prot by vm.debugProt.collectAsState()
    val f0 by vm.debugF0.collectAsState()
    val brightness by vm.debugBrightness.collectAsState()

    var showF0Dialog by remember { mutableStateOf(false) }
    var manageRole by remember { mutableStateOf<RoleInfo?>(null) }

    // 一次性提示:显示 3 秒后自动清除
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3000)
            vm.consumeNotice()
        }
    }

    // 注意:不嵌套 Scaffold!外层 RvcApp 的 Scaffold 已提供 contentPadding。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        // ---- 大开关 ----
        item(key = "master-switch") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
                onClick = { vm.setHalActive(!halActive) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (halActive) "实时变声 · 运行中" else "实时变声 · 已关闭",
                        )
                        Text(
                            text = if (halActive) "系统音频正在走 RVC 变声链路"
                            else "开启后系统音频走 RVC 变声链路",
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = halActive,
                        onCheckedChange = { vm.setHalActive(it) },
                    )
                }
            }
        }

        // ---- 一次性提示 ----
        if (notice != null) {
            item(key = "notice") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Text(
                        text = notice ?: "",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        // ---- 当前角色 + 快速切换 ----
        item(key = "role-title") {
            SmallTitle(text = "当前角色")
        }
        if (roles.isEmpty()) {
            item(key = "role-empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "还没有角色 → 去「模型包」tab 导入",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(roles.size, key = { roles[it].modelId }) { i ->
                val role = roles[i]
                val selected = role.modelId == currentRoleId
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                    onClick = { vm.setCurrentRole(role.modelId) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (selected) "✓ ${role.name} (${role.modelId})" else "${role.name} (${role.modelId})",
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = "管理",
                            onClick = { manageRole = role },
                        )
                    }
                }
            }
        }

        // ---- 参数面板 ----
        item(key = "param-title") {
            SmallTitle(text = "参数(下次开启变声时生效)")
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
    }

    // F0 选择弹窗
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

    // 角色管理弹窗
    manageRole?.let { role ->
        RoleManageDialog(
            role = role,
            onDismiss = { manageRole = null },
        )
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
