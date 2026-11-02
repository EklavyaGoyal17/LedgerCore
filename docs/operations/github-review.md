# GitHub delivery and dependency review

Inspected on **2 November 2026** through GitHub's public REST API. This is a
snapshot, not a claim that every future branch or dependency update passes.

The published initial main commit was
`8eb6933df0e7b925460e73287059bbdff94fe663`. Its push-triggered
[verification run passed](https://github.com/EklavyaGoyal17/LedgerCore/actions/runs/40085392209).
That result covers the initial implementation; the completion changes require a
new push-triggered run for their exact SHA. See [verification](../VERIFICATION.md)
and the repository's [Actions history](https://github.com/EklavyaGoyal17/LedgerCore/actions/workflows/ci.yml).

At inspection there were **10 open pull requests**, all from Dependabot, and no
separate open bug issues. There were no published tags or releases. Main was not
branch-protected. This audit did not change repository settings, merge or close
pull requests, or create a release.

Authenticated inspection of the private Dependabot-alert endpoint returned HTTP
403 with the available Git credential. Private alert state is therefore not
claimed as verified. The resolved runtime dependency scan against OSV was executed
independently; its findings, dated exceptions and package versions are included in
the [verification evidence](../VERIFICATION.md).

## Open dependency proposals

| PR | Proposal | Observed initial-branch CI | Disposition |
|---|---|---|---|
| [1](https://github.com/EklavyaGoyal17/LedgerCore/pull/1) | Maven builder with Java 26 | Passed | Defer: retain the specified Java 21 build toolchain |
| [2](https://github.com/EklavyaGoyal17/LedgerCore/pull/2) | Temurin runtime Java 24 | Passed | Defer: retain the specified Java 21 runtime |
| [3](https://github.com/EklavyaGoyal17/LedgerCore/pull/3) | Spring Boot 4.1.1 | Failed | Defer to a coordinated framework migration |
| [4](https://github.com/EklavyaGoyal17/LedgerCore/pull/4) | setup-java 6.1.0 | Passed | Optional maintenance; retain the verified SHA pin until deliberately upgraded |
| [5](https://github.com/EklavyaGoyal17/LedgerCore/pull/5) | JaCoCo 0.8.15 | Passed | Optional maintenance requiring the hardened suite and coverage comparison |
| [6](https://github.com/EklavyaGoyal17/LedgerCore/pull/6) | checkout 7.0.1 | Passed | Optional maintenance; retain the verified SHA pin until deliberately upgraded |
| [7](https://github.com/EklavyaGoyal17/LedgerCore/pull/7) | upload-artifact 7.0.2 | Passed | Optional maintenance; retain the verified SHA pin until deliberately upgraded |
| [8](https://github.com/EklavyaGoyal17/LedgerCore/pull/8) | Spotless 3.10.4 | Passed | Optional maintenance requiring a formatting-diff review and full verification |
| [9](https://github.com/EklavyaGoyal17/LedgerCore/pull/9) | Failsafe 3.6.0 | Passed | Optional maintenance requiring the hardened integration and crash-recovery suite |
| [10](https://github.com/EklavyaGoyal17/LedgerCore/pull/10) | springdoc 3.1.1 | Failed | Defer with Boot 4; the retained Boot 3.5 line uses springdoc 2.x |

The [official springdoc compatibility matrix](https://springdoc.org/v2/)
pairs Spring Boot 3.5 with springdoc 2.8 through 2.10 and Boot 4 with springdoc 3.
Independent major-version bumps are therefore not a substitute for a coordinated
migration. Passing maintenance proposals on the initial branch do not prove
compatibility with all subsequent financial, event and recovery changes. Open
maintenance proposals are not missing financial-product phases.

## CI and update-policy corrections

The initial CI only parsed Compose configuration. Its green results for the two
Docker proposals did not establish that those images built or retained Java 21.
The completion workflow now builds the packaged Docker image and executes its
Java binary, requiring a Java 21 version string. Native compilation and tests also
select Java 21. Runtime-image verification does not by itself constrain the builder
JDK, so the Dockerfile and update policy preserve the builder choice explicitly.

`.github/dependabot.yml` now ignores major Boot and springdoc version proposals
while leaving compatible-line updates eligible. It ignores Temurin versions
`>=22`, preserving Java 21 updates, including updates to the pinned runtime digest.
Maven image updates require manual review: their tags combine a Maven version with
a Java suffix, and Dependabot's Docker numeric requirements cannot safely express
"any Maven version, but only a Java 21 suffix." The Maven image is consequently
ignored by the automated Docker updater; when refreshing it, choose a Java 21 tag,
build the image, and run the full verification workflow.

This uses supported configuration rather than wildcard tag expressions in the
`versions` field. GitHub documents Docker ignore ranges as Bundler-style
requirements; wildcard matching applies to dependency names, not arbitrary tag
suffix filters. See the [official options reference](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference#versions-ignore)
and Dependabot's [Docker requirement implementation](https://github.com/dependabot/dependabot-core/blob/main/docker/lib/dependabot/docker/requirement.rb).

Continue reviewing the resolved-runtime OSV report and the dated exceptions after
each update. A passing scan means no unreviewed findings under the stated policy;
it does not mean the system has no possible vulnerabilities. Future framework,
Java or build-tool migrations should be tested as explicit maintenance work, with
their own delivered-SHA CI evidence.
