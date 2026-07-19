package com.hyx.miao.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.hyx.miao.ui.screens.auth.LoginScreen
import com.hyx.miao.ui.screens.auth.RegisterScreen
import com.hyx.miao.ui.screens.chat.ChatScreen
import com.hyx.miao.ui.screens.conversations.ConversationListScreen
import com.hyx.miao.ui.screens.main.MainScreen
import com.hyx.miao.ui.screens.profile.ProfileScreen
import com.hyx.miao.ui.theme.MiaoTheme

// ── Auth ─────────────────────────────────────────────────────────────────────

@Preview(name = "登录页 · 亮色", showBackground = true, showSystemUi = true)
@Composable
private fun LoginScreenPreview() {
    MiaoTheme(darkMode = "light") {
        LoginScreen(onLoginSuccess = {}, onNavigateToRegister = {})
    }
}

@Preview(name = "登录页 · 暗色", showBackground = true, showSystemUi = true)
@Composable
private fun LoginScreenDarkPreview() {
    MiaoTheme(darkMode = "dark") {
        LoginScreen(onLoginSuccess = {}, onNavigateToRegister = {})
    }
}

@Preview(name = "注册页 · 亮色", showBackground = true, showSystemUi = true)
@Composable
private fun RegisterScreenPreview() {
    MiaoTheme(darkMode = "light") {
        RegisterScreen(onRegisterSuccess = {}, onNavigateBack = {})
    }
}

// ── 会话列表 ──────────────────────────────────────────────────────────────────

@Preview(name = "会话列表 · 亮色", showBackground = true, showSystemUi = true)
@Composable
private fun ConversationListPreview() {
    MiaoTheme(darkMode = "light") {
        ConversationListScreen(onNavigateToChat = {})
    }
}

@Preview(name = "会话列表 · 暗色", showBackground = true, showSystemUi = true)
@Composable
private fun ConversationListDarkPreview() {
    MiaoTheme(darkMode = "dark") {
        ConversationListScreen(onNavigateToChat = {})
    }
}

// ── 主页（含底部导航）────────────────────────────────────────────────────────

@Preview(name = "主页 · 亮色", showBackground = true, showSystemUi = true)
@Composable
private fun MainScreenPreview() {
    MiaoTheme(darkMode = "light") {
        MainScreen(onNavigateToChat = {})
    }
}

// ── 聊天页 ────────────────────────────────────────────────────────────────────

@Preview(name = "聊天页 · 亮色", showBackground = true, showSystemUi = true)
@Composable
private fun ChatScreenPreview() {
    MiaoTheme(darkMode = "light") {
        ChatScreen(conversationId = "preview", onNavigateBack = {})
    }
}

@Preview(name = "聊天页 · 暗色", showBackground = true, showSystemUi = true)
@Composable
private fun ChatScreenDarkPreview() {
    MiaoTheme(darkMode = "dark") {
        ChatScreen(conversationId = "preview", onNavigateBack = {})
    }
}

// ── 我的页 ────────────────────────────────────────────────────────────────────

@Preview(name = "我的页 · 亮色", showBackground = true, showSystemUi = true)
@Composable
private fun ProfileScreenPreview() {
    MiaoTheme(darkMode = "light") {
        ProfileScreen()
    }
}

@Preview(name = "我的页 · 暗色", showBackground = true, showSystemUi = true)
@Composable
private fun ProfileScreenDarkPreview() {
    MiaoTheme(darkMode = "dark") {
        ProfileScreen()
    }
}
