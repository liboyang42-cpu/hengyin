"""SOURCE ONLY: pure controls; no Docker, HTTP, database or provider execution."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
from unittest import mock

path = Path(__file__).with_name("capacity_mixed_probe.py")
spec = importlib.util.spec_from_file_location("capacity_mixed", path)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class CapacityMixedControls(unittest.TestCase):
    def test_scope_is_manual_exact_hosted_child_only(self):
        env = {
            "GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux",
            "RUNNER_ENVIRONMENT": "github-hosted",
            "GITHUB_WORKFLOW": "Isolated authenticated Redis recovery",
            "GITHUB_JOB": "isolated-authenticated-redis-recovery",
            "GITHUB_REPOSITORY": "liboyang42-cpu/hengyin",
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_WORKFLOW_REF":
                "liboyang42-cpu/hengyin/.github/workflows/"
                "isolated-authenticated-redis-recovery.yml@refs/heads/codex/test",
            "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_SHA": "a" * 40,
            "CHENGYIN_CI_AUTH_REDIS_RECOVERY": "true",
            "CHENGYIN_CI_CAPACITY_MIXED": "true",
        }
        with mock.patch.object(probe.sys, "platform", "linux"):
            probe.validate_env(env)
            for key, value in (
                ("GITHUB_EVENT_NAME", "pull_request"),
                ("GITHUB_SHA", "a" * 7),
                ("GITHUB_RUN_ATTEMPT", "0"),
                ("CHENGYIN_CI_CAPACITY_MIXED", "false"),
                ("RUNNER_ENVIRONMENT", "self-hosted"),
                ("DOCKER_HOST", "ssh://foreign"),
                ("GITHUB_JOB", "backend"),
            ):
                with self.subTest(key=key), self.assertRaises(RuntimeError):
                    probe.validate_env(dict(env, **{key: value}))

    def test_profile_does_not_upgrade_existing_caps_or_pool(self):
        objects = []
        for index in range(3):
            memory = (1024 ** 3, 1024 ** 3, 64 * 1024 ** 2)[index]
            objects.append({
                "Id": str(index + 1) * 64,
                "HostConfig": {
                    "Memory": memory, "MemorySwap": memory,
                    "NanoCpus": (10 ** 9, 10 ** 9, 0)[index],
                    "Privileged": False, "PortBindings": {},
                },
                "Config": {"Cmd": [
                    "--spring.config.location=file:/fixture/application.properties",
                    "--spring.datasource.druid.master.url=jdbc:mysql://owned/schema",
                ]},
            })
        probe.validate_profile(objects, probe.PROFILE_PROPERTIES)
        for alteration in ("memory", "swap", "cpu", "port", "privileged", "pool"):
            changed = copy.deepcopy(objects)
            host = changed[0]["HostConfig"]
            if alteration == "memory":
                host["Memory"] *= 2
            elif alteration == "swap":
                host["MemorySwap"] *= 2
            elif alteration == "cpu":
                host["NanoCpus"] *= 2
            elif alteration == "port":
                host["PortBindings"] = {"8080/tcp": [{"HostPort": "8080"}]}
            elif alteration == "privileged":
                host["Privileged"] = True
            else:
                changed[0]["Config"]["Cmd"].append("--spring.datasource.druid.maxActive=20")
            with self.subTest(alteration=alteration), self.assertRaises(RuntimeError):
                probe.validate_profile(changed, probe.PROFILE_PROPERTIES)

    def test_insufficient_headroom_is_not_run_not_a_smaller_load(self):
        limits = [{"memoryBytes": 1024 ** 3}] * 2 + [{"memoryBytes": 64 * 1024 ** 2}]
        probe.admit(limits, 4 * 1024 ** 3, 1024 ** 3, 2, 100)
        for memory, disk, cpus in (
            (1024, 1024 ** 3, 2), (4 * 1024 ** 3, 1, 2),
            (4 * 1024 ** 3, 1024 ** 3, 1),
        ):
            with self.subTest(memory=memory, disk=disk, cpus=cpus):
                with self.assertRaises(probe.NotRun):
                    probe.admit(limits, memory, disk, cpus, 100)

    def test_http_200_and_json_shape_are_not_success_proof(self):
        expected = [("capacity-0", "0")]
        good = {"code": 200, "data": [{"dictLabel": "capacity-0", "dictValue": "0"}]}
        self.assertEqual("SUCCESS", probe.classify("DICT", 200, good, expected))
        for body in (
            {"code": 200}, {"code": 200, "data": []},
            {"code": 200, "data": [{"dictLabel": "other", "dictValue": "0"}]},
            {"code": 200, "data": ["wrong"]},
        ):
            with self.subTest(body=body):
                self.assertEqual("CONTRACT_MISMATCH",
                                 probe.classify("DICT", 200, body, expected))
        self.assertEqual("CONTRACT_MISMATCH",
                         probe.classify("ACTOR", 200, {"code": 200, "user": {"userId": 2}}))
        self.assertEqual("UNKNOWN", probe.classify("DICT", 200, {"code": "200"}))

    def test_auth_rejection_system_failure_and_unknown_are_separate(self):
        self.assertEqual("LEGAL_AUTH_REJECTION",
                         probe.classify("RELEASE", 200, {"code": 401}))
        self.assertEqual("SYSTEM_FAILURE",
                         probe.classify("RELEASE", 200, {"code": 500}))
        self.assertEqual("UNKNOWN", probe.classify("RELEASE", 200, {"code": 409}))
        self.assertEqual("UNKNOWN", probe.classify("RELEASE", 200, None))
        # The projection never returns dynamic response data or failure messages.
        canary = "secret-canary-response"
        result = probe.classify("DICT", 500, {"code": 500, "msg": canary, "sql": canary})
        self.assertNotIn(canary, json.dumps(result))

    def test_physical_overlap_cannot_be_inferred_from_slow_threads(self):
        probe.assert_fault_overlap(3, True)
        for count, paused in ((0, True), (2, True), (4, True), (3, False), ("3", True)):
            with self.subTest(count=count, paused=paused), self.assertRaises(RuntimeError):
                probe.assert_fault_overlap(count, paused)

    def test_counts_latency_and_throughput_do_not_merge_unknown_or_timeout(self):
        records = [
            {"category": category, "elapsedSeconds": float(index + 1)}
            for index, category in enumerate((
                "SUCCESS", "LEGAL_AUTH_REJECTION", "UNKNOWN",
                "TRANSPORT_TIMEOUT", "SYSTEM_FAILURE"))
        ]
        report = probe.distribution(records, 10)
        self.assertEqual(1, report["counts"]["UNKNOWN"])
        self.assertEqual(1, report["counts"]["TRANSPORT_TIMEOUT"])
        self.assertEqual(1, report["counts"]["LEGAL_AUTH_REJECTION"])
        self.assertEqual(.1, report["successfulRequestsPerSecond"])
        self.assertEqual(.3, report["completedResponsesPerSecond"])
        self.assertEqual(3, report["p50Seconds"])
        self.assertEqual(5, report["p99Seconds"])
        self.assertEqual(5, sum(report["counts"].values()))

    def test_curve_has_no_extra_submission_queue_or_arbitrary_scope(self):
        observed = []
        def request(kind, value):
            observed.append((kind, value))
            return {"category": "SUCCESS", "elapsedSeconds": .01}
        items = [("ACTOR", None)] * probe.REQUESTS_PER_STAGE
        report = probe.run_curve(items, request, 3, lambda: self.fail("unexpected stop"))
        self.assertEqual(24, len(observed))
        self.assertEqual(24, report["counts"]["SUCCESS"])
        self.assertLessEqual(report["maxOutstandingClientFutures"], 3)
        self.assertEqual(0, report["additionalClientWaitingQueue"])
        self.assertEqual("UNKNOWN_NOT_MEASURED", report["serverConcurrentRequests"])
        for concurrency, work in ((20, items), (3, items[:-1])):
            with self.assertRaises(RuntimeError):
                probe.run_curve(work, request, concurrency, lambda: None)

    def test_fixed_category_protocol_rejects_gameplay_aliases_and_arbitrary_values(self):
        self.assertEqual(('SUCCESS', 'LEGAL_AUTH_REJECTION', 'SYSTEM_FAILURE', 'UNKNOWN',
                          'TRANSPORT_TIMEOUT', 'TRANSPORT_ERROR', 'CONTRACT_MISMATCH', 'NOT_SENT'), probe.CATEGORIES)
        for category in ('accepted', 'authorized-rejection', 'UNKNOWN_OR_CONTRACT_FAILURE',
                         'UNREVIEWED_CATEGORY', '', None):
            with self.subTest(category=category), self.assertRaisesRegex(RuntimeError, 'unknown observation category'):
                probe.distribution([{'category': category, 'elapsedSeconds': .01}], 1)

    def test_final_validation_rejects_partial_foreign_or_missing_evidence(self):
        identity = {"sourceSha": "a" * 40}
        for report in (
            {}, {"result": "PASS", "identity": identity},
            {"result": probe.PASS_OBSERVATION, "identity": {"sourceSha": "b" * 40}},
            {"result": probe.PASS_OBSERVATION, "identity": identity,
             "initial": {"id": 1}, "final": {"id": 2}},
        ):
            with self.subTest(report=report), self.assertRaises(RuntimeError):
                probe.validate_observation(report, identity)

    def test_finalization_cannot_promote_an_original_failure(self):
        # Earlier checks are isolated here only to exercise first-error preservation;
        # this is a pure protocol control, never physical cleanup proof.
        report = {
            "result": probe.PASS_OBSERVATION, "taskName": "owned",
            "schema": "owned-schema", "firstFailure": {"type": "OriginalSchemaFailure"},
        }
        path = mock.Mock()
        path.read_text.return_value = json.dumps(report)
        out = mock.Mock()
        out.__truediv__ = mock.Mock(return_value=path)
        written = []
        with mock.patch.object(probe, "source_identity", return_value={}), \
                mock.patch.object(probe, "validate_observation"), \
                mock.patch.object(probe.auth, "write_report",
                                  side_effect=lambda out, name, value: written.append(copy.deepcopy(value))), \
                mock.patch.object(probe.subprocess, "run") as inspect:
            with self.assertRaises(RuntimeError):
                probe.finalize("owned", "owned-schema", out, 98)
        inspect.assert_not_called()
        self.assertEqual(98, written[0]["originalExitCode"])
        self.assertEqual({"type": "OriginalSchemaFailure"}, written[0]["firstFailure"])
        self.assertEqual("FAIL_PARTIAL", written[0]["result"])


class CapacityEntryCleanupControls(unittest.TestCase):
    def test_actual_exit_trap_preserves_failure_and_waits_for_private_cleanup(self):
        source = Path(__file__).with_name("authenticated-redis-recovery.sh").read_text()
        cleanup = "cleanup() {\n" + source.split("cleanup() {\n", 1)[1].split(
            "\n}\ntrap cleanup EXIT", 1)[0] + "\n}"
        for body, outer, private, finalizer, expected, passed, gameplay in (
            (0, 0, 0, 0, 0, 0, "false"),
            (7, 0, 0, 1, 7, 7, "false"),
            (0, 1, 0, 1, 1, 1, "false"),
            (0, 0, 1, 1, 1, 1, "false"),
            (0, 0, 0, 1, 1, 0, "false"),
            (0, 0, 0, 0, 0, 0, "true"),
            (7, 0, 0, 1, 7, 7, "true"),
            (0, 1, 0, 1, 1, 1, "true"),
            (0, 0, 1, 1, 1, 1, "true"),
            (0, 0, 0, 1, 1, 0, "true"),
        ):
            with self.subTest(body=body, outer=outer, private=private, finalizer=finalizer, gameplay=gameplay):
                # Only the real EXIT function runs; all external effects are shell stubs.
                script = """
