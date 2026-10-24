"""Lock-cache controls reuse the existing isolated Git/XML fixtures; no Java/DB."""
from contextlib import redirect_stdout
import io
import json
import sys
import unittest
from unittest.mock import patch

import test_gate as fixtures

gate = fixtures.gate
SQL = "select id from orders where id=#{id} for update"


def locking(sql=SQL, attrs="", name="lock"):
    return fixtures.mapper('<select id="%s" %s>%s</select>' % (name, attrs, sql))


class LockingSelectTest(unittest.TestCase):
    setUp = fixtures.InventoryAndDeltaTest.setUp
    write = fixtures.InventoryAndDeltaTest.write
    commit = fixtures.InventoryAndDeltaTest.commit
    delta = fixtures.InventoryAndDeltaTest.delta
    prepare_policy = fixtures.InventoryAndDeltaTest.prepare_policy
    source_check = fixtures.InventoryAndDeltaTest.source_check

    def rules(self):
        return [item["rule"] for item in self.delta()]

    def test_missing_both_flags_is_red(self):
        self.write(fixtures.MAPPER, locking())
        self.assertIn("LOCKING_SELECT_CACHE", self.rules())

    def test_disabling_only_l2_is_red(self):
        self.write(fixtures.MAPPER, locking(attrs='useCache="false"'))
        self.assertIn("LOCKING_SELECT_CACHE", self.rules())

    def test_flush_only_is_red(self):
        self.write(fixtures.MAPPER, locking(attrs='flushCache="true"'))
        self.assertIn("LOCKING_SELECT_CACHE", self.rules())

    def test_both_explicit_flags_are_green(self):
        self.write(fixtures.MAPPER, locking(attrs='useCache="false" flushCache="true"'))
        self.assertEqual([], self.rules())

    def test_wrong_or_substituted_flags_are_red(self):
        for attrs in ('useCache="true" flushCache="true"',
                      'useCache="false" flushCache="false"',
                      'useCache="${cache}" flushCache="true"'):
            with self.subTest(attrs=attrs):
                self.write(fixtures.MAPPER, locking(attrs=attrs))
                self.assertIn("LOCKING_SELECT_CACHE", self.rules())

    def test_case_comments_and_terminal_lock_options_are_recognized(self):
        for suffix in ("", " NOWAIT", " SKIP LOCKED"):
            with self.subTest(suffix=suffix):
                self.write(fixtures.MAPPER, locking(sql='SELECT id FROM orders WHERE id=#{id} FOR /* lock */ UPDATE' + suffix))
                self.assertIn("LOCKING_SELECT_CACHE", self.rules())

    def test_quoted_or_commented_for_update_is_not_a_lock(self):
        for sql in ("select 'for update' from orders", "select id from orders -- for update\n",
                    "select id from orders /* for update */", 'select `for update` from orders'):
            with self.subTest(sql=sql):
                self.write(fixtures.MAPPER, locking(sql=sql))
                self.assertEqual([], self.rules())

    def test_include_and_dynamic_sql_are_unknown_not_blocked(self):
        for sql in ('select <include refid="columns"/> from orders for update',
                    'select id from orders <if test="id != null">where id=#{id}</if> for update',
                    'select id from ${table} for update',
                    '<include refid="unknownLock"/>'):
            with self.subTest(sql=sql):
                self.write(fixtures.MAPPER, locking(sql=sql))
                self.assertEqual([], self.rules())
                self.assertTrue(any(r["reason"] == "UNKNOWN_LOCKING_SELECT" for r in self.review))

    def test_executable_comments_and_unsupported_shapes_are_unknown(self):
        for sql in ("select id from orders /*! for update */", "select id from orders; select id from orders for update",
                    "select id from orders for update trailing_unknown", "select 'unterminated for update",
                    "select id from orders # for update"):
            with self.subTest(sql=sql):
                self.write(fixtures.MAPPER, locking(sql=sql))
                self.assertEqual([], self.rules())
                self.assertTrue(any(r["reason"] == "UNKNOWN_LOCKING_SELECT" for r in self.review))

    def test_unchanged_legacy_lock_does_not_block_unrelated_pr(self):
        self.write(fixtures.MAPPER, locking())
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        self.write("docs/normal.md", b"small change")
        self.assertEqual([], self.rules())

    def test_modified_legacy_lock_requires_flags(self):
        self.write(fixtures.MAPPER, locking())
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        self.write(fixtures.MAPPER, locking(sql=SQL.replace("select id", "select id, state")))
        self.assertIn("LOCKING_SELECT_CACHE", self.rules())

    def test_same_mapper_other_statement_edit_does_not_reflag_legacy_lock(self):
        self.write(fixtures.MAPPER, fixtures.mapper('<select id="lock">' + SQL + '</select>' + fixtures.ORDER))
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        self.write(fixtures.MAPPER, fixtures.mapper('<select id="lock">' + SQL + '</select>' + fixtures.ORDER.replace('state=#{state}', 'state=2')))
        self.assertNotIn("LOCKING_SELECT_CACHE", self.rules())

    def test_deleted_legacy_then_reintroduced_is_red(self):
        self.write(fixtures.MAPPER, locking())
        self.commit()
        self.write(fixtures.MAPPER, fixtures.mapper(""))
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        self.write(fixtures.MAPPER, locking())
        self.assertIn("LOCKING_SELECT_CACHE", self.rules())

    def test_fixed_legacy_then_flags_removed_is_red(self):
        self.write(fixtures.MAPPER, locking())
        self.commit()
        self.write(fixtures.MAPPER, locking(attrs='useCache="false" flushCache="true"'))
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        self.write(fixtures.MAPPER, locking())
        self.assertIn("LOCKING_SELECT_CACHE", self.rules())

    def test_proposal_is_exact_identity_and_canonical_body_not_candidate_exemption(self):
        self.write(fixtures.MAPPER, locking())
        details = {}
        old = gate.inventory(self.root, self.commit(), details)
        proposal = gate.locking_debt_proposal(old, details)
        self.assertEqual(old["base_sha"], proposal["base_sha"])
        self.assertEqual("PROPOSAL_NOT_APPROVED_NOT_AN_ALLOWLIST", proposal["status"])
        record = proposal["statements"][fixtures.MAPPER + "#lock"]
        self.assertEqual("example.OrderMapper", record["namespace"])
        self.assertEqual("lock", record["id"])
        self.assertEqual(64, len(record["body_sha256"]))
        reordered = gate.xml(b'<select resultType="long" id="lock">select id from orders for update</select>')
        normal = gate.xml(b'<select id="lock" resultType="long">select id from orders for update</select>')
        self.assertEqual(gate.locking_select(reordered, "n"), gate.locking_select(normal, "n"))
        one = gate.locking_select(gate.xml(b"<select id='lock'>select 'a b' from orders for update</select>"), "n")
        two = gate.locking_select(gate.xml(b"<select id='lock'>select 'a  b' from orders for update</select>"), "n")
        self.assertNotEqual(one["body_sha256"], two["body_sha256"], "literal whitespace must survive normalization")

    def test_proposal_drift_in_trusted_checkout_cannot_self_accept(self):
        trusted, candidate = self.prepare_policy()
        path = trusted / "locking-select-baseline.proposal.json"
        data = json.loads(path.read_text())
        data["statements"]["forged#entry"] = {"namespace": "candidate"}
        path.write_bytes(gate.encode(data))
        with self.assertRaisesRegex(gate.GateError, "LOCKING_BASELINE_LAUNDERING"):
            self.source_check(trusted, candidate)

    def test_candidate_cannot_edit_proposal(self):
        trusted, _ = self.prepare_policy()
        self.write("ci/architecture/locking-select-baseline.proposal.json", b"{}")
        with self.assertRaisesRegex(gate.GateError, "POLICY_CHANGE"):
            self.source_check(trusted, self.commit())

    def test_actual_source_cli_is_red_then_green_with_both_flags(self):
        trusted, _ = self.prepare_policy()
        for attrs, expected in (("", 1), ('useCache="false" flushCache="true"', 0)):
            with self.subTest(attrs=attrs):
                self.write(fixtures.MAPPER, locking(attrs=attrs))
                candidate = self.commit()
                context = {"event": "push", "checkout_sha": candidate,
                           "payload": {"repository": {"full_name": "liboyang42-cpu/hengyin"},
                                       "before": self.base, "after": candidate}}
                argv = ["gate.py", "source", "--source", str(self.root),
                        "--candidate", candidate, "--compare-base", self.base]
                output = io.StringIO()
                # The function's default baseline path was bound at import;
                # inject the isolated trusted fixture policy, as source_check does.
                check = gate.check_source
                with patch.object(gate, "BASE", self.base), patch.object(gate, "BOUNDARY_BASE", self.base), patch.object(gate, "HERE", trusted), \
                     patch.object(gate, "check_source", side_effect=lambda *args: check(*args, baseline_path=trusted / "baseline.json")), \
                     patch.object(gate, "ci_context", return_value=context), \
                     patch.object(sys, "argv", argv), redirect_stdout(output):
                    self.assertEqual(expected, gate.main())
                result = json.loads(output.getvalue())
                self.assertEqual(bool(expected), any(f["rule"] == "LOCKING_SELECT_CACHE" for f in result["findings"]))

    def test_namespace_or_statement_id_change_does_not_inherit_legacy_status(self):
        self.write(fixtures.MAPPER, locking())
        self.base = self.commit()
        self.before = gate.inventory(self.root, self.base)
        for content in (locking(name="newLock"), locking().replace(b"example.OrderMapper", b"different.Owner")):
            with self.subTest(content=content):
                self.write(fixtures.MAPPER, content)
                self.assertIn("LOCKING_SELECT_CACHE", self.rules())


if __name__ == "__main__":
    unittest.main()
