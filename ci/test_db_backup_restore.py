import importlib.util
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("db_backup", Path(__file__).resolve().parents[1] / "scripts/db-backup-restore.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
SNAPSHOT = {"tables": {"example": {"rows": 3, "checksum": "17"}}, "views": [],
            "objects": {"ROUTINES": 0, "TRIGGERS": 0, "EVENTS": 0},
            "definitions": {"DATABASE:test_db": "a" * 64, "TABLE:example": "b" * 64}}


class BackupRestoreTest(unittest.TestCase):
    def test_dump_failure_leaves_no_success_artifact_and_never_uses_force(self):
        with tempfile.TemporaryDirectory() as temp:
            args = SimpleNamespace(defaults_file=Path(temp) / "mysql.cnf", database="test_db",
                                   output=Path(temp) / "backup.sql")
            def fail(command, **kwargs):
                self.assertNotIn("--force", command)
                self.assertTrue({"--routines", "--triggers", "--events"}.issubset(command))
                self.assertIn("--default-character-set=utf8mb4", command)
                kwargs["stdout"].write(b"incomplete dump")
                return SimpleNamespace(returncode=1)
            with patch.object(tool, "identify", return_value=["source", "8.0.45", "test_db"]), \
                    patch.object(tool, "snapshot", return_value=SNAPSHOT), \
                    patch.object(tool.subprocess, "run", side_effect=fail):
                with self.assertRaises(ValueError):
                    tool.backup(args)
            self.assertEqual([], list(Path(temp).glob("backup.sql*")))

    def test_restore_identity_hash_empty_target_and_confirmation_guards(self):
        with tempfile.TemporaryDirectory() as temp:
            dump = Path(temp) / "backup.sql"
            dump.write_text("fixture SQL")
            Path(str(dump) + ".json").write_text(json.dumps({"formatVersion": 2, "serverUuid": "source", "mysqlVersion": "8.0.45",
                                                          "database": "test_db", "sha256": tool.digest(dump),
                                                          "snapshot": SNAPSHOT}))
            args = SimpleNamespace(defaults_file=Path(temp) / "mysql.cnf", database="test_db", backup=dump,
                                   confirm_test_target=True)
            for uuid, version, confirmed, changed, populated in (
                    ("source", "8.0.45", True, False, False),
                    ("target", "8.0.46", True, False, False),
                    ("target", "8.0.45", False, False, False),
                    ("target", "8.0.45", True, True, False),
                    ("target", "8.0.45", True, False, True)):
                args.confirm_test_target = confirmed
                dump.write_text("changed SQL" if changed else "fixture SQL")
                empty = {"tables": {}, "views": [], "objects": {"EVENTS": 0},
                         "definitions": {"DATABASE:test_db": "a" * 64}}
                with self.subTest(uuid=uuid, version=version, confirmed=confirmed, changed=changed,
                                  populated=populated), \
                        patch.object(tool, "identify", return_value=[uuid, version, "test_db"]), \
                        patch.object(tool, "mysql", return_value="OFF"), \
                        patch.object(tool, "snapshot", return_value=SNAPSHOT if populated else empty), \
                        patch.object(tool.subprocess, "run") as run:
                    with self.assertRaises(ValueError):
                        tool.restore(args)
                    run.assert_not_called()

    def test_legacy_receipt_is_rejected_before_target_access_or_restore_write(self):
        with tempfile.TemporaryDirectory() as temp:
            dump = Path(temp) / "backup.sql"
            dump.write_text("fixture SQL")
            manifest = {"serverUuid": "source", "mysqlVersion": "8.0.45", "database": "test_db",
                        "sha256": tool.digest(dump), "snapshot": SNAPSHOT}
            args = SimpleNamespace(database="test_db", backup=dump, confirm_test_target=True)
            for changes in ({}, {"formatVersion": 2, "snapshot": {key: value for key, value in SNAPSHOT.items()
                                                                 if key != "definitions"}}):
                with self.subTest(fields=tuple(changes)):
                    Path(str(dump) + ".json").write_text(json.dumps({**manifest, **changes}))
                    with patch.object(tool, "identify") as identify, patch.object(tool, "snapshot") as snapshot, \
                            patch.object(tool.subprocess, "run") as run:
                        with self.assertRaisesRegex(ValueError, "definition|manifest"):
                            tool.restore(args)
                        identify.assert_not_called()
                        snapshot.assert_not_called()
                        run.assert_not_called()


# MySQL batch-mode SHOW CREATE fields, including the one non-definition timestamp.
DDL = {
    ("DATABASE", "test_db"): {"Database": "test_db", "Create Database": "CREATE DATABASE `test_db` DEFAULT CHARACTER SET utf8mb4"},
    ("TABLE", "example"): {"Table": "example", "Create Table": "CREATE TABLE `example` (`id` int NOT NULL AUTO_INCREMENT, `value` int, UNIQUE KEY `uq` (`id`), CONSTRAINT `positive` CHECK (`value` > 0), CONSTRAINT `fk` FOREIGN KEY (`value`) REFERENCES `example` (`id`) ON DELETE RESTRICT) ENGINE=InnoDB AUTO_INCREMENT=9"},
    ("VIEW", "sample_view"): {"View": "sample_view", "Create View": "CREATE VIEW `sample_view` AS select `id` from `example` where `value` > 0", "character_set_client": "utf8mb4", "collation_connection": "utf8mb4_0900_ai_ci"},
    ("PROCEDURE", "sample_proc"): {"Procedure": "sample_proc", "sql_mode": "STRICT_TRANS_TABLES", "Create Procedure": "CREATE PROCEDURE `sample_proc`() SQL SECURITY DEFINER SELECT '恢复,\\t\\n,test_db' /*test_db*/;", "character_set_client": "utf8mb4"},
    ("FUNCTION", "sample_function"): {"Function": "sample_function", "sql_mode": "STRICT_TRANS_TABLES", "Create Function": "CREATE FUNCTION `sample_function`() RETURNS int DETERMINISTIC RETURN 1"},
    ("TRIGGER", "sample_added"): {"Trigger": "sample_added", "sql_mode": "STRICT_TRANS_TABLES", "SQL Original Statement": "CREATE TRIGGER `sample_added` BEFORE INSERT ON `example` FOR EACH ROW SET NEW.value = 1", "Created": "2026-10-15 10:00:00.00"},
    ("EVENT", "sample_disabled"): {"Event": "sample_disabled", "sql_mode": "STRICT_TRANS_TABLES", "time_zone": "SYSTEM", "Create Event": "CREATE EVENT `sample_disabled` ON SCHEDULE EVERY 1 DAY STARTS '2026-10-15 10:00:00' DISABLE DO SELECT 1"},
}


def fixture_mysql(definitions):
    def query(args, sql, definition=False):
        if sql == "SHOW FULL TABLES":
            return "example\tBASE TABLE\nsample_view\tVIEW"
        if sql.startswith("CHECKSUM TABLE"):
            return "test_db.example\t17"
        if sql.startswith("SELECT COUNT(*)"):
            return "2" if "information_schema.ROUTINES" in sql else "1" if "information_schema." in sql else "3"
        if "JSON_ARRAY(TABLE_NAME, TABLE_TYPE)" in sql:
            rows = [["example", "BASE TABLE"], ["sample_view", "VIEW"]]
        elif "JSON_ARRAY(ROUTINE_NAME, ROUTINE_TYPE)" in sql:
            rows = [[name, kind] for kind, name in definitions if kind in ("PROCEDURE", "FUNCTION")]
        elif "JSON_ARRAY(TRIGGER_NAME)" in sql:
            rows = [[name] for kind, name in definitions if kind == "TRIGGER"]
        elif "JSON_ARRAY(EVENT_NAME)" in sql:
            rows = [[name] for kind, name in definitions if kind == "EVENT"]
        elif sql.startswith("SHOW CREATE "):
            _, _, kind, quoted = sql.split(" ", 3)
            fields = definitions[kind, quoted[1:-1].replace("``", "`")]
            escape = lambda value: value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
            return "\t".join(fields) + "\n" + "\t".join(escape(value) for value in fields.values())
        else:
            raise AssertionError("Unexpected fixture query")
        return "\n".join(json.dumps(row, ensure_ascii=False) for row in rows)
    return query


class DefinitionSnapshotTest(unittest.TestCase):
    def snapshot(self, definitions):
        with patch.object(tool, "mysql", side_effect=fixture_mysql(definitions)):
            return tool.snapshot(SimpleNamespace(database="test_db"))

    def test_same_names_counts_and_data_do_not_hide_changed_definitions(self):
        before = self.snapshot(DDL)
        mutations = (
            (("TABLE", "example"), "Create Table", "UNIQUE KEY `uq` (`id`), ", ""),
            (("TABLE", "example"), "Create Table", "CHECK (`value` > 0)", "CHECK (`value` >= 0)"),
            (("TABLE", "example"), "Create Table", "AUTO_INCREMENT=9", "AUTO_INCREMENT=10"),
            (("TABLE", "example"), "Create Table", "ON DELETE RESTRICT", "ON DELETE CASCADE"),
            (("VIEW", "sample_view"), "Create View", "`value` > 0", "`value` >= 0"),
            (("PROCEDURE", "sample_proc"), "Create Procedure", "恢复", "different"),
            (("PROCEDURE", "sample_proc"), "Create Procedure", "SQL SECURITY DEFINER", "SQL SECURITY INVOKER"),
            (("PROCEDURE", "sample_proc"), "Create Procedure", "test_db", "different_name"),
            (("PROCEDURE", "sample_proc"), "Create Procedure", "\\n", "\n"),
            (("PROCEDURE", "sample_proc"), "character_set_client", "utf8mb4", "latin1"),
            (("FUNCTION", "sample_function"), "Create Function", "RETURN 1", "RETURN 2"),
            (("TRIGGER", "sample_added"), "SQL Original Statement", "value = 1", "value = 2"),
            (("TRIGGER", "sample_added"), "sql_mode", "STRICT_TRANS_TABLES", ""),
            (("EVENT", "sample_disabled"), "Create Event", "DISABLE", "ENABLE"),
            (("EVENT", "sample_disabled"), "Create Event", "EVERY 1 DAY", "EVERY 2 DAY"),
            (("EVENT", "sample_disabled"), "time_zone", "SYSTEM", "+00:00"),
        )
        for key, field, old, new in mutations:
            with self.subTest(kind=key[0], field=field):
                changed = copy.deepcopy(DDL)
                changed[key][field] = changed[key][field].replace(old, new)
                after = self.snapshot(changed)
                self.assertEqual({k: before[k] for k in ("tables", "views", "objects")},
                                 {k: after[k] for k in ("tables", "views", "objects")})
                self.assertNotEqual(before, after)

    def test_only_trigger_creation_timestamp_is_ignored(self):
        changed = copy.deepcopy(DDL)
        changed["TRIGGER", "sample_added"]["Created"] = "2026-10-16 10:00:00.00"
        self.assertEqual(self.snapshot(DDL), self.snapshot(changed))

    def test_snapshot_contains_every_object_identity_and_no_definition_text(self):
        snapshot = self.snapshot(DDL)
        self.assertEqual({kind + ":" + name for kind, name in DDL}, set(snapshot["definitions"]))
        self.assertTrue(all(len(value) == 64 for value in snapshot["definitions"].values()))
        self.assertNotIn("CREATE PROCEDURE", json.dumps(snapshot))

    def test_definition_missing_or_permission_hidden_is_rejected(self):
        for value in ("", "NULL"):
            with self.subTest(value=value):
                changed = copy.deepcopy(DDL)
                changed["PROCEDURE", "sample_proc"]["Create Procedure"] = value
                with self.assertRaises(ValueError):
                    self.snapshot(changed)

    def test_batch_decodes_exactly_once_and_field_order_does_not_change_hash(self):
        value = "恢复\\n literal / actual\nline\tcell\\path"
        escaped = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        self.assertEqual(value, tool.batch_cell(escaped))
        reordered = {key: dict(reversed(list(fields.items()))) for key, fields in DDL.items()}
        self.assertEqual(self.snapshot(DDL), self.snapshot(reordered))

    def test_restore_rejects_missing_object_proof_or_database_options_before_import(self):
        source = self.snapshot(DDL)
        with tempfile.TemporaryDirectory() as temp:
            dump = Path(temp) / "backup.sql"
            dump.write_text("fixture SQL")
            manifest = {"formatVersion": 2, "serverUuid": "source", "mysqlVersion": "8.0.45",
                        "database": "test_db", "sha256": tool.digest(dump), "snapshot": source}
            args = SimpleNamespace(database="test_db", backup=dump, confirm_test_target=True)
            for omitted in source["definitions"]:
                changed = copy.deepcopy(manifest)
                del changed["snapshot"]["definitions"][omitted]
                Path(str(dump) + ".json").write_text(json.dumps(changed))
                with self.subTest(omitted=omitted), patch.object(tool, "identify") as identify, \
                        patch.object(tool.subprocess, "run") as run, self.assertRaisesRegex(ValueError, "definition"):
                    tool.restore(args)
                identify.assert_not_called()
                run.assert_not_called()
            Path(str(dump) + ".json").write_text(json.dumps(manifest))
            empty = {"tables": {}, "views": [], "objects": {"ROUTINES": 0, "TRIGGERS": 0, "EVENTS": 0},
                     "definitions": {"DATABASE:test_db": "f" * 64}}
            with patch.object(tool, "identify", return_value=["target", "8.0.45", "test_db"]), \
                    patch.object(tool, "mysql", return_value="OFF"), patch.object(tool, "snapshot", return_value=empty), \
                    patch.object(tool.subprocess, "run") as run, self.assertRaisesRegex(ValueError, "database definition"):
                tool.restore(args)
            run.assert_not_called()

    def test_restore_requires_definition_equality_even_when_import_reports_success(self):
        source = self.snapshot(DDL)
        empty = {"tables": {}, "views": [], "objects": {"ROUTINES": 0, "TRIGGERS": 0, "EVENTS": 0},
                 "definitions": {"DATABASE:test_db": source["definitions"]["DATABASE:test_db"]}}
        with tempfile.TemporaryDirectory() as temp:
            dump = Path(temp) / "backup.sql"
            dump.write_text("fixture SQL")
            Path(str(dump) + ".json").write_text(json.dumps({"formatVersion": 2, "serverUuid": "source",
                "mysqlVersion": "8.0.45", "database": "test_db", "sha256": tool.digest(dump), "snapshot": source}))
            args = SimpleNamespace(defaults_file=Path(temp) / "private.cnf", database="test_db", backup=dump,
                                   confirm_test_target=True)
            for differs in (False, True):
                restored = copy.deepcopy(source)
                if differs:
                    restored["definitions"]["TABLE:example"] = "c" * 64
                with self.subTest(differs=differs), patch.object(tool, "identify", return_value=["target", "8.0.45", "test_db"]), \
                        patch.object(tool, "mysql", return_value="OFF"), patch.object(tool, "snapshot", side_effect=[empty, restored]), \
                        patch.object(tool.subprocess, "run", return_value=SimpleNamespace(returncode=0)) as run, \
                        patch("builtins.print") as printer:
                    if differs:
                        with self.assertRaisesRegex(ValueError, "failed verification"):
                            tool.restore(args)
                        printer.assert_not_called()
                    else:
                        tool.restore(args)
                    self.assertIn("--default-character-set=utf8mb4", run.call_args.args[0])
                self.assertEqual("fixture SQL", dump.read_text())

    def test_definition_client_format_is_explicit_utf8_escaped_and_headered(self):
        args = SimpleNamespace(defaults_file=Path("private.cnf"), database="test_db")
        with patch.object(tool.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=b"fields\nvalues\n")) as run:
            self.assertEqual("fields\nvalues", tool.mysql(args, "SHOW CREATE TABLE `example`", definition=True))
        command = run.call_args.args[0]
        self.assertIn("--default-character-set=utf8mb4", command)
        self.assertIn("--skip-raw", command)
        self.assertIn("--column-names", command)
        self.assertNotIn("--raw", command)


if __name__ == "__main__":
    unittest.main()
