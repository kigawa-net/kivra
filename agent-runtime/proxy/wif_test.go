package main

import (
	"context"
	"encoding/json"
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
