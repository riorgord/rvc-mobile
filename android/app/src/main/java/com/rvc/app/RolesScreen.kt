package com.rvc.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text

/**
 * 角色 tab:
 * ① 转化脚本角色(本地导入的角色包 + SAF 导入)
 * ② 自己的角色库(对接 catalog.roles,当前为空显示空态)
 */
@Composable
fun RolesScreen(
    contentPadding: PaddingValues,
    vm: RvcViewModel = viewModel(),
) {
    val roles by vm.roles.collectAsState()
    val currentRoleId by vm.currentRoleId.collectAsState()
    val catalogRoles by vm.catalogRoles.collectAsState()
    val catalogLoading by vm.catalogLoading.collectAsState()
    val catalogError by vm.catalogError.collectAsState()
    val importResult by vm.importResult.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importRole(uri)
    }

    // 消费导入结果提示
    LaunchedEffect(importResult) {
        importResult?.let { msg ->
            vm.consumeImportResult()
        }
    }

    // 注意:不嵌套 Scaffold!外层 RvcApp 的 Scaffold 已提供 contentPadding,
    // 否则内容会被 TopAppBar 盖住。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        // ---- ① 转化脚本角色 ----
        item(key = "local-title") {
            SmallTitle(text = "转化脚本角色")
        }
        item(key = "local-import") {
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
        if (roles.isEmpty()) {
            item(key = "local-empty") {
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
                    Text(
                        text = if (selected) "✓ ${role.name} (${role.modelId})" else "${role.name} (${role.modelId})",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        // ---- ② 自己的角色库(catalog.roles) ----
        item(key = "catalog-title") {
            SmallTitle(text = "自己的角色库")
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
            catalogRoles.isEmpty() -> item(key = "catalog-empty") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "自己的角色库为空(在线目录暂无角色)\n将来自建角色发布进 catalog.roles 后自动出现",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            else -> items(catalogRoles.size, key = { catalogRoles[it].id }) { i ->
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
    }
}
