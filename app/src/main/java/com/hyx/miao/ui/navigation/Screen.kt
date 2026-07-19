package com.hyx.miao.ui.navigation

sealed class Screen(val route: String) {
    object Login        : Screen("login")
    object Register     : Screen("register")
    object ForgotPassword : Screen("forgot_password")
    object ResetPassword : Screen("reset_password?token={token}") {
        fun createRoute(token: String) = "reset_password?token=${android.net.Uri.encode(token)}"
    }
    object Main         : Screen("main")
    object Chat         : Screen("chat/{conversationId}") {
        fun createRoute(id: String) = "chat/$id"
    }
    object Memory       : Screen("memory")
    object Profile      : Screen("profile")
    object WechatBind         : Screen("wechat_bind")
    object ModelSettings      : Screen("model_settings")
    object PersonaSettings    : Screen("persona_settings")
    object AppearanceSettings : Screen("appearance_settings")
    object GeneralSettings    : Screen("general_settings")
}
