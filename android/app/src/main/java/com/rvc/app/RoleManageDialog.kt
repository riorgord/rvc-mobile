package com.rvc.app

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 角色管理弹窗(首页/模型包共用):重命名 + 删除。
 * 删除为两步操作(点「管理」→ 再点「删除角色」),不再二次确认。
 */
@Composable
fun RoleManageDialog(
    role: RoleInfo,
    onDismiss: () -> Unit,
    vm: RvcViewModel = viewModel(),
) {
    var newName by remember { mutableStateOf(role.name) }

    OverlayDialog(
        show = true,
        title = "管理角色",
        summary = "${role.name} (${role.modelId})",
        onDismissRequest = onDismiss,
    ) {
        TextField(
            value = newName,
            onValueChange = { newName = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = "保存名称",
            onClick = {
                if (vm.renameRole(role.modelId, newName)) onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = "删除角色",
            onClick = {
                vm.deleteRole(role.modelId)
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
