#!/usr/bin/env python3
"""Offline BASE-01/QA-01/QA-07 evidence companion to chain_acceptance receipts.

python3 scripts/e2e/run_metadata.py --metadata run.json --out existing-run-dir
Optional --chain-receipt receipt.json / --release-manifest release-manifest.json
reuse candidate identities; neither file proves the currently running backend.
Paths are relative to run.json. Inputs must be sanitized normal-entry captures.
This tool never calls a backend, creates fixtures, or certifies rendered UI.
"""
import argparse
import copy
import hashlib
import json
import re
from pathlib import Path

UNKNOWN = "UNKNOWN"
CLIENTS = ("miniprogram", "ios", "admin")
VERSION_FIELDS = ("releaseId", "bindingRevision", "contentRevisionId",
                  "contentRevisionNo", "contentSha256", "bindingIdentity", "contractVersions")


def known(value):
    if isinstance(value, dict):
        return bool(value) and all(known(item) for item in value.values())
    if isinstance(value, list):
        return bool(value) and all(known(item) for item in value)
    if isinstance(value, str):
        return bool(value.strip()) and value.strip() != UNKNOWN
    return value is not None


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def pointer(value, path):
    if path == "":
        return value
    if not path.startswith("/"):
        raise ValueError("comparisonRoot must be a JSON pointer")
    for part in path[1:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        value = value[int(part)] if isinstance(value, list) else value[part]
    return value


def differences(left, right, clients, path=""):
    """Locate values without copying private response text into the report."""
    def item(kind, a=left, b=right):
        result = {"kind": kind, "path": path, "clients": list(clients),
                  "fingerprints": [fingerprint(a), fingerprint(b)]}
        result["id"] = fingerprint(result)
        return result

    if type(left) is not type(right):
        return [item("type")]
    result = []
    if isinstance(left, dict):
        for key in sorted(left.keys() | right.keys()):
            child = path + "/" + key.replace("~", "~0").replace("/", "~1")
            if key not in left or key not in right:
                row = {"kind": "missing_field", "path": child, "clients": list(clients),
                       "missingIn": clients[0] if key not in left else clients[1]}
                row["id"] = fingerprint(row)
                result.append(row)
            else:
                result.extend(differences(left[key], right[key], clients, child))
    elif isinstance(left, list):
        if left != right and sorted(map(fingerprint, left)) == sorted(map(fingerprint, right)):
            result.append(item("order"))
        else:
            if len(left) != len(right):
                result.append(item("length", len(left), len(right)))
            for index, (a, b) in enumerate(zip(left, right)):
                result.extend(differences(a, b, clients, path + "/" + str(index)))
    elif left != right:
        result.append(item("value"))
    return result


def build_report(metadata, base, chain_receipt=None, release_manifest=None):
    run = copy.deepcopy(metadata)
    pending, errors, evidence = [], [], []

    def require(value, label):
        if not known(value):
            pending.append(label)

    def artifact(path, label):
        if not known(path):
            pending.append(label)
            return None
        target = Path(base) / path
        exists = target.is_file()
        evidence.append({"label": label, "path": str(path), "exists": exists})
        if not exists:
            pending.append(label)
            return None
        return target

    backend = run.setdefault("backend", {})
    sources = []
    if chain_receipt is not None:
        sources.append(("chainReceipt", {
            "sourceSha": chain_receipt.get("plan", {}).get("source_sha", UNKNOWN),
            "jarSha256": chain_receipt.get("stages", {}).get("explode", {}).get("jar_sha256", UNKNOWN)}))
    if release_manifest is not None:
        sources.append(("releaseManifest", {
            "sourceSha": release_manifest.get("public", {}).get("commit", UNKNOWN),
            "jarSha256": release_manifest.get("jar", {}).get("sha256", UNKNOWN)}))
    for label, source in sources:
        for key, value in source.items():
            if known(value) and value not in ("TO_FILL_FROM_CI_ARTEFACT", "OWNER_INPUT_REQUIRED"):
                if known(backend.get(key)) and backend[key] != value:
                    errors.append(label + "/backend/" + key + " mismatch")
                else:
                    backend[key] = value
    require(run.get("runId"), "runId")
    if run.get("purpose") != "acceptance":
        pending.append("fixture/template is not acceptance evidence")
    for key in ("sourceSha", "jarSha256", "runtimeSourceSha", "runtimeJarSha256"):
        require(backend.get(key), "backend/" + key)
        length = 40 if key.endswith("SourceSha") or key == "sourceSha" else 64
        if known(backend.get(key)) and not re.fullmatch("[0-9a-f]{%d}" % length, str(backend[key])):
            errors.append("backend/" + key + " invalid digest")
    for candidate, runtime in (("sourceSha", "runtimeSourceSha"), ("jarSha256", "runtimeJarSha256")):
        if known(backend.get(candidate)) and known(backend.get(runtime)) and backend[candidate] != backend[runtime]:
            errors.append("backend/" + runtime + " mismatch; stop scenario")
    environment = run.get("environment", {})
    for key in ("target", "databaseTarget", "verifiedAt"):
        require(environment.get(key), "environment/" + key)
    if environment.get("kind") != "test" or environment.get("verified") is not True:
        pending.append("verified test environment")
    artifact(environment.get("evidence"), "environment/evidence")
    artifact(backend.get("runtimeEvidence"), "backend/runtimeEvidence")
    content = run.get("content", {})
    for key in VERSION_FIELDS:
        require(content.get(key), "content/" + key)
    for contract in ("C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08"):
        require((content.get("contractVersions") or {}).get(contract), "content/contractVersions/" + contract)
    artifact(content.get("mappingEvidence"), "content/mappingEvidence")
    artifact(content.get("revisionEvidence"), "content/revisionEvidence")
    artifact(content.get("bindingEvidence"), "content/bindingEvidence")
    clients, bodies, client_results = run.get("clients", {}), {}, {}
    if set(clients) - set(CLIENTS):
        pending.append("unknown clients: " + ",".join(sorted(set(clients) - set(CLIENTS))))
    for name in CLIENTS:
        client = clients.get(name, {})
        for key in ("build", "actor", "role", "device", "os", "session", "capturedAt", "request"):
            require(client.get(key), name + "/" + key)
        request = client.get("request")
        for key in ("method", "path", "chapterId"):
            require(request.get(key) if isinstance(request, dict) else None, name + "/request/" + key)
        if client.get("normalEntry") is not True:
            pending.append(name + "/normalEntry")
        artifact(client.get("versionEvidence"), name + "/versionEvidence")
        for key in VERSION_FIELDS:
            observed = client.get("observedVersions", {}).get(key)
            require(observed, name + "/observedVersions/" + key)
            if known(observed) and known(content.get(key)) and observed != content[key]:
                errors.append(name + "/observedVersions/" + key + " mismatch")
        response = client.get("response", {})
        response_file = artifact(response.get("path"), name + "/response")
        screenshot = artifact(client.get("screenshot"), name + "/screenshot")
        client_results[name] = {"build": client.get("build", UNKNOWN),
                                "observedVersions": client.get("observedVersions", {}),
                                "screenshotEvidencePresent": screenshot is not None,
                                "renderStatus": "PENDING_MANUAL_REVIEW"}
        if response.get("projection") != "public":
            pending.append(name + "/public projection required; private projection is not interchangeable")
        elif response_file:
            try:
                bodies[name] = pointer(load(response_file), response.get("comparisonRoot", ""))
            except (ValueError, KeyError, IndexError, TypeError) as error:
                errors.append(name + "/response unreadable: " + type(error).__name__)
    diff = []
    for index, left in enumerate(CLIENTS):
        for right in CLIENTS[index + 1:]:
            if left not in bodies or right not in bodies:
                continue
            a, b = clients[left], clients[right]
            if a.get("request") != b.get("request"):
                errors.append(left + "/" + right + "/request mismatch")
                continue
            diff.extend(differences(bodies[left], bodies[right], (left, right)))
    exceptions = {}
    for item in run.get("knownDifferences", []):
        if not known(item.get("reason")):
            errors.append("known difference requires reason: " + str(item.get("id", UNKNOWN)))
        else:
            exceptions[item.get("id")] = item["reason"]
    for row in diff:
        row["reason"] = exceptions.get(row["id"], UNKNOWN)
        row["status"] = "KNOWN" if row["id"] in exceptions else "UNRESOLVED"
    if set(exceptions) - {row["id"] for row in diff}:
        errors.append("stale known differences; review against current snapshots")
    if errors or any(row["status"] == "UNRESOLVED" for row in diff):
        status = "DIFFERENT"
    elif pending:
        status = "PENDING"
    else:
        status = "CONSISTENT_WITH_KNOWN_DIFFERENCES" if diff else "CONSISTENT"
    return {"schemaVersion": 1, "runId": run.get("runId", UNKNOWN), "metadata": run,
            "dataStatus": status, "acceptanceStatus": "PENDING", "renderStatus": "PENDING_MANUAL_REVIEW",
            "clients": client_results, "differences": diff, "errors": errors,
            "pending": sorted(set(pending)), "evidenceIndex": evidence,
            "limits": "Offline supplied captures only; file presence does not prove rendering, provenance or live state."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--chain-receipt", type=Path)
    parser.add_argument("--release-manifest", type=Path)
    args = parser.parse_args(argv)
    report = build_report(load(args.metadata), args.metadata.parent,
                          load(args.chain_receipt) if args.chain_receipt else None,
                          load(args.release_manifest) if args.release_manifest else None)
    args.out.mkdir(parents=True, exist_ok=True)
    # Preserve historical run evidence; a rerun must choose a fresh output directory.
    with (args.out / "diff-report.json").open("x", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(report["dataStatus"] + "; rendering and acceptance PENDING")
    if report["dataStatus"] == "DIFFERENT":
        return 1
    return 2 if report["dataStatus"] == "PENDING" else 0


if __name__ == "__main__":
    raise SystemExit(main())
