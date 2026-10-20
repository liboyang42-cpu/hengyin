#!/usr/bin/env python3
"""候选真 HTTP 业务验收（手动入口）：把 5-1 链路搬到 GitHub 托管 runner 上真跑。

由 .github/workflows/chain-acceptance.yml 调用；本机只跑负控 ci/test_chain_acceptance.py。

  plan          校验 SOURCE_SHA / THROUGH / FAIL_PATH，给出要跑的链路
  precheck      候选必须基于 master（与 origin/master 有共同祖先）、pom java.version 映射到 JDK 21
  explode       解开候选 jar，把两个 e2e 桩（支付通道、故障注入）编进 exploded classes
  storage-start 起一次性 MySQL(127.0.0.1:3316) / Redis(127.0.0.1:6379)，写 redis-cli 垫片并自检
  schema        用候选自己的 build_schema.py + seed_fixtures.py 建 cy_e2e_51，再直读 SQL 核对
  run           起应用（夹具配置 + 每次随机生成的合成密钥）→ 按序跑链路 → 每条写回执 → 停应用
  storage-stop  导出容器日志并删除容器
  summarize     汇总全部回执；任一计划内链路不是 PASS 即失败

★ 回执≠观测：链路退出码 0 不算通过。PASS 必须同时满足：
  退出 0、输出里没有「✗」也没有 Traceback、链路自己打印过真 HTTP 响应和「[库回读]」行，
  且本驱动在链路结束后【另起连接】直读 SQL 的终态回读与期望一致。缺任何一项都 fail-closed。
★ 本入口只证明「该源码 SHA 在一次性库上按 5-1 链路真 HTTP 跑通」。外部通道（微信支付、内容安全、
  短信、AI 供应商）不在其中：支付只桩掉通道层，成功仍走真实 /api/registration/callback。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

RECEIPT_SCHEMA = "CY-CHAIN-ACCEPTANCE-RECEIPT-20261019"
SUMMARY_SCHEMA = "CY-CHAIN-ACCEPTANCE-SUMMARY-20261019"
REGISTRY_PATH = Path(__file__).resolve().parent / "real-storage-it-registry.json"
FIXTURE_CONFIG = Path(__file__).resolve().parent / "chain-acceptance" / "application-e2e.properties"
# 打包的 logback.xml 写死 /home/ruoyi/logs，非 root 的 runner 建不了目录，Spring 直接中止；
# 两个开关都要给：logback 自身的早期初始化会先于 Spring 的 logging.config 读取类路径上的配置。
FIXTURE_LOGBACK = Path(__file__).resolve().parent / "chain-acceptance" / "logback.xml"

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
FAIL_PATH_RE = re.compile(r"^/(?:api|business)/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+){0,8}$")
JAVA_VERSION_RE = re.compile(r"<java\.version>\s*([^<\s]+)\s*</java\.version>")
# 只接受 Boot 4 / Java 21 线；旧 master（Boot 2.5 / Java 8）制品一律拒绝。
JDK_FOR_JAVA_VERSION = {"21": "21"}

SCHEMA = "cy_e2e_51"
DB_HOST, DB_PORT = "127.0.0.1", 3316
REDIS_HOST, REDIS_PORT, REDIS_DB = "127.0.0.1", 6379, 9
APP_BASE = "http://127.0.0.1:8099"
APP_READY_LINE = "Started RuoYiApplication"
FAULT_MARKER = "e2e 注入"
STUBS = ("E2eWechatPayStub", "E2eFaultInjectionFilter", "E2eWxContentSecurityStub")
CONTENT_SECURITY_STUB = "E2eWxContentSecurityStub"
CONTENT_SECURITY_GATE = "EXTERNAL_GATE: content security stubbed"
# 内容安全桩只许改写两次出站调用;回调验签、nonce、结论落地必须走真实实现。
CONTENT_SECURITY_REAL_METHODS = ("processMediaCheckCallback", "verifyMediaCheckCallback", "handleMediaCheckResult",
                                 "tryAcquireCallbackNonce", "releaseCallbackNonce", "submitMediaCheck",
                                 "submitMediaCheckOne", "isEnabled")
CHAIN_TIMEOUT_SECONDS = 900
APP_READY_SECONDS = 300
PLAYER_MEMBER = 9009
PROBE_PHONE = "13900000004"
DEFAULT_FAULT_PROBE_PATH = "/api/play/checkin"
NOT_COVERED = [
    "external WeChat Pay channel (stubbed at the channel layer only; callback path is real)",
    CONTENT_SECURITY_GATE + " (WeChat msg_sec_check / media_check_async calls only; "
    "callback verification, nonce and business side are real)",
    "SMS, AI providers and other external services",
    "device / WeChat DevTools / real-phone behaviour",
    "integrated cross-branch candidate unless SOURCE_SHA is that candidate",
    "production data, persistent-database migration execution, merge or deployment",
]


class GateError(Exception):
    """缺证据或不一致：结束本步并写进回执（fail-closed）。"""


# ---------------------------------------------------------------- 链路登记

def _one(rows):
    return rows[0] if len(rows) == 1 else None


def _platform_checks(topic_id, extra):
    return [
        ("发布归属与审核终态 member_id/club_id/status/user_status/lifecycle",
         "SELECT member_id,club_id,status,user_status,lifecycle FROM cms_topic WHERE id=%s", (topic_id,),
         lambda rows: [tuple(int(v) for v in row) for row in rows] == [(9003, 7001, 1, 1, 1)],
         "唯一一行 (9003, 7001, 1, 1, 1)"),
        ("尚未开售：无开售快照",
         "SELECT COUNT(*) FROM topic_sale_snapshot WHERE topic_id=%s", (topic_id,),
         lambda rows: rows == [(0,)], "0"),
    ]


def _merchant_club_checks(topic_id, extra):
    return [
        ("lifecycle 应为 2=PRICING",
         "SELECT lifecycle FROM cms_topic WHERE id=%s", (topic_id,),
         lambda rows: rows == [(2,)], "2"),
        ("四章四家不同商家承接",
         "SELECT COUNT(*),COUNT(DISTINCT merchant_member_id) FROM cms_topic_node "
         "WHERE topic_id=%s AND merchant_member_id IS NOT NULL", (topic_id,),
         lambda rows: rows == [(4, 4)], "(4, 4)"),
        ("主题层票种模板只有一张",
         "SELECT COUNT(*) FROM oms_ticket WHERE owner_type=1 AND owner_id=%s AND del_flag=0", (topic_id,),
         lambda rows: rows == [(1,)], "1"),
    ]


def _sale_close_checks(topic_id, extra):
    return [
        ("lifecycle 应为 3=SELLING 且 selling_started_at 非空",
         "SELECT lifecycle,selling_started_at IS NOT NULL FROM cms_topic WHERE id=%s", (topic_id,),
         lambda rows: rows == [(3, 1)], "(3, 1)"),
        ("开售快照唯一",
         "SELECT COUNT(*) FROM topic_sale_snapshot WHERE topic_id=%s", (topic_id,),
         lambda rows: rows == [(1,)], "1"),
    ]


def _player_checks(topic_id, extra):
    reg_sql = ("SELECT r.id,r.payment_status,r.transaction_id FROM cms_registration r "
               "JOIN cms_activity a ON a.id=r.owner_id AND r.owner_type=2 "
               "WHERE a.topic_id=%s AND r.member_id=%s")

    def paid_once(rows):
        row = _one(rows)
        return row is not None and int(row[1]) == 2 and row[2] == "E2E-TXN-%s" % row[0]

    reg_scope = ("SELECT r.id FROM cms_registration r JOIN cms_activity a ON a.id=r.owner_id "
                 "AND r.owner_type=2 WHERE a.topic_id=%s AND r.member_id=%s")
    return [
        ("玩家只有一张已支付报名单且交易号来自回调", reg_sql, (topic_id, PLAYER_MEMBER), paid_once,
         "唯一一行 payment_status=2, transaction_id=E2E-TXN-<id>"),
        ("权益四条全部核销（无多扣/无漏扣）",
         "SELECT COUNT(*),COALESCE(SUM(status=1),0),COALESCE(SUM(redeemed_at IS NOT NULL),0) "
         "FROM registration_entitlement WHERE registration_id IN (%s)" % reg_scope, (topic_id, PLAYER_MEMBER),
         lambda rows: [tuple(int(v) for v in row) for row in rows] == [(4, 4, 4)], "(4, 4, 4)"),
        ("redemption 恰好四行 ACTIVE（无重复核销）",
         "SELECT COUNT(*),COALESCE(SUM(status='ACTIVE'),0),COUNT(DISTINCT chapter_id) FROM redemption "
         "WHERE registration_id IN (%s)" % reg_scope, (topic_id, PLAYER_MEMBER),
         lambda rows: [tuple(int(v) for v in row) for row in rows] == [(4, 4, 4)], "(4, 4, 4)"),
        ("通关勋章恰好一枚（无重复发放）",
         "SELECT COUNT(*) FROM player_badge WHERE member_id=%s AND source_id=%s", (PLAYER_MEMBER, topic_id),
         lambda rows: rows == [(1,)], "1"),
    ]


def _finance_checks(topic_id, extra):
    def paid_batch(rows):
        row = _one(rows)
        return (row is not None and row[0] == "PAID" and row[1] is not None and row[2] is not None
                and row[1] != row[2])

    return [
        ("FINAL 资金快照当前版本唯一",
         "SELECT COUNT(*) FROM topic_edition_funds WHERE topic_id=%s AND is_current=1 "
         "AND snapshot_kind='FINAL'", (topic_id,),
         lambda rows: rows == [(1,)], "1"),
        ("付款批次唯一、PAID、确认人与付款人分离",
         "SELECT batch_status,confirmed_by,paid_by FROM club_payable_batch WHERE topic_id=%s", (topic_id,),
         paid_batch, "唯一一行 PAID 且 confirmed_by != paid_by"),
    ]


CHAINS = (
    {"name": "platform", "script": "chain_platform.py", "takes_topic": False, "checks": _platform_checks},
    {"name": "merchant_club", "script": "chain_merchant_club.py", "takes_topic": True,
     "checks": _merchant_club_checks},
    {"name": "sale_close", "script": "chain_sale_close.py", "takes_topic": True, "checks": _sale_close_checks},
    {"name": "player", "script": "chain_player.py", "takes_topic": True, "checks": _player_checks},
    {"name": "finance", "script": "chain_finance.py", "takes_topic": True, "checks": _finance_checks},
)
CHAIN_NAMES = tuple(chain["name"] for chain in CHAINS)
CHAIN_BY_NAME = {chain["name"]: chain for chain in CHAINS}


# ---------------------------------------------------------------- 计划

def plan(source_sha, through, fail_path):
    if not SHA_RE.fullmatch(source_sha or ""):
        raise GateError("source_sha 必须是完整 40 位小写 commit SHA: %r" % source_sha)
    if through not in CHAIN_NAMES:
        raise GateError("through 必须是 %s 之一: %r" % (list(CHAIN_NAMES), through))
    fail_path = (fail_path or "").strip()
    if fail_path and not FAIL_PATH_RE.fullmatch(fail_path):
        raise GateError("fail_path 只接受 /api/... 或 /business/... 的路径段: %r" % fail_path)
    # 后续链路都依赖 platform 建出的 topicId，所以子集只能是从头开始的前缀。
    chains = list(CHAIN_NAMES[:CHAIN_NAMES.index(through) + 1])
    return {"source_sha": source_sha, "through": through, "chains": chains, "fail_path": fail_path}


# ---------------------------------------------------------------- 预检

def git(source_dir, *args, check=True):
    process = subprocess.run(["git", "-C", str(source_dir)] + list(args), stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True)
    if check and process.returncode != 0:
        raise GateError("git %s 失败: %s" % (args[0], process.stderr.strip()[-400:]))
    return process


def master_basis(source_dir, source_sha, master_ref="origin/master"):
    head = git(source_dir, "rev-parse", "HEAD").stdout.strip()
    if head != source_sha:
        raise GateError("候选检出 HEAD=%s 与 SOURCE_SHA=%s 不一致" % (head, source_sha))
    master = git(source_dir, "rev-parse", "--verify", master_ref + "^{commit}", check=False)
    if master.returncode != 0:
        raise GateError("候选检出里没有 %s，无法判定是否基于 master（需要 fetch-depth: 0）" % master_ref)
    base = git(source_dir, "merge-base", source_sha, master_ref, check=False)
    if base.returncode != 0 or not SHA_RE.fullmatch(base.stdout.strip()):
        raise GateError("SOURCE_SHA 与 %s 没有共同祖先：不是基于 master 的候选" % master_ref)
    merge_base = base.stdout.strip()
    ahead = int(git(source_dir, "rev-list", "--count", "%s..%s" % (merge_base, source_sha)).stdout)
    behind = int(git(source_dir, "rev-list", "--count", "%s..%s" % (source_sha, master_ref)).stdout)
    return {"master_ref": master_ref, "master_sha": master.stdout.strip(), "merge_base": merge_base,
            "ahead_of_merge_base": ahead, "behind_master": behind}


def candidate_jdk(source_dir):
    pom = Path(source_dir) / "pom.xml"
    if not pom.is_file():
        raise GateError("候选缺少根 pom.xml")
    match = JAVA_VERSION_RE.search(pom.read_text(encoding="utf-8"))
    version = match.group(1) if match else None
    jdk = JDK_FOR_JAVA_VERSION.get(version or "")
    if jdk is None:
        raise GateError("候选 pom java.version=%r 不在 Boot 4 / Java 21 线上（旧 master 制品不得验收）" % version)
    return version, jdk


def harness_files(directory):
    root = Path(directory) / "scripts" / "e2e"
    names = ["chain_lib.py", "build_schema.py", "seed_fixtures.py", "canonical_draft.py"]
    names += [chain["script"] for chain in CHAINS]
    names += ["%s.java.stub" % stub for stub in STUBS]
    found = {}
    for name in names:
        path = root / name
        found[name] = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
    return found


def stub_problems(directory):
    """桩必须齐全且贴着自己的口径;缺桩或越权改写真实回调路径一律让 explode 变红。"""
    root = Path(directory) / "scripts" / "e2e"
    problems = []
    for name in STUBS:
        if not (root / ("%s.java.stub" % name)).is_file():
            problems.append("缺桩 %s.java.stub" % name)
    source = root / ("%s.java.stub" % CONTENT_SECURITY_STUB)
    if source.is_file():
        text = source.read_text(encoding="utf-8")
        if CONTENT_SECURITY_GATE not in text:
            problems.append("内容安全桩缺少口径标签 %r" % CONTENT_SECURITY_GATE)
        if "extends WxContentSecurityServiceImpl" not in text:
            problems.append("内容安全桩必须继承真实 WxContentSecurityServiceImpl")
        for method in CONTENT_SECURITY_REAL_METHODS:
            if re.search(r"(?:public|protected)[^;{}=]*\b%s\s*\(" % method, text):
                problems.append("内容安全桩不得改写真实路径 %s" % method)
    return problems


# ---------------------------------------------------------------- 回执

def receipt_path(out_dir):
    return Path(out_dir) / "receipt.json"


def read_receipt(out_dir):
    path = receipt_path(out_dir)
    if not path.is_file():
        return {"schema": RECEIPT_SCHEMA, "stages": {}}
    return json.loads(path.read_text(encoding="utf-8"))


def merge_receipt(out_dir, patch):
    receipt = read_receipt(out_dir)
    for key, value in patch.items():
        if key == "stages":
            receipt.setdefault("stages", {}).update(value)
        else:
            receipt[key] = value
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    receipt_path(out_dir).write_text(json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                                     encoding="utf-8")
    return receipt


def stage(out_dir, name, status, **facts):
    merge_receipt(out_dir, {"stages": {name: dict(facts, status=status)}})


def require_stage(out_dir, name):
    status = read_receipt(out_dir).get("stages", {}).get(name, {}).get("status")
    if status != "PASS":
        raise GateError("前置步骤 %s 未通过（%s），拒绝继续" % (name, status))


def write_github_output(path, values):
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        for key, value in values.items():
            if "\n" in str(value):
                raise GateError("GITHUB_OUTPUT 值不能多行: %s" % key)
            handle.write("%s=%s\n" % (key, value))


def workflow_facts(environ):
    keys = ("GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_SHA", "GITHUB_REF",
            "GITHUB_WORKFLOW", "RUNNER_ENVIRONMENT", "RUNNER_OS", "ImageOS", "ImageVersion")
    facts = {key: environ.get(key) for key in keys if environ.get(key)}
    if environ.get("GITHUB_SERVER_URL") and environ.get("GITHUB_REPOSITORY") and environ.get("GITHUB_RUN_ID"):
        facts["run_url"] = "%s/%s/actions/runs/%s" % (environ["GITHUB_SERVER_URL"], environ["GITHUB_REPOSITORY"],
                                                      environ["GITHUB_RUN_ID"])
    return facts


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


# ---------------------------------------------------------------- 容器

def docker(*args, check=True):
    process = subprocess.run(["docker"] + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if check and process.returncode != 0:
        raise GateError("docker %s 失败(%d): %s" % (args[0], process.returncode, process.stderr.strip()[-600:]))
    return process.stdout


def container_names(environ):
    run = "%s-%s" % (environ.get("GITHUB_RUN_ID", "local"), environ.get("GITHUB_RUN_ATTEMPT", "1"))
    if not re.match(r"^[A-Za-z0-9-]+$", run):
        raise GateError("容器名后缀不合规: %r" % run)
    return {"mysql": "cy-chain-%s-mysql" % run, "redis": "cy-chain-%s-redis" % run}


def load_images(path=REGISTRY_PATH):
    images = json.loads(Path(path).read_text(encoding="utf-8"))["images"]
    for kind in ("mysql", "redis"):
        if not re.match(r"^[a-z0-9./-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$", images.get(kind, "")):
            raise GateError("images.%s 必须带 tag 并钉 sha256 digest" % kind)
    return images


def redis_shim(container):
    if not re.match(r"^[A-Za-z0-9-]+$", container):
        raise GateError("容器名不合规: %r" % container)
    return "#!/bin/sh\n# chain_lib.app_token 调 redis-cli 种验证码；转发到本次一次性 Redis 容器。\n" \
           "exec docker exec -i %s redis-cli \"$@\"\n" % container


def install_redis_shim(bin_dir, container):
    bin_dir = Path(bin_dir)
    bin_dir.mkdir(parents=True, exist_ok=True)
    shim = bin_dir / "redis-cli"
    shim.write_text(redis_shim(container), encoding="utf-8")
    shim.chmod(0o755)
    return shim


def chain_path(bin_dir, environ):
    return "%s%s%s" % (Path(bin_dir).resolve(), os.pathsep, environ.get("PATH", ""))


def verify_redis_shim(bin_dir, environ):
    path = chain_path(bin_dir, environ)
    resolved = shutil.which("redis-cli", path=path)
    if resolved is None or Path(resolved).resolve() != (Path(bin_dir) / "redis-cli").resolve():
        raise GateError("PATH 上的 redis-cli 不是本次垫片: %s" % resolved)
    env = dict(environ, PATH=path)
    key = "chain_acceptance_probe_%s" % secrets.token_hex(4)

    def cli(*args):
        return subprocess.run(["redis-cli", "-n", str(REDIS_DB)] + list(args), env=env, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, timeout=30)

    wrote = cli("SET", key, '"123456"')
    read = cli("GET", key)
    cli("DEL", key)
    if wrote.stdout.strip() != "OK" or read.stdout.strip() != '"123456"':
        raise GateError("redis-cli 垫片读写自检失败: set=%r get=%r" % (wrote.stdout, read.stdout))
    return {"resolved": str(resolved), "db": REDIS_DB, "roundtrip": "OK"}


# ---------------------------------------------------------------- SQL

def connect():
    import pymysql
    connection = pymysql.connect(host=DB_HOST, port=DB_PORT, user="root", password="", database=SCHEMA,
                                 autocommit=True, charset="utf8mb4")
    cursor = connection.cursor()
    cursor.execute("SELECT DATABASE()")
    actual = cursor.fetchone()[0]
    cursor.close()
    if actual != SCHEMA:
        connection.close()
        raise GateError("SQL 回读串库: %s != %s" % (actual, SCHEMA))
    return connection


def query(sql, args=()):
    connection = connect()
    try:
        cursor = connection.cursor()
        cursor.execute(sql, args)
        rows = [tuple(row) for row in cursor.fetchall()]
        cursor.close()
        return rows
    finally:
        connection.close()


def execute(sql, args=()):
    connection = connect()
    try:
        cursor = connection.cursor()
        cursor.execute(sql, args)
        count = cursor.rowcount
        connection.commit()
        cursor.close()
        return count
    finally:
        connection.close()


# 小程序会员登录必然带 openid;内容安全 msgSecCheck v2 缺 openid 会 fail-close(WxContentSecurityServiceImpl
# checkText)。候选的 seed_fixtures.py 指纹被 prelaunch-seed-provenance 钉住,所以由门禁在建库后补,只写 cy_e2e_51
# 的九个夹具会员;出站微信调用由 E2eWxContentSecurityStub 桩掉。
FIXTURE_OPENID_SQL = ("UPDATE ums_member SET wx_open_id=CONCAT('e2e-openid-', id) "
                      "WHERE id BETWEEN 9001 AND 9009 AND (wx_open_id IS NULL OR wx_open_id='')")


def jsonable(rows):
    return [[value if isinstance(value, (int, str, type(None))) else str(value) for value in row] for row in rows]


def terminal_readbacks(checks, run_query=query):
    """链路跑完后另起连接直读 SQL；任何一项不符即 FAIL。"""
    results = []
    for label, sql, args, predicate, expected in checks:
        try:
            rows = run_query(sql, args)
            ok = bool(predicate(rows))
            results.append({"label": label, "sql": sql, "args": list(args), "rows": jsonable(rows),
                            "expected": expected, "ok": ok})
        except Exception as exc:  # 回读失败本身就是 fail-closed 证据
            results.append({"label": label, "sql": sql, "args": list(args), "error": "%s: %s" % (
                type(exc).__name__, str(exc)[:300]), "expected": expected, "ok": False})
    return results


# ---------------------------------------------------------------- 判定

TOPIC_RE = re.compile(r"^TOPIC_ID=(\d+)\s*$", re.MULTILINE)
HTTP_STATUS_RE = re.compile(r'"_status": (-?\d+)')


def observed_facts(log_text):
    statuses = [int(value) for value in HTTP_STATUS_RE.findall(log_text)]
    return {
        "readback_lines": log_text.count("[库回读]"),
        "http_responses": len(statuses),
        "http_status_counts": {str(code): statuses.count(code) for code in sorted(set(statuses))},
        "failure_markers": [line.strip() for line in log_text.splitlines() if "✗" in line][:20],
        "traceback": "Traceback (most recent call last)" in log_text,
    }


def parse_topic_id(log_text):
    ids = set(TOPIC_RE.findall(log_text))
    if len(ids) != 1:
        return None
    return int(ids.pop())


def chain_verdict(exit_code, facts, readbacks, timed_out=False):
    problems = []
    if timed_out:
        problems.append("链路超时")
    if exit_code != 0:
        problems.append("链路退出码 %s" % exit_code)
    if facts["failure_markers"]:
        problems.append("链路输出含 ✗ 失败标记")
    if facts["traceback"]:
        problems.append("链路输出含 Traceback")
    if facts["readback_lines"] < 1:
        problems.append("链路没有打印任何 [库回读]：不能只凭退出码判通过")
    if facts["http_responses"] < 1 or facts["http_status_counts"].get("200", 0) < 1:
        problems.append("链路没有观测到任何 HTTP 200 原始响应")
    if not readbacks:
        problems.append("缺少驱动侧终态回读")
    for item in readbacks:
        if not item.get("ok"):
            problems.append("终态回读不符: %s" % item["label"])
    return ("PASS" if not problems else "FAIL"), problems


def chain_env(environ, bin_dir):
    env = {key: value for key, value in environ.items()
           if not key.startswith(("CHENGYIN_", "GITHUB_TOKEN", "ACTIONS_"))}
    env.update({
        "PATH": chain_path(bin_dir, environ),
        "PYTHONUNBUFFERED": "1",
        "PYTHONIOENCODING": "utf-8",
        "NO_PROXY": "127.0.0.1,localhost",
        "no_proxy": "127.0.0.1,localhost",
        "CHENGYIN_E2E_BASE": APP_BASE,
        "CHENGYIN_E2E_DB_HOST": DB_HOST,
        "CHENGYIN_E2E_DB_PORT": str(DB_PORT),
        "CHENGYIN_E2E_DB_USER": "root",
        "CHENGYIN_E2E_DB_PASSWORD": "",
        "CHENGYIN_E2E_DB_SCHEMA": SCHEMA,
    })
    for proxy in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
        env.pop(proxy, None)
    return env


def run_chain(chain, gate_dir, topic_id, env, chains_dir, run_query=query, runner=None):
    gate_dir = Path(gate_dir).resolve()
    script = gate_dir / "scripts" / "e2e" / chain["script"]
    command = [sys.executable, "-u", str(script)]
    if chain["takes_topic"]:
        command.append(str(topic_id))
    log_path = Path(chains_dir) / ("%s.log" % chain["name"])
    started = time.time()
    timed_out = False
    if runner is None:
        try:
            process = subprocess.run(command, cwd=str(gate_dir), env=env, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, timeout=CHAIN_TIMEOUT_SECONDS)
            exit_code, output = process.returncode, process.stdout
        except subprocess.TimeoutExpired as exc:
            exit_code, output, timed_out = -1, exc.stdout or b"", True
        text = output.decode("utf-8", "replace")
    else:
        exit_code, text = runner(command)
    log_path.write_text(text, encoding="utf-8")
    facts = observed_facts(text)
    if chain["name"] == "platform":
        topic_id = parse_topic_id(text)
    # 链路失败时也回读终态，供诊断；判定仍以退出码、失败标记与回读三者同时成立为准。
    readbacks = terminal_readbacks(chain["checks"](topic_id, {}), run_query) if topic_id and not timed_out else []
    verdict, problems = chain_verdict(exit_code, facts, readbacks, timed_out)
    if chain["name"] == "platform" and topic_id is None:
        verdict, problems = "FAIL", problems + ["没有唯一的 TOPIC_ID 输出"]
    return {
        "schema": RECEIPT_SCHEMA, "chain": chain["name"], "script": "scripts/e2e/" + chain["script"],
        "command_args": command[3:], "topic_id": topic_id, "exit_code": exit_code, "timed_out": timed_out,
        "duration_seconds": round(time.time() - started, 1), "observed": facts,
        "terminal_readbacks": readbacks, "verdict": verdict, "problems": problems,
        "log": "chains/%s.log" % chain["name"],
    }


def run_chains(planned, gate_dir, env, out_dir, run_query=query, runner=None):
    chains_dir = Path(out_dir) / "chains"
    chains_dir.mkdir(parents=True, exist_ok=True)
    topic_id = None
    blocked_by = None
    results = []
    for name in planned:
        chain = CHAIN_BY_NAME[name]
        if blocked_by is not None:
            receipt = {"schema": RECEIPT_SCHEMA, "chain": name, "verdict": "NOT_RUN", "topic_id": topic_id,
                       "problems": ["前序链路 %s 未通过，依赖其产出的主题，未执行" % blocked_by]}
        else:
            receipt = run_chain(chain, gate_dir, topic_id, env, chains_dir, run_query, runner)
            if name == "platform":
                topic_id = receipt["topic_id"]
            if receipt["verdict"] != "PASS":
                blocked_by = name
        (chains_dir / ("%s.json" % name)).write_text(
            json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        results.append(receipt)
    return results


# ---------------------------------------------------------------- 应用

def http_probe(method, path, token=None, form=None, json_body=None, timeout=15):
    data, headers = None, {}
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif json_body is not None:
        data = json.dumps(json_body).encode()
        headers["Content-Type"] = "application/json"
    elif method == "POST":
        data = b""
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(APP_BASE + path, data=data, headers=headers, method=method)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")[:600]
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")[:600]
    except Exception as exc:
        return -1, "%s: %s" % (type(exc).__name__, exc)


def fixture_admin_password(gate_dir):
    """超管夹具口令只有一个真源：链路共用件 chain_lib.E2E_SUPERADMIN_PASSWORD。"""
    source = (Path(gate_dir) / "scripts" / "e2e" / "chain_lib.py").read_text(encoding="utf-8")
    match = re.search(r'^E2E_SUPERADMIN_PASSWORD = "([^"]+)"$', source, re.MULTILINE)
    if match is None:
        raise GateError("chain_lib.py 里找不到 E2E_SUPERADMIN_PASSWORD")
    return match.group(1)


def seed_sms_code(phone, bin_dir, environ):
    subprocess.run(["redis-cli", "-n", str(REDIS_DB), "SET", "sms_%s_code" % phone, '"123456"'],
                   env=dict(environ, PATH=chain_path(bin_dir, environ)), stdout=subprocess.PIPE,
                   stderr=subprocess.PIPE, timeout=30, check=True)


def probe_token(path, gate_dir, bin_dir, environ, probe=http_probe, seed=seed_sms_code):
    """注入探针必须带真实登录态：过滤器排在鉴权之后，匿名请求只会拿到 401。"""
    if path.startswith("/business/"):
        status, body = probe("POST", "/login", json_body={"username": "admin",
                                                          "password": fixture_admin_password(gate_dir)})
    else:
        # 9004「玩家小城」不参与任何链路；与 chain_lib.app_token 一样先种验证码，再走真实登录。
        seed(PROBE_PHONE, bin_dir, environ)
        status, body = probe("POST", "/api/login/phone", form={"phone": PROBE_PHONE, "code": "123456"})
    try:
        token = json.loads(body).get("token")
    except ValueError:
        token = None
    if status != 200 or not token:
        raise GateError("注入探针登录失败(%s): %s" % (status, body[:200]))
    return token


def fault_probe(fail_path, token, probe=http_probe):
    """注入开时：注入路径必须 500 + 标记，控制组不受影响；注入关时：过滤器必须透明。"""
    control = probe("GET", "/api/city/nodes")
    target_path = fail_path or DEFAULT_FAULT_PROBE_PATH
    target = probe("POST", target_path, token=token)
    injected = target[0] == 500 and FAULT_MARKER in target[1]
    facts = {"fail_path": fail_path, "target_path": target_path, "target_status": target[0],
             "target_body": target[1][:200], "target_injected": injected, "control_path": "/api/city/nodes",
             "control_status": control[0], "authenticated": bool(token)}
    if control[0] != 401:
        raise GateError("控制组 /api/city/nodes 应为未登录 401，实际 %s" % control[0])
    if fail_path and not injected:
        raise GateError("已要求注入 %s，但没有观测到注入响应: %s" % (fail_path, target))
    if not fail_path and injected:
        raise GateError("未要求注入却观测到注入响应：过滤器不透明")
    return facts


def app_env(environ, work_dir):
    keep = ("PATH", "HOME", "JAVA_HOME", "LANG", "LC_ALL", "USER")
    env = {key: environ[key] for key in keep if key in environ}
    upload = Path(work_dir) / "upload"
    upload.mkdir(parents=True, exist_ok=True)
    env.update({
        "TZ": "Asia/Shanghai",
        "CHAIN_ACCEPTANCE_UPLOAD_DIR": str(upload.resolve()),
        # 每次运行现生成的合成值，只活在这个进程里；不入库、不进回执、不是任何真实凭据。
        "CHENGYIN_JWT_SECRET": secrets.token_hex(32),
        "CHENGYIN_WITHDRAWAL_PII_KEY": __import__("base64").b64encode(secrets.token_bytes(32)).decode(),
        "CHENGYIN_WXMINI_SECRET": secrets.token_hex(16),
        "CHENGYIN_WXPAY_APIV3_KEY": secrets.token_hex(16),
        "CHENGYIN_DRUID_PASSWORD": secrets.token_hex(16),
        "CHENGYIN_DB_PASSWORD": "",
        "COUPON_QR_SECRET": secrets.token_hex(32),
    })
    return env


def public_env(env):
    shown = {}
    for key, value in sorted(env.items()):
        if re.search(r"SECRET|PASSWORD|KEY", key):
            shown[key] = "<explicit-empty>" if value == "" else "<generated-per-run>"
        elif key in ("PATH", "HOME"):
            continue
        else:
            shown[key] = value
    return shown


def main_class(app_dir):
    manifest = (Path(app_dir) / "META-INF" / "MANIFEST.MF").read_text(encoding="utf-8")
    match = re.search(r"^Main-Class:\s*(\S+)\s*$", manifest, re.MULTILINE)
    if match is None:
        raise GateError("exploded jar 缺 Main-Class")
    return match.group(1)


def java_command(app_dir, fail_path, config=FIXTURE_CONFIG, logback=FIXTURE_LOGBACK):
    logback = Path(logback).resolve()
    command = ["java", "-Xmx1536m", "-Duser.timezone=Asia/Shanghai", "-Dfile.encoding=UTF-8",
               "-Dlogging.config=%s" % logback, "-Dlogback.configurationFile=%s" % logback]
    if fail_path:
        command.append("-De2e.fail.path=%s" % fail_path)
    command += ["-cp", str(Path(app_dir).resolve()), main_class(app_dir),
                "--spring.config.additional-location=file:%s" % Path(config).resolve()]
    return command


def start_app(app_dir, work_dir, out_dir, fail_path, environ):
    log = open(Path(out_dir) / "application.log", "wb")
    command = java_command(app_dir, fail_path)
    env = app_env(environ, work_dir)
    process = subprocess.Popen(command, cwd=str(work_dir), env=env, stdout=log, stderr=subprocess.STDOUT,
                               start_new_session=True)
    deadline = time.time() + APP_READY_SECONDS
    ready = False
    while time.time() < deadline:
        if process.poll() is not None:
            break
        text = (Path(out_dir) / "application.log").read_text(encoding="utf-8", errors="replace")
        if APP_READY_LINE in text:
            ready = True
            break
        time.sleep(2)
    facts = {"command": command[:-1] + ["--spring.config.additional-location=<fixture>"],
             "env": public_env(env), "pid": process.pid}
    if not ready:
        stop_app(process)
        tail = (Path(out_dir) / "application.log").read_text(encoding="utf-8", errors="replace").splitlines()[-60:]
        print("---- application.log (last %d lines) ----\n%s" % (len(tail), "\n".join(tail)), file=sys.stderr)
        raise GateError("应用未在 %ds 内就绪（退出码 %s）" % (APP_READY_SECONDS, process.poll()))
    return process, facts


def stop_app(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=30)
    return process.returncode


# ---------------------------------------------------------------- 子命令

def cmd_plan(args, environ):
    result = plan(environ.get("SOURCE_SHA", ""), environ.get("THROUGH", ""), environ.get("FAIL_PATH", ""))
    merge_receipt(args.out, {"schema": RECEIPT_SCHEMA, "plan": result, "workflow": workflow_facts(environ),
                             "not_covered": NOT_COVERED})
    stage(args.out, "plan", "PASS", chains=result["chains"])
    write_github_output(args.github_output, {"chains": ",".join(result["chains"]), "sha12": result["source_sha"][:12]})
    print("plan: %s through %s, fail_path=%r" % (result["source_sha"], result["through"], result["fail_path"]))
    return 0


def cmd_precheck(args, environ):
    require_stage(args.out, "plan")
    source_sha = read_receipt(args.out)["plan"]["source_sha"]
    basis = master_basis(args.source, source_sha)
    java_version, jdk = candidate_jdk(args.source)
    candidate = harness_files(args.source)
    missing = [name for name in ("build_schema.py", "seed_fixtures.py") if candidate.get(name) is None]
    if missing:
        raise GateError("候选缺少建库/夹具脚本: %s" % missing)
    gate = harness_files(args.gate)
    if any(value is None for value in gate.values()):
        raise GateError("门禁检出缺少链路文件: %s" % [k for k, v in gate.items() if v is None])
    drift = sorted(name for name in gate if candidate.get(name) != gate[name])
    stage(args.out, "precheck", "PASS", master_basis=basis, java_version=java_version, jdk=jdk,
          harness={"chains_and_stubs_from": "gate", "schema_and_seed_from": "candidate",
                   "gate_sha256": gate, "candidate_sha256": candidate, "drift": drift})
    write_github_output(args.github_output, {"jdk": jdk})
    print("precheck: merge-base %s, ahead %d, behind %d, JDK %s, harness drift %s" % (
        basis["merge_base"], basis["ahead_of_merge_base"], basis["behind_master"], jdk, drift or "none"))
    return 0


def cmd_explode(args, environ):
    require_stage(args.out, "precheck")
    jar = Path(args.jar)
    if not jar.is_file():
        raise GateError("候选 jar 不存在: %s" % jar)
    app = Path(args.app)
    if app.exists():
        shutil.rmtree(app)
    app.mkdir(parents=True)
    with zipfile.ZipFile(jar) as archive:
        for member in archive.namelist():
            target = (app / member).resolve()
            if not str(target).startswith(str(app.resolve()) + os.sep) and target != app.resolve():
                raise GateError("jar 内路径越界: %s" % member)
        archive.extractall(app)
    classes = app / "BOOT-INF" / "classes"
    libs = sorted((app / "BOOT-INF" / "lib").glob("*.jar"))
    if not classes.is_dir() or not libs:
        raise GateError("不是 Spring Boot fat jar（缺 BOOT-INF/classes 或 lib）")
    stub_src = Path(args.work) / "stub-src" / "com" / "chengyinhub" / "e2e"
    stub_src.mkdir(parents=True, exist_ok=True)
    missing = stub_problems(args.gate)
    if missing:
        raise GateError("桩检查失败: %s" % "; ".join(missing))
    stubs = {}
    for name in STUBS:
        source = Path(args.gate) / "scripts" / "e2e" / ("%s.java.stub" % name)
        shutil.copyfile(source, stub_src / ("%s.java" % name))
        stubs[name] = sha256_file(source)
    classpath = os.pathsep.join([str(classes)] + [str(lib) for lib in libs])
    compiled = subprocess.run(["javac", "-encoding", "UTF-8", "--release", "21", "-proc:none", "-cp", classpath,
                               "-d", str(classes)] + [str(stub_src / ("%s.java" % name)) for name in STUBS],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if compiled.returncode != 0:
        raise GateError("桩编译失败:\n%s" % compiled.stdout[-2000:])
    for name in STUBS:
        if not (classes / "com" / "chengyinhub" / "e2e" / ("%s.class" % name)).is_file():
            raise GateError("桩 %s 编译后没有 class" % name)
    stage(args.out, "explode", "PASS", jar_sha256=sha256_file(jar), main_class=main_class(app),
          stub_sha256=stubs, lib_count=len(libs))
    print("explode: %d libs, stubs %s compiled into exploded classes" % (len(libs), list(STUBS)))
    return 0


def cmd_storage_start(args, environ):
    require_stage(args.out, "explode")
    images = load_images()
    names = container_names(environ)
    for name in names.values():
        docker("rm", "-f", "-v", name, check=False)
    docker("run", "-d", "--name", names["mysql"], "-e", "MYSQL_ALLOW_EMPTY_PASSWORD=yes",
           "-p", "%s:%d:3306" % (DB_HOST, DB_PORT), "--tmpfs", "/var/lib/mysql:rw,size=3g",
           images["mysql"], "--skip-log-bin", "--default-time-zone=+00:00")
    docker("run", "-d", "--name", names["redis"], "-p", "%s:%d:6379" % (REDIS_HOST, REDIS_PORT),
           images["redis"], "redis-server", "--save", "", "--appendonly", "no")
    deadline = time.time() + 180
    mysql_ok = redis_ok = False
    while time.time() < deadline and not (mysql_ok and redis_ok):
        if not mysql_ok:
            mysql_ok = subprocess.run(["docker", "exec", names["mysql"], "mysql", "-h127.0.0.1", "-uroot", "-N",
                                       "-e", "SELECT 1"], stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE).returncode == 0
        if not redis_ok:
            redis_ok = docker("exec", names["redis"], "redis-cli", "PING", check=False).strip() == "PONG"
        if not (mysql_ok and redis_ok):
            time.sleep(2)
    if not (mysql_ok and redis_ok):
        raise GateError("一次性存储未就绪: mysql=%s redis=%s" % (mysql_ok, redis_ok))
    version = docker("exec", names["mysql"], "mysql", "-uroot", "-N", "-e",
                     "SELECT CONCAT(VERSION(),' ',@@GLOBAL.time_zone,' ',@@character_set_server)").strip()
    schemas = docker("exec", names["mysql"], "mysql", "-uroot", "-N", "-e", "SHOW DATABASES").split()
    if set(schemas) - {"information_schema", "mysql", "performance_schema", "sys"}:
        raise GateError("新 MySQL 不是空实例: %s" % schemas)
    if docker("exec", names["redis"], "redis-cli", "DBSIZE").strip() != "0":
        raise GateError("新 Redis 不是空实例")
    shim = install_redis_shim(Path(args.work) / "bin", names["redis"])
    shim_facts = verify_redis_shim(Path(args.work) / "bin", environ)
    stage(args.out, "storage", "PASS", containers=names, images=images, mysql=version,
          redis_shim=dict(shim_facts, path=str(shim)))
    print("storage: %s, redis-cli shim ok" % version)
    return 0


def cmd_schema(args, environ):
    require_stage(args.out, "storage")
    env = {key: value for key, value in environ.items() if not key.startswith("CHENGYIN_")}
    env["PYTHONIOENCODING"] = "utf-8"
    build = subprocess.run([sys.executable, "scripts/e2e/build_schema.py", "--schema", SCHEMA, "--drop",
                            "--host", DB_HOST, "--port", str(DB_PORT)], cwd=str(args.source), env=env,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    build_log = build.stdout.decode("utf-8", "replace")
    (Path(args.out) / "build_schema.log").write_text(build_log, encoding="utf-8")
    seed_log = ""
    seed_code = None
    if build.returncode == 0:
        seed = subprocess.run([sys.executable, "scripts/e2e/seed_fixtures.py", "--schema", SCHEMA,
                               "--host", DB_HOST, "--port", str(DB_PORT)], cwd=str(args.source), env=env,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        seed_code = seed.returncode
        seed_log = seed.stdout.decode("utf-8", "replace")
        (Path(args.out) / "seed_fixtures.log").write_text(seed_log, encoding="utf-8")
    problems = []
    if build.returncode != 0:
        problems.append("build_schema 退出码 %s" % build.returncode)
    if not re.search(r"迁移阶段问题: 0\b", build_log):
        problems.append("build_schema 没有给出「迁移阶段问题: 0」")
    if seed_code != 0:
        problems.append("seed_fixtures 退出码 %s" % seed_code)
    readback = {}
    if not problems:
        readback["fixture_openid_bound"] = execute(FIXTURE_OPENID_SQL)
        expected = {
            "tables": ("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s", (SCHEMA,), None),
            "sys_user": ("SELECT COUNT(*) FROM sys_user WHERE del_flag='0'", (), None),
            "ums_member_fixture": ("SELECT COUNT(*) FROM ums_member WHERE id BETWEEN 9001 AND 9009", (), 9),
            "ums_member_openid": ("SELECT COUNT(*) FROM ums_member WHERE id BETWEEN 9001 AND 9009 "
                                  "AND wx_open_id LIKE %s", ("e2e-openid-%",), 9),
            "mms_merchant_fixture": ("SELECT COUNT(*) FROM mms_merchant WHERE id BETWEEN 8001 AND 8005", (), 5),
            "club_fixture": ("SELECT COUNT(*) FROM club WHERE id IN (7001,7002)", (), 2),
            "captcha_disabled": ("SELECT COUNT(*) FROM sys_config WHERE config_key='sys.account.captchaEnabled' "
                                 "AND config_value='false'", (), 1),
        }
        for label, (sql, sql_args, want) in expected.items():
            value = query(sql, sql_args)[0][0]
            readback[label] = value
            if want is not None and value != want:
                problems.append("夹具回读 %s=%s，期望 %s" % (label, value, want))
        if readback.get("tables", 0) < 100:
            problems.append("建库后表数异常: %s" % readback.get("tables"))
    status = "PASS" if not problems else "FAIL"
    stage(args.out, "schema", status, readback=readback, problems=problems,
          logs=["build_schema.log", "seed_fixtures.log"])
    print("schema: %s %s" % (status, readback if not problems else problems))
    return 0 if status == "PASS" else 1


def cmd_run(args, environ):
    require_stage(args.out, "schema")
    planned = read_receipt(args.out)["plan"]
    process, facts = start_app(args.app, args.work, args.out, planned["fail_path"], environ)
    try:
        target = planned["fail_path"] or DEFAULT_FAULT_PROBE_PATH
        token = probe_token(target, args.gate, Path(args.work) / "bin", environ)
        facts["fault_injection"] = fault_probe(planned["fail_path"], token)
        stage(args.out, "app", "PASS", **facts)
        results = run_chains(planned["chains"], args.gate, chain_env(environ, Path(args.work) / "bin"), args.out)
    finally:
        exit_code = stop_app(process)
        merge_receipt(args.out, {"app_exit_code": exit_code})
    for result in results:
        print("chain %-14s %s %s" % (result["chain"], result["verdict"], "; ".join(result.get("problems", []))))
    return 0 if all(result["verdict"] == "PASS" for result in results) else 1


def cmd_storage_stop(args, environ):
    names = container_names(environ)
    removed = {}
    for kind, name in names.items():
        logs = subprocess.run(["docker", "logs", "--tail", "400", name], stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT)
        Path(args.out).mkdir(parents=True, exist_ok=True)
        (Path(args.out) / ("%s.container.log" % kind)).write_bytes(logs.stdout)
        docker("rm", "-f", "-v", name, check=False)
        removed[kind] = name not in docker("ps", "-a", "--format", "{{.Names}}", check=False).split()
    merge_receipt(args.out, {"teardown": removed})
    return 0 if all(removed.values()) else 1


def summarize(out_dir):
    receipt = read_receipt(out_dir)
    planned = (receipt.get("plan") or {}).get("chains") or []
    problems = []
    if not planned:
        problems.append("没有计划回执")
    stages = receipt.get("stages", {})
    for name in ("plan", "precheck", "explode", "storage", "schema", "app"):
        if stages.get(name, {}).get("status") != "PASS":
            problems.append("步骤 %s 未通过（%s）" % (name, stages.get(name, {}).get("status", "缺回执")))
    chains = []
    for name in planned:
        path = Path(out_dir) / "chains" / ("%s.json" % name)
        if not path.is_file():
            chains.append({"chain": name, "verdict": "MISSING"})
            problems.append("链路 %s 缺回执" % name)
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        chains.append({"chain": name, "verdict": data.get("verdict"), "topic_id": data.get("topic_id"),
                       "problems": data.get("problems", []),
                       "terminal_readbacks": [{"label": item["label"], "rows": item.get("rows"),
                                               "ok": item["ok"]} for item in data.get("terminal_readbacks", [])]})
        if data.get("verdict") != "PASS":
            problems.append("链路 %s: %s" % (name, data.get("verdict")))
    plan_facts = receipt.get("plan") or {}
    summary = {
        "schema": SUMMARY_SCHEMA, "source_sha": plan_facts.get("source_sha"), "through": plan_facts.get("through"),
        "fail_path": plan_facts.get("fail_path"), "workflow": receipt.get("workflow", {}),
        "master_basis": stages.get("precheck", {}).get("master_basis"),
        "harness_drift": (stages.get("precheck", {}).get("harness") or {}).get("drift"),
        "chains": chains, "problems": problems, "not_covered": receipt.get("not_covered", NOT_COVERED),
        "verdict": "PASS" if not problems else "FAIL",
    }
    return summary


def summary_markdown(summary):
    lines = ["## Chain acceptance: %s" % summary["verdict"], "",
             "- source: `%s` (through `%s`, fail_path `%s`)" % (summary["source_sha"], summary["through"],
                                                               summary["fail_path"] or "-"), ""]
    lines += ["| chain | verdict | topic | terminal readbacks |", "|---|---|---|---|"]
    for chain in summary["chains"]:
        readbacks = "<br>".join("%s %s `%s`" % ("✅" if item["ok"] else "❌", item["label"], item["rows"])
                                for item in chain.get("terminal_readbacks", []))
        lines.append("| %s | %s | %s | %s |" % (chain["chain"], chain["verdict"], chain.get("topic_id") or "-",
                                               readbacks or "; ".join(chain.get("problems", [])) or "-"))
    if summary["problems"]:
        lines += ["", "Problems:"] + ["- %s" % problem for problem in summary["problems"]]
    return "\n".join(lines) + "\n"


def cmd_summarize(args, environ):
    summary = summarize(args.out)
    (Path(args.out) / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True)
                                                 + "\n", encoding="utf-8")
    text = summary_markdown(summary)
    if environ.get("GITHUB_STEP_SUMMARY"):
        with open(environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write(text)
    print(text)
    return 0 if summary["verdict"] == "PASS" else 1


COMMANDS = {
    "plan": cmd_plan, "precheck": cmd_precheck, "explode": cmd_explode, "storage-start": cmd_storage_start,
    "schema": cmd_schema, "run": cmd_run, "storage-stop": cmd_storage_stop, "summarize": cmd_summarize,
}


def main(argv=None, environ=None):
    environ = dict(os.environ if environ is None else environ)
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=sorted(COMMANDS))
    parser.add_argument("--out", default="out")
    parser.add_argument("--gate", default="gate")
    parser.add_argument("--source", default="candidate")
    parser.add_argument("--work", default="work")
    parser.add_argument("--app", default="work/app")
    parser.add_argument("--jar", default="candidate/chengyinhub-admin/target/chengyinhub-admin.jar")
    parser.add_argument("--github-output", default=environ.get("GITHUB_OUTPUT"))
    args = parser.parse_args(argv)
    try:
        return COMMANDS[args.command](args, environ)
    except GateError as exc:
        stage_name = {"storage-start": "storage", "run": "app"}.get(args.command, args.command)
        if args.command not in ("summarize", "storage-stop"):
            stage(args.out, stage_name, "FAIL", problems=[str(exc)])
        print("FAIL %s: %s" % (args.command, exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
