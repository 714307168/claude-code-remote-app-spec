package com.claudecode.remote.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudecode.remote.data.model.Message
import com.claudecode.remote.data.remote.RelayWebSocket
import com.claudecode.remote.domain.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isConnected: Boolean = false,
    val isSending: Boolean = false,
    val projectId: String = "",
    val projectName: String = ""
)

class ChatViewModel(
    private val messageRepository: MessageRepository,
    private val webSocket: RelayWebSocket
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Observe connection state
        viewModelScope.launch {
            webSocket.connectionState.collect { state ->
                _uiState.update {
                    it.copy(isConnected = state == RelayWebSocket.ConnectionState.CONNECTED)
                }
            }
        }
        // Process incoming envelopes
        viewModelScope.launch {
            webSocket.incomingEnvelopes.collect { envelope ->
                messageRepository.processEnvelope(envelope)
            }
        }
    }

    fun loadProject(projectId: String, projectName: String) {
        _uiState.update { it.copy(projectId = projectId, projectName = projectName) }
        viewModelScope.launch {
            messageRepository.getMessagesForProject(projectId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
        viewModelScope.launch {
            webSocket.connect()
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val state = _uiState.value
        val content = state.inputText.trim()
        if (content.isEmpty() || state.isSending) return

        _uiState.update { it.copy(inputText = "", isSending = true) }
        viewModelScope.launch {
            try {
                messageRepository.sendMessage(state.projectId, content)
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun clearInput() {
        _uiState.update { it.copy(inputText = "") }
    }

    override fun onCleared() {
        super.onCleared()
        webSocket.disconnect()
    }
}
