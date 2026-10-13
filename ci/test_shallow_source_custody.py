import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import sys
import unittest
from scripts.e2e.policy_source_custody import SourceCustody, export_proof

SPEC = importlib.util.spec_from_file_location("shallow_candidate", Path(__file__).resolve().parents[1] / "scripts/prepare-shallow-candidate.py")
SHALLOW = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(SHALLOW)
PATTERN = re.compile(r"chengyinhub-admin/sql/migration_[a-z0-9_]+\.sql\Z")


class ExactSourceCustodyTest(unittest.TestCase):
    def fixture(self, root, extra=False):
        repo = root / "repo"; repo.mkdir()
        def git(*args):
            return subprocess.check_output(["git", "-c", "core.hooksPath=/dev/null", "-C", str(repo), *args], stderr=subprocess.DEVNULL)
        git("init", "-q"); git("config", "user.name", "Fixture"); git("config", "user.email", "fixture@example.invalid")
        secret = repo / "cert/apiclient_key.pem"; secret.parent.mkdir(); secret.write_bytes(b"synthetic historical marker")
        git("add", "."); git("commit", "-qm", "fixture ancestor")
        ancestor = git("rev-parse", "HEAD").decode().strip(); secret_oid = git("rev-parse", "HEAD:cert/apiclient_key.pem").decode().strip()
        migration = repo / "chengyinhub-admin/sql/migration_fixture.sql"; migration.parent.mkdir(parents=True); migration.write_bytes(b"SELECT 1;\n")
        seed = repo / "sql/ry_fixture.sql"; seed.parent.mkdir(); seed.write_bytes(b"SELECT 2;\n")
        if extra: (migration.parent / "migration_extra.sql").write_bytes(b"SELECT 3;\n")
        git("add", "."); git("commit", "-qm", "fixture policy")
        policy = git("rev-parse", "HEAD").decode().strip()
        secret.unlink(); (repo / "public.bin").write_bytes(bytes(range(256)))
        git("add", "-A"); git("commit", "-qm", "fixture candidate")
        head = git("rev-parse", "HEAD").decode().strip()
        migrations = {migration.relative_to(repo).as_posix(): hashlib.sha256(migration.read_bytes()).hexdigest()}
        seeds = {seed.relative_to(repo).as_posix(): hashlib.sha256(seed.read_bytes()).hexdigest()}
        return repo, policy, head, ancestor, secret_oid, migrations, seeds

    def proof(self, repo, policy, out, migrations, seeds):
        export_proof(repo, policy, out, PATTERN, set(), seeds)
        return SourceCustody(out, policy, PATTERN, set(), migrations, seeds)

    def test_exact_head_full_public_asset_tree_and_sha_survive_without_ancestor_or_secret_objects(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); repo, policy, head, ancestor, secret, migrations, seeds = self.fixture(root)
            destination = root / "candidate"
            manifest = SHALLOW.prepare(repo, head, destination)
            self.assertEqual(head, SHALLOW.git(destination, "rev-parse", "HEAD").decode().strip())
            self.assertEqual(bytes(range(256)), (destination / "public.bin").read_bytes())
            self.assertNotIn(ancestor, manifest["objects"]); self.assertNotIn(policy, manifest["objects"]); self.assertNotIn(secret, manifest["objects"])
            self.assertEqual(head + "\n", (destination / ".git/shallow").read_text())
            self.assertFalse(SHALLOW.git(destination, "status", "--porcelain"))
            self.assertFalse((destination / ".git/objects/info/alternates").exists())
            self.assertFalse((destination / ".git/hooks").exists())

    def test_original_policy_commit_and_complete_sql_paths_verify_without_unrelated_certificate_blob(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); repo, policy, head, ancestor, secret, migrations, seeds = self.fixture(root)
            verified = self.proof(repo, policy, root / "proof", migrations, seeds)
            self.assertEqual(migrations, verified.migrations)
            self.assertNotIn(secret, verified.objects); self.assertNotIn(ancestor, verified.objects)

    def test_missing_or_corrupted_blob_and_wrong_source_commit_fail_closed(self):
        for mode in ("missing", "corrupted", "wrong-commit"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); repo, policy, head, ancestor, secret, migrations, seeds = self.fixture(root)
                out = root / "proof"; good = self.proof(repo, policy, out, migrations, seeds)
                oid = next(k for k,v in good.objects.items() if v == "blob")
                if mode == "missing": (out / "objects" / oid).unlink()
                elif mode == "corrupted": (out / "objects" / oid).write_bytes(b"changed")
                with self.assertRaises((ValueError, OSError)):
                    SourceCustody(out, head if mode == "wrong-commit" else policy, PATTERN, set(), migrations, seeds)

    def test_extra_migration_and_hash_or_path_changes_are_not_accepted_as_a_partial_policy(self):
        for mode in ("extra-migration", "changed-hash", "changed-path"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); repo, policy, head, ancestor, secret, migrations, seeds = self.fixture(root, extra=mode == "extra-migration")
                out = root / "proof"; export_proof(repo, policy, out, PATTERN, set(), seeds)
                if mode == "changed-hash": migrations[next(iter(migrations))] = "0" * 64
                if mode == "changed-path": migrations = {"chengyinhub-admin/sql/migration_wrong.sql": next(iter(migrations.values()))}
                with self.assertRaises(ValueError): SourceCustody(out, policy, PATTERN, set(), migrations, seeds)

    def test_unrelated_secret_blob_is_refused_even_when_indexed_and_original_git_hash_is_valid(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); repo, policy, head, ancestor, secret, migrations, seeds = self.fixture(root)
            out = root / "proof"; self.proof(repo, policy, out, migrations, seeds)
            index = json.loads((out / "index.json").read_text()); index["objects"][secret] = "blob"
            (out / "objects" / secret).write_bytes(SHALLOW.git(repo, "cat-file", "blob", secret))
            (out / "index.json").write_text(json.dumps(index))
            with self.assertRaisesRegex(ValueError, "Unrelated"): SourceCustody(out, policy, PATTERN, set(), migrations, seeds)

    def test_dirty_candidate_or_existing_destination_cannot_be_packaged(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); repo, policy, head, ancestor, secret, migrations, seeds = self.fixture(root)
            (repo / "public.bin").write_bytes(b"uncommitted")
            with self.assertRaises(ValueError): SHALLOW.prepare(repo, head, root / "out")
            self.assertFalse((root / "out").exists())
            SHALLOW.git(repo, "checkout", "--", "public.bin")
            out = root / "out"; out.mkdir(); (out / "keep").write_text("owned")
            with self.assertRaises(FileExistsError): SHALLOW.prepare(repo, head, out)
            self.assertEqual("owned", (out / "keep").read_text())


