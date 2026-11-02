"""Scan the resolved runtime CycloneDX SBOM against OSV using Python's standard library.

No credentials or source code are transmitted: queries contain Maven coordinates
and resolved versions only. Fail closed if the service cannot be queried. Reports
describe advisories known to OSV at scan time, not a security certification.
"""
import argparse
import concurrent.futures
import datetime as dt
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


API = "https://api.osv.dev/v1"


def api(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(API + path, data=data, headers={
        "Content-Type": "application/json", "User-Agent": "LedgerCore-dependency-audit/1.0"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def scan(sbom):
    components = []
    seen = set()
    for component in sbom.get("components", []):
        name = component.get("group", "") + ":" + component.get("name", "")
        version = component.get("version")
        if not component.get("purl", "").startswith("pkg:maven/") or not version:
            continue
        if (name, version) not in seen:
            seen.add((name, version))
            components.append({"name": name, "version": version})
    if not components:
        raise ValueError("SBOM has no resolved Maven components")
    essential = {"org.apache.tomcat.embed:tomcat-embed-core", "org.springframework:spring-webmvc",
                 "org.postgresql:postgresql", "com.fasterxml.jackson.core:jackson-databind"}
    missing = essential - {component["name"] for component in components}
    if missing:
        raise ValueError("Incomplete LedgerCore runtime SBOM: " + ", ".join(sorted(missing)))
    findings = []
    for offset in range(0, len(components), 100):
        batch = components[offset:offset + 100]
        queries = [{"package": {"ecosystem": "Maven", "name": c["name"]}, "version": c["version"]} for c in batch]
        results = api("/querybatch", {"queries": queries}).get("results", [])
        if len(results) != len(batch):
            raise ValueError("Incomplete OSV query response")
        for component, query, result in zip(batch, queries, results):
            ids = {v["id"] for v in result.get("vulns", [])}
            while result.get("next_page_token"):
                query["page_token"] = result["next_page_token"]
                result = api("/querybatch", {"queries": [query]})["results"][0]
                ids.update(v["id"] for v in result.get("vulns", []))
            if ids:
                findings.append({**component, "advisoryIds": sorted(ids)})
    identifiers = sorted({identifier for f in findings for identifier in f["advisoryIds"]})
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        details = dict(zip(identifiers, pool.map(lambda identifier: api("/vulns/" + urllib.parse.quote(identifier, safe="")), identifiers)))
    return components, findings, details


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbom", type=Path, help="Override Boot's embedded SBOM or the standalone bom.json")
    parser.add_argument("--output", type=Path, default=Path("target/security/osv-report.json"))
    parser.add_argument("--exceptions", type=Path, default=Path("security/advisory-exceptions.json"))
    args = parser.parse_args()
    try:
        sbom = args.sbom
        if sbom is None:
            embedded = Path("target/classes/META-INF/sbom/application.cdx.json")
            sbom = embedded if embedded.exists() else Path("target/bom.json")
        components, findings, details = scan(json.loads(sbom.read_text(encoding="utf-8")))
        today = dt.date.today()
        exceptions = json.loads(args.exceptions.read_text()) if args.exceptions.exists() else []
        approved = set()
        for exception in exceptions:
            if not exception.get("rationale") or not exception.get("evidence"):
                raise ValueError("Every advisory exception requires rationale and evidence")
            if dt.date.fromisoformat(exception["expires"]) < today:
                raise ValueError("Expired advisory exception: " + exception["advisoryId"])
            approved.add((exception["package"], exception["advisoryId"]))
        unresolved = [{**finding, "advisoryIds": [i for i in finding["advisoryIds"] if (finding["name"], i) not in approved]} for finding in findings]
        unresolved = [finding for finding in unresolved if finding["advisoryIds"]]
        report = {"scannedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "database": API, "sbom": str(sbom),
                  "scope": "Resolved non-test Maven components from CycloneDX SBOM", "componentCount": len(components),
                  "status": "PASS" if not unresolved else "FINDINGS", "findings": findings,
                  "unresolved": unresolved, "advisories": details, "exceptions": exceptions}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"OSV: {len(components)} components, {len(details)} advisories, {len(unresolved)} unresolved component findings")
        for finding in unresolved:
            print(f"  {finding['name']}:{finding['version']}: {', '.join(finding['advisoryIds'])}")
        return 1 if unresolved else 0
    except (ValueError, OSError, KeyError, urllib.error.URLError) as ex:
        print("Dependency scan could not complete: " + str(ex), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
