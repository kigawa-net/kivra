package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

const kcHost = "user.kigawa.net"

// fakeDial records which dialer was used and the address it got.
type fakeDial struct {
	name  string
	calls []string
}

func (f *fakeDial) dial(_ context.Context, _, addr string) (net.Conn, error) {
	f.calls = append(f.calls, f.name+" "+addr)
	return nil, errors.New("fake")
}

func TestKeycloakDialFuncExceptionIsNarrow(t *testing.T) {
	allowed := net.ParseIP("10.0.0.240")
	cases := []struct {
		name   string
		addr   string
		lookup []net.IP // nil means the lookup fails
		want   string   // "exact" or "plain"
	}{
		{"matching host, port, ip", kcHost + ":443", []net.IP{allowed}, "exact"},
		{"host case-insensitive", "USER.kigawa.net:443", []net.IP{allowed}, "exact"},
		{"one of several answers matches", kcHost + ":443", []net.IP{net.ParseIP("10.0.0.241"), allowed}, "exact"},
		{"other private ip for keycloak host", kcHost + ":443", []net.IP{net.ParseIP("10.0.0.241")}, "plain"},
		{"other port", kcHost + ":8443", []net.IP{allowed}, "plain"},
		{"other host resolving to allowed ip", "api.anthropic.com:443", []net.IP{allowed}, "plain"},
		{"lookup fails", kcHost + ":443", nil, "plain"},
		{"malformed address", "no-port", []net.IP{allowed}, "plain"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			plain := &fakeDial{name: "plain"}
			exact := &fakeDial{name: "exact"}
			lookup := func(context.Context, string) ([]net.IPAddr, error) {
				if tc.lookup == nil {
					return nil, errors.New("no such host")
				}
				var out []net.IPAddr
				for _, ip := range tc.lookup {
					out = append(out, net.IPAddr{IP: ip})
				}
				return out, nil
			}
			d := keycloakDialFunc(plain.dial, kcHost, allowed, lookup, exact.dial)
			_, _ = d(context.Background(), "tcp", tc.addr)
			if tc.want == "exact" {
				if len(exact.calls) != 1 || exact.calls[0] != "exact 10.0.0.240:443" || len(plain.calls) != 0 {
					t.Fatalf("exact=%v plain=%v", exact.calls, plain.calls)
				}
				return
			}
			if len(exact.calls) != 0 || len(plain.calls) != 1 || plain.calls[0] != "plain "+tc.addr {
				t.Fatalf("exact=%v plain=%v", exact.calls, plain.calls)
			}
		})
	}
}

func TestAllowOnlyIPControl(t *testing.T) {
	ctl := allowOnlyIP(net.ParseIP("10.0.0.240"))
	if err := ctl("tcp", "10.0.0.240:443", nil); err != nil {
		t.Fatalf("allowed address refused: %v", err)
	}
	for _, addr := range []string{"10.0.0.240:80", "10.0.0.241:443", "8.8.8.8:443", "10.0.0.240", "[::1]:443"} {
		if err := ctl("tcp", addr, nil); err == nil {
			t.Errorf("%s must be refused", addr)
		}
	}
}

// The CONNECT path keeps rejectNonPublic; the exception is never applied to it.
func TestConnectPathStillRefusesAllowedIP(t *testing.T) {
	if err := rejectNonPublic("tcp", "10.0.0.240:443", nil); err == nil {
		t.Fatal("CONNECT dial control must refuse 10.0.0.240")
	}
	allow := &Allowlist{hosts: map[string]bool{"10.0.0.240": true}}
	h := newConnectHandler(allow, func(string, ...any) {})
	req := httptest.NewRequest(http.MethodConnect, "/", nil)
	req.Host = "10.0.0.240:443"
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("CONNECT to private address: status %d, want 502 (dial refused)", rec.Code)
	}
}

func TestNewWIFHTTPClientUnsetIsUnchanged(t *testing.T) {
	base := &http.Client{}
	got, err := newWIFHTTPClient(WIFConfig{KeycloakTokenURL: "https://" + kcHost + "/x"}, base)
	if err != nil || got != base {
		t.Fatalf("unset must return the base client unchanged: %v", err)
	}
}

func TestNewWIFHTTPClientSetReplacesOnlyTheWIFClient(t *testing.T) {
	base := &http.Client{}
	cfg := WIFConfig{KeycloakTokenURL: "https://" + kcHost + "/x", KeycloakAllowedIP: net.ParseIP("10.0.0.240")}
	got, err := newWIFHTTPClient(cfg, base)
	if err != nil {
		t.Fatal(err)
	}
	if got == base || got.Transport == nil || base.Transport != nil {
		t.Fatal("set must return a separate client and leave the base client alone")
	}
	if _, ok := got.Transport.(*http.Transport); !ok {
		t.Fatalf("unexpected transport type %T", got.Transport)
	}
	bad := cfg
	bad.KeycloakTokenURL = "://bad"
	if _, err := newWIFHTTPClient(bad, base); err == nil {
		t.Fatal("invalid token URL must fail")
	}
}

func TestLoadConfigKeycloakAllowedIP(t *testing.T) {
	env := func(extra map[string]string) func(string) string {
		m := map[string]string{
			"KIVRA_PROXY_WIF_FEDERATION_RULE_ID": "r",
			"KIVRA_PROXY_WIF_ORGANIZATION_ID":    "o",
			"KIVRA_PROXY_WIF_SERVICE_ACCOUNT_ID": "s",
		}
		for k, v := range extra {
			m[k] = v
		}
		return func(k string) string { return m[k] }
	}
	c, err := LoadConfig(env(nil))
	if err != nil || c.WIF.KeycloakAllowedIP != nil {
		t.Fatalf("unset: err=%v ip=%v", err, c.WIF.KeycloakAllowedIP)
	}
	c, err = LoadConfig(env(map[string]string{"KIVRA_PROXY_KEYCLOAK_ALLOWED_IP": "10.0.0.240"}))
	if err != nil || !c.WIF.KeycloakAllowedIP.Equal(net.ParseIP("10.0.0.240")) {
		t.Fatalf("valid: err=%v ip=%v", err, c.WIF.KeycloakAllowedIP)
	}
	for _, v := range []string{"10.0.0.240/32", "10.0.0.0/8", "not-an-ip", "0.0.0.0", "10.0.0.240,10.0.0.241"} {
		if _, err := LoadConfig(env(map[string]string{"KIVRA_PROXY_KEYCLOAK_ALLOWED_IP": v})); err == nil {
			t.Errorf("%q must be rejected", v)
		}
	}
}
