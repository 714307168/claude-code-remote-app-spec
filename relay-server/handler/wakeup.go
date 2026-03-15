package handler

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/claudecode/relay-server/config"
	"github.com/claudecode/relay-server/hub"
	"github.com/claudecode/relay-server/model"
	"github.com/google/uuid"
)

type wakeupRequest struct {
	AgentID string `json:"agent_id"`
}

// WakeupHandler sends an agent.wakeup event to the target agent if online,
// or returns 202 Accepted if the agent is currently offline.
func WakeupHandler(h *hub.Hub, cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var req wakeupRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.AgentID == "" {
			http.Error(w, "agent_id is required", http.StatusBadRequest)
			return
		}

		env := &model.Envelope{
			ID:        uuid.New().String(),
			Event:     model.EventAgentWakeup,
			Seq:       h.NextSeq(),
			Timestamp: time.Now().UnixMilli(),
		}

		sent := h.SendToAgent(req.AgentID, env)

		w.Header().Set("Content-Type", "application/json")
		if sent {
			w.WriteHeader(http.StatusOK)
			json.NewEncoder(w).Encode(map[string]string{"status": "sent"})
		} else {
			w.WriteHeader(http.StatusAccepted)
			json.NewEncoder(w).Encode(map[string]string{"status": "queued"})
		}
	}
}
