import importlib.util
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
            "objects": {"ROUTINES": 0, "TRIGGERS": 0, "EVENTS": 0}}


class BackupRestoreTest(unittest.TestCase):
    def test_dump_failure_leaves_no_success_artifact_and_never_uses_force(self):
        with tempfile.TemporaryDirectory() as temp:
            args = SimpleNamespace(defaults_file=Path(temp) / "mysql.cnf", database="test_db",
                                   output=Path(temp) / "backup.sql")
            def fail(command, **kwargs):
                self.assertNotIn("--force", command)
                self.assertTrue({"--routines", "--triggers", "--events"}.issubset(command))
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
            Path(str(dump) + ".json").write_text(json.dumps({"serverUuid": "source", "mysqlVersion": "8.0.45",
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
                empty = {"tables": {}, "views": [], "objects": {"EVENTS": 0}}
                with self.subTest(uuid=uuid, version=version, confirmed=confirmed, changed=changed,
                                  populated=populated), \
                        patch.object(tool, "identify", return_value=[uuid, version, "test_db"]), \
                        patch.object(tool, "mysql", return_value="OFF"), \
                        patch.object(tool, "snapshot", return_value=SNAPSHOT if populated else empty), \
                        patch.object(tool.subprocess, "run") as run:
                    with self.assertRaises(ValueError):
                        tool.restore(args)
                    run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
