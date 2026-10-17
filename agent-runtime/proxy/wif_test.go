package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const testSecret = "TEST-CLIENT-SECRET-DO-NOT-LOG-0001"

// fakeIdP plays Keycloak and the Anthropic token endpoint. Each Keycloak JWT is unique, and each JWT may
// be exchanged only once (its jti is single-use), like the real service.
type fakeIdP struct {
	t            *testing.T
	kcCalls      atomic.Int64
	kcFail       atomic.Bool
	exCalls      atomic.Int64
	exFail       atomic.Bool
	expiresIn    int64
	mu           sync.Mutex
	usedJWT      map[string]bool
	lastExBody   string
	lastSecret   string
	redirectSeen atomic.Bool
	kc           *httptest.Server
	ex           *httptest.Server
	seenAssert   []string
}

func newFakeIdP(t *testing.T, expiresIn int64) *fakeIdP {
	f := &fakeIdP{t: t, expiresIn: expiresIn, usedJWT: map[string]bool{}}
	f.kc = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.kcCalls.Add(1)
		if err := r.ParseForm(); err != nil {
			http.Error(w, "bad", 400)
			return
		}
		f.mu.Lock()
		f.lastSecret = r.PostForm.Get("client_secret")
		f.mu.Unlock()
		if f.kcFail.Load() || r.PostForm.Get("grant_type") != "client_credentials" || r.PostForm.Get("client_id") != "kivra-agent" {
			http.Error(w, `{"error":"boom secret=`+testSecret+`"}`, 500)
			return
		}
		n := f.kcCalls.Load()
		_ = json.NewEncoder(w).Encode(map[string]any{"access_token": fmt.Sprintf("JWT-%d-%d", n, time.Now().UnixNano())})
	}))
	f.ex = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.exCalls.Add(1)
		b, _ := io.ReadAll(r.Body)
		var req map[string]any
		if err := json.Unmarshal(b, &req); err != nil {
			http.Error(w, "bad json", 400)
			return
		}
		assertion, _ := req["assertion"].(string)
		f.mu.Lock()
		f.lastExBody = string(b)
		f.seenAssert = append(f.seenAssert, assertion)
		used := f.usedJWT[assertion]
		f.usedJWT[assertion] = true
		f.mu.Unlock()
		if f.exFail.Load() || used || req["grant_type"] != wifGrantType || r.Header.Get("Authorization") != "" {
			http.Error(w, `{"error":"rejected"}`, 401)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"access_token": fmt.Sprintf("sk-ant-oat01-FAKE-%d", f.exCalls.Load()),
			"token_type":   "bearer",
			"expires_in":   f.expiresIn,
		})
	}))
	t.Cleanup(func() { f.kc.Close(); f.ex.Close() })
	return f
}

func (f *fakeIdP) cfg(secretFile string) WIFConfig {
	return WIFConfig{
		KeycloakTokenURL:  f.kc.URL + "/token",
		KeycloakClientID:  "kivra-agent",
		ClientSecretFile:  secretFile,
		AnthropicTokenURL: f.ex.URL + "/v1/oauth/token",
		FederationRuleID:  "fdrl_TEST",
		OrganizationID:    "00000000-0000-4000-8000-000000000001",
		ServiceAccountID:  "svac_TEST",
		WorkspaceID:       "wrkspc_TEST",
	}
}

