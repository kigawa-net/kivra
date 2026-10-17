package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// WIFConfig: Workload Identity Federation settings. The four IDs are not secret.
// The Keycloak client secret is read from ClientSecretFile at every Keycloak call.
type WIFConfig struct {
	KeycloakTokenURL  string
	KeycloakClientID  string
	ClientSecretFile  string
	AnthropicTokenURL string
	FederationRuleID  string
	OrganizationID    string
	ServiceAccountID  string
	WorkspaceID       string
}

const (
	wifGrantType          = "urn:ietf:params:oauth:grant-type:jwt-bearer"
	recommendedRefreshSec = 120 // refresh if the token expires within this window; a failure keeps the cache
	requiredRefreshSec    = 30  // refresh is mandatory inside this window; a failure is an error
	maxResponseBytes      = 1 << 20
)

// Fixed error messages. They never contain the JWT, the access token, response bodies or the secret.
var (
	errKeycloak  = errors.New("identity provider token request failed")
	errExchange  = errors.New("anthropic token exchange failed")
	errMalformed = errors.New("token response is malformed")
)

// Stage labels for AuthError.Stage. These fixed labels are the only failure details the proxy logs.
const (
	stageSecretUnreadable = "secret_unreadable"
	stageSecretEmpty      = "secret_empty"
	stageKeycloak         = "keycloak"         // Keycloak answered with a non-200 status
	stageKeycloakNetwork  = "keycloak_network" // no HTTP response from Keycloak (DNS, refused, timeout)
	stageExchange         = "exchange"         // Anthropic token endpoint answered with a non-200 status
	stageExchangeNetwork  = "exchange_network" // no HTTP response from the Anthropic token endpoint
	stageMalformed        = "malformed"        // a 200 response whose body is not the expected JSON
	stageOther            = "other"
)

var (
	errSecretUnreadable = errors.New("secret file could not be read")
	errSecretEmpty      = errors.New("secret file is empty")
)

// AuthError is what Authorize returns on failure. Stage names the failed step and Status is the HTTP status
// of the Keycloak or Anthropic response (0 when there was none). Neither field can carry a response body,
// header, token or secret, so both are safe to log. The message is the fixed text of the cause.
type AuthError struct {
	Stage  string
	Status int
	cause  error
}

func (e *AuthError) Error() string {
	if e.Status != 0 {
		return fmt.Sprintf("%s (HTTP %d)", e.cause.Error(), e.Status)
	}
	return e.cause.Error()
}

func (e *AuthError) Unwrap() error { return e.cause }

func authErr(stage string, status int, cause error) error {
	return &AuthError{Stage: stage, Status: status, cause: cause}
}

// WIFAuth exchanges a fresh Keycloak JWT for an Anthropic access token and caches the access token.
//
// Each exchange uses a NEW JWT: the JWT jti is single-use, so a JWT is never sent twice.
// Concurrent callers share one exchange (the mutex is held across the network calls).
type WIFAuth struct {
	cfg    WIFConfig
	client *http.Client
	now    func() time.Time

	mu        sync.Mutex
	cache     *cachedToken
	exchanges int // number of successful exchanges (observable in tests)
}

type cachedToken struct {
	token     string
	expiresAt time.Time
}

func NewWIFAuth(cfg WIFConfig, client *http.Client, now func() time.Time) *WIFAuth {
	if now == nil {
		now = time.Now
	}
	c := *client
	// The assertion and the access token must not be forwarded to another host by a redirect.
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &WIFAuth{cfg: cfg, client: &c, now: now}
}

