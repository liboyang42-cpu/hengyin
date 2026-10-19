#!/usr/bin/env python3
"""Strict, invocation-bound NPC draft MySQL proof, additive to the unchanged Funds gate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

SUITE = "com.chengyinhub.business.service.ai.npc.MerchantNpcKnowledgeDraftMySqlIT"
MIGRATION_SHA = "8aa009a719247e23647affae8b0324a1c60b1e10e77842eb3ba3bf7985c87c5c"
FIXTURE_SHA = "ff8f708bd05b5174d5a8ba5ac76c0e495917bb725a0fa083cec120ef900e205c"
METHODS = frozenset({
    "savesImmutableRevisionAndReadbackWithActualOwnerMapper",
    "newRevisionPreservesOldContentAndRejectsStaleCas",
    "retryIsBoundToOriginalPayloadAndVersion",
    "changedOwnerFencesReadsWritesReplayAndReview",
    "crossMerchantAndDisabledMerchantFailClosed",
    "featureOffAndMissingTransactionCannotWrite",
    "missingReviewAuthorityDoesNotCreateTasks",
    "submitsExactPendingCandidateToExistingAuditTablesAndReplays",
    "staleVersionCannotSubmitOrReviewAfterHeadMoves",
    "alteredSnapshotOrSourceTypeCannotReplay",
    "persistedContentTamperingFailsHashReadback",
    "saveAuditFailureRollsBackRevisionAndHead",
    "submitAuditRecordFailureRollsBackTaskAndBinding",
    "deniedReviewerCannotValidate",
    "concurrentSavesWithSameExpectedVersionCommitOnlyOnce",
    "sqlCasAndBindingAreOwnerFencedEvenWithoutService",
    "failedHeadWriteRollsBackRevision",
    "failedRevisionWriteLeavesNoHeadOrAudit",
    "failedBindingAndSubmitEventRollBackWholeHandoff",
    "forgedTaskIdentityAndHashFailClosed",
    "readOnlyTransactionStopsBeforePersistence",
    "retryAfterNewHeadReturnsOriginalReceiptWithoutRewinding",
    "concurrentSameRevisionSubmissionsCreateOneTaskAndRecord",
    "malformedPublicFactsCannotPersistOrBorrowPrivateProfileFields",
    "everyCurrentMerchantEligibilityFieldFencesExistingReceiptAndReview",
    "merchantTransferCommittedWhileReplayWaitsCannotReturnOldReceipt",
    "actualSchemaRetainsNativeJsonOpenGuardAndStrictRequiredColumns",
    "approvedMigrationCannotSilentlyReuseWeakPreexistingTables",
})
EVIDENCE = re.compile(
    r"NPC_DRAFT_MYSQL_EVIDENCE METHOD=(\w+) VERSION=(\S+) SCHEMA=(\S+) "
    r"MIGRATION_SHA256=(\S+) FIXTURE_SHA256=(\S+) COMMIT=(\S+) RUN=(\S+) SCRATCH_DROPPED=(\S+)")


def validate(reports, commit, run_id):
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or not re.fullmatch(r"[0-9a-f]{32}", run_id):
        raise ValueError("Exact commit and fresh invocation binding required")
    candidates = []
    for path in sorted(Path(reports).glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        if root.get("name", "").rsplit(".", 1)[-1] == "MerchantNpcKnowledgeDraftMySqlIT":
            candidates.append((path, root))
    if len(candidates) != 1:
        raise ValueError("Exactly one NPC draft MySQL suite report required")
    path, root = candidates[0]
    if root.tag != "testsuite" or root.get("name") != SUITE:
        raise ValueError("Exact MySQL suite identity required; no H2 or namesake substitute")
    for key, expected in {"tests": len(METHODS), "skipped": 0, "failures": 0, "errors": 0}.items():
        if root.get(key) != str(expected):
            raise ValueError("NPC draft suite counter mismatch: " + key)
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
        raise ValueError("Exact unique NPC draft method identities required")
    proofs = {}
    for output in root.findall(".//system-out"):
        for line in (output.text or "").splitlines():
            if not line.startswith("NPC_DRAFT_MYSQL_EVIDENCE"):
                continue
            match = EVIDENCE.fullmatch(line)
            if not match:
                raise ValueError("Malformed NPC draft proof line")
            method, version, schema, migration, fixture, proof_commit, invocation, dropped = match.groups()
            if method not in METHODS or method in proofs:
                raise ValueError("Unknown or duplicate NPC draft proof identity")
            if version != "8.0.45" or migration != MIGRATION_SHA or fixture != FIXTURE_SHA or proof_commit != commit or invocation != run_id or dropped != "true":
                raise ValueError("Wrong database, migration, commit, invocation or cleanup proof")
            if not re.fullmatch(r"cy_module_city_npc_draft_[0-9a-f]{32}", schema):
                raise ValueError("Only unique isolated NPC draft scratch schemas are accepted")
            proofs[method] = schema
    if set(proofs) != METHODS or len(set(proofs.values())) != len(METHODS):
        raise ValueError("Each NPC draft test must prove its own MySQL scratch cleanup")
    return {"suite": SUITE, "tests": len(METHODS), "commit": commit, "run": run_id,
            "mysql": "8.0.45", "migration_sha256": MIGRATION_SHA, "fixture_sha256": FIXTURE_SHA, "scratch_dropped": True,
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
        parser.exit(1, "NPC_DRAFT_MYSQL_GATE_FAILED: " + str(error) + "\n")
    if args.output:
        target = Path(args.output); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    print("NPC_DRAFT_MYSQL_GATE_OK CASES=28 MYSQL=8.0.45 SKIPPED=0 RETRIES=0 COMMIT=" + args.commit + " RUN=" + args.run_id)


if __name__ == "__main__":
    main()
