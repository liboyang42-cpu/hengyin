#!/usr/bin/env python3
"""Export and verify only the SQL source objects required by the unchanged replay policy."""
import argparse
import hashlib
import subprocess
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from ci.migration_ledger import MIGRATION_PATH, EXPLICIT_MANAGED_SQL
from scripts.e2e import build_schema
from scripts.e2e.policy_source_custody import export_proof


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    build_schema.BASELINE_MANIFEST = args.root / "scripts/e2e/base_schema_20260818_migrations.json"
    build_schema.SCHEMA_REPLAY_POLICY = args.root / "scripts/e2e/schema_replay_policy_20260921.json"
    build_schema.REPO = args.root.resolve()
    policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text())
    historical = subprocess.run(["git", "-C", str(args.root), "cat-file", "-e", policy["sourceCommit"] + "^{commit}"],
                                capture_output=True)
    if historical.returncode != 0:
        # A reviewed public hash snapshot cannot prove an unavailable historical Git object.
        fingerprints = build_schema.source_provenance_files(policy["sourceCommit"])
        metadata = json.loads(build_schema.BASELINE_MANIFEST.read_text())
        expected = {item["path"]: item["sourceSha256"] for item in metadata["migrations"]}
        expected.update(policy["baselineMissingMigrations"])
        if (len(expected) != policy["sourceMigrationCount"] or any(fingerprints.get(path) != sha
                for path, sha in {**expected, **policy["baselineSeed"]["sourceFiles"]}.items())):
            raise ValueError("Public source snapshot differs from replay policy")
        args.output.mkdir(exist_ok=False)
        report = {"schemaVersion": 1, "scope": "PINNED_PUBLIC_HASH_SNAPSHOT_NOT_GIT_MERKLE",
                  "policySourceCommit": policy["sourceCommit"], "historicalGitCommitVerified": False,
                  "sourceProvenanceSha256": hashlib.sha256((args.root / "scripts/e2e/source_provenance_20260818.json").read_bytes()).hexdigest(),
                  "migrationCount": len(expected), "objectsTransferred": 0,
                  "usage": "Run check-candidate-predeploy.py without --policy-source-custody; this receipt is not a Git proof"}
        (args.output / "snapshot-receipt.json").write_text(json.dumps(report, sort_keys=True, indent=2) + "\n")
        print(json.dumps(report))
        return 0
    report = export_proof(args.root, policy["sourceCommit"], args.output, MIGRATION_PATH, EXPLICIT_MANAGED_SQL,
                          policy["baselineSeed"]["sourceFiles"])
    verified = build_schema.verified_policy_custody(args.output)
    report.update({"verified": True, "migrationCount": len(verified.migrations),
                   "ancestorObjectsTransferred": False, "unrelatedBlobsTransferred": False})
    print(json.dumps(report))


if __name__ == "__main__": main()
