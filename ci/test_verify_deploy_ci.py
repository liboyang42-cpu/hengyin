import importlib.util
from pathlib import Path
import re
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("verify_deploy", Path(__file__).with_name("verify_deploy_ci.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
SHA = "a" * 40
REPOSITORY = "fixture/repository"


class ManualDeployCiTest(unittest.TestCase):
    def fixture(self, path):
        if "/workflows/" in path:
            return {"workflow_runs": [{"head_sha": SHA, "head_repository": {"full_name": REPOSITORY},
                                       "run_number": 1, "run_attempt": 1, "id": 17,
                                       "status": "completed", "conclusion": "success"}]}
        names = ["Backend tests", "Mini program static checks", "Secret scan (gitleaks)",
                 "SBOM + dependency scan", "Migration layout gate", "Migration safety gate",
                 "Admin UI E2E contract gate", "Admin UI build gate",
                 "Fast gates (CWD / API existence / silentError)",
                 "Funds IT gate self-test (skipped=0 negative controls)",
                 "Funds MySQL IT (50/9/30, skipped=0, fail-closed)"]
        steps = [{"name": name, "conclusion": "success"} for name in
                 ("Build and test backend", "Run mini program unit contracts",
                  "Run funds MySQL integration gate (fail-closed on missing MySQL)")]
        return {"jobs": [{"name": name, "conclusion": "success", "steps": steps} for name in names]}

    def test_full_same_sha_evidence_passes_and_branch_names_fail_before_network(self):
        with patch.object(gate, "api", side_effect=self.fixture):
            gate.verify(SHA, REPOSITORY)
        with patch.object(gate, "api") as api, self.assertRaises(ValueError):
            gate.verify("master", REPOSITORY)
        api.assert_not_called()

    def test_skipped_test_step_failed_latest_run_wrong_sha_and_foreign_repo_are_rejected(self):
        for mode in ("skipped-step", "failed", "wrong-sha", "foreign-repo"):
            def altered(path):
                result = self.fixture(path)
                if "workflow_runs" in result:
                    run = result["workflow_runs"][0]
                    if mode == "failed": run["conclusion"] = "failure"
                    if mode == "wrong-sha": run["head_sha"] = "b" * 40
                    if mode == "foreign-repo": run["head_repository"]["full_name"] = "other/repo"
                elif mode == "skipped-step":
                    result["jobs"][0]["steps"][0]["conclusion"] = "skipped"
                return result
            with self.subTest(mode=mode), patch.object(gate, "api", side_effect=altered), \
                    self.assertRaises(ValueError):
                gate.verify(SHA, REPOSITORY)

    def test_parallel_mini_requires_all_twenty_shards_and_static_gates(self):
        def parallel_fixture(path):
            result = self.fixture(path)
            if "jobs" in result:
                mini = next(job for job in result["jobs"] if job["name"] == "Mini program static checks")
                mini["steps"] = [{"name": "Verify all mini program checks", "conclusion": "success"}]
                result["jobs"].append({"name": "Mini program static gates", "conclusion": "success", "steps": [
                    {"name": "Check mini program JavaScript and JSON (incl. U2/U3/U4 wxml-js bind gates)", "conclusion": "success"}]})
                result["jobs"].extend({"name": "Mini program unit shard (%d/20)" % shard,
                    "conclusion": "success", "steps": [{"name": "Run mini program unit contracts", "conclusion": "success"}]}
                    for shard in range(1, 21))
            return result
        with patch.object(gate, "api", side_effect=parallel_fixture):
            gate.verify(SHA, REPOSITORY)
        targets = ["Mini program static gates", "Mini program static checks"] + [
            "Mini program unit shard (%d/20)" % shard for shard in range(1, 21)]
        for target in targets:
            for mode in ("missing", "failure", "cancelled", "skipped", "skipped-step"):
                def altered(path):
                    result = parallel_fixture(path)
                    if "jobs" in result:
                        if mode == "missing":
                            result["jobs"] = [job for job in result["jobs"] if job["name"] != target]
                        else:
                            job = next(job for job in result["jobs"] if job["name"] == target)
                            if mode == "skipped-step":
                                job["steps"][0]["conclusion"] = "skipped"
                            else:
                                job["conclusion"] = mode
                    return result
                with self.subTest(target=target, mode=mode), patch.object(gate, "api", side_effect=altered), self.assertRaises(ValueError):
                    gate.verify(SHA, REPOSITORY)

    def assert_private_repository_guard(self, condition):
        condition = condition.strip().removeprefix('${{').removesuffix('}}').strip()
        prefix = "github.repository == 'liboyang42-cpu/chengyin' &&"
        self.assertTrue(condition.startswith(prefix), 'production job lacks the private-repository guard')
        remaining = condition[len(prefix):].strip()
        self.assertTrue(remaining, 'production job guard lacks a condition')
        # A top-level OR could bypass the leading repository check.
        tokens = re.sub(r"'[^']*'|\"[^\"]*\"", '', remaining)
        depth = 0
        for index, char in enumerate(tokens):
            if char == '(':
                depth += 1
            elif char == ')':
                depth -= 1
                self.assertGreaterEqual(depth, 0)
            elif tokens[index:index + 2] == '||' and depth == 0:
                self.fail('production job can bypass the private-repository guard')
        self.assertEqual(0, depth)

    def test_all_production_jobs_reject_public_repository_even_for_manual_runs(self):
        for name in ('deploy.yml', 'deploy-ui.yml', 'codeup-mirror.yml', 'drift-audit.yml'):
            source = (Path(__file__).resolve().parents[1] / '.github/workflows' / name).read_text()
            jobs = re.findall(r'^  ([\w-]+):\n([\s\S]*?)(?=^  [\w-]+:|\Z)',
                              source.split('\njobs:\n', 1)[1], re.MULTILINE)
            self.assertTrue(jobs)
            for job, body in jobs:
                with self.subTest(workflow=name, job=job):
                    matched = re.search(r'^    if:([^\n]*)(?:\n((?:      [^\n]*(?:\n|$))*))?', body, re.MULTILINE)
                    self.assertIsNotNone(matched, 'every production job must be guarded')
                    condition = matched.group(1).strip()
                    if condition in ('>', '>-', '|', '|-'):
                        condition = ' '.join(line.strip() for line in (matched.group(2) or '').splitlines())
                    self.assert_private_repository_guard(condition)

    def test_deploy_workflow_is_dispatch_only_and_runs_exact_sha_gate_before_classify(self):
        source = (Path(__file__).resolve().parents[1] / '.github/workflows/deploy.yml').read_text()
        triggers = source.split('\non:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertEqual(['workflow_dispatch'], re.findall(r'^  ([\w-]+):', triggers, re.MULTILINE))
        ref_input = re.search(r'^      ref:\n((?:        [^\n]*\n)*)', triggers, re.MULTILINE).group(1)
        self.assertNotIn('default:', ref_input, 'deploy ref must not default to a branch name')
        self.assertRegex(source, r'(?m)^  actions: read$')
        prepare = source.split('\n  prepare:\n', 1)[1].split('\n  deploy:\n', 1)[0]
        gate_at = prepare.find('python3 ci/verify_deploy_ci.py "$DEPLOY_REF" "$REPOSITORY"')
        self.assertGreater(gate_at, 0, 'manual deploy must verify exact-SHA CI evidence')
        self.assertLess(gate_at, prepare.index('bash ci/deploy-classify.sh'))
        self.assertIn('DEPLOY_REF: ${{ inputs.ref }}', prepare)
        self.assertIn('test "$(git rev-parse HEAD)" = "$DEPLOY_REF"', prepare)
        self.assertNotIn('continue-on-error', prepare)

    def test_removed_wrong_repository_and_or_bypass_guards_are_rejected(self):
        prefix = "github.repository == 'liboyang42-cpu/chengyin' && "
        self.assert_private_repository_guard(prefix + "(github.event_name == 'workflow_dispatch' || success())")
        for condition in ("github.event_name == 'workflow_dispatch'", prefix.replace('chengyin', 'hengyin') + 'true',
                          prefix + "success() || github.event_name == 'workflow_dispatch'",
                          "true # " + prefix + 'true'):
            with self.subTest(condition=condition), self.assertRaises(AssertionError):
                self.assert_private_repository_guard(condition)


if __name__ == "__main__":
    unittest.main()
