package main

import (
	"io"
	"net/http"
	"strings"
)

// anthropicPaths are the only API paths forwarded. Claude Code calls the messages endpoint
// (and count_tokens). Anything else is refused with 403.
// TODO(live-smoke): if the live run needs another path, add it here and to verify/proxy-mock.sh.
var anthropicPaths = map[string]bool{
	"/v1/messages":              true,
	"/v1/messages/count_tokens": true,
}

// hopByHop headers and headers that carry credentials. Request-side auth headers are always dropped.
var hopByHop = map[string]bool{
	"connection":          true,
	"keep-alive":          true,
	"proxy-authenticate":  true,
	"proxy-authorization": true,
	"proxy-connection":    true,
	"te":                  true,
	"trailer":             true,
	"transfer-encoding":   true,
	"upgrade":             true,
	"authorization":       true,
	"x-api-key":           true,
	"cookie":              true,
}

type anthropicHandler struct {
	upstream string // base URL, e.g. https://api.anthropic.com
	auth     Authorizer
	client   *http.Client
	logf     func(format string, args ...any)
}

func (h *anthropicHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/anthropic")
	if r.Method != http.MethodPost || !anthropicPaths[path] {
		h.logf("deny method=%s path=%s reason=path_not_allowed", r.Method, path)
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	cred, err := h.auth.Authorize(r.Context())
	if err != nil {
		h.logf("deny method=%s path=%s reason=authorization_unavailable", r.Method, path)
		http.Error(w, "upstream authorization unavailable", http.StatusServiceUnavailable)
		return
	}
	target := h.upstream + path
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}
	out, err := http.NewRequestWithContext(r.Context(), http.MethodPost, target, r.Body)
	if err != nil {
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	out.ContentLength = r.ContentLength
	for k, vs := range r.Header {
		if hopByHop[strings.ToLower(k)] {
			continue
		}
		for _, v := range vs {
			out.Header.Add(k, v)
		}
	}
	out.Header.Set(cred.Name, cred.Value)

	resp, err := h.client.Do(out)
	if err != nil {
		h.logf("error method=%s path=%s reason=upstream_unreachable", r.Method, path)
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusUnauthorized {
		h.auth.Invalidate(cred)
	}
	for k, vs := range resp.Header {
		if hopByHop[strings.ToLower(k)] {
			continue
		}
		for _, v := range vs {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	n, _ := copyFlushing(w, resp.Body)
	h.logf("allow method=%s path=%s status=%d bytes_down=%d", r.Method, path, resp.StatusCode, n)
}

// copyFlushing streams the body and flushes each chunk, so SSE (streaming) responses are not buffered.
func copyFlushing(w http.ResponseWriter, body io.Reader) (int64, error) {
	rc := http.NewResponseController(w)
	buf := make([]byte, 32*1024)
	var total int64
	for {
		n, err := body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return total, werr
			}
			total += int64(n)
			_ = rc.Flush()
		}
		if err != nil {
			if err == io.EOF {
				return total, nil
			}
			return total, err
		}
	}
}
