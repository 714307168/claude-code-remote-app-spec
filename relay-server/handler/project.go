package handler

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/claudecode/relay-server/auth"
	"github.com/claudecode/relay-server/config"
	"github.com/claudecode/relay-server/hub"
)

type projectBindRequest struct {
	ProjectID string `json:"project_id"`
	AgentID   string `json:"agent_id"`
	Path      string `json:"path"`
	Name      string `json:"name"`
}

// ProjectBindHandler registers a project->agent mapping via REST.
func ProjectBindHandler(h *hub.Hub, cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}

		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if token == "" {
			http.Error(w, "missing authorization", http.StatusUnauthorized)
			return
		}
		if _, err := auth.VerifyToken(cfg.JWTSecret, token); err != nil {
			http.Error(w, "invalid token", http.StatusUnauthorized)
			return
		}

		var req projectBindRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}
		if req.ProjectID == "" || req.AgentID == "" {
			http.Error(w, "project_id and agent_id are required", http.StatusBadRequest)
			return
		}

		h.BindProject(req.ProjectID, req.AgentID)

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "bound"})
	}
}
