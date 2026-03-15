package com.claudecode.remote.data.remote

import android.util.Log
import com.claudecode.remote.data.local.TokenStore
import com.claudecode.remote.data.model.Envelope
import com.claudecode.remote.data.model.Events
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

class RelayWebSocket(
    private val serverUrl: String,
    private val tokenStore: TokenStore
) {
    private val tag = "RelayWebSocket"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _incomingEnvelopes = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    val incomingEnvelopes: SharedFlow<Envelope> = _incomingEnvelopes.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var webSocket: WebSocket? = null
    private var lastSeq: Long = 0
    private var reconnectAttempts = 0
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    suspend fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) return
        _connectionState.value = ConnectionState.CONNECTING
        reconnectAttempts = 0
        openWebSocket()
    }

    private fun openWebSocket() {
        val wsUrl = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/') + "/ws"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, createListener())
    }

    private fun createListener() = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(tag, "WebSocket opened")
            _connectionState.value = ConnectionState.CONNECTED
            reconnectAttempts = 0
            authenticate()
            startPing()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                val envelope = json.decodeFromString<Envelope>(text)
                envelope.seq?.let { if (it > lastSeq) lastSeq = it }
                scope.launch { _incomingEnvelopes.emit(envelope) }
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse envelope: $text", e)
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(tag, "WebSocket failure", t)
            _connectionState.value = ConnectionState.RECONNECTING
            stopPing()
            scheduleReconnect()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(tag, "WebSocket closed: $code $reason")
            if (_connectionState.value != ConnectionState.DISCONNECTED) {
                _connectionState.value = ConnectionState.RECONNECTING
                scheduleReconnect()
            }
            stopPing()
        }
    }

    private fun authenticate() {
        val token = tokenetToken()
        val event = if (lastSeq > 0) Events.AUTH_RESUME else Events.AUTH_LOGIN
        val payload = buildJsonObject {
            put("token", JsonPrimitive(token ?: ""))
            if (lastSeq > 0) put("last_seq", JsonPrimitive(lastSeq))
        }
        send(
            Envelope(
                id = UUID.randomUUID().toString(),
                event = event,
                payload = payload,
                ts = System.currentTimeMillis()
            )
        )
    }

    fun send(envelope: Envelope) {
        try {
            val text = json.encodeToString(envelope)
            webSocket?.send(text)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send envelope", e)
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        stopPing()
        _connectionState.value = ConnectionState.DISCONNECTED
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(30_000)
                if (_connectionState.value == ConnectionState.CONNECTED) {
              send(
                        Envelope(
                            id = UUID.randomUUID().toString(),
                            event = Events.PING,
                            ts = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffSecminOf(30L, 1L shl reconnectAtte
            Log.d(tag, "Reconnecting in ${backoffSeconds}s (attempt ${reconnectAttempts + 1})")
            delay(backoffSeconds * 1000)
            reconnectAttempts++
            openWebSocket()
        }
    }
}