set -u
ROOT=/unused-source-fixture
RUN_NAME=owned
SCHEMA=owned_schema
OUT=/unused-evidence
PRIVATE=/unused-private
CHENGYIN_CI_CAPACITY_MIXED=true
CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY=GAMEPLAY_MODE
python3() {
  case "$3" in
    cleanup) printf 'OUTER_CLEANUP\\n'; return OUTER_STATUS ;;
    finalize)
      if [[ "$2" == */gameplay_process_recovery_probe.py ]]; then
        printf 'FINALIZE:%s\\n' "$7"
      else
        printf 'FINALIZE:%s\\n' "$8"
      fi
      return FINALIZER_STATUS ;;
    *) return 99 ;;
  esac
}
rm() { return 0; }
rmdir() { printf 'PRIVATE_CLEANUP\\n'; return PRIVATE_STATUS; }
""".replace("OUTER_STATUS", str(outer)).replace(
                    "PRIVATE_STATUS", str(private)).replace("FINALIZER_STATUS", str(finalizer)).replace(
                    "GAMEPLAY_MODE", gameplay)
                result = probe.subprocess.run(
                    ["bash", "-c", script + cleanup + "\ntrap cleanup EXIT\nexit " + str(body)],
                    capture_output=True, text=True, timeout=5)
                self.assertEqual(expected, result.returncode, result.stderr)
                lines = result.stdout.splitlines()
                self.assertIn("FINALIZE:" + str(passed), lines)
                self.assertEqual(1, sum(line.startswith("FINALIZE:") for line in lines))
                self.assertLess(lines.index("OUTER_CLEANUP"), lines.index("PRIVATE_CLEANUP"))
                self.assertLess(lines.index("PRIVATE_CLEANUP"), lines.index("FINALIZE:" + str(passed)))


if __name__ == "__main__":
    unittest.main()
