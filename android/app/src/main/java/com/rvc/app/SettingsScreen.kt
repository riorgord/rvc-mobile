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
    var showSocDialog by remember { mutableStateOf(false) }

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
        // P2.7:HAL 开关已移到首页「变声」大开关,设置页只留模块安装/设备/关于
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
                    onClick = { showSocDialog = true },
                )
            }

            item(key = "about-title") { SmallTitle(text = "关于") }

            item(key = "about") {
                ArrowPreference(
                    title = "RVC Mobile",
                    summary = "版本 0.1.0b",
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
    BackHandler(
        enabled = showRedDialog || showYellowDialog || showRebootDialog || showSocDialog
    ) {
        showRedDialog = false
        showYellowDialog = false
        showRebootDialog = false
        showSocDialog = false
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
        // 勾选框立即可勾;「继续安装」10 秒倒计时(开屏广告样式)后才可点
        var confirmed by remember { mutableStateOf(false) }
        var remaining by remember { mutableStateOf(10) }
        val risks = guard?.yellowReasons?.joinToString("\n• ", "• ") ?: ""
        LaunchedEffect(showYellowDialog) {
            remaining = 10
            while (remaining > 0) {
                kotlinx.coroutines.delay(1000)
                remaining--
            }
        }
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                onClick = { confirmed = !confirmed },
            ) {
                Text(
                    text = if (confirmed) "✓ 我已阅读并理解上述风险" else "☐ 我已阅读并理解上述风险",
                    modifier = Modifier.padding(16.dp),
                )
            }
            TextButton(
                text = if (remaining > 0) "继续安装 ($remaining)" else "继续安装",
                enabled = confirmed && remaining <= 0,
                onClick = {
                    showYellowDialog = false
                    vm.confirmInstallHalModule()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showSocDialog) {
        val socOptions = listOf("sm8475", "sm8450", "sm8550", "sm8650", "sm8750", "sm8850")
        OverlayDialog(
            show = showSocDialog,
            title = "选择本机 SoC",
            summary = "手动选择后优先于自动探测;QNN 产物绑死架构,选错将无法变声",
            onDismissRequest = { showSocDialog = false },
        ) {
            TextButton(
                text = "自动检测(推荐)",
                onClick = {
                    vm.setSocOverride(null)
                    showSocDialog = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
            socOptions.forEach { s ->
                TextButton(
                    text = s + if (s == soc) "(当前)" else "",
                    onClick = {
                        vm.setSocOverride(s)
                        showSocDialog = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
