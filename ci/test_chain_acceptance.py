#!/usr/bin/env python3
"""chain_acceptance 的红绿负控：证明「退出码 0 但没观测到 / 回读不符 / 重复发放 / 串库 / 非 master 候选 /
注入没生效 / 工作流被放宽」都会转红，而不是只在注释里这么写。"""
import importlib.util
import json
import hashlib
import copy
import re
import subprocess
import tempfile
import unittest
import zipfile
from unittest import mock
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location("chain_acceptance", _HERE / "chain_acceptance.py")
ca = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ca)

WORKFLOW = _HERE.parent / ".github" / "workflows" / "chain-acceptance.yml"
SHA = "a" * 40
TOPIC = 42
REG = 77

GOOD_ROWS = {
    "platform": [[(9003, 7001, 1, 1, 1)], [(0,)]],
    "merchant_club": [[(2,)], [(4, 4)], [(1,)]],
    "sale_close": [[(3, 1)], [(1,)]],
    "player": [[(REG, 2, "E2E-TXN-%d" % REG)], [(4, 4, 4)], [(4, 4, 4)], [(1,)]],
    "finance": [[(1,)], [("PAID", "e2e_finance", "e2e_operator")]],
}


def good_query():
    """按链路顺序逐次回放「好」结果；同一条 SQL 在不同链路里可期望不同的行。"""
    by_sql = {}
    for name in ca.CHAIN_NAMES:
        checks = ca.CHAIN_BY_NAME[name]["checks"](TOPIC, {})
        assert len(checks) == len(GOOD_ROWS[name]), name
        for check, value in zip(checks, GOOD_ROWS[name]):
            by_sql.setdefault(check[1], []).append(value)
    return lambda sql, args: by_sql[sql].pop(0) if len(by_sql[sql]) > 1 else by_sql[sql][0]


def good_log(name):
    text = ('\n──────── 步骤 1:x\n   POST /x  {"code": 200, "_status": 200}\n'
            '   [库回读] lifecycle               ((1,),)\n')
    if name == "platform":
        text += "\nTOPIC_ID=%d\n" % TOPIC
    return text


class Runner:
    """按链路名返回 (退出码, 输出)；记录被执行的命令。"""

    def __init__(self, overrides=None):
        self.overrides = overrides or {}
        self.calls = []

    def __call__(self, command):
        name = Path(command[2]).name.replace("chain_", "").replace(".py", "")
        self.calls.append((name, command[3:]))
        return self.overrides.get(name, (0, good_log(name)))


class PlanTest(unittest.TestCase):
    def test_prefix_subset_and_valid_fail_path(self):
        result = ca.plan(SHA, "sale_close", " /api/play/checkin ")
        self.assertEqual(["platform", "merchant_club", "sale_close"], result["chains"])
        self.assertEqual("/api/play/checkin", result["fail_path"])
        self.assertEqual(list(ca.CHAIN_NAMES), ca.plan(SHA, "finance", "")["chains"])
        self.assertEqual([], ca.plan(SHA, "post_deploy", "")["chains"])
        with self.assertRaises(ca.GateError): ca.plan(SHA, "post_deploy", "/api/play/checkin")

    def test_rejects_bad_inputs(self):
        for sha in ("", "a" * 39, "A" * 40, "g" * 40, SHA + "\n"):
            with self.subTest(sha=sha), self.assertRaises(ca.GateError):
                ca.plan(sha, "finance", "")
        with self.assertRaises(ca.GateError):
            ca.plan(SHA, "player_only", "")
        for path in ("http://127.0.0.1/api/x", "/api/../x", "/api/x;id", "/other/x", "/api/x?y=1",
                     "/api/", "/api/x y", "/api/x\n/api/y"):
            with self.subTest(path=path), self.assertRaises(ca.GateError):
                ca.plan(SHA, "finance", path)


class ChainVerdictTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def run_all(self, runner, run_query=None):
        return ca.run_chains(list(ca.CHAIN_NAMES), self.out, {}, self.out, run_query or good_query(), runner)

    def verdicts(self, results):
        return [result["verdict"] for result in results]

    def test_all_observed_and_read_back_passes(self):
        runner = Runner()
        results = self.run_all(runner)
        self.assertEqual(["PASS"] * 5, self.verdicts(results))
        self.assertEqual([[], [str(TOPIC)], [str(TOPIC)], [str(TOPIC)], [str(TOPIC)]],
                         [args for _, args in runner.calls])
        receipt = json.loads((self.out / "chains" / "player.json").read_text(encoding="utf-8"))
        self.assertTrue(all(item["ok"] for item in receipt["terminal_readbacks"]))
        self.assertTrue((self.out / "chains" / "finance.log").is_file())

    def assert_fails_at(self, name, results, needle):
        index = ca.CHAIN_NAMES.index(name)
        self.assertEqual(["PASS"] * index + ["FAIL"] + ["NOT_RUN"] * (4 - index), self.verdicts(results))
        self.assertTrue(any(needle in problem for problem in results[index]["problems"]), results[index]["problems"])

    def test_exit_zero_is_not_enough(self):
        cases = {
            "failure marker": ("merchant_club", (0, good_log("merchant_club") + "   ✗ 锁价失败\n"), "✗"),
            "no readback": ("sale_close", (0, good_log("sale_close").replace("[库回读]", "")), "[库回读]"),
            "no http 200": ("player", (0, good_log("player").replace('"_status": 200', '"_status": 500')),
                            "HTTP 200"),
            "traceback": ("finance", (0, good_log("finance") + "Traceback (most recent call last):\n"),
                          "Traceback"),
            "nonzero exit": ("player", (1, good_log("player")), "退出码"),
        }
        for label, (name, override, needle) in cases.items():
            with self.subTest(label):
                self.assert_fails_at(name, self.run_all(Runner({name: override})), needle)

    def test_terminal_readback_mismatch_fails_even_with_clean_log(self):
        base = good_query()
        duplicated = ca.CHAIN_BY_NAME["player"]["checks"](TOPIC, {})[2][1]
        query = lambda sql, args: [(5, 5, 4)] if sql == duplicated else base(sql, args)
        self.assert_fails_at("player", self.run_all(Runner(), query), "终态回读不符")

    def test_readback_error_fails_closed(self):
        base = good_query()
        target = ca.CHAIN_BY_NAME["finance"]["checks"](TOPIC, {})[1][1]

        def query(sql, args):
            if sql == target:
                raise RuntimeError("SQL readback 串库")
            return base(sql, args)

        results = self.run_all(Runner(), query)
        self.assert_fails_at("finance", results, "终态回读不符")
        self.assertIn("串库", results[4]["terminal_readbacks"][1]["error"])

    def test_platform_needs_exactly_one_topic_id(self):
        for label, text in (("missing", good_log("platform").replace("TOPIC_ID=%d" % TOPIC, "")),
                            ("ambiguous", good_log("platform") + "TOPIC_ID=43\n")):
            with self.subTest(label):
                self.assert_fails_at("platform", self.run_all(Runner({"platform": (0, text)})), "")

    def test_failed_chain_still_reads_back_for_diagnosis(self):
        results = self.run_all(Runner({"sale_close": (1, good_log("sale_close"))}))
        self.assertTrue(results[2]["terminal_readbacks"])
        self.assertEqual("FAIL", results[2]["verdict"])


class TerminalPredicateTest(unittest.TestCase):
    """每条终态回读都必须能被一个现实的坏结果打红。"""

    MUTATIONS = {
        "platform": [[(9003, 7001, 1, 1, 7)], [(1,)]],
        "merchant_club": [[(1,)], [(4, 1)], [(2,)]],
        "sale_close": [[(2, 0)], [(2,)]],
        "player": [[(REG, 2, "E2E-TXN-%d" % REG), (REG + 1, 2, "E2E-TXN-%d" % (REG + 1))],
                   [(4, 3, 3)], [(5, 5, 4)], [(2,)]],
        "finance": [[(2,)], [("PAID", "e2e_finance", "e2e_finance")]],
    }

    def test_each_predicate_has_a_negative_control(self):
        for name, bad_rows in self.MUTATIONS.items():
            checks = ca.CHAIN_BY_NAME[name]["checks"](TOPIC, {})
            for check, good, bad in zip(checks, GOOD_ROWS[name], bad_rows):
                with self.subTest(chain=name, check=check[0]):
                    self.assertTrue(check[3](good))
                    self.assertFalse(check[3](bad))
                    self.assertFalse(check[3]([]))

    def test_player_rejects_unpaid_or_foreign_transaction(self):
        check = ca.CHAIN_BY_NAME["player"]["checks"](TOPIC, {})[0][3]
        self.assertFalse(check([(REG, 1, "E2E-TXN-%d" % REG)]))
        self.assertFalse(check([(REG, 2, "E2E-TXN-%d" % (REG + 1))]))


class SummaryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = Path(self.tmp.name)
        ca.merge_receipt(self.out, {"plan": ca.plan(SHA, "finance", "")})
        for name in ("plan", "precheck", "explode", "storage", "schema", "app"):
            ca.stage(self.out, name, "PASS")
        ca.stage(self.out, "explode", "PASS", jar_sha256="b" * 64)
        proof = dict(ca.post.validate_observation(*live_documents(), SHA), jar_sha256="b" * 64,
                     schema=ca.SCHEMA, server_uuid=POST_UUID,
                     process={"pid": 123, "starttime": "456", "command_sha256": "d" * 64,
                              "listener_inodes": ["789"]})
        result = ca.post.register_jobs(_HERE.parent, PostStorage().connect, POST_UUID)
        ca.stage(self.out, "postdeploy", "PASS", stopped_before_sql=True, app_exit_code=-15,
                 proof=proof, result=result)
        ca.run_chains(list(ca.CHAIN_NAMES), self.out, {}, self.out, good_query(), Runner())

    def tearDown(self):
        self.tmp.cleanup()

    def test_pass_only_when_every_stage_and_chain_passed(self):
        self.assertEqual("PASS", ca.summarize(self.out)["verdict"])

    def test_missing_stage_or_chain_receipt_fails(self):
        ca.stage(self.out, "schema", "FAIL")
        self.assertEqual("FAIL", ca.summarize(self.out)["verdict"])
        ca.stage(self.out, "schema", "PASS")
        (self.out / "chains" / "finance.json").unlink()
        summary = ca.summarize(self.out)
        self.assertEqual("FAIL", summary["verdict"])
        self.assertEqual("MISSING", summary["chains"][-1]["verdict"])

    def test_no_plan_fails(self):
        with tempfile.TemporaryDirectory() as empty:
            self.assertEqual("FAIL", ca.summarize(empty)["verdict"])

    def test_postdeploy_missing_partial_or_wrong_candidate_cannot_be_green(self):
        good = ca.read_receipt(self.out)["stages"]["postdeploy"]
        for patch in ({"status": "FAIL"}, {"stopped_before_sql": False},
                      {"result": {"status": "PASS", "statement_count": 14, "scalar_count": 9}},
                      {"proof": dict(good["proof"], source_sha="c" * 40)}):
            ca.stage(self.out, "postdeploy", **dict(good, **patch))
            self.assertEqual("FAIL", ca.summarize(self.out)["verdict"])

    def test_post_only_has_no_five_chain_receipts_but_still_requires_complete_post_stage(self):
        ca.merge_receipt(self.out, {"plan": ca.plan(SHA, "post_deploy", "")})
        for path in (self.out / "chains").glob("*.json"):
            path.unlink()
        self.assertEqual("FAIL", ca.summarize(self.out)["verdict"])  # Old empty PASS is not this scenario.
        self.install_existing_scenario()
        self.assertEqual("PASS", ca.summarize(self.out)["verdict"])
        document = ca.read_receipt(self.out); del document["stages"]["postdeploy"]
        ca.receipt_path(self.out).write_text(json.dumps(document))
        self.assertEqual("FAIL", ca.summarize(self.out)["verdict"])

    def install_existing_scenario(self):
        document = ca.read_receipt(self.out)
        document["stages"]["postdeploy"]["proof"]["admin"] = {
            "status": "PASS_FIXTURE_SUPERADMIN_HTTP_SQL_ONLY", "userId": 1, "roleKeys": ["admin"],
            "permissions": ["*:*:*"], "sqlReadback": {"schema": ca.SCHEMA,
                "userIdentity": {"userId": 1, "userName": "admin", "status": "0", "delFlag": "0"},
                "roleBindings": [{"roleId": 1, "roleKey": "admin", "status": "0", "delFlag": "0"}]},
            "routes": [{"menuId": 100, "path": "/system/user", "component": "system/user/index"}],
            "routeScope": "enabled_internal_C_plain_paths_with_enabled_M_ancestors_only",
            "ordinaryRbacIsolationVerified": False, "realRoleCardsVerified": 0}
        ca.receipt_path(self.out).write_text(json.dumps(document))
        db = PostStorage()
        fixture = ca.post.prepare_operator_fixture(db.connect, POST_UUID)
        result = ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
        good = ca.read_receipt(self.out)["stages"]["postdeploy"]
        ca.stage(self.out, "postdeploy", **dict(good, scenario=ca.post.OPERATOR_SCENARIO,
                 operator_fixture=fixture, result=result, unique_migration_count=3))
        rejection = ca.post.characterize_commerce_rejection(_HERE.parent, db.connect, POST_UUID, result)
        ca.stage(self.out, "postdeploy_commerce_rejection", "PASS", result=rejection)

    def test_post_only_summary_rejects_missing_or_forged_existing_and_rejection_evidence(self):
        ca.merge_receipt(self.out, {"plan": ca.plan(SHA, "post_deploy", "")})
        self.install_existing_scenario()
        good = copy.deepcopy(ca.read_receipt(self.out))
        for mutation in ("missing", "migration_pass", "continued", "false_readback", "drift", "fixture", "empty"):
            bad = copy.deepcopy(good)
            rejection = bad["stages"]["postdeploy_commerce_rejection"]["result"]
            if mutation == "missing": del bad["stages"]["postdeploy_commerce_rejection"]
            if mutation == "migration_pass": rejection["rejected_migration"]["status"] = "PASS"
            if mutation == "continued": rejection["rejected_migration"]["statement_count"] = 4
            if mutation == "false_readback": rejection["scalar_readback"]["actual"] = [1]
            if mutation == "drift": rejection["after"]["rows"][0][11] = "changed"
            if mutation == "fixture": bad["stages"]["postdeploy"]["operator_fixture"]["after"]["rows"][0][7] = "0"
            if mutation == "empty": bad["stages"]["postdeploy"]["result"]["before"]["rows"] = []
            ca.receipt_path(self.out).write_text(json.dumps(bad))
            with self.subTest(mutation=mutation): self.assertEqual("FAIL", ca.summarize(self.out)["verdict"])

    def test_post_only_summary_rejects_missing_admin_identity_binding_or_routes(self):
        ca.merge_receipt(self.out, {"plan": ca.plan(SHA, "post_deploy", "")})
        self.install_existing_scenario()
        good = copy.deepcopy(ca.read_receipt(self.out))
        for field, value in (("status", "PASS"), ("userId", 2), ("roleKeys", []),
                             ("permissions", []), ("sqlReadback", {}), ("routes", []),
                             ("routeScope", "all"), ("ordinaryRbacIsolationVerified", True),
                             ("realRoleCardsVerified", 84)):
            bad = copy.deepcopy(good)
            bad["stages"]["postdeploy"]["proof"]["admin"][field] = value
            ca.receipt_path(self.out).write_text(json.dumps(bad))
            with self.subTest(field=field): self.assertEqual("FAIL", ca.summarize(self.out)["verdict"])

    def test_summary_rejects_missing_live_or_per_statement_or_independent_sql_evidence(self):
        good = ca.read_receipt(self.out)["stages"]["postdeploy"]
        for mutation in ("bean", "health", "listener", "file", "scalar", "uuid", "rows", "file_readback"):
            bad = copy.deepcopy(good)
            if mutation == "bean": bad["proof"]["beans"].pop(next(iter(ca.post.BEANS)))
            if mutation == "health": bad["proof"]["redis"] = "DOWN"
            if mutation == "listener": bad["proof"]["process"].pop("listener_inodes")
            if mutation == "file": bad["result"]["files"].pop()
            if mutation == "scalar": bad["result"]["files"][0]["scalars"][0]["actual"] = [0]
            if mutation == "uuid": bad["result"]["after"]["server_uuid"] = "other"
            if mutation == "rows": bad["result"]["after"]["rows"].pop()
            if mutation == "file_readback": bad["result"]["files"][0].pop("independent_readback")
            ca.stage(self.out, "postdeploy", **bad)
            with self.subTest(mutation=mutation): self.assertEqual("FAIL", ca.summarize(self.out)["verdict"])

    def test_refuses_to_run_after_failed_stage(self):
        ca.stage(self.out, "schema", "FAIL")
        original = ca.start_app
        ca.start_app = lambda *args: self.fail("app must not start")
        try:
            self.assertEqual(1, ca.main(["run", "--out", str(self.out)], {}))
        finally:
            ca.start_app = original
        self.assertEqual("FAIL", ca.read_receipt(self.out)["stages"]["app"]["status"])


class MasterBasisTest(unittest.TestCase):
    def git(self, *args):
        return subprocess.run(["git", "-C", self.repo] + list(args), check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True).stdout.strip()

    def commit(self, message):
        Path(self.repo, "f").write_text(message, encoding="utf-8")
        self.git("add", "f")
        self.git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        self.git("init", "-q", "-b", "master")
        self.master = self.commit("m1")
        self.git("update-ref", "refs/remotes/origin/master", self.master)

    def tearDown(self):
        self.tmp.cleanup()

    def test_descendant_of_master_is_accepted(self):
        candidate = self.commit("c1")
        facts = ca.master_basis(self.repo, candidate)
        self.assertEqual((self.master, 1, 0), (facts["merge_base"], facts["ahead_of_merge_base"],
                                               facts["behind_master"]))

    def test_unrelated_history_and_head_mismatch_are_rejected(self):
        self.git("checkout", "-q", "--orphan", "legacy")
        orphan = self.commit("legacy")
        with self.assertRaisesRegex(ca.GateError, "共同祖先"):
            ca.master_basis(self.repo, orphan)
        with self.assertRaisesRegex(ca.GateError, "不一致"):
            ca.master_basis(self.repo, self.master)

    def test_missing_master_ref_is_rejected(self):
        self.git("update-ref", "-d", "refs/remotes/origin/master")
        with self.assertRaisesRegex(ca.GateError, "fetch-depth"):
            ca.master_basis(self.repo, self.master)

    def test_jdk_comes_from_candidate_pom_and_legacy_line_is_rejected(self):
        pom = Path(self.repo, "pom.xml")
        pom.write_text("<properties><java.version>21</java.version></properties>", encoding="utf-8")
        self.assertEqual(("21", "21"), ca.candidate_jdk(self.repo))
        pom.write_text("<properties><java.version>1.8</java.version></properties>", encoding="utf-8")
        with self.assertRaises(ca.GateError):
            ca.candidate_jdk(self.repo)
        pom.write_text("<properties/>", encoding="utf-8")
        with self.assertRaises(ca.GateError):
            ca.candidate_jdk(self.repo)


class RuntimeIsolationTest(unittest.TestCase):
    def test_fault_probe_requires_observed_injection_and_clean_control(self):
        def probe(target_status, target_body, control=401):
            def call(method, path, token=None, **kwargs):
                if path == "/api/city/nodes":
                    return control, ""
                if not token:
                    return 401, '{"code":401}'
                return target_status, target_body
            return call

        injected = probe(500, '{"code":500,"msg":"e2e 注入:打卡服务异常"}')
        self.assertTrue(ca.fault_probe("/api/play/checkin", "t", injected)["target_injected"])
        self.assertFalse(ca.fault_probe("", "t", probe(400, ""))["target_injected"])
        with self.assertRaisesRegex(ca.GateError, "没有观测到"):
            ca.fault_probe("/api/play/checkin", "t", probe(500, '{"msg":"real failure"}'))
        with self.assertRaisesRegex(ca.GateError, "没有观测到"):
            ca.fault_probe("/api/play/checkin", None, injected)
        with self.assertRaisesRegex(ca.GateError, "不透明"):
            ca.fault_probe("", "t", injected)
        with self.assertRaisesRegex(ca.GateError, "控制组"):
            ca.fault_probe("", "t", probe(401, "", control=200))

    def test_probe_token_uses_real_login_and_fails_closed(self):
        gate = _HERE.parent
        seen = []

        def probe(method, path, **kwargs):
            seen.append((path, kwargs))
            return (200, '{"code":200,"token":"tok"}') if path == "/login" else (200, '{"code":500,"msg":"x"}')

        self.assertEqual("tok", ca.probe_token("/business/audit/x", gate, "/nonexistent", {}, probe))
        self.assertEqual("admin", seen[0][1]["json_body"]["username"])
        self.assertTrue(seen[0][1]["json_body"]["password"])
        seeded = []
        with self.assertRaisesRegex(ca.GateError, "登录失败"):
            ca.probe_token("/api/play/checkin", gate, "/nonexistent", {}, probe,
                           seed=lambda phone, *args: seeded.append(phone))
        self.assertEqual([ca.PROBE_PHONE], seeded)
        self.assertEqual({"phone": ca.PROBE_PHONE, "code": "123456"}, seen[-1][1]["form"])

    def test_chain_env_pins_local_storage_and_drops_inherited_targets(self):
        inherited = {"PATH": "/usr/bin", "CHENGYIN_E2E_DB_HOST": "db.example.invalid", "CHENGYIN_E2E_BASE":
                     "http://api.example.invalid", "HTTPS_PROXY": "http://proxy.invalid", "GITHUB_TOKEN": "x",
                     "ACTIONS_RUNTIME_TOKEN": "y"}
        env = ca.chain_env(inherited, "/work/bin")
        self.assertEqual(("127.0.0.1", "3316", "cy_e2e_51", "http://127.0.0.1:8099"), (
            env["CHENGYIN_E2E_DB_HOST"], env["CHENGYIN_E2E_DB_PORT"], env["CHENGYIN_E2E_DB_SCHEMA"],
            env["CHENGYIN_E2E_BASE"]))
        self.assertTrue(env["PATH"].split(":")[0].endswith("/work/bin"))
        for key in ("HTTPS_PROXY", "GITHUB_TOKEN", "ACTIONS_RUNTIME_TOKEN"):
            self.assertNotIn(key, env)

    def test_generated_secrets_never_reach_the_receipt(self):
        with tempfile.TemporaryDirectory() as work:
            env = ca.app_env({"PATH": "/usr/bin"}, work)
            shown = json.dumps(ca.public_env(env))
        for key in ("CHENGYIN_JWT_SECRET", "CHENGYIN_WITHDRAWAL_PII_KEY", "COUPON_QR_SECRET",
                    "CHENGYIN_DRUID_PASSWORD", "CHENGYIN_WXMINI_SECRET", "CHENGYIN_WXPAY_APIV3_KEY"):
            self.assertGreaterEqual(len(env[key]), 32)
            self.assertNotIn(env[key], shown)
        self.assertEqual(32, len(__import__("base64").b64decode(env["CHENGYIN_WITHDRAWAL_PII_KEY"])))

    def test_redis_shim_targets_only_the_run_container(self):
        self.assertIn("exec docker exec -i cy-chain-1-1-redis redis-cli \"$@\"", ca.redis_shim("cy-chain-1-1-redis"))
        for bad in ("x; rm -rf /", "$(id)", "a b"):
            with self.subTest(bad), self.assertRaises(ca.GateError):
                ca.redis_shim(bad)
        with self.assertRaises(ca.GateError):
            ca.container_names({"GITHUB_RUN_ID": "1;id"})

    def test_app_logs_to_console_fixture_not_packaged_file_path(self):
        with tempfile.TemporaryDirectory() as app:
            Path(app, "META-INF").mkdir()
            Path(app, "META-INF", "MANIFEST.MF").write_text("Main-Class: x.Launcher\n", encoding="utf-8")
            command = ca.java_command(app, "")
        logback = str(ca.FIXTURE_LOGBACK.resolve())
        self.assertIn("-Dlogging.config=" + logback, command)
        self.assertIn("-Dlogback.configurationFile=" + logback, command)
        self.assertLess(command.index("-Dlogback.configurationFile=" + logback), command.index("-cp"))
        fixture = ca.FIXTURE_LOGBACK.read_text(encoding="utf-8")
        self.assertIn("ConsoleAppender", fixture)
        for forbidden in ("FileAppender", "<file>", "/home/ruoyi"):
            self.assertNotIn(forbidden, fixture.split("-->", 1)[1])

    def test_fixture_config_is_loopback_scratch_only(self):
        text = ca.FIXTURE_CONFIG.read_text(encoding="utf-8")
        props = dict(line.split("=", 1) for line in text.splitlines() if line and not line.startswith("#"))
        self.assertEqual(("127.0.0.1", "8099"), (props["server.address"], props["server.port"]))
        self.assertTrue(props["spring.datasource.druid.master.url"].startswith("jdbc:mysql://127.0.0.1:3316/cy_e2e_51?"))
        self.assertEqual(("127.0.0.1", "6379", "9"), (props["spring.data.redis.host"], props["spring.data.redis.port"],
                                                      props["spring.data.redis.database"]))
        self.assertEqual("health,info,beans", props["management.endpoints.web.exposure.include"])
        self.assertEqual("full", props["management.info.git.mode"])
        self.assertEqual("true", props["management.endpoint.health.probes.enabled"])
        self.assertEqual("db,redis,livenessState,readinessState",
                         props["management.endpoint.health.group.isolatedStartup.include"])
        self.assertEqual("when_authorized", props["management.endpoint.health.group.isolatedStartup.show-details"])
        for key, value in props.items():
            if "password" in key.lower() or "secret" in key.lower():
                self.assertEqual("", value, key)
        ips = set(re.findall(r"\b\d{1,3}(?:\.\d{1,3}){3}\b", text))
        self.assertEqual({"127.0.0.1"}, ips)


