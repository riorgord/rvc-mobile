package com.rvc.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * P0.5 冒烟验证:最小 Compose + Miuix 界面,
 * 验证 Kotlin 2.4 + Compose + Chaquopy 共存。
 * 暂不替换 MainActivity,独立入口(manifest 单独注册)。
 */
class SmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                SmokeScreen()
            }
        }
    }
}

@Composable
private fun SmokeScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = "RVC Miuix 冒烟",
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { contentPadding ->
        Text(
            text = "Compose + Miuix + Chaquopy 共存验证 OK",
            modifier = Modifier.padding(contentPadding).padding(16.dp),
        )
    }
}