func (w *WIFAuth) Authorize(ctx context.Context) (Credential, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	now := w.now()
	c := w.cache
	switch {
	case c != nil && now.Before(c.expiresAt.Add(-recommendedRefreshSec*time.Second)):
		return bearer(c.token), nil
	case c != nil && now.Before(c.expiresAt.Add(-requiredRefreshSec*time.Second)):
		// Recommended refresh: if it fails, the cached token is still valid.
		if fresh, err := w.exchange(ctx); err == nil {
			w.cache = fresh
			return bearer(fresh.token), nil
		}
		return bearer(c.token), nil
	default:
		// Missing or about to expire: refresh is mandatory.
		fresh, err := w.exchange(ctx)
		if err != nil {
			return Credential{}, err
		}
		w.cache = fresh
		return bearer(fresh.token), nil
	}
}

// Invalidate drops the cached token when it is the one the upstream rejected (401).
// The request is not retried; the next Authorize performs a new exchange.
func (w *WIFAuth) Invalidate(cred Credential) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.cache != nil && cred.secret != "" && cred.secret == w.cache.token {
		w.cache = nil
	}
}

// ExchangeCount reports how many exchanges succeeded (used by tests).
func (w *WIFAuth) ExchangeCount() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.exchanges
}

func (w *WIFAuth) exchange(ctx context.Context) (*cachedToken, error) {
	jwt, err := w.fetchAssertion(ctx)
	if err != nil {
		return nil, err
	}
	body, err := json.Marshal(exchangeRequest{
		GrantType:        wifGrantType,
		Assertion:        jwt,
		FederationRuleID: w.cfg.FederationRuleID,
		OrganizationID:   w.cfg.OrganizationID,
		ServiceAccountID: w.cfg.ServiceAccountID,
		WorkspaceID:      w.cfg.WorkspaceID,
	})
	if err != nil {
		return nil, authErr(stageOther, 0, errExchange)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, w.cfg.AnthropicTokenURL, strings.NewReader(string(body)))
	if err != nil {
		return nil, authErr(stageOther, 0, errExchange)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := w.client.Do(req)
	if err != nil {
		return nil, authErr(stageExchangeNetwork, 0, errExchange)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, maxResponseBytes))
		return nil, authErr(stageExchange, resp.StatusCode, errExchange)
	}
	var out struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxResponseBytes)).Decode(&out); err != nil {
		return nil, authErr(stageMalformed, 0, errMalformed)
	}
	if out.AccessToken == "" || out.ExpiresIn <= 0 {
		return nil, authErr(stageMalformed, 0, errMalformed)
	}
	w.exchanges++
	return &cachedToken{token: out.AccessToken, expiresAt: w.now().Add(time.Duration(out.ExpiresIn) * time.Second)}, nil
}

// fetchAssertion asks Keycloak for a new client-credentials JWT. The secret is read from the file each time.
func (w *WIFAuth) fetchAssertion(ctx context.Context) (string, error) {
	secret, err := readFileTrimmed(w.cfg.ClientSecretFile)
	if err != nil {
		if errors.Is(err, errSecretEmpty) {
			return "", authErr(stageSecretEmpty, 0, errSecretEmpty)
		}
		return "", authErr(stageSecretUnreadable, 0, errSecretUnreadable)
	}
	form := url.Values{}
	form.Set("grant_type", "client_credentials")
	form.Set("client_id", w.cfg.KeycloakClientID)
	form.Set("client_secret", secret)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, w.cfg.KeycloakTokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", authErr(stageOther, 0, errKeycloak)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := w.client.Do(req)
	if err != nil {
		return "", authErr(stageKeycloakNetwork, 0, errKeycloak)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, maxResponseBytes))
		return "", authErr(stageKeycloak, resp.StatusCode, errKeycloak)
	}
	var out struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxResponseBytes)).Decode(&out); err != nil || out.AccessToken == "" {
		return "", authErr(stageMalformed, 0, errMalformed)
	}
	return out.AccessToken, nil
}

type exchangeRequest struct {
	GrantType        string `json:"grant_type"`
	Assertion        string `json:"assertion"`
	FederationRuleID string `json:"federation_rule_id"`
	OrganizationID   string `json:"organization_id"`
	ServiceAccountID string `json:"service_account_id"`
	WorkspaceID      string `json:"workspace_id,omitempty"`
}
