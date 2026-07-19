package com.hyx.miao.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.hyx.miao.data.remote.api.AppAnnouncement
import com.hyx.miao.data.remote.api.AppVersionResponse

@Composable
internal fun AppUpdateDialog(
    version: AppVersionResponse,
    currentVersionCode: Int,
    onDownload: (String) -> Unit,
    onPostpone: () -> Unit,
) {
    val forceUpdate = requiresForceUpdate(version, currentVersionCode)
    val downloadUrl = version.downloadUrl.orEmpty()
    // A mandatory dialog must never trap the user when the server forgot the URL.
    val lockDialog = shouldLockUpdateDialog(version, currentVersionCode)
    AlertDialog(
        onDismissRequest = { if (!lockDialog) onPostpone() },
        properties = DialogProperties(
            dismissOnBackPress = !lockDialog,
            dismissOnClickOutside = !lockDialog,
        ),
        title = {
            Text(
                version.latestVersion?.takeIf { it.isNotBlank() }
                    ?.let { "发现新版本 $it" }
                    ?: "发现新版本",
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (forceUpdate) {
                    Text(
                        "当前版本已不再受支持，请更新后继续使用。",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
                version.releaseNotes?.takeIf { it.isNotBlank() }?.let {
                    Text("更新内容", style = MaterialTheme.typography.titleSmall)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (downloadUrl.isBlank()) {
                    Text(
                        "下载地址暂不可用，请稍后重试。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDownload(downloadUrl) },
                enabled = downloadUrl.isNotBlank(),
            ) {
                Text(if (forceUpdate) "立即更新" else "下载更新")
            }
        },
        dismissButton = if (lockDialog) {
            null
        } else {
            { TextButton(onClick = onPostpone) { Text(if (forceUpdate) "稍后重试" else "稍后") } }
        },
    )
}

@Composable
internal fun AnnouncementDialog(
    announcement: AppAnnouncement,
    onRead: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onRead,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(announcement.title?.takeIf { it.isNotBlank() } ?: "公告")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (announcement.isPinned) {
                        Text(
                            "置顶",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    announcementTypeLabel(announcement.type)?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        },
        text = {
            Text(
                text = announcement.content.orEmpty(),
                modifier = Modifier.heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(onClick = onRead) { Text("我知道了") }
        },
    )
}

private fun announcementTypeLabel(type: String?): String? = when (type?.lowercase()) {
    "warning" -> "重要提醒"
    "maintenance" -> "维护通知"
    "update" -> "更新通知"
    "info" -> "通知"
    else -> null
}
