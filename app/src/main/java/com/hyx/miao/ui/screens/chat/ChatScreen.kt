package com.hyx.miao.ui.screens.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.google.gson.Gson
import com.hyx.miao.R
import com.hyx.miao.data.local.entity.EmojiEntity
import com.hyx.miao.data.local.entity.MessageEntity
import com.hyx.miao.data.remote.api.ModelDto
import com.hyx.miao.ui.components.ActionHintBar
import com.hyx.miao.ui.components.LiquidGlassCard
import com.hyx.miao.ui.components.MiaoSurfaceStyle
import com.hyx.miao.ui.components.PawLoadingIndicator
import com.hyx.miao.ui.theme.ChatUserBubbleDark
import com.hyx.miao.ui.theme.ChatUserBubbleLight
import com.hyx.miao.ui.theme.LocalMiaoExtraColors
import com.hyx.miao.ui.theme.MiaoPurple
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val emotionKaomojiMap = mapOf(
    "happy" to "(≧▽≦)", "excited" to "✧(≖ ◡ ≖✿)", "curious" to "(・∀・)?",
    "shy" to "(⁄ ⁄•⁄ω⁄•⁄ ⁄)", "embarrassed" to "(*/ω＼*)", "caring" to "(づ｡◕‿‿◕｡)づ",
    "gentle" to "(´▽`ʃ♡ƪ)", "playful" to "(￣▽￣)ノ", "thinking" to "(・へ・)",
    "surprised" to "Σ(っ °Д °;)っ", "sad" to "(；′⌒`)", "nervous" to "(⊙﹏⊙)",
    "proud" to "(￣︶￣)↗", "sleepy" to "(-_-) zzz", "angry" to "(╬▔皿▔)╯",
)

private data class ImagePreviewState(
    val urls: List<String>,
    val initialIndex: Int,
)

