package main

import (
	"bufio"
	"io"
	"os"
	"strings"
)

// Allowlist holds the destination hosts that CONNECT may reach (port 443 only, exact match).
type Allowlist struct{ hosts map[string]bool }

func LoadAllowlist(path string) (*Allowlist, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return ParseAllowlist(f)
}

func ParseAllowlist(r io.Reader) (*Allowlist, error) {
	a := &Allowlist{hosts: map[string]bool{}}
	sc := bufio.NewScanner(r)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		a.hosts[normalizeHost(line)] = true
	}
	return a, sc.Err()
}

func (a *Allowlist) Allowed(host string) bool {
	return a.hosts[normalizeHost(host)]
}

func normalizeHost(h string) string {
	return strings.TrimSuffix(strings.ToLower(strings.TrimSpace(h)), ".")
}
