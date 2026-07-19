package com.hyx.miao.ui.screens.wechat

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.hyx.miao.data.remote.api.BindingInfo
import com.hyx.miao.data.remote.api.PersonaApi
import com.hyx.miao.data.remote.api.QrStatusRequest
import com.hyx.miao.data.remote.api.WechatApi
import com.hyx.miao.data.remote.api.WechatPersonaRequest
import com.hyx.miao.data.remote.dto.PersonaResponse
import com.hyx.miao.ui.components.LiquidGlassCard
import com.hyx.miao.ui.components.MiaoTopBar
import com.hyx.miao.ui.theme.MiaoPurple
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

enum class QrState { LOADING, WAITING, SCANNED, CONFIRMED, EXPIRED, ERROR }

data class WechatUiState(
    val qrState: QrState = QrState.LOADING,
    val qrContent: String? = null,
    val isBound: Boolean = false,
    val bindingInfo: BindingInfo? = null,
    val personas: List<PersonaResponse> = emptyList(),
    val selectedPersonaId: String? = null,
    val personasLoading: Boolean = true,
    val isSavingPersona: Boolean = false,
    val statusRefreshFailed: Boolean = false,
    val isRestartingWorker: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class WechatViewModel @Inject constructor(
    private val api: WechatApi,
    private val personaApi: PersonaApi,
) : ViewModel() {

    private val _state = MutableStateFlow(WechatUiState())
    val state = _state.asStateFlow()

    private var pollJob: Job? = null
    private var statusJob: Job? = null
    private var personaSaveJob: Job? = null
    private var currentQrToken: String? = null
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            runCatching { personaApi.getAll() }
                .onSuccess { personas ->
                    _state.update { it.copy(personas = personas, personasLoading = false) }
                }
                .onFailure {
                    _state.update { it.copy(personasLoading = false, error = "人格列表加载失败，请稍后重试") }
                }
            runCatching { api.getBindStatus() }
                .onSuccess { status ->
                    if (status.bound) {
                        _state.update {
                            it.copy(
                                isBound = true,
                                bindingInfo = status.binding,
                                selectedPersonaId = status.binding?.personaId,
                                statusRefreshFailed = false,
                            )
                        }
                        startStatusPolling()
                    } else {
                        requestQrCode()
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            statusRefreshFailed = true,
                            error = "微信绑定状态加载失败，正在自动重试",
                        )
                    }
                    startInitialStatusPolling()
                }
        }
    }

    fun requestQrCode() {
        pollJob?.cancel()
        statusJob?.cancel()
        currentQrToken = null
        _state.update {
            it.copy(
                qrState = QrState.LOADING,
                qrContent = null,
                statusRefreshFailed = false,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                val res = api.getQrCode()
                currentQrToken = res.qrcode
                _state.update { it.copy(qrState = QrState.WAITING, qrContent = res.qrcode_url) }
                startPolling(res.qrcode)
            } catch (e: Exception) {
                _state.update { it.copy(qrState = QrState.ERROR, error = "获取二维码失败：${e.message}") }
            }
        }
    }

    private fun startPolling(token: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            repeat(90) { // max 3 min at 2s intervals
                delay(2000)
                try {
                    val res = api.getStatus(
                        QrStatusRequest(
                            qrcode = token,
                            personaId = _state.value.selectedPersonaId.orEmpty(),
                        )
                    )
                    when (res.data?.status) {
                        "scaned"    -> _state.update { it.copy(qrState = QrState.SCANNED) }
                        "confirmed" -> {
                            // The confirmed poll may have been sent just before the user
                            // changed the selector. Disable further edits briefly and apply
                            // the latest on-screen choice once more to make that choice win.
                            _state.update {
                                it.copy(
                                    qrState = QrState.CONFIRMED,
                                    isBound = true,
                                    isSavingPersona = true,
                                )
                            }
                            val desiredPersonaId = _state.value.selectedPersonaId
                            val personaResult = runCatching {
                                api.updatePersona(WechatPersonaRequest(desiredPersonaId.orEmpty()))
                            }
                            val binding = runCatching { api.getBindStatus() }.getOrNull()?.binding
                            val bound = personaResult.isSuccess || binding != null
                            _state.update {
                                it.copy(
                                    qrState = if (bound) QrState.CONFIRMED else QrState.ERROR,
                                    isBound = bound,
                                    bindingInfo = binding,
                                    selectedPersonaId = when {
                                        personaResult.isSuccess -> personaResult.getOrNull()?.personaId
                                        binding != null -> binding.personaId
                                        else -> desiredPersonaId
                                    },
                                    isSavingPersona = false,
                                    error = personaResult.exceptionOrNull()?.let { error ->
                                        if (bound) {
                                            "微信已绑定，但人格同步失败：${error.message ?: "请重新选择"}"
                                        } else {
                                            "微信确认成功，但绑定信息未保存：${error.message ?: "请刷新后重试"}"
                                        }
                                    },
                                )
                            }
                            if (bound) startStatusPolling()
                            return@launch
                        }
                        "expired"   -> {
                            _state.update { it.copy(qrState = QrState.EXPIRED) }
                            return@launch
                        }
                    }
                } catch (_: Exception) {}
            }
            // Timeout → expired
            if (_state.value.qrState == QrState.WAITING || _state.value.qrState == QrState.SCANNED) {
                _state.update { it.copy(qrState = QrState.EXPIRED) }
            }
        }
    }

    fun unbind() {
        viewModelScope.launch {
            try {
                api.unbind()
                statusJob?.cancel()
                _state.update { it.copy(isBound = false, bindingInfo = null) }
                requestQrCode()
            } catch (e: Exception) {
                _state.update { it.copy(error = "解绑失败：${e.message}") }
            }
        }
    }

    private fun startInitialStatusPolling() {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            while (isActive && !_state.value.isBound && currentQrToken == null) {
                delay(3_000)
                val status = runCatching { api.getBindStatus() }.getOrNull() ?: continue
                if (status.bound) {
                    _state.update {
                        it.copy(
                            isBound = true,
                            bindingInfo = status.binding,
                            selectedPersonaId = status.binding?.personaId,
                            statusRefreshFailed = false,
                        )
                    }
                    startStatusPolling()
                } else {
                    requestQrCode()
                }
                return@launch
            }
        }
    }

    private fun startStatusPolling() {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            while (isActive && _state.value.isBound) {
                val status = runCatching { api.getBindStatus() }
                status.onSuccess { response ->
                    if (!response.bound) {
                        _state.update {
                            it.copy(
                                isBound = false,
                                bindingInfo = null,
                                statusRefreshFailed = false,
                            )
                        }
                        requestQrCode()
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            bindingInfo = response.binding,
                            selectedPersonaId = if (it.isSavingPersona) {
                                it.selectedPersonaId
                            } else {
                                response.binding?.personaId
                            },
                            statusRefreshFailed = false,
                        )
                    }
                }.onFailure {
                    _state.update { it.copy(statusRefreshFailed = true) }
                }
                val connection = _state.value.bindingInfo?.connectionStatus
                delay(if (connection == "running") 5_000 else 3_000)
            }
        }
    }

    fun restartWorker() {
        if (_state.value.isRestartingWorker) return
        viewModelScope.launch {
            _state.update { it.copy(isRestartingWorker = true) }
            runCatching { api.restart() }
                .onSuccess {
                    _state.update { it.copy(statusRefreshFailed = false) }
                    startStatusPolling()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(error = "微信重连失败：${error.message ?: "请稍后重试"}")
                    }
                }
            _state.update { it.copy(isRestartingWorker = false) }
        }
    }

    fun selectPersona(personaId: String?) {
        val previous = _state.value.selectedPersonaId
        if (personaId == previous || _state.value.isSavingPersona) return
        _state.update { it.copy(selectedPersonaId = personaId) }
        if (!_state.value.isBound) return

        personaSaveJob?.cancel()
        personaSaveJob = viewModelScope.launch {
            _state.update { it.copy(isSavingPersona = true) }
            runCatching { api.updatePersona(WechatPersonaRequest(personaId.orEmpty())) }
                .onSuccess { response ->
                    _state.update {
                        it.copy(
                            selectedPersonaId = response.personaId,
                            bindingInfo = it.bindingInfo?.copy(
                                personaId = response.personaId,
                                personaName = response.personaName,
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            selectedPersonaId = previous,
                            error = "切换微信人格失败：${error.message ?: "请稍后重试"}",
                        )
                    }
                }
            _state.update { it.copy(isSavingPersona = false) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        statusJob?.cancel()
        personaSaveJob?.cancel()
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WechatBindScreen(
    onNavigateBack: () -> Unit,
    vm: WechatViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.init() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); vm.clearError() }
    }

    Scaffold(
        topBar = {
            MiaoTopBar(
                title = { Text("微信绑定") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Transparent,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            WechatPersonaSelector(
                personas = state.personas,
                selectedPersonaId = state.selectedPersonaId,
                loading = state.personasLoading,
                saving = state.isSavingPersona,
                onSelect = vm::selectPersona,
            )
            if (state.isBound) {
                BoundView(
                    binding = state.bindingInfo,
                    statusRefreshFailed = state.statusRefreshFailed,
                    restarting = state.isRestartingWorker,
                    onRestart = vm::restartWorker,
                    onUnbind = vm::unbind,
                )
            } else {
                UnboundView(state = state, onRefresh = { vm.requestQrCode() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WechatPersonaSelector(
    personas: List<PersonaResponse>,
    selectedPersonaId: String?,
    loading: Boolean,
    saving: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = personas.firstOrNull { it.id == selectedPersonaId }
    val selectedName = selected?.name ?: "默认喵喵"
    val selectedDescription = selected?.description?.takeIf { it.isNotBlank() }
        ?: if (selected == null) "使用微信内置的温柔猫娘人格" else "自定义人格"

    LiquidGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("微信默认人格", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "微信收到消息时使用，绑定后也可以随时修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loading || saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MiaoPurple,
                    )
                }
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (!loading && !saving) expanded = it },
            ) {
                OutlinedTextField(
                    value = selectedName,
                    onValueChange = {},
                    readOnly = true,
                    enabled = !loading && !saving,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("默认喵喵") },
                        onClick = { expanded = false; onSelect(null) },
                    )
                    personas.forEach { persona ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(persona.name)
                                    persona.description?.takeIf { it.isNotBlank() }?.let { description ->
                                        Text(
                                            description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            },
                            onClick = { expanded = false; onSelect(persona.id) },
                        )
                    }
                }
            }
            Text(
                selectedDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnboundView(state: WechatUiState, onRefresh: () -> Unit) {
    Text("使用微信扫描下方二维码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    Text("即可在微信中与喵喵对话喵～", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    LiquidGlassCard(cornerRadius = 26.dp) {
        Box(modifier = Modifier.size(240.dp).padding(12.dp), contentAlignment = Alignment.Center) {
            when (state.qrState) {
                QrState.LOADING -> CircularProgressIndicator(color = MiaoPurple, modifier = Modifier.size(40.dp))
                QrState.ERROR   -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("获取失败", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRefresh) { Text("点击重试", color = MiaoPurple) }
                }
                QrState.EXPIRED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.qrContent != null) {
                        QrCodeImage(
                            content = state.qrContent,
                            modifier = Modifier.size(160.dp).alpha(0.25f),
                        )
                    }
                    Text("已过期", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRefresh) { Text("刷新", color = MiaoPurple) }
                }
                else -> {
                    if (state.qrContent != null) {
                        Box {
                            QrCodeImage(content = state.qrContent, modifier = Modifier.fillMaxSize())
                            // Scanned overlay
                            if (state.qrState == QrState.SCANNED) {
                                Box(modifier = Modifier.fillMaxSize().background(Color.Green.copy(0.3f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                    Text("✓", fontSize = 56.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val (statusText, statusColor) = when (state.qrState) {
        QrState.LOADING  -> "正在生成二维码..." to MaterialTheme.colorScheme.onSurfaceVariant
        QrState.WAITING  -> "等待扫描..." to MaterialTheme.colorScheme.onSurfaceVariant
        QrState.SCANNED  -> "已扫描！请在微信中点击确认" to Color(0xFF10B981)
        QrState.CONFIRMED-> "🎉 绑定成功！" to Color(0xFF10B981)
        QrState.EXPIRED  -> "二维码已过期" to MaterialTheme.colorScheme.error
        QrState.ERROR    -> "获取二维码失败" to MaterialTheme.colorScheme.error
    }

    if (state.qrState == QrState.WAITING) {
        WaitingDots()
    } else {
        Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor, textAlign = TextAlign.Center)
    }

    Text("二维码有效期 3 分钟", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
}

@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { createQrCodeBitmap(content) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "微信二维码",
        modifier = modifier,
    )
}

private fun createQrCodeBitmap(content: String, size: Int = 768): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2,
        ),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun WaitingDots() {
    val infinite = rememberInfiniteTransition(label = "dots")
    val offset by infinite.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "dot")
    val dots = "等待扫描" + ".".repeat((offset.toInt() % 3) + 1)
    Text(dots, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BoundView(
    binding: BindingInfo?,
    statusRefreshFailed: Boolean,
    restarting: Boolean,
    onRestart: () -> Unit,
    onUnbind: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val connectionStatus = if (statusRefreshFailed) {
        "unknown"
    } else {
        binding?.connectionStatus ?: binding?.worker_status ?: "starting"
    }
    val (statusText, statusColor) = when (connectionStatus) {
        "running" -> "微信在线 · 实时同步" to Color(0xFF10B981)
        "starting" -> "正在建立微信连接" to MiaoPurple
        "reconnecting" -> "连接中断 · 正在自动重连" to Color(0xFFF59E0B)
        "expired" -> "微信登录已失效" to MaterialTheme.colorScheme.error
        "offline", "stopped" -> "微信服务离线" to MaterialTheme.colorScheme.error
        else -> "连接状态暂时无法同步" to Color(0xFFF59E0B)
    }

    LiquidGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🐾", fontSize = 40.sp)
            Text("已绑定微信 ✅", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF10B981))
            Text("在微信中直接与喵喵聊天吧～", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(statusText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = statusColor)
            binding?.heartbeatAgeSeconds?.let { age ->
                Text(
                    "最近心跳：${formatWechatAge(age)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            binding?.lastMessageAgeSeconds?.let { age ->
                Text(
                    "最近收到微信消息：${formatWechatAge(age)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            binding?.lastDeliveryAgeSeconds?.let { age ->
                Text(
                    "最近成功回复微信：${formatWechatAge(age)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if ((binding?.pendingDeliveryCount ?: 0) > 0) {
                Text(
                    "${binding?.pendingDeliveryCount} 条回复待投递，系统会持续重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF59E0B),
                    textAlign = TextAlign.Center,
                )
            }
            if ((binding?.failedDeliveryCount ?: 0) > 0) {
                Text(
                    "${binding?.failedDeliveryCount} 条回复已降级或投递失败，请在 App 中查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            if (connectionStatus == "reconnecting" && (binding?.consecutiveFailures ?: 0) > 0) {
                Text(
                    "已自动重连 ${binding?.consecutiveFailures} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF59E0B),
                )
            }
            if (connectionStatus == "expired") {
                Text(
                    "请解除绑定后重新扫码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (connectionStatus != "running") {
                TextButton(onClick = onRestart, enabled = !restarting) {
                    if (restarting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("立即重新连接")
                    }
                }
            }
        }
    }

    Button(
        onClick = { showConfirm = true },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) { Text("解除绑定") }

    if (showConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("解除微信绑定") },
            text = { Text("解绑后将无法在微信中使用喵喵，确认解绑吗？") },
            confirmButton = {
                Button(onClick = { showConfirm = false; onUnbind() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("解绑") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("取消") } },
        )
    }
}

private fun formatWechatAge(seconds: Int): String = when {
    seconds < 5 -> "刚刚"
    seconds < 60 -> "${seconds} 秒前"
    seconds < 3600 -> "${seconds / 60} 分钟前"
    else -> "${seconds / 3600} 小时前"
}
