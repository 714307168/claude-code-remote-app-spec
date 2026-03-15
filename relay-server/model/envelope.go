package model

import "encoding/json"

// Event name constants
const (
	// Auth events
	EventAuthLogin   = "auth.login"
	EventAuthResume  = "auth.resume"
	EventAuthRefresh = "auth.refresh"
	EventAuthOK      = "auth.ok"
	EventAuthError   = "auth.error"

	// Project events
	EventProjectBind   = "project.bind"
	EventProjectBound  = "project.bound"
	EventProjectList   = "project.list"
	EventProjectListed = "project.listed"

	// Message events
	EventMessageSend  = "message.send"
	EventMessageChunk = "message.chunk"
	EventMessageDone  = "message.done"
	EventMessageError = "message.error"

	// Agent events
	EventAgentStatus = "agent.status"
	EventAgentWakeup = "agent.wakeup"

	// File events
	EventFileSync = "file.sync"

	// System events
	EventPing  = "ping"
	EventPong  = "pong"
	EventError = "error"
)

// ClientType identifies the type of connected client
type ClientType string

const (
	ClientTypeAgent  ClientType = "agent"
	ClientTypeDevice ClientType = "device"
)

// Envelope is the universal message wrapper for all WS communication
type Envelope struct {
	ID        string          `json:"id"`
	Event     string          `json:"event"`
	ProjectID string          `json:"project_id,omitempty"`
	StreamID  string          `json:"stream_id,omitempty"`
	Seq       int64           `json:"seq,omitempty"`
	Payload   json.RawMessage `json:"payload,omitempty"`
	Timestamp int64           `json:"ts"`
}

// AuthLoginPayload is the payload for auth.login
type AuthLoginPayload struct {
	Token    string     `json:"token"`
	Type     ClientType `json:"type"`
	AgentID  string     `json:"agent_id,omitempty"`
	DeviceID string     `json:"device_id,omitempty"`
}

// AuthResumePayload is the payload for auth.resume
type AuthResumePayload struct {
	Token    string     `json:"token"`
	Type     ClientType `json:"type"`
	AgentID  string     `json:"agent_id,omitempty"`
	DeviceID string     `json:"device_id,omitempty"`
	LastSeq  int64      `json:"last_seq"`
}

// AuthOKPayload is the payload for auth.ok
type AuthOKPayload struct {
	AgentID  string `json:"agent_id,omitempty"`
	DeviceID string `json:"device_id,omitempty"`
}

// ProjectBindPayload is the payload for project.bind
type ProjectBindPayload struct {
	ProjectID string `json:"project_id"`
	Path      string `json:"path"`
	Name      string `json:"name"`
}

// MessageSendPayload is the payload for message.send
type MessageSendPayload struct {
	Content   string `json:"content"`
	ProjectID string `json:"project_id"`
}

// MessageChunkPayload is the payload for message.chunk
type MessageChunkPayload struct {
	Content   string `json:"content"`
	ProjectID string `json:"project_id"`
	StreamID  string `json:"stream_id"`
	Seq       int64  `json:"seq"`
	Done      bool   `json:"done"`
}

// AgentStatusPayload is the payload for agent.status
type AgentStatusPayload struct {
	AgentID   string `json:"agent_id"`
	Online    bool   `json:"online"`
	ProjectID string `json:"project_id,omitempty"`
}

// ErrorPayload is the payload for error events
type ErrorPayload struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// FileSyncPayload is the payload for file.sync
type FileSyncPayload struct {
	ProjectID string `json:"project_id"`
	Path      string `json:"path"`
	Content   string `json:"content"` // base64 encoded
	Version   int64  `json:"version"`
	Checksum  string `json:"checksum"`
}