def workflow_problems(text):
    problems = []
    events = re.search(r"^on:\n([\s\S]*?)(?=^\S)", text, re.MULTILINE)
    if events is None or re.findall(r"^  ([\w-]+):", events.group(1), re.MULTILINE) != ["workflow_dispatch"]:
        problems.append("must be manual-only (workflow_dispatch)")
    through = re.search(r"^      through:\n[\s\S]*?options:\n((?:          - .+\n)+)", text, re.MULTILINE)
    options = re.findall(r"- (\S+)", through.group(1)) if through else []
    if options != list(ca.CHAIN_NAMES) + ["post_deploy", "post_restart"]:
        problems.append("through choices %s != chain order %s" % (options, list(ca.CHAIN_NAMES)))
    if "\npermissions:\n  contents: read\n" not in text or re.search(r":\s*write\b", text):
        problems.append("permissions must be contents: read only")
    if "secrets." in text:
        problems.append("must not use repository secrets")
    if "    if: github.repository == 'liboyang42-cpu/hengyin'\n" not in text:
        problems.append("job must be guarded to the public repository")
    runs_on = re.findall(r"^\s+runs-on:\s*(.+)$", text, re.MULTILINE)
    if not runs_on or any(value.strip() != "ubuntu-latest" for value in runs_on):
        problems.append("every job must run on GitHub-hosted ubuntu-latest")
    if len(re.findall(r"^    timeout-minutes: \d+$", text, re.MULTILINE)) != len(runs_on):
        problems.append("every job needs timeout-minutes")
    steps = re.split(r"\n(?=      - )", text)
    for step in steps:
        if "uses: actions/checkout@" in step and "persist-credentials: false" not in step:
            problems.append("checkout without persist-credentials: false")
    names = re.findall(r"^      - (?:name: (.+)|uses: (.+))$", text, re.MULTILINE)
    order = [name or uses for name, uses in names]
    if "Chain acceptance negative controls" not in order or "Plan" not in order or \
            order.index("Chain acceptance negative controls") > order.index("Plan"):
        problems.append("negative controls must run before the plan")
    candidate = [step for step in steps if "path: candidate" in step]
    if not candidate or "fetch-depth: 0" not in candidate[0]:
        problems.append("candidate checkout needs fetch-depth: 0 for the master-basis check")
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r"^(\s*)(?:- )?run:\s*(.*)$", line)
        if not match:
            continue
        body = [match.group(2)]
        if match.group(2).strip() in ("|", ">", "|-", ">-"):
            indent = len(match.group(1))
            for following in lines[index + 1:]:
                if following.strip() and len(following) - len(following.lstrip()) <= indent:
                    break
                body.append(following)
        if any("${{" in part for part in body):
            problems.append("expression interpolated into a run script: %s" % line.strip())
    for always in ("Summarize (fail-closed)", "Remove throwaway containers", "Upload receipts and raw logs"):
        block = next((step for step in steps if always in step), "")
        if "if: always()" not in block:
            problems.append("%s must run if: always()" % always)
    return problems


class ContentSecurityStubTest(unittest.TestCase):
    """内容安全桩:必须在、必须贴口径、只许桩出站调用;拿掉或越权都让 explode 变红。"""

    def setUp(self):
        self.repo = _HERE.parent
        self.stub = self.repo / "scripts" / "e2e" / ("%s.java.stub" % ca.CONTENT_SECURITY_STUB)

    def _copy(self, text=None, drop=None):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name) / "scripts" / "e2e"
        root.mkdir(parents=True)
        for name in ca.STUBS:
            if name == drop:
                continue
            source = self.repo / "scripts" / "e2e" / ("%s.java.stub" % name)
            content = source.read_text(encoding="utf-8")
            if name == ca.CONTENT_SECURITY_STUB and text is not None:
                content = text
            (root / ("%s.java.stub" % name)).write_text(content, encoding="utf-8")
        return tmp.name

    def test_real_stub_is_present_labelled_and_compiled_with_the_others(self):
        self.assertIn(ca.CONTENT_SECURITY_STUB, ca.STUBS)
        self.assertEqual([], ca.stub_problems(self.repo))
        self.assertTrue(any(item.startswith(ca.CONTENT_SECURITY_GATE) for item in ca.NOT_COVERED))
        text = self.stub.read_text(encoding="utf-8")
        self.assertIn("REJECT_MARKER", text)
        self.assertIn("@Primary", text)

    def test_negative_controls(self):
        text = self.stub.read_text(encoding="utf-8")
        override = ("    @Override\n    public CallbackResult processMediaCheckCallback(String s, String t, String n, "
                    "String b) {\n        return CallbackResult.SUCCESS;\n    }\n}\n")
        mutations = {
            "stub removed": self._copy(drop=ca.CONTENT_SECURITY_STUB),
            "label removed": self._copy(text.replace(ca.CONTENT_SECURITY_GATE, "content security")),
            "not the real service": self._copy(text.replace("extends WxContentSecurityServiceImpl",
                                                            "implements IWxContentSecurityService")),
            "callback overridden": self._copy(text.rstrip()[:-1] + override),
        }
        for label, directory in mutations.items():
            with self.subTest(label):
                self.assertTrue(ca.stub_problems(directory), label)

    def test_receipt_carries_the_gate_label(self):
        with tempfile.TemporaryDirectory() as tmp:
            ca.merge_receipt(tmp, {"not_covered": ca.NOT_COVERED})
            self.assertIn(ca.CONTENT_SECURITY_GATE, ca.receipt_path(tmp).read_text(encoding="utf-8"))


class WorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.text = WORKFLOW.read_text(encoding="utf-8")

    def test_workflow_contract(self):
        self.assertEqual([], workflow_problems(self.text))

    def test_negative_controls(self):
        mutations = {
            "automatic push": self.text.replace("on:\n", "on:\n  push:\n    branches: [master]\n", 1),
            "pull_request": self.text.replace("on:\n", "on:\n  pull_request:\n", 1),
            "schedule": self.text.replace("on:\n", "on:\n  schedule:\n    - cron: '0 0 * * *'\n", 1),
            "input in script": self.text.replace(
                "run: python3 gate/ci/chain_acceptance.py plan",
                "run: python3 gate/ci/chain_acceptance.py plan '${{ inputs.fail_path }}'", 1),
            "credential persisted": self.text.replace("          persist-credentials: false\n", "", 1),
            "self-hosted": self.text.replace("runs-on: ubuntu-latest", "runs-on: [self-hosted, chengyin]", 1),
            "chain choice drift": self.text.replace("          - sale_close\n", "", 1),
            "secrets": self.text.replace("FAIL_PATH: ${{ inputs.fail_path }}",
                                         "FAIL_PATH: ${{ secrets.DB_PASSWORD }}", 1),
            "write permission": self.text.replace("  contents: read\n", "  contents: write\n", 1),
            "no repository guard": self.text.replace("    if: github.repository == 'liboyang42-cpu/hengyin'\n", "", 1),
            "no timeout": self.text.replace("    timeout-minutes: 120\n", "", 1),
            "shallow candidate": self.text.replace("          fetch-depth: 0\n", "", 1),
            "summary skipped on failure": self.text.replace(
                "      - name: Summarize (fail-closed)\n        if: always()\n",
                "      - name: Summarize (fail-closed)\n", 1),
        }
        for label, mutated in mutations.items():
            with self.subTest(label):
                self.assertNotEqual(self.text, mutated, "negative control could not mutate: " + label)
                self.assertTrue(workflow_problems(mutated), label)


class StorageStopTest(unittest.TestCase):
    def stop(self, readbacks, rm_failure=None, log_failure=False):
        env = {"GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2"}
        names = ca.container_names(env)
        calls, pending = [], iter(readbacks)

        def run(command, **kwargs):
            calls.append(command)
            self.assertEqual("docker", command[0])
            if command[1] == "logs":
                if log_failure:
                    raise OSError("synthetic log capture failure")
                return subprocess.CompletedProcess(command, 0, stdout=b"fixture logs")
            if command[1] == "rm":
                self.assertEqual(["docker", "rm", "-f", "-v", command[-1]], command)
                self.assertIn(command[-1], names.values())
                if isinstance(rm_failure, Exception):
                    raise rm_failure
                return subprocess.CompletedProcess(command, rm_failure or 0, stdout="", stderr="synthetic rm failure")
            self.assertEqual(["docker", "ps", "-a", "--format", "{{.Names}}"], command)
            result = next(pending)
            if isinstance(result, Exception):
                raise result
            code, stdout = result
            return subprocess.CompletedProcess(command, code, stdout=stdout, stderr="synthetic ps failure")

        with tempfile.TemporaryDirectory() as directory, mock.patch.object(ca.subprocess, "run", side_effect=run):
            status = ca.cmd_storage_stop(ca.argparse.Namespace(out=directory), env)
            receipt = ca.read_receipt(directory)
        self.assertEqual([names["mysql"], names["redis"]], [call[-1] for call in calls if call[1] == "rm"])
        self.assertEqual(2, sum(call[1] == "ps" for call in calls))
        return status, receipt

    def test_nonzero_empty_ps_is_unknown_not_absent(self):
        status, receipt = self.stop([(1, ""), (0, "")])
        self.assertEqual(1, status)
        self.assertEqual({"mysql": False, "redis": True}, receipt["teardown"])
        self.assertEqual({"mysql": "UNKNOWN", "redis": "ABSENT"}, receipt["teardown_readback"])

    def test_leftover_container_is_not_removed(self):
        status, receipt = self.stop([(0, "cy-chain-123-2-mysql\n"), (0, "")])
        self.assertEqual(1, status)
        self.assertEqual({"mysql": False, "redis": True}, receipt["teardown"])
        self.assertEqual({"mysql": "LEFTOVER", "redis": "ABSENT"}, receipt["teardown_readback"])

    def test_readback_oserror_still_cleans_the_other_exact_container(self):
        status, receipt = self.stop([OSError("synthetic readback failure"), (0, "")])
        self.assertEqual(1, status)
        self.assertEqual({"mysql": False, "redis": True}, receipt["teardown"])
        self.assertEqual({"mysql": "UNKNOWN", "redis": "ABSENT"}, receipt["teardown_readback"])

    def test_successful_rm_and_empty_successful_ps_confirm_absence(self):
        status, receipt = self.stop([(0, ""), (0, "")])
        self.assertEqual(0, status)
        self.assertEqual({"mysql": True, "redis": True}, receipt["teardown"])
        self.assertEqual({"mysql": "ABSENT", "redis": "ABSENT"}, receipt["teardown_readback"])

    def test_failed_rm_can_pass_only_if_successful_ps_proves_already_absent(self):
        for failure in (1, OSError("synthetic rm failure")):
            with self.subTest(failure=type(failure).__name__):
                status, receipt = self.stop([(0, ""), (0, "")], rm_failure=failure)
                self.assertEqual(0, status)
                self.assertEqual({"mysql": True, "redis": True}, receipt["teardown"])
                self.assertEqual({"mysql": "ABSENT", "redis": "ABSENT"}, receipt["teardown_readback"])

    def test_log_capture_failure_does_not_skip_exact_cleanup(self):
        status, receipt = self.stop([(0, ""), (0, "")], log_failure=True)
        self.assertEqual(0, status)
        self.assertEqual({"mysql": True, "redis": True}, receipt["teardown"])


