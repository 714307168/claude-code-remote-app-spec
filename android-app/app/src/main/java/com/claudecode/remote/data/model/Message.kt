package com.claudecode.remote.data.model

import kotlinx.serialization.Serializable

enum class MessageRole { USER, ASSISTANT }

@Serializable
data class Message(
    val id: String,
    val projectId: String,
    val role: MessageRole,
    val content: String,
    val streamId: String? = null,
    val timestamp: Long,
    val isStreaming: Boolean = false
)

data class StreamBuffer(
    val streamId: String,
    val chunks: MutableList<Pair<Long, String>> = mutableListOf(),
    var isDone: Boolean = false
) {
    fun assembledContent(): String = chunks.sortedBy { it.first }.joinToString("") { it.second }
}