func secretFileWith(t *testing.T, v string) string {
	p := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(p, []byte(v+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) now() time.Time      { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) add(d time.Duration) { c.mu.Lock(); c.t = c.t.Add(d); c.mu.Unlock() }

func TestWIFFirstCallExchangesWithFreshJWTAndCaches(t *testing.T) {
	idp := newFakeIdP(t, 3600)
	ck := &clock{t: time.Unix(1_000_000, 0)}
	a := NewWIFAuth(idp.cfg(secretFileWith(t, testSecret)), http.DefaultClient, ck.now)
	c1, err := a.Authorize(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if c1.Name != "Authorization" || !strings.HasPrefix(c1.Value, "Bearer sk-ant-oat01-FAKE-") {
		t.Fatalf("unexpected credential header: %s", c1.Name)
	}
	c2, _ := a.Authorize(context.Background())
	if c2.Value != c1.Value || idp.kcCalls.Load() != 1 || idp.exCalls.Load() != 1 {
		t.Fatalf("cached token must not be re-exchanged: kc=%d ex=%d", idp.kcCalls.Load(), idp.exCalls.Load())
	}
	// Exchange body: the four IDs, grant type, and the assertion.
	idp.mu.Lock()
	body := idp.lastExBody
	idp.mu.Unlock()
	for _, want := range []string{`"grant_type":"urn:ietf:params:oauth:grant-type:jwt-bearer"`, `"federation_rule_id":"fdrl_TEST"`,
		`"organization_id":"00000000-0000-4000-8000-000000000001"`, `"service_account_id":"svac_TEST"`, `"workspace_id":"wrkspc_TEST"`} {
		if !strings.Contains(body, want) {
			t.Errorf("exchange body lacks %s", want)
		}
	}
}

func TestWIFUsesNewJWTForEveryExchange(t *testing.T) {
	idp := newFakeIdP(t, 300)
	ck := &clock{t: time.Unix(1_000_000, 0)}
	a := NewWIFAuth(idp.cfg(secretFileWith(t, testSecret)), http.DefaultClient, ck.now)
	for i := 0; i < 3; i++ {
		a.Invalidate(bearer("x")) // no-op, keeps the test using the public surface
		if _, err := a.Authorize(context.Background()); err != nil {
			t.Fatal(err)
		}
		a.Invalidate(Credential{secret: a.cache.token})
	}
	idp.mu.Lock()
	defer idp.mu.Unlock()
	seen := map[string]bool{}
	for _, j := range idp.seenAssert {
		if seen[j] {
			t.Fatalf("a JWT was sent twice: jti is single-use")
		}
		seen[j] = true
	}
	if len(seen) != 3 || idp.kcCalls.Load() != 3 {
		t.Fatalf("want 3 fresh JWTs, got %d (keycloak calls %d)", len(seen), idp.kcCalls.Load())
	}
}

func TestWIFRefreshThresholds(t *testing.T) {
	idp := newFakeIdP(t, 300) // expires in 300 s
	ck := &clock{t: time.Unix(1_000_000, 0)}
	a := NewWIFAuth(idp.cfg(secretFileWith(t, testSecret)), http.DefaultClient, ck.now)
	first, _ := a.Authorize(context.Background())

	// 100 s before expiry (inside the 120 s recommended window, outside the 30 s mandatory one): refresh.
	ck.add(200 * time.Second)
	idp.kcFail.Store(true) // the refresh fails: the cached token must still be used
	c, err := a.Authorize(context.Background())
	if err != nil || c.Value != first.Value {
		t.Fatalf("recommended refresh failure must keep the cache: err=%v", err)
	}
	idp.kcFail.Store(false)
	c, err = a.Authorize(context.Background())
	if err != nil || c.Value == first.Value {
		t.Fatalf("recommended refresh must replace the token: err=%v", err)
	}
	second := c.Value

	// Within 30 s of expiry the refresh is mandatory: a failure is an error.
	ck.add(275 * time.Second)
	idp.kcFail.Store(true)
	if _, err := a.Authorize(context.Background()); err == nil {
		t.Fatal("mandatory refresh failure must return an error")
	}
	idp.kcFail.Store(false)
	c, err = a.Authorize(context.Background())
	if err != nil || c.Value == second {
		t.Fatalf("mandatory refresh must succeed: err=%v", err)
	}
}

func TestWIFUnauthorizedInvalidatesCache(t *testing.T) {
	idp := newFakeIdP(t, 3600)
	ck := &clock{t: time.Unix(1_000_000, 0)}
	a := NewWIFAuth(idp.cfg(secretFileWith(t, testSecret)), http.DefaultClient, ck.now)
	c1, _ := a.Authorize(context.Background())
	a.Invalidate(c1)
	c2, err := a.Authorize(context.Background())
	if err != nil || c2.Value == c1.Value || idp.exCalls.Load() != 2 {
		t.Fatalf("after 401 the next call must exchange again: err=%v ex=%d", err, idp.exCalls.Load())
	}
	// Invalidating a token that is no longer cached must not drop the new one.
	a.Invalidate(c1)
	if c3, _ := a.Authorize(context.Background()); c3.Value != c2.Value {
		t.Fatal("stale invalidation must not drop the current token")
	}
}

func TestWIFConcurrentCallersShareOneExchange(t *testing.T) {
	idp := newFakeIdP(t, 3600)
	ck := &clock{t: time.Unix(1_000_000, 0)}
	a := NewWIFAuth(idp.cfg(secretFileWith(t, testSecret)), http.DefaultClient, ck.now)
	var wg sync.WaitGroup
	for i := 0; i < 25; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); _, _ = a.Authorize(context.Background()) }()
	}
	wg.Wait()
	if idp.exCalls.Load() != 1 {
		t.Fatalf("want 1 exchange for concurrent callers, got %d", idp.exCalls.Load())
	}
}