class NativeJarTest(unittest.TestCase):
    def test_native_parameters_require_exact_pair(self):
        self.assertIsNone(ca.plan(SHA, "finance", "")["native_jar"])
        self.assertEqual(123, ca.plan(SHA, "finance", "", "123", "b" * 64)["native_jar"]["run_id"])
        for run, digest in (("", "b" * 64), ("123", ""), ("0", "b" * 64),
                            ("1;id", "b" * 64), ("1\n", "b" * 64), ("123", "B" * 64)):
            with self.subTest(run=run), self.assertRaises(ca.GateError):
                ca.plan(SHA, "finance", "", run, digest)

    def test_native_original_marker_sidecar_and_embedded_commit_are_required(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = Path(folder) / "chengyinhub-admin.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("BOOT-INF/classes/git.properties", "git.commit.id=" + SHA + "\n")
            digest = ca.sha256_file(jar)
            planned = ca.plan(SHA, "finance", "", "123", digest)
            marker, sidecar = jar.parent / "SOURCE_SHA", jar.with_name(jar.name + ".sha256")
            marker.write_text(SHA + "\n"); sidecar.write_text(digest + "  " + jar.name + "\n")
            self.assertEqual(digest, ca.verify_candidate_jar(jar, planned)["jar_sha256"])
            for target, bad in ((marker, "c" * 40), (sidecar, "0" * 64 + "  " + jar.name)):
                original = target.read_bytes(); target.write_text(bad)
                with self.assertRaises(ca.GateError): ca.verify_candidate_jar(jar, planned)
                target.write_bytes(original)
            with self.assertRaises(ca.GateError):
                ca.verify_candidate_jar(jar, ca.plan(SHA, "finance", "", "123", "0" * 64))
            with self.assertRaises(ca.GateError):
                ca.verify_candidate_jar(jar, ca.plan("c" * 40, "finance", ""))

    def test_jar_mismatch_refuses_before_any_java_compile(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder); jar = out / "jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("BOOT-INF/classes/git.properties", "git.commit.id=" + "c" * 40)
            ca.merge_receipt(out, {"plan": ca.plan(SHA, "finance", "")})
            ca.stage(out, "precheck", "PASS")
            with mock.patch.object(ca.subprocess, "run") as java, self.assertRaises(ca.GateError):
                ca.cmd_explode(ca.argparse.Namespace(out=out, jar=jar), {})
            java.assert_not_called()


POST_UUID = "12345678-1234-1234-1234-123456789012"


def live_documents():
    return ({"git": {"commit": {"id": SHA}}},
            {"status": "UP", "components": {key: {"status": "UP"} for key in
             ("db", "redis", "livenessState", "readinessState")}},
            {"contexts": {"app": {"beans": {name: {"type": value} for name, value in ca.post.BEANS.items()}}}})


class PostStorage:
    """Pure cursor model: tests shared execution/assertion and readback gates, not MySQL semantics."""
    def __init__(self):
        self.rows, self.calls, self.opened, self.closed = [], [], 0, 0
        self.bad_scalar, self.write_error, self.uuid = None, False, POST_UUID
        self.writes, self.commits, self.rollbacks = [], 0, 0
        self.delete_failure_after, self.extra_after_deletes = None, False

    def connect(self):
        model = self
        self.opened += 1
        class Cursor:
            def execute(self, sql, args=None):
                model.calls.append(sql)
                self.one, self.many, self.rowcount = None, [], 0
                if sql == "SELECT @@server_uuid,DATABASE()":
                    self.one = (model.uuid, ca.SCHEMA)
                elif sql == ca.post.READ_ROWS:
                    self.many = sorted(model.rows, key=lambda row: (row[3], row[0]))
                elif sql in (ca.post.ALL_JOBS, ca.post.ALL_JOBS + " FOR UPDATE"):
                    self.many = copy.deepcopy(sorted(model.rows, key=lambda row: row[0]))
                elif sql.startswith("DELETE FROM sys_job WHERE "):
                    if model.delete_failure_after is not None and len(model.writes) >= model.delete_failure_after:
                        return
                    model.writes.append((sql, args))
                    matched = [row for row in model.rows if tuple(row) == args]
                    self.rowcount = len(matched)
                    model.rows = [row for row in model.rows if tuple(row) != args]
                    if not model.rows and model.extra_after_deletes:
                        model.rows = [[999, "unknown"] + [None] * 11]
                elif sql == ca.post.FIXTURE_INSERT:
                    if model.write_error: raise RuntimeError("PRIVATE_SQL_ERROR")
                    model.writes.append((sql, args))
                    model.rows.append([len(model.rows) + 1] + list(args))
                    self.rowcount = 1
                elif sql == ca.post.COMMERCE_PAUSE:
                    model.writes.append((sql, args))
                    for row in model.rows:
                        if (row[0], row[3]) == (args[2], args[3]):
                            row[4], row[7], self.rowcount = args[0], args[1], 1
                elif sql.startswith("INSERT INTO sys_job"):
                    if model.write_error: raise RuntimeError("PRIVATE_SQL_ERROR")
                    target = next(name for name in ca.post.NEW_ROWS if "'" + name + "'" in sql)
                    if not any(row[3] == target for row in model.rows):
                        group, cron, misfire, concurrent, status = ca.post.NEW_ROWS[target]
                        model.rows.append([len(model.rows) + 1, "fixture", group, target, cron, misfire,
                                           concurrent, status, "system", "2026-10-20", "fixture", "", None])
                elif "SUM(cron_expression" in sql:
                    rows = [row for row in model.rows if row[3] == ca.post.COMMERCE]
                    valid = len(rows) == 1 and tuple(rows[0][i] for i in (4, 5, 6, 7)) == ca.post.NEW_ROWS[ca.post.COMMERCE][1:]
                    self.one = (int(valid),) if model.bad_scalar is None else model.bad_scalar
                    self.many = [self.one]
                else:
                    self.one = (1,) if model.bad_scalar is None else model.bad_scalar
            def fetchone(self): return self.one
            def fetchall(self): return tuple(self.many)
            def close(self): pass
        class Connection:
            def cursor(self): return Cursor()
            def close(self): model.closed += 1
            def begin(self): self.before = copy.deepcopy(model.rows)
            def commit(self): model.commits += 1
            def rollback(self):
                model.rollbacks += 1
                model.rows = self.before
        return Connection()


class PostDeployTest(unittest.TestCase):
    def existing_scenario(self):
        db = PostStorage()
        fixture = ca.post.prepare_operator_fixture(db.connect, POST_UUID)
        positive = ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
        rejection = ca.post.characterize_commerce_rejection(_HERE.parent, db.connect, POST_UUID, positive)
        return db, fixture, positive, rejection

    def test_existing_operator_six_rows_preserved_and_commerce_incompatible_rejected_by_original_sql(self):
        db, fixture, positive, rejection = self.existing_scenario()
        self.assertEqual([], fixture["before"]["rows"])
        self.assertEqual(fixture["after"], positive["before"])
        self.assertEqual(positive["before"], positive["after"])
        self.assertEqual((15, 9), (positive["statement_count"], positive["scalar_count"]))
        for row in positive["before"]["rows"]:
            self.assertEqual(ca.post.operator_values(row[3]), row[1:])
            self.assertEqual("0" if row[3] == ca.post.COMMERCE else "1", row[7])
        self.assertEqual("FAIL", rejection["rejected_migration"]["status"])
        self.assertEqual((3, 1), (rejection["rejected_migration"]["statement_count"],
                                 rejection["rejected_migration"]["scalar_count"]))
        self.assertEqual([0], rejection["scalar_readback"]["actual"])
        self.assertEqual(rejection["before"], rejection["after"])
        self.assertEqual(7, len(db.writes))
        self.assertEqual(2, db.commits)
        self.assertEqual(db.opened, db.closed)
        # The last original execution ends at merchant stmt3; only its independent SELECT is repeated.
        failed_start = next(i for i, sql in enumerate(db.calls) if sql == ca.post.COMMERCE_PAUSE)
        self.assertFalse(any("roamPresenceRevokeAuditTask" in sql or "metricsProbeTask" in sql
                             for sql in db.calls[failed_start:] if sql != ca.post.READ_ROWS))
        self.assertEqual(3, rejection["unique_migration_count"])

    def test_fixture_requires_empty_targets_and_parameterized_writes_roll_back_on_failure(self):
        db = PostStorage(); ca.post.prepare_operator_fixture(db.connect, POST_UUID)
        original = copy.deepcopy(db.rows); count = len(db.writes)
        with self.assertRaises(ca.post.PostDeployError): ca.post.prepare_operator_fixture(db.connect, POST_UUID)
        self.assertEqual(original, db.rows); self.assertEqual(count, len(db.writes))
        db = PostStorage(); db.write_error = True
        with self.assertRaises(ca.post.PostDeployError) as caught:
            ca.post.prepare_operator_fixture(db.connect, POST_UUID)
        self.assertEqual([], db.rows); self.assertEqual(0, db.commits); self.assertEqual(1, db.rollbacks)
        self.assertNotIn("PRIVATE_SQL_ERROR", json.dumps(caught.exception.evidence))
        self.assertEqual(db.opened, db.closed)

    def test_nonempty_cron_pause_and_update_audit_drift_fail_preservation(self):
        _, _, positive, rejection = self.existing_scenario()
        for index, value in ((4, "0 18 2 * * ?"), (7, "0"), (11, "unexpected"), (12, "2026-10-21 00:00:00")):
            changed = copy.deepcopy(positive["after"]["rows"]); changed[0][index] = value
            with self.subTest(column=index), self.assertRaises(ca.post.PostDeployError):
                ca.post.verify_rows(positive["before"]["rows"], changed)
        for mutation in ("success", "extra_file", "bad_scalar", "drift", "unknown"):
            changed = copy.deepcopy(rejection)
            if mutation == "success": changed["rejected_migration"]["status"] = "PASS"
            if mutation == "extra_file": changed["rejected_migration"]["files"].append({})
            if mutation == "bad_scalar": changed["scalar_readback"]["actual"] = [1]
            if mutation == "drift": changed["after"]["rows"][0][12] = "changed"
            if mutation == "unknown": changed["rejected_migration"]["error_type"] = "RuntimeError"
            with self.subTest(mutation=mutation), self.assertRaises(ca.post.PostDeployError):
                ca.post.validate_commerce_rejection(changed, positive)

    def test_all_six_real_class_names_and_health_required(self):
        info, health, beans = live_documents()
        result = ca.post.validate_observation(info, health, beans, SHA)
        self.assertEqual(set(ca.post.BEANS), set(result["beans"]))
        for mutation in ("missing", "duplicate", "wrong_type", "source", "db"):
            info, health, beans = live_documents()
            key = next(iter(ca.post.BEANS))
            if mutation == "missing": del beans["contexts"]["app"]["beans"][key]
            if mutation == "duplicate": beans["contexts"]["other"] = {"beans": {key: {"type": ca.post.BEANS[key]}}}
            if mutation == "wrong_type": beans["contexts"]["app"]["beans"][key]["type"] = "fixture.Fake"
            if mutation == "source": info["git"]["commit"]["id"] = "c" * 40
            if mutation == "db": health["components"]["db"]["status"] = "DOWN"
            with self.subTest(mutation=mutation), self.assertRaises(ca.post.PostDeployError):
                ca.post.validate_observation(info, health, beans, SHA)

    def test_original_sql_15_statements_9_real_cursor_scalars_and_fresh_readbacks(self):
        db = PostStorage()
        result = ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
        self.assertEqual(("PASS", 15, 9), (result["status"], result["statement_count"], result["scalar_count"]))
        self.assertEqual(6, len(result["after"]["rows"]))
        self.assertEqual(6, db.opened)  # before, writer, three independent per-file reads, final
        self.assertEqual(db.opened, db.closed)
        self.assertTrue(all(x["actual"] == [1] for f in result["files"] for x in f["scalars"]))
        before = [row[:] for row in db.rows]
        replay = ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
        self.assertEqual(before, db.rows)
        self.assertTrue(replay["existing_rows_preserved"])

    def test_bad_scalar_and_sql_error_stop_immediately_and_retain_partial_evidence(self):
        for row in ((0,), ("1",), (1, 1), ()):
            db = PostStorage(); db.bad_scalar = row
            with self.subTest(row=row), self.assertRaises(ca.post.PostDeployError) as caught:
                ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
            self.assertEqual(1, caught.exception.evidence["statement_count"])
            self.assertFalse(any(sql.startswith("INSERT") for sql in db.calls))
            self.assertEqual(db.opened, db.closed)
        db = PostStorage(); db.write_error = True
        with self.assertRaises(ca.post.PostDeployError) as caught:
            ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
        self.assertNotIn("PRIVATE_SQL_ERROR", json.dumps(caught.exception.evidence))
        self.assertEqual(1, caught.exception.evidence["statement_count"])

    def test_original_sql_fingerprint_must_pass_before_any_database_connection(self):
        with tempfile.TemporaryDirectory() as source, mock.patch.object(ca.post, "SQL_FILES", (
                ("changed.sql", "0" * 64, 1, 1),)):
            folder = Path(source) / "chengyinhub-admin/sql"; folder.mkdir(parents=True)
            (folder / "changed.sql").write_text("SELECT 1;")
            connect = mock.Mock()
            with self.assertRaises(ca.post.PostDeployError):
                ca.post.register_jobs(source, connect, POST_UUID)
            connect.assert_not_called()

    def test_duplicate_targets_or_wrong_database_stop_before_writes(self):
        db = PostStorage(); ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
        db.rows.append([99] + db.rows[0][1:]); db.calls.clear()
        with self.assertRaises(ca.post.PostDeployError):
            ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
        self.assertFalse(any(sql.startswith("INSERT") for sql in db.calls))
        db = PostStorage(); db.uuid = "00000000-0000-0000-0000-000000000000"
        with self.assertRaises(ca.post.PostDeployError):
            ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
        self.assertFalse(any(sql.startswith("INSERT") for sql in db.calls))

    def test_operator_paused_rows_preserved_and_new_wrong_schedule_rejected(self):
        db = PostStorage(); ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
        original = [row[:] for row in db.rows]
        original[1][4], original[1][7] = "operator-cron", "1"
        ca.post.verify_rows(original, [row[:] for row in original])
        with self.assertRaises(ca.post.PostDeployError): ca.post.verify_rows(original, db.rows)
        for index, value in ((11, "unexpected-operator"), (12, "2026-10-21")):
            drifted = [row[:] for row in original]; drifted[1][index] = value
            with self.subTest(column=index), self.assertRaises(ca.post.PostDeployError):
                ca.post.verify_rows(original, drifted)
        changed = [row[:] for row in db.rows]; changed[0][4] = "wrong"
        with self.assertRaises(ca.post.PostDeployError): ca.post.verify_rows([], changed)

    def test_http_json_and_errors_cannot_leak_response_payload(self):
        response = mock.MagicMock(); response.__enter__.return_value = response
        response.status, response.read.return_value = 200, b'PRIVATE_RESPONSE_NOT_JSON'
        opener = mock.Mock(); opener.open.return_value = response
        with mock.patch.object(ca.urllib.request, "build_opener", return_value=opener):
            with self.assertRaises(ca.GateError) as caught: ca.postdeploy_http("/actuator/beans", "PRIVATE_TOKEN")
        self.assertNotIn("PRIVATE_RESPONSE", str(caught.exception))
        self.assertNotIn("PRIVATE_TOKEN", str(caught.exception))
        with self.assertRaises(ca.GateError): ca.postdeploy_http("https://outside.invalid")

    def run_control(self, stop_unknown=False, observation_failure=False, through="finance", stale=False):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        out = Path(temp.name); planned = ca.plan(SHA, through, "")
        ca.merge_receipt(out, {"plan": planned}); ca.stage(out, "schema", "PASS")
        process = mock.Mock(pid=123); process.poll.return_value = None
        proof = {"source_sha": SHA, "server_uuid": POST_UUID, "observed_at": ca.time.time() - (91 if stale else 0)}
        args = ca.argparse.Namespace(out=out, source=_HERE.parent, gate=_HERE.parent,
                                    work=out, app=out, jar=out / "jar")
        order = []
        def stop(p):
            order.append("stop")
            p.poll.return_value = None if stop_unknown else -15
        def register(*args):
            order.append("sql"); self.assertIsNotNone(process.poll())
            return {"status": "PASS", "statement_count": 15, "scalar_count": 9}
        with mock.patch.object(ca, "start_app", return_value=(process, {})), \
                mock.patch.object(ca, "probe_token", return_value="token") as login_probe, \
                mock.patch.object(ca, "fault_probe", return_value={}), \
                mock.patch.object(ca, "run_chains", return_value=[{"verdict": "PASS", "chain": "platform", "problems": []}]) as chains, \
                mock.patch.object(ca, "observe_postdeploy", side_effect=(ca.AdminReadbackError(observation_failure)
                                  if isinstance(observation_failure, str) else ca.GateError("bad bean")) if observation_failure else None,
                                  return_value=proof), \
                mock.patch.object(ca, "stop_app", side_effect=stop), \
                mock.patch.object(ca.post, "prepare_operator_fixture", return_value={"status": "PASS"}) as fixture, \
                mock.patch.object(ca.post, "characterize_commerce_rejection", return_value={"status": "PASS_EXPECTED_REJECTION"}) as reject, \
                mock.patch.object(ca.post, "register_jobs", side_effect=register) as sql:
            if stop_unknown or observation_failure or stale:
                with self.assertRaises(ca.GateError): ca.cmd_run(args, {})
                sql.assert_not_called()
                fixture.assert_not_called(); reject.assert_not_called()
                failed = ca.read_receipt(out)["stages"]["postdeploy"]
                self.assertEqual("FAIL", failed["status"])
                if isinstance(observation_failure, str):
                    self.assertEqual(observation_failure if re.fullmatch(r"ADMIN_[A-Z_]+", observation_failure) else None,
                                     failed["failure_code"])
            else:
                self.assertEqual(0, ca.cmd_run(args, {}))
                self.assertEqual(["stop", "sql"], order)
                self.assertTrue(ca.read_receipt(out)["stages"]["postdeploy"]["stopped_before_sql"])
            if through == "post_deploy":
                chains.assert_not_called(); login_probe.assert_not_called()
                if not (stop_unknown or observation_failure or stale):
                    fixture.assert_called_once(); reject.assert_called_once()
            else:
                fixture.assert_not_called(); reject.assert_not_called()

    def test_original_jvm_confirmed_stopped_before_sql(self): self.run_control()
    def test_unknown_stop_blocks_every_sql(self): self.run_control(stop_unknown=True)
    def test_failed_live_observation_stops_jvm_and_blocks_sql(self): self.run_control(observation_failure=True)
    def test_admin_failure_keeps_only_safe_fixed_code_and_blocks_sql(self):
        self.run_control(observation_failure="ADMIN_ROUTER_SQL_MISMATCH")
        self.run_control(observation_failure="private raw error payload")
    def test_post_only_does_not_repeat_chain_or_gameplay_fault_probe(self): self.run_control(through="post_deploy")
    def test_stale_live_proof_cannot_start_sql(self): self.run_control(stale=True)

    def test_observation_keeps_only_six_beans_and_same_pid_and_jar(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder); ca.stage(out, "explode", "PASS", jar_sha256="b" * 64)
            args = ca.argparse.Namespace(out=out, jar=out / "jar", app=out, gate=_HERE.parent)
            planned = ca.plan(SHA, "post_deploy", "")
            info, health, beans = live_documents()
            beans["contexts"]["app"]["beans"]["unrelated"] = {"secret": "PRIVATE_BEAN_DETAIL"}
            before = {"pid": 123, "starttime": "456", "command_sha256": "d" * 64, "listener_inodes": ["1234"]}
            for change in (None, "pid", "jar", "listener"):
                after = dict(before)
                if change == "pid": after["starttime"] = "789"
                if change == "listener": after["listener_inodes"] = ["789"]
                with mock.patch.object(ca, "verify_candidate_jar", return_value={"jar_sha256": "b" * 64}), \
                        mock.patch.object(ca, "java_command", return_value=["java"]), \
                        mock.patch.object(ca, "app_process_identity", side_effect=[before, after]), \
                        mock.patch.object(ca, "postdeploy_http", side_effect=[{"code": 200, "token": "PRIVATE_TOKEN"}, info, health, beans]), \
                        mock.patch.object(ca, "observe_admin", return_value={"status": "READONLY_ADMIN"}) as admin, \
                        mock.patch.object(ca.post, "read_state", return_value={"server_uuid": POST_UUID}), \
                        mock.patch.object(ca, "sha256_file", return_value="c" * 64 if change == "jar" else "b" * 64):
                    if change:
                        with self.assertRaises(ca.GateError): ca.observe_postdeploy(args, planned, mock.Mock())
                    else:
                        proof = ca.observe_postdeploy(args, planned, mock.Mock())
                        self.assertEqual(before, proof["process"])
                        self.assertEqual({"status": "READONLY_ADMIN"}, proof["admin"])
                        admin.assert_called_once()
                        self.assertNotIn("PRIVATE", json.dumps(proof))

    def test_pid_command_and_starttime_use_only_original_process(self):
        process = mock.Mock(pid=123); process.poll.return_value = None
        with tempfile.TemporaryDirectory() as temporary:
            proc = Path(temporary); folder = proc / "123"; folder.mkdir()
            (folder / "fd").mkdir(); (folder / "net").mkdir()
            (folder / "cmdline").write_bytes(b"java\0-cp\0fixture\0")
            (folder / "stat").write_text("123 (java) S " + "0 " * 18 + "456")
            fd = folder / "fd/1"; fd.symlink_to("socket:[1234]")
            header = "sl local_address rem_address st queues timer retransmit uid timeout inode\n"
            def tcp(table, address=None, state="0A", inode="1234"):
                row = ("0: " + address + " 00000000:0000 " + state + " 0:0 00:0 0 1000 0 " + inode + "\n") if address else ""
                (folder / "net" / table).write_text(header + row)
            with mock.patch.object(ca, "Path", return_value=proc):
                for table, address in (("tcp", "0100007F:1FA3"),
                                       ("tcp6", "0000000000000000FFFF00000100007F:1FA3")):
                    tcp("tcp"); tcp("tcp6"); tcp(table, address)
                    actual = ca.app_process_identity(process, ["java", "-cp", "fixture"])
                    self.assertEqual("456", actual["starttime"])
                    self.assertEqual(["1234"], actual["listener_inodes"])
                with self.assertRaises(ca.GateError): ca.app_process_identity(process, ["java", "other"])
                for table, address, state, inode in (
                    ("tcp", "00000000:1FA3", "0A", "1234"),
                    ("tcp6", "00000000000000000000000000000000:1FA3", "0A", "1234"),
                    ("tcp", "0100007F:1F90", "0A", "1234"),
                    ("tcp", "0100007F:1FA3", "01", "1234"),
                    ("tcp", "0100007F:1FA3", "0A", "9999")):
                    tcp("tcp"); tcp("tcp6"); tcp(table, address, state, inode)
                    with self.subTest(table=table, address=address, state=state, inode=inode), self.assertRaises(ca.GateError):
                        ca.app_process_identity(process, ["java", "-cp", "fixture"])
                fd.unlink(); tcp("tcp", "0100007F:1FA3")
                with self.assertRaises(ca.GateError): ca.app_process_identity(process, ["java", "-cp", "fixture"])
        process.poll.return_value = 1
        with self.assertRaises(ca.GateError): ca.app_process_identity(process, ["java"])



class PostRestartTest(unittest.TestCase):
    EPOCH = 1800000000
    def state(self):
        return {"roam": [[2, 1, 0]], "metrics": [["jvm.heap.used.ratio", "jvm", "start", "end", "OK", "0.1"]],
                "audit": [[ca.post.FIXTURE_ID, 0, ca.post.FIXTURE_ID, 9009]],
                "withdraw": [[ca.post.FIXTURE_ID, 9009, "1.00", 3, 0]],
                "settlement": [[ca.post.FIXTURE_ID, "1.00", "1.00"]],
                "commerce": [[ca.post.FIXTURE_ID, "TPLTM_9932001", 9009, "premium_template", 0, "1.00", 0, None, None, None]],
                "assets": [["0.00"]], "entitlements": []}

    def result(self):
        db = PostStorage()
        ca.post.register_jobs(_HERE.parent, db.connect, POST_UUID)
        jobs = [r[:7] + ["1"] + r[8:] for r in db.rows]
        state = self.state()
        def effect(target, n, negative=False):
            job = next(r for r in jobs if r[3] == target)
            before, after = copy.deepcopy(state), copy.deepcopy(state)
            if target == ca.post.ROAM_TARGET and n == 1: before["roam"] = [[0, 0, 0]]
            if target == "metricsProbeTask.run()" and n == 2: before["metrics"] = []
            kind = "COMMERCE_UNKNOWN" if target == ca.post.COMMERCE else "SETTLEMENT_MISMATCH" if negative else "OTHER"
            if negative: before["settlement"] = after["settlement"] = [[ca.post.FIXTURE_ID, "2.00", "1.00"]]
            row = {"target": target, "job_id": job[0], "baseline_log_id": 0,
                   "new_jvm_start_database_epoch": self.EPOCH,
                   "log": [n, "1" if target == ca.post.COMMERCE or negative else "0", self.EPOCH + 1, kind, job[1], job[2], target],
                   "before": before, "after": after,
                   "invocation": "ORIGINAL_MINUTE_CRON" if target == ca.post.ROAM_TARGET and n == 1 else "SINGLE_NORMAL_ADMIN_PUT"}
            if target == ca.post.COMMERCE:
                row["specific_cause"] = {"code": "COMMERCE_QUERY_UNCONFIRMED", "job_log_id": n,
                    "start_offset": 0, "end_offset": 100, "cause_class": "java.lang.IllegalStateException",
                    "source_frame": "MerchantCommerceService.reconcileOrder:177"}
            if target in ("auditBacklogMonitorTask.run()", "withdrawReconciliationTask.run()") or negative:
                row["alarm"] = {"value": "1", "ttl_ms": 10000 - n}
            return row
        targets = [ca.post.ROAM_TARGET, "metricsProbeTask.run()", "auditBacklogMonitorTask.run()",
                   "withdrawReconciliationTask.run()", "settlementReconciliationTask.run()", ca.post.COMMERCE]
        effects = [effect(t, i) for i, t in enumerate(targets, 1)]
        rejection = effect("settlementReconciliationTask.run()", 10, True)
        return {"status": "PASS", "schema": ca.SCHEMA, "jvm1_stopped": True, "jvm2_stopped": True,
                "jvm2_exit_code": 143, "jvm2_new_identity": True, "jvm2_started_database_epoch": self.EPOCH,
                "server_uuid": POST_UUID, "controlled_stop": True,
                "stop_precondition": {"pid": 124, "starttime": "457", "command_sha256": "d" * 64, "listener_inodes": ["790"]},
                "proof": {"server_uuid": POST_UUID, "observed_at": self.EPOCH + 1,
                          "process": {"pid": 124, "starttime": "457", "command_sha256": "d" * 64, "listener_inodes": ["790"]}},
                "clock": {"before_start": self.clock(), "after_live": self.clock(2), "before_stop": self.clock(20)},
                "payment_stub_binding": {"task": True, "service": True, "type": "com.chengyinhub.e2e.E2eWechatPayStub",
                                         "source_sha256": "62daf04bc0e6aac703e7536fe17b79999b86d92ddec722cfb88c59cadba87161"},
                "automatic_roam_observed": True, "fixture": {"status": "PASS", "schema": ca.SCHEMA, "jobs": jobs, "server_uuid": POST_UUID},
                "effects": effects, "roam_repeat": effect(ca.post.ROAM_TARGET, 7),
                "metrics_repeat": effect("metricsProbeTask.run()", 8),
                "audit_repeat": effect("auditBacklogMonitorTask.run()", 9), "settlement_rejection": rejection,
                "final_readback": copy.deepcopy(rejection["after"]), "after_stop_readback": copy.deepcopy(rejection["after"]),
                "final_jobs": copy.deepcopy(jobs), "after_stop_jobs": copy.deepcopy(jobs),
                "passive_queues": {"crm_export_tasks": 0, "account_deregistrations": 0},
                "local_files": {"crm_export_directory": "EMPTY_TASK_OWNED"},
                "after_stop_local_files": {"crm_export_directory": "EMPTY_TASK_OWNED"}}

    def clock(self, offset=0):
        return {"epoch": self.EPOCH + offset, "host_before": self.EPOCH + offset + 0.01,
                "host_after": self.EPOCH + offset + 0.02}

    def test_mode_is_separate_no_chains_or_fault_probe(self):
        self.assertEqual([], ca.plan(SHA, "post_restart", "")["chains"])
        with self.assertRaises(ca.GateError): ca.plan(SHA, "post_restart", "/api/play/checkin")
        self.assertEqual("post_deploy", ca.plan(SHA, "post_deploy", "")["through"])

    def seed_fixture(self):
        rows = []
        for i, fields in enumerate(ca.post.RESTART_SEED_ROWS.values(), 1):
            row = [i] + copy.deepcopy(fields)
            row[9] = "2026-10-21 00:00:01"
            row[12] = "2026-10-21 00:00:02" if row[11] == "admin" else None
            rows.append(row)
        clock = {"server_uuid": POST_UUID, "datetime": "2026-10-21 00:00:00",
                 "session_timezone": "SYSTEM", "system_timezone": "UTC"}
        fresh = {"build_exit": 0, "seed_exit": 0, "source_sha": ca.post.RESTART_SEED_SOURCE,
                 "clock_before": clock, "clock_after": dict(clock, datetime="2026-10-21 00:00:03"),
                 "containers": {"mysql": "cy-chain-1-1-mysql", "redis": "cy-chain-1-1-redis"}}
        return rows, fresh

    def test_preparation_clock_uses_mysql_system_schema_and_closes_its_read_only_connection(self):
        connection = mock.Mock()
        cursor = connection.cursor.return_value
        cursor.fetchone.return_value = (POST_UUID, "2026-10-21 00:00:00", "SYSTEM", "UTC")
        with mock.patch.object(ca, "connect", return_value=connection) as connect:
            clock = ca.restart_seed_clock()
        connect.assert_called_once_with("mysql")
        self.assertEqual(POST_UUID, clock["server_uuid"])
        self.assertEqual("2026-10-21 00:00:00", clock["datetime"])
        self.assertIn("DATE_FORMAT(NOW()", cursor.execute.call_args.args[0])
        cursor.close.assert_called_once()
        connection.close.assert_called_once()
        with self.assertRaises(ca.GateError):
            ca.connect("unrelated_schema")

    def test_schema_captures_database_window_before_builder_and_after_seed_only_for_restart(self):
        _, fresh = self.seed_fixture()
        for mode in ("post_restart", "post_deploy"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as out:
                events = []
                receipt = {"plan": {"through": mode, "source_sha": ca.post.RESTART_SEED_SOURCE},
                           "stages": {"storage": {"containers": fresh["containers"]}}}
                def run(command, **kwargs):
                    events.append("build" if "build_schema.py" in command[1] else "seed")
                    return subprocess.CompletedProcess(command, 0, "迁移阶段问题: 0".encode())
                def clock():
                    events.append("clock")
                    return fresh["clock_before"] if events.count("clock") == 1 else fresh["clock_after"]
                def prepare(connect, observed):
                    events.append("prepare")
                    self.assertEqual(fresh, observed)
                    return {"status": "PASS"}
                def query(sql, args):
                    return [(9 if "ums_member" in sql else 5 if "mms_merchant" in sql else
                             2 if "club " in sql else 1 if "sys_config" in sql else 378,)]
                args = mock.Mock(out=out, source=_HERE.parent)
                with mock.patch.object(ca, "require_stage"), mock.patch.object(ca, "read_receipt", return_value=receipt), \
                     mock.patch.object(ca, "restart_seed_clock", side_effect=clock), \
                     mock.patch.object(ca.subprocess, "run", side_effect=run), \
                     mock.patch.object(ca, "query", side_effect=query), mock.patch.object(ca, "execute", return_value=9), \
                     mock.patch.object(ca.post, "prepare_restart_seed", side_effect=prepare), mock.patch.object(ca, "stage"):
                    self.assertEqual(0, ca.cmd_schema(args, {}))
                self.assertEqual(["clock", "build", "seed", "clock", "prepare"] if mode == "post_restart"
                                 else ["build", "seed"], events)

    def test_full_seed_contract_deletes_exact_22_rows_and_independently_reads_empty(self):
        rows, fresh = self.seed_fixture()
        db = PostStorage()
        db.rows = copy.deepcopy(rows)
        with mock.patch.object(ca.post, "read_state", return_value={"server_uuid": POST_UUID}), \
             mock.patch.object(ca.post, "passive_queues", return_value={}):
            result = ca.post.prepare_restart_seed(db.connect, fresh)
        self.assertEqual(22, len(db.writes))
        self.assertEqual(1, db.commits)
        self.assertEqual(0, db.rollbacks)
        self.assertEqual([], db.rows)
        for (sql, params), row in zip(db.writes, rows):
            self.assertEqual(13, sql.count("<=> %s"))
            self.assertEqual(tuple(row), params)
        self.assertEqual([r[0] for r in rows], result["removed_seed_job_ids"])
        self.assertEqual(22, len(result["removed_seed_fingerprints"]))
        self.assertEqual(0, result["jobs_before_jvm1"])
        self.assertEqual(2, db.calls.count(ca.post.ALL_JOBS + " FOR UPDATE"))
        self.assertEqual(2, db.calls.count(ca.post.ALL_JOBS))
        self.assertIn("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ", db.calls)

    def test_seed_contract_rejects_extra_missing_duplicate_identity_and_every_static_column(self):
        rows, fresh = self.seed_fixture()
        cases = [rows[:-1], rows + [rows[0]], rows[:1] * 22,
                 [[False] + rows[0][1:]] + rows[1:],
                 [[0] + rows[0][1:]] + rows[1:],
                 [rows[0][:-1]] + rows[1:]]
        duplicate_id = copy.deepcopy(rows)
        duplicate_id[1][0] = duplicate_id[0][0]
        cases.append(duplicate_id)
        extra = copy.deepcopy(rows[0]); extra[0] = 999; extra[3] = "unknown.run()"
        cases.append(rows + [extra])
        for index, row in enumerate(rows):
            for column in list(range(1, 9)) + [10, 11]:
                changed = copy.deepcopy(rows)
                changed[index][column] = str(row[column]) + "-drift"
                cases.append(changed)
        for changed in cases:
            with self.subTest(rows=len(changed), first=changed[0][:4]), \
                 mock.patch.object(ca.post, "read_state", return_value={"server_uuid": POST_UUID}), \
                 mock.patch.object(ca.post, "restart_rows", return_value=changed), \
                 mock.patch.object(ca.post, "fixture_write") as write:
                with self.assertRaises(ca.post.PostDeployError):
                    ca.post.prepare_restart_seed(None, fresh)
                write.assert_not_called()

    def test_seed_contract_rejects_invalid_stale_create_and_update_times_before_writes(self):
        rows, fresh = self.seed_fixture()
        for index, row in enumerate(rows):
            bad_created = [None, "2026-02-30 00:00:01", "1970-01-01 00:00:00",
                           "2026-10-20 23:59:59", "2026-10-21 00:00:04"]
            for column, value in [(9, value) for value in bad_created] + (
                    [(12, None), (12, "2026-10-21 00:00:00"), (12, "2026-10-21 00:00:04")]
                    if row[11] == "admin" else [(12, "2026-10-21 00:00:02")]):
                changed = copy.deepcopy(rows); changed[index][column] = value
                with self.subTest(target=row[3], column=column, value=value), \
                     mock.patch.object(ca.post, "read_state", return_value={"server_uuid": POST_UUID}), \
                     mock.patch.object(ca.post, "restart_rows", return_value=changed), \
                     mock.patch.object(ca.post, "fixture_write") as write:
                    with self.assertRaises(ca.post.PostDeployError):
                        ca.post.prepare_restart_seed(None, fresh)
                    write.assert_not_called()

    def test_seed_provenance_database_clock_and_timezone_cannot_be_forged(self):
        rows, fresh = self.seed_fixture()
        cases = [{}, dict(fresh, source_sha=SHA), dict(fresh, build_exit=1), dict(fresh, seed_exit=1),
                 dict(fresh, containers={"mysql": "other", "redis": "cy-chain-1-1-redis"})]
        for field, value in [("server_uuid", "foreign"), ("session_timezone", "+01:00"),
                             ("system_timezone", "other"), ("datetime", "2026-10-20 00:00:00"),
                             ("datetime", None)]:
            cases.append(dict(fresh, clock_after=dict(fresh["clock_after"], **{field: value})))
        for bad in cases:
            with self.subTest(fresh=bad), mock.patch.object(ca.post, "read_state", return_value={"server_uuid": POST_UUID}), \
                 mock.patch.object(ca.post, "restart_rows", return_value=rows), \
                 mock.patch.object(ca.post, "fixture_write") as write:
                with self.assertRaises(ca.post.PostDeployError):
                    ca.post.prepare_restart_seed(None, bad)
                write.assert_not_called()

    def test_seed_locked_drift_mid_delete_and_extra_row_roll_back_before_commit(self):
        rows, _ = self.seed_fixture()
        where = " AND ".join("`%s` <=> %%s" % c for c in ca.post.JOB_COLUMNS)
        writes = [("DELETE FROM sys_job WHERE " + where, tuple(row)) for row in rows]
        for scenario in ("locked_drift", "cas_zero", "extra_before_commit"):
            db = PostStorage(); db.rows = copy.deepcopy(rows)
            if scenario == "locked_drift": db.rows[0][10] += "-drift"
            if scenario == "cas_zero": db.delete_failure_after = 1
            if scenario == "extra_before_commit": db.extra_after_deletes = True
            before = copy.deepcopy(db.rows)
            with self.subTest(scenario=scenario), self.assertRaises(ca.post.PostDeployError):
                ca.post.fixture_write(db.connect, POST_UUID, writes, expected_jobs=rows)
            self.assertEqual(0, db.commits)
            self.assertEqual(1, db.rollbacks)
            self.assertEqual(before, db.rows)

    def test_seed_postcommit_nonempty_or_foreign_identity_cannot_pass(self):
        rows, fresh = self.seed_fixture()
        with mock.patch.object(ca.post, "read_state", return_value={"server_uuid": POST_UUID}), \
             mock.patch.object(ca.post, "restart_rows", side_effect=[rows, [rows[0]]]), \
             mock.patch.object(ca.post, "fixture_write"):
            with self.assertRaisesRegex(ca.post.PostDeployError, "restart_seed_removal_unknown"):
                ca.post.prepare_restart_seed(None, fresh)
        db = PostStorage(); db.uuid = "foreign"
        with self.assertRaisesRegex(ca.post.PostDeployError, "post_database_identity_mismatch"):
            ca.post.restart_rows(db.connect, POST_UUID, ca.post.ALL_JOBS)

    def test_required_effects_and_repeats_pass_and_fake_evidence_fails(self):
        good = self.result(); ca.post.validate_restart_result(good)
        for mutate in (
            lambda r: r.update(jvm2_stopped=False),
            lambda r: r["effects"].pop(),
            lambda r: r["effects"][0]["after"].update(roam=[[0, 0, 0]]),
            lambda r: r["effects"][0]["log"].__setitem__(0, 0),
            lambda r: r["effects"][1]["after"].update(metrics=[]),
            lambda r: r["effects"][1]["after"]["metrics"][0].__setitem__(5, "NaN"),
            lambda r: r["effects"][2].pop("alarm"),
            lambda r: r["effects"][3]["log"].__setitem__(4, "foreign-name"),
            lambda r: r["effects"][5]["log"].__setitem__(3, "OTHER"),
            lambda r: r["metrics_repeat"]["after"]["metrics"][0].__setitem__(2, "another-window"),
            lambda r: r["audit_repeat"]["alarm"].update(ttl_ms=600000),
            lambda r: r["settlement_rejection"]["log"].__setitem__(3, "OTHER"),
            lambda r: r["after_stop_readback"].update(assets=[["99.00"]]),
            lambda r: r["after_stop_jobs"].append(copy.deepcopy(r["after_stop_jobs"][0])),
        ):
            with self.subTest(mutate=mutate):
                changed = copy.deepcopy(good); mutate(changed)
                with self.assertRaises((ca.post.PostDeployError, KeyError)): ca.post.validate_restart_result(changed)

    def test_log_must_be_new_and_bound_to_exact_names_target_and_new_jvm_time(self):
        job = self.result()["fixture"]["jobs"][0]
        good = [11, "0", 1001, "OTHER", job[1], job[2], job[3]]
        for rows, valid in (([good], True), ([], True), ([good, good], False),
                            ([[10] + good[1:]], False), ([good[:2] + [999] + good[3:]], False),
                            ([good[:4] + ["foreign"] + good[5:]], False)):
            with mock.patch.object(ca.post, "restart_rows", return_value=rows) as query:
                if valid: ca.post.restart_log(None, POST_UUID, job, 10, 1000)
                else:
                    with self.assertRaises(ca.post.PostDeployError): ca.post.restart_log(None, POST_UUID, job, 10, 1000)
                self.assertEqual((job[3], job[1], job[2]), query.call_args.args[3][-3:])

    def test_restart_config_pins_background_limits_webhooks_and_owned_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = ca.argparse.Namespace(source=Path(tmp) / "source", work=Path(tmp) / "work")
            def digest(path): return ca.RESTART_BACKGROUND_SOURCE[str(Path(path).relative_to(args.source))]
            with mock.patch.object(ca, "sha256_file", side_effect=digest):
                config = ca.restart_config(args)
                text = config.read_text()
                for line in ("chengyin.jobs.enabled=true", "withdrawal.pii.backfill-limit=0", "member.password.legacy-hash-limit=0",
                             "alarm.webhook=", "alarm.failure-webhook=", "chengyin.status.feed-file=" + str(args.work.resolve() / "restart-status/chengyin-status.json")):
                    self.assertIn(line + "\n", text)
                self.assertEqual(0o600, config.stat().st_mode & 0o777)
                with self.assertRaises(ca.GateError): ca.restart_config(args)
            with mock.patch.object(ca, "sha256_file", return_value="wrong"):
                with self.assertRaises(ca.GateError): ca.restart_config(args)

    def test_private_crm_directory_refuses_symlink_and_any_existing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertEqual({"crm_export_directory": "EMPTY_TASK_OWNED"}, ca.restart_local_files(root))
            crm = root / "upload-private/merchant-crm-export"
            crm.mkdir(parents=True)
            (crm / "foreign.xlsx").write_text("must preserve")
            with self.assertRaises(ca.GateError): ca.restart_local_files(root)
            self.assertEqual("must preserve", (crm / "foreign.xlsx").read_text())
            (crm / "foreign.xlsx").unlink()
            crm.rmdir(); crm.symlink_to(root, target_is_directory=True)
            with self.assertRaises(ca.GateError): ca.restart_local_files(root)

    def test_fresh_effect_writes_are_parameterized_exact_rows_and_unknown_queue_refuses(self):
        good = self.result()
        jobs = [row[:7] + ["0"] + row[8:] for row in good["fixture"]["jobs"]]
        calls = []
        def rows(c, server, sql, params=()):
            if sql == ca.post.ALL_JOBS:
                return jobs if not calls else good["fixture"]["jobs"]
            if "merchant_crm_export_task" in sql: return [[0, 0]]
            if "COUNT(*) FROM audit_task" in sql: return [[0, 0, 0, 0, 0, 0]]
            raise AssertionError(sql)
        def write(c, server, statements):
            for sql, params in statements:
                self.assertEqual(sql.count("%s"), len(params))
                self.assertNotIn(str(ca.post.FIXTURE_ID), sql)
            calls.append(statements)
        with mock.patch.object(ca.post, "restart_rows", side_effect=rows), \
             mock.patch.object(ca.post, "fixture_write", side_effect=write), \
             mock.patch.object(ca.post, "restart_effect_state", return_value=dict(self.state(), roam=[[0, 0, 0]], metrics=[])):
            fixture = ca.post.prepare_restart_effects(None, POST_UUID)
            self.assertEqual(2, len(calls)); self.assertEqual(6, len(calls[0])); self.assertEqual(7, len(calls[1]))
            self.assertEqual(6, len(fixture["jobs"]))
        with mock.patch.object(ca.post, "restart_rows", return_value=[[1, 0]]):
            with self.assertRaises(ca.post.PostDeployError): ca.post.passive_queues(None, POST_UUID)

    def test_live_and_summary_job_definitions_refuse_every_immutable_column_drift(self):
        good = self.result()
        jobs = good["fixture"]["jobs"]
        ca.post.verify_restart_jobs(jobs, copy.deepcopy(jobs))
        roam = next(i for i, row in enumerate(jobs) if row[3] == ca.post.ROAM_TARGET)
        resumed = copy.deepcopy(jobs); resumed[roam][7] = "0"; resumed[roam][12] = "2026-10-21 12:34:56"
        ca.post.verify_restart_jobs(jobs, resumed, "0")
        with self.assertRaises(ca.post.PostDeployError): ca.post.verify_restart_jobs(jobs, resumed)
        for index, job in enumerate(jobs):
            for col in range(13):
                if index == roam and col == 12: continue
                changed = copy.deepcopy(jobs); changed[index][col] = "DRIFT"
                with self.subTest(target=job[3], column=ca.post.JOB_COLUMNS[col]):
                    with self.assertRaises(ca.post.PostDeployError): ca.post.verify_restart_jobs(jobs, changed)
                    result = copy.deepcopy(good)
                    result["final_jobs"] = result["after_stop_jobs"] = changed
                    with self.assertRaises(ca.post.PostDeployError): ca.post.validate_restart_result(result)
        paused = copy.deepcopy(resumed); paused[roam][7] = "1"
        good["final_jobs"] = good["after_stop_jobs"] = paused
        ca.post.validate_restart_result(good)

    def test_commerce_projection_covers_actual_paid_order_and_subscription_writes(self):
        state = self.state()
        order = dict(zip(("id","order_sn","member_id","biz_type","status","pay_amount","pay_type",
                          "payment_transaction_id","payment_time","update_time"), state["commerce"][0]))
        subscription = {"id": 1, "merchant_id": 9009, "subscription_type": "premium_template",
                        "start_date": "2026-01-01", "end_date": "2026-01-02", "max_usage": 1,
                        "used_count": 0, "is_active": 1, "remark": None,
                        "create_time": "2026-01-01", "update_time": "2026-01-01"}
        selected = {}
        def query(c, server, sql, params=()):
            for table, row in (("oms_order", order), ("merchant_subscription", subscription)):
                if "FROM " + table in sql:
                    cols = sql.split(" FROM ")[0][7:].split(","); selected[table] = cols
                    return [[row[col.strip()] for col in cols]]
            if "roam_presence" in sql: return state["roam"]
            for table,key in (("metrics_snapshot","metrics"),("audit_task","audit"),
                              ("member_cash_withdraw","withdraw"),("settlement_batch","settlement"),("ums_member","assets")):
                if "FROM " + table in sql: return state[key]
            raise AssertionError(sql)
        with mock.patch.object(ca.post, "restart_rows", side_effect=query):
            before = ca.post.restart_effect_state(None, POST_UUID)
            self.assertEqual(set(subscription), set(selected["merchant_subscription"]))
            self.assertTrue({"pay_type", "payment_transaction_id", "payment_time", "update_time"} <= set(selected["oms_order"]))
            for row, fields in ((order, ("pay_type", "payment_transaction_id", "payment_time", "update_time")),
                                (subscription, ("start_date", "end_date", "max_usage", "used_count", "is_active", "update_time"))):
                for field in fields:
                    original = row[field]; row[field] = "DRIFT"
                    after = ca.post.restart_effect_state(None, POST_UUID)
                    with self.subTest(field=field), self.assertRaises(ca.post.PostDeployError):
                        ca.post.validate_restart_effect(ca.post.COMMERCE, [1,"1",1001,"COMMERCE_UNKNOWN"], before, after)
                    row[field] = original

    def test_unknown_put_stops_before_readback_and_never_resends(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp); args = ca.argparse.Namespace(out=out, app=out, work=out, jar=out / "jar", gate=_HERE.parent)
            ca.stage(out, "storage", "PASS", containers={"mysql": "cy-chain-1-1-mysql", "redis": "cy-chain-1-1-redis"})
            fixture = self.result()["fixture"]; fixture.update(server_uuid=POST_UUID, before=self.state())
            events = []; process = mock.Mock(); process.returncode = None; process.poll.side_effect = lambda: process.returncode
            first = mock.Mock(); first.poll.return_value = 143
            proof = {"server_uuid": POST_UUID, "jar_sha256": "b" * 64,
                     "process": {"pid": 124, "starttime": "new", "command_sha256": "d" * 64, "listener_inodes": ["9"]}}
            def identity(*unused):
                if process.returncode is not None: raise ca.GateError("gone")
                return proof["process"]
            def stop(unused, require_alive=False): events.append("stop"); process.returncode = 143
            def query(c, u, sql, params=()):
                if sql == "SELECT UNIX_TIMESTAMP()": return [[1000]]
                if sql == ca.post.ALL_JOBS: return fixture["jobs"]
                if "MAX(job_log_id)" in sql: return [[0]]
                if "IS_USED_LOCK" in sql: return [[None]]
                raise AssertionError(sql)
            def state(*unused):
                events.append("after_stop_readback" if process.returncode is not None else "before_put")
                return self.state()
            def http(path, *unused, **kwargs):
                if path == "/login": return {"token": "fixture-token"}
                if path == "/actuator/beans": return {"contexts": {"a": {"beans": {
                    "e2eWechatPayStub": {"type": "com.chengyinhub.e2e.E2eWechatPayStub"},
                    "merchantCommerceReconcileTask": {"dependencies": ["e2eWechatPayStub"]},
                    "merchantCommerceService": {"dependencies": ["e2eWechatPayStub"]}}}}}
                events.append("put_unknown"); raise TimeoutError("PRIVATE_TRANSPORT_DETAIL")
            with mock.patch.object(ca.post, "prepare_restart_effects", return_value=fixture), \
                 mock.patch.object(ca.post, "restart_rows", side_effect=query), mock.patch.object(ca.post, "restart_effect_state", side_effect=state), \
                 mock.patch.object(ca, "start_app", return_value=(process, {})), mock.patch.object(ca, "observe_postdeploy", return_value=proof), \
                 mock.patch.object(ca, "java_command", return_value=["java", "fixture"]), mock.patch.object(ca, "app_process_identity", side_effect=identity), \
                 mock.patch.object(ca, "sha256_file", side_effect=lambda p: "62daf04bc0e6aac703e7536fe17b79999b86d92ddec722cfb88c59cadba87161" if str(p).endswith(".stub") else "b" * 64), \
                 mock.patch.object(ca.post, "restart_clock", return_value=self.clock()), mock.patch.object(ca, "postdeploy_http", side_effect=http), \
                 mock.patch.object(ca, "stop_app", side_effect=stop):
                with self.assertRaises(ca.GateError):
                    ca.run_post_restart(args, {}, ca.plan(SHA, "post_restart", ""), {"server_uuid": POST_UUID, "process": {"pid": 123}}, first, out / "fixture")
            self.assertEqual(1, events.count("put_unknown"))
            self.assertLess(events.index("put_unknown"), events.index("stop"))
            self.assertLess(events.index("stop"), events.index("after_stop_readback"))
            receipt = ca.read_receipt(out)["stages"]["post_restart"]
            self.assertEqual("FAIL", receipt["status"])
            self.assertNotIn("PRIVATE_TRANSPORT_DETAIL", json.dumps(receipt))

    def test_clock_uuid_and_summary_refuse_zero_stale_foreign_and_future_logs(self):
        with mock.patch.object(ca.post, "restart_rows", return_value=[[self.EPOCH]]) as rows, \
             mock.patch.object(ca.post.time, "time", side_effect=[self.EPOCH + .01, self.EPOCH + .02]):
            self.assertEqual(self.clock(), ca.post.restart_clock(None, POST_UUID))
            self.assertEqual(POST_UUID, rows.call_args.args[1])
        for clock in ({"epoch": 0, "host_before": self.EPOCH, "host_after": self.EPOCH},
                      {"epoch": self.EPOCH - 30, "host_before": self.EPOCH, "host_after": self.EPOCH},
                      {"epoch": self.EPOCH, "host_before": self.EPOCH, "host_after": self.EPOCH + 31}):
            with self.assertRaises(ca.post.PostDeployError): ca.post.validate_restart_clock(clock)
        for mutate in (lambda r: r["fixture"].update(server_uuid="foreign"),
                       lambda r: r["clock"]["before_start"].update(epoch=0),
                       lambda r: r["clock"]["after_live"].update(epoch=self.EPOCH - 30),
                       lambda r: r["proof"].update(observed_at=self.EPOCH - 90),
                       lambda r: r["effects"][0]["log"].__setitem__(2, self.EPOCH + 100),
                       lambda r: r.update(controlled_stop=False)):
            result = self.result(); mutate(result)
            with self.assertRaises(ca.post.PostDeployError): ca.post.validate_restart_result(result)
        stale = self.result(); stale["jvm2_started_database_epoch"] = 0
        for effect in stale["effects"] + [stale[k] for k in ("roam_repeat", "metrics_repeat", "audit_repeat", "settlement_rejection")]:
            effect["new_jvm_start_database_epoch"] = 0; effect["log"][2] = 1
        with self.assertRaises(ca.post.PostDeployError): ca.post.validate_restart_result(stale)

    def test_commerce_specific_new_log_cause_not_generic_or_technical_failure(self):
        text = ("java.lang.IllegalStateException: 商业化查单补偿失败 1 单\n"
                "Caused by: java.lang.IllegalStateException: 商业化查单结果无法确认\n"
                "    at com.chengyinhub.business.service.impl.MerchantCommerceService.reconcileOrder(MerchantCommerceService.java:177)\n")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "application.log"; prefix = "old fixture output\n"
            path.write_text(prefix + text)
            result = ca.post.commerce_cause(path, len(prefix.encode()), 6)
            self.assertEqual(6, result["job_log_id"]); self.assertNotIn("raw", result)
            for bad in (text.replace("商业化查单结果无法确认", "SQL fixture failure"),
                        text.replace("MerchantCommerceService.java:177", "OtherService.java:177"),
                        "java.lang.IllegalStateException: 商业化查单补偿失败 1 单\n",
                        text + "Caused by: java.sql.SQLException: fixture-only\n"):
                path.write_text(prefix + bad)
                with self.assertRaises(ca.post.PostDeployError): ca.post.commerce_cause(path, len(prefix.encode()), 6)
            path.write_text(text + prefix)
            with self.assertRaises(ca.post.PostDeployError): ca.post.commerce_cause(path, len(text.encode()), 6)
        result = self.result()
        commerce = next(e for e in result["effects"] if e["target"] == ca.post.COMMERCE)
        for mutate in (lambda r: r.pop("specific_cause"), lambda r: r["specific_cause"].update(job_log_id=999)):
            bad = copy.deepcopy(result); item = next(e for e in bad["effects"] if e["target"] == ca.post.COMMERCE); mutate(item)
            with self.assertRaises(ca.post.PostDeployError): ca.post.validate_restart_result(bad)
        result["payment_stub_binding"]["service"] = False
        with self.assertRaises(ca.post.PostDeployError): ca.post.validate_restart_result(result)

    def test_controlled_stop_rejects_dead_process_but_cleanup_preserves_exit(self):
        dead = mock.Mock(returncode=-9); dead.poll.return_value = -9
        with self.assertRaises(ca.GateError): ca.stop_app(dead, require_alive=True)
        dead.terminate.assert_not_called()
        self.assertEqual(-9, ca.stop_app(dead)); dead.terminate.assert_not_called()
        alive = mock.Mock(returncode=-15); alive.poll.return_value = None
        ca.stop_app(alive, require_alive=True); alive.terminate.assert_called_once(); alive.wait.assert_called_once()

    def test_lifecycle_final_readback_crash_is_fail_and_exact_cleanup_still_runs(self):
        for crash in (False, True):
            with self.subTest(crash=crash), tempfile.TemporaryDirectory() as tmp:
                out = Path(tmp); args = ca.argparse.Namespace(out=out, app=out, work=out, jar=out / "jar", gate=_HERE.parent)
                ca.stage(out, "storage", "PASS", containers={"mysql": "cy-chain-1-1-mysql", "redis": "cy-chain-1-1-redis"})
                good = self.result(); fixture = copy.deepcopy(good["fixture"]); current = copy.deepcopy(fixture["jobs"])
                proof = dict(good["proof"], jar_sha256="b" * 64)
                process = mock.Mock(returncode=None)
                process.poll.side_effect = lambda: process.returncode
                process.terminate.side_effect = lambda: setattr(process, "returncode", -15)
                process.wait.side_effect = lambda **kw: process.returncode
                first = mock.Mock(); first.poll.return_value = -15
                sequence = [good["effects"][0], good["roam_repeat"], good["effects"][1], good["metrics_repeat"],
                            *good["effects"][2:], good["audit_repeat"], good["settlement_rejection"]]
                states = [copy.deepcopy(state) for effect in sequence for state in (effect["before"], effect["after"])]
                states += [good["final_readback"], good["after_stop_readback"]]
                logs = [copy.deepcopy(e["log"]) for e in sequence]
                for log in logs:
                    if log[-1] == ca.post.COMMERCE: log[3] = "COMMERCE_AGGREGATE_FAILURE"
                reads = []
                def state(*unused):
                    reads.append(1); result = states.pop(0)
                    if crash and len(reads) == 21: process.returncode = -9
                    return result
                def identity(*unused):
                    if process.returncode is not None: raise ca.GateError("gone")
                    return proof["process"]
                def query(c,u,sql,params=()):
                    if sql == ca.post.ALL_JOBS: return copy.deepcopy(current)
                    if "MAX(job_log_id)" in sql: return [[0]]
                    if "SELECT status FROM sys_job" in sql: return [["1"]]
                    if "IS_USED_LOCK" in sql: return [[None]]
                    raise AssertionError(sql)
                def start(*unused,**kw):
                    (out / "post_restart/application.log").write_text("")
                    return process, {}
                def http(path,*unused,**kw):
                    if path == "/login": return {"token": "fixture"}
                    if path == "/actuator/beans": return {"contexts": {"a": {"beans": {
                        "e2eWechatPayStub": {"type": "com.chengyinhub.e2e.E2eWechatPayStub"},
                        "merchantCommerceReconcileTask": {"dependencies": ["e2eWechatPayStub"]},
                        "merchantCommerceService": {"dependencies": ["e2eWechatPayStub"]}}}}}
                    job = kw["job"]
                    if path.endswith("changeStatus"):
                        row = next(r for r in current if r[0] == job["jobId"]); row[7] = job["status"]
                    if path.endswith("run") and job["jobId"] == next(r[0] for r in current if r[3] == ca.post.COMMERCE):
                        (out / "post_restart/application.log").write_text(
                            "java.lang.IllegalStateException: 商业化查单补偿失败 1 单\n"
                            "Caused by: java.lang.IllegalStateException: 商业化查单结果无法确认\n"
                            " at com.chengyinhub.business.service.impl.MerchantCommerceService.reconcileOrder(MerchantCommerceService.java:177)\n")
                    return {"code": 200}
                with mock.patch.object(ca.post,"prepare_restart_effects",return_value=fixture), \
                     mock.patch.object(ca.post,"restart_clock",side_effect=[self.clock(),self.clock(2),self.clock(20)]), \
                     mock.patch.object(ca.post,"restart_rows",side_effect=query), mock.patch.object(ca.post,"restart_effect_state",side_effect=state), \
                     mock.patch.object(ca.post,"restart_log",side_effect=lambda *a: logs.pop(0)), mock.patch.object(ca.post,"fixture_write"), \
                     mock.patch.object(ca.post,"passive_queues",return_value={"crm_export_tasks":0,"account_deregistrations":0}), \
                     mock.patch.object(ca,"start_app",side_effect=start), mock.patch.object(ca,"observe_postdeploy",return_value=proof), \
                     mock.patch.object(ca,"java_command",return_value=["fixture"]), mock.patch.object(ca,"app_process_identity",side_effect=identity), \
                     mock.patch.object(ca,"postdeploy_http",side_effect=http), mock.patch.object(ca.time,"time",return_value=self.EPOCH+10), \
                     mock.patch.object(ca,"docker",side_effect=lambda *a: "1" if a[-2]=="GET" else "10000"), \
                     mock.patch.object(ca,"sha256_file",side_effect=lambda p: "62daf04bc0e6aac703e7536fe17b79999b86d92ddec722cfb88c59cadba87161" if str(p).endswith(".stub") else "b"*64):
                    if crash:
                        with self.assertRaises(ca.GateError): ca.run_post_restart(args,{}, {}, {"server_uuid":POST_UUID,"process":{"pid":123}},first,out/"fixture")
                    else: ca.run_post_restart(args,{}, {}, {"server_uuid":POST_UUID,"process":{"pid":123}},first,out/"fixture")
                result = ca.read_receipt(out)["stages"]["post_restart"]["result"]
                self.assertEqual("FAIL" if crash else "PASS", result["status"])
                self.assertEqual(22, len(reads))
                self.assertEqual(0 if crash else 1, process.terminate.call_count)
                if crash: self.assertEqual(-9, result["jvm2_exit_code"])

    def test_summary_cannot_pass_restart_mode_without_complete_restart_stage(self):
        original = SummaryTest(); original.setUp()
        try:
            ca.merge_receipt(original.out, {"plan": ca.plan(SHA, "post_restart", "")})
            self.assertEqual("FAIL", ca.summarize(original.out)["verdict"])
            result = self.result()
            proof = copy.deepcopy(ca.read_receipt(original.out)["stages"]["postdeploy"]["proof"])
            proof["process"].update(pid=124, starttime="457", listener_inodes=["790"])
            proof["observed_at"] = self.EPOCH + 1
            result["proof"] = proof
            ca.stage(original.out, "post_restart", "PASS", result=result)
            self.assertEqual("PASS", ca.summarize(original.out)["verdict"])
            result["effects"][0]["after"]["roam"] = [[0, 0, 0]]
            ca.stage(original.out, "post_restart", "PASS", result=result)
            self.assertEqual("FAIL", ca.summarize(original.out)["verdict"])
        finally: original.tearDown()


if __name__ == "__main__":
    unittest.main()
