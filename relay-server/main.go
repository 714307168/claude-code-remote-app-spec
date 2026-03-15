package main

import (
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/claudecode/relay-server/config"
	"github.com/claudecode/relay-server/handler"
	"github.com/claudecode/relay-server/hub"
	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
)

func main() {
	cfg := config.Load()

	// Configure zerolog JSON output.
	zerolog.TimeFieldFormat = time.RFC3339
	level, err := zerolog.ParseLevel(cfg.LogLevel)
	if err != nil {
		level = zerolog.InfoLevel
	}
	zerolog.SetGlobalLevel(level)
	log.Logger = zerolog.New(os.Stdout).With().Timestamp().Logger()

	h := hub.NewHub(cfg)

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", handler.WSHandler(h, cfg))
	mux.HandleFunc("/api/session", handler.SessionHandler(cfg))
	mux.HandleFunc("/api/project/bind", handler.ProjectBindHandler(h, cfg))
	mux.HandleFunc("/api/agent/wakeup", handler.WakeupHandler(h, cfg))
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	// CORS middleware
	corsHandler := corsMiddleware(mux, cfg.CORSOrigins)

	addr := ":" + cfg.Port
	if cfg.TLSCert != "" && cfg.TLSKey != "" {
		log.Info().Str("addr", addr).Msg("relay server starting (TLS)")
		if err := http.ListenAndServeTLS(addr, cfg.TLSCert, cfg.TLSKey, corsHandler); err != nil {
			log.Fatal().Err(err).Msg("server exited")
		}
	} else {
		log.Info().Str("addr", addr).Msg("relay server starting")
		if err := http.ListenAndServe(addr, corsHandler); err != nil {
			log.Fatal().Err(err).Msg("server exited")
		}
	}
}

func corsMiddleware(next http.Handler, origins string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origins == "*" {
			w.Header().Set("Access-Control-Allow-Origin", "*")
		} else if origin != "" {
			for _, allowed := range strings.Split(origins, ",") {
				if strings.TrimSpace(allowed) == origin {
					w.Header().Set("Access-Control-Allow-Origin", origin)
					break
				}
			}
		}
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}
