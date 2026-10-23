#!/usr/bin/env python3
"""Small, conservative source/evidence gate. No Java/SQL semantic safety claim.

Run this file from a trusted gate checkout, with --source pointing at the
candidate checkout. Policy changes require a separate reviewed gate revision.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BASE = "2ec68899099a1e250df0949a8383bf85b5d3ffe0"
HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SHA = re.compile(r"[0-9a-f]{40}\Z")
IDENT = r"[A-Za-z_][A-Za-z0-9_]*"
MAPPER = re.compile(r"chengyinhub-[^/]+/src/main/resources/mapper/.+\.xml\Z")
BAD_CASE = {"skipped", "failure", "error", "flakyFailure", "flakyError",
            "rerunFailure", "rerunError"}


class GateError(ValueError):
    pass


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True)
    if result.returncode:
        raise GateError("git %s failed: %s" % (args[0], result.stderr.decode(errors="replace").strip()))
    return result.stdout


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(data):
    return (json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()


def encode_inventory(data):
    # One exact record per line: generated inventory stays inspectable without
    # tens of thousands of repeated pretty-print lines.
    lines = ["{"]
    for index, (key, value) in enumerate(sorted(data.items())):
        prefix = "  " + json.dumps(key) + ": "
        suffix = "," if index + 1 < len(data) else ""
        if isinstance(value, dict):
            lines.append(prefix + "{")
            for number, (name, record) in enumerate(sorted(value.items())):
                lines.append("    " + json.dumps(name) + ": " +
                             json.dumps(record, ensure_ascii=False, sort_keys=True) +
                             ("," if number + 1 < len(value) else ""))
            lines.append("  }" + suffix)
        else:
            lines.append(prefix + json.dumps(value) + suffix)
    return ("\n".join(lines) + "\n}\n").encode()


def tree(root, ref):
    # Immutable git blobs, never files from a possibly dirty checkout.
    if not SHA.fullmatch(ref):
        raise GateError("candidate must be a full lowercase commit SHA")
    names = git(root, "ls-tree", "-rz", "--name-only", ref).decode().split("\0")
    return [name for name in names if name]


def blob(root, ref, path):
    return git(root, "show", "%s:%s" % (ref, path))


def xml(data):
    if b"<!ENTITY" in data.upper():
        raise GateError("XML entity declarations are unsupported")
    try:
        return ET.fromstring(data)
    except ET.ParseError as error:
        raise GateError("invalid XML: %s" % error) from error


def sql_shape(node, fragments, namespace):
    """Fingerprint statement + transitive includes; hints are NOT SQL parsing.

    Dynamic tags, substitutions and incomplete literal shapes remain UNKNOWN.
    Even LITERAL_SHAPE does not establish business authorization or atomicity.
    """
    dependencies = {}
    unknown = set()

    def expand(item, stack=(), current_namespace=namespace):
        parts = [item.text or ""]
        for child in item:
            if child.tag == "include":
                ref = child.get("refid", "")
                ref = ref if "." in ref else current_namespace + "." + ref
                if ref in stack or ref not in fragments:
                    unknown.add("unresolved_or_cyclic_include:" + ref)
                else:
                    fragment = fragments[ref]
                    dependencies[ref] = digest(ET.tostring(fragment))
                    parts.append(expand(fragment, stack + (ref,), ref.rsplit(".", 1)[0]))
                if list(child):
                    unknown.add("include_properties")
            else:
                unknown.add("dynamic_tag:" + child.tag)
                parts.append(expand(child, stack, current_namespace))
            parts.append(child.tail or "")
        return " ".join(parts)

    text = expand(node)
    if "${" in text:
        unknown.add("text_substitution")
    hints = sorted(set(re.findall(
        r"\b(?:update|insert\s+into|delete\s+from|replace\s+into)\s+`?(%s)`?" % IDENT,
        text, re.I)))
    # Only SET's left-hand sides; WHERE predicate columns are not write fields.
    assignment = re.search(r"\bset\b(.*?)(?:\bwhere\b|$)", text, re.I | re.S)
    columns = sorted(set(re.findall(r"\b(%s)\s*=" % IDENT, assignment.group(1)))) if assignment else []
    # INSERT column hints only for an immediately following literal column list.
    match = re.search(r"\binsert\s+into\s+`?%s`?\s*\(([^)]+)\)" % IDENT, text, re.I)
    if match:
        columns = sorted(set(columns + re.findall(IDENT, match.group(1))))
    if len(hints) != 1 or node.tag not in {"insert", "update", "delete"}:
        unknown.add("unsupported_sql_shape")
    fingerprint = digest(ET.tostring(node) + encode(dependencies))
    return {"sha256": fingerprint, "table_hints": hints, "column_hints": columns,
            "analysis": "UNKNOWN" if unknown else "LITERAL_SHAPE_ONLY",
            "unknown": sorted(unknown)}


def inventory(root, ref):
    names = tree(root, ref)
    mappers, fragments = {}, {}
    poms = {}
    for path in names:
        if MAPPER.fullmatch(path):
            node = xml(blob(root, ref, path))
            if node.tag != "mapper" or not node.get("namespace"):
                raise GateError("invalid mapper: " + path)
            mappers[path] = node
            for fragment in node.findall("sql"):
                key = node.get("namespace") + "." + fragment.get("id", "")
                if key in fragments:
                    raise GateError("duplicate SQL fragment: " + key)
                fragments[key] = fragment
        if path == "pom.xml" or re.fullmatch(r"chengyinhub-[^/]+/pom.xml", path):
            pom = xml(blob(root, ref, path))
            # Compare complete dependency declarations, including versions/scopes,
            # dependencyManagement and plugin dependencies. Multiplicity retained.
            poms[path] = sorted(ET.tostring(dep).decode().strip()
                                for dep in pom.iter() if dep.tag.split("}")[-1] == "dependency")
    statements, owners = {}, {}
    for path, mapper in sorted(mappers.items()):
        seen = set()
        namespace = mapper.get("namespace")
        owner = path.split("/")[0] + ":" + namespace
        for node in mapper:
            if node.tag not in {"insert", "update", "delete", "select"}:
                continue
            name = node.get("id")
            if not name or name in seen:
                raise GateError("missing/duplicate statement id: " + path)
            seen.add(name)
            shape = sql_shape(node, fragments, namespace)
            # Selects are fingerprinted too: SQL can mutate via select/affectData.
            shape.update({"kind": node.tag, "owner": owner})
            statements[path + "#" + name] = shape
            if node.tag == "select":
                continue
            for table in shape["table_hints"]:
                record = owners.setdefault(table, {})
                record.setdefault(owner, set()).update(shape["column_hints"])
    return {"schema": 1, "base_sha": ref, "dependencies": poms,
            "statements": statements,
            "observed_table_field_writers": {
                table: {owner: sorted(cols) for owner, cols in sorted(records.items())}
                for table, records in sorted(owners.items())}}


def static_findings(base, current, changed):
    findings = []
    for pom, dependencies in current["dependencies"].items():
        old = list(base["dependencies"].get(pom, []))
        for declaration in dependencies:
            if declaration in old:
                old.remove(declaration)
            else:
                findings.append({"rule": "DEPENDENCY_EXPANSION", "key": pom,
                                 "detail": "new/changed Maven dependency declaration"})
    for key, statement in current["statements"].items():
        if statement == base["statements"].get(key):
            continue
        findings.append({"rule": "SQL_REVIEW_REQUIRED", "key": key,
                         "detail": statement["analysis"], "sha256": statement["sha256"]})
        if statement["kind"] != "select":
            for table in statement["table_hints"]:
                if statement["owner"] not in base["observed_table_field_writers"].get(table, {}):
                    findings.append({"rule": "NEW_TABLE_WRITER", "key": key,
                                     "detail": table + " <- " + statement["owner"]})
    for key in base["statements"].keys() - current["statements"].keys():
        findings.append({"rule": "SQL_REMOVAL_REVIEW_REQUIRED", "key": key,
                         "detail": "may remove receipt/recovery/guard query"})
    for path in sorted(changed):
        if MAPPER.fullmatch(path):
            # Dynamic refids cannot form a complete static dependency graph.
            # Root attributes and fragment-only edits must still require review,
            # even when no resolved statement fingerprint changes.
            findings.append({"rule": "MAPPER_FILE_REVIEW_REQUIRED", "key": path,
                             "detail": "UNKNOWN: XML metadata/dynamic include reachability needs review"})
        # Broad UNKNOWN triage intentionally catches new annotations, JDBC,
        # reflection and side effects without pretending to resolve Java calls.
        if "/src/main/" in path and not MAPPER.fullmatch(path):
            findings.append({"rule": "PRODUCTION_REVIEW_REQUIRED", "key": path,
                             "detail": "UNKNOWN: scope/intent/version/recovery and dependency calls need review"})
        if path.startswith((".github/workflows/", "ci/")) and not path.startswith("ci/architecture/"):
            findings.append({"rule": "TEST_GATE_REVIEW_REQUIRED", "key": path,
                             "detail": "shared gate or workflow changed"})
        if "/src/test/" in path:
            findings.append({"rule": "TEST_SOURCE_REVIEW_REQUIRED", "key": path,
                             "detail": "test/fixture/skip changes require review; report counts alone are insufficient"})
        if path.endswith(".sql") and "/src/main/" not in path:
            findings.append({"rule": "SQL_SCRIPT_REVIEW_REQUIRED", "key": path,
                             "detail": "UNKNOWN: migration/seed/ad-hoc SQL must not bypass mapper ownership review"})
        if path.startswith(".mvn/") or path in {"mvnw", "mvnw.cmd"}:
            findings.append({"rule": "BUILD_REVIEW_REQUIRED", "key": path,
                             "detail": "Maven launcher/flags can change dependency or test execution"})
        if path.endswith("pom.xml") and path not in current["dependencies"]:
            findings.append({"rule": "UNKNOWN_BUILD_FILE", "key": path,
                             "detail": "new/unclassified build file requires review"})
        elif path.endswith("pom.xml"):
            findings.append({"rule": "BUILD_REVIEW_REQUIRED", "key": path,
                             "detail": "plugins/profiles/modules/test skip configuration require review"})
    return sorted(findings, key=lambda item: (item["rule"], item["key"]))


def check_source(root, candidate, baseline_path=HERE / "baseline.json"):
    if not SHA.fullmatch(candidate):
        raise GateError("candidate must be a full lowercase commit SHA")
    if git(root, "rev-parse", "HEAD").decode().strip() != candidate:
        raise GateError("candidate HEAD mismatch")
    if git(root, "status", "--porcelain", "--untracked-files=all").strip():
        raise GateError("candidate checkout is dirty; commit/isolate changes before verification")
    git(root, "merge-base", "--is-ancestor", BASE, candidate)
    frozen = inventory(root, BASE)
    if baseline_path.read_bytes() != encode_inventory(frozen):
        raise GateError("BASELINE_LAUNDERING: baseline must exactly reproduce pinned public git blobs")
    names = set(tree(root, candidate))
    # The verifier and frozen policy must come from the trusted checkout, not PR code.
    trusted_files = sorted(p for p in HERE.rglob("*") if p.is_file()
                           and "__pycache__" not in p.parts)
    policy_names = {p.relative_to(REPO).as_posix() for p in trusted_files}
    candidate_policy_names = {n for n in names if n.startswith("ci/architecture/")}
    if policy_names != candidate_policy_names:
        raise GateError("POLICY_CHANGE: candidate gate file set differs from trusted gate")
    for path in trusted_files:
        relative = path.relative_to(REPO).as_posix()
        if blob(root, candidate, relative) != path.read_bytes():
            raise GateError("POLICY_CHANGE: candidate changed trusted policy: " + relative)
    changed = set(git(root, "diff", "--name-only", "--no-renames", BASE, candidate).decode().splitlines())
    current = inventory(root, candidate)
    return {"schema": 1, "candidate_sha": candidate, "base_sha": BASE,
            "verdict": "STATIC_ONLY", "findings": static_findings(frozen, current, changed),
            "coverage": {"statements": len(current["statements"]),
                         "observed_tables": len(current["observed_table_field_writers"]),
                         "unknown_statements": sum(s["analysis"] == "UNKNOWN"
                                                   for s in current["statements"].values())},
            "not_proven": ["Java/SQL semantics", "authorization", "transaction atomicity",
                           "persistent recovery reachability", "real MySQL/Redis execution"]}


def readback(plan, receipt, reports, assert_path=REPO / "ci/assert_it_ran.py"):
    """Trusted CI plan + job receipt + report bytes. Not self-attestation proof.

    CI must construct the plan outside candidate code and attest storage identity.
    This read-only step checks identity and report consistency, not provenance by
    cryptography. Existing real_storage_it.py remains the real storage executor.
    """
    expected_keys = {"candidate_sha", "run_id", "run_attempt", "suites"}
    if set(plan) != expected_keys or not SHA.fullmatch(str(plan.get("candidate_sha", ""))):
        raise GateError("invalid trusted test plan")
    if any(not re.fullmatch(r"[1-9][0-9]*", str(plan.get(key, ""))) for key in ("run_id", "run_attempt")):
        raise GateError("run identity must be positive integers")
    suites = plan["suites"]
    if not isinstance(suites, dict) or not suites:
        raise GateError("missing expected suite plan")
    for name, count in suites.items():
        if (not re.fullmatch(r"(?:[A-Za-z_]\w*\.)+[A-Za-z_]\w*(?:\$[A-Za-z_]\w*)*", name)
                or type(count) is not int or count < 1):
            raise GateError("expected full suite names and positive exact counts")
    if set(receipt) != expected_keys | {"reports_sha256", "maven_exit_code"}:
        raise GateError("invalid receipt fields")
    for key in expected_keys:
        if receipt[key] != plan[key]:
            raise GateError("CANDIDATE_OR_RUN_MISMATCH: " + key)
    if type(receipt["maven_exit_code"]) is not int or receipt["maven_exit_code"] != 0:
        raise GateError("Maven did not succeed")
    if reports.is_symlink() or not reports.is_dir():
        raise GateError("missing/symlink report directory")
    files = sorted(reports.glob("TEST-*.xml"))
    if any(path.is_symlink() for path in files):
        raise GateError("symlink report rejected")
    hashes = {path.name: digest(path.read_bytes()) for path in files}
    if not hashes or hashes != receipt["reports_sha256"]:
        raise GateError("missing/changed/unexpected report bytes")
    roots = [xml(path.read_bytes()) for path in files]
    # Reuse the established missing/zero/skip/failure/error check first.
    spec = importlib.util.spec_from_file_location("architecture_assert_it_ran", assert_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    problems = module.enforce(str(reports), [n.rsplit(".", 1)[-1] for n in suites])
    if problems:
        raise GateError("; ".join(problems))
    found = {}
    for root in roots:
        name = root.get("name")
        if root.tag != "testsuite" or name in found or name not in suites:
            raise GateError("duplicate/foreign/unsupported suite: " + str(name))
        expected = suites[name]
        if any(root.get(key) != str(value) for key, value in
               {"tests": expected, "failures": 0, "errors": 0, "skipped": 0}.items()):
            raise GateError("suite totals mismatch: " + name)
        cases = root.findall("testcase")
        if len(cases) != expected or len({(c.get("classname"), c.get("name")) for c in cases}) != expected:
            raise GateError("missing/duplicate test cases: " + name)
        if any(not case.get("name") or case.get("classname") != name for case in cases):
            raise GateError("testcase identity mismatch: " + name)
        if any(element.tag in BAD_CASE for element in root.iter()):
            raise GateError("skipped/failed/flaky test case: " + name)
        found[name] = expected
    if found != suites:
        raise GateError("missing full-name suite reports")
    return {"candidate_sha": plan["candidate_sha"], "run_id": plan["run_id"],
            "run_attempt": plan["run_attempt"], "verdict": "REPORTS_CONSISTENT",
            "suites": found, "not_proven": ["storage identity", "test semantic coverage",
                                              "report producer trust", "real business chain"]}


def reviewed_result(result, review, evidence):
    """Exact-candidate, external human review; never grow the legacy baseline.

    The trusted CI caller supplies the approved review artifact, just as it
    supplies the trusted test plan. A PR-authored JSON is not an approval.
    """
    if set(review) != {"schema", "candidate_sha", "findings_sha256", "reviewer", "decision_ref",
                       "contracts", "required_suites"} or review["schema"] != 1:
        raise GateError("invalid external review contract")
    if review["candidate_sha"] != result["candidate_sha"] or evidence.get("candidate_sha") != result["candidate_sha"]:
        raise GateError("review/evidence candidate mismatch")
    if evidence.get("verdict") != "REPORTS_CONSISTENT" or not review["required_suites"] or review["required_suites"] != evidence.get("suites"):
        raise GateError("review requires the exact verified suite plan")
    if review["findings_sha256"] != digest(encode(result["findings"])):
        raise GateError("review findings fingerprint mismatch")
    if not all(isinstance(review[key], str) and review[key].strip() for key in ("reviewer", "decision_ref")):
        raise GateError("missing external reviewer or decision reference")
    if any(item["rule"] == "DEPENDENCY_EXPANSION" for item in result["findings"]):
        raise GateError("dependency expansion is not authorized by this task; cannot waive")
    required = {"scope", "identity", "intent", "version", "recovery", "atomicity", "reject_zero_effect"}
    contracts = review["contracts"]
    if not isinstance(contracts, list):
        raise GateError("review contracts must be a list")
    keys = set()
    for contract in contracts:
        if set(contract) != required | {"rule", "key"}:
            raise GateError("each finding requires a full dangerous-command contract")
        if any(not isinstance(contract[key], str) or not contract[key].strip() for key in contract):
            raise GateError("empty review contract field; explain non-applicability explicitly")
        key = (contract["rule"], contract["key"])
        if key in keys:
            raise GateError("duplicate review contract")
        keys.add(key)
    expected = {(item["rule"], item["key"]) for item in result["findings"]}
    if not expected or keys != expected:
        raise GateError("missing/extra review finding contracts")
    return {**result, "verdict": "REVIEWED_STATIC_WITH_REPORTS", "reviewer": review["reviewer"],
            "decision_ref": review["decision_ref"], "evidence": evidence}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    snapshot = subs.add_parser("inventory")
    snapshot.add_argument("--source", type=Path, required=True)
    source = subs.add_parser("source")
    source.add_argument("--source", type=Path, required=True)
    source.add_argument("--candidate", required=True)
    for flag in ("review", "plan", "receipt", "reports"):
        source.add_argument("--" + flag, type=Path)
    evidence = subs.add_parser("evidence")
    evidence.add_argument("--plan", type=Path, required=True)
    evidence.add_argument("--receipt", type=Path, required=True)
    evidence.add_argument("--reports", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "inventory":
            print(encode_inventory(inventory(args.source, BASE)).decode(), end="")
            return 0
        elif args.command == "source":
            result = check_source(args.source, args.candidate)
            inputs = [args.review, args.plan, args.receipt, args.reports]
            if any(inputs):
                if not all(inputs):
                    raise GateError("external review requires --review --plan --receipt --reports together")
                for path in inputs:
                    if path.resolve().is_relative_to(args.source.resolve()):
                        raise GateError("review/evidence inputs must be outside the candidate checkout")
                evidence_result = readback(json.loads(args.plan.read_text()), json.loads(args.receipt.read_text()), args.reports)
                result = reviewed_result(result, json.loads(args.review.read_text()), evidence_result)
        else:
            result = readback(json.loads(args.plan.read_text()), json.loads(args.receipt.read_text()), args.reports)
        print(encode(result).decode(), end="")
        return 1 if result.get("findings") and result.get("verdict") != "REVIEWED_STATIC_WITH_REPORTS" else 0
    except (GateError, OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        print("architecture gate FAIL-CLOSED: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
