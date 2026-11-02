# API security and object authorization

## Concept and purpose

Authentication establishes identity. Authorization decides whether that identity
may act on a particular object. A signed token does not grant every account UUID
or permission to issue simulated money. Both route policy and object checks matter.

## Trace a login and request

`security/AuthenticationController` accepts validated credentials and delegates to
`identity/IdentityService`. Email is normalized and registration creates USER
identities. BCrypt uses cost 12. Credentials require 12–72 printable ASCII
characters, making its byte limit explicit. This is the project's educational
policy, not a general claim that every product should forbid Unicode. An absent
user still incurs a dummy-hash check to avoid an obvious no-hash timing difference.

`Tokens.issue` creates HS256 tokens with issuer, audience, UUID subject, issue
time, expiry, and role. `SecurityConfiguration` requires a signing secret of at
least 32 bytes, verifies signature/issuer/time, requires expiry/intended audience,
and checks the current database user's active status and role. This permits
subsequent-request rejection after disablement or role changes at the cost of a
database lookup per authenticated request.

| Attack | Current defense | Remaining boundary |
|---|---|---|
| Tampered or wrong-audience token | decoder validation | key distribution and rotation |
| USER calls simulated deposit | ADMIN route plus service checks | compromised admin credentials |
| Alice requests Bob's statement | `AccountStore.owned`, 404 | consistent checks on future endpoints |
| Stranger knows transfer ID | `TransferStore.owned` checks actor/participants/admin | appropriate participant DTO fields |
| Duplicate payment | durable key and fingerprint | retaining original key after unknown outcome |
| Brute-force login | BCrypt plus Redis admission | trusted ingress/IP handling and monitoring |

`TransferEngine` checks source ownership after locking. V2 adds database checks
for financial actors, currencies, account status, and administrative operations.
These defend ordinary SQL writes without replacing authentication.

## Credentials and database privileges

The migrator owns schema evolution; the runtime role has only required table
operations. Runtime cannot create/truncate tables, read Flyway history, or disable
immutable triggers. `ProcessRecoveryIT` executes real denied statements using
runtime credentials. Environment variables supply secrets. Simulation defaults
in `.env.example` are not suitable credentials for external deployment. TLS,
ingress policy, private metrics exposure, and key rotation remain deployment work.

## Dependency findings require scoped reasoning

`scan-dependencies.py` scans the resolved runtime CycloneDX SBOM against OSV.
`security/advisory-exceptions.json` records package/advisory, specific evidence,
and expiry. Current Spring MVC exceptions cover unused SSE-fragment and XSLT-view
features; `ArchitectureTest` rejects dependencies on their relevant classes.
This is an expiring applicability decision, not a claim that the framework has no
vulnerabilities. Reassess when configuration, dependency versions, feature use,
or advisory information changes.

## Questions, mistakes, and experiments

- **Why 404 for another owner's private account?** Avoid exposing its existence
  through the normal object lookup response.
- **Does stateless JWT eliminate DB work?** It eliminates server sessions; current
  identity validation still queries PostgreSQL.
- **Why disable CSRF here?** Explicit bearer headers authorize requests rather
  than an automatically attached session cookie. Reassess if that changes.
- **Does disabling an identity help with stolen tokens?** Subsequent requests are
  rejected by the current-state validator; expiration also bounds token use.

Run real login/JWT, invalid-claim, ownership, malformed-request, method/media-type,
and runtime-role denial tests. Avoid unsigned roles, route-only authorization,
entity serialization, missing-expiry acceptance, raw bearer logging, and treating
a scanner's empty result as a security certification. Propose asymmetric key
rotation with overlapping verification keys, then specify its migration tests.
