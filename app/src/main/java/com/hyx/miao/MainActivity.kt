package com.hyx.miao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.hyx.miao.data.local.TokenStore
import com.hyx.miao.data.repository.AppSettings
import com.hyx.miao.data.repository.SettingsRepository
import com.hyx.miao.ui.navigation.MiaoNavGraph
import com.hyx.miao.ui.navigation.Screen
import com.hyx.miao.ui.theme.MiaoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
            MiaoTheme(
                darkMode = settings.darkMode,
                themeColor = settings.themeColor,
                fontSize = settings.fontSize,
            ) {
                val navController = rememberNavController()
                val start = if (tokenStore.accessToken != null) Screen.Main.route
                            else Screen.Login.route
                MiaoNavGraph(navController = navController, startDestination = start)
            }
        }
    }
}
