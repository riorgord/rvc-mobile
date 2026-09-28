package com.rvc.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * P2.7:模型包页(合并原「角色 tab」+「在线库 tab」)。
 *
 * - 角色包:本地导入的角色,SAF 导入 + 点击切换
 * - 共享包:shared.zip 状态 / 下载 / SAF 导入 / 进度
 * - 在线角色库:catalog.roles(自己的角色库 + 授权在线角色)
 */
@Composable
fun ModelPackScreen(
    contentPadding: PaddingValues,
    vm: RvcViewModel = viewModel(),
) {
    val roles by vm.roles.collectAsState()
    val currentRoleId by vm.currentRoleId.collectAsState()
    val importResult by vm.importResult.collectAsState()

    val catalogRoles by vm.catalogRoles.collectAsState()
    val catalogLoading by vm.catalogLoading.collectAsState()
    val catalogError by vm.catalogError.collectAsState()

    val sharedStatus by vm.sharedStatus.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()
    val downloading by vm.downloading.collectAsState()
    val sharedImportResult by vm.sharedImportResult.collectAsState()
    val onlineRoles by vm.onlineRoles.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importRole(uri)
    }
    val sharedImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importShared(uri)
    }

    // 角色管理弹窗状态
    var manageRole by remember { mutableStateOf<RoleInfo?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshSharedStatus()
        vm.refreshOnlineRoles()
    }

    // 消费一次性结果提示
    LaunchedEffect(importResult) { importResult?.let { vm.consumeImportResult() } }
    LaunchedEffect(sharedImportResult) { sharedImportResult?.let { vm.consumeSharedImportResult() } }

    // 注意:不嵌套 Scaffold!外层 RvcApp 的 Scaffold 已提供 contentPadding。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        // ---- ① 角色包 ----
        item(key = "role-title") {
            SmallTitle(text = "角色包")
        }
        item(key = "role-import") {
            Button(
                onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
            ) {
                Text("导入角色包")
            }
        }
        if (importResult != null) {
            item(key = "role-import-result") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Text(
                        text = importResult ?: "",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        if (roles.isEmpty()) {
            item(key = "role-empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "还没有角色,点击上方「导入角色包」导入转化脚本产出的角色",
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
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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

        // ---- ② 共享包 ----
        item(key = "shared-title") {
            SmallTitle(text = "共享包")
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

        // ---- ③ 在线角色库(catalog.roles + 授权在线角色) ----
        item(key = "catalog-title") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                SmallTitle(
                    text = "在线角色库",
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "刷新",
                    onClick = {
                        vm.refreshCatalogRoles()
                        vm.refreshOnlineRoles()
                    },
                )
            }
        }
        when {
            catalogLoading -> item(key = "catalog-loading") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "正在拉取在线角色库…",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            catalogError != null -> item(key = "catalog-error") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "加载失败:$catalogError",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            catalogRoles.isEmpty() && onlineRoles.isEmpty() -> item(key = "catalog-empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "在线角色库为空(暂无授权角色)\n发布进 catalog.roles 或获得授权后自动出现",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            else -> {
                if (catalogRoles.isNotEmpty()) {
                    items(catalogRoles.size, key = { "c-" + catalogRoles[it].id }) { i ->
                        val r = catalogRoles[i]
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 8.dp),
                        ) {
                            Text(
                                text = "${r.name}\n${r.id} · ${r.license ?: "无许可"}"
                                    .let { if (r.sourceAuthor != null) "$it\n来源:${r.sourceAuthor}" else it },
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                if (onlineRoles.isNotEmpty()) {
                    items(onlineRoles.size, key = { "o-" + onlineRoles[it].first.id }) { i ->
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

    // 角色管理弹窗
    manageRole?.let { role ->
        RoleManageDialog(
            role = role,
            onDismiss = { manageRole = null },
        )
    }
}
