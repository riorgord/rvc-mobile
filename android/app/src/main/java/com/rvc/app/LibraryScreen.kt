package com.rvc.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text

/**
 * 在线库 tab:
 * - 共享件状态(就绪/缺失 + 版本)
 * - 下载共享件(带进度)/ SAF 导入 shared.zip
 * - 在线授权角色列表(点击下载)
 */
@Composable
fun LibraryScreen(vm: RvcViewModel = viewModel()) {
    val sharedStatus by vm.sharedStatus.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()
    val downloading by vm.downloading.collectAsState()
    val sharedImportResult by vm.sharedImportResult.collectAsState()
    val onlineRoles by vm.onlineRoles.collectAsState()

    val sharedImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importShared(uri)
    }

    LaunchedEffect(Unit) {
        vm.refreshSharedStatus()
        vm.refreshOnlineRoles()
    }

    LaunchedEffect(sharedImportResult) {
        sharedImportResult?.let { vm.consumeSharedImportResult() }
    }

    Scaffold { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            // ---- 共享件 ----
            item(key = "shared-title") {
                SmallTitle(text = "共享件")
            }
            item(key = "shared-status") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                ) {
                    val (ready, ver) = sharedStatus
                    Text(
                        text = when {
                            ready && ver > 0 -> "共享件已就绪(v$ver) ✔"
                            ready -> "共享件已就绪(缺版本记录)"
                            else -> "共享件缺失 → 请下载或 SAF 导入 shared.zip"
                        },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item(key = "shared-actions") {
                Button(
                    onClick = { vm.downloadSharedFromCatalog() },
                    enabled = !downloading,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Text(if (downloading) "下载中…" else "下载共享件")
                }
                Button(
                    onClick = {
                        sharedImportLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    enabled = !downloading,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text("SAF 导入 shared.zip")
                }
            }
            if (downloading || downloadState != null) {
                item(key = "download-progress") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Text(
                            text = downloadState ?: "",
                            modifier = Modifier.padding(16.dp),
                        )
                        if (downloading) {
                            LinearProgressIndicator(
                                progress = downloadProgress / 100f,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                            )
                        }
                    }
                }
            }
            if (sharedImportResult != null) {
                item(key = "shared-import-result") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Text(
                            text = sharedImportResult ?: "",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            // ---- 在线角色 ----
            item(key = "online-title") {
                SmallTitle(text = "在线角色")
            }
            if (onlineRoles.isEmpty()) {
                item(key = "online-empty") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Text(
                            text = "暂无授权角色(需 catalog 授权 + 匹配本机 SoC)",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(onlineRoles.size, key = { onlineRoles[it].first.id }) { i ->
                    val (role, file) = onlineRoles[i]
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                        onClick = { vm.downloadOnlineRole(role, file) },
                    ) {
                        Text(
                            text = "${role.name} (${file.size / 1048576}MB)\n点击下载安装",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
