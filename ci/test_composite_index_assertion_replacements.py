"""Offline SQL predicate/preservation controls; these are not MySQL apply receipts."""
import copy
import hashlib
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import unittest
from unittest import mock

from scripts.e2e import build_schema

from ci.migration_gates import check_schema_manifest_order_source
from ci.sql_splitter import executable_statement, split_statements


ROOT = Path(__file__).resolve().parents[1]
SOURCE = "d3803b0221f0d61c03d1e3000536ebdf57ae4d9e"
CASES = (
    ("public_content_catalog_20261108", "public_content_catalog_index_shape_20261109",
     "2d238c2e96ab388ac7494389562ccf5037616fa452e20a3220076c228c71a9b6",
     "82816800400ec24bf6fdfb353393f7671685daf8c32a370e94afa0a93d1ae05e",
     "content_landing_translation", "uk_content_landing_translation_topic_locale", ("topic_id", "locale")),
    ("z_marketing_revenue_channel_reverse_kind_20261108",
     "z_marketing_revenue_channel_reverse_kind_index_shape_20261109",
     "99dab1cc3390cfc2f9139fbd901631570abb616df67776409ab31adec0a5930f",
     "51a6741ef64cf2645c93fb6f5b08cbb3f9ca8a2fafd748f350de19453d00fe07",
     "marketing_revenue_posting_task", "uk_revenue_posting_ref", ("task_kind", "ref_id")),
)
NEW_PREDICATE = re.compile(r"\(SELECT COUNT\(\*\) = 2\s+AND SUM\(.*?AND index_name='[^']+'\) = 1", re.S)
OLD_PREDICATE = re.compile(r"\(SELECT COUNT\(\*\) FROM information_schema.statistics\s+WHERE.*?AND non_unique=0\)\s*=\s*1", re.S)


def sql_path(migration):
    return ROOT / "chengyinhub-admin/sql" / ("migration_" + migration + ".sql")


class CompositeIndexReplacementContract(unittest.TestCase):
    def test_original_bytes_are_immutable_and_both_replacements_are_pinned(self):
        for old, new, old_hash, new_hash, *_ in CASES:
            with self.subTest(migration=old):
                original = sql_path(old).read_bytes()
                self.assertEqual(old_hash, hashlib.sha256(original).hexdigest())
                self.assertEqual(original, subprocess.check_output(
                    ["git", "show", SOURCE + ":" + sql_path(old).relative_to(ROOT).as_posix()], cwd=ROOT))
                self.assertEqual(new_hash, hashlib.sha256(sql_path(new).read_bytes()).hexdigest())

    def test_only_the_broken_predicate_changes_all_ddl_and_other_assertions_survive(self):
        from ci.run_migration import statement_assertion
        for old, new, *_ in CASES:
            with self.subTest(migration=old):
                before = split_statements(sql_path(old).read_text())
                after = split_statements(sql_path(new).read_text())
                self.assertEqual(len(before), len(after))
                self.assertEqual(list(map(statement_assertion, before)), list(map(statement_assertion, after)))
                changes = 0
                for original, replacement in zip(before, after):
                    original, replacement = map(executable_statement, (original, replacement))
                    if original == replacement:
                        continue
                    changes += 1
                    exact = NEW_PREDICATE.search(replacement)
                    self.assertIsNotNone(exact)
                    # Catalog also contains the valid single-column index assertion in another statement.
                    self.assertEqual(1, len(OLD_PREDICATE.findall(original)))
                    self.assertEqual(OLD_PREDICATE.sub("EXACT_INDEX", original),
                                     NEW_PREDICATE.sub("EXACT_INDEX", replacement))
                self.assertEqual(1, changes)

    def test_exact_sql_predicates_reject_missing_wrong_reordered_nonunique_and_prefix_indexes(self):
        # Evaluate the unchanged SQL predicate with synthetic statistics rows in memory only.
        # Oracle MySQL execution, DDL, and information_schema provenance remain the owner's next gate.
        with sqlite3.connect(":memory:") as connection:
            connection.execute("ATTACH DATABASE ':memory:' AS information_schema")
            connection.create_function("DATABASE", 0, lambda: "owned_scratch")
            connection.execute("CREATE TABLE information_schema.statistics (table_schema TEXT, table_name TEXT, "
                               "index_name TEXT, non_unique INT, seq_in_index INT, column_name TEXT, sub_part INT)")
            for _, new, _, _, table, index, columns in CASES:
                predicate = NEW_PREDICATE.search(sql_path(new).read_text()).group()
                valid = [("owned_scratch", table, index, 0, i, column, None)
                         for i, column in enumerate(columns, 1)]
                scenarios = {
                    "exact": (valid, 1), "missing": ([], 0), "one-column": (valid[:1], 0),
                    "wrong": ([valid[0], (*valid[1][:5], "wrong", None)], 0),
                    "reordered": ([(*row[:5], columns[2-row[4]], None) for row in valid], 0),
                    "nonunique": ([(*row[:3], 1, *row[4:]) for row in valid], 0),
                    "prefix": ([(*row[:6], 4) for row in valid], 0),
                    "extra": (valid + [("owned_scratch", table, index, 0, 3, "extra", None)], 0),
                    "wrong-index": ([(*row[:2], "wrong", *row[3:]) for row in valid], 0),
                    "foreign-schema": ([("foreign", *row[1:]) for row in valid], 0),
                }
                for name, (rows, expected) in scenarios.items():
                    with self.subTest(migration=new, case=name):
                        connection.execute("DELETE FROM information_schema.statistics")
                        connection.executemany("INSERT INTO information_schema.statistics VALUES (?,?,?,?,?,?,?)", rows)
                        self.assertEqual((expected,), connection.execute("SELECT " + predicate).fetchone())

    def test_protected_runner_rejects_an_additional_unreviewed_replacement(self):
        source = (ROOT / "scripts/e2e/build_schema.py").read_text()
        self.assertEqual([], check_schema_manifest_order_source(source))
        anchor = '    selected = broadcast_forward["selected"] if broadcast_forward else selected\n'
        self.assertEqual(1, source.count(anchor))
        for old, _, *_ in CASES:
            changed = source.replace(anchor, anchor +
                                     '    selected = [m for m in selected if m["migrationId"] != ' + repr(old) + ']\n')
            self.assertTrue(check_schema_manifest_order_source(changed))


