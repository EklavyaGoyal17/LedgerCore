# Dependency and static analysis policy

`mvn clean verify` executes SpotBugs at high-confidence severity, architecture
dependency rules, generated financial properties and the PostgreSQL/Kafka/Redis/JVM
recovery suite. The Boot package build embeds the runtime CycloneDX SBOM at
`target/classes/META-INF/sbom/application.cdx.json`. A standalone CycloneDX goal
may instead write `target/bom.json`; the scanner detects both or accepts `--sbom PATH`.
Run `python scripts/scan-dependencies.py` after packaging to query OSV for every
non-test Maven component. The scan exits nonzero on any unreviewed advisory or
service failure. No API key is needed. Package names/versions are sent to OSV;
application source, environment secrets and customer data are not sent.

An exception requires exact package/advisory identifiers, evidence explaining
non-applicability or a documented compensating control, and an expiry date. Empty
exceptions are the default policy. Two documented exceptions currently cover
Spring MVC SSE-fragment and XSLT-view features that this REST-only application
does not use; architecture tests prohibit introducing those affected APIs.
Both expire on 22 January 2027. A passing scan with these exceptions does not mean
the dependency has no advisories: it means every finding was patched or explicitly
reviewed as inapplicable. Re-run regularly and review new
advisories rather than treating old results as a permanent guarantee.

Dependabot separately proposes Maven, container and GitHub Actions updates. Review
release notes and run invariant/recovery tests before merging; do not auto-merge
financial infrastructure upgrades solely because the version is newer.

OSV API reference: https://google.github.io/osv.dev/post-v1-querybatch/
