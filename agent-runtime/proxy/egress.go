package main

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"syscall"
	"time"
)

const dialTimeout = 10 * time.Second

// connectHandler tunnels HTTPS (CONNECT host:443) to allowlisted hosts only.
type connectHandler struct {
	allow *Allowlist
	logf  func(format string, args ...any)
	dial  func(network, addr string) (net.Conn, error) // overridable in tests
}

func newConnectHandler(allow *Allowlist, logf func(string, ...any)) *connectHandler {
	d := &net.Dialer{Timeout: dialTimeout, Control: rejectNonPublic}
	return &connectHandler{
		allow: allow,
		logf:  logf,
		dial:  func(network, addr string) (net.Conn, error) { return d.Dial(network, addr) },
	}
}

// rejectNonPublic refuses to connect to loopback, private, link-local or unspecified addresses,
// so an allowlisted name that resolves to an internal address cannot reach the internal network.
func rejectNonPublic(network, address string, _ syscall.RawConn) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return err
	}
	ip := net.ParseIP(host)
	if ip == nil || ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() ||
		ip.IsUnspecified() || ip.IsMulticast() || ip.IsInterfaceLocalMulticast() {
		return fmt.Errorf("destination address is not public")
	}
	return nil
}

func (h *connectHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	host, port, err := net.SplitHostPort(r.Host)
	if err != nil || port != "443" {
		h.logf("deny method=CONNECT dest=%s reason=port_not_allowed", r.Host)
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	if !h.allow.Allowed(host) {
		h.logf("deny method=CONNECT dest=%s reason=not_allowlisted", r.Host)
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	upstream, err := h.dial("tcp", net.JoinHostPort(host, port))
	if err != nil {
		h.logf("error method=CONNECT dest=%s reason=dial_failed", r.Host)
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		upstream.Close()
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	client, buf, err := hj.Hijack()
	if err != nil {
		upstream.Close()
		return
	}
	if _, err := client.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
		upstream.Close()
		client.Close()
		return
	}
	var up, down int64
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		// buf may hold bytes the client already sent after the CONNECT request.
		up, _ = io.Copy(upstream, buf)
		closeWrite(upstream)
	}()
	go func() {
		defer wg.Done()
		down, _ = io.Copy(client, upstream)
		closeWrite(client)
	}()
	wg.Wait()
	upstream.Close()
	client.Close()
	h.logf("allow method=CONNECT dest=%s status=200 bytes_up=%d bytes_down=%d", r.Host, up, down)
}

type closeWriter interface{ CloseWrite() error }

func closeWrite(c io.Writer) {
	if cw, ok := c.(closeWriter); ok {
		_ = cw.CloseWrite()
	}
}
