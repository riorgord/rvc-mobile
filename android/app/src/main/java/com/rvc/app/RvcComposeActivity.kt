package com.rvc.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * P2.1:Compose + Miuix 新 UI 入口(launcher)。
 *
 * 三 tab 底部导航:角色 / 在线库 / 设置。
 * 主题跟随系统(Miuix ColorSchemeMode.System)。
 * 业务逻辑后续全部走 RvcCore(经 ViewModel),本文件只负责壳。
 */
class RvcComposeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                RvcApp()
            }
        }
    }
}

/** 三个 tab 的枚举。 */
private enum class RvcTab(val label: String, val icon: ImageVector) {
    ROLES("角色", MiuixIcons.ContactsCircle),
    LIBRARY("在线库", MiuixIcons.CloudFill),
    SETTINGS("设置", MiuixIcons.Settings),
}

@Composable
private fun RvcApp() {
    var current by rememberSaveable { mutableIntStateOf(0) }
    val tabs = RvcTab.entries

    Scaffold(
        topBar = {
            TopAppBar(
                title = tabs[current].label,
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = current == index,
                        onClick = { current = index },
                        icon = tab.icon,
                        label = tab.label,
                    )
                }
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text("「${tabs[current].label}」页(P2.2 实现)")
        }
    }
}
