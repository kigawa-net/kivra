package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

type recAuth struct {
	cred        Credential
	invalidated atomic.Int64
}

func (r *recAuth) Authorize(context.Context) (Credential, error) { return r.cred, nil }
func (r *recAuth) Invalidate(Credential)                         { r.invalidated.Add(1) }

func newAnthropicTestServer(t *testing.T, auth Authorizer, upstream string, logs *[]string) *proxyServer {
	t.Helper()
	logf := func(format string, args ...any) { *logs = append(*logs, fmt.Sprintf(format, args...)) }
	return &proxyServer{
		connect:   newConnectHandler(&Allowlist{hosts: map[string]bool{}}, logf),
		anthropic: &anthropicHandler{upstream: upstream, auth: auth, client: http.DefaultClient, logf: logf},
		logf:      logf,
	}
}

func TestAnthropicRoutesNamedAccounts(t *testing.T) {
	type call struct{ path, key string }
	calls := make(chan call, 3)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls <- call{r.URL.RequestURI(), r.Header.Get("X-Api-Key")}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer up.Close()
	var logs []string
	srv := newAnthropicTestServer(t, StaticAuth{Key: "fake-default"}, up.URL, &logs)
	srv.anthropic.accounts = map[string]Authorizer{
		"repo-a": StaticAuth{Key: "fake-alpha"},
		"repo-b": StaticAuth{Key: "fake-beta"},
	}
	for _, tc := range []struct {
		path string
		want call
	}{
		{"/anthropic/v1/messages", call{"/v1/messages", "fake-default"}},
		{"/anthropic/%761/messages", call{"/v1/messages", "fake-default"}},
		{"/anthropic/repo-a/v1/messages?beta=true", call{"/v1/messages?beta=true", "fake-alpha"}},
		{"/anthropic/repo-b/v1/messages/count_tokens", call{"/v1/messages/count_tokens", "fake-beta"}},
	} {
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, tc.path, nil))
		if rec.Code != http.StatusNoContent {
			t.Fatalf("%s: status %d", tc.path, rec.Code)
		}
		if got := <-calls; got != tc.want {
			t.Errorf("%s: upstream request = %+v, want %+v", tc.path, got, tc.want)
		}
	}
	for _, path := range []string{
		"/anthropic/unknown/v1/messages",
		"/anthropic/Repo-a/v1/messages",
		"/anthropic/repo_a/v1/messages",
		"/anthropic/-repo/v1/messages",
		"/anthropic/" + strings.Repeat("a", 64) + "/v1/messages",
		"/anthropic//v1/messages",
		"/anthropic/%72epo-a/v1/messages",
		"/anthropic/repo-a%2fv1/messages",
		"/anthropic/repo-a/v1/files",
	} {
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, path, nil))
		if rec.Code != http.StatusForbidden {
			t.Errorf("%s: status %d, want 403", path, rec.Code)
		}
	}
	if len(calls) != 0 {
		t.Fatal("denied requests reached upstream")
	}
	if joined := strings.Join(logs, "\n"); !strings.Contains(joined, `account_id="repo-a"`) ||
		strings.Contains(joined, "fake-") || strings.Contains(joined, "beta=true") {
		t.Errorf("unexpected logs: %s", joined)
	}
}

func TestAnthropicReplacesContainerAuthHeaders(t *testing.T) {
	var gotAuth, gotKey string
	var gotPath string
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotKey = r.Header.Get("X-Api-Key")
		gotPath = r.URL.RequestURI()
		_, _ = io.WriteString(w, `{"ok":true}`)
	}))
	defer up.Close()
	var logs []string
	auth := &recAuth{cred: bearer("sk-ant-oat01-FAKE-TOKEN")}
	srv := newAnthropicTestServer(t, auth, up.URL, &logs)

	req := httptest.NewRequest(http.MethodPost, "/anthropic/v1/messages?beta=true", strings.NewReader(`{}`))
	req.Header.Set("X-Api-Key", "container-dummy-key")
	req.Header.Set("Authorization", "Bearer container-supplied")
	req.Header.Set("Cookie", "session=abc")
	req.Header.Set("anthropic-version", "2023-06-01")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != 200 {
		t.Fatalf("status %d", rec.Code)
	}
	if gotAuth != "Bearer sk-ant-oat01-FAKE-TOKEN" {
		t.Fatalf("upstream Authorization = %q", gotAuth)
	}
	if gotKey != "" {
		t.Fatal("container x-api-key must not reach upstream")
	}
	if gotPath != "/v1/messages?beta=true" {
		t.Fatalf("path = %s", gotPath)
	}
	for _, l := range logs {
		if strings.Contains(l, "FAKE") || strings.Contains(l, "container") {
			t.Fatalf("log line leaks values: %s", l)
		}
	}
}

func TestAnthropicRejectsOtherPathsAndMethods(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("forbidden request reached upstream: %s %s", r.Method, r.URL.Path)
	}))
	defer up.Close()
	var logs []string
	srv := newAnthropicTestServer(t, &recAuth{cred: bearer("t")}, up.URL, &logs)
	cases := []struct{ method, path string }{
		{http.MethodPost, "/anthropic/v1/files"},
		{http.MethodPost, "/anthropic/v1/messages/../files"},
		{http.MethodGet, "/anthropic/v1/messages"},
		{http.MethodPost, "/anthropic/"},
	}
	for _, c := range cases {
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, httptest.NewRequest(c.method, c.path, nil))
		if rec.Code != http.StatusForbidden {
			t.Errorf("%s %s: status %d, want 403", c.method, c.path, rec.Code)
		}
	}
}

