package handler

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/claudecode/relay-server/auth"
	"github.com/claudecode/relay-server/config"
	"github.com/claudecode/relay-server/model"
)

type sessionRequest struct {
	Type     model.ClientType `json:"type"`
	AgentID  string           `json:"agent_id"`
	DeviceID string           `json:"device_id"`
}

type sessionResponse struct {
	Token     string `json:"token"`
	ExpiresAt string `json:"expires_at"`
}

// SessionHandler issues JWT tokens for agents and devices.
func SessionHandler(cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}

		var req sessionRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}

		if req.Type != model.ClientTypeAgent && req.Type != model.ClientTypeDevice {
			http.Error(w, "type must be 'agent' or 'device'", http.StatusBadRequest)
			return
		}

		ttl := 24 * time.Hour
		if req.Type == model.ClientTypeAgent {
			ttl = 30 * 24 * time.Hour
		}

		token, err := auth.SignToken(cfg.JWTSecret, req.AgentID, req.DeviceID, req.Type, ttl)
		if err != nil {
			http.Error(w, "failed to sign token", http.StatusInternalServerError)
			return
		}

		resp := sessionResponse{
			Token:     token,
			ExpiresAt: time.Now().Add(ttl).UTC().Format(time.RFC3339),
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	}
}
