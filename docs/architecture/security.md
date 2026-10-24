# Security and threat model

This is working educational application security, not a banking certification.

| Threat | Implemented control | Remaining scope |
|---|---|---|
| Duplicate payment after timeout | durable scoped key + request fingerprint + one transaction | permanent key retention/archival policy |
| Concurrent overspending | row locks, nonnegative projection, deferred ledger consistency | privileged DB access, wider future credit rules |
| Horizontal access | `AccountStore.owned`, `TransferStore.owned`; 404 for other owners | recipient UUID can receive funds; there is no public directory |
| Admin escalation | registration always USER; signed role checked against current DB identity; admin routes restricted | privileged bootstrap environment must be controlled |
| Brute force | BCrypt cost 12, constant dummy hash path for absent user, Redis IP rate window | distributed attacks and account-specific throttling |
| Token theft | 15-minute expiry, signature/issuer/audience validation, database role/status check | no MFA, refresh tokens, device binding or asymmetric key rotation |
| Malformed input | Bean Validation, exact minor-unit conversion, parameterized SQL | ingress body size/WAF policy |
| Log leaks | no request bodies, secrets, email or token logging in application code; correlation IDs sanitized | deployment log controls, third-party verbose logging |
| API abuse | atomic 60-second Redis fixed window, 429/Retry-After | fail-open authenticated policy during Redis outage |

`SecurityConfiguration` configures HS256 with a minimum 32-byte environment key.
Use cryptographically random secrets; length alone does not prove entropy. The
secret signs and verifies tokens and must be shared only among trusted instances.
Decoder validators require issuer, intended audience, expiry, a UUID subject,
active current database identity and a matching role. Changing user status/role
invalidates existing tokens at the next request. Passwords use printable ASCII
with 12–72 characters, explicitly respecting BCrypt's 72-byte limit; bootstrap
admin requires at least 16 characters. There is no production hardcoded secret.
Compose's visible defaults are strictly local simulation credentials.

Stateless Authorization-header APIs disable CSRF because browsers do not
automatically attach the bearer header. If cookies are introduced, revisit CSRF,
SameSite and CORS together. No permissive CORS configuration is enabled.
Do not trust incoming `X-Forwarded-For` without a separately configured trusted
proxy; the limiter uses the actual remote address. A trusted proxy deployment
requires a deliberate forwarded-header policy or all clients will share its IP.

Authentication endpoints fail closed when Redis is unavailable; existing
authenticated business requests fail open and financial correctness stays in
PostgreSQL. Network TLS and authenticated Kafka/Redis are outside this local
Compose design. All host service bindings are loopback; container network access
and a compromised local machine remain relevant threats.

Flyway and the runtime currently use the same schema-owner credentials for easy
local startup. A production deployment must run migrations separately, revoke
runtime DDL/TRUNCATE privileges, protect function definitions and immutable tables,
rotate keys and database credentials, configure TLS/ingress protection, test
backups and enforce retention/privacy requirements. No public endpoint changes or
corrupts journal rows. The corruption fixture exists only in integration tests.
