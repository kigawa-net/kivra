package main

import (
	"errors"
	"fmt"
	"os"
	"strings"
)

// Config holds the proxy settings. It is read from environment variables only.
// Secret values are never stored here: the Keycloak client secret is read from a file at each exchange.
type Config struct {
	Listen        string
	AuthMode      string // "wif" (production) or "static-test" (tests only: fixed fake key)
	Upstream      string // Anthropic API base URL (https://api.anthropic.com in production)
	AllowlistFile string
	StaticKey     string // static-test only: fake key value, sent as x-api-key
	WIF           WIFConfig
}

// Defaults. The production endpoints are fixed; tests override them through environment variables.
const (
	defaultListen        = ":8080"
	defaultUpstream      = "https://api.anthropic.com"
	defaultAllowlist     = "/etc/kivra-agent-proxy/allowlist.txt"
	defaultKeycloakToken = "https://user.kigawa.net/realms/kigawa-net/protocol/openid-connect/token"
	defaultClientID      = "kivra-agent"
	defaultSecretFile    = "/run/secrets/keycloak_client_secret"
	defaultAnthropicTok  = "https://api.anthropic.com/v1/oauth/token"
)

// LoadConfig reads the environment. Error messages name variables, never their values.
func LoadConfig(getenv func(string) string) (Config, error) {
	c := Config{
		Listen:        orDefault(getenv("KIVRA_PROXY_LISTEN"), defaultListen),
		AuthMode:      orDefault(getenv("KIVRA_PROXY_AUTH"), "wif"),
		Upstream:      strings.TrimRight(orDefault(getenv("KIVRA_PROXY_ANTHROPIC_UPSTREAM"), defaultUpstream), "/"),
		AllowlistFile: orDefault(getenv("KIVRA_PROXY_ALLOWLIST_FILE"), defaultAllowlist),
		StaticKey:     getenv("KIVRA_PROXY_STATIC_TEST_KEY"),
		WIF: WIFConfig{
			KeycloakTokenURL:  orDefault(getenv("KIVRA_PROXY_KEYCLOAK_TOKEN_URL"), defaultKeycloakToken),
			KeycloakClientID:  orDefault(getenv("KIVRA_PROXY_KEYCLOAK_CLIENT_ID"), defaultClientID),
			ClientSecretFile:  orDefault(getenv("KIVRA_PROXY_KEYCLOAK_CLIENT_SECRET_FILE"), defaultSecretFile),
			AnthropicTokenURL: orDefault(getenv("KIVRA_PROXY_ANTHROPIC_TOKEN_URL"), defaultAnthropicTok),
			FederationRuleID:  getenv("KIVRA_PROXY_WIF_FEDERATION_RULE_ID"),
			OrganizationID:    getenv("KIVRA_PROXY_WIF_ORGANIZATION_ID"),
			ServiceAccountID:  getenv("KIVRA_PROXY_WIF_SERVICE_ACCOUNT_ID"),
			WorkspaceID:       getenv("KIVRA_PROXY_WIF_WORKSPACE_ID"),
		},
	}
	switch c.AuthMode {
	case "wif":
		var missing []string
		for name, v := range map[string]string{
			"KIVRA_PROXY_WIF_FEDERATION_RULE_ID": c.WIF.FederationRuleID,
			"KIVRA_PROXY_WIF_ORGANIZATION_ID":    c.WIF.OrganizationID,
			"KIVRA_PROXY_WIF_SERVICE_ACCOUNT_ID": c.WIF.ServiceAccountID,
		} {
			if v == "" {
				missing = append(missing, name)
			}
		}
		if len(missing) > 0 {
			return c, fmt.Errorf("missing environment variables: %s", strings.Join(sortedCopy(missing), ", "))
		}
	case "static-test":
		if c.StaticKey == "" {
			return c, errors.New("KIVRA_PROXY_AUTH=static-test requires KIVRA_PROXY_STATIC_TEST_KEY (test use only)")
		}
	default:
		return c, errors.New("KIVRA_PROXY_AUTH must be \"wif\" or \"static-test\"")
	}
	return c, nil
}

func orDefault(v, d string) string {
	if v == "" {
		return d
	}
	return v
}

func sortedCopy(s []string) []string {
	out := append([]string(nil), s...)
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j] < out[j-1]; j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

// readFileTrimmed reads a secret file. The error never includes the content.
func readFileTrimmed(path string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", errors.New("secret file could not be read")
	}
	v := strings.TrimSpace(string(b))
	if v == "" {
		return "", errors.New("secret file is empty")
	}
	return v, nil
}
