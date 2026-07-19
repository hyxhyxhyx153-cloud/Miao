package com.hyx.miao.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyx.miao.ui.components.PawLoadingIndicator
import com.hyx.miao.ui.components.GlassIconButton
import com.hyx.miao.ui.components.LiquidGlassCard
import com.hyx.miao.ui.components.MiaoBackdrop
import com.hyx.miao.ui.components.MiaoSurfaceStyle

@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
    legalViewModel: LegalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val legalDocuments by legalViewModel.documents.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var legalAccepted by remember { mutableStateOf(false) }
    var openLegalDocument by remember { mutableStateOf<LegalDocumentType?>(null) }
    val valid = Regex("^[A-Za-z0-9_]{2,32}$").matches(username) &&
        android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() &&
        password.length >= 8 && password == confirmation

    LaunchedEffect(state.isSuccess) { if (state.isSuccess) onRegisterSuccess() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); viewModel.clearNotice() } }

    MiaoBackdrop(
        modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
    ) {
        GlassIconButton(
            onClick = onNavigateBack,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
        }
        Column(
            modifier = Modifier.fillMaxSize().widthIn(max = 520.dp).align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("创建账号", style = MaterialTheme.typography.headlineMedium)
            Text(
                "保存你的对话、记忆与个性设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 4.dp))
            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 28.dp,
                style = MiaoSurfaceStyle.GlassStrong,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AuthField(username, { username = it }, "用户名（2-32 位字母、数字或下划线）", Icons.Default.Person)
                    AuthField(email, { email = it }, "邮箱", Icons.Default.Email, KeyboardType.Email)
                    AuthField(password, { password = it }, "密码（至少 8 位）", Icons.Default.Lock, KeyboardType.Password, true)
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("确认密码") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            if (valid && legalAccepted && !state.isLoading) {
                                viewModel.register(username, email, password)
                            }
                        }),
                        isError = confirmation.isNotEmpty() && confirmation != password,
                        supportingText = { if (confirmation.isNotEmpty() && confirmation != password) Text("两次密码不一致") },
                        colors = glassTextFieldColors(),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            LegalConsentRow(
                checked = legalAccepted,
                onCheckedChange = { legalAccepted = it },
                onOpenDocument = { openLegalDocument = it },
            )
            Button(
                onClick = { viewModel.register(username, email, password) },
                enabled = valid && legalAccepted && !state.isLoading,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(18.dp),
            ) { if (state.isLoading) PawLoadingIndicator() else Text("注册并登录", style = MaterialTheme.typography.labelLarge) }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    openLegalDocument?.let { type ->
        LegalDocumentDialog(
            type = type,
            documents = legalDocuments,
            onDismiss = { openLegalDocument = null },
        )
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = glassTextFieldColors(),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}
