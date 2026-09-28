package com.rvc.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 设置 tab:
 * - HAL 桥接开关(实时变声开关)
 * - 安装/更新 HAL 模块(红/黄守卫弹窗)
 * - 本机 SoC
 * - 关于(版本号,连击 5 次进调试页)
 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    vm: RvcViewModel = viewModel(),
    onOpenDebug: () -> Unit,
) {
    val halActive by vm.halActive.collectAsState()
    val guard by vm.guard.collectAsState()
    val installLog by vm.installLog.collectAsState()
    val installPendingReboot by vm.installPendingReboot.collectAsState()
    val soc by vm.soc.collectAsState()

    // 版本号连击计数(5 次进调试页)
    var versionTaps by remember { mutableIntStateOf(0) }

    // 弹窗状态
    var showRedDialog by remember { mutableStateOf(false) }
    var showYellowDialog by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshHalState()
    }

    // 注意:不嵌套 Scaffold!外层 RvcApp 的 Scaffold 已提供 contentPadding
    // (含 TopAppBar 高度),这里直接用,否则内容会被 TopAppBar 盖住。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        item(key = "hal-title") { SmallTitle(text = "实时变声") }

            item(key = "hal-switch") {
                SwitchPreference(
                    checked = halActive,
                    onCheckedChange = { vm.setHalActive(it) },
                    title = "HAL 桥接",
                    summary = "开启后系统音频走 RVC 变声链路",
                )
            }

            item(key = "hal-module") {
                ArrowPreference(
                    title = "安装 / 更新 HAL 模块",
                    summary = "Magisk/KernelSU 模块,需 root;重启后生效",
                    onClick = { vm.installHalModule() },
                )
            }

            if (installLog != null) {
                item(key = "install-log") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                    ) {
                        Text(
                            text = installLog ?: "",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            item(key = "device-title") { SmallTitle(text = "设备") }

            item(key = "soc") {
                ArrowPreference(
                    title = "本机 SoC",
                    summary = soc ?: "未知(需手动选择)",
                    onClick = { /* P2.5:SoC 选择弹窗 */ },
                )
            }

            item(key = "about-title") { SmallTitle(text = "关于") }

            item(key = "about") {
                ArrowPreference(
                    title = "RVC Mobile",
                    summary = "版本 0.1.0b(点击 5 次进入调试)",
                    onClick = {
                        versionTaps++
                        if (versionTaps >= 5) {
                            versionTaps = 0
                            onOpenDebug()
                        }
                    },
                )
            }
        }

    // ---- 守卫弹窗 ----
    // 检测完成且为 RED 档 → 红色拦截弹窗
    LaunchedEffect(guard) {
        guard?.let { g ->
            if (g.tier == DeviceGuard.Tier.RED) showRedDialog = true
            else if (g.tier == DeviceGuard.Tier.YELLOW) showYellowDialog = true
        }
    }

    // 安装完成等重启 → 重启弹窗
    LaunchedEffect(installPendingReboot) {
        if (installPendingReboot) showRebootDialog = true
    }

    // 弹窗显示时,返回键只关弹窗(不退出 Activity)。
    // 注:miuix 0.9.3 OverlayDialog 的返回键处理依赖导航宿主喂 back 事件,
    // 本项目手写导航无宿主,故用 activity-compose 的 BackHandler 兜底。
    BackHandler(enabled = showRedDialog || showYellowDialog || showRebootDialog) {
        showRedDialog = false
        showYellowDialog = false
        showRebootDialog = false
        vm.consumeInstallState()
    }

    if (showRedDialog) {
        val reasons = guard?.redReasons?.joinToString("\n• ", "• ") ?: ""
        OverlayDialog(
            show = showRedDialog,
            title = "⚠ 设备不满足机架运行条件",
            summary = "本机架目前仅验证于:\n" +
                "K50U + Legacy/HIDL 音频 + 官方内核 + Android 12(MIUI13)+ Magisk\n\n" +
                "检测到以下不兼容项:\n$reasons\n\n" +
                "为避免无声/变声失败或系统异常,已禁止安装。",
            onDismissRequest = {
                showRedDialog = false
                vm.consumeInstallState()
            },
        ) {
            TextButton(
                text = "退出",
                onClick = {
                    showRedDialog = false
                    vm.consumeInstallState()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showYellowDialog) {
        var confirmed by remember { mutableStateOf(false) }
        val risks = guard?.yellowReasons?.joinToString("\n• ", "• ") ?: ""
        OverlayDialog(
            show = showYellowDialog,
            title = "⚠ 设备条件与已验证基线不完全一致",
            summary = "检测到以下条件未完全符合已验证基线:\n$risks\n\n" +
                "安装后可能出现:无声、变声失败、系统音频异常。\n" +
                "请阅读并勾选确认;「继续安装」将在 10 秒倒计时后可用。",
            onDismissRequest = {
                showYellowDialog = false
                vm.consumeInstallState()
            },
        ) {
            TextButton(
                text = if (confirmed) "✓ 已确认风险,继续安装" else "请阅读风险说明后点击确认",
                onClick = {
                    confirmed = true
                    showYellowDialog = false
                    vm.confirmInstallHalModule()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showRebootDialog) {
        OverlayDialog(
            show = showRebootDialog,
            title = "HAL 模块已更新",
            summary = "重启后新的 HAL 模块才会生效。现在重启吗?",
            onDismissRequest = {
                showRebootDialog = false
                vm.consumeInstallState()
            },
        ) {
            TextButton(
                text = "立即重启",
                onClick = {
                    showRebootDialog = false
                    vm.rebootNow()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                text = "稍后",
                onClick = {
                    showRebootDialog = false
                    vm.consumeInstallState()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
