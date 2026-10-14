import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

PATH = Path(__file__).resolve().parents[1] / "scripts/audit-job-registration.py"
SPEC = importlib.util.spec_from_file_location("monitor_registration", PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class RegistrationReadbackTest(unittest.TestCase):
    def test_readback_set_matches_startup_self_check(self):
        import re
        java = (Path(__file__).resolve().parents[1] / "chengyinhub-admin/src/main/java/com/chengyinhub/web/task/"
                "MonitorJobRegistrationCheck.java").read_text(encoding="utf-8")
        block = java.split("REQUIRED_MONITOR_TARGETS", 1)[1].split(";", 1)[0]
        self.assertEqual(set(MODULE.REQUIRED), set(re.findall(r'"([A-Za-z]+\.[a-z]+\(\))"', block)))

    def rows(self, changes=None, duplicate=False):
        changes = changes or {}
        rows = []
        for name, cron in MODULE.REQUIRED.items():
            current = changes.get(name, (cron, "0", "1", "3"))
            if current is None:
                continue
            row = "\t".join((name.encode().hex(), current[0].encode().hex(), *current[1:]))
            rows.append(row)
            if duplicate and name == next(iter(MODULE.REQUIRED)):
                rows.append(row)
        return "\n".join(rows)

    def check(self, raw, confirmed=True):
        args = SimpleNamespace(confirm_test_target=confirmed)
        with patch.object(MODULE.CLIENT, "identify") as identify, \
                patch.object(MODULE.CLIENT, "mysql", return_value=raw) as mysql:
            report = MODULE.inspect_registration(args)
            identify.assert_called_once_with(args)
            query = mysql.call_args.args[1]
            self.assertTrue(query.startswith("SELECT "))
            self.assertNotIn("UPDATE", query)
            self.assertNotIn("INSERT", query)
            return report

    def test_registered_active_nonconcurrent_jobs_are_ready(self):
        self.assertTrue(self.check(self.rows())["ready"])

    def test_missing_paused_duplicate_and_changed_policy_are_not_a_green_registration(self):
        name = next(iter(MODULE.REQUIRED))
        for changes, duplicate, expected in [
            ({name: None}, False, "missing"),
            ({name: (MODULE.REQUIRED[name], "1", "1", "3")}, False, "paused-or-invalid-status"),
            ({}, True, "duplicate"),
            ({name: ("0 0 * * * ?", "0", "0", "1")}, False, "cron-differs"),
        ]:
            with self.subTest(expected=expected):
                report = self.check(self.rows(changes, duplicate))
                self.assertFalse(report["ready"])
                self.assertIn(expected, report["jobs"][0]["problems"])

    def test_unconfirmed_or_wrong_identity_is_rejected_before_job_read(self):
        with patch.object(MODULE.CLIENT, "identify") as identify, \
                patch.object(MODULE.CLIENT, "mysql") as mysql:
            with self.assertRaises(ValueError):
                MODULE.inspect_registration(SimpleNamespace(confirm_test_target=False))
            identify.assert_not_called()
            mysql.assert_not_called()
            identify.side_effect = ValueError("different test target")
            with self.assertRaises(ValueError):
                MODULE.inspect_registration(SimpleNamespace(confirm_test_target=True))
            mysql.assert_not_called()


if __name__ == "__main__":
    unittest.main()
