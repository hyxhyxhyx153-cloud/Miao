package com.hyx.miao.ui.screens.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyx.miao.data.local.entity.MemoryEntity
import com.hyx.miao.ui.components.LiquidGlassCard
import com.hyx.miao.ui.components.MiaoSurfaceStyle
import com.hyx.miao.ui.components.PawLoadingIndicator
import com.hyx.miao.ui.screens.auth.glassTextFieldColors
import com.hyx.miao.ui.theme.LocalMiaoExtraColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    modifier: Modifier = Modifier,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val extra = LocalMiaoExtraColors.current
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var searchQuery by remember { mutableStateOf("") }
    var showAddSheet by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<MemoryEntity?>(null) }

    val filtered = if (searchQuery.isBlank()) memories else uiState.searchResults.orEmpty()

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MemoryHeader(onHelp = { showHelp = true })
            MemorySearchField(
                query = searchQuery,
                onQueryChange = {
                    searchQuery = it
                    viewModel.search(it)
                },
                onClear = {
                    searchQuery = ""
                    viewModel.search("")
                },
            )
            MemorySummaryBar(
                count = filtered.size,
                isSearchResult = searchQuery.isNotBlank(),
                onAdd = { showAddSheet = true },
            )
            MemoryContent(
                modifier = Modifier.weight(1f),
                items = filtered,
                isLoading = uiState.isLoading && memories.isEmpty() && searchQuery.isBlank(),
                isSearching = uiState.isSearching && searchQuery.isNotBlank(),
                isSearchResult = searchQuery.isNotBlank(),
                onAdd = { showAddSheet = true },
                onEdit = { editTarget = it },
                onDelete = { deleteTarget = it },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }

    if (showAddSheet || editTarget != null) {
        MemoryEditSheet(
            initial = editTarget?.content.orEmpty(),
            title = if (editTarget != null) "编辑记忆" else "添加记忆",
            isLoading = uiState.isSaving,
            onConfirm = { content ->
                val target = editTarget
                if (target != null) {
                    viewModel.update(target.id, content) { editTarget = null }
                } else {
                    viewModel.add(content) { showAddSheet = false }
                }
            },
            onDismiss = {
                showAddSheet = false
                editTarget = null
            },
        )
    }

    deleteTarget?.let { memory ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除记忆？") },
            text = { Text("删除后无法恢复这条记忆，确定继续吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(memory.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("删除")
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

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("关于记忆") },
            text = {
                Text("记忆会保存你的偏好、计划和重要信息，用于让后续对话更贴合你。你可以随时新建、编辑或删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = { showHelp = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("知道了") }
            },
            containerColor = extra.glassStrong,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

@Composable
private fun MemoryHeader(onHelp: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "记忆",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onHelp, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "记忆说明")
        }
    }
}

@Composable
private fun MemorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("搜索记忆…") },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "清除搜索")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = glassTextFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(22.dp),
    )
}

@Composable
private fun MemorySummaryBar(
    count: Int,
    isSearchResult: Boolean,
    onAdd: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                shape,
            )
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isSearchResult) "找到 " else "共 ",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = " 条记忆",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onAdd,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text("新建", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MemoryContent(
    modifier: Modifier,
    items: List<MemoryEntity>,
    isLoading: Boolean,
    isSearching: Boolean,
    isSearchResult: Boolean,
    onAdd: () -> Unit,
    onEdit: (MemoryEntity) -> Unit,
    onDelete: (MemoryEntity) -> Unit,
) {
    LiquidGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        cornerRadius = 24.dp,
        style = MiaoSurfaceStyle.Content,
    ) {
        when {
            isLoading || isSearching -> {
                MemoryPlaceholder(isLoading = true, isSearchResult = isSearchResult, onAdd = onAdd)
            }

            items.isEmpty() -> {
                MemoryPlaceholder(isLoading = false, isSearchResult = isSearchResult, onAdd = onAdd)
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        MemoryRow(
                            item = item,
                            showDivider = index != items.lastIndex,
                            onEdit = onEdit,
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryPlaceholder(
    isLoading: Boolean,
    isSearchResult: Boolean,
    onAdd: () -> Unit,
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
                    imageVector = if (isSearchResult) Icons.Default.SearchOff else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = when {
                isLoading && isSearchResult -> "正在搜索记忆"
                isLoading -> "正在同步记忆"
                isSearchResult -> "没有找到相关记忆"
                else -> "还没有记忆"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                isLoading -> "稍等一下，很快就好"
                isSearchResult -> "换一个关键词试试"
                else -> "记录偏好和重要信息，让喵喵更懂你"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isLoading && !isSearchResult) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建第一条记忆")
            }
        }
    }
}

@Composable
private fun MemoryRow(
    item: MemoryEntity,
    showDivider: Boolean,
    onEdit: (MemoryEntity) -> Unit,
    onDelete: (MemoryEntity) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val formattedDate = remember(item.createdAt, locale) {
        formatMemoryDate(item.createdAt, locale)
    }
    val title = item.summary.ifBlank {
        if (item.source == "auto") "自动记忆" else "手动记忆"
    }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MemorySourceTag(isAuto = item.source == "auto")
                    Text(
                        text = " · ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "记忆操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            menuExpanded = false
                            onEdit(item)
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete(item)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = LocalMiaoExtraColors.current.divider,
            )
        }
    }
}

@Composable
private fun MemorySourceTag(isAuto: Boolean) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isAuto) 0.1f else 0.07f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (isAuto) "自动" else "手动",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryEditSheet(
    initial: String,
    title: String,
    isLoading: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val loadingState = rememberUpdatedState(isLoading)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            target != SheetValue.Hidden || !loadingState.value
        },
    )
    var text by remember(initial) { mutableStateOf(initial) }
    val focusManager = LocalFocusManager.current

    ModalBottomSheet(
        onDismissRequest = { if (!isLoading) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !isLoading,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = LocalMiaoExtraColors.current.glassStrong,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        scrimColor = Color.Black.copy(alpha = 0.28f),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "写下偏好、计划或希望喵喵长期记住的信息。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !isLoading,
                placeholder = { Text("例如：我喜欢少糖拿铁") },
                minLines = 4,
                maxLines = 7,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = glassTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = "保存",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

private fun formatMemoryDate(timestamp: Long, locale: Locale): String {
    if (timestamp <= 0L) return ""
    val now = Calendar.getInstance()
    val date = Calendar.getInstance().apply { timeInMillis = timestamp }
    val isToday = now.get(Calendar.ERA) == date.get(Calendar.ERA) &&
        now.get(Calendar.YEAR) == date.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR)
    val pattern = if (isToday) "'今天' HH:mm" else "MM/dd"
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}
