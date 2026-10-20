#!/usr/bin/env python3
"""chain_acceptance 的红绿负控：证明「退出码 0 但没观测到 / 回读不符 / 重复发放 / 串库 / 非 master 候选 /
注入没生效 / 工作流被放宽」都会转红，而不是只在注释里这么写。"""
import importlib.util
import json
import re
import subprocess
import tempfile
import unittest
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
    if options != list(ca.CHAIN_NAMES):
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


if __name__ == "__main__":
    unittest.main()
