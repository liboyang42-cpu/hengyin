#!/usr/bin/env python3
"""Strict, invocation-bound W16 MySQL proof, additive to the unchanged Funds gate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

SUITE = "com.chengyinhub.business.service.rendezvous.RendezvousConsentMySqlIT"
MIGRATION_SHA = "216099864b936d7a1fbc1e3a190a39b0a477651b8f78f9ed5a92ae41645e2936"
METHODS = frozenset({
    "originalDdlCreatesInnoDbAndGeneratedActorUniqueness",
    "bilateralReceiptsPersistAcrossIndependentMapperSessions",
    "concurrentOptInCommitsOneActiveTicket",
    "concurrentProposalsCannotReserveOneTicketTwice",
    "partialReservationFailureRollsBackBothTickets",
    "receiptOverwriteRejectionRollsBackAcceptanceMutation",
    "concurrentCancelAndAcceptCannotResurrectProposal",
    "expiryCommitsSlotReleaseAtExactBoundary",
    "rejectedRejoinStillCommitsSafetyTermination",
    "duplicateAcceptanceKeepsOriginalReceiptAndRevision",
    "globalGuardHoldsAcrossIndependentMysqlConnections",
    "cancelledActorsCanOptInAgainWithoutReusingReservations",
})
EVIDENCE = re.compile(
    r"W16_MYSQL_EVIDENCE METHOD=(\w+) VERSION=(\S+) SCHEMA=(\S+) "
    r"MIGRATION_SHA256=(\S+) COMMIT=(\S+) RUN=(\S+) SCRATCH_DROPPED=(\S+)")


def binding_problems(workflow, script, source):
    """Pin the executed selector/readback/trigger contract, not mentions in comments."""
    problems = []
    active = "\n".join(line for line in script.splitlines() if not line.lstrip().startswith("#")).replace("\\\n", " ")
    calls = [line for line in active.splitlines() if line.startswith("mvn ")]
    if len(calls) != 1:
        problems.append("one reactor test invocation required")
    else:
        call = calls[0]
        selection = re.search(r"-Dtest=([^\s]+)", call)
        if not selection or selection.group(1).split(",").count("RendezvousConsentMySqlIT") != 1:
            problems.append("exact W16 execution selector missing")
        for token in ("-am clean test", '-Dw16.test.commit="$w16_commit"', '-Dw16.test.run="$w16_run_id"'):
            if token not in call:
                problems.append("unbound reactor invocation: " + token)
    proof_calls = [line for line in active.splitlines() if line.startswith("python3 ci/w16_mysql_gate.py ")]
    if len(proof_calls) != 1 or '--commit "$w16_commit" --run-id "$w16_run_id"' not in proof_calls[0]:
        problems.append("bound W16 proof readback missing")
    if 'w16_commit="$(git rev-parse HEAD)"' not in active or "uuid.uuid4().hex" not in active:
        problems.append("fresh checkout/invocation binding missing")
    for path in ("chengyinhub-admin/src/test/java/com/chengyinhub/business/service/rendezvous/**",
                 "chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql",
                 "ci/w16_mysql_gate.py", "ci/test_w16_mysql_gate.py"):
        if len(re.findall(r"^\s+- '" + re.escape(path) + r"'$", workflow, re.MULTILINE)) != 2:
            problems.append("both workflow trigger blocks must cover " + path)
    if not re.search(r"^\s+run: python3 -m unittest .*ci/test_w16_mysql_gate.py", workflow, re.MULTILINE):
        problems.append("W16 negative controls must execute in workflow")
    declared = re.findall(r"@Test\s+void\s+(\w+)\(", source)
    if len(declared) != len(METHODS) or set(declared) != METHODS:
        problems.append("source method identity drift")
    if '@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")' not in source:
        problems.append("explicit isolated MySQL opt-in missing")
    if "mysql.open(\"w16_consent\")" not in source or "mysql.apply(MIGRATION)" not in source or "mysql.close()" not in source:
        problems.append("established isolated schema lifecycle missing")
    if "import org.h2" in source or re.search(r"class RendezvousConsentMySqlIT\s+extends", source):
        problems.append("MySQL source must not inherit/substitute H2")
    return problems


def validate(reports, commit, run_id):
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or not re.fullmatch(r"[0-9a-f]{32}", run_id):
        raise ValueError("Exact commit and fresh invocation binding required")
    candidates = []
    for path in sorted(Path(reports).glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        if root.get("name", "").rsplit(".", 1)[-1] == "RendezvousConsentMySqlIT":
            candidates.append((path, root))
    if len(candidates) != 1:
        raise ValueError("Exactly one W16 MySQL suite report required")
    path, root = candidates[0]
    if root.tag != "testsuite" or root.get("name") != SUITE:
        raise ValueError("Exact MySQL suite identity required; no H2 or namesake substitute")
    for key, expected in {"tests": len(METHODS), "skipped": 0, "failures": 0, "errors": 0}.items():
        if root.get(key) != str(expected):
            raise ValueError("W16 suite counter mismatch: " + key)
    if any(node.tag in {"skipped", "failure", "error", "flakyFailure", "flakyError", "rerunFailure", "rerunError"}
           for node in root.iter()):
        raise ValueError("Skipped, failed or retried execution is not accepted")
    cases = root.findall("testcase")
    identities = [case.get("name") for case in cases]
    # Surefire can emit either the FQCN or the no-argument method display name
    # in classname. The hosted 3.0.2 report uses the latter; accept exactly these
    # two uniform forms, never an arbitrary class/name suffix or a mixed report.
    fqcn_format = all(case.get("classname") == SUITE for case in cases)
    method_format = all(case.get("classname") == case.get("name", "") + "()" for case in cases)
    if len(cases) != len(METHODS) or set(identities) != METHODS or not (fqcn_format or method_format):
        raise ValueError("Exact unique W16 method identities required")
    proofs = {}
    for output in root.findall(".//system-out"):
        for line in (output.text or "").splitlines():
            if not line.startswith("W16_MYSQL_EVIDENCE"):
                continue
            match = EVIDENCE.fullmatch(line)
            if not match:
                raise ValueError("Malformed W16 proof line")
            method, version, schema, migration, proof_commit, invocation, dropped = match.groups()
            if method not in METHODS or method in proofs:
                raise ValueError("Unknown or duplicate W16 proof identity")
            if version != "8.0.45" or migration != MIGRATION_SHA or proof_commit != commit or invocation != run_id or dropped != "true":
                raise ValueError("Wrong database, migration, commit, invocation or cleanup proof")
            if not re.fullmatch(r"cy_module_city_w16_consent_[0-9a-f]{32}", schema):
                raise ValueError("Only unique isolated W16 scratch schemas are accepted")
            proofs[method] = schema
    if set(proofs) != METHODS or len(set(proofs.values())) != len(METHODS):
        raise ValueError("Each W16 test must prove its own MySQL scratch cleanup")
    return {"suite": SUITE, "tests": len(METHODS), "commit": commit, "run": run_id,
            "mysql": "8.0.45", "migration_sha256": MIGRATION_SHA, "scratch_dropped": True,
            "methods": sorted(METHODS), "report_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reports")
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output")
    args = parser.parse_args()
    try:
        proof = validate(args.reports, args.commit, args.run_id)
    except (ValueError, OSError, ET.ParseError) as error:
        parser.exit(1, "W16_MYSQL_GATE_FAILED: " + str(error) + "\n")
    if args.output:
        target = Path(args.output); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    print("W16_MYSQL_GATE_OK CASES=12 MYSQL=8.0.45 SKIPPED=0 RETRIES=0 COMMIT=" + args.commit + " RUN=" + args.run_id)


if __name__ == "__main__":
    main()
