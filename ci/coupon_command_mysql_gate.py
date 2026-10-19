#!/usr/bin/env python3
"""Strict, invocation-bound Coupon command MySQL proof, additive to the unchanged Funds gate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

SUITE = "com.chengyinhub.business.service.impl.CouponCommandServiceMySqlIT"
MIGRATION_SHA = "2696b2b9111738e9fdca15312777ead0e0a9ea5da19ed1da07a510473c37d11c"
FIXTURE_SHA = "9945a408d21efec3fc8c4393049bf715636d987ef01d12f14b497c39bcfc1c75"
METHODS = frozenset({
    "originalReceiptMigrationHasNativeInnoDbUniqueAndPrecision",
    "concurrentIdenticalCommandsCommitOneCouponAndOneReceipt",
    "actualMemberForUpdateBlocksCommandUntilOwnerTransactionCommits",
    "concurrentDifferentPayloadsCannotOverwriteTheWinningReceipt",
    "distinctKeysStillSerializeThroughActualQuotaGuard",
    "originalMinuteMigrationStillRejectsDifferentKeysWithoutFalseReceipt",
    "replaySurvivesQuotaFullRateLimitStopAndCouponDeletion",
    "receiptUniqueKeyIncludesActorMerchantAndExcludesOperation",
    "changedOwnerAndRevokedActorCannotRecoverForeignBinding",
    "receiptInsertFailureRollsBackCouponAndAllowsSameKeyRetry",
    "exceptionAfterReceiptInsertRollsBackBothDatabaseWrites",
    "exceptionAfterCouponInsertRollsBackBothDatabaseWrites",
    "missingGeneratedKeyCannotLeaveACommittedCoupon",
    "stopRollbackRestoresStatusAndNeverChangesClaimedHistory",
    "concurrentIdenticalStopsReplaySuccessAndPreserveClaimedHistory",
    "merchantStopSqlIsOwnerScopeMerchantAndStatusFenced",
    "commitThenResponseFailureReturnsGenericEnvelopeButReadbackRecovers",
    "rollbackThenResponseFailureHasSameGenericEnvelopeAndNoReceipt",
    "rollbackCanConsumeExistingRedisMinuteGuardWithoutFabricatedSuccess",
    "disabledOrMissingReceiptTableNeverFallsBackToMutation",
})
EVIDENCE = re.compile(
    r"COUPON_COMMAND_MYSQL_EVIDENCE METHOD=(\w+) VERSION=(\S+) SCHEMA=(\S+) "
    r"MIGRATION_SHA256=(\S+) FIXTURE_SHA256=(\S+) SOURCE_SHA256=(\S+) COMMIT=(\S+) RUN=(\S+) SCRATCH_DROPPED=(\S+)")


def validate(reports, commit, run_id, source_sha):
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or not re.fullmatch(r"[0-9a-f]{32}", run_id):
        raise ValueError("Exact commit and fresh invocation binding required")
    if not re.fullmatch(r"[0-9a-f]{64}", source_sha):
        raise ValueError("Exact source fingerprint required")
    candidates = []
    for path in sorted(Path(reports).glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        if root.get("name", "").rsplit(".", 1)[-1] == "CouponCommandServiceMySqlIT":
            candidates.append((path, root))
    if len(candidates) != 1:
        raise ValueError("Exactly one Coupon command MySQL suite report required")
    path, root = candidates[0]
    if root.tag != "testsuite" or root.get("name") != SUITE:
        raise ValueError("Exact MySQL suite identity required; no H2 or namesake substitute")
    for key, expected in {"tests": len(METHODS), "skipped": 0, "failures": 0, "errors": 0}.items():
        if root.get(key) != str(expected):
            raise ValueError("Coupon command suite counter mismatch: " + key)
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
        raise ValueError("Exact unique Coupon command method identities required")
    proofs = {}
    for output in root.findall(".//system-out"):
        for line in (output.text or "").splitlines():
            if not line.startswith("COUPON_COMMAND_MYSQL_EVIDENCE"):
                continue
            match = EVIDENCE.fullmatch(line)
            if not match:
                raise ValueError("Malformed Coupon command proof line")
            method, version, schema, migration, fixture, proof_source, proof_commit, invocation, dropped = match.groups()
            if method not in METHODS or method in proofs:
                raise ValueError("Unknown or duplicate Coupon command proof identity")
            if version != "8.0.45" or migration != MIGRATION_SHA or fixture != FIXTURE_SHA or proof_source != source_sha or proof_commit != commit or invocation != run_id or dropped != "true":
                raise ValueError("Wrong database, migration, commit, invocation or cleanup proof")
            if not re.fullmatch(r"cy_module_city_coupon_cmd_[0-9a-f]{32}", schema):
                raise ValueError("Only unique isolated Coupon command scratch schemas are accepted")
            proofs[method] = schema
    if set(proofs) != METHODS or len(set(proofs.values())) != len(METHODS):
        raise ValueError("Each Coupon command test must prove its own MySQL scratch cleanup")
    return {"suite": SUITE, "tests": len(METHODS), "commit": commit, "run": run_id,
            "mysql": "8.0.45", "migration_sha256": MIGRATION_SHA, "fixture_sha256": FIXTURE_SHA, "source_sha256": source_sha, "scratch_dropped": True,
            "methods": sorted(METHODS), "report_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


SOURCE_PATHS = ['chengyinhub-admin/sql/migration_coupon_command_receipt.sql', 'chengyinhub-admin/sql/migration_coupon_dedup.sql', 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCouponController.java', 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/support/IsolatedMySqlSchema.java', 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/MysqlIntegrationTestConfig.java', 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/CouponCommandServiceMySqlIT.java', 'chengyinhub-admin/src/test/resources/fixtures/coupon-command-existing-schema.sql', 'chengyinhub-framework/src/main/java/com/chengyinhub/framework/web/exception/GlobalExceptionHandler.java', 'chengyinhub-system/src/main/java/com/chengyinhub/business/domain/CouponCommandReceipt.java', 'chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CouponCommandReceiptMapper.java', 'chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/SmsCouponMapper.java', 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CouponCommandService.java', 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/QuotaServiceImpl.java', 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SmsCouponServiceImpl.java', 'chengyinhub-system/src/main/resources/mapper/business/CouponCommandReceiptMapper.xml', 'chengyinhub-system/src/main/resources/mapper/business/SmsCouponMapper.xml', 'chengyinhub-system/src/main/resources/mapper/business/UmsMemberMapper.xml']

def fingerprint_source(root):
    root = Path(root)
    rows = "".join(path + "\0" + hashlib.sha256((root / path).read_bytes()).hexdigest() + "\n" for path in SOURCE_PATHS)
    return hashlib.sha256(rows.encode("utf-8")).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="?")
    parser.add_argument("--commit")
    parser.add_argument("--run-id")
    parser.add_argument("--source-sha256")
    parser.add_argument("--fingerprint-source")
    parser.add_argument("--output")
    args = parser.parse_args()
    try:
        if args.fingerprint_source:
            print(fingerprint_source(args.fingerprint_source)); return
        if not all((args.reports, args.commit, args.run_id, args.source_sha256)):
            raise ValueError("Reports, commit, run-id and source-sha256 are required")
        proof = validate(args.reports, args.commit, args.run_id, args.source_sha256)
    except (ValueError, OSError, ET.ParseError) as error:
        parser.exit(1, "COUPON_COMMAND_MYSQL_GATE_FAILED: " + str(error) + "\n")
    if args.output:
        target = Path(args.output); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    print("COUPON_COMMAND_MYSQL_GATE_OK CASES=20 MYSQL=8.0.45 SKIPPED=0 RETRIES=0 COMMIT=" + args.commit + " RUN=" + args.run_id)


if __name__ == "__main__":
    main()
