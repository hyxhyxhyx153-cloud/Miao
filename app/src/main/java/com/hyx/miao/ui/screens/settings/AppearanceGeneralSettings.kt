package com.hyx.miao.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hyx.miao.data.repository.SettingsRepository
import com.hyx.miao.BuildConfig
import com.hyx.miao.data.local.MiaoDatabase
import com.hyx.miao.data.remote.ApiEndpointProvider
import com.hyx.miao.data.remote.api.AppApi
import com.hyx.miao.data.remote.api.AppVersionResponse
import com.hyx.miao.data.remote.api.UserApi
import com.hyx.miao.ui.components.LiquidGlassCard
import com.hyx.miao.ui.components.MiaoTopBar
import com.hyx.miao.ui.screens.auth.glassTextFieldColors
import com.hyx.miao.ui.screens.main.isUpdateAvailable
import com.hyx.miao.ui.screens.main.requiresForceUpdate
import com.hyx.miao.ui.screens.main.resolveDownloadUrl
import com.hyx.miao.ui.theme.LocalMiaoExtraColors
import com.hyx.miao.ui.theme.MiaoPurple
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppearanceViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.hyx.miao.data.repository.AppSettings())
    init { viewModelScope.launch { repo.syncFromCloud() } }
    fun setDarkMode(v: String)   = viewModelScope.launch { repo.update { it.copy(darkMode = v) } }
    fun setFontSize(v: String)   = viewModelScope.launch { repo.update { it.copy(fontSize = v) } }
    fun setThemeColor(v: String) = viewModelScope.launch { repo.update { it.copy(themeColor = v) } }
}

