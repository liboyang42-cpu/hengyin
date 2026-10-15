#!/usr/bin/env python3
"""Read-only source/custody preparation. Never connects to MySQL or applies SQL."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--schema", required=True)
    parser.add_argument("--policy-source-custody", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    sys.path.insert(0, str(root))
    from ci.migration_ledger import build_manifest
    from scripts.e2e import build_schema

    if not build_schema.SCRATCH_SCHEMA.fullmatch(args.schema):
        parser.error("only a new task-owned cy_e2e_* schema may be prepared")
    if args.schema == "cy_e2e_prelaunchfix1012":
        parser.error("the retained old fixture schema must not be replayed or dropped")
    build_schema.REPO = root
    build_schema.BASELINE = root / "sql/base_schema_20260818.sql"
    build_schema.BASELINE_SEED = root / "sql/base_seed_20260818.sql"
    build_schema.BASELINE_MANIFEST = root / "scripts/e2e/base_schema_20260818_migrations.json"
    build_schema.SCHEMA_REPLAY_POLICY = root / "scripts/e2e/schema_replay_policy_20260921.json"
    # Reuse the actual Merkle/source and replay policy validators, not a second sorter.
    build_schema.POLICY_CUSTODY = (build_schema.verified_policy_custody(args.policy_source_custody)
                                   if args.policy_source_custody else None)
    policy = json.loads(build_schema.SCHEMA_REPLAY_POLICY.read_text())
    if build_schema.POLICY_CUSTODY is None:
        build_schema.source_provenance_files(policy["sourceCommit"])
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    graph = build_manifest(root, head)
    selected = build_schema.migrations_after_baseline(graph["migrations"])
    pre = [item for item in selected if item.get("executionPhase") != "POST_DEPLOY"]
    post = [item for item in selected if item.get("executionPhase") == "POST_DEPLOY"]
    paths = ["sql/base_schema_20260818.sql", "sql/base_seed_20260818.sql",
             "scripts/e2e/base_schema_20260818_migrations.json",
             "scripts/e2e/schema_replay_policy_20260921.json", "ci/migration-dependencies.json",
             "ci/migration_ledger.py", "ci/sql_splitter.py", "ci/run_migration.py",
             "scripts/e2e/build_schema.py", "scripts/e2e/policy_source_custody.py"]
    if build_schema.POLICY_CUSTODY is None:
        paths += ["scripts/e2e/source_provenance_20260818.json",
                  "scripts/e2e/fixtures/baseline_menu_2050.sql.txt"]
    payload = {
        "evidenceLayer": "STATIC_SOURCE_AND_CUSTODY_ONLY",
        "liveDatabaseVerified": False,
        "database": args.schema,
        "mustBeAbsentBeforeBuild": True,
        "retainedFixtureCommitBinding": "491283214cb68836714e5dca095b13c354e870ba is not bound to the schema-source archive",
        "graphHeadLabel": head,
        "immutableReleaseCommitVerified": False,
        "policySourceCommit": policy["sourceCommit"],
        "policySourceVerification": ("GIT_MERKLE_SELECTED_OBJECT_PROOF" if build_schema.POLICY_CUSTODY
                                     else "PINNED_PUBLIC_SOURCE_HASH_SNAPSHOT_NOT_GIT_MERKLE"),
        "historicalGitCommitVerified": build_schema.POLICY_CUSTODY is not None,
        "managedMigrationCount": len(graph["migrations"]),
        "selectedReplayCount": len(selected),
        "preDeployCount": len(pre),
        "deferredPostDeployCount": len(post),
        "preDeploy": pre,
        "deferredPostDeploy": post,
        "sourceInputs": [{"path": path, "sha256": hashlib.sha256((root / path).read_bytes()).hexdigest()}
                         for path in paths],
        "custodyIndexSha256": (hashlib.sha256((args.policy_source_custody / "index.json").read_bytes()).hexdigest()
                               if args.policy_source_custody else None),
    }
    encoded = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
    else:
        print(encoded, end="")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        # Source validators emit metadata only; never expose SQL bodies or credentials.
        print("PRE_DEPLOY_STATIC_NO_GO: " + str(error), file=sys.stderr)
        raise SystemExit(1)