func TestWIFErrorsNeverContainSecrets(t *testing.T) {
	idp := newFakeIdP(t, 3600)
	idp.kcFail.Store(true)
	ck := &clock{t: time.Unix(1_000_000, 0)}
	a := NewWIFAuth(idp.cfg(secretFileWith(t, testSecret)), http.DefaultClient, ck.now)
	_, err := a.Authorize(context.Background())
	if err == nil {
		t.Fatal("expected error")
	}
	if strings.Contains(err.Error(), testSecret) || strings.Contains(err.Error(), "JWT") {
		t.Fatalf("error leaks secret material: %v", err)
	}
	// Missing secret file: fixed error, path not required.
	a2 := NewWIFAuth(idp.cfg(filepath.Join(t.TempDir(), "absent")), http.DefaultClient, ck.now)
	if _, err := a2.Authorize(context.Background()); err == nil {
		t.Fatal("missing secret must fail")
	}
}

func TestExchangeRejectsRedirect(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("redirect target must not receive the assertion")
	}))
	defer target.Close()
	redir := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			_, _ = w.Write([]byte(`{"access_token":"JWT-R"}`))
			return
		}
		http.Redirect(w, r, target.URL, http.StatusTemporaryRedirect)
	}))
	defer redir.Close()
	cfg := WIFConfig{KeycloakTokenURL: redir.URL + "/token", KeycloakClientID: "kivra-agent",
		ClientSecretFile: secretFileWith(t, testSecret), AnthropicTokenURL: redir.URL + "/v1/oauth/token",
		FederationRuleID: "f", OrganizationID: "o", ServiceAccountID: "s"}
	a := NewWIFAuth(cfg, http.DefaultClient, nil)
	if _, err := a.Authorize(context.Background()); err == nil {
		t.Fatal("a redirected exchange must fail, not follow")
	}
}

// logMarker stands for response bodies, tokens and JWTs. It must never reach a log line or an error message.
const logMarker = "LEAK-MARKER-BODY-0042"

