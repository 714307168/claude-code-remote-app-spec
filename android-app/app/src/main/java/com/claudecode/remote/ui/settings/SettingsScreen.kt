package com.claudecode.remote.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.claudecode.remote.R

data class SettingsState(
    val serverUrl: String = "",
    val deviceId: String = "",
    val token: String = "",
    val e2eEnabled: Boolean = false,
    val e2ePublicKey: String = "",
    val language: String = "en",
    val isSaving: Boolean = false,
    val message: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialState: SettingsState,
    onSave: (serverUrl: String, deviceId: String, token: String, e2eEnabled: Boolean) -> Unit,
    onLanguageChange: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var serverUrl by remember { mutableStateOf(initialState.serverUrl) }
    var deviceId by remember { mutableStateOf(initialState.deviceId) }
    var token by remember { mutableStateOf(initialState.token) }
    var e2eEnabled by remember { mutableStateOf(initialState.e2eEnabled) }
    var selectedLang by remember { mutableStateOf(initialState.language) }
    var langExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Server Connection Section
            Text(
                text = stringResource(R.string.server_connection),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text(stringResource(R.string.relay_server_url)) },
                placeholder = { Text(stringResource(R.string.relay_server_url_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it },
                label = { Text(stringResource(R.string.device_id)) },
                placeholder = { Text(stringResource(R.string.device_id_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.auth_token)) },
                placeholder = { Text(stringResource(R.string.auth_token_placeholder)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // E2E Encryption Section
            Text(
                text = stringResource(R.string.e2e_encryption),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.e2e_enable), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = e2eEnabled, onCheckedChange = { e2eEnabled = it })
            }

            if (initialState.e2ePublicKey.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.public_key),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = initialState.e2ePublicKey,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider()

            // Language Section
            Text(
                text = stringResource(R.string.language),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            ExposedDropdownMenuBox(
                expanded = langExpanded,
                onExpandedChange = { langExpanded = it }
            ) {
                OutlinedTextField(
                    value = if (selectedLang == "zh") stringResource(R.string.lang_zh) else stringResource(R.string.lang_en),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.language_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = langExpanded,
                    onDismissRequest = { langExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lang_en)) },
                        onClick = {
                            selectedLang = "en"
                            langExpanded = false
                            onLanguageChange("en")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lang_zh)) },
                        onClick = {
                            selectedLang = "zh"
                            langExpanded = false
                            onLanguageChange("zh")
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    onSave(serverUrl.trim(), deviceId.trim(), token.trim(), e2eEnabled)
                    message = "Settings saved"
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = serverUrl.isNotBlank()
            ) {
                Text(stringResource(R.string.save_reconnect))
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
