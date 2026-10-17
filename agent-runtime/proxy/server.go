package main

import (
	"net/http"
	"strings"
)

// proxyServer routes requests:
//   - CONNECT host:443 to the egress allowlist (HTTPS tunnel),
//   - /anthropic/... to the Anthropic API handler (credentials added here),
//   - everything else is refused.
type proxyServer struct {
	connect   *connectHandler
	anthropic *anthropicHandler
	logf      func(format string, args ...any)
}

func (s *proxyServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == http.MethodConnect:
		s.connect.ServeHTTP(w, r)
	case r.URL.Path == "/anthropic" || strings.HasPrefix(r.URL.Path, "/anthropic/"):
		s.anthropic.ServeHTTP(w, r)
	default:
		s.logf("deny method=%s path=%s reason=no_route", r.Method, r.URL.Path)
		http.Error(w, "forbidden", http.StatusForbidden)
	}
}
