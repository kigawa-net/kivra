package main

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
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
	logf := func(format string, args ...any) { *logs = append(*logs, format) }
	return &proxyServer{
		connect:   newConnectHandler(&Allowlist{hosts: map[string]bool{}}, logf),
		anthropic: &anthropicHandler{upstream: upstream, auth: auth, client: http.DefaultClient, logf: logf},
		logf:      logf,
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

func TestConfigRequiresWIFIDs(t *testing.T) {
	_, err := LoadConfig(func(string) string { return "" })
	if err == nil || !strings.Contains(err.Error(), "KIVRA_PROXY_WIF_ORGANIZATION_ID") {
		t.Fatalf("want missing-variable error naming the variable, got %v", err)
	}
	if strings.Contains(err.Error(), "=") {
		t.Fatal("config errors must not echo values")
	}
}