func TestWIFFailureStages(t *testing.T) {
	okKeycloak := func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"access_token":"jwt-` + logMarker + `"}`))
	}
	failKeycloak := func(status int) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			http.Error(w, logMarker+" "+testSecret, status)
		}
	}
	failExchange := func(status int) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			http.Error(w, logMarker, status)
		}
	}
	exchangeOK := func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"access_token":"at-` + logMarker + `","expires_in":3600}`))
	}
	badJSON := func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("not-json-" + logMarker)) }

	cases := []struct {
		name       string
		kc         http.HandlerFunc // nil: Keycloak URL is a closed server (network failure)
		ex         http.HandlerFunc // nil: exchange URL is a closed server (network failure)
		secret     string           // "" with absent=false: written empty; absent=true: no file
		absent     bool
		wantStage  string
		wantStatus int
	}{
		{name: "secret_unreadable", kc: okKeycloak, ex: exchangeOK, absent: true, wantStage: stageSecretUnreadable},
		{name: "secret_empty", kc: okKeycloak, ex: exchangeOK, secret: " \n", wantStage: stageSecretEmpty},
		{name: "keycloak_401", kc: failKeycloak(401), ex: exchangeOK, wantStage: stageKeycloak, wantStatus: 401},
		{name: "keycloak_network", kc: nil, ex: exchangeOK, wantStage: stageKeycloakNetwork},
		{name: "keycloak_malformed", kc: badJSON, ex: exchangeOK, wantStage: stageMalformed},
		{name: "exchange_500", kc: okKeycloak, ex: failExchange(500), wantStage: stageExchange, wantStatus: 500},
		{name: "exchange_network", kc: okKeycloak, ex: nil, wantStage: stageExchangeNetwork},
		{name: "exchange_malformed", kc: okKeycloak, ex: badJSON, wantStage: stageMalformed},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			kcURL := closedURL(t)
			if tc.kc != nil {
				s := httptest.NewServer(tc.kc)
				defer s.Close()
				kcURL = s.URL
			}
			exURL := closedURL(t)
			if tc.ex != nil {
				s := httptest.NewServer(tc.ex)
				defer s.Close()
				exURL = s.URL
			}
			secretPath := secretFileWith(t, testSecret)
			switch {
			case tc.absent:
				secretPath = filepath.Join(t.TempDir(), "absent")
			case tc.secret != "":
				secretPath = secretFileWith(t, tc.secret)
			}
			cfg := WIFConfig{
				KeycloakTokenURL: kcURL + "/token", KeycloakClientID: "kivra-agent", ClientSecretFile: secretPath,
				AnthropicTokenURL: exURL + "/v1/oauth/token", FederationRuleID: "r", OrganizationID: "o", ServiceAccountID: "s",
			}
			a := NewWIFAuth(cfg, http.DefaultClient, nil)
			_, err := a.Authorize(context.Background())
			var ae *AuthError
			if !errors.As(err, &ae) {
				t.Fatalf("want *AuthError, got %T %v", err, err)
			}
			if ae.Stage != tc.wantStage || ae.Status != tc.wantStatus {
				t.Fatalf("stage=%q status=%d, want stage=%q status=%d", ae.Stage, ae.Status, tc.wantStage, tc.wantStatus)
			}
			for _, bad := range []string{logMarker, testSecret, "jwt", "at-", "127.0.0.1"} {
				if strings.Contains(err.Error(), bad) {
					t.Fatalf("error message leaks %q: %v", bad, err)
				}
			}
		})
	}
}

// closedURL returns a base URL that refuses connections (a server that has already been shut down).
func closedURL(t *testing.T) string {
	t.Helper()
	s := httptest.NewServer(http.NotFoundHandler())
	u := s.URL
	s.Close()
	return u
}

// TestAuthFailureLogLine checks the deny line that anthropicHandler writes for each failure kind.
// The log is captured with the arguments formatted, so a leaked value would show up.
func TestAuthFailureLogLine(t *testing.T) {
	kc := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, logMarker+" "+testSecret, http.StatusUnauthorized)
	}))
	defer kc.Close()
	var lines []string
	logf := func(format string, args ...any) { lines = append(lines, fmt.Sprintf(format, args...)) }
	cfg := WIFConfig{
		KeycloakTokenURL: kc.URL + "/token", KeycloakClientID: "kivra-agent", ClientSecretFile: secretFileWith(t, testSecret),
		AnthropicTokenURL: kc.URL + "/v1/oauth/token", FederationRuleID: "r", OrganizationID: "o", ServiceAccountID: "s",
	}
	h := &anthropicHandler{upstream: kc.URL, auth: NewWIFAuth(cfg, http.DefaultClient, nil), client: http.DefaultClient, logf: logf}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/anthropic/v1/messages", strings.NewReader("{}")))
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status %d, want 503", rec.Code)
	}
	cfg.KeycloakTokenURL = closedURL(t) + "/token"
	h.auth = NewWIFAuth(cfg, http.DefaultClient, nil)
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/anthropic/v1/messages", strings.NewReader("{}")))

	if len(lines) != 2 {
		t.Fatalf("want 2 log lines, got %d: %q", len(lines), lines)
	}
	want := []string{
		"reason=authorization_unavailable stage=keycloak upstream_status=401",
		"reason=authorization_unavailable stage=keycloak_network\n",
	}
	for i, w := range want {
		if !strings.Contains(lines[i], strings.TrimSuffix(w, "\n")) || strings.Contains(lines[i], "upstream_status") != (i == 0) {
			t.Errorf("line %d = %q, want it to contain %q", i, lines[i], w)
		}
	}
	for _, l := range lines {
		for _, bad := range []string{logMarker, testSecret, "jwt", "127.0.0.1", "connection refused"} {
			if strings.Contains(l, bad) {
				t.Fatalf("log line leaks %q: %q", bad, l)
			}
		}
	}
}