private val themeColors = listOf(
    "purple" to Color(0xFFC084FC),
    "pink"   to Color(0xFFF9A8D4),
    "blue"   to Color(0xFF60A5FA),
    "green"  to Color(0xFF34D399),
    "orange" to Color(0xFFFB923C),
    "red"    to Color(0xFFF87171),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    vm: AppearanceViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val extra = LocalMiaoExtraColors.current

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MiaoTopBar(
                title = { Text("外观设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Theme color
                LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("主题色", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            themeColors.forEach { (key, color) ->
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (s.themeColor == key) 3.dp else 0.dp,
                                            color = if (s.themeColor == key) Color.White else Color.Transparent,
                                            shape = CircleShape,
                                        )
                                        .clickable { vm.setThemeColor(key) }
                                )
                            }
                        }
                    }
                }

                // Dark mode
                LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("深色模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        listOf("system" to "跟随系统", "light" to "始终亮色", "dark" to "始终暗色").forEach { (key, label) ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { vm.setDarkMode(key) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = s.darkMode == key, onClick = { vm.setDarkMode(key) }, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                // Font size
                LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("消息字体大小", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        listOf("small" to "小", "medium" to "中（推荐）", "large" to "大").forEach { (key, label) ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { vm.setFontSize(key) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = s.fontSize == key, onClick = { vm.setFontSize(key) }, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── General Settings ──────────────────────────────────────────────────────────

data class GeneralActionState(
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val version: AppVersionResponse? = null,
)

@HiltViewModel
class GeneralSettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val userApi: UserApi,
    private val appApi: AppApi,
    private val endpointProvider: ApiEndpointProvider,
    private val database: MiaoDatabase,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.hyx.miao.data.repository.AppSettings())
    private val _action = MutableStateFlow(GeneralActionState())
    val action = _action.asStateFlow()
    init { viewModelScope.launch { repo.syncFromCloud() } }
    fun setSendOnEnter(v: Boolean)   = viewModelScope.launch { repo.update { it.copy(sendOnEnter = v) } }
    fun setHapticFeedback(v: Boolean) = viewModelScope.launch { repo.update { it.copy(hapticFeedback = v) } }

    fun changePassword(currentPassword: String, newPassword: String) {
        viewModelScope.launch {
            _action.value = GeneralActionState(isLoading = true)
            runCatching {
                userApi.changePassword(mapOf("currentPassword" to currentPassword, "newPassword" to newPassword))
            }.fold(
                onSuccess = { _action.value = GeneralActionState(message = "密码修改成功") },
                onFailure = { _action.value = GeneralActionState(error = it.message ?: "密码修改失败") },
            )
        }
    }

    fun clearLocalData() {
        viewModelScope.launch {
            _action.value = GeneralActionState(isLoading = true)
            runCatching { withContext(Dispatchers.IO) { database.clearAllTables() } }.fold(
                onSuccess = { _action.value = GeneralActionState(message = "本地会话、消息和缓存已清除") },
                onFailure = { _action.value = GeneralActionState(error = it.message ?: "清理本地数据失败") },
            )
        }
    }

    fun checkVersion() {
        viewModelScope.launch {
            _action.value = GeneralActionState(isLoading = true)
            runCatching {
                appApi.getVersion(BuildConfig.VERSION_CODE)
                    .resolveDownloadUrl(endpointProvider.baseUrl)
            }.fold(
                onSuccess = { version ->
                    val message = if (isUpdateAvailable(version, BuildConfig.VERSION_CODE)) {
                        "发现新版本 ${version.latestVersion.orEmpty()}"
                    } else {
                        "当前已是最新版本"
                    }
                    _action.value = GeneralActionState(message = message, version = version)
                },
                onFailure = { _action.value = GeneralActionState(error = it.message ?: "版本检查失败") },
            )
        }
    }

    fun clearNotice() { _action.value = _action.value.copy(message = null, error = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    vm: GeneralSettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val extra = LocalMiaoExtraColors.current
    val context = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(action.message, action.error) {
        (action.message ?: action.error)?.let { snackbar.showSnackbar(it); vm.clearNotice() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MiaoTopBar(
                title = { Text("通用设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Send method
                LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("发送方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        listOf(false to "点击发送按钮", true to "回车发送（换行用 Shift+Enter）").forEach { (v, label) ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { vm.setSendOnEnter(v) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = s.sendOnEnter == v, onClick = { vm.setSendOnEnter(v) }, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("账号安全", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Button(onClick = { showPasswordDialog = true }, enabled = !action.isLoading, modifier = Modifier.fillMaxWidth()) {
                            Text("修改登录密码")
                        }
                    }
                }

                // Haptic feedback
                LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("震动反馈", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("发送消息时震动提示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = s.hapticFeedback, onCheckedChange = { vm.setHapticFeedback(it) }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary))
                    }
                }

                // Clear data
                LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("数据管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { showClearDialog = true },
                            enabled = !action.isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("清除本地所有数据") }
                    }
                }

                LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("当前版本", style = MaterialTheme.typography.bodyMedium)
                            Text("喵 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = vm::checkVersion, enabled = !action.isLoading, modifier = Modifier.fillMaxWidth()) { Text("检查更新") }
                        action.version?.takeIf { isUpdateAvailable(it, BuildConfig.VERSION_CODE) }?.let { version ->
                            version.releaseNotes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            version.downloadUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                Button(
                                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (requiresForceUpdate(version, BuildConfig.VERSION_CODE)) {
                                            "立即更新（必须）"
                                        } else {
                                            "下载新版本"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除本地数据") },
            text = { Text("这会删除本地缓存的会话、消息等数据，不影响服务器数据，可重新登录恢复喵～") },
            confirmButton = {
                Button(onClick = { showClearDialog = false; vm.clearLocalData() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } },
        )
    }


    if (showPasswordDialog) {
        var currentPassword by remember { mutableStateOf("") }
        var newPassword by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("修改密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(currentPassword, { currentPassword = it }, label = { Text("当前密码") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), colors = glassTextFieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), singleLine = true)
                    OutlinedTextField(newPassword, { newPassword = it }, label = { Text("新密码（至少 8 位）") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), colors = glassTextFieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), singleLine = true)
                    OutlinedTextField(confirmation, { confirmation = it }, label = { Text("确认新密码") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), colors = glassTextFieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), isError = confirmation.isNotEmpty() && confirmation != newPassword, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPasswordDialog = false; vm.changePassword(currentPassword, newPassword) },
                    enabled = currentPassword.isNotBlank() && newPassword.length >= 8 && newPassword == confirmation,
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showPasswordDialog = false }) { Text("取消") } },
        )
    }
}