private val LocalUserAvatarUrl = staticCompositionLocalOf<String?> { null }
private val LocalAiAvatarUrl = staticCompositionLocalOf<String?> { null }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val emojis by viewModel.emojis.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val chatBackground = Color.Transparent
    val toolbarColor = LocalMiaoExtraColors.current.glassStrong

    var inputText by remember { mutableStateOf("") }
    var selectedImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showExpandPanel by remember { mutableStateOf(false) }
    var showEmojiPanel by remember { mutableStateOf(false) }
    var imagePreview by remember { mutableStateOf<ImagePreviewState?>(null) }
    var longPressMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var memoryExpanded by remember { mutableStateOf(false) }
    var showConversationSettings by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { selectedImageUris = it }
    val userAvatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.updateAvatar(isUser = true, uri = it) } }
    val aiAvatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.updateAvatar(isUser = false, uri = it) } }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                inputText = listOf(inputText.trim(), it.trim()).filter(String::isNotBlank).joinToString(" ")
            }
        }
    }
    val launchVoiceInput: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出要发送的消息")
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("设备未安装语音识别服务") }
        }
    }

    LaunchedEffect(conversationId) { viewModel.init(conversationId) }
    // Keep following the conversation only while the user is already near the
    // bottom. Chunk the streaming key to avoid scheduling an animation for
    // every token and never drag a reader away from older messages.
    LaunchedEffect(messages.size, uiState.streamingText.length / 80, uiState.isGenerating) {
        val count = messages.size + if (uiState.isGenerating) 1 else 0
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: (count - 1)
        if (count > 0 && lastVisible >= count - 3) listState.scrollToItem(count - 1)
    }
    LaunchedEffect(uiState.notice) {
        uiState.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.clearNotice()
        }
    }
    val currentModelName = uiState.availableModels
        .firstOrNull { it.modelId == uiState.modelId }
        ?.displayName
        ?: uiState.modelId

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ChatHeaderAvatar(uiState.aiAvatarUrl)
                        Column {
                            Text(
                                uiState.conversationTitle.ifBlank { "喵喵" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    "在线",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    currentModelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                RoundedCornerShape(18.dp),
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (uiState.dailyQuota > 0) "${uiState.quotaRemaining} / ${uiState.dailyQuota}" else "配额 —",
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                uiState.quotaExhausted -> MaterialTheme.colorScheme.error
                                uiState.quotaRemaining <= 10 && uiState.dailyQuota > 0 -> Color(0xFF925200)
                                else -> MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                        )
                    }
                    IconButton(
                        onClick = { showConversationSettings = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.MoreHoriz, "会话设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = toolbarColor,
                    scrolledContainerColor = toolbarColor,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = chatBackground,
    ) { innerPadding ->
        CompositionLocalProvider(
            LocalUserAvatarUrl provides uiState.userAvatarUrl,
            LocalAiAvatarUrl provides uiState.aiAvatarUrl,
        ) {
        Column(Modifier.padding(innerPadding).fillMaxSize().imePadding()) {
            if (uiState.quotaExhausted) {
                Text(
                    "今日配额已用完，仍可查看和管理历史消息",
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (uiState.contextClearNext) {
                Text(
                    "下一条消息不会携带此前上下文",
                    modifier = Modifier.fillMaxWidth().background(MiaoPurple.copy(alpha = 0.1f)).padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MiaoPurple,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val previous = messages.getOrNull(index - 1)
                    if (previous == null || shouldShowTime(previous.createdAt, message.createdAt)) {
                        TimeSeparator(message.createdAt)
                    }
                    MessageItem(
                        message = message,
                        onLongPress = { longPressMessage = message },
                        onImageClick = { urls, index -> imagePreview = ImagePreviewState(urls, index) },
                        onRetry = { viewModel.retry(message) },
                    )
                }
                if (uiState.isGenerating) {
                    if (uiState.memories.isNotEmpty()) {
                        item("memory") {
                            MemoryNotice(
                                count = uiState.memories.size,
                                summaries = uiState.memories.map { it.summary },
                                expanded = memoryExpanded,
                                onToggle = { memoryExpanded = !memoryExpanded },
                            )
                        }
                    }
                    item("streaming") {
                        if (uiState.streamingText.isBlank() && uiState.imageGenerationStatus == null) {
                            ThinkingBubble()
                        } else if (uiState.streamingText.isNotBlank()) AiBubbleRow(
                            content = uiState.streamingText,
                            kaomoji = uiState.currentEmotion?.let(emotionKaomojiMap::get),
                            isStreaming = true,
                            onLongPress = {},
                        )
                    }
                    uiState.imageGenerationStatus?.let { status ->
                        item("image_generation_status") { ImageGenerationBubble(status) }
                    }
                    uiState.pendingEmojiUrl?.let { url ->
                        item("pending_emoji") {
                            AiEmojiFromUrl(url) { imagePreview = ImagePreviewState(listOf(url), 0) }
                        }
                    }
                }
                item("bottom_space") { Spacer(Modifier.height(4.dp)) }
            }

            if (selectedImageUris.isNotEmpty()) {
                SelectedImages(selectedImageUris) { uri -> selectedImageUris = selectedImageUris - uri }
            }

            ChatInputBar(
                text = inputText,
                isGenerating = uiState.isGenerating,
                isUploading = uiState.isUploading,
                quotaExhausted = uiState.quotaExhausted,
                hasImages = selectedImageUris.isNotEmpty(),
                sendOnEnter = uiState.sendOnEnter,
                onTextChange = { inputText = it },
                onSend = {
                    if (uiState.hapticFeedback) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    viewModel.sendMessage(inputText, selectedImageUris)
                    inputText = ""
                    selectedImageUris = emptyList()
                },
                onStop = viewModel::stopGeneration,
                onExpandClick = { showExpandPanel = true },
                onEmojiClick = { showEmojiPanel = true },
                onVoiceClick = launchVoiceInput,
            )
        }
        }
    }

    if (showExpandPanel) {
        ExpandPanel(
            onImagePick = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                showExpandPanel = false
            },
            onVoiceInput = { launchVoiceInput(); showExpandPanel = false },
            onClearContext = {
                viewModel.clearContextOnNextMessage()
                showExpandPanel = false
            },
            onDismiss = { showExpandPanel = false },
        )
    }
    if (showEmojiPanel) {
        EmojiPanel(
            emojis = emojis,
            onSelect = {
                viewModel.sendEmoji(it)
                showEmojiPanel = false
            },
            onDismiss = { showEmojiPanel = false },
        )
    }
    if (showConversationSettings) {
        ConversationSettingsSheet(
            models = uiState.availableModels,
            selectedModelId = uiState.modelId,
            userAvatarUrl = uiState.userAvatarUrl,
            aiAvatarUrl = uiState.aiAvatarUrl,
            modelsLoading = uiState.modelsLoading,
            isChangingModel = uiState.isChangingModel,
            avatarUploading = uiState.avatarUploading,
            onSelectModel = viewModel::changeModel,
            onRefreshModels = viewModel::refreshModels,
            onPickUserAvatar = {
                userAvatarPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickAiAvatar = {
                aiAvatarPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onClearUserAvatar = { viewModel.updateAvatar(isUser = true, uri = null) },
            onClearAiAvatar = { viewModel.updateAvatar(isUser = false, uri = null) },
            onDismiss = { showConversationSettings = false },
        )
    }

    imagePreview?.let { preview ->
        ImagePreviewDialog(preview = preview, onDismiss = { imagePreview = null })
    }

    longPressMessage?.let { message ->
        MessageActionDialog(
            message = message,
            onDismiss = { longPressMessage = null },
            onCopy = {
                clipboard.setText(AnnotatedString(message.content))
                longPressMessage = null
            },
            onRegenerate = {
                viewModel.regenerate(message)
                longPressMessage = null
            },
            onMemory = {
                viewModel.addToMemory(message)
                longPressMessage = null
            },
            onRecall = {
                viewModel.recallMessage(message)
                longPressMessage = null
            },
            onDelete = {
                viewModel.deleteMessage(message)
                longPressMessage = null
            },
        )
    }
}

@Composable
private fun ChatHeaderAvatar(url: String?) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, LocalMiaoExtraColors.current.glassBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable._1de21655_6f0c_4b06_9b54_3101c192145f),
            contentDescription = "喵喵头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = "AI 头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSettingsSheet(
    models: List<ModelDto>,
    selectedModelId: String,
    userAvatarUrl: String?,
    aiAvatarUrl: String?,
    modelsLoading: Boolean,
    isChangingModel: Boolean,
    avatarUploading: String?,
    onSelectModel: (ModelDto) -> Unit,
    onRefreshModels: () -> Unit,
    onPickUserAvatar: () -> Unit,
    onPickAiAvatar: () -> Unit,
    onClearUserAvatar: () -> Unit,
    onClearAiAvatar: () -> Unit,
    onDismiss: () -> Unit,
) {
    val wechatGreen = Color(0xFF07C160)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("settings_header") {
                Text("聊天设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "模型和头像只应用于当前会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp, bottom = 16.dp),
                )
            }
            item("avatars") {
                Text(
                    "聊天头像",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AvatarSettingCard(
                        label = "我的头像",
                        avatarUrl = userAvatarUrl,
                        fallback = "我",
                        uploading = avatarUploading == "user",
                        enabled = avatarUploading == null,
                        onPick = onPickUserAvatar,
                        onClear = onClearUserAvatar,
                        modifier = Modifier.weight(1f),
                    )
                    AvatarSettingCard(
                        label = "AI 头像",
                        avatarUrl = aiAvatarUrl,
                        fallback = "喵",
                        uploading = avatarUploading == "ai",
                        enabled = avatarUploading == null,
                        onPick = onPickAiAvatar,
                        onClear = onClearAiAvatar,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item("model_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 3.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("当前模型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "切换后，下一条消息立即使用新模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefreshModels, enabled = !modelsLoading) {
                        if (modelsLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, "刷新模型")
                        }
                    }
                }
            }
            if (models.isEmpty() && modelsLoading) {
                item("models_loading") {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (models.isEmpty()) {
                item("models_empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("模型列表暂时不可用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onRefreshModels) { Text("重新加载") }
                    }
                }
            } else {
                items(models, key = { "model_${it.modelId}" }) { model ->
                    val selected = model.modelId == selectedModelId
                    val enabled = model.isEnabled && !isChangingModel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (selected) Modifier.border(1.dp, wechatGreen, RoundedCornerShape(10.dp))
                                else Modifier
                            )
                            .background(
                                if (selected) wechatGreen.copy(alpha = 0.08f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                            .clickable(enabled = enabled) { onSelectModel(model) }
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    model.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (model.isEnabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (model.supportsVision) ModelTag("图片", wechatGreen)
                                if (!model.isEnabled) ModelTag("已停用", MaterialTheme.colorScheme.error)
                            }
                            Text(
                                listOf(model.provider, model.description).filter(String::isNotBlank).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (selected) {
                            if (isChangingModel) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = wechatGreen)
                            } else {
                                Icon(Icons.Default.Check, "已选择", tint = wechatGreen, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarSettingCard(
    label: String,
    avatarUrl: String?,
    fallback: String,
    uploading: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .clickable(enabled = enabled) { onPick() }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (fallback == "我") Color(0xFF6B7B86) else MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (fallback == "我") {
                    Text(fallback, color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Image(
                        painter = painterResource(R.drawable._1de21655_6f0c_4b06_9b54_3101c192145f),
                        contentDescription = "默认 AI 头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (!avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (uploading) {
                Box(
                    Modifier.size(58.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 7.dp))
        if (avatarUrl.isNullOrBlank()) {
            Text("点击从相册选择", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(onClick = onClear, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("恢复默认", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ModelTag(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 7.dp).clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.1f)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun MessageItem(
    message: MessageEntity,
    onLongPress: () -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        message.role == "system" || message.contentType == "system" || message.isRecalled ->
            SystemMessage(message.content)
        message.role == "user" -> UserBubble(message, onLongPress, onImageClick)
        message.isError -> ChatErrorBubble(message, onLongPress, onRetry)
        message.contentType == "image" -> AiImageBubble(message, onLongPress, onImageClick)
        message.contentType == "emoji" -> decodeUrls(message.mediaUrls).firstOrNull()?.let { url ->
            AiEmojiFromUrl(url) { onImageClick(listOf(url), 0) }
        }
        else -> {
            AiBubbleRow(
                content = message.content,
                kaomoji = message.emotion?.let(emotionKaomojiMap::get),
                isStreaming = false,
                onLongPress = onLongPress,
            )
            if (!message.actionText.isNullOrBlank()) ActionHintBar(message.actionText, Modifier.fillMaxWidth())
            if (message.source == "wechat") SourceLabel("来自微信")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiImageBubble(
    message: MessageEntity,
    onLongPress: () -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
) {
    val urls = decodeUrls(message.mediaUrls)
    if (urls.isEmpty()) {
        AiBubbleRow(
            content = message.content,
            kaomoji = message.emotion?.let(emotionKaomojiMap::get),
            isStreaming = false,
            onLongPress = onLongPress,
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar()
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.widthIn(max = 292.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ImageGrid(
                urls = urls,
                onImageClick = onImageClick,
                horizontalAlignment = Alignment.Start,
                onLongPress = onLongPress,
            )
            if (message.content.isNotBlank()) {
                Card(
                    modifier = Modifier.combinedClickable(
                        onClick = { Unit },
                        onLongClick = onLongPress,
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = LocalMiaoExtraColors.current.card,
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    MarkdownText(
                        content = message.content,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    )
                }
            }
            if (message.source == "wechat") SourceLabel("来自微信")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(message: MessageEntity, onLongPress: () -> Unit, onImageClick: (List<String>, Int) -> Unit) {
    val urls = decodeUrls(message.mediaUrls)
    val isDark = MaterialTheme.colorScheme.background.red < 0.25f
    val bubbleColor = if (isDark) ChatUserBubbleDark else ChatUserBubbleLight
    val bubbleShape = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 292.dp)) {
            if (urls.isNotEmpty()) ImageGrid(urls, onImageClick)
            if (message.content.isNotBlank() && (message.contentType != "emoji" || urls.isEmpty())) {
                Box {
                    BubbleTail(bubbleColor, isUser = true)
                    Box(
                        modifier = Modifier.shadow(2.dp, bubbleShape, ambientColor = Color.Black.copy(alpha = 0.08f))
                            .clip(bubbleShape)
                            .background(bubbleColor)
                            .combinedClickable(onClick = { Unit }, onLongClick = onLongPress)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            message.content,
                            color = if (isDark) Color.White else Color(0xFF181818),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (message.source == "wechat") SourceLabel("来自微信")
                if (!message.isSynced) Text("待同步", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Avatar(isUser = true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGrid(
    urls: List<String>,
    onImageClick: (List<String>, Int) -> Unit,
    horizontalAlignment: Alignment.Horizontal = Alignment.End,
    onLongPress: (() -> Unit)? = null,
) {
    val columns = if (urls.size <= 4) 2 else 3
    Column(horizontalAlignment = horizontalAlignment, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        urls.chunked(columns).forEach { rowUrls ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                rowUrls.forEach { url ->
                    val index = urls.indexOf(url)
                    val interactionModifier = if (onLongPress == null) {
                        Modifier.clickable { onImageClick(urls, index) }
                    } else {
                        Modifier.combinedClickable(
                            onClick = { onImageClick(urls, index) },
                            onLongClick = onLongPress,
                        )
                    }
                    AsyncImage(
                        model = url,
                        contentDescription = "消息图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(if (urls.size == 1) 190.dp else 92.dp)
                            .clip(RoundedCornerShape(10.dp)).then(interactionModifier),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImagePreviewDialog(preview: ImagePreviewState, onDismiss: () -> Unit) {
    val initialPage = preview.initialIndex.coerceIn(0, preview.urls.lastIndex)
    val pagerState = rememberPagerState(initialPage = initialPage) { preview.urls.size }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ZoomablePreviewImage(preview.urls[page])
            }
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${preview.urls.size}",
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "关闭图片预览", tint = Color.White)
                }
            }
            Text(
                "双击或双指缩放，左右滑动切换",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun ZoomablePreviewImage(url: String) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        offset = if (newScale == 1f) Offset.Zero else offset + panChange
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
            contentDescription = "图片预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(vertical = 56.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .transformable(transformState)
                .pointerInput(url) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatErrorBubble(message: MessageEntity, onLongPress: () -> Unit, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Avatar(error = true)
        Spacer(Modifier.width(10.dp))
        Card(
        modifier = Modifier.widthIn(max = 292.dp).combinedClickable(onClick = { Unit }, onLongClick = onLongPress),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(message.content, color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重试")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiBubbleRow(content: String, kaomoji: String?, isStreaming: Boolean, onLongPress: () -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.25f
    val bubbleColor = if (isDark) Color(0xFF252527) else LocalMiaoExtraColors.current.card
    val bubbleShape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(46.dp)) {
            Avatar()
            kaomoji?.let { Text(it, fontSize = 9.sp, maxLines = 1) }
        }
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.widthIn(max = 292.dp)) {
            BubbleTail(bubbleColor, isUser = false)
            Row(
                modifier = Modifier.combinedClickable(onClick = { Unit }, onLongClick = onLongPress)
                    .shadow(2.dp, bubbleShape, ambientColor = Color.Black.copy(alpha = 0.08f))
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f), bubbleShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                MarkdownText(content, Modifier.weight(1f, fill = false))
                if (isStreaming) StreamingCursor()
            }
        }
    }
}

@Composable
private fun MarkdownText(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) { parseMarkdownBlocks(content) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            if (block.isCode) {
                CodeBlock(block.language, block.content)
            } else if (block.content.isNotBlank()) {
                MarkdownBody(block.content, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MarkdownBody(content: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MiaoPurple.toArgb()
    val markwon = remember(context) { Markwon.create(context) }
    AndroidView(
        factory = {
            TextView(it).apply {
                setTextColor(color)
                setLinkTextColor(linkColor)
                textSize = 16f
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            view.setTextColor(color)
            view.setLinkTextColor(linkColor)
            markwon.setMarkdown(view, content)
        },
        modifier = modifier,
    )
}

private data class MarkdownBlock(
    val content: String,
    val language: String? = null,
    val isCode: Boolean = false,
)

private fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val pattern = Regex("```([^\\n`]*)\\n([\\s\\S]*?)```")
    val blocks = mutableListOf<MarkdownBlock>()
    var cursor = 0
    pattern.findAll(content).forEach { match ->
        if (match.range.first > cursor) {
            blocks += MarkdownBlock(content.substring(cursor, match.range.first))
        }
        blocks += MarkdownBlock(
            content = match.groupValues[2].trimEnd(),
            language = match.groupValues[1].trim().ifBlank { null },
            isCode = true,
        )
        cursor = match.range.last + 1
    }
    if (cursor < content.length) blocks += MarkdownBlock(content.substring(cursor))
    return blocks.ifEmpty { listOf(MarkdownBlock(content)) }
}

@Composable
private fun CodeBlock(language: String?, code: String) {
    val clipboard = LocalClipboardManager.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    language ?: "代码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, "复制代码", modifier = Modifier.size(17.dp))
                }
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun Avatar(error: Boolean = false, isUser: Boolean = false) {
    val avatarUrl = if (isUser) LocalUserAvatarUrl.current else LocalAiAvatarUrl.current
    val avatarColor = when {
        isUser -> Color(0xFF6B7B86)
        else -> MaterialTheme.colorScheme.primary
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(42.dp).clip(shape).background(avatarColor)
            .border(1.dp, LocalMiaoExtraColors.current.glassBorder, shape),
    ) {
        if (isUser) {
            Text("我", color = Color.White, fontWeight = FontWeight.Bold)
        } else {
            Image(
                painter = painterResource(R.drawable._1de21655_6f0c_4b06_9b54_3101c192145f),
                contentDescription = "喵喵头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = if (isUser) "我的头像" else "AI 头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (error) {
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Text("!", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BoxScope.BubbleTail(color: Color, isUser: Boolean) {
    Canvas(
        modifier = Modifier
            .size(width = 8.dp, height = 12.dp)
            .align(if (isUser) Alignment.TopEnd else Alignment.TopStart)
            .offset(x = if (isUser) 6.dp else (-6).dp, y = 11.dp),
    ) {
        val path = Path().apply {
            if (isUser) {
                moveTo(0f, 0f)
                lineTo(size.width, size.height * 0.45f)
                lineTo(0f, size.height)
            } else {
                moveTo(size.width, 0f)
                lineTo(0f, size.height * 0.45f)
                lineTo(size.width, size.height)
            }
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun ThinkingBubble() {
    val bubbleColor = if (MaterialTheme.colorScheme.background.red < 0.25f) Color(0xFF252527) else LocalMiaoExtraColors.current.card
    val bubbleShape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Avatar()
        Spacer(Modifier.width(10.dp))
        Box {
            BubbleTail(bubbleColor, isUser = false)
            Row(
                Modifier.shadow(2.dp, bubbleShape, ambientColor = Color.Black.copy(alpha = 0.08f))
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f), bubbleShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PawLoadingIndicator()
                Text("喵喵正在思考…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ImageGenerationBubble(message: String) {
    val bubbleColor = if (MaterialTheme.colorScheme.background.red < 0.25f) {
        Color(0xFF252527)
    } else {
        LocalMiaoExtraColors.current.card
    }
    val bubbleShape = RoundedCornerShape(
        topStart = 6.dp,
        topEnd = 20.dp,
        bottomEnd = 20.dp,
        bottomStart = 20.dp,
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Avatar()
        Spacer(Modifier.width(10.dp))
        Box {
            BubbleTail(bubbleColor, isUser = false)
            Row(
                modifier = Modifier
                    .shadow(2.dp, bubbleShape, ambientColor = Color.Black.copy(alpha = 0.08f))
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    .border(
                        0.6.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                        bubbleShape,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PawLoadingIndicator()
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AiEmojiFromUrl(url: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(54.dp))
        AsyncImage(
            model = url,
            contentDescription = "表情",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        )
    }
}

@Composable
private fun StreamingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursor_alpha",
    )
    Text("▌", color = MiaoPurple.copy(alpha = alpha))
}

@Composable
private fun TimeSeparator(timestamp: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(formatMessageTime(timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SystemMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        )
    }
}

@Composable
private fun SourceLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MemoryNotice(count: Int, summaries: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(54.dp))
        Card(
            modifier = Modifier.widthIn(max = 292.dp).clickable(onClick = onToggle),
            colors = CardDefaults.cardColors(containerColor = MiaoPurple.copy(alpha = 0.08f)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Default.Psychology, null, tint = MiaoPurple, modifier = Modifier.size(18.dp))
                    Text("已参考 $count 条相关记忆", color = MiaoPurple, style = MaterialTheme.typography.bodySmall)
                }
                if (expanded) summaries.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SelectedImages(uris: List<Uri>, onRemove: (Uri) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(uris, key = { it.toString() }) { uri ->
            Box(Modifier.size(84.dp)) {
                AsyncImage(
                    uri,
                    "待发送图片",
                    Modifier.size(72.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
                IconButton(
                    onClick = { onRemove(uri) },
                    modifier = Modifier.size(48.dp).align(Alignment.TopEnd),
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.58f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, "移除", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    isGenerating: Boolean,
    isUploading: Boolean,
    quotaExhausted: Boolean,
    hasImages: Boolean,
    sendOnEnter: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onExpandClick: () -> Unit,
    onEmojiClick: () -> Unit,
    onVoiceClick: () -> Unit,
) {
    val enabled = !quotaExhausted && !isUploading
    val fieldColor = LocalMiaoExtraColors.current.glass
    Box(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        LiquidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 30.dp,
            style = MiaoSurfaceStyle.GlassStrong,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = onVoiceClick,
                    enabled = enabled && !isGenerating,
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Default.Mic, "语音输入") }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = enabled && !isGenerating,
                    placeholder = {
                        Text(
                            if (quotaExhausted) "今日配额已用完" else "想和喵喵说点什么…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { if (enabled && !isGenerating && (text.isNotBlank() || hasImages)) onSend() },
                    ),
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                        disabledBorderColor = Color.Transparent,
                        focusedContainerColor = fieldColor,
                        unfocusedContainerColor = fieldColor,
                        disabledContainerColor = fieldColor.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 132.dp),
                )
                IconButton(
                    onClick = onEmojiClick,
                    enabled = enabled && !isGenerating,
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Default.SentimentSatisfiedAlt, "表情") }
                when {
                    isUploading -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    isGenerating -> IconButton(
                        onClick = onStop,
                        modifier = Modifier.size(48.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Icon(Icons.Default.Stop, "停止生成", tint = MaterialTheme.colorScheme.primary)
                    }
                    enabled && (text.isNotBlank() || hasImages) -> IconButton(
                        onClick = onSend,
                        modifier = Modifier.size(48.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    else -> IconButton(
                        onClick = onExpandClick,
                        enabled = enabled,
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Default.Add, "更多") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandPanel(
    onImagePick: () -> Unit,
    onVoiceInput: () -> Unit,
    onClearContext: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = LocalMiaoExtraColors.current.glassStrong,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ExpandAction(Icons.Default.Image, "图片", onImagePick)
            ExpandAction(Icons.Default.Mic, "语音输入", onVoiceInput)
            ExpandAction(Icons.Default.Refresh, "清空上下文", onClearContext)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExpandAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MiaoPurple.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = MiaoPurple)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun EmojiPanel(emojis: List<EmojiEntity>, onSelect: (EmojiEntity) -> Unit, onDismiss: () -> Unit) {
    val categories = remember(emojis) {
        listOf("全部") + emojis.map { it.emotionTag.trim() }.filter(String::isNotBlank).distinct().sorted()
    }
    var selectedCategory by remember { mutableStateOf("全部") }
    var previewEmoji by remember { mutableStateOf<EmojiEntity?>(null) }
    LaunchedEffect(categories) {
        if (selectedCategory !in categories) selectedCategory = "全部"
    }
    val visibleEmojis = remember(emojis, selectedCategory) {
        if (selectedCategory == "全部") emojis else emojis.filter { it.emotionTag == selectedCategory }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = LocalMiaoExtraColors.current.glassStrong,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Text("选择表情", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
        if (emojis.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text("暂无可用表情，请稍后同步", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ScrollableTabRow(
                selectedTabIndex = categories.indexOf(selectedCategory).coerceAtLeast(0),
                edgePadding = 12.dp,
                divider = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                categories.forEach { category ->
                    Tab(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        text = { Text(category, maxLines = 1) },
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().height(290.dp).padding(12.dp),
            ) {
                gridItems(visibleEmojis, key = { it.id }) { emoji ->
                    Column(
                        modifier = Modifier.padding(5.dp).clip(RoundedCornerShape(10.dp))
                            .combinedClickable(
                                onClick = { onSelect(emoji) },
                                onLongClick = { previewEmoji = emoji },
                            ).padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AsyncImage(emoji.thumbUrl.ifBlank { emoji.url }, emoji.description, Modifier.size(64.dp), contentScale = ContentScale.Fit)
                        Text(emoji.emotionTag, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
    previewEmoji?.let { emoji ->
        AlertDialog(
            onDismissRequest = { previewEmoji = null },
            title = { Text(emoji.emotionTag.ifBlank { "表情预览" }) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = emoji.url,
                        contentDescription = emoji.description,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                    if (emoji.description.isNotBlank()) {
                        Text(
                            emoji.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    previewEmoji = null
                    onSelect(emoji)
                }) { Text("发送") }
            },
            dismissButton = {
                TextButton(onClick = { previewEmoji = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun MessageActionDialog(
    message: MessageEntity,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onMemory: () -> Unit,
    onRecall: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息操作") },
        text = {
            Column {
                if (message.content.isNotBlank()) ActionButton(Icons.Default.ContentCopy, "复制文本", onCopy)
                if (
                    message.role == "assistant" &&
                    !message.isError &&
                    message.contentType != "image"
                ) {
                    ActionButton(Icons.Default.Refresh, "重新生成", onRegenerate)
                    ActionButton(Icons.Default.Psychology, "添加到记忆", onMemory)
                }
                if (message.role == "user" && System.currentTimeMillis() - message.createdAt <= 120_000L && message.serverId != null) {
                    ActionButton(Icons.Default.Refresh, "撤回消息", onRecall)
                }
                ActionButton(Icons.Default.Delete, "删除消息", onDelete, MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, modifier = Modifier.weight(1f))
    }
}

private fun decodeUrls(value: String?): List<String> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching { Gson().fromJson(value, Array<String>::class.java).toList() }
        .getOrElse { listOf(value) }.filter(String::isNotBlank)
}

private fun shouldShowTime(previous: Long, current: Long): Boolean {
    if (current - previous >= 5 * 60_000L) return true
    val first = Calendar.getInstance().apply { timeInMillis = previous }
    val second = Calendar.getInstance().apply { timeInMillis = current }
    return first.get(Calendar.YEAR) != second.get(Calendar.YEAR) ||
        first.get(Calendar.DAY_OF_YEAR) != second.get(Calendar.DAY_OF_YEAR)
}

private fun formatMessageTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
