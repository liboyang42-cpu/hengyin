#!/usr/bin/env python3
"""候选真存储回归（手动入口）：按登记表逐类在独占 MySQL / Redis 上真跑，并从 surefire 报告回读。

由 .github/workflows/real-storage-it.yml 调用；本机只跑负控 ci/test_real_storage_it.py。

  plan          读 SOURCE_SHA / BATCH / CLASSES / TIMEZONE，校验并静态预检整批，输出 matrix（一类一个 job）
  precheck      核对候选源码里的类文件、@Test 数、@Nested、门控变量与登记表一致，给出 JDK
  storage-start 起本 job 独占的 MySQL 和/或 Redis 容器，核身份并记录服务端事实
  run           只把该类自己的门控与连接变量交给 mvn，跑完回读报告、记录清理证据、给出结论
  storage-stop  删除本 job 的容器并记录删除结果
  summarize     汇总整批回执，任一类缺回执或未通过即失败
  m1-pair-plan  仅原 hint10+lead4，两个完整候选分别沿用原预检，输出同 run 四 job 计划
  m1-pair-verify 核对两侧当前 run/attempt/source/原10+4/CLEAN，随后仍须冻结 canonical 比较

★ 回执≠观测：mvn 退出 0 不算通过。必须读回该类（含 @Nested）实跑条数 == 登记数，
  skipped / failures / errors 全为 0，没有重复条目，也没混进别的类。缺任何一项都 fail-closed。
★ 本入口只证明「该源码 SHA 的这些测试在真 MySQL / Redis 上通过」，不证明真 HTTP、真机业务、
  合并后的候选、迁移在持久库执行或部署。每份回执的 not_covered 逐项写明。
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REGISTRY_PATH = Path(__file__).resolve().parent / "real-storage-it-registry.json"
RECEIPT_SCHEMA = "CY-REAL-STORAGE-IT-RECEIPT-20261019"
SUMMARY_SCHEMA = "CY-REAL-STORAGE-IT-BATCH-20261019"

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
FQCN_RE = re.compile(r"^(?:[a-z][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_]*$")
SIMPLE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*$")
BATCH_RE = re.compile(r"^[a-z0-9][a-z0-9-]*$")
REVIEWED_APPROVAL_GATES = ("B_PAYMENT_INTENT_DDL_APPROVED", "B_FUNDS_PROCESS_RECOVERY_APPROVED")
GATE_NAME_RE = re.compile(r"^(?:CHENGYIN_[A-Z0-9_]+|%s)$" %
                          "|".join(re.escape(name) for name in REVIEWED_APPROVAL_GATES))
GATE_VALUE_RE = re.compile(r"^[A-Za-z0-9_.-]+$")
IDENT_RE = re.compile(r"^[A-Za-z0-9_]+$")
MODULE_RE = re.compile(r"^[A-Za-z0-9_.-]+$")
PINNED_IMAGE_RE = re.compile(r"^[a-z0-9./-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$")
# A03EndingSnapshotMySqlIT 对 CHENGYIN_MYBATIS_IT_DB_SUFFIX 的格式要求；生成的后缀必须同时满足它。
A03_SUFFIX_RE = re.compile(r"^_[a-z0-9_]{1,24}$")
# OWN_C01_51Test 的库名是 base + "_own_c01_51_" + 32 位 UUID + 后缀，MySQL 上限 64：base + 后缀 ≤ 20。
SUFFIX_MAX_LEN = 12
TIMEZONES = {"UTC": "+00:00", "Asia/Shanghai": "+08:00"}
STORAGE_KINDS = ("mysql", "redis")
CLASS_KEYS = {"tests", "storage", "gates", "database_base", "nested", "note"}
BAD_STATUSES = ("failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError")
MYSQL_FACTS = (
    ("version", "VERSION()"),
    ("version_comment", "@@version_comment"),
    ("sql_mode", "@@GLOBAL.sql_mode"),
    ("transaction_isolation", "@@GLOBAL.transaction_isolation"),
    ("time_zone", "@@GLOBAL.time_zone"),
    ("system_time_zone", "@@system_time_zone"),
    ("character_set_server", "@@character_set_server"),
    ("collation_server", "@@collation_server"),
    ("lower_case_table_names", "@@lower_case_table_names"),
    ("innodb_lock_wait_timeout", "@@innodb_lock_wait_timeout"),
    ("log_bin", "@@log_bin"),
    ("autocommit", "@@GLOBAL.autocommit"),
)
MYSQL_SYSTEM_SCHEMAS = {"information_schema", "mysql", "performance_schema", "sys"}
NOT_COVERED = [
    "runtime HTTP with real identities",
    "device or business acceptance",
    "integrated cross-branch candidate",
    "migration execution on a persistent database",
    "merge or deployment",
]


class GateError(Exception):
    """缺证据或不一致：结束本步并写进回执（fail-closed）。"""


# ---------------------------------------------------------------- 登记表

def load_registry(path=REGISTRY_PATH):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def simple_name(fqcn):
    return fqcn.rsplit(".", 1)[-1]


def validate_class_entry(fqcn, entry):
    problems = []
    if not FQCN_RE.match(fqcn):
        problems.append("类名不是全限定名: %s" % fqcn)
    unknown = set(entry) - CLASS_KEYS
    if unknown:
        problems.append("%s 含未知字段 %s" % (fqcn, sorted(unknown)))
    tests = entry.get("tests")
    if not isinstance(tests, int) or isinstance(tests, bool) or tests < 1:
        problems.append("%s.tests 必须是正整数" % fqcn)
    storage = entry.get("storage")
    if (not isinstance(storage, list) or not storage or len(set(storage)) != len(storage)
            or any(kind not in STORAGE_KINDS for kind in storage)):
        problems.append("%s.storage 必须是 mysql/redis 的非空不重复子集" % fqcn)
    gates = entry.get("gates")
    if not isinstance(gates, dict):
        problems.append("%s.gates 必须是对象（没有门控写 {}）" % fqcn)
    else:
        for name, value in gates.items():
            if name == "CHENGYIN_RESTORE_MODE":
                problems.append("%s: CHENGYIN_RESTORE_MODE 不是测试审批门，parent 必须不存在" % fqcn)
            if not GATE_NAME_RE.match(name) or not isinstance(value, str) or not GATE_VALUE_RE.match(value):
                problems.append("%s 的门控 %s=%r 不合规" % (fqcn, name, value))
    base = entry.get("database_base")
    if base is not None and (not isinstance(base, str) or not IDENT_RE.match(base)):
        problems.append("%s.database_base 只允许字母数字下划线" % fqcn)
    nested = entry.get("nested", [])
    if not isinstance(nested, list) or any(not isinstance(n, str) or not SIMPLE_RE.match(n) for n in nested):
        problems.append("%s.nested 必须是嵌套类简单名列表" % fqcn)
    return problems


def validate_registry(registry):
    problems = []
    if not MODULE_RE.match(str(registry.get("module", ""))):
        problems.append("module 非法")
    images = registry.get("images", {})
    for kind in STORAGE_KINDS:
        if not PINNED_IMAGE_RE.match(str(images.get(kind, ""))):
            problems.append("images.%s 必须带 tag 并钉 sha256 digest" % kind)
    version = str(registry.get("mysql_version", ""))
    if not re.match(r"^\d+\.\d+\.\d+$", version) or not str(images.get("mysql", "")).startswith("mysql:%s@" % version):
        problems.append("mysql 镜像 tag 与 mysql_version 不一致")
    jdks = registry.get("jdk_for_java_version", {})
    if not jdks or any(not re.match(r"^\d+$", str(jdk)) for jdk in jdks.values()):
        problems.append("jdk_for_java_version 必须把 java.version 映射到 JDK 主版本号")
    default_base = str(registry.get("default_database_base", ""))
    if not IDENT_RE.match(default_base):
        problems.append("default_database_base 只允许字母数字下划线")
    elif len(default_base) + SUFFIX_MAX_LEN > 20:
        problems.append("default_database_base 过长：OWN_C01_51Test 的库名会超过 MySQL 64 字符")
    classes = registry.get("classes", {})
    for fqcn, entry in classes.items():
        problems.extend(validate_class_entry(fqcn, entry))
    batches = registry.get("batches", {})
    if not batches:
        problems.append("没有登记批次")
    used = set()
    for name, batch in batches.items():
        if not BATCH_RE.match(name):
            problems.append("批次名不合规: %s" % name)
        if not SHA_RE.match(str(batch.get("registered_source", ""))):
            problems.append("批次 %s 的 registered_source 必须是完整 40 位 SHA" % name)
        members = batch.get("classes", [])
        if not members:
            problems.append("批次 %s 没有类" % name)
        if len(set(members)) != len(members):
            problems.append("批次 %s 内类重复" % name)
        simples = [simple_name(member) for member in members]
        if len(set(simples)) != len(simples):
            problems.append("批次 %s 内简单类名重复（job/artifact 名会撞）" % name)
        for member in members:
            if member not in classes:
                problems.append("批次 %s 引用未登记的类 %s" % (name, member))
        used.update(members)
    for fqcn in classes:
        if fqcn not in used:
            problems.append("类 %s 未进入任何批次" % fqcn)
    return problems


def require_valid_registry(registry):
    problems = validate_registry(registry)
    if problems:
        raise GateError("登记表不合规:\n  " + "\n  ".join(problems))


# ---------------------------------------------------------------- 环境

def database_suffix(run_id, attempt):
    """一 job 一台新 MySQL，库名天然独占；后缀仍按 run 区分，并满足 A03 格式与 64 字符预算。"""
    suffix = "_gh%06da%d" % (int(run_id) % 1000000, int(attempt))
    if len(suffix) > SUFFIX_MAX_LEN or not A03_SUFFIX_RE.match(suffix):
        raise GateError("生成的库名后缀不合规: %s" % suffix)
    return suffix


def class_env(registry, fqcn, mysql_port=None, redis_port=None, suffix="", timezone="UTC"):
    """该类专属的变量：只开它自己的门控；连接变量全部显式给出（多数类拒绝 helper 默认值）。"""
    entry = registry["classes"][fqcn]
    if timezone not in TIMEZONES:
        raise GateError("未登记的时区: %s" % timezone)
    env = {"TZ": timezone}
    env.update(entry.get("gates", {}))
    if "mysql" in entry["storage"]:
        if mysql_port is None:
            raise GateError("%s 需要 MySQL，但没有 MySQL 端口" % fqcn)
        if not suffix:
            raise GateError("%s 需要非空库名后缀" % fqcn)
        port = str(int(mysql_port))
        env.update({
            # MysqlIntegrationTestConfig 家族：六项都显式给，空口令也要显式存在。
            "CHENGYIN_TEST_MYSQL_HOST": "127.0.0.1",
            "CHENGYIN_TEST_MYSQL_PORT": port,
            "CHENGYIN_TEST_MYSQL_DATABASE_BASE": entry.get("database_base", registry["default_database_base"]),
            "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX": suffix,
            "CHENGYIN_TEST_MYSQL_USER": "root",
            "CHENGYIN_TEST_MYSQL_PASSWORD": "",
            # MybatisItDatabase 家族（OWN_C04_19 / A03 / Journey / 撤销核销冲回）。
            "CHENGYIN_MYBATIS_IT_HOST": "127.0.0.1",
            "CHENGYIN_MYBATIS_IT_PORT": port,
            "CHENGYIN_MYBATIS_IT_USER": "root",
            "CHENGYIN_MYBATIS_IT_PASSWORD": "",
            "CHENGYIN_MYBATIS_IT_DB_SUFFIX": suffix,
            # AI 恢复类自己的 MySQL 变量，主机在源码里固定为 127.0.0.1。
            "CHENGYIN_AI_MYSQL_IT_PORT": port,
            "CHENGYIN_AI_MYSQL_IT_USER": "root",
            "CHENGYIN_AI_MYSQL_IT_PASSWORD": "",
        })
    if "redis" in entry["storage"]:
        if redis_port is None:
            raise GateError("%s 需要 Redis，但没有 Redis 端口" % fqcn)
        env.update({
            "CHENGYIN_AI_REDIS_IT_HOST": "127.0.0.1",
            "CHENGYIN_AI_REDIS_IT_PORT": str(int(redis_port)),
            "CHENGYIN_AI_REDIS_IT_DATABASE": "0",
        })
    return env


def public_env(env):
    """回执里只留可公开的形状：口令一律不落值，只记是否显式为空。"""
    shown = {}
    for key, value in sorted(env.items()):
        if key.endswith("_PASSWORD"):
            shown[key] = "<explicit-empty>" if value == "" else "<redacted>"
        else:
            shown[key] = value
    return shown


def child_env(base, extra):
    """去掉继承来的 CHENGYIN_*，只留本类登记的那组，避免别的门控把其它测试一起打开。"""
    require_normal_parent(base)
    require_normal_parent(extra)
    env = {key: value for key, value in base.items()
           if not key.startswith("CHENGYIN_") and key not in REVIEWED_APPROVAL_GATES}
    env.update(extra)
    return env


def require_normal_parent(environ):
    if "CHENGYIN_RESTORE_MODE" in environ:
        raise GateError("CHENGYIN_RESTORE_MODE 必须在验收 parent 环境中不存在，不能设空/false或静默删除")


def require_class_source(fqcn, source_sha, environ):
    # This actual Java owner binds GITHUB_SHA to its loaded class/source receipts.
    # Do not forge GitHub context for an input SHA; dispatch from the same candidate.
    if fqcn == "com.chengyinhub.business.service.impl.FundsProcessRecoveryMySqlIT" \
            and environ.get("GITHUB_SHA") != source_sha:
        raise GateError("FundsProcessRecoveryMySqlIT 必须从同一候选 workflow SHA 派发；不得覆盖 GITHUB_SHA")


# ---------------------------------------------------------------- 计划

def plan(registry, source_sha, batch, classes_csv, timezone):
    if not SHA_RE.match(source_sha or ""):
        raise GateError("source_sha 必须是完整 40 位小写 commit SHA: %r" % source_sha)
    if batch not in registry["batches"]:
        raise GateError("未登记的批次: %r" % batch)
    if timezone not in TIMEZONES:
        raise GateError("未登记的时区: %r" % timezone)
    members = registry["batches"][batch]["classes"]
    wanted = [item.strip() for item in (classes_csv or "").split(",") if item.strip()]
    if wanted:
        bad = [item for item in wanted if not SIMPLE_RE.match(item)]
        if bad:
            raise GateError("classes 只接受逗号分隔的简单类名: %s" % bad)
        if len(set(wanted)) != len(wanted):
            raise GateError("classes 有重复: %s" % wanted)
        known = {simple_name(member) for member in members}
        unknown = [item for item in wanted if item not in known]
        if unknown:
            raise GateError("这些类不在批次 %s 内: %s" % (batch, unknown))
        members = [member for member in members if simple_name(member) in set(wanted)]
    return [{"class": member, "simple": simple_name(member)} for member in members]


# ---------------------------------------------------------------- 静态预检

TEST_RE = re.compile(r"@(?:org\.junit\.jupiter\.api\.)?Test(?![A-Za-z0-9_$])")
DYNAMIC_RE = re.compile(r"@(?:[\w.]*\.)?(ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)(?![A-Za-z0-9_$])")
NESTED_RE = re.compile(
    r"@(?:org\.junit\.jupiter\.api\.)?Nested(?![A-Za-z0-9_$])"
    r"(?:\s*@[\w.]+(?:\s*\((?:[^()]|\([^()]*\))*\))?)*"
    r"\s*(?:(?:public|protected|private|static|final|abstract)\s+)*class\s+(\w+)")
GATE_START_RE = re.compile(r"@(?:[\w.]*\.)?EnabledIfEnvironmentVariable\s*\(")
CONDITION_RE = re.compile(r"@(?:[\w.]*\.)?((?:Disabled|Enabled)\w*)(?![A-Za-z0-9_$])")
JAVA_VERSION_RE = re.compile(r"<java\.version>\s*([^<\s]+)\s*</java\.version>")
# 部分类在 @BeforeAll 里写死 assertEquals("e04_scratch", System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_BASE"))；
# 登记的 database_base 不一致时整类只报 initializationError，静态预检要提前拦下。
REQUIRED_BASE_RE = re.compile(
    r'assertEquals\s*\(\s*"([A-Za-z0-9_]+)"\s*,\s*System\.getenv\s*\(\s*"CHENGYIN_TEST_MYSQL_DATABASE_BASE"\s*\)')


def strip_java_comments(source, keep_strings=True):
    """去掉 // 与 /* */ 注释（"jdbc:mysql://..." 里的 // 不是注释）。

    keep_strings=False 时把字符串字面量清成空串：数注解时字符串里的 "@Test" 不能算数。
    """
    out = []
    i = 0
    n = len(source)
    while i < n:
        if source.startswith("//", i):
            end = source.find("\n", i)
            i = n if end < 0 else end
            continue
        if source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = n if end < 0 else end + 2
            out.append(" ")
            continue
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(source[i:end] if keep_strings else '""')
            i = end
            continue
        char = source[i]
        if char in "\"'":
            j = i + 1
            while j < n and source[j] != char and source[j] != "\n":
                j += 2 if source[j] == "\\" else 1
            out.append(source[i:j + 1] if keep_strings else char * 2)
            i = j + 1
            continue
        out.append(char)
        i += 1
    return "".join(out)


def annotation_args(code, open_paren):
    depth = 0
    in_string = False
    i = open_paren
    while i < len(code):
        char = code[i]
        if in_string:
            if char == "\\":
                i += 2
                continue
            if char == '"':
                in_string = False
        elif char == '"':
            in_string = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return code[open_paren + 1:i]
        i += 1
    return None


def java_string_value(args, key):
    match = re.search(r"\b%s\s*=\s*\"((?:[^\"\\]|\\.)*)\"" % key, args or "")
    if match is None:
        return None
    return re.sub(r"\\(.)", r"\1", match.group(1))


def scan_java(source):
    code = strip_java_comments(source)
    bare = strip_java_comments(source, keep_strings=False)
    gates = []
    for match in GATE_START_RE.finditer(code):
        args = annotation_args(code, match.end() - 1)
        gates.append((java_string_value(args, "named"), java_string_value(args, "matches")))
    # Reviewed assertion gates fail before DDL/process faults rather than silently skip.
    for name in REVIEWED_APPROVAL_GATES:
        pattern = r'^\s*assertEquals\(\s*"1"\s*,\s*System\.getenv\(\s*"%s"\s*\)\s*\);' % re.escape(name)
        if re.search(pattern, code, re.MULTILINE):
            gates.append((name, "1"))
    return {
        "declared_tests": len(TEST_RE.findall(bare)),
        "dynamic": sorted(set(DYNAMIC_RE.findall(bare))),
        "nested": NESTED_RE.findall(bare),
        "gates": gates,
        "conditions": sorted({name for name in CONDITION_RE.findall(bare) if name != "EnabledIfEnvironmentVariable"}),
        "required_database_bases": sorted(set(REQUIRED_BASE_RE.findall(code))),
    }


def check_gates(source_gates, registry_gates):
    problems = []
    declared = collections.OrderedDict()
    for name, pattern in source_gates:
        if not name or pattern is None:
            problems.append("无法解析 @EnabledIfEnvironmentVariable 的 named/matches")
            continue
        declared.setdefault(name, []).append(pattern)
    for name, patterns in declared.items():
        value = registry_gates.get(name)
        if value is None:
            problems.append("源码门控 %s 在登记表没有值：该类或其嵌套类会被静默跳过" % name)
            continue
        for pattern in patterns:
            try:
                matched = re.fullmatch(pattern, value) is not None
            except re.error:
                matched = False
            if not matched:
                problems.append("登记值 %s=%r 不满足源码 matches=%r" % (name, value, pattern))
    for name in registry_gates:
        if name not in declared:
            problems.append("登记表给了源码未声明的门控 %s（登记表过期）" % name)
    return problems


def read_java_version(pom_path):
    try:
        text = Path(pom_path).read_text(encoding="utf-8")
    except OSError as error:
        raise GateError("读不到候选根 pom.xml: %s" % error)
    match = JAVA_VERSION_RE.search(text)
    return match.group(1) if match else None


def precheck(registry, source_dir, fqcn):
    entry = registry["classes"].get(fqcn)
    if entry is None:
        raise GateError("未登记的类: %s" % fqcn)
    relative = Path(registry["module"]) / "src" / "test" / "java" / (fqcn.replace(".", "/") + ".java")
    path = Path(source_dir) / relative
    if not path.is_file():
        raise GateError("候选源码里没有 %s" % relative.as_posix())
    scan = scan_java(path.read_text(encoding="utf-8"))
    problems = []
    if scan["dynamic"]:
        problems.append("含 %s：实跑条数不等于声明数，本入口不接" % ", ".join(scan["dynamic"]))
    if scan["conditions"]:
        problems.append("含未登记的条件注解 %s，可能静默跳过" % ", ".join(scan["conditions"]))
    if scan["declared_tests"] != entry["tests"]:
        problems.append("源码声明 %d 条 @Test，登记 %d 条；登记表需评审后更新"
                        % (scan["declared_tests"], entry["tests"]))
    if sorted(scan["nested"]) != sorted(entry.get("nested", [])):
        problems.append("@Nested 类 %s 与登记 %s 不一致（surefire 只在显式点名嵌套类时才跑它的方法）"
                        % (sorted(scan["nested"]), sorted(entry.get("nested", []))))
    problems.extend(check_gates(scan["gates"], entry.get("gates", {})))
    base = entry.get("database_base", registry.get("default_database_base"))
    for required in scan["required_database_bases"]:
        if required != base:
            problems.append("源码要求 CHENGYIN_TEST_MYSQL_DATABASE_BASE=%s，登记给的是 %s" % (required, base))
    java_version = read_java_version(Path(source_dir) / "pom.xml")
    jdk = registry["jdk_for_java_version"].get(java_version or "")
    if jdk is None:
        problems.append("未登记 java.version=%r 对应的 JDK" % java_version)
    facts = {
        "path": relative.as_posix(),
        "declared_tests": scan["declared_tests"],
        "nested": scan["nested"],
        "source_gates": [{"named": name, "matches": pattern} for name, pattern in scan["gates"]],
        "java_version": java_version,
        "jdk": jdk,
    }
    return facts, problems


# ---------------------------------------------------------------- surefire 回读

def collect_testcases(reports_dir):
    cases = []
    directory = Path(reports_dir)
    if not directory.is_dir():
        return cases
    for path in sorted(directory.glob("TEST-*.xml")):
        try:
            root = ET.parse(str(path)).getroot()
        except ET.ParseError as error:
            raise GateError("surefire 报告不可解析 %s: %s" % (path.name, error))
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            for case in suite.iter("testcase"):
                status = "passed"
                for tag in BAD_STATUSES:
                    if case.find(tag) is not None:
                        status = tag
                        break
                cases.append({
                    "classname": case.get("classname", ""),
                    "name": case.get("name", ""),
                    "time": case.get("time"),
                    "status": status,
                    "report": path.name,
                })
    return cases


def verify_class_reports(cases, fqcn, expected, nested=()):
    mine = [case for case in cases if case["classname"] == fqcn or case["classname"].startswith(fqcn + "$")]
    others = sorted({case["classname"] for case in cases if case not in mine})
    statuses = collections.Counter(case["status"] for case in mine)
    keys = collections.Counter((case["classname"], case["name"]) for case in mine)
    problems = []
    if not mine:
        problems.append("surefire 报告里没有该类的用例：未被选中或根本没跑")
    for status in BAD_STATUSES:
        if statuses[status]:
            problems.append("%d 条 %s" % (statuses[status], status))
    duplicates = sorted("%s#%s" % key for key, count in keys.items() if count > 1)
    if duplicates:
        problems.append("用例重复出现（跑了两遍或报了两遍）: %s" % duplicates)
    if len(mine) != expected:
        problems.append("实跑 %d 条，登记 %d 条" % (len(mine), expected))
    executed_classes = sorted({case["classname"] for case in mine})
    for name in nested:
        if "%s$%s" % (fqcn, name) not in executed_classes:
            problems.append("嵌套类 %s 没有任何用例被执行" % name)
    if others:
        problems.append("混进了别的类: %s" % others)
    summary = {
        "tests": len(mine),
        "passed": statuses["passed"],
        "skipped": statuses["skipped"],
        "failures": statuses["failure"],
        "errors": statuses["error"],
        "classes": executed_classes,
        "testcases": [{key: case[key] for key in ("classname", "name", "status", "time")} for case in mine],
    }
    return summary, problems


# ---------------------------------------------------------------- 回执

def receipt_path(out_dir):
    return Path(out_dir) / "receipt.json"


def read_receipt(out_dir):
    path = receipt_path(out_dir)
    if not path.is_file():
        return {}
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def merge_receipt(out_dir, patch):
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    receipt = read_receipt(out_dir)
    receipt.update(patch)
    path = receipt_path(out_dir)
    temporary = path.with_suffix(".tmp")
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(receipt, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    temporary.replace(path)
    return receipt


def fail_receipt(out_dir, stage, problems):
    receipt = read_receipt(out_dir)
    recorded = list(receipt.get("problems", []))
    recorded.extend("%s: %s" % (stage, problem) for problem in problems)
    merge_receipt(out_dir, {"verdict": "FAIL", "problems": recorded})


def workflow_facts(environ):
    run_id = environ.get("GITHUB_RUN_ID", "0")
    return {
        "repository": environ.get("GITHUB_REPOSITORY"),
        "run_id": run_id,
        "run_attempt": environ.get("GITHUB_RUN_ATTEMPT", "1"),
        "workflow_sha": environ.get("GITHUB_SHA"),
        "workflow_ref": environ.get("GITHUB_REF"),
        "job": environ.get("GITHUB_JOB"),
        "run_url": "%s/%s/actions/runs/%s" % (environ.get("GITHUB_SERVER_URL", "https://github.com"),
                                              environ.get("GITHUB_REPOSITORY", ""), run_id),
    }


def required_env(environ, name):
    value = environ.get(name, "")
    if not value:
        raise GateError("缺环境变量 %s" % name)
    return value


def write_github_output(path, values):
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        for key, value in values.items():
            if "\n" in value:
                raise GateError("GITHUB_OUTPUT 值不能多行: %s" % key)
            handle.write("%s=%s\n" % (key, value))


def append_step_summary(environ, text):
    path = environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(text.rstrip("\n") + "\n")


# ---------------------------------------------------------------- 容器

def docker(*args, check=True):
    process = subprocess.run(["docker"] + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             universal_newlines=True)
    if check and process.returncode != 0:
        raise GateError("docker %s 失败(%d): %s" % (args[0], process.returncode, process.stderr.strip()[-800:]))
    return process.stdout


def container_names():
    return set(docker("ps", "-a", "--format", "{{.Names}}").split())


def mapped_port(name, container_port):
    for line in docker("port", name, "%d/tcp" % container_port).splitlines():
        host, _, port = line.strip().rpartition(":")
        if host == "127.0.0.1" and port.isdigit():
            return int(port)
    raise GateError("%s 没有发布到 127.0.0.1 的 %d 端口" % (name, container_port))


def mysql_handshake_version(port):
    """主机侧读 MySQL 握手包里的版本串：证明发布端口连到的就是本容器的正式服务。"""
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2) as connection:
            data = connection.recv(256)
    except OSError:
        return None
    if len(data) < 6 or data[4] != 0x0A:
        return None
    end = data.find(b"\x00", 5)
    return data[5:end].decode("ascii", "replace") if end > 5 else None


def mysql_query(name, sql):
    output = docker("exec", name, "mysql", "-N", "-B", "-uroot", "-e", sql)
    return [line.split("\t") for line in output.splitlines() if line]


def mysql_schemas(name):
    return sorted(row[0] for row in mysql_query(name, "SELECT schema_name FROM information_schema.schemata"))


def start_mysql(registry, name, timezone):
    offset = TIMEZONES[timezone]
    version = registry["mysql_version"]
    image = registry["images"]["mysql"]
    docker("rm", "-f", "-v", name, check=False)
    docker("run", "-d", "--name", name, "-e", "MYSQL_ALLOW_EMPTY_PASSWORD=yes", "-p", "127.0.0.1::3306",
           image, "--default-time-zone=" + offset)
    port = mapped_port(name, 3306)
    deadline = time.monotonic() + 240
    ready = False
    while time.monotonic() < deadline:
        seen = mysql_handshake_version(port)
        if seen is not None and seen.split("-")[0] == version:
            try:
                ready = mysql_query(name, "SELECT 1") == [["1"]]
            except GateError:
                ready = False
            if ready:
                break
        time.sleep(2)
    if not ready:
        logs = docker("logs", "--tail", "40", name, check=False)
        raise GateError("MySQL 240s 内未就绪，或 127.0.0.1:%d 上不是 %s\n%s" % (port, version, logs))
    row = mysql_query(name, "SELECT " + ", ".join(expression for _, expression in MYSQL_FACTS))[0]
    if len(row) != len(MYSQL_FACTS):
        raise GateError("MySQL 服务端事实列数不符: %r" % row)
    facts = dict(zip((key for key, _ in MYSQL_FACTS), row))
    if facts["version"] != version or facts["version_comment"] != "MySQL Community Server - GPL":
        raise GateError("MySQL 身份不符: %s / %s" % (facts["version"], facts["version_comment"]))
    if facts["time_zone"] != offset:
        raise GateError("MySQL time_zone=%s，期望 %s" % (facts["time_zone"], offset))
    schemas = mysql_schemas(name)
    if set(schemas) - MYSQL_SYSTEM_SCHEMAS:
        raise GateError("新起的 MySQL 已有业务库: %s" % schemas)
    facts.update({"container": name, "image": image, "port": port, "schemas_before": schemas})
    return facts


def redis_ping(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2) as connection:
            connection.sendall(b"PING\r\n")
            return connection.recv(64).startswith(b"+PONG")
    except OSError:
        return False


def redis_dbsize(name):
    return int(docker("exec", name, "redis-cli", "DBSIZE").strip())


def start_redis(registry, name):
    image = registry["images"]["redis"]
    docker("rm", "-f", "-v", name, check=False)
    docker("run", "-d", "--name", name, "-p", "127.0.0.1::6379", image,
           "redis-server", "--save", "", "--appendonly", "no")
    port = mapped_port(name, 6379)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if docker("exec", name, "redis-cli", "PING", check=False).strip() == "PONG" and redis_ping(port):
            break
        time.sleep(1)
    else:
        raise GateError("Redis 60s 内未就绪: %s" % name)
    info = docker("exec", name, "redis-cli", "INFO", "server")
    version = re.search(r"^redis_version:(\S+)", info, re.MULTILINE)
    size = redis_dbsize(name)
    if size != 0:
        raise GateError("新起的 Redis 已有 %d 个键" % size)
    return {"container": name, "image": image, "port": port,
            "version": version.group(1) if version else None, "dbsize_before": size}


def container_name(environ, simple, kind):
    return "cy-rs-%s-%s-%s-%s" % (environ.get("GITHUB_RUN_ID", "0"), environ.get("GITHUB_RUN_ATTEMPT", "1"),
                                  simple.lower(), kind)


# ---------------------------------------------------------------- 执行

def first_line(command):
    try:
        process = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 universal_newlines=True)
    except OSError as error:
        return "unavailable: %s" % error
    lines = [line for line in process.stdout.splitlines() if line.strip()]
    return lines[0].strip() if lines else ""


# Maven 3.9+ 打印 "--- surefire:3.6.0:test (default-test) @ 模块 ---"，旧版打印 "maven-surefire-plugin:x:test"。
SUREFIRE_RE = re.compile(r"--- (?:maven-)?surefire(?:-plugin)?:([0-9][^:\s]*):test(?: \([^)]*\))?(?: @ (\S+))?")


def surefire_version(log_text, module):
    """-am 会先跑依赖模块的 surefire；优先取目标模块那一行的版本。"""
    found = SUREFIRE_RE.findall(log_text)
    for version, name in found:
        if name == module:
            return version
    return found[-1][0] if found else None


def run_maven(source_dir, module, selectors, env, log_path):
    command = ["mvn", "-B", "-ntp", "-f", str(Path(source_dir) / "pom.xml"), "-pl", module, "-am", "test",
               "-Dtest=" + ",".join(selectors), "-Dsurefire.failIfNoSpecifiedTests=false", "-DfailIfNoTests=false"]
    print("+ " + " ".join(command), flush=True)
    with open(log_path, "w", encoding="utf-8") as log:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env,
                                   universal_newlines=True)
        for line in process.stdout:
            sys.stdout.write(line)
            log.write(line)
        return process.wait()


def cmd_plan(args, environ):
    registry = load_registry()
    require_valid_registry(registry)
    source_sha = environ.get("SOURCE_SHA", "")
    batch = environ.get("BATCH", "")
    matrix = plan(registry, source_sha, batch, environ.get("CLASSES", ""), environ.get("TIMEZONE", ""))
    head = subprocess.run(["git", "-C", args.source, "rev-parse", "HEAD"], stdout=subprocess.PIPE,
                          universal_newlines=True).stdout.strip()
    if head != source_sha:
        raise GateError("候选检出 HEAD=%s，不是 %s" % (head, source_sha))
    problems = []
    for item in matrix:
        require_class_source(item["class"], source_sha, environ)
        _, found = precheck(registry, args.source, item["class"])
        problems.extend("%s: %s" % (item["simple"], problem) for problem in found)
    if problems:
        raise GateError("静态预检未过（整批不起 runner）:\n  " + "\n  ".join(problems))
    registered = registry["batches"][batch]["registered_source"]
    lines = ["### Real storage plan: `%s` @ `%s`" % (batch, source_sha[:12]), "",
             "registered source `%s`%s, timezone `%s`" % (registered[:12], "" if registered == source_sha
                                                          else " (differs)", environ.get("TIMEZONE")), "",
             "| class | tests | storage |", "|---|---|---|"]
    for item in matrix:
        entry = registry["classes"][item["class"]]
        lines.append("| %s | %d | %s |" % (item["simple"], entry["tests"], "+".join(entry["storage"])))
    print("\n".join(lines))
    append_step_summary(environ, "\n".join(lines))
    write_github_output(args.github_output, {"matrix": json.dumps(matrix, separators=(",", ":")),
                                             "sha12": source_sha[:12]})
    return 0


def class_context(environ):
    registry = load_registry()
    require_valid_registry(registry)
    fqcn = required_env(environ, "TEST_CLASS")
    if fqcn not in registry["classes"]:
        raise GateError("未登记的类: %s" % fqcn)
    return registry, fqcn


def cmd_precheck(args, environ):
    registry, fqcn = class_context(environ)
    source_sha = required_env(environ, "SOURCE_SHA")
    batch = required_env(environ, "BATCH")
    registered = registry["batches"].get(batch, {}).get("registered_source")
    merge_receipt(args.out, {
        "schema": RECEIPT_SCHEMA,
        "class": fqcn,
        "batch": batch,
        "source_sha": source_sha,
        "registered_source": registered,
        "registered_source_match": registered == source_sha,
        "timezone": environ.get("TIMEZONE"),
        "expected_tests": registry["classes"][fqcn]["tests"],
        "workflow": workflow_facts(environ),
        "layer": "REAL_STORAGE",
        "not_covered": NOT_COVERED,
        "verdict": "INCOMPLETE",
        "problems": [],
    })
    require_normal_parent(environ)
    require_class_source(fqcn, source_sha, environ)
    head = subprocess.run(["git", "-C", args.source, "log", "-1", "--format=%H%x00%s%x00%cI"],
                          stdout=subprocess.PIPE, universal_newlines=True).stdout.strip().split("\x00")
    if head[0] != source_sha:
        raise GateError("候选检出 HEAD=%s，不是 %s" % (head[0], source_sha))
    facts, problems = precheck(registry, args.source, fqcn)
    facts["candidate"] = {"sha": head[0], "subject": head[1] if len(head) > 1 else "",
                          "committed_at": head[2] if len(head) > 2 else ""}
    merge_receipt(args.out, {"precheck": facts})
    if problems:
        raise GateError("; ".join(problems))
    write_github_output(args.github_output, {"jdk": facts["jdk"]})
    return 0


def cmd_storage_start(args, environ):
    registry, fqcn = class_context(environ)
    timezone = required_env(environ, "TIMEZONE")
    if timezone not in TIMEZONES:
        raise GateError("未登记的时区: %s" % timezone)
    storage = registry["classes"][fqcn]["storage"]
    simple = simple_name(fqcn)
    # 先记容器名：起到一半失败时，storage-stop 仍能按名删除。
    planned = {kind: {"container": container_name(environ, simple, kind)} for kind in storage}
    merge_receipt(args.out, {"storage": planned})
    facts = {}
    if "mysql" in storage:
        facts["mysql"] = start_mysql(registry, planned["mysql"]["container"], timezone)
        merge_receipt(args.out, {"storage": dict(planned, **facts)})
    if "redis" in storage:
        facts["redis"] = start_redis(registry, planned["redis"]["container"])
    merge_receipt(args.out, {"storage": dict(planned, **facts)})
    return 0


def cmd_run(args, environ):
    registry, fqcn = class_context(environ)
    require_normal_parent(environ)
    require_class_source(fqcn, required_env(environ, "SOURCE_SHA"), environ)
    entry = registry["classes"][fqcn]
    receipt = read_receipt(args.out)
    storage = receipt.get("storage", {})
    if receipt.get("problems") or any("port" not in storage.get(kind, {}) for kind in entry["storage"]):
        raise GateError("前置步骤未完成（预检或起库失败），不执行测试")
    suffix = database_suffix(environ.get("GITHUB_RUN_ID", "0"), environ.get("GITHUB_RUN_ATTEMPT", "1"))
    extra = class_env(registry, fqcn, storage.get("mysql", {}).get("port"), storage.get("redis", {}).get("port"),
                      suffix, required_env(environ, "TIMEZONE"))
    selectors = [fqcn] + ["%s$%s" % (fqcn, name) for name in entry.get("nested", [])]
    module = registry["module"]
    reports = Path(args.source) / module / "target" / "surefire-reports"
    if reports.exists():
        shutil.rmtree(str(reports))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    started = time.time()
    exit_code = run_maven(args.source, module, selectors, child_env(environ, extra), out / "maven.log")
    log_text = (out / "maven.log").read_text(encoding="utf-8", errors="replace")
    surefire = surefire_version(log_text, module)
    cases = collect_testcases(reports)
    executed, problems = verify_class_reports(cases, fqcn, entry["tests"], entry.get("nested", []))
    if reports.is_dir():
        shutil.copytree(str(reports), str(out / "surefire-reports"), dirs_exist_ok=True)
    xml_hashes = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in sorted((out / "surefire-reports").glob("TEST-*.xml"))}
    if exit_code != 0:
        problems.append("mvn 退出码 %d" % exit_code)
    verdict = "FAIL" if problems else "PASS"
    # 先落执行结果，再查清理：容器若已异常退出，清理查询失败也不能吞掉已读回的用例。
    merge_receipt(args.out, {
        "toolchain": {"java": first_line(["java", "-version"]), "maven": first_line(["mvn", "-v"]),
                      "surefire": surefire},
        "env_profile": public_env(extra),
        "maven": {"exit_code": exit_code, "selectors": selectors, "log": "maven.log",
                  "seconds": round(time.time() - started, 1)},
        "executed": executed,
        "surefire_xml_sha256": xml_hashes,
        "verdict": verdict,
        "problems": ["run: %s" % problem for problem in problems],
    })
    cleanup = {}
    try:
        if "mysql" in entry["storage"]:
            after = mysql_schemas(storage["mysql"]["container"])
            before = storage["mysql"].get("schemas_before")
            if not after or not before or set(before) - set(after):
                raise GateError("MySQL 清理回读缺失或初始 schema 不再可见")
            cleanup["mysql_schemas_after"] = after
            cleanup["mysql_leftover_schemas"] = sorted(set(after) - set(before))
        if "redis" in entry["storage"]:
            cleanup["redis_dbsize_after"] = redis_dbsize(storage["redis"]["container"])
            if not isinstance(cleanup["redis_dbsize_after"], int) or cleanup["redis_dbsize_after"] < 0:
                raise GateError("Redis 清理回读缺失或不是非负键数")
        cleanup["status"] = "CLEAN" if not cleanup.get("mysql_leftover_schemas") and not cleanup.get(
            "redis_dbsize_after") else "LEFTOVER"
    except (GateError, ValueError) as error:
        cleanup.update(status="UNKNOWN", error=str(error))
    if cleanup.get("status") != "CLEAN":
        problems.append("cleanup %s: 必须独立回读 CLEAN" % cleanup.get("status", "MISSING"))
        verdict = "FAIL"
    merge_receipt(args.out, {"cleanup": cleanup, "verdict": verdict,
                             "problems": ["run: %s" % problem for problem in problems]})
    line = "%s: %s %d/%d (%s)" % (simple_name(fqcn), verdict, executed["tests"], entry["tests"],
                                  "; ".join(problems) if problems else "skipped=0, cleanup " + cleanup["status"])
    print(line)
    append_step_summary(environ, line)
    return 1 if problems else 0


def cmd_storage_stop(args, environ):
    # 删除结果只记录、不改结论：hosted runner 用完即毁，这里留的是清理证据，不是业务判定。
    receipt = read_receipt(args.out)
    teardown = {}
    for kind, info in sorted(receipt.get("storage", {}).items()):
        name = info.get("container")
        if not name:
            continue
        docker("rm", "-f", "-v", name, check=False)
        teardown[kind] = {"container": name, "removed": name not in container_names()}
    merge_receipt(args.out, {"teardown": teardown})
    for item in teardown.values():
        if not item["removed"]:
            print("::warning::容器未删除: %s" % item["container"])
    return 0


# ---------------------------------------------------------------- 汇总

def summarize(registry, receipts_dir, planned, batch, source_sha, workflow=None):
    latest = {}
    problems = []
    if workflow is not None and (workflow.get('repository') != 'liboyang42-cpu/hengyin'
            or not SHA_RE.fullmatch(workflow.get('workflow_sha') or '')
            or any(not re.fullmatch(r'[1-9][0-9]*', workflow.get(key) or '')
                   for key in ('run_id', 'run_attempt')) or not workflow.get('workflow_ref')):
        raise GateError('汇总必须绑定真实公共 workflow/source/run/attempt')
    for path in sorted(Path(receipts_dir).glob("*/receipt.json")):
        try:
            if path.parent.is_symlink() or path.is_symlink() or not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
                raise ValueError()
            with open(path, encoding="utf-8") as handle:
                receipt = json.load(handle)
            if not isinstance(receipt, dict):
                raise ValueError()
        except (OSError, ValueError):
            problems.append("receipt: 无法确认有界的原JSON回执")
            continue
        fqcn = receipt.get("class")
        if not isinstance(fqcn, str) or not fqcn or not isinstance(receipt.get("workflow", {}), dict):
            problems.append("receipt: class/workflow 类型无法确认")
            continue
        if workflow is not None:
            observed = receipt.get("workflow", {})
            if not isinstance(observed, dict) or any(observed.get(key) != workflow.get(key) for key in
                    ("repository", "run_id", "run_attempt", "workflow_sha", "workflow_ref")) \
                    or observed.get("job") != "storage_it":
                problems.append("receipt: 非本次 workflow/run/attempt，不能以最新attempt替代")
                continue
            if receipt.get("schema") != RECEIPT_SCHEMA or fqcn in latest:
                problems.append("receipt: 类型/重复原回执不能确认")
                continue
        try:
            attempt = int(receipt.get("workflow", {}).get("run_attempt") or 0)
        except (TypeError, ValueError):
            problems.append("receipt: attempt 无法确认")
            continue
        if fqcn and (fqcn not in latest or attempt > latest[fqcn][0]):
            latest[fqcn] = (attempt, receipt)
    planned_classes = [item["class"] for item in planned]
    rows = []
    for fqcn in planned_classes:
        expected = registry["classes"][fqcn]["tests"]
        receipt = latest.get(fqcn, (0, None))[1]
        if receipt is None:
            rows.append({"class": fqcn, "expected": expected, "executed": 0, "verdict": "MISSING"})
            problems.append("%s: 没有回执" % simple_name(fqcn))
            continue
        verdict = receipt.get("verdict")
        if receipt.get("source_sha") != source_sha or receipt.get("batch") != batch:
            verdict = "FAIL"
            problems.append("%s: 回执的 SHA/批次与本次不符" % simple_name(fqcn))
        elif verdict != "PASS":
            problems.append("%s: %s %s" % (simple_name(fqcn), verdict, receipt.get("problems")))
        cleanup = receipt.get("cleanup")
        cleanup_status = cleanup.get("status") if isinstance(cleanup, dict) else None
        if cleanup_status != "CLEAN":
            verdict = "FAIL"
            problems.append("%s: cleanup %s，必须有 CLEAN 回读" % (simple_name(fqcn), cleanup_status or "MISSING"))
        if workflow is not None:
            def object_field(value, key):
                field = value.get(key) if isinstance(value, dict) else None
                return field if isinstance(field, dict) else {}
            executed = object_field(receipt, "executed")
            maven = object_field(receipt, "maven")
            candidate = object_field(object_field(receipt, "precheck"), "candidate")
            teardown = object_field(receipt, "teardown")
            if (receipt.get("expected_tests") != expected or executed.get("tests") != expected
                    or type(receipt.get("expected_tests")) is not int
                    or type(executed.get("tests")) is not int
                    or any(type(executed.get(key)) is not int or executed.get(key) != 0
                           for key in ("failures", "errors", "skipped"))
                    or type(maven.get("exit_code")) is not int or maven.get("exit_code") != 0
                    or candidate.get("sha") != source_sha):
                verdict = "FAIL"
                problems.append("%s: 原JUnit/Maven/候选证据不完整" % simple_name(fqcn))
            for kind in registry["classes"][fqcn]["storage"]:
                observed_cleanup = (isinstance(cleanup, dict) and
                                    (cleanup.get("mysql_leftover_schemas") == [] if kind == "mysql"
                                     else type(cleanup.get("redis_dbsize_after")) is int
                                     and cleanup.get("redis_dbsize_after") == 0))
                if not observed_cleanup or object_field(teardown, kind).get("removed") is not True:
                    verdict = "FAIL"
                    problems.append("%s: %s 独立清理/原teardown缺失" % (simple_name(fqcn), kind))
        rows.append({
            "class": fqcn,
            "expected": expected,
            "executed": (receipt.get("executed", {}).get("tests", 0)
                         if isinstance(receipt.get("executed"), dict)
                         and type(receipt["executed"].get("tests")) is int else 0),
            "verdict": verdict,
            "cleanup": cleanup_status,
            "run_attempt": receipt.get("workflow", {}).get("run_attempt"),
        })
    for fqcn in sorted(set(latest) - set(planned_classes)):
        problems.append("%s: 不在本次计划内却有回执" % simple_name(fqcn))
    summary = {
        "schema": SUMMARY_SCHEMA,
        "batch": batch,
        "source_sha": source_sha,
        "registered_source": registry["batches"][batch]["registered_source"],
        # 定点复跑只覆盖所选的类；整批结论要把各次回执按类合起来看，不能拿子集冒充整批。
        "subset": len(planned_classes) != len(registry["batches"][batch]["classes"]),
        "classes": rows,
        "expected_tests": sum(row["expected"] for row in rows),
        "executed_tests": sum(row["executed"] for row in rows),
        "verdict": "PASS" if not problems else "FAIL",
        "problems": problems,
        "not_covered": NOT_COVERED,
    }
    return summary, [receipt for _, receipt in latest.values()]


def summary_markdown(summary, environ):
    lines = ["### Real storage `%s`%s @ `%s`: %s" % (summary["batch"], " (subset)" if summary["subset"] else "",
                                                     summary["source_sha"][:12], summary["verdict"]),
             "", "executed %d / registered %d tests, timezone `%s`" % (summary["executed_tests"],
                                                                      summary["expected_tests"],
                                                                      environ.get("TIMEZONE")),
             "", "| class | executed / registered | verdict | cleanup |", "|---|---|---|---|"]
    for row in summary["classes"]:
        lines.append("| %s | %d / %d | %s | %s |" % (simple_name(row["class"]), row["executed"], row["expected"],
                                                     row["verdict"], row.get("cleanup") or "-"))
    if summary["problems"]:
        lines += ["", "Problems:"] + ["- %s" % problem for problem in summary["problems"]]
    lines += ["", "Not covered: " + "; ".join(summary["not_covered"])]
    return "\n".join(lines)


def cmd_summarize(args, environ):
    registry = load_registry()
    require_valid_registry(registry)
    planned = json.loads(required_env(environ, "PLANNED"))
    summary, receipts = summarize(registry, args.receipts, planned, required_env(environ, "BATCH"),
                                  required_env(environ, "SOURCE_SHA"), workflow=workflow_facts(environ))
    # A failing artifact collector/step must not be hidden by an earlier PASS test receipt.
    summary["storage_matrix_result"] = environ.get("STORAGE_MATRIX_RESULT", "UNKNOWN")
    if summary["storage_matrix_result"] != "success":
        summary["verdict"] = "FAIL"
        summary["problems"].append("storage matrix 未成功，不能把部分原回执提升为整批通过")
    summary["timezone"] = environ.get("TIMEZONE")
    summary["workflow"] = workflow_facts(environ)
    out = Path(args.out)
    (out / "receipts").mkdir(parents=True, exist_ok=True)
    for receipt in receipts:
        with open(out / "receipts" / (simple_name(receipt["class"]) + ".json"), "w", encoding="utf-8") as handle:
            json.dump(receipt, handle, ensure_ascii=False, indent=2, sort_keys=True)
    with open(out / "summary.json", "w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2, sort_keys=True)
    markdown = summary_markdown(summary, environ)
    (out / "summary.md").write_text(markdown + "\n", encoding="utf-8")
    print(markdown)
    append_step_summary(environ, markdown)
    return 0 if summary["verdict"] == "PASS" else 1


# ---------------------------------------------------------------- M1 original hint10 + lead4 same-run pair (manual only)

M1_PAIR_BATCH = "architecture-day-lead-admission"
M1_PAIR_CLASSES = {
    "com.chengyinhub.business.it.PlayHintUnlockMySqlIT": 10,
    "com.chengyinhub.business.it.PlayLeadAdmissionFailureMySqlIT": 4,
}


def m1_pair_request(registry, environ):
    """Narrow pair contract; ordinary plan/run/summary are deliberately unchanged."""
    new, old = required_env(environ, "SOURCE_SHA"), required_env(environ, "OLD_SOURCE_SHA")
    if not all(SHA_RE.fullmatch(sha) for sha in (new, old)) or new == old:
        raise GateError("M1 pair requires two distinct full 40-hex source SHAs")
    if environ.get("BATCH") != M1_PAIR_BATCH or environ.get("CLASSES", "").strip():
        raise GateError("M1 pair requires the whole architecture-day-lead-admission batch; no classes subset")
    if environ.get("TIMEZONE") not in TIMEZONES:
        raise GateError("M1 pair requires an original registered timezone")
    members = registry["batches"].get(M1_PAIR_BATCH, {}).get("classes", [])
    if len(members) != 2 or set(members) != set(M1_PAIR_CLASSES):
        raise GateError("M1 pair original class set drift")
    for fqcn, count in M1_PAIR_CLASSES.items():
        entry = registry["classes"].get(fqcn, {})
        if entry.get("tests") != count or entry.get("nested", []) or entry.get("storage") != ["mysql"] or entry.get("gates") != {}:
            raise GateError("M1 pair original 10/4 storage/count/gate contract drift")
    return {"old": old, "new": new}


def cmd_m1_pair_plan(args, environ):
    registry = load_registry()
    require_valid_registry(registry)
    sources = m1_pair_request(registry, environ)
    paths = {"new": args.source, "old": args.old_source}
    planned = None
    matrix = []
    for side, sha in sources.items():
        # Reuse the actual original HEAD/static/dynamic-test/JDK precheck, not a second parser.
        side_env = dict(environ, SOURCE_SHA=sha)
        side_args = argparse.Namespace(source=paths[side], github_output=None)
        cmd_plan(side_args, side_env)
        items = plan(registry, sha, M1_PAIR_BATCH, "", environ["TIMEZONE"])
        planned = items
        matrix.extend(dict(item, side=side, source_sha=sha) for item in items)
    sides = [dict(side=side, source_sha=sha) for side, sha in sources.items()]
    write_github_output(args.github_output, {"matrix": json.dumps(matrix, separators=(",", ":")),
                        "planned": json.dumps(planned, separators=(",", ":")),
                        "sides": json.dumps(sides, separators=(",", ":"))})
    proof = {"schema": "CY-M1-PAIR-PLAN-1", "sources": sources, "matrix": matrix, "tests_per_side": 14,
             "workflow": workflow_facts(environ), "timezone": environ["TIMEZONE"],
             "harness_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
             "registry_sha256": hashlib.sha256(REGISTRY_PATH.read_bytes()).hexdigest()}
    Path(args.out).mkdir(parents=True, exist_ok=True)
    (Path(args.out) / "pair-plan.json").write_text(json.dumps(proof, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


def verify_m1_pair_receipts(registry, old_dir, new_dir, environ):
    sources = m1_pair_request(registry, environ)
    if environ.get("PAIR_STORAGE_RESULT") != "success" or environ.get("PAIR_SUMMARY_RESULT") != "success":
        raise GateError("M1 pair requires all four storage jobs and both source-specific summaries to succeed")
    run_id, attempt = required_env(environ, "GITHUB_RUN_ID"), required_env(environ, "GITHUB_RUN_ATTEMPT")
    workflow_sha = required_env(environ, "GITHUB_SHA")
    if not re.fullmatch(r"[1-9][0-9]*", run_id) or not re.fullmatch(r"[1-9][0-9]*", attempt) or not SHA_RE.fullmatch(workflow_sha):
        raise GateError("M1 pair needs exact current workflow/run/attempt identity")
    if environ.get("GITHUB_REPOSITORY") != "liboyang42-cpu/hengyin":
        raise GateError("M1 pair is limited to the public hengyin repository")
    suffix = database_suffix(run_id, attempt)
    evidence = []
    for side, directory in (("old", old_dir), ("new", new_dir)):
        paths = sorted(Path(directory).glob("*/receipt.json"))
        seen = set()
        if len(paths) != 2:
            raise GateError("M1 pair missing/duplicate/extra %s receipt files" % side)
        for path in paths:
            try:
                raw = path.read_bytes()
                receipt = json.loads(raw)
            except (OSError, ValueError, UnicodeError) as error:
                raise GateError("M1 pair unreadable %s receipt" % side) from error
            if not isinstance(receipt, dict):
                raise GateError("M1 pair invalid receipt shape")
            fqcn = receipt.get("class")
            if fqcn not in M1_PAIR_CLASSES or fqcn in seen:
                raise GateError("M1 pair wrong/duplicate original class")
            seen.add(fqcn)
            if receipt.get("schema") != RECEIPT_SCHEMA or receipt.get("source_sha") != sources[side] or receipt.get("batch") != M1_PAIR_BATCH:
                raise GateError("M1 pair cross-source/batch/receipt identity")
            workflow = receipt.get("workflow", {})
            if not isinstance(workflow, dict) or any(workflow.get(key) != value for key, value in
                    (("run_id", run_id), ("run_attempt", attempt), ("workflow_sha", workflow_sha),
                     ("repository", "liboyang42-cpu/hengyin"))):
                raise GateError("M1 pair cross-workflow/run/attempt receipt")
            profile = receipt.get("env_profile", {})
            if not isinstance(profile, dict) or any(profile.get(key) != suffix for key in
                    ("CHENGYIN_MYBATIS_IT_DB_SUFFIX", "CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX")):
                raise GateError("M1 pair original same-run suffix mismatch")
            cleanup, maven, executed, precheck = (receipt.get(key, {}) for key in ("cleanup", "maven", "executed", "precheck"))
            if not all(isinstance(value, dict) for value in (cleanup, maven, executed, precheck)):
                raise GateError("M1 pair incomplete original receipt evidence")
            if receipt.get("verdict") != "PASS" or cleanup.get("status") != "CLEAN" or cleanup.get("mysql_leftover_schemas") != [] or maven.get("exit_code") != 0:
                raise GateError("M1 pair requires original PASS/Maven0/independently CLEAN receipts")
            if not isinstance(precheck.get("candidate"), dict) or precheck["candidate"].get("sha") != sources[side]:
                raise GateError("M1 pair original precheck source mismatch")
            count = M1_PAIR_CLASSES[fqcn]
            expected = {"tests": count, "passed": count, "failures": 0, "errors": 0, "skipped": 0}
            if any(type(executed.get(key)) is not int or executed[key] != value for key, value in expected.items()):
                raise GateError("M1 pair original 10/4 zero-skip result mismatch")
            cases = executed.get("testcases", [])
            if not isinstance(cases, list) or len(cases) != count or any(not isinstance(case, dict) or
                    case.get("classname") != fqcn or case.get("status") != "passed" or not isinstance(case.get("name"), str) or not case["name"] for case in cases):
                raise GateError("M1 pair original testcase identities/status missing")
            if len({case["name"] for case in cases}) != count:
                raise GateError("M1 pair duplicate testcase identity")
            evidence.append({"side": side, "source_sha": sources[side], "class": fqcn,
                             "receipt_sha256": hashlib.sha256(raw).hexdigest()})
    return {"schema": "CY-M1-PAIR-PROVENANCE-1", "verdict": "PASS", "sources": sources,
            "run_id": run_id, "run_attempt": attempt, "workflow_sha": workflow_sha, "suffix": suffix,
            "receipts": evidence, "not_canonical_verdict": True}


def cmd_m1_pair_verify(args, environ):
    try:
        registry = load_registry()
        require_valid_registry(registry)
        proof = verify_m1_pair_receipts(registry, args.old_receipts, args.new_receipts, environ)
    except GateError as error:
        Path(args.out).write_text(json.dumps({"verdict": "FAIL", "reason": str(error)}, indent=2) + "\n", encoding="utf-8")
        raise
    Path(args.out).write_text(json.dumps(proof, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


def main(argv=None, environ=None):
    environ = dict(os.environ if environ is None else environ)
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("plan", "precheck"):
        command = sub.add_parser(name)
        command.add_argument("--source", default="candidate")
        command.add_argument("--out", default="out")
        command.add_argument("--github-output", default=environ.get("GITHUB_OUTPUT"))
    pair_plan = sub.add_parser("m1-pair-plan")
    pair_plan.add_argument("--source", default="candidate")
    pair_plan.add_argument("--old-source", default="old-candidate")
    pair_plan.add_argument("--out", default="out")
    pair_plan.add_argument("--github-output", default=environ.get("GITHUB_OUTPUT"))
    pair_verify = sub.add_parser("m1-pair-verify")
    pair_verify.add_argument("--old-receipts", default="old-receipts")
    pair_verify.add_argument("--new-receipts", default="new-receipts")
    pair_verify.add_argument("--out", default="pair-provenance.json")
    for name in ("storage-start", "storage-stop"):
        sub.add_parser(name).add_argument("--out", default="out")
    run = sub.add_parser("run")
    run.add_argument("--source", default="candidate")
    run.add_argument("--out", default="out")
    summary = sub.add_parser("summarize")
    summary.add_argument("--receipts", default="receipts")
    summary.add_argument("--out", default="summary")
    args = parser.parse_args(argv)
    handlers = {"plan": cmd_plan, "precheck": cmd_precheck, "storage-start": cmd_storage_start, "run": cmd_run,
                "storage-stop": cmd_storage_stop, "summarize": cmd_summarize,
                "m1-pair-plan": cmd_m1_pair_plan, "m1-pair-verify": cmd_m1_pair_verify}
    try:
        if args.command in ("plan", "precheck", "storage-start", "run", "m1-pair-plan"):
            require_normal_parent(environ)
        return handlers[args.command](args, environ)
    except GateError as error:
        print("❌ %s: %s" % (args.command, error), file=sys.stderr)
        if args.command in ("precheck", "storage-start", "run", "storage-stop"):
            fail_receipt(args.out, args.command, [str(error)])
        return 1


if __name__ == "__main__":
    sys.exit(main())