func TestAnthropic401InvalidatesAndIsNotRetried(t *testing.T) {
	var calls atomic.Int64
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
	}))
	defer up.Close()
	var logs []string
	auth := &recAuth{cred: bearer("t")}
	srv := newAnthropicTestServer(t, auth, up.URL, &logs)
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/anthropic/v1/messages", strings.NewReader("{}")))
	if rec.Code != 401 || calls.Load() != 1 || auth.invalidated.Load() != 1 {
		t.Fatalf("status=%d upstream_calls=%d invalidated=%d", rec.Code, calls.Load(), auth.invalidated.Load())
	}
}

func TestConnectDeniesNonAllowlistedAndNonPort443(t *testing.T) {
	var logs []string
	h := newConnectHandler(&Allowlist{hosts: map[string]bool{"repo.maven.apache.org": true}}, func(f string, a ...any) { logs = append(logs, f) })
	for _, host := range []string{"example.com:443", "repo.maven.apache.org:80", "169.254.169.254:443"} {
		req := httptest.NewRequest(http.MethodConnect, "/", nil)
		req.Host = host
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusForbidden {
			t.Errorf("%s: status %d, want 403", host, rec.Code)
		}
	}
}

func TestConnectRefusesNonPublicAddress(t *testing.T) {
	// Allowlisted name that resolves to loopback: the dialer's Control hook must refuse it.
	if err := rejectNonPublic("tcp", "127.0.0.1:443", nil); err == nil {
		t.Fatal("loopback must be refused")
	}
	if err := rejectNonPublic("tcp", "10.1.2.3:443", nil); err == nil {
		t.Fatal("private address must be refused")
	}
	if err := rejectNonPublic("tcp", "8.8.8.8:443", nil); err != nil {
		t.Fatalf("public address must be allowed: %v", err)
	}
}

func TestAllowlistParsing(t *testing.T) {
	a, err := ParseAllowlist(strings.NewReader("# c\n\nRepo.Maven.Apache.org.\n  services.gradle.org  \n"))
	if err != nil {
		t.Fatal(err)
	}
	if !a.Allowed("repo.maven.apache.org") || !a.Allowed("SERVICES.gradle.org") {
		t.Fatal("exact, case-insensitive match expected")
	}
	if a.Allowed("evil.repo.maven.apache.org") || a.Allowed("example.com") {
		t.Fatal("no wildcard or suffix match")
	}
}

// The shipped allowlist file is exactly the hosts the runtime needs: Gradle's three, and github.com for the Pod's base commit fetch
// (ADR 0010 追補 7 PR 5). A new host is a deliberate change of this test.
func TestShippedAllowlistIsExactlyTheNeededHosts(t *testing.T) {
	a, err := LoadAllowlist("allowlist.txt")
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"services.gradle.org", "plugins.gradle.org", "repo.maven.apache.org", "github.com"}
	if len(a.hosts) != len(want) {
		t.Fatalf("allowlist has %d hosts, want %d: %v", len(a.hosts), len(want), a.hosts)
	}
	for _, h := range want {
		if !a.Allowed(h) {
			t.Errorf("%s must be allowed", h)
		}
	}
	for _, h := range []string{"codeload.github.com", "api.github.com", "raw.githubusercontent.com", "objects.githubusercontent.com", "gist.github.com", "example.com"} {
		if a.Allowed(h) {
			t.Errorf("%s must not be allowed", h)
		}
	}
}

func TestConfigRequiresWIFIDs(t *testing.T) {
	_, err := LoadConfig(func(string) string { return "" })
	if err == nil || !strings.Contains(err.Error(), "KIVRA_PROXY_WIF_ORGANIZATION_ID") {
		t.Fatalf("want missing-variable error naming the variable, got %v", err)
	}
	if strings.Contains(err.Error(), "=") {
		t.Fatal("config errors must not echo values")
	}
}

func TestConfigLoadsAccountRegistry(t *testing.T) {
	path := filepath.Join(t.TempDir(), "accounts.json")
	env := map[string]string{
		accountsFileEnv:                      path,
		"KIVRA_PROXY_WIF_FEDERATION_RULE_ID": "default-rule",
		"KIVRA_PROXY_WIF_ORGANIZATION_ID":    "default-org",
		"KIVRA_PROXY_WIF_SERVICE_ACCOUNT_ID": "default-service",
	}
	account := `{"id":"repo-a","federation_rule_id":"rule","organization_id":"org","service_account_id":"svc","workspace_id":"ws"}`
	write := func(body string) {
		t.Helper()
		if err := os.WriteFile(path, []byte(body), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write("[" + account + "]")
	cfg, err := LoadConfig(func(key string) string { return env[key] })
	if err != nil || len(cfg.Accounts) != 1 {
		t.Fatalf("accounts = %v, err = %v", cfg.Accounts, err)
	}
	named := cfg.WIF.forAccount(cfg.Accounts[0])
	if cfg.Accounts[0].ID != "repo-a" || named.FederationRuleID != "rule" || named.OrganizationID != "org" ||
		named.ServiceAccountID != "svc" || named.WorkspaceID != "ws" || cfg.WIF.FederationRuleID != "default-rule" {
		t.Fatal("named WIF settings did not override the default IDs independently")
	}
	for _, body := range []string{
		"null", "{}", "[] []",
		"[" + account + "," + account + "]",
		"[" + strings.Replace(account, "repo-a", "../repo-a", 1) + "]",
		"[" + strings.Replace(account, `"ws"`, `""`, 1) + "]",
		"[" + strings.Replace(account, "workspace_id", "unexpected-marker", 1) + "]",
	} {
		write(body)
		_, err := LoadConfig(func(key string) string { return env[key] })
		if err == nil || !strings.Contains(err.Error(), accountsFileEnv) || strings.Contains(err.Error(), "unexpected-marker") {
			t.Errorf("invalid registry accepted or leaked values: %v", err)
		}
	}
}
