package main

import "context"

// Credential is the header the proxy adds to an upstream request.
// secret is the raw token, kept so that the cache can be invalidated. It is never logged.
type Credential struct {
	Name   string
	Value  string
	secret string
}

// Authorizer supplies the upstream credential. Implementations are swappable: WIF in production,
// a fixed fake key for tests.
type Authorizer interface {
	Authorize(ctx context.Context) (Credential, error)
	// Invalidate drops cached state for cred (called after an upstream 401).
	Invalidate(cred Credential)
}

// StaticAuth sends a fixed key as x-api-key. Test use only (KIVRA_PROXY_AUTH=static-test).
type StaticAuth struct{ Key string }

func (s StaticAuth) Authorize(context.Context) (Credential, error) {
	return Credential{Name: "x-api-key", Value: s.Key, secret: s.Key}, nil
}

func (s StaticAuth) Invalidate(Credential) {}

func bearer(token string) Credential {
	return Credential{Name: "Authorization", Value: "Bearer " + token, secret: token}
}
