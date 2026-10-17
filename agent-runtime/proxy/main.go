// Command kivra-agent-proxy is the egress proxy for the kivra-agent runtime.
//
// It serves two roles on one port:
//   - /anthropic/... : forwards Claude Code API calls to Anthropic and adds the credential (WIF or static test key).
//     The container's own auth headers are always dropped.
//   - CONNECT        : HTTPS tunnel to allowlisted hosts only (Gradle and Maven endpoints).
//
// Logs contain method, route, decision, destination host, status and byte counts only.
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	if err := run(); err != nil {
		log.Printf("fatal: %v", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := LoadConfig(os.Getenv)
	if err != nil {
		return err
	}
	allow, err := LoadAllowlist(cfg.AllowlistFile)
	if err != nil {
		return errors.New("allowlist file could not be read")
	}
	logf := log.Printf

	client := &http.Client{
		Timeout:       0, // streaming responses are long-lived; the request context bounds them
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	var auth Authorizer
	switch cfg.AuthMode {
	case "wif":
		auth = NewWIFAuth(cfg.WIF, client, nil)
		logf("auth=wif upstream_host=%s", hostOf(cfg.Upstream))
	case "static-test":
		auth = StaticAuth{Key: cfg.StaticKey}
		logf("WARNING auth=static-test (test use only; not for production)")
	}

	srv := &proxyServer{
		connect:   newConnectHandler(allow, logf),
		anthropic: &anthropicHandler{upstream: cfg.Upstream, auth: auth, client: client, logf: logf},
		logf:      logf,
	}
	hs := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = hs.Shutdown(shutdownCtx)
	}()
	logf("listen=%s", cfg.Listen)
	if err := hs.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func hostOf(u string) string {
	// Only the host is logged, never the full URL.
	s := u
	for _, p := range []string{"https://", "http://"} {
		if len(s) > len(p) && s[:len(p)] == p {
			s = s[len(p):]
		}
	}
	for i, c := range s {
		if c == '/' {
			return s[:i]
		}
	}
	return s
}
