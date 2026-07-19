package com.hyx.miao.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.hyx.miao.ui.screens.auth.LoginScreen
import com.hyx.miao.ui.screens.auth.ForgotPasswordScreen
import com.hyx.miao.ui.screens.auth.RegisterScreen
import com.hyx.miao.ui.screens.auth.ResetPasswordScreen
import com.hyx.miao.ui.screens.chat.ChatScreen
import com.hyx.miao.ui.screens.main.MainScreen
import com.hyx.miao.ui.screens.settings.AppearanceSettingsScreen
import com.hyx.miao.ui.screens.settings.GeneralSettingsScreen
import com.hyx.miao.ui.screens.settings.ModelSettingsScreen
import com.hyx.miao.ui.screens.settings.PersonaSettingsScreen
import com.hyx.miao.ui.screens.wechat.WechatBindScreen
import com.hyx.miao.ui.components.MiaoBackdrop

@Composable
fun MiaoNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Login.route,
) {
    MiaoBackdrop(Modifier.fillMaxSize()) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                onNavigateToForgotPassword = { navController.navigate(Screen.ForgotPassword.route) },
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.ForgotPassword.route) {
            ForgotPasswordScreen(
                onBack = { navController.popBackStack() },
                onResetWithToken = { navController.navigate(Screen.ResetPassword.createRoute(it)) },
            )
        }

        composable(
            route = Screen.ResetPassword.route,
            arguments = listOf(navArgument("token") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            ResetPasswordScreen(
                initialToken = entry.arguments?.getString("token").orEmpty(),
                onBack = { navController.popBackStack() },
                onResetSuccess = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Login.route) { inclusive = false }
                    }
                },
            )
        }

        composable(Screen.Main.route) {
            MainScreen(
                onNavigateToChat = { id -> navController.navigate(Screen.Chat.createRoute(id)) },
                onNavigateToWechat = { navController.navigate(Screen.WechatBind.route) },
                onNavigateToModelSettings = { navController.navigate(Screen.ModelSettings.route) },
                onNavigateToPersonaSettings = { navController.navigate(Screen.PersonaSettings.route) },
                onNavigateToAppearanceSettings = { navController.navigate(Screen.AppearanceSettings.route) },
                onNavigateToGeneralSettings = { navController.navigate(Screen.GeneralSettings.route) },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.WechatBind.route) {
            WechatBindScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.ModelSettings.route) {
            ModelSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.PersonaSettings.route) {
            PersonaSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.AppearanceSettings.route) {
            AppearanceSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.GeneralSettings.route) {
            GeneralSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) { backStack ->
            val id = backStack.arguments?.getString("conversationId") ?: return@composable
            ChatScreen(
                conversationId = id,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
    }
}
