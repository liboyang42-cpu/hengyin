#!/usr/bin/env python3
"""候选真 HTTP 业务验收（手动入口）：把 5-1 链路搬到 GitHub 托管 runner 上真跑。

由 .github/workflows/chain-acceptance.yml 调用；本机只跑负控 ci/test_chain_acceptance.py。

  plan          校验 SOURCE_SHA / THROUGH / FAIL_PATH，给出要跑的链路
  precheck      候选必须基于 master（与 origin/master 有共同祖先）、pom java.version 映射到 JDK 21
  explode       解开候选 jar，把两个 e2e 桩（支付通道、故障注入）编进 exploded classes
  storage-start 起一次性 MySQL(127.0.0.1:3316) / Redis(127.0.0.1:6379)，写 redis-cli 垫片并自检
  schema        用候选自己的 build_schema.py + seed_fixtures.py 建 cy_e2e_51，再直读 SQL 核对
  run           同一应用取证（可选五链）→ 确认停机 → 严格3POST SQL及独立回读
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

try:
    import chain_postdeploy as post
    from chain_admin_readback import AdminReadbackError, observe_admin
except ModuleNotFoundError:
    from ci import chain_postdeploy as post
    from ci.chain_admin_readback import AdminReadbackError, observe_admin

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
STUBS = ("E2eWechatPayStub", "E2eWechatPayGatewayStub", "E2eFaultInjectionFilter", "E2eWxContentSecurityStub")
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
    "actual execution of the six POST_DEPLOY scheduled tasks or live old/new JAR rollback",
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

def plan(source_sha, through, fail_path, native_jar_run_id="", native_jar_sha256=""):
    if not SHA_RE.fullmatch(source_sha or ""):
        raise GateError("source_sha 必须是完整 40 位小写 commit SHA: %r" % source_sha)
    if through not in CHAIN_NAMES + ("post_deploy", "post_restart"):
        raise GateError("through 必须是 %s 之一: %r" % (list(CHAIN_NAMES), through))
    fail_path = (fail_path or "").strip()
    if fail_path and not FAIL_PATH_RE.fullmatch(fail_path):
        raise GateError("fail_path 只接受 /api/... 或 /business/... 的路径段: %r" % fail_path)
    if through in ("post_deploy", "post_restart") and fail_path:
        raise GateError("post_deploy-only does not execute gameplay fault probes")
    # 后续链路都依赖 platform 建出的 topicId，所以子集只能是从头开始的前缀。
    chains = [] if through in ("post_deploy", "post_restart") else list(CHAIN_NAMES[:CHAIN_NAMES.index(through) + 1])
    native = None
    if native_jar_run_id or native_jar_sha256:
        if (not re.fullmatch(r"[1-9][0-9]{0,18}", native_jar_run_id or "") or
                not re.fullmatch(r"[0-9a-f]{64}", native_jar_sha256 or "")):
            raise GateError("native JAR requires paired positive run id and lowercase SHA256")
        native = {"run_id": int(native_jar_run_id), "sha256": native_jar_sha256,
                  "artifact_name": "backend-jar-" + source_sha}
    return {"source_sha": source_sha, "through": through, "chains": chains, "fail_path": fail_path,
            "native_jar": native}


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


def verify_candidate_jar(jar, planned):
    """Downloaded original bytes and their marker/sidecar must agree before extraction or Java."""
    jar = Path(jar)
    digest = sha256_file(jar)
    with zipfile.ZipFile(jar) as archive:
        entry = "BOOT-INF/classes/git.properties"
        if archive.namelist().count(entry) != 1:
            raise GateError("candidate JAR requires one git.properties")
        commits = re.findall(r"^git\.commit\.id=([^\r\n]+)\r?$", archive.read(entry).decode("utf-8"), re.MULTILINE)
        if commits != [planned["source_sha"]]:
            raise GateError("candidate JAR embedded source differs from plan")
    native = planned.get("native_jar")
    if native:
        if digest != native["sha256"]:
            raise GateError("native JAR checksum mismatch")
        if (jar.parent / "SOURCE_SHA").read_text(encoding="utf-8") not in (
                planned["source_sha"], planned["source_sha"] + "\n"):
            raise GateError("native JAR source marker mismatch")
        sidecar = (jar.parent / (jar.name + ".sha256")).read_text(encoding="utf-8")
        if sidecar not in (digest + "  " + jar.name, digest + "  " + jar.name + "\n"):
            raise GateError("native JAR checksum sidecar mismatch")
    return {"jar_sha256": digest, "source_sha": planned["source_sha"], "native_jar": native}


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

def connect(database=SCHEMA):
    if database not in (SCHEMA, "mysql"):
        raise GateError("SQL 回读禁止其他schema")
    import pymysql
    connection = pymysql.connect(host=DB_HOST, port=DB_PORT, user="root", password="", database=database,
                                 autocommit=True, charset="utf8mb4")
    cursor = connection.cursor()
    cursor.execute("SELECT DATABASE()")
    actual = cursor.fetchone()[0]
    cursor.close()
    if actual != database:
        connection.close()
        raise GateError("SQL 回读串库: %s != %s" % (actual, database))
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


def start_app(app_dir, work_dir, out_dir, fail_path, environ, config=FIXTURE_CONFIG):
    log = open(Path(out_dir) / "application.log", "wb")
    command = java_command(app_dir, fail_path, config=config)
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


def stop_app(process, require_alive=False):
    alive = process.poll()
    if require_alive and alive is not None:
        raise GateError("application exited before controlled stop")
    if alive is None:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=30)
    return process.returncode


def postdeploy_http(path, token=None, password=None, job=None):
    """Only fixed loopback Actuator reads or normal admin login; never persist full beans or credentials."""
    if path not in ("/login", "/getInfo", "/getRouters", "/actuator/info",
                    "/actuator/health/isolatedStartup", "/actuator/beans",
                    "/monitor/job/run", "/monitor/job/changeStatus"):
        raise GateError("postdeploy HTTP path outside fixed allowlist")
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    data = json.dumps({"username": "admin", "password": password}).encode() if path == "/login" else None
    if path.startswith("/monitor/job/"):
        if (not isinstance(job, dict) or type(job.get("jobId")) is not int or job["jobId"] <= 0
                or set(job) != ({"jobId"} if path.endswith("/run") else {"jobId", "status"})
                or (path.endswith("/changeStatus") and job["status"] not in ("0", "1")) or not token):
            raise GateError("restart job request outside exact fixture contract")
        data = json.dumps(job).encode()
    elif job is not None:
        raise GateError("restart job payload on another path")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(APP_BASE + path, data=data, headers=headers,
                                     method="PUT" if path.startswith("/monitor/job/") else None)
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(request, timeout=15) as response:
            raw = response.read(8 * 1024 * 1024 + 1)
            if response.status != 200 or len(raw) > 8 * 1024 * 1024:
                raise ValueError("invalid response")
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError("invalid JSON shape")
        return value
    except Exception as error:
        raise GateError("postdeploy HTTP unavailable (%s)" % type(error).__name__) from None


def app_process_identity(process, command):
    if process.poll() is not None or not isinstance(process.pid, int) or process.pid <= 0:
        raise GateError("original application PID is not alive")
    folder = Path("/proc") / str(process.pid)
    try:
        raw = (folder / "cmdline").read_bytes().rstrip(b"\0")
        if raw.split(b"\0") != [os.fsencode(arg) for arg in command]:
            raise ValueError("command changed")
        # Field22 is starttime; command names can contain spaces and parentheses.
        starttime = (folder / "stat").read_text().rsplit(")", 1)[1].split()[19]
        if not starttime.isdigit():
            raise ValueError("invalid starttime")
        owned_sockets = set()
        for fd in (folder / "fd").iterdir():
            try:
                match = re.fullmatch(r"socket:\[([0-9]+)\]", os.readlink(fd))
            except FileNotFoundError:
                continue  # A closing unrelated descriptor does not prove ownership of a listener.
            if match:
                owned_sockets.add(match.group(1))
        listeners = set()
        for table, address in (("tcp", "0100007F:1FA3"),
                               ("tcp6", "0000000000000000FFFF00000100007F:1FA3")):
            try:
                rows = (folder / "net" / table).read_text().splitlines()[1:]
            except FileNotFoundError:
                if table == "tcp6":
                    continue  # IPv6 can be disabled; the IPv4 listener must still be owned.
                raise
            for row in rows:
                fields = row.split()
                if len(fields) < 10:
                    raise ValueError("invalid TCP metadata")
                if fields[1].upper() == address and fields[3].upper() == "0A":
                    if not fields[9].isdigit():
                        raise ValueError("invalid socket inode")
                    listeners.add(fields[9])
        listener_inodes = sorted(owned_sockets & listeners)
        if not listener_inodes:
            raise ValueError("original PID does not own the exact loopback listener")
        return {"pid": process.pid, "starttime": starttime,
                "command_sha256": hashlib.sha256(raw).hexdigest(), "listener_inodes": listener_inodes}
    except (OSError, ValueError, IndexError):
        raise GateError("original application process identity unavailable") from None


def observe_postdeploy(args, planned, process, config=FIXTURE_CONFIG):
    expected = read_receipt(args.out)["stages"]["explode"]["jar_sha256"]
    binding = verify_candidate_jar(args.jar, planned)
    if binding["jar_sha256"] != expected:
        raise GateError("original candidate JAR changed since explode")
    command = java_command(args.app, planned["fail_path"], config=config)
    before = app_process_identity(process, command)
    login = postdeploy_http("/login", password=fixture_admin_password(args.gate))
    token = login.get("token")
    if login.get("code") != 200 or not isinstance(token, str) or not token:
        raise GateError("postdeploy normal administrator login failed")
    observation = post.validate_observation(
        postdeploy_http("/actuator/info", token),
        postdeploy_http("/actuator/health/isolatedStartup", token),
        postdeploy_http("/actuator/beans", token), planned["source_sha"])
    admin = (observe_admin(lambda path: postdeploy_http(path, token), connect)
             if planned["through"] == "post_deploy" else None)
    database = post.read_state(connect)
    if app_process_identity(process, command) != before or sha256_file(args.jar) != expected:
        raise GateError("candidate PID or original JAR changed during observation")
    return dict(observation, admin=admin, process=before, jar_sha256=expected, schema=SCHEMA,
                server_uuid=database["server_uuid"], observed_at=time.time(),
                boundary=("same exploded candidate JVM; reviewed four builtin plans may run; "
                          "sys_job checked separately: JVM1 zero, JVM2 six initially paused; offline provider stubs unchanged"
                          if planned["through"] == post.RESTART_MODE else
                          "same exploded candidate JVM; external-provider stubs unchanged; no task invoked"))


# ---------------------------------------------------------------- 子命令

def cmd_plan(args, environ):
    result = plan(environ.get("SOURCE_SHA", ""), environ.get("THROUGH", ""), environ.get("FAIL_PATH", ""),
                  environ.get("NATIVE_JAR_RUN_ID", ""), environ.get("NATIVE_JAR_SHA256", ""))
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
    try:
        binding = verify_candidate_jar(jar, read_receipt(args.out)["plan"])
    except (OSError, UnicodeError, zipfile.BadZipFile, KeyError):
        raise GateError("candidate JAR source/checksum evidence unavailable") from None
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
    stage(args.out, "explode", "PASS", **binding, main_class=main_class(app),
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


def restart_seed_clock():
    connection = connect("mysql")
    try:
        cursor = connection.cursor()
        try:
            cursor.execute("SELECT @@server_uuid,DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s'),"
                           "@@session.time_zone,@@system_time_zone")
            row = cursor.fetchone()
            post.require(row is not None and len(row) == 4, "restart_seed_clock_shape")
            return dict(zip(("server_uuid", "datetime", "session_timezone", "system_timezone"), row))
        finally:
            cursor.close()
    finally:
        connection.close()


def cmd_schema(args, environ):
    require_stage(args.out, "storage")
    restart = read_receipt(args.out)["plan"]["through"] == post.RESTART_MODE
    preparation_before = restart_seed_clock() if restart else None
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
    if not problems and restart:
        fresh = {"build_exit": build.returncode, "seed_exit": seed_code,
                 "source_sha": read_receipt(args.out)["plan"]["source_sha"],
                 "clock_before": preparation_before, "clock_after": restart_seed_clock(),
                 "containers": read_receipt(args.out)["stages"]["storage"]["containers"]}
        readback["restart_seed"] = post.prepare_restart_seed(connect, fresh)
    status = "PASS" if not problems else "FAIL"
    stage(args.out, "schema", status, readback=readback, problems=problems,
          logs=["build_schema.log", "seed_fixtures.log"])
    print("schema: %s %s" % (status, readback if not problems else problems))
    return 0 if status == "PASS" else 1



RESTART_BACKGROUND_SOURCE = {
    "chengyinhub-admin/src/main/java/com/chengyinhub/web/job/LeaderScheduledJobsConfig.java": (
        "8d8def82330fd7210739ea1d71c042d57e3ba5366ef9903be4174e5233c1f61e"
    ),
    "chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/BackgroundSchedulingConfig.java": (
        "1185826cb7125edbd8045f827541a70fd785c103e615c699c334c6d33bb7b9cf"
    ),
    "chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/config/DisabledJobsQuartzConfig.java": (
        "0a2227ec1dbadb85c1fba9359e30d06c9774bc56241eddc9f2b77acd614babca"
    ),
    "chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/service/MySqlSchedulerCoordinator.java": (
        "a419e344dd6513ed7cbc562dff608172e5a1989abd298e4d403c9f5b61d3b6d1"
    ),
    "chengyinhub-admin/src/main/java/com/chengyinhub/web/runner/WithdrawalPiiBackfillRunner.java": (
        "b9d6388011f517553001712b38deae698f589569999784cde9220de1dce66ef2"
    ),
    "chengyinhub-admin/src/main/java/com/chengyinhub/web/runner/MemberLegacyPasswordHashRunner.java": (
        "8bf398d4259259cd8ecf3e427bc9fbe11d7ecd0f4ae6026ed169773db32331c0"
    ),
    "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ServiceStatusFeedServiceImpl.java": (
        "3d11a154c84fa3ebbe818c90a70c54981491331ca9575e66cdeda950668c74d6"
    ),
    "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantCrmExportServiceImpl.java": (
        "143a64f0b984bb1b89fec775b56c4059251bc6ed62afa2eabdb53508f8d37268"
    ),
    "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AccountDeregistrationServiceImpl.java": (
        "61d263d1feb69e1baeb9dc1f22f232b3cef10cc9f4802dcca8f909f92969e2d9"
    ),
}


def restart_local_files(work):
    root = Path(work).resolve()
    paths = [root / "upload", root / "upload-private", root / "upload-private/merchant-crm-export"]
    if any(path.is_symlink() for path in paths):
        raise GateError("restart private export path aliases another resource")
    crm = paths[-1]
    if crm.exists() and (not crm.is_dir() or any(crm.iterdir())):
        raise GateError("restart private export directory is not empty")
    return {"crm_export_directory": "EMPTY_TASK_OWNED"}


def restart_config(args):
    # The only automatic plans admitted here are the reviewed four builtins; their queues must stay empty.
    for name, digest in RESTART_BACKGROUND_SOURCE.items():
        if sha256_file(Path(args.source) / name) != digest:
            raise GateError("restart background source needs review")
    for module in ("chengyinhub-admin", "chengyinhub-system", "chengyinhub-quartz", "chengyinhub-framework", "chengyinhub-common"):
        for path in (Path(args.source) / module / "src/main/java").rglob("*.java"):
            text = re.sub(r"/\*.*?\*/|//[^\n]*", "", path.read_text(), flags=re.S)
            if re.search(r"@Scheduled\s*\(", text):
                raise GateError("restart unreviewed scheduled annotation")
            if re.search(r"\.register(?:Cron|FixedDelay)\s*\(", text) and path.name != "LeaderScheduledJobsConfig.java":
                raise GateError("restart unreviewed automatic plan")
    restart_local_files(args.work)
    root = Path(args.work).resolve()
    root.mkdir(parents=True, exist_ok=True)
    feed = root / "restart-status" / "chengyin-status.json"
    target = root / "application-post-restart.properties"
    if target.exists() or feed.parent.is_symlink():
        raise GateError("restart fixture configuration already exists")
    values = {"chengyin.jobs.enabled": "true", "withdrawal.pii.backfill-limit": "0",
              "member.password.legacy-hash-limit": "0", "alarm.webhook": "", "alarm.failure-webhook": "",
              "chengyin.status.feed-file": str(feed)}
    with target.open("x") as handle:
        handle.write(FIXTURE_CONFIG.read_text() + "\n" + "\n".join(k + "=" + v for k, v in values.items()) + "\n")
    target.chmod(0o600)
    return target


def restart_alarm(raw_value, raw_ttl):
    try:
        value = json.loads(raw_value)
    except (TypeError, ValueError):
        raise post.PostDeployError("restart_monitor_attempt_malformed_json")
    post.require(type(value) is str and value == "1", "restart_monitor_attempt_wrong_value")
    post.require(isinstance(raw_ttl, str) and re.fullmatch(r"[0-9]{1,6}", raw_ttl) and
                 0 < int(raw_ttl) <= 600000, "restart_monitor_attempt_invalid_ttl")
    return {"value": value, "ttl_ms": int(raw_ttl)}


def run_post_restart(args, environ, planned, first_proof, first_process, config):
    result = {"status": "FAIL", "schema": SCHEMA, "effects": [], "jvm1_stopped": first_process.poll() is not None,
              "recovery": "UNKNOWN_KEEP_ORIGINAL_COMMAND_IDENTITY_NO_RETRY"}
    post.require(result["jvm1_stopped"], "restart_original_jvm_not_stopped")
    server = first_proof["server_uuid"]
    result["server_uuid"] = server
    stage(args.out, "post_restart", "RUNNING", result=result, server_uuid=server, fixture_id=post.FIXTURE_ID)
    process = None
    failure = None
    try:
        result["local_files"] = restart_local_files(args.work)
        fixture = post.prepare_restart_effects(connect, server)
        result["fixture"] = fixture
        folder = Path(args.out) / "post_restart"
        folder.mkdir()
        result["clock"] = {"before_start": post.restart_clock(connect, server)}
        started = result["clock"]["before_start"]["epoch"]
        process, facts = start_app(args.app, args.work, folder, "", environ, config=config)
        proof = observe_postdeploy(args, planned, process, config=config)
        post.require(proof["server_uuid"] == server and proof["process"] != first_proof["process"] and
                     proof["process"]["pid"] != first_proof["process"]["pid"], "restart_jvm_identity_not_new")
        result["clock"]["after_live"] = post.restart_clock(connect, server)
        result.update(proof=proof, jvm2_new_identity=True, jvm2_started_database_epoch=started)
        token = postdeploy_http("/login", password=fixture_admin_password(args.gate)).get("token")
        post.require(isinstance(token, str) and bool(token), "restart_admin_login_failed")
        beans = postdeploy_http("/actuator/beans", token)
        entries = [context.get("beans", {}) for context in beans.get("contexts", {}).values()]
        providers = [entry for context in entries for name, entry in context.items() if name == "e2eWechatPayStub"]
        tasks = [entry for context in entries for name, entry in context.items() if name == "merchantCommerceReconcileTask"]
        services = [entry for context in entries for name, entry in context.items() if name == "merchantCommerceService"]
        stub_sha = sha256_file(Path(args.gate) / "scripts/e2e/E2eWechatPayStub.java.stub")
        post.require(len(providers) == len(tasks) == len(services) == 1 and
                     re.fullmatch(r"com\.chengyinhub\.e2e\.E2eWechatPayStub(?:\$\$SpringCGLIB\$\$[A-Za-z0-9_]+)?", providers[0].get("type", "")) and
                     "e2eWechatPayStub" in tasks[0].get("dependencies", []) and
                     "e2eWechatPayStub" in services[0].get("dependencies", []) and
                     stub_sha == "62daf04bc0e6aac703e7536fe17b79999b86d92ddec722cfb88c59cadba87161",
                     "restart_offline_payment_stub_not_wired")
        result["payment_stub_binding"] = {"task": True, "service": True, "type": providers[0]["type"],
                                           "source_sha256": stub_sha}  # Source hash is not a compiled-class hash.
        jobs = {row[3]: row for row in fixture["jobs"]}
        names = read_receipt(args.out)["stages"]["storage"]["containers"]
        command = java_command(args.app, "", config=config)
        roam_status = "1"

        def live():
            post.require(app_process_identity(process, command) == proof["process"] and
                         sha256_file(args.jar) == proof["jar_sha256"], "restart_live_identity_changed")
            current = post.restart_rows(connect, server, post.ALL_JOBS)
            post.verify_restart_jobs(fixture["jobs"], current, roam_status)

        def put(path, payload):
            nonlocal roam_status
            live()
            value = postdeploy_http(path, token, job=payload)  # No retry: this endpoint has no request-id receipt.
            post.require(value.get("code") == 200, "restart_manual_request_not_accepted")
            if path == "/monitor/job/changeStatus":
                post.require(payload["jobId"] == jobs[post.ROAM_TARGET][0], "restart_only_roam_status_may_change")
                roam_status = payload["status"]
            live()

        def alarm(key):
            value = docker("exec", names["redis"], "redis-cli", "-n", str(REDIS_DB), "GET", key).strip()
            ttl = docker("exec", names["redis"], "redis-cli", "-n", str(REDIS_DB), "PTTL", key).strip()
            return restart_alarm(value, ttl)

        def invoke(target, automatic=False, settlement_negative=False):
            live()
            job = jobs[target]
            baseline = post.restart_rows(connect, server,
                "SELECT COALESCE(MAX(job_log_id),0) FROM sys_job_log WHERE "
                "CAST(invoke_target AS BINARY)=CAST(%s AS BINARY) AND job_name=%s AND job_group=%s",
                (target, job[1], job[2]))[0][0]
            before = post.restart_effect_state(connect, server)
            log_offset = (folder / "application.log").stat().st_size if target == post.COMMERCE else None
            if automatic:
                put("/monitor/job/changeStatus", {"jobId": job[0], "status": "0"})
            else:
                put("/monitor/job/run", {"jobId": job[0]})
            deadline = time.monotonic() + 90
            actual = None
            while time.monotonic() < deadline:
                live()
                actual = post.restart_log(connect, server, job, baseline, started)
                if actual is not None:
                    break
                time.sleep(0.25)
            post.require(actual is not None, "restart_async_job_outcome_unknown")
            if automatic:
                put("/monitor/job/changeStatus", {"jobId": job[0], "status": "1"})
                post.require(post.restart_rows(connect, server,
                             "SELECT status FROM sys_job WHERE job_id=%s AND invoke_target=%s", (job[0], target)) == [["1"]],
                             "restart_roam_pause_not_persisted")
            after = post.restart_effect_state(connect, server)
            evidence = {"target": target, "job_id": job[0], "baseline_log_id": baseline,
                        "new_jvm_start_database_epoch": started, "log": actual, "before": before, "after": after,
                        "invocation": "ORIGINAL_MINUTE_CRON" if automatic else "SINGLE_NORMAL_ADMIN_PUT"}
            if target == post.COMMERCE:
                post.require(actual[3] == "COMMERCE_AGGREGATE_FAILURE", "restart_commerce_wrapper_not_observed")
                evidence["specific_cause"] = post.commerce_cause(folder / "application.log", log_offset, actual[0])
                actual[3] = "COMMERCE_UNKNOWN"  # Only the independently observed specific cause permits this classification.
            events = {"auditBacklogMonitorTask.run()": "monitor.audit.backlog",
                      "withdrawReconciliationTask.run()": "monitor.withdraw.manual"}
            if target in events:
                evidence["alarm"] = alarm("alarm:attempt:" + events[target])
            if settlement_negative:
                evidence["alarm"] = alarm("alarm:attempt:monitor.settlement.mismatch")
                post.require(actual[1] == "1" and actual[3] == "SETTLEMENT_MISMATCH" and before == after and
                             evidence["alarm"]["value"] == "1" and evidence["alarm"]["ttl_ms"] > 0,
                             "restart_wrong_settlement_rejection")
            else:
                post.validate_restart_effect(target, actual, before, after, evidence.get("alarm"))
            return evidence

        result["effects"].append(invoke(post.ROAM_TARGET, automatic=True))
        result["automatic_roam_observed"] = True
        result["roam_repeat"] = invoke(post.ROAM_TARGET)
        # Both metric samples must fall in one actual five-minute natural-key window.
        remaining = 300 - int(time.time()) % 300
        if remaining < 30:
            time.sleep(remaining + 1)
        result["effects"].append(invoke("metricsProbeTask.run()"))
        result["metrics_repeat"] = invoke("metricsProbeTask.run()")
        original_keys = [r[:4] for r in result["effects"][-1]["after"]["metrics"]]
        post.require([r[:4] for r in result["metrics_repeat"]["after"]["metrics"]] == original_keys,
                     "restart_metrics_repeat_changed_natural_keys")
        for target in ("auditBacklogMonitorTask.run()", "withdrawReconciliationTask.run()",
                       "settlementReconciliationTask.run()", post.COMMERCE):
            result["effects"].append(invoke(target))
        result["audit_repeat"] = invoke("auditBacklogMonitorTask.run()")
        first_alarm = next(e["alarm"] for e in result["effects"] if e["target"] == "auditBacklogMonitorTask.run()")
        post.require(result["audit_repeat"]["alarm"]["ttl_ms"] <= first_alarm["ttl_ms"], "restart_alarm_lease_refreshed")
        post.fixture_write(connect, server, [("UPDATE settlement_batch SET total_amount=2 WHERE id=%s AND total_amount=1", (post.FIXTURE_ID,))])
        result["settlement_rejection"] = invoke("settlementReconciliationTask.run()", settlement_negative=True)
        result["final_readback"] = post.restart_effect_state(connect, server)
        result["final_jobs"] = post.restart_rows(connect, server, post.ALL_JOBS)
        result["passive_queues"] = post.passive_queues(connect, server)
        result["local_files"] = restart_local_files(args.work)
    except Exception as error:
        failure = type(error).__name__
        if isinstance(error, post.PostDeployError):
            result["error_code"] = error.code
    finally:
        if process is not None:
            try:
                result["clock"]["before_stop"] = post.restart_clock(connect, server)
                post.require(app_process_identity(process, java_command(args.app, "", config=config)) == proof["process"],
                             "restart_process_not_live_at_controlled_stop")
                result["stop_precondition"] = proof["process"]
                stop_app(process, require_alive=True)
                result["controlled_stop"] = True
            except Exception as error:
                failure = "PRESTOP_UNKNOWN_" + type(error).__name__
            try:
                stop_app(process)  # Exact owned Popen cleanup still runs after a failed controlled-stop precondition.
                post.require(process.poll() is not None, "restart_stop_unknown")
                try:
                    app_process_identity(process, java_command(args.app, "", config=config))
                except GateError:
                    pass
                else:
                    raise GateError("restart_listener_process_still_alive")
                result["jvm2_stopped"] = True
                result["jvm2_exit_code"] = process.poll()
            except Exception as error:
                failure = "STOP_UNKNOWN_" + type(error).__name__
        if result.get("jvm2_stopped"):
            # A write timeout is observed only after the exact JVM has stopped; never resubmit it.
            try:
                leader_key = "cy:leader:" + hashlib.sha256((SCHEMA + "\nall").encode()).hexdigest()[:48]
                post.require(post.restart_rows(connect, server, "SELECT IS_USED_LOCK(%s)", (leader_key,)) == [[None]],
                             "restart_leader_session_still_owned")
                result["after_stop_readback"] = post.restart_effect_state(connect, server)
                result["after_stop_jobs"] = post.restart_rows(connect, server, post.ALL_JOBS)
                result["after_stop_local_files"] = restart_local_files(args.work)
            except Exception as error:
                failure = "READBACK_UNKNOWN_" + type(error).__name__
        if failure:
            result["error_type"] = failure
        else:
            result["status"] = "PASS"
            try:
                post.validate_restart_result(result)
            except Exception as error:
                result.update(status="FAIL", error_type=type(error).__name__)
        stage(args.out, "post_restart", result["status"], result=result)
    if result["status"] != "PASS":
        raise GateError("post_restart evidence incomplete; original command identities retained")


def cmd_run(args, environ):
    require_stage(args.out, "schema")
    planned = read_receipt(args.out)["plan"]
    restart = planned["through"] == post.RESTART_MODE
    config = restart_config(args) if restart else FIXTURE_CONFIG
    if restart:
        post.require(post.restart_rows(connect, read_receipt(args.out)["stages"]["schema"]["readback"]["restart_seed"]["server_uuid"], post.ALL_JOBS) == [],
                     "restart_jvm1_has_unknown_jobs")
    process, facts = (start_app(args.app, args.work, args.out, planned["fail_path"], environ, config=config)
                      if restart else start_app(args.app, args.work, args.out, planned["fail_path"], environ))
    proof = None
    try:
        if planned["through"] not in ("post_deploy", post.RESTART_MODE):
            target = planned["fail_path"] or DEFAULT_FAULT_PROBE_PATH
            token = probe_token(target, args.gate, Path(args.work) / "bin", environ)
            facts["fault_injection"] = fault_probe(planned["fail_path"], token)
        stage(args.out, "app", "PASS", **facts)
        results = (run_chains(planned["chains"], args.gate, chain_env(environ, Path(args.work) / "bin"), args.out)
                   if planned["through"] not in ("post_deploy", post.RESTART_MODE) else [])
        if all(result["verdict"] == "PASS" for result in results):
            post.prepared_sql(args.source)
            proof = (observe_postdeploy(args, planned, process, config=config) if restart
                     else observe_postdeploy(args, planned, process))
        else:
            stage(args.out, "postdeploy", "FAIL", reason="prerequisite_chain_failed", sql_started=False)
    except Exception as error:
        code = str(error) if isinstance(error, AdminReadbackError) else None
        stage(args.out, "postdeploy", "FAIL", reason=type(error).__name__, sql_started=False,
              failure_code=code if code and re.fullmatch(r"ADMIN_[A-Z_]+", code) else None)
        raise GateError("postdeploy live prerequisite failed") from None
    finally:
        try:
            if proof is not None and process.poll() is not None:
                raise GateError("original JVM exited before the controlled stop")
            stop_app(process)
            exit_code = process.poll()
            if exit_code is None:
                raise GateError("application stop outcome is unknown")
            merge_receipt(args.out, {"app_exit_code": exit_code})
        except Exception as error:
            stage(args.out, "postdeploy", "FAIL", reason="application_stop_unknown",
                  error_type=type(error).__name__, sql_started=False)
            raise GateError("postdeploy requires confirmed original JVM exit") from None
    if proof is not None:
        if not 0 <= time.time() - proof["observed_at"] <= 90:
            stage(args.out, "postdeploy", "FAIL", reason="live_proof_expired", sql_started=False)
            raise GateError("postdeploy live observation is stale")
        stage(args.out, "postdeploy", "RUNNING", proof=proof, stopped_before_sql=True,
              app_exit_code=exit_code, sql_started=True, recovery="UNKNOWN_PARTIAL_DO_NOT_RETRY")
        fixture = None
        try:
            if planned["through"] == "post_deploy":
                fixture = post.prepare_operator_fixture(connect, proof["server_uuid"])
            result = post.register_jobs(args.source, connect, proof["server_uuid"])
        except post.PostDeployError as error:
            stage(args.out, "postdeploy", "FAIL", proof=proof, stopped_before_sql=True,
                  sql_started=True, result=error.evidence)
            raise GateError("strict POST_DEPLOY SQL or independent readback failed") from None
        stage(args.out, "postdeploy", "PASS", proof=proof, stopped_before_sql=True,
              app_exit_code=exit_code, sql_started=True, result=result,
              scenario=post.OPERATOR_SCENARIO if fixture else "REGISTRATION_FROM_CURRENT_ROWS",
              operator_fixture=fixture, unique_migration_count=3,
              not_covered="actual scheduled execution, persistent deployment and live old/new JAR rollback")
        if fixture is not None:
            stage(args.out, "postdeploy_commerce_rejection", "RUNNING",
                  recovery="UNKNOWN_PARTIAL_DO_NOT_RETRY")
            try:
                rejection = post.characterize_commerce_rejection(args.source, connect, proof["server_uuid"], result)
            except post.PostDeployError as error:
                stage(args.out, "postdeploy_commerce_rejection", "FAIL", result=error.evidence)
                raise GateError("commerce incompatible operator control failed") from None
            stage(args.out, "postdeploy_commerce_rejection", "PASS", result=rejection)
    if restart and proof is not None:
        run_post_restart(args, environ, planned, proof, process, config)
    for result in results:
        print("chain %-14s %s %s" % (result["chain"], result["verdict"], "; ".join(result.get("problems", []))))
    return 0 if all(result["verdict"] == "PASS" for result in results) else 1


def cmd_storage_stop(args, environ):
    names = container_names(environ)
    removed, readback = {}, {}
    for kind, name in names.items():
        try:
            logs = subprocess.run(["docker", "logs", "--tail", "400", name], stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT)
            Path(args.out).mkdir(parents=True, exist_ok=True)
            (Path(args.out) / ("%s.container.log" % kind)).write_bytes(logs.stdout)
        except OSError:
            pass  # Best-effort logs must not prevent either exact container's cleanup.
        try:
            docker("rm", "-f", "-v", name, check=False)
        except OSError:
            pass  # A successful independent readback may still prove it was already absent.
        try:
            remaining = docker("ps", "-a", "--format", "{{.Names}}", check=True).split()
        except (GateError, OSError):
            removed[kind], readback[kind] = False, "UNKNOWN"
        else:
            removed[kind] = name not in remaining
            readback[kind] = "ABSENT" if removed[kind] else "LEFTOVER"
    merge_receipt(args.out, {"teardown": removed, "teardown_readback": readback})
    return 0 if all(removed.values()) else 1


def summarize(out_dir):
    receipt = read_receipt(out_dir)
    planned = (receipt.get("plan") or {}).get("chains") or []
    problems = []
    post_only = (receipt.get("plan") or {}).get("through") in ("post_deploy", post.RESTART_MODE)
    if not SHA_RE.fullmatch((receipt.get("plan") or {}).get("source_sha") or ""):
        problems.append("缺完整候选 source_sha")
    if not planned and not post_only:
        problems.append("没有计划回执")
    if post_only and (receipt.get("plan") or {}).get("chains") != []:
        problems.append("post_deploy-only 不得混入五条既有链路")
    stages = receipt.get("stages", {})
    for name in ("plan", "precheck", "explode", "storage", "schema", "app", "postdeploy"):
        if stages.get(name, {}).get("status") != "PASS":
            problems.append("步骤 %s 未通过（%s）" % (name, stages.get(name, {}).get("status", "缺回执")))
    registration = stages.get("postdeploy", {})
    result = registration.get("result", {})
    proof = registration.get("proof", {})
    try:
        if registration.get("stopped_before_sql") is not True or type(registration.get("app_exit_code")) is not int:
            raise post.PostDeployError("post_stop_proof_missing")
        post.validate_result(result, proof, (receipt.get("plan") or {}).get("source_sha"),
                             stages.get("explode", {}).get("jar_sha256"))
        if (receipt.get("plan") or {}).get("through") == "post_deploy":
            admin = proof.get("admin") or {}
            post.require(admin.get("status") == "PASS_FIXTURE_SUPERADMIN_HTTP_SQL_ONLY" and
                         admin.get("userId") == 1 and admin.get("roleKeys") == ["admin"] and
                         admin.get("permissions") == ["*:*:*"] and
                         admin.get("sqlReadback") == {"schema": SCHEMA,
                             "userIdentity": {"userId": 1, "userName": "admin", "status": "0", "delFlag": "0"},
                             "roleBindings": [{"roleId": 1, "roleKey": "admin", "status": "0", "delFlag": "0"}]} and
                         admin.get("routeScope") == "enabled_internal_C_plain_paths_with_enabled_M_ancestors_only" and
                         admin.get("ordinaryRbacIsolationVerified") is False and admin.get("realRoleCardsVerified") == 0 and
                         bool(admin.get("routes")) and all(
                             type(row.get("menuId")) is int and row["menuId"] > 0 and
                             isinstance(row.get("path"), str) and row["path"].startswith("/") and
                             isinstance(row.get("component"), str) and bool(row["component"])
                             for row in admin["routes"]), "post_admin_readback_incomplete")
            fixture = registration.get("operator_fixture") or {}
            post.validate_operator_fixture(fixture, proof["server_uuid"])
            post.require(registration.get("scenario") == post.OPERATOR_SCENARIO and
                         fixture.get("status") == "PASS" and fixture.get("inserted_targets") == 6 and
                         registration.get("unique_migration_count") == 3 and
                         result["before"] == fixture["after"] and result["after"] == fixture["after"],
                         "post_existing_scenario_incomplete")
            rejection = stages.get("postdeploy_commerce_rejection", {})
            post.require(rejection.get("status") == "PASS", "post_rejection_stage_missing")
            post.validate_commerce_rejection(rejection.get("result") or {}, result)
    except Exception:
        problems.append("postdeploy 缺同候选/停机/15句9标量完整回执")
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
    if (receipt.get("plan") or {}).get("through") == post.RESTART_MODE:
        extra = stages.get("post_restart", {})
        try:
            if extra.get("status") != "PASS":
                raise post.PostDeployError("restart_stage_missing")
            value = extra["result"]
            post.validate_restart_result(value)
            post.require(value["server_uuid"] == proof["server_uuid"], "restart_original_database_changed")
            post.validate_result(result, value["proof"], receipt["plan"]["source_sha"],
                                 stages["explode"]["jar_sha256"])
            if value["proof"]["process"]["pid"] == proof["process"]["pid"]:
                raise post.PostDeployError("restart_same_pid")
        except Exception:
            problems.append("post_restart 缺真实新JVM/六任务效果及停机后完整回读")
    plan_facts = receipt.get("plan") or {}
    summary = {
        "schema": SUMMARY_SCHEMA, "source_sha": plan_facts.get("source_sha"), "through": plan_facts.get("through"),
        "fail_path": plan_facts.get("fail_path"), "workflow": receipt.get("workflow", {}),
        "master_basis": stages.get("precheck", {}).get("master_basis"),
        "harness_drift": (stages.get("precheck", {}).get("harness") or {}).get("drift"),
        "chains": chains, "problems": problems, "not_covered": receipt.get("not_covered", NOT_COVERED),
        "postdeploy": stages.get("postdeploy", {}),
        "post_restart": stages.get("post_restart", {}),
        "postdeploy_commerce_rejection": stages.get("postdeploy_commerce_rejection", {}),
        "verdict": "PASS" if not problems else "FAIL",
    }
    return summary


def summary_markdown(summary):
    lines = ["## Chain acceptance: %s" % summary["verdict"], "",
             "- source: `%s` (through `%s`, fail_path `%s`)" % (summary["source_sha"], summary["through"],
                                                               summary["fail_path"] or "-"), ""]
    post_facts = summary.get("postdeploy", {})
    post_result = post_facts.get("result", {})
    lines += ["- POST_DEPLOY: `%s`; stopped before SQL: `%s`; statements/scalars: `%s/%s`. "
              "Registration only; scheduled execution and live JAR rollback are not covered." % (
                  post_facts.get("status", "MISSING"), post_facts.get("stopped_before_sql", False),
                  post_result.get("statement_count", 0), post_result.get("scalar_count", 0)), ""]
    if summary["through"] == post.RESTART_MODE:
        lines += ["- POST_RESTART: `%s`; same candidate, new JVM; five local task effects and commerce UNKNOWN rejection. "
                  "Alarm attempt is not delivery; real provider, live rollback and production remain unverified." %
                  summary.get("post_restart", {}).get("status", "MISSING"), ""]
    if summary["through"] == "post_deploy":
        lines += ["- Scenario: `EXISTING_OPERATOR_ROWS`; commerce incompatible configuration: `%s`. "
                  "Expected original SQL rejection is a negative-control result, not migration PASS; "
                  "3 unique migrations (repeated observations are not additional coverage)." %
                  summary.get("postdeploy_commerce_rejection", {}).get("result", {}).get("status", "MISSING"), ""]
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
