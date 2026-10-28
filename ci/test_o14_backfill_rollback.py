"""Standard-library tests for manifest boundaries and complete transaction rollback."""
import contextlib
import copy
import datetime
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ci import o14_backfill_rollback as tool

UUID = "11111111-2222-3333-4444-555555555555"
NOW = "2026-10-22 12:00:00"


def fixture():
    return {"format_version": 1, "database": "o14_scratch", "server_uuid": UUID,
            "expected_count": 2, "before_snapshot_sha256": "0" * 64,
            "rows": [{"target_table": "cms_topic", "row_id": 11, "city_code": "310100",
                      "source": "o14_v2:club_city", "write_time": NOW},
                     {"target_table": "cms_activity", "row_id": 12, "city_code": "310100",
                      "source": "o14_v2:parent_topic", "write_time": NOW}]}


class FakeCursor:
    def __init__(self, connection):
        self.connection = connection
        self.result = []
        self.rowcount = 0

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def execute(self, sql, params=()):
        c = self.connection
        c.executed.append((sql, params))
        self.rowcount = 0
        if sql.startswith("SET TRANSACTION"):
            self.result = []
        elif sql.startswith("SELECT DATABASE()"):
            self.result = [(c.database, UUID, "8.0.45", "MySQL Community Server - GPL")]
        elif "information_schema.tables" in sql:
            self.result = [(name, c.engine, "BASE TABLE") for name in tool.TABLES + (tool.LOG,)]
        elif "information_schema.statistics" in sql:
            self.result = [(name, "id", 1) for name in tool.TABLES] + [
                (tool.LOG, "target_table", 1), (tool.LOG, "row_id", 2)]
        elif "information_schema.columns" in sql:
            self.result = [(name,) for name in tool.TABLES]
        elif "privilege_type='SELECT'" in sql:
            self.result = [(int(c.metadata_visibility),)]
        elif "information_schema.user_privileges" in sql:
            self.result = [("*",)] if c.trigger_visibility else []
        elif "information_schema.triggers" in sql:
            self.result = [(c.triggers,)]
        elif "information_schema.key_column_usage" in sql:
            self.result = [(0,)]
        elif sql.startswith("SELECT city_code,source,write_time"):
            self.result = [c.logs[params]] if params in c.logs else []
        elif sql.startswith("SELECT city_code FROM"):
            key = (sql.split("`")[1], params[0])
            self.result = [(c.business[key],)] if key in c.business else []
        elif sql.startswith("UPDATE"):
            c.updates += 1
            if c.fail_update == c.updates:
                raise RuntimeError("injected transaction failure")
            key = (sql.split("`")[1], params[0])
            if c.business.get(key) == params[1]:
                c.business[key] = None
                self.rowcount = 1
            if c.mutate_log:
                value, source, timestamp = c.logs[key]
                c.logs[key] = (value, source, timestamp + datetime.timedelta(seconds=1))
        else:
            raise AssertionError("unexpected SQL: " + sql)

    def fetchone(self):
        return self.result[0] if self.result else None

    def fetchall(self):
        return self.result


