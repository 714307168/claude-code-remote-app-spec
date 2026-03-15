package com.claudecode.remote.domain

import com.claudecode.remote.data.model.Envelope
import com.claudecode.remote.data.model.Events
import com.claudecode.remote.data.model.Message
import com.claudecode.remote.data.model.MessageRole
import com.claudecode.remote.data.model.StreamBuffer
import com.claudecode.remote.data.remote.RelayWebSocket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

class MessageRepository(private val webSocket: RelayWebSocket) {

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: Flow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val streamBuffers = mutableMapOf<String, StreamBuffer>()

    suspend fun sendMessage(projectId: String, content: String) {
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            event = Events.MESSAGE_SEND,
            projectId = projectId,
            payload = buildJsonObject { put("content", JsonPrimitive(content)) },
            ts = System.currentTimeMillis()
        )
        // Optimistically add user message to local state
        val userMessage = Message(
            id = envelope.id,
            projectId = projectId,
            role = MessageRole.USER,
            content = content,
            timestamp = envelope.ts
        )
        addMessage(projectId, userMessage)
        webSocket.send(envelope)
    }

    fun processEnvelope(envelope: Envelope) {
        val projectId = envelope.projectId ?: return
        when (envelope.event) {
            Events.MESSAGE_CHUNK -> {
                val streamId = envelope.streamId ?: return
                val seq = envelope.seq ?: 0L
                val chunk = envelope.payload?.jsonObject?.get("content")?.jsonPrimitive?.content ?: return

                val buffer = streamBuffers.getOrPut(streamId) { StreamBuffer(streamId) }
                buffer.chunks.add(Pair(seq, chunk))

                // Upsert streaming message
                val assembled = buffer.assembledContent()
                upsertStreamingMessage(projectId, streamId, assembled, isStreaming = true)
            }
            Events.MESSAGE_DONE -> {
                val streamId = envelope.streamId ?: return
                val buffer = streamBuffers[streamId]
                if (buffer != null) {
                    buffer.isDone = true
                    val assembled = buffer.assembledContent()
                    upsertStreamingMessage(projectId, streamId, assembled, isStreaming = false)
                    streamBuffers.remove(streamId)
                }
            }
            Events.MESSAGE_ERROR -> {
                val streamId = envelope.streamId
                if (streamId != null) {
                    streamBuffers.remove(streamId)
             tStreamingMessage(projectId, streamId, "[Error receiving response]", isStreaming = false)
                }
            }
        }
    }

    fun getMessagesForProject(projectId: String): Flow<List<Message>> =
        _messages.map { it[projectId] ?: emptyList() }

    private fun addMessage(projectId: String, message: Message) {
        val current = _messages.value.toMutableMap()
        current[projectId] = (current[projectId] ?: emptyList()) + message
        _messages.value = current
    }

    private fun upsertStreamingMessage(projectId: String, streamId: String, content: String, isStr Boolean) {
        val current = _messages.value.toMutableMap()
        val projectMessages = (current[projectId] ?: emptyList()).toMutableList()
        val existingIndex = projectMessages.indexOfFirst { it.streamId == streamId }
        val message = Message(
            id = streamId,
            projectId = projectId,
            role = MessageRole.ASSISTANT,
            content = content,
            streamId = streamId,
            timestamp = System.currentTimeMillis(),
            isStreaming = isStreaming
        )
        if (existingIndex >= 0) {
            projectMessages[existingIndex] = message
        } else {
            projectMessages.add(message)
        }
        current[projectId] = projectMessages
        _messages.value = current
    }
}
