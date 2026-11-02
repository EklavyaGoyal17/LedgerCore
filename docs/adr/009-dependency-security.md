# ADR 009: Patch compatible dependencies and review advisory applicability

Status: accepted.

The Spring Boot 3.5.16 BOM supplies a coherent baseline but does not promise that
every library remains free of newly published advisories indefinitely. A real OSV
scan found advisories in 12 resolved runtime components. Targeted compatible
version overrides update Jackson, Tomcat, Netty, PostgreSQL JDBC, Log4j API,
OpenTelemetry, LZ4, Snappy and Commons Lang. The complete regression suite must pass on
these pinned versions; resolving a version number alone is insufficient evidence.

The build generates a CycloneDX runtime SBOM and CI queries OSV. Missing essential
runtime components or unavailable scan service fail the check rather than yielding
a false clean result. Test dependencies are excluded from the runtime SBOM; build
tools and container OS packages require their own update/security review.

Two Spring MVC advisories affect features absent from LedgerCore: server-sent
events rendering view fragments, and implicitly selected XSLT views on a wildcard
view-rendering route. The application exposes REST JSON DTOs and static Swagger
resources, with no such emitters, views or fragment configuration. Exact scoped
exceptions include rationale, evidence and a 90-day expiry. Architecture tests
reject application references to the affected view/emitter APIs. Introducing those
features requires removing the exceptions and selecting a patched framework.

Rejected alternatives: blanket advisory suppression hides risk; upgrading an entire
framework major solely to erase an inapplicable finding creates unrelated migration
risk; leaving genuinely affected libraries unpatched is unacceptable. This policy
keeps exceptions explicit and temporary while preserving the verified financial
transaction semantics.

References: [OSV query API](https://google.github.io/osv.dev/post-v1-querybatch/),
[OpenTelemetry trace-context parsing advisory](https://github.com/open-telemetry/opentelemetry-java/security/advisories/GHSA-ccrj-gw8r-854m),
[SSE fragment advisory](https://osv.dev/vulnerability/GHSA-j9f9-w8pj-32f8),
[XSLT view advisory](https://osv.dev/vulnerability/GHSA-pc63-qcmh-9cmg).
