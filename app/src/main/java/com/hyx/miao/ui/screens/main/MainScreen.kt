package com.hyx.miao.ui.screens.main

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyx.miao.BuildConfig
import com.hyx.miao.ui.components.MiaoFloatingNavigationBar
import com.hyx.miao.ui.components.MiaoNavigationItem
import com.hyx.miao.ui.screens.conversations.ConversationListScreen
import com.hyx.miao.ui.screens.memory.MemoryScreen
import com.hyx.miao.ui.screens.profile.ProfileScreen
import com.hyx.miao.ui.theme.MiaoSize

@Composable
fun MainScreen(
    onNavigateToChat: (String) -> Unit,
    onNavigateToWechat: () -> Unit = {},
    onNavigateToModelSettings: () -> Unit = {},
    onNavigateToPersonaSettings: () -> Unit = {},
    onNavigateToAppearanceSettings: () -> Unit = {},
    onNavigateToGeneralSettings: () -> Unit = {},
    onLogout: () -> Unit = {},
    startupViewModel: AppStartupViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val startupState by startupViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        startupViewModel.refresh()
    }

    val tabs = listOf(
        MiaoNavigationItem(
            label = "消息",
            selectedIcon = Icons.Default.ChatBubble,
            unselectedIcon = Icons.Default.ChatBubbleOutline,
        ),
        MiaoNavigationItem(
            label = "记忆",
            selectedIcon = Icons.Default.ViewInAr,
            unselectedIcon = Icons.Default.ViewInAr,
        ),
        MiaoNavigationItem(
            label = "我的",
            selectedIcon = Icons.Default.Person,
            unselectedIcon = Icons.Default.PersonOutline,
        ),
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                MiaoFloatingNavigationBar(
                    items = tabs,
                    selectedIndex = selectedTab,
                    onItemSelected = { selectedTab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MiaoSize.FloatingNavigationHeight),
                )
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> ConversationListScreen(
                onNavigateToChat = onNavigateToChat,
                modifier = Modifier.padding(innerPadding),
            )
            1 -> MemoryScreen(modifier = Modifier.padding(innerPadding))
            2 -> ProfileScreen(
                modifier = Modifier.padding(innerPadding),
                onNavigateToWechat = onNavigateToWechat,
                onNavigateToModelSettings = onNavigateToModelSettings,
                onNavigateToPersonaSettings = onNavigateToPersonaSettings,
                onNavigateToAppearanceSettings = onNavigateToAppearanceSettings,
                onNavigateToGeneralSettings = onNavigateToGeneralSettings,
                onLogout = onLogout,
            )
        }
    }

    startupState.update?.let { version ->
        AppUpdateDialog(
            version = version,
            currentVersionCode = BuildConfig.VERSION_CODE,
            onDownload = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                }
            },
            onPostpone = startupViewModel::postponeUpdate,
        )
    } ?: startupState.announcements.firstOrNull()?.let { announcement ->
        AnnouncementDialog(
            announcement = announcement,
            onRead = { startupViewModel.markAnnouncementRead(announcement.id) },
        )
    }
}
