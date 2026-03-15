package com.claudecode.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.claudecode.remote.data.crypto.E2ECrypto
import com.claudecode.remote.data.local.TokenStore
import com.claudecode.remote.data.remote.RelayApi
import com.claudecode.remote.data.remote.RelayWebSocket
import com.claudecode.remote.domain.MessageRepository
import com.claudecode.remote.domain.SessionRepository
import com.claudecode.remote.ui.chat.ChatScreen
import com.claudecode.remote.ui.chat.ChatViewModel
import com.claudecode.remote.ui.session.SessionListScreen
import com.claudecode.remote.ui.session.SessionViewModel
import com.claudecode.remote.ui.settings.SettingsScreen
import com.claudecode.remote.ui.settings.SettingsState
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val tokenStore = remember { TokenStore(applicationContext) }
                val serverUrl = remember {
                    tokenStore.getServerUrl() ?: "http://10.0.2.2:3000"
                }
                val e2eCrypto = remember { E2ECrypto() }
                val json = remember {
                    Json { ignoreUnknownKeys = true; encodeDefaults = true }
                }
                val retrofit = remember {
                    Retrofit.Builder()
                        .baseUrl(serverUrl.trimEnd('/') + "/")
                        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                        .build()
                }
                val relayApi = remember { retrofit.create(RelayApi::class.java) }
                val relayWebSocket = remember { RelayWebSocket(serverUrl, tokenStore) }
                val sessionRepository = remember { SessionRepository(relayApi, tokenStore) }
                val messageRepository = remember { MessageRepository(relayWebSocket) }

                NavHost(navController = navController, startDestination = "sessions") {
                    composable("sessions") {
                        val viewModel = remember {
                            SessionViewModel(sessionRepository)
                        }
                        SessionListScreen(
                            viewModel = viewModel,
                            onNavigateToChat = { sessionId ->
                                val session = sessionRepository.getSessions()
                                    .firstOrNull { it.id == sessionId }
                                if (session != null) {
                                    navController.navigate(
                                        "chat/${session.projectId}/${
                                            android.net.Uri.encode(session.name)
                                        }"
                                    )
                                }
                            },
                            onNavigateToSettings = {
                                navController.navigate("settings")
                            }
                        )
                    }
                    composable("chat/{projectId}/{projectName}") { backStackEntry ->
                        val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
                        val projectName = android.net.Uri.decode(
                            backStackEntry.arguments?.getString("projectName") ?: ""
                        )
                        val viewModel = remember {
                            ChatViewModel(messageRepository, relayWebSocket)
                        }
                        ChatScreen(
                            projectId = projectId,
                            projectName = projectName,
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            initialState = SettingsState(
                                serverUrl = tokenStore.getServerUrl() ?: "",
                                deviceId = tokenStore.getDeviceId() ?: "",
                                token = tokenStore.getToken() ?: "",
                                e2eEnabled = tokenStore.isE2EEnabled(),
                                e2ePublicKey = e2eCrypto.getPublicKeyBase64()
                            ),
                            onSave = { url, devId, tok, e2e ->
                                tokenStore.saveServerUrl(url)
                                tokenStore.saveDeviceId(devId)
                                tokenStore.saveToken(tok)
                                tokenStore.saveE2EEnabled(e2e)
                            },
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