class FixedIndexForwardSelection(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = build_schema.build_manifest(ROOT, target_commit=SOURCE)
        cls.selected = build_schema.migrations_after_baseline(cls.manifest["migrations"])

    def rows(self):
        return copy.deepcopy({"migrations": self.manifest["migrations"], "selected": self.selected})

    def reject_before_connect(self, rows):
        with mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_index", "--drop"]), \
                mock.patch.object(build_schema, "build_manifest", return_value=rows), \
                mock.patch.object(build_schema, "migrations_after_baseline", return_value=rows["selected"]), \
                mock.patch.object(build_schema, "connect") as connect, \
                mock.patch.object(build_schema, "run_file") as run:
            with self.assertRaisesRegex(RuntimeError, "index assertion forward"):
                build_schema.main()
            connect.assert_not_called()
            run.assert_not_called()

    def test_exact_two_pairs_preserve_all_other_entries_order_phases_and_inputs(self):
        rows = self.rows()
        before = copy.deepcopy(rows)
        plan = build_schema.index_assertion_forward_plan(rows["migrations"], rows["selected"])
        excluded = {old for old, *_ in CASES}
        self.assertEqual([row for row in self.selected if row["migrationId"] not in excluded], plan["selected"])
        self.assertEqual(len(self.selected) - 2, len(plan["selected"]))
        self.assertEqual(rows, before)
        self.assertEqual(set(build_schema.INDEX_ASSERTION_FORWARD_FILES), set(plan["fingerprints"]))
        other = [{"migrationId": "other", "path": "other.sql"}]
        self.assertIsNone(build_schema.index_assertion_forward_plan(other, other))

    def test_missing_duplicate_or_nonpending_pair_or_parent_fails_before_connect(self):
        for collection in ("migrations", "selected"):
            for key in build_schema.INDEX_ASSERTION_FORWARD_FILES:
                for duplicate in (False, True):
                    with self.subTest(collection=collection, key=key, duplicate=duplicate):
                        rows = self.rows()
                        row = next(row for row in rows[collection] if row["migrationId"] == key)
                        if duplicate:
                            rows[collection].append(copy.deepcopy(row))
                        else:
                            rows[collection].remove(row)
                            if collection == "migrations":
                                for i, entry in enumerate(rows[collection], 1):
                                    entry["order"] = i
                        self.reject_before_connect(rows)

    def test_identity_hash_dependency_phase_and_order_drift_fails_before_connect(self):
        for key in build_schema.INDEX_ASSERTION_FORWARD_FILES:
            for field, value in (("migrationId", "foreign"), ("path", "foreign.sql"), ("sha256", "0" * 64),
                                 ("dependsOn", ["foreign"]), ("executionPhase", "POST_DEPLOY"), ("order", 0)):
                with self.subTest(key=key, field=field):
                    rows = self.rows()
                    next(row for row in rows["migrations"] if row["migrationId"] == key)[field] = value
                    self.reject_before_connect(rows)
        for collection in ("migrations", "selected"):
            for old, new, *_ in CASES:
                rows = self.rows()
                a, b = [next(i for i, row in enumerate(rows[collection]) if row["migrationId"] == key)
                        for key in (old, new)]
                rows[collection][a], rows[collection][b] = rows[collection][b], rows[collection][a]
                if collection == "migrations":
                    for i, row in enumerate(rows[collection], 1):
                        row["order"] = i
                with self.subTest(collection=collection, old=old):
                    self.reject_before_connect(rows)

    def test_each_original_parent_and_replacement_file_byte_or_symlink_drift_fails_before_connect(self):
        original = Path.read_bytes
        for path, _ in build_schema.INDEX_ASSERTION_FORWARD_FILES.values():
            target = ROOT / path
            def changed(file):
                value = original(file)
                return value + b"\n" if file == target else value
            with self.subTest(path=path, mutation="bytes"), mock.patch.object(Path, "read_bytes", changed):
                self.reject_before_connect(self.rows())
            is_symlink = Path.is_symlink
            with self.subTest(path=path, mutation="symlink"), \
                    mock.patch.object(Path, "is_symlink", lambda file: file == target or is_symlink(file)):
                self.reject_before_connect(self.rows())

    def test_index_only_plan_rejects_existing_unknown_schema_even_with_drop(self):
        # Exclude the two earlier fixed forward families so this proves the new plan's own freshness gate.
        selected = [copy.deepcopy(row) for row in self.selected
                    if row["migrationId"] in build_schema.INDEX_ASSERTION_FORWARD_FILES]
        for i, row in enumerate(selected, 1):
            row["order"] = i
        for readback in ((1,), None, (None,), (), (0, 1), (-1,), ("0",), (False,)):
            admin = mock.Mock()
            admin.cursor.return_value.fetchone.side_effect = [("8.0.45", "routing-double"), readback]
            with self.subTest(readback=readback), \
                    mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_index", "--drop"]), \
                    mock.patch.object(build_schema, "build_manifest", return_value={"migrations": selected}), \
                    mock.patch.object(build_schema, "migrations_after_baseline", return_value=selected), \
                    mock.patch.object(build_schema, "connect", return_value=admin), \
                    mock.patch.object(build_schema, "run_file") as run:
                with self.assertRaisesRegex(RuntimeError, "nonexistent fresh scratch"):
                    build_schema.main()
                run.assert_not_called()
                self.assertFalse(any(call.args[0].startswith(("CREATE", "DROP"))
                                     for call in admin.cursor.return_value.execute.call_args_list))

    def test_both_replacements_run_real_assertions_and_fail_strictly_even_keep_going(self):
        for _, failed, *_ in CASES:
            selected = [copy.deepcopy(row) for row in self.selected
                        if row["migrationId"] in build_schema.INDEX_ASSERTION_FORWARD_FILES]
            selected.append({"migrationId": "later", "path": "later.sql"})
            for i, row in enumerate(selected, 1):
                row["order"] = i
            admin = mock.Mock()
            admin.cursor.return_value.fetchone.side_effect = [("8.0.45", "routing-double"), (0,)]
            connection = mock.Mock()
            cursor = connection.cursor.return_value
            cursor.connection = connection
            # This is assertion-runner routing evidence only; no MySQL or DDL success is simulated as a receipt.
            def fetch():
                sql = cursor.execute.call_args.args[0]
                return (0,) if "COUNT(*) = 2" in sql else (1,)
            cursor.fetchone.side_effect = fetch
            original_run = build_schema.run_file
            def route(cur, path, label, results, *args, **kwargs):
                return original_run(cur, path, label, results, *args, **kwargs) if label == failed else True
            with self.subTest(failed=failed), \
                    mock.patch.object(sys, "argv", ["build_schema.py", "--schema", "cy_e2e_index", "--keep-going"]), \
                    mock.patch.object(build_schema, "build_manifest", return_value={"migrations": selected}), \
                    mock.patch.object(build_schema, "migrations_after_baseline", return_value=selected), \
                    mock.patch.object(build_schema, "connect", side_effect=[admin, connection]), \
                    mock.patch.object(build_schema, "verify_seed_negative_controls", return_value=6), \
                    mock.patch.object(build_schema, "run_file", side_effect=route) as run, \
                    mock.patch("builtins.print") as emitted:
                self.assertEqual(1, build_schema.main())
            labels = [call.args[2] for call in run.call_args_list]
            self.assertEqual(failed, labels[-1])
            self.assertNotIn("later", labels)
            self.assertFalse(any(old in labels for old, *_ in CASES))
            self.assertFalse(run.call_args_list[-1].kwargs["keep_going"])
            self.assertTrue(any("scalar-true readback=0" in str(call) for call in emitted.call_args_list))
            receipts = [str(call) for call in emitted.call_args_list if "REPLACED_UNAPPLIED" in str(call)]
            self.assertEqual(2, len(receipts))
            for old, new, old_hash, new_hash, *_ in CASES:
                receipt = next(row for row in receipts if old + " -> " in row)
                for value in (old, new, old_hash, new_hash):
                    self.assertIn(value, receipt)
            self.assertFalse(any(str(call.args[0]).startswith("APPLIED") for call in emitted.call_args_list))
            if failed == build_schema.INDEX_REVENUE_NEW:
                self.assertFalse(any(call.args[0].startswith("ALTER") for call in cursor.execute.call_args_list))

    def test_ast_contract_rejects_selector_and_call_site_bypasses(self):
        source = (ROOT / "scripts/e2e/build_schema.py").read_text()
        self.assertEqual([], check_schema_manifest_order_source(source))
        start = source.index("INDEX_CATALOG_OLD =")
        end = source.index("\ndef main():", start)
        block = source[start:end]
        controls = [
            ('len(rows) != 1 or len(pending) != 1', 'False'),
            ('pending_entry != entry', 'False'),
            ('entry.get("sha256") != expected_sha', 'False'),
            ('hashlib.sha256(target.read_bytes()).hexdigest() != expected_sha', 'False'),
            ('entry.get("dependsOn") != dependencies[key]', 'False'),
            ('target.is_symlink() or not target.is_file()', 'not target.is_file()'),
            ('entry.get("executionPhase") is not None', 'False'),
            ('entry.get("order") != index + 1', 'False'),
            ('not sequence[INDEX_CATALOG_OLD] < sequence[INDEX_CATALOG_NEW]', 'False'),
            ('INDEX_CATALOG_OLD, INDEX_REVENUE_OLD)', 'INDEX_CATALOG_NEW, INDEX_REVENUE_NEW)'),
            ('INDEX_CATALOG_OLD, INDEX_REVENUE_OLD)', 'INDEX_CATALOG_OLD, INDEX_REVENUE_OLD, "third")'),
        ]
        for needle, replacement in controls:
            with self.subTest(needle=needle):
                self.assertIn(needle, block)
                self.assertTrue(check_schema_manifest_order_source(source[:start] + block.replace(needle, replacement) + source[end:]))
        for _, _, old_hash, new_hash, *_ in CASES:
            for digest in (old_hash, new_hash):
                self.assertTrue(check_schema_manifest_order_source(source.replace(digest, "0" * 64)))
        anchor = '    index_assertion_forward = index_assertion_forward_plan(all_migrations, selected)'
        controls = [
            (anchor, '    index_assertion_forward = None'),
            (anchor, '    INDEX_ASSERTION_FORWARD_FILES.update({"third": ("fake.sql", "0" * 64)})\n' + anchor),
            (anchor, '    index_assertion_forward_plan = lambda *args: {}\n' + anchor),
            ('index_assertion_forward_plan(all_migrations, selected)', 'index_assertion_forward_plan(selected, all_migrations)'),
            ('selected = index_assertion_forward["selected"] if index_assertion_forward else selected', 'selected = selected'),
            ('if notice_forward or broadcast_forward or index_assertion_forward:', 'if notice_forward or broadcast_forward:'),
            ('bool(index_assertion_forward) and m["migrationId"] in (INDEX_CATALOG_NEW, INDEX_REVENUE_NEW)', 'False'),
            ('(index_assertion_forward, INDEX_CATALOG_OLD, INDEX_CATALOG_NEW)', '(None, INDEX_CATALOG_OLD, INDEX_CATALOG_NEW)'),
            ('(index_assertion_forward, INDEX_REVENUE_OLD, INDEX_REVENUE_NEW)', '(None, INDEX_REVENUE_OLD, INDEX_REVENUE_NEW)'),
        ]
        for needle, replacement in controls:
            with self.subTest(needle=needle):
                self.assertIn(needle, source)
                self.assertTrue(check_schema_manifest_order_source(source.replace(needle, replacement)))


if __name__ == "__main__":
    unittest.main()
