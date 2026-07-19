package com.hyx.miao.ui.screens.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.hyx.miao.R
import com.hyx.miao.data.local.entity.ConversationEntity
import com.hyx.miao.ui.components.LiquidGlassCard
import com.hyx.miao.ui.components.MiaoSurfaceStyle
import com.hyx.miao.ui.components.PawLoadingIndicator
import com.hyx.miao.ui.screens.auth.glassTextFieldColors
import com.hyx.miao.ui.theme.LocalMiaoExtraColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onNavigateToChat: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationListViewModel = hiltViewModel(),
) {
    val extra = LocalMiaoExtraColors.current
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showNewSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val filtered = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) {
            conversations
        } else {
            conversations.filter {
                it.title.contains(searchQuery, true) ||
                    it.lastMessagePreview.contains(searchQuery, true) ||
                    it.modelId.contains(searchQuery, true)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ConversationHeader(
                showSearch = showSearch,
                query = searchQuery,
                isRefreshing = isRefreshing,
                onQueryChange = { searchQuery = it },
                onRefresh = viewModel::refresh,
                onToggleSearch = {
                    showSearch = !showSearch
                    if (!showSearch) searchQuery = ""
                },
            )
        },
        floatingActionButton = {
            if (!showSearch) {
                FloatingActionButton(
                    onClick = { showNewSheet = true },
                    modifier = Modifier.size(60.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(22.dp),
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 3.dp,
                    ),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "新建会话")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Transparent,
    ) { contentPadding ->
        LiquidGlassCard(
            modifier = Modifier
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxSize(),
            cornerRadius = 24.dp,
            style = MiaoSurfaceStyle.Content,
        ) {
            when {
                isRefreshing && conversations.isEmpty() -> {
                    ConversationPlaceholder(isLoading = true, isSearchResult = false)
                }

                filtered.isEmpty() -> {
                    ConversationPlaceholder(
                        isLoading = false,
                        isSearchResult = searchQuery.isNotBlank(),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(filtered, key = { it.id }) { conversation ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        deleteTarget = conversation
                                    }
                                    false
                                },
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(horizontal = 22.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "删除会话",
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                },
                            ) {
                                ConversationRow(
                                    conversation = conversation,
                                    rowBackground = extra.card,
                                    onClick = { onNavigateToChat(conversation.id) },
                                    onLongClick = { viewModel.markRead(conversation.id) },
                                    onPinnedChange = {
                                        viewModel.setPinned(conversation.id, !conversation.isPinned)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewSheet) {
        NewConversationSheet(
            options = options,
            onDismiss = { showNewSheet = false },
            onConfirm = { model, persona, title ->
                viewModel.createConversation(model, persona, title) { id ->
                    showNewSheet = false
                    onNavigateToChat(id)
                }
            },
        )
    }

    deleteTarget?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话？") },
            text = { Text("“${conversation.title}”及其本地聊天记录将被删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(conversation.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("取消")
                }
            },
            containerColor = extra.glassStrong,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

@Composable
private fun ConversationHeader(
    showSearch: Boolean,
    query: String,
    isRefreshing: Boolean,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleSearch: () -> Unit,
) {
    val extra = LocalMiaoExtraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索会话或消息") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                colors = glassTextFieldColors(),
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            HeaderIconButton(
                onClick = onToggleSearch,
                contentDescription = "关闭搜索",
                containerColor = extra.glass,
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
        } else {
            Text(
                text = "消息",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            HeaderIconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                contentDescription = "同步会话",
                containerColor = extra.glass,
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            }
            HeaderIconButton(
                onClick = onToggleSearch,
                contentDescription = "搜索会话",
                containerColor = extra.glass,
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    containerColor: Color,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val extra = LocalMiaoExtraColors.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(0.8.dp, extra.glassBorder, RoundedCornerShape(16.dp))
            .semantics { this.contentDescription = contentDescription },
    ) {
        content()
    }
}

@Composable
private fun ConversationPlaceholder(
    isLoading: Boolean,
    isSearchResult: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                PawLoadingIndicator()
            } else {
                Icon(
                    imageVector = if (isSearchResult) Icons.Default.SearchOff else Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Text(
            text = when {
                isLoading -> "正在同步会话"
                isSearchResult -> "没有找到相关会话"
                else -> "还没有会话"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = when {
                isLoading -> "稍等一下，很快就好"
                isSearchResult -> "换一个关键词试试"
                else -> "点击右下角的按钮开始聊天"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ConversationEntity,
    rowBackground: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPinnedChange: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "标记已读",
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationAvatar(conversation)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatTime(conversation.lastMessageAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.lastMessagePreview.ifBlank { conversation.modelId },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (conversation.unreadCount > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = Color.White,
                        ) {
                            Text(conversation.unreadCount.coerceAtMost(99).toString())
                        }
                    }
                    IconButton(
                        onClick = onPinnedChange,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = if (conversation.isPinned) "取消置顶" else "置顶会话",
                            tint = if (conversation.isPinned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.56f)
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 86.dp, end = 16.dp),
            color = LocalMiaoExtraColors.current.divider,
        )
    }
}

@Composable
private fun ConversationAvatar(conversation: ConversationEntity) {
    val shape = RoundedCornerShape(18.dp)
    val modifier = Modifier.size(56.dp).clip(shape)
    when {
        !conversation.aiAvatarUrl.isNullOrBlank() -> {
            AsyncImage(
                model = conversation.aiAvatarUrl,
                contentDescription = "${conversation.title}头像",
                modifier = modifier,
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable._1de21655_6f0c_4b06_9b54_3101c192145f),
            )
        }

        conversation.isWechat -> {
            Box(
                modifier = modifier.background(Color(0xFFE3F5E8)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = Color(0xFF248A5B),
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        else -> {
            Image(
                painter = painterResource(R.drawable._1de21655_6f0c_4b06_9b54_3101c192145f),
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    val pattern = when {
        diff < 86_400_000L -> "HH:mm"
        diff < 7 * 86_400_000L -> "EEE"
        else -> "MM/dd"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}
