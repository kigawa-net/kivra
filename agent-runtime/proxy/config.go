package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"regexp"
	"strings"
)

// Config holds the proxy settings and the optional registry of named WIF accounts.
type Config struct {
	Listen        string
	AuthMode      string // "wif" (production) or "static-test" (tests only: fixed fake key)
	Upstream      string // Anthropic API base URL (https://api.anthropic.com in production)
	AllowlistFile string
	StaticKey     string // static-test only: fake key value, sent as x-api-key
	WIF           WIFConfig
	Accounts      []WIFAccount
}

// Keep this pattern in sync with DockerPatterns.ACCOUNT.
var accountIDPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,62}$`)

func validAccountID(id string) bool {
	return accountIDPattern.MatchString(id) && !strings.Contains(id, "sk-ant-")
}

const accountsFileEnv = "KIVRA_PROXY_WIF_ACCOUNTS_FILE"

// WIFAccount contains only the non-secret settings that vary by account.
type WIFAccount struct {
	ID               string `json:"id"`
	FederationRuleID string `json:"federation_rule_id"`
	OrganizationID   string `json:"organization_id"`
	ServiceAccountID string `json:"service_account_id"`
	WorkspaceID      string `json:"workspace_id"`
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
			clientSecret:      getenv("KIVRA_PROXY_KEYCLOAK_CLIENT_SECRET"),
			AnthropicTokenURL: orDefault(getenv("KIVRA_PROXY_ANTHROPIC_TOKEN_URL"), defaultAnthropicTok),
			FederationRuleID:  getenv("KIVRA_PROXY_WIF_FEDERATION_RULE_ID"),
			OrganizationID:    getenv("KIVRA_PROXY_WIF_ORGANIZATION_ID"),
			ServiceAccountID:  getenv("KIVRA_PROXY_WIF_SERVICE_ACCOUNT_ID"),
			WorkspaceID:       getenv("KIVRA_PROXY_WIF_WORKSPACE_ID"),
		},
	}
	if v := getenv("KIVRA_PROXY_KEYCLOAK_ALLOWED_IP"); v != "" {
		ip := net.ParseIP(v)
		if ip == nil || ip.IsUnspecified() {
			return c, errors.New("KIVRA_PROXY_KEYCLOAK_ALLOWED_IP must be one IP address")
		}
		c.WIF.KeycloakAllowedIP = ip
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
	if path := getenv(accountsFileEnv); path != "" {
		accounts, err := loadWIFAccounts(path)
		if err != nil {
			return c, err
		}
		c.Accounts = accounts
	}
	return c, nil
}

func loadWIFAccounts(path string) ([]WIFAccount, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, errors.New(accountsFileEnv + " could not be read")
	}
	defer f.Close()
	decoder := json.NewDecoder(f)
	decoder.DisallowUnknownFields()
	var accounts []WIFAccount
	if err := decoder.Decode(&accounts); err != nil || accounts == nil {
		return nil, errors.New(accountsFileEnv + " must contain a JSON array of WIF accounts")
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return nil, errors.New(accountsFileEnv + " must contain one JSON array")
	}
	seen := make(map[string]bool, len(accounts))
	for _, a := range accounts {
		if !validAccountID(a.ID) {
			return nil, errors.New(accountsFileEnv + " contains an invalid account id")
		}
		if seen[a.ID] {
			return nil, errors.New(accountsFileEnv + " contains a duplicate account id")
		}
		seen[a.ID] = true
		for _, id := range []string{a.FederationRuleID, a.OrganizationID, a.ServiceAccountID, a.WorkspaceID} {
			if id == "" || strings.TrimSpace(id) != id {
				return nil, errors.New(accountsFileEnv + " contains a missing or invalid WIF id")
			}
		}
	}
	return accounts, nil
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
		return "", errSecretUnreadable
	}
	v := strings.TrimSpace(string(b))
	if v == "" {
		return "", errSecretEmpty
	}
	return v, nil
}
