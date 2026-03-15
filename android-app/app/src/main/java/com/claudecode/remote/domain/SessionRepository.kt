package com.claudecode.remote.domain

import com.claudecode.remote.data.local.TokenStore
import com.claudecode.remote.data.model.CreateSessionRequest
import com.claudecode.remote.data.model.Session
import com.claudecode.remote.data.remote.BindProjectRequest
import com.claudecode.remote.data.remote.RelayApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class SessionRepository(
    private val api: RelayApi,
    private val tokenStore: TokenStore
) {
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: Flow<List<Session>> = _sessions.asStateFlow()

    suspend fun initialize(): Result<Unit> {
        return try {
            var deviceId = tokenStore.getDeviceId()
            if (deviceId == null) {
                deviceId = UUID.randomUUID().toString()
                tokenStore.saveDeviceId(deviceId)
            }
            if (tokenStore.getToken() == null) {
                val response = api.createSession(CreateSessionRequest(deviceId = deviceId))
                tokenStore.saveToken(response.token)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addSession(
        agentId: String,
        projectId: String,
        projectPath: String,
        name: String
    ): Result<Session> {
        return try {
            val token = tokenStore.getToken() ?: return Result.failure(Exception("Not authenticated"))
            api.bindProject(
                auth = "Bearer $token",
                request = BindProjectRequest(
                    projectId = projectId,
                    agentId = agentId,
                    path = projectPath,
                    name = name
                )
            )
            val session = Session(
                id = UUID.randomUUID().toString(),
                name = name,
                agentId = agentId,
                projectId = projectId,
                projectPath = projectPath,
                createdAt = System.currentTimeMillis(),
                lastActiveAt = System.currentTimeMillis()
            )
            _sessions.value = _sessions.value + session
            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getSessions(): List<Session> = _sessions.value

    fun removeSession(id: String) {
        _sessions.value = _sessions.value.filter { it.id != id }
    }
}