class PublicSnapshotHelperTest(unittest.TestCase):
    def test_predeploy_snapshot_is_static_and_never_claims_historical_git_verification(self):
        repo = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'report.json'
            result = subprocess.run([sys.executable, str(repo / 'scripts/check-candidate-predeploy.py'),
                '--root', str(repo), '--schema', 'cy_e2e_public_static_fixture', '--output', str(output)],
                capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            report = json.loads(output.read_text())
            self.assertFalse(report['liveDatabaseVerified'])
            self.assertFalse(report['historicalGitCommitVerified'])
            self.assertFalse(report['immutableReleaseCommitVerified'])
            self.assertIsNone(report['custodyIndexSha256'])
            self.assertIn('PINNED_PUBLIC_SOURCE_HASH_SNAPSHOT', report['policySourceVerification'])

    def test_public_snapshot_receipt_has_no_git_objects_and_cannot_overwrite_output(self):
        repo = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'snapshot'
            command = [sys.executable, str(repo / 'scripts/prepare-policy-source-custody.py'),
                       '--root', str(repo), '--output', str(output)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            receipt = json.loads((output / 'snapshot-receipt.json').read_text())
            self.assertFalse(receipt['historicalGitCommitVerified'])
            self.assertEqual(0, receipt['objectsTransferred'])
            self.assertEqual(['snapshot-receipt.json'], [path.name for path in output.iterdir()])
            self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)


if __name__ == "__main__": unittest.main()