class FakeConnection:
    def __init__(self, manifest):
        self.database = manifest["database"]
        self.business = {(r["target_table"], r["row_id"]): r["city_code"] for r in manifest["rows"]}
        self.logs = {(r["target_table"], r["row_id"]): (
            r["city_code"], r["source"], datetime.datetime.strptime(r["write_time"], "%Y-%m-%d %H:%M:%S"))
                     for r in manifest["rows"]}
        self.executed = []
        self.engine = "InnoDB"
        self.triggers = 0
        self.trigger_visibility = True
        self.metadata_visibility = True
        self.fail_update = None
        self.mutate_log = False
        self.updates = self.commits = self.rollbacks = 0
        self.original = None

    def cursor(self):
        return FakeCursor(self)

    def begin(self):
        self.original = copy.deepcopy((self.business, self.logs))

    def commit(self):
        self.commits += 1
        self.original = None

    def rollback(self):
        self.rollbacks += 1
        if self.original is not None:
            self.business, self.logs = self.original
            self.original = None


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "manifest.json"
        self.before_path = Path(self.directory.name) / "before.json"
        self.manifest = fixture()
        self.before = {"format_version": 1, "database": "o14_scratch", "server_uuid": UUID,
                       "captured_at": "2026-10-21T12:00:00Z", "rows": [
                           {"target_table": r["target_table"], "row_id": r["row_id"], "city_code": None}
                           for r in self.manifest["rows"]]}

    def write(self):
        self.before_path.write_text(json.dumps(self.before), encoding="utf-8")
        self.manifest["before_snapshot_sha256"] = hashlib.sha256(self.before_path.read_bytes()).hexdigest()
        self.path.write_text(json.dumps(self.manifest), encoding="utf-8")
        return hashlib.sha256(self.path.read_bytes()).hexdigest()

    def load(self, database="o14_scratch", count=2):
        return tool.load_manifest(self.path, self.write(), database, count, self.before_path)

    def test_valid_before_image_is_bound_to_manifest(self):
        self.assertEqual(self.manifest, self.load())

    def test_tampered_manifest_is_rejected(self):
        digest = self.write()
        self.path.write_bytes(self.path.read_bytes() + b" ")
        with self.assertRaisesRegex(tool.Refusal, "SHA256 mismatch"):
            tool.load_manifest(self.path, digest, "o14_scratch", 2, self.before_path)

    def test_tampered_before_image_is_rejected(self):
        digest = self.write()
        self.before_path.write_bytes(self.before_path.read_bytes() + b" ")
        with self.assertRaisesRegex(tool.Refusal, "SHA256 mismatch"):
            tool.load_manifest(self.path, digest, "o14_scratch", 2, self.before_path)

    def test_missing_before_evidence_is_rejected(self):
        digest = self.write()
        del self.manifest["before_snapshot_sha256"]
        self.path.write_text(json.dumps(self.manifest))
        digest = hashlib.sha256(self.path.read_bytes()).hexdigest()
        with self.assertRaisesRegex(tool.Refusal, "manifest fields"):
            tool.load_manifest(self.path, digest, "o14_scratch", 2, self.before_path)

    def test_wrong_database_or_expected_count_is_rejected(self):
        for database, count in (("other_database", 2), ("o14_scratch", 1), ("o14_scratch", 0)):
            with self.subTest(database=database, count=count), self.assertRaises(tool.Refusal):
                self.load(database, count)

    def test_unapproved_target_or_duplicate_target_is_rejected(self):
        for change in (lambda m: m["rows"][0].update(target_table="sys_job"),
                       lambda m: m["rows"][0].update(target_table="cms_topic` SET id=0 --"),
                       lambda m: m["rows"].__setitem__(1, copy.deepcopy(m["rows"][0])),
                       lambda m: m["rows"][0].update(row_id=True),
                       lambda m: m["rows"][0].update(source="o14_v1:address")):
            self.manifest = fixture()
            change(self.manifest)
            with self.subTest(manifest=self.manifest), self.assertRaises(tool.Refusal):
                self.load()

    def test_before_image_must_be_null_and_match_exact_targets(self):
        for change in (lambda b: b["rows"][0].update(city_code="320100"),
                       lambda b: b["rows"][0].update(row_id=99),
                       lambda b: b.update(server_uuid="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                       lambda b: b["rows"].pop()):
            original = copy.deepcopy(self.before)
            change(self.before)
            with self.subTest(before=self.before), self.assertRaises(tool.Refusal):
                self.load()
            self.before = original

    def test_duplicate_json_keys_are_rejected_even_with_valid_hash(self):
        self.write()
        self.path.write_text('{"database":"one","database":"two"}')
        digest = hashlib.sha256(self.path.read_bytes()).hexdigest()
        with self.assertRaisesRegex(tool.Refusal, "duplicate JSON key"):
            tool.load_manifest(self.path, digest, "o14_scratch", 2, self.before_path)

    def test_connection_requires_explicit_environment_without_defaults(self):
        with patch.dict(tool.os.environ, {}, clear=True):
            with self.assertRaisesRegex(tool.Refusal, "explicit O14_MYSQL"):
                tool.connect_from_env("o14_scratch")
        environment = {"O14_MYSQL_HOST": "private-host", "O14_MYSQL_PORT": "3306",
                       "O14_MYSQL_DATABASE": "other_database", "O14_MYSQL_USER": "user",
                       "O14_MYSQL_PASSWORD": "secret"}
        with patch.dict(tool.os.environ, environment, clear=True):
            with self.assertRaisesRegex(tool.Refusal, "environment database mismatch"):
                tool.connect_from_env("o14_scratch")

    def test_cli_defaults_to_preview_and_suppresses_driver_secrets(self):
        digest = self.write()
        args = ["--manifest", str(self.path), "--sha256", digest, "--before-snapshot",
                str(self.before_path), "--database", "o14_scratch", "--expected-count", "2"]
        with patch.object(tool, "connect_from_env", side_effect=RuntimeError("private-host password=secret")), \
                contextlib.redirect_stderr(io.StringIO()) as output:
            self.assertEqual(1, tool.main(args))
        self.assertNotIn("private-host", output.getvalue())
        self.assertNotIn("secret", output.getvalue())
        connection = FakeConnection(self.manifest)
        connection.close = lambda: None
        with patch.object(tool, "connect_from_env", return_value=connection), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, tool.main(args))
        self.assertEqual("preview", json.loads(output.getvalue())["status"])
        self.assertEqual(0, connection.updates)


class TransactionTest(unittest.TestCase):
    def setUp(self):
        self.manifest = fixture()
        self.connection = FakeConnection(self.manifest)

    def test_preview_does_not_issue_any_dml(self):
        before = copy.deepcopy(self.connection.business)
        result = tool.rollback_batch(self.connection, self.manifest)
        self.assertEqual("preview", result["status"])
        self.assertEqual(before, self.connection.business)
        self.assertEqual((0, 0, 1), (self.connection.updates, self.connection.commits, self.connection.rollbacks))

    def test_apply_clears_only_targets_preserves_logs_and_rejects_repeat(self):
        logs = copy.deepcopy(self.connection.logs)
        self.connection.business[("cms_topic", 999)] = "440100"
        result = tool.rollback_batch(self.connection, self.manifest, apply=True)
        self.assertEqual("applied", result["status"])
        self.assertEqual(2, result["row_count"])
        for row in self.manifest["rows"]:
            self.assertIsNone(self.connection.business[(row["target_table"], row["row_id"])])
        self.assertEqual("440100", self.connection.business[("cms_topic", 999)])
        self.assertEqual(logs, self.connection.logs)
        with self.assertRaisesRegex(tool.Refusal, "already withdrawn"):
            tool.rollback_batch(self.connection, self.manifest, apply=True)
        self.assertEqual(1, self.connection.commits)

    def test_all_rows_are_locked_before_first_write(self):
        tool.rollback_batch(self.connection, self.manifest, apply=True)
        statements = [sql for sql, _ in self.connection.executed]
        first_update = next(i for i, sql in enumerate(statements) if sql.startswith("UPDATE"))
        locks = [sql for sql in statements[:first_update] if "FOR UPDATE" in sql]
        self.assertEqual(4, len(locks))
        self.assertTrue(all("BINARY city_code=BINARY %s" in sql
                            for sql in statements if sql.startswith("UPDATE")))

    def test_manual_value_or_log_drift_aborts_entire_batch_without_writes(self):
        for drift in ("manual", "time", "source", "missing_log", "missing_row"):
            c = FakeConnection(self.manifest)
            key = ("cms_topic", 11)  # Last in lock order, so an earlier row has already been verified.
            if drift == "manual":
                c.business[key] = "440100"
            elif drift == "missing_log":
                del c.logs[key]
            elif drift == "missing_row":
                del c.business[key]
            else:
                value, source, timestamp = c.logs[key]
                c.logs[key] = (value, "changed" if drift == "source" else source,
                               timestamp + datetime.timedelta(seconds=drift == "time"))
            before = copy.deepcopy((c.business, c.logs))
            with self.subTest(drift=drift), self.assertRaises(tool.Refusal):
                tool.rollback_batch(c, self.manifest, apply=True)
            self.assertEqual(before, (c.business, c.logs))
            self.assertEqual((0, 0, 1), (c.updates, c.commits, c.rollbacks))

    def test_second_update_failure_rolls_back_first_update(self):
        before = copy.deepcopy((self.connection.business, self.connection.logs))
        self.connection.fail_update = 2
        with self.assertRaisesRegex(RuntimeError, "injected"):
            tool.rollback_batch(self.connection, self.manifest, apply=True)
        self.assertEqual(before, (self.connection.business, self.connection.logs))
        self.assertEqual((0, 1), (self.connection.commits, self.connection.rollbacks))

    def test_post_update_log_change_rolls_back_complete_batch(self):
        before = copy.deepcopy((self.connection.business, self.connection.logs))
        self.connection.mutate_log = True
        with self.assertRaisesRegex(tool.Refusal, "ownership"):
            tool.rollback_batch(self.connection, self.manifest, apply=True)
        self.assertEqual(before, (self.connection.business, self.connection.logs))

    def test_wrong_server_nontransactional_table_or_trigger_refuses_writes(self):
        for field, value in (("database", "other_database"), ("engine", "MyISAM"),
                             ("triggers", 1), ("trigger_visibility", False),
                             ("metadata_visibility", False)):
            c = FakeConnection(self.manifest)
            setattr(c, field, value)
            with self.subTest(field=field), self.assertRaises(tool.Refusal):
                tool.rollback_batch(c, self.manifest, apply=True)
            self.assertEqual(0, c.updates)
            self.assertEqual(0, c.commits)


if __name__ == "__main__":
    unittest.main()
