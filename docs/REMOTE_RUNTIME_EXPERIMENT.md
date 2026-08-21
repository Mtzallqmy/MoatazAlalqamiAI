# Moataz Remote Runtime experiment

Status: **experimental and not enabled by default**.

The Android REST client enforces HTTPS, short-lived token expiry, bounded
responses, typed HTTP failures, path/query encoding and Base64 file payloads.
Its advertised capabilities intentionally exclude interactive streaming,
idle/lifetime enforcement and network-policy enforcement.

`RemoteSandboxProtocol` is schema version 1. A server-side implementation must
use `TenantSandboxProvider`: tenant identity comes only from a verified JWT and
is required on every provider operation. A sandbox id supplied by the client is
never sufficient authorization.

## Experimental gateway

The `sandbox-gateway` JVM module is a `testApplication`-verified contract reference with Ktor
routes, injected JWT verification, tenant-scoped provider calls, bounded REST
operations and a versioned WebSocket exec flow. It defaults to
`NotConfiguredSandboxProvider`; its fake provider exists only in tests. The
Android client is not connected to that WebSocket yet and therefore continues
to report interactive streaming as unsupported.

## Activation blocker

The repository still has no isolated Incus provider, deployment identity/key
management, external rate-limit store, monitoring or disaster-recovery setup.
The experiment must stay off until environment-level integration tests prove:

- JWT issuer, audience, expiry, tenant, subject and scope validation;
- cross-tenant sandbox ids are rejected for every endpoint;
- versioned WebSocket stdout/stderr/exit, stdin, resize and cancel frames;
- output, file, process and port quotas plus rate limits;
- sandbox isolation, snapshot ownership and audit redaction;
- deployment monitoring, backup and disaster-recovery procedures.

No infrastructure credential or provider administration endpoint belongs in
the Android application.
