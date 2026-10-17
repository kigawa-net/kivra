// Command mockupstream stands in for Keycloak, the Anthropic token endpoint and the Anthropic API.
// It is for verify/ only (fake secrets, fake tokens). It never talks to the real services.
//
// Control: GET /mock/state reports counters; POST /mock/revoke invalidates all issued access tokens.
package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
)

type state struct {
	mu                sync.Mutex
	secret            string
	expiresIn         int64
	jwtCount          int
	usedAssertions    map[string]bool
	validTokens       map[string]bool
	exchanges         int
	messagesOK        int
	messagesUnauth    int
	xAPIKeySeen       int
	authorizationSeen int
}

func main() {
	s := &state{
		secret:         os.Getenv("MOCK_CLIENT_SECRET"),
		expiresIn:      3600,
		usedAssertions: map[string]bool{},
		validTokens:    map[string]bool{},
	}
	if v := os.Getenv("MOCK_EXPIRES_IN"); v != "" {
		fmt.Sscanf(v, "%d", &s.expiresIn)
	}
	if s.secret == "" {
		log.Fatal("MOCK_CLIENT_SECRET is required")
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/realms/kigawa-net/protocol/openid-connect/token", s.keycloak)
	mux.HandleFunc("/v1/oauth/token", s.exchange)
	mux.HandleFunc("/v1/messages", s.messages)
	mux.HandleFunc("/v1/messages/count_tokens", s.messages)
	mux.HandleFunc("/mock/state", s.stateHandler)
	mux.HandleFunc("/mock/revoke", s.revoke)
	addr := os.Getenv("MOCK_LISTEN")
	if addr == "" {
		addr = ":9000"
	}
	log.Printf("mockupstream listen=%s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}

func randHex() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// keycloak: client_credentials with the expected secret. Each call returns a new JWT.
func (s *state) keycloak(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || r.ParseForm() != nil {
		http.Error(w, "bad", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if r.PostForm.Get("grant_type") != "client_credentials" || r.PostForm.Get("client_id") != "kivra-agent" || r.PostForm.Get("client_secret") != s.secret {
		log.Printf("keycloak %s status=401", r.Method)
		http.Error(w, `{"error":"invalid_client"}`, http.StatusUnauthorized)
		return
	}
	s.jwtCount++
	jwt := fmt.Sprintf("eyJmock.%d.%s", s.jwtCount, randHex())
	log.Printf("keycloak %s status=200", r.Method)
	writeJSON(w, map[string]any{"access_token": jwt, "token_type": "Bearer", "expires_in": 300})
}

// exchange: the JWT-bearer grant. A JWT (assertion) may be used once.
func (s *state) exchange(w http.ResponseWriter, r *http.Request) {
	b, _ := io.ReadAll(io.LimitReader(r.Body, 1<<16))
	var req struct {
		GrantType        string `json:"grant_type"`
		Assertion        string `json:"assertion"`
		FederationRuleID string `json:"federation_rule_id"`
		OrganizationID   string `json:"organization_id"`
		ServiceAccountID string `json:"service_account_id"`
		WorkspaceID      string `json:"workspace_id"`
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := json.Unmarshal(b, &req); err != nil || req.GrantType != "urn:ietf:params:oauth:grant-type:jwt-bearer" ||
		req.Assertion == "" || req.FederationRuleID == "" || req.OrganizationID == "" || req.ServiceAccountID == "" {
		log.Printf("exchange %s status=400", r.Method)
		http.Error(w, `{"error":"invalid_request"}`, http.StatusBadRequest)
		return
	}
	if s.usedAssertions[req.Assertion] {
		log.Printf("exchange %s status=401 reason=assertion_reused", r.Method)
		http.Error(w, `{"error":"assertion_reused"}`, http.StatusUnauthorized)
		return
	}
	s.usedAssertions[req.Assertion] = true
	s.exchanges++
	tok := fmt.Sprintf("sk-ant-oat01-MOCK-%d-%s", s.exchanges, randHex())
	s.validTokens[tok] = true
	log.Printf("exchange %s status=200", r.Method)
	writeJSON(w, map[string]any{"access_token": tok, "token_type": "bearer", "expires_in": s.expiresIn, "scope": "mock"})
}

// messages: accepts only a currently valid Bearer token. Records whether x-api-key arrived (never its value).
func (s *state) messages(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if r.Header.Get("X-Api-Key") != "" {
		s.xAPIKeySeen++
	}
	auth := r.Header.Get("Authorization")
	if auth != "" {
		s.authorizationSeen++
	}
	if !strings.HasPrefix(auth, "Bearer ") || !s.validTokens[strings.TrimPrefix(auth, "Bearer ")] {
		s.messagesUnauth++
		log.Printf("messages %s status=401", r.Method)
		http.Error(w, `{"type":"error","error":{"type":"authentication_error"}}`, http.StatusUnauthorized)
		return
	}
	s.messagesOK++
	_, _ = io.Copy(io.Discard, r.Body)
	log.Printf("messages %s status=200", r.Method)
	writeJSON(w, map[string]any{"id": "msg_mock", "type": "message", "role": "assistant", "model": "mock",
		"content": []map[string]any{{"type": "text", "text": "ok"}}, "stop_reason": "end_turn",
		"usage": map[string]any{"input_tokens": 1, "output_tokens": 1}})
}

func (s *state) stateHandler(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	writeJSON(w, map[string]any{"keycloak_jwts_issued": s.jwtCount, "exchanges": s.exchanges,
		"messages_ok": s.messagesOK, "messages_unauthorized": s.messagesUnauth,
		"x_api_key_seen": s.xAPIKeySeen, "authorization_seen": s.authorizationSeen})
}

func (s *state) revoke(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.validTokens = map[string]bool{}
	writeJSON(w, map[string]any{"revoked": true})
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}
