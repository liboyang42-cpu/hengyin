#!/usr/bin/env python3
"""Fixed-budget observation child of the existing authenticated startup fixture.

No schema builder, app launcher, provider, payment dispatch or independent cleanup
framework. A scoped observation is not whole-system capacity/Q4/Q8 acceptance.
"""
import concurrent.futures
import datetime
import decimal
import hashlib
import importlib.util
import itertools
import json
import math
import os
import re
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "capacity_auth_fixture", ROOT / "ci/authenticated_redis_recovery_probe.py")
auth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auth)

D = auth.DOCKER
require = auth.require
SIZES = (1, 16, 64)
CONCURRENCY = (1, 3, 6)
REQUESTS_PER_STAGE = 24
MAX_BODY = 1024 * 1024
CATEGORIES = (
    "SUCCESS", "LEGAL_AUTH_REJECTION", "SYSTEM_FAILURE", "UNKNOWN",
    "TRANSPORT_TIMEOUT", "TRANSPORT_ERROR", "CONTRACT_MISMATCH", "NOT_SENT",
)
PROFILE_PROPERTIES = {
    "spring.datasource.druid.maxActive": "3",
    "spring.datasource.druid.maxWait": "5000",
    "spring.datasource.druid.socketTimeout": "5000",
    "spring.data.redis.timeout": "5s",
    "chengyin.jobs.enabled": "false",
    "spring.quartz.auto-startup": "false",
}
PASS_OBSERVATION = "OBSERVED_SCOPED_CURVE_AND_MIXED_RECOVERY"
PASS_FINAL = "PASS_SCOPED_CAPACITY_MIXED_OBSERVATION"
GAMEPLAY_CHILD = "com.chengyinhub.business.it.GameplayProcessRecoveryChild"
GAMEPLAY_CHILD_PATH = GAMEPLAY_CHILD.replace(".", "/")
GAMEPLAY_COMMAND = [
    "-A", "-f", "-s", "0", "-e", "trace=connect,sendto,sendmsg",
    "-o", "/evidence/gameplay-network.trace", "java", "-Xmx512m",
    "-Dlogging.config=/fixture/logback.xml", "-Dlogback.configurationFile=/fixture/logback.xml",
    "-Dloader.path=/recovery-testclasses", "-Dloader.main=" + GAMEPLAY_CHILD,
    "-cp", "/application.jar", "org.springframework.boot.loader.launch.PropertiesLauncher",
]
# Existing economic/entitlement stores used by these actors, never a nonempty-baseline registry.
GAMEPLAY_EMPTY_TABLES = ("oms_order", "oms_order_item", "coop_order", "registration_entitlement",
                        "sms_coupon_history", "member_capability", "merchant_subscription",
                        "ums_member_withdrawal", "member_cash_withdraw")
AUTHORITY_TABLES = ("sys_role", "sys_role_menu", "sys_menu", "sys_user_role", "user_permission")


class NotRun(RuntimeError):
    pass


class NoCashFixtureError(RuntimeError):
    def __init__(self, code, tables=()):
        super().__init__(code)
        self.reason_code, self.tables = code, list(tables)


def member_rows_digest(columns, rows):
    # Bind every original column/value and its Python SQL type, including Decimal scale and NULL.
    return digest({"columns": columns, "rows": rows,
                   "types": [[type(value).__name__ for value in row] for row in rows]})


def gameplay_actor_proof(identities, columns, rows, schema, server_uuid, seed_boot, identities_sha, authority):
    """Read-only proof of the real child's two source-seeded, zero-cash player identities."""
    if not isinstance(authority, dict) or set(authority) != set(AUTHORITY_TABLES) | {"accountCredentials"}:
        raise NoCashFixtureError("GAMEPLAY_ORIGINAL_AUTHORITY_PROOF_MISSING")
    if len(rows) != 2 or len(columns) != len(set(columns)):
        raise NoCashFixtureError("GAMEPLAY_MEMBER_COUNT_OR_COLUMNS", ("ums_member",))
    required = {"id", "phone", "nickname", "wx_open_id", "password", "app_open_id", "union_id", "apple_id",
                "role", "user_type", "status", "del_flag",
                "level_id", "balance", "point", "freeze_end_time", "deregister_status",
                "deregister_apply_time", "deregister_execute_after", "login_time"}
    if not required.issubset(columns) or any(len(row) != len(columns) for row in rows):
        raise NoCashFixtureError("GAMEPLAY_MEMBER_PROOF_FIELDS_MISSING", ("ums_member",))
    records = [dict(zip(columns, row)) for row in rows]
    actors = {}
    for index, label in enumerate(("a", "b")):
        identity = identities.get(label)
        if not isinstance(identity, dict) or set(identity) != {"memberId", "token"}:
            raise NoCashFixtureError("GAMEPLAY_ORIGINAL_ACTOR_IDENTITY_MISSING", ("ums_member",))
        member_id, token = identity["memberId"], identity["token"]
        if type(member_id) is not int or member_id <= 0 or not isinstance(token, str) or not re.fullmatch(
                r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", token):
            raise NoCashFixtureError("GAMEPLAY_ORIGINAL_ACTOR_IDENTITY_INVALID", ("ums_member",))
        matching = [(row, record) for row, record in zip(rows, records) if record["id"] == member_id]
        if len(matching) != 1:
            raise NoCashFixtureError("GAMEPLAY_MEMBER_IDENTITY_MISMATCH", ("ums_member",))
        row, record = matching[0]
        if (type(record["id"]) is not int or record["phone"] != "13800000" + ("901" if index == 0 else "902")
                or record["nickname"] != "进程恢复夹具" + str(index)
                or record["password"] != "" or any(record[key] is not None for key in ("app_open_id", "union_id", "apple_id"))
                or not isinstance(record["wx_open_id"], str)
                or not re.fullmatch(r"recovery-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", record["wx_open_id"])):
            raise NoCashFixtureError("GAMEPLAY_MEMBER_SOURCE_IDENTITY_MISMATCH", ("ums_member",))
        if not isinstance(record["balance"], decimal.Decimal) or record["balance"] != decimal.Decimal("0.00"):
            raise NoCashFixtureError("GAMEPLAY_MEMBER_BALANCE_NOT_ZERO", ("ums_member",))
        if type(record["point"]) is not int or record["point"] != 0:
            raise NoCashFixtureError("GAMEPLAY_MEMBER_POINTS_NOT_ZERO", ("ums_member",))
        if (record["role"] != "player" or any(type(record[key]) is not int or record[key] != value
                for key, value in (("user_type", 1), ("status", 1), ("del_flag", 0), ("level_id", 0)))
                or record["freeze_end_time"] is not None or record["deregister_status"] != "NORMAL"
                or record["deregister_apply_time"] is not None or record["deregister_execute_after"] is not None
                or not isinstance(record["login_time"], datetime.datetime)):
            raise NoCashFixtureError("GAMEPLAY_MEMBER_AUTH_OR_LIFECYCLE_MISMATCH", ("ums_member",))
        actors[label] = {"memberId": member_id, "tokenSha256": hashlib.sha256(token.encode()).hexdigest(),
                         "rowSha256": member_rows_digest(columns, [row])}
    if actors["a"]["memberId"] == actors["b"]["memberId"] or set(record["id"] for record in records) != {
            actors["a"]["memberId"], actors["b"]["memberId"]}:
        raise NoCashFixtureError("GAMEPLAY_MEMBER_EXTRA_OR_DUPLICATE_IDENTITY", ("ums_member",))
    return {"schema": schema, "serverUuid": server_uuid, "seedBootId": seed_boot,
            "identitiesSha256": identities_sha, "columns": list(columns),
            "rowsSha256": member_rows_digest(columns, rows), "actors": actors,
            "authority": json.loads(json.dumps(authority))}


def validate_env(env):
    auth.validate_execution_env(env)
    require(env.get("CHENGYIN_CI_CAPACITY_MIXED") == "true",
            "capacity child requires its exact opt-in")
    require(env.get("GITHUB_EVENT_NAME") == "workflow_dispatch",
            "capacity child is manual-only")
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        require(re.fullmatch(r"[1-9][0-9]*", env.get(key, "")) is not None,
                "missing execution identity: " + key)
    require(re.fullmatch(r"[0-9a-f]{40}", env.get("GITHUB_SHA", "")) is not None,
            "complete source SHA required")


def digest(value):
    raw = json.dumps(value, sort_keys=True, default=str,
                     separators=(",", ":")).encode()
    return hashlib.sha256(raw).hexdigest()


def file_digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return {"sha256": h.hexdigest(), "size": path.stat().st_size}


def source_identity():
    def git(*args):
        return auth.command(["git", "-C", str(ROOT), *args]).decode().strip()
    source, tree = git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")
    require(source == os.environ["GITHUB_SHA"], "checkout/source mismatch")
    require(re.fullmatch(r"[0-9a-f]{40}", tree) is not None,
            "complete source tree required")
    require(not git("status", "--porcelain", "--untracked-files=normal"),
            "source tree not clean")
    return {
        "sourceSha": source, "sourceTree": tree,
        "runId": os.environ["GITHUB_RUN_ID"],
        "runAttempt": os.environ["GITHUB_RUN_ATTEMPT"],
        "runnerOs": os.environ["RUNNER_OS"],
        "runnerEnvironment": os.environ["RUNNER_ENVIRONMENT"],
    }


def validate_profile(objects, properties, gameplay_binding=None):
    for key, value in PROFILE_PROPERTIES.items():
        require(properties.get(key) == value, "unapproved fixture profile: " + key)
    limits = []
    for index, obj in enumerate(objects):
        host = obj["HostConfig"]
        memory = (1024 ** 3, 1024 ** 3, 64 * 1024 ** 2)[index]
        cpu = (10 ** 9, 10 ** 9, 0)[index]
        require(host.get("Memory") == memory
                and host.get("MemorySwap") == memory
                and host.get("NanoCpus", 0) == cpu,
                "fixture resource caps changed")
        require(not host.get("Privileged") and not host.get("PortBindings"),
                "privileged/published fixture forbidden")
        limits.append({"id": obj["Id"], "memoryBytes": memory,
                       "memorySwapBytes": memory, "nanoCpus": cpu})
    cmd = objects[0]["Config"]["Cmd"]
    if gameplay_binding is None:
        # Default remains the original fixture, never infer a different entry from argv.
        require("--spring.config.location=file:/fixture/application.properties" in cmd,
                "wrong application fixture")
    else:
        validate_gameplay_binding(objects, gameplay_binding)
    require(not any(arg.startswith("--spring.datasource.druid.")
                    and not arg.startswith("--spring.datasource.druid.master.url=")
                    for arg in cmd),
            "pool overrides would change this profile")
    return limits


def validate_gameplay_binding(objects, binding):
    """Only the existing gameplay caller can supply this exact recovered-boot binding."""
    require(os.environ.get("CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY") == "true"
            and os.environ.get("CHENGYIN_CI_GAMEPLAY_CAPACITY") == "true", "gameplay profile not explicitly admitted")
    hashes = ("jarSha256", "childSourceSha256", "childClassSha256",
              "testClassTreeSha256", "configSha256", "fixturePropertiesSha256")
    require(isinstance(binding, dict) and set(binding) == set(hashes) | {
        "sourceSha", "boot", "appCid", "appPid", "appStartedAt", "networkId", "storeProcessIdentity", "actorProof",
    }, "exact gameplay profile binding required")
    require(isinstance(binding["sourceSha"], str) and binding["sourceSha"] == os.environ.get("GITHUB_SHA")
            and re.fullmatch(r"[0-9a-f]{40}", binding["sourceSha"]) is not None,
            "gameplay binding source mismatch")
    require(all(isinstance(binding[key], str)
                and re.fullmatch(r"[0-9a-f]{64}", binding[key]) for key in hashes),
            "complete gameplay artifact binding required")
    proof = binding["actorProof"]
    require(isinstance(proof, dict) and set(proof) == {
        "schema", "serverUuid", "seedBootId", "identitiesSha256", "columns", "rowsSha256", "actors", "authority",
    } and isinstance(proof["actors"], dict) and set(proof["actors"]) == {"a", "b"}, "original actor proof required")
    require(all(isinstance(proof[key], str) and re.fullmatch(r"[0-9a-f]{64}", proof[key])
                for key in ("identitiesSha256", "rowsSha256")), "original actor proof hashes required")
    boot = binding["boot"]
    require(isinstance(boot, dict) and set(boot) == {
        "bootId", "javaPid", "sourceSha", "jarSha256", "childClassSha256", "configSha256", "port",
    }, "exact recovered boot required")
    require(type(boot["javaPid"]) is int and boot["javaPid"] > 0
            and type(boot["port"]) is int and boot["port"] == 8080
            and isinstance(boot["bootId"], str)
            and re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", boot["bootId"]) is not None
            and all(boot[key] == binding[key] for key in ("sourceSha", "jarSha256", "childClassSha256", "configSha256")),
            "recovered boot provenance/port mismatch")
    app = objects[0]
    require(app["Id"] == binding["appCid"] and type(binding["appPid"]) is int
            and binding["appPid"] > 0 and app["State"]["Pid"] == binding["appPid"]
            and app["State"]["StartedAt"] == binding["appStartedAt"], "recovered app process changed")
    require(app["Config"].get("Entrypoint") == ["/trace/strace"]
            and app["Config"]["Cmd"] == GAMEPLAY_COMMAND, "wrong gameplay application fixture")
    env = app["Config"].get("Env", [])
    require("GITHUB_SHA=" + binding["sourceSha"] in env
            and "CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY=true" in env
            and not any(item.split("=", 1)[0].startswith(("SPRING_", "LOADER_"))
                        or item.split("=", 1)[0] in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
                        for item in env), "gameplay environment overrides forbidden")
    stores = binding["storeProcessIdentity"]
    require(isinstance(stores, dict) and set(stores) == {"mysql", "redis"}, "original store binding required")
    for name, obj in zip(("app", "mysql", "redis"), objects):
        networks = list(obj["NetworkSettings"]["Networks"].values())
        require(len(networks) == 1 and networks[0]["NetworkID"] == binding["networkId"], "gameplay network changed")
        if name != "app":
            require(stores[name] == {
                "id": obj["Id"], "Pid": obj["State"]["Pid"], "StartedAt": obj["State"]["StartedAt"],
                "networkId": binding["networkId"],
            }, "original gameplay store process changed")


def admit(limits, available_memory, free_disk, cpus, jar_size):
    required_memory = sum(item["memoryBytes"] for item in limits) + 512 * 1024 ** 2
    required_disk = 2 * jar_size + 256 * 1024 ** 2
    if available_memory < required_memory or free_disk < required_disk or cpus < 2:
        raise NotRun("insufficient existing hosted resource headroom")
    return {
        "availableMemoryBytes": available_memory,
        "requiredMemoryBytes": required_memory,
        "freeDiskBytes": free_disk, "requiredDiskBytes": required_disk,
        "logicalCpus": cpus,
        "source": "/proc/meminfo MemAvailable; statvfs; os.cpu_count",
        "hostExclusivity": "UNKNOWN_NOT_ESTABLISHED",
    }


def classify(kind, http, body, expected=None):
    if not isinstance(body, dict) or type(body.get("code")) is not int:
        return "UNKNOWN"
    code = body["code"]
    if http in (200, 401) and code == 401:
        return "LEGAL_AUTH_REJECTION"
    if http == 200 and code == 200:
        if kind == "DICT":
            data = body.get("data")
            if not isinstance(data, list):
                return "CONTRACT_MISMATCH"
            if any(not isinstance(row, dict) for row in data):
                return "CONTRACT_MISMATCH"
            actual = sorted((row.get("dictLabel"), row.get("dictValue")) for row in data)
            return "SUCCESS" if actual == sorted(expected or []) else "CONTRACT_MISMATCH"
        if kind == "ACTOR":
            user = body.get("user")
            return ("SUCCESS" if isinstance(user, dict) and type(user.get("userId")) is int
                    and user["userId"] == 1 else "CONTRACT_MISMATCH")
        # Command success is provisional until its separate durable-state assertion.
        return "SUCCESS"
    if code == 500 or http >= 500:
        return "SYSTEM_FAILURE"
    return "UNKNOWN"


def distribution(records, duration):
    require(duration > 0, "positive measured duration required")
    counts = {key: 0 for key in CATEGORIES}
    for record in records:
        require(record["category"] in counts, "unknown observation category")
        counts[record["category"]] += 1
    values = sorted(record["elapsedSeconds"] for record in records
                    if record["category"] != "NOT_SENT")
    def percentile(p):
        return values[max(0, math.ceil(len(values) * p) - 1)] if values else None
    return {
        "requestsRecorded": len(records), "counts": counts,
        "durationSeconds": duration,
        "completedResponsesPerSecond": sum(
            counts[key] for key in ("SUCCESS", "LEGAL_AUTH_REJECTION",
                                   "SYSTEM_FAILURE", "CONTRACT_MISMATCH")) / duration,
        "successfulRequestsPerSecond": counts["SUCCESS"] / duration,
        "p50Seconds": percentile(.50), "p95Seconds": percentile(.95),
        "p99Seconds": percentile(.99),
        "latencyPopulation": "all sent attempts including failures/timeouts",
        "slo": "NOT_DEFINED_BY_THIS_PROBE",
    }


def controlled_combination_summary(report, receipt, source, run_id, attempt, jar_sha):
    """Project one fixed new HTTP suite AFTER the existing exact XML/cleanup verifier.

    No launcher, resource owner, mutable scope registry or whole-capacity verdict. The caller
    must first validate_final(receipt, source); these checks also prevent accidental foreign
    receipt aggregation. JSON flags alone are never execution/JAR/physical-isolation proof.
    """
    suite = 'CrossDomainCombinedLoadMySqlIT'
    require(re.fullmatch(r'[0-9a-f]{40}', source or '') is not None
            and re.fullmatch(r'[0-9a-f]{64}', jar_sha or '') is not None,
            'complete combination source/JAR identity required')
    require(isinstance(run_id, str) and re.fullmatch(r'[1-9][0-9]*', run_id)
            and isinstance(attempt, str) and re.fullmatch(r'[1-9][0-9]*', attempt),
            'actual positive run/attempt required')
    require(type(report.get('javaPid')) is int and report['javaPid'] > 0, 'actual test JVM identity required')
    require(report.get('format') == 'CONTROLLED_COMBINATION_V1'
            and report.get('result') == 'OBSERVED_REPRESENTATIVE_COMBINATION'
            and report.get('sourceSha') == source and report.get('runId') == run_id
            and report.get('runAttempt') == attempt and report.get('jarSha256') == jar_sha,
            'combination execution identity/result mismatch')
    require(receipt.get('classes') == [suite] and receipt.get('sourceSha') == source and receipt.get('profile') == 'github-hosted'
            and receipt.get('state') == 'PASS' and receipt.get('javaExit') == 0
            and type(receipt.get('javaExit')) is int and receipt.get('tests') == 1
            and type(receipt.get('tests')) is int and receipt.get('xmlVerified') is True
            and receipt.get('fixtureReportVerified') is True
            and receipt.get('database') == report.get('schema')
            and receipt.get('cleanup') == {'mysqlAbsent': True, 'redisStoppedAndPortFree': True}
            and all(value is True for value in receipt['cleanup'].values()),
            'combination needs its own strict same-source XML/fixture/cleanup receipt')
    require(report.get('wholeSystemCapacityAccepted') is False and report.get('q4q8Complete') is False
            and report.get('economicAssertionsVerified') is True and report.get('innerOwnedCleanupVerified') is True,
            'combination cannot self-promote capacity or omit economic assertions')
    require(isinstance(report.get('schema'), str)
            and re.fullmatch(r'cy_e2e_w1_http_[0-9_]+', report['schema'])
            and isinstance(report.get('economicSchema'), str)
            and re.fullmatch(r'cy_wp14_[0-9a-f]{32}', report['economicSchema'])
            and report['schema'] != report['economicSchema']
            and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', report.get('serverUuid', '')),
            'original separate schema/native-server binding missing')
    waves, extra = report.get('coreWaves'), report.get('additionalReadWindows')
    require(isinstance(waves, list) and len(waves) == 3
            and isinstance(extra, list) and len(extra) == 4, 'fixed three curves/four fault windows required')
    all_records, stages, write_ids = [], [], []

    def curve(raw, concurrency, writes):
        require(isinstance(raw, dict) and raw.get('configuredConcurrency') == concurrency
                and type(raw.get('configuredConcurrency')) is int
                and raw.get('requestsRecorded') == 24 and type(raw.get('requestsRecorded')) is int and isinstance(raw.get('records'), list)
                and len(raw['records']) == 24, 'fixed measured curve denominator differs')
        records = raw['records']
        kinds = ('command',) if writes else ('replay', 'receipt', 'foreign')
        for item in records:
            require(isinstance(item, dict) and set(item) == {'kind', 'intentSha256', 'category', 'elapsedSeconds'}
                    and item['kind'] in kinds and re.fullmatch(r'[0-9a-f]{64}', item['intentSha256'])
                    and item['category'] == ('LEGAL_AUTH_REJECTION' if item['kind'] == 'foreign' else 'SUCCESS')
                    and type(item['elapsedSeconds']) in (int, float)
                    and math.isfinite(item['elapsedSeconds']) and item['elapsedSeconds'] >= 0,
                    'unknown/foreign observation cannot become a successful curve')
        require(len({item['intentSha256'] for item in records}) == 24, 'duplicate or omitted original intent')
        if not writes:
            require(all(sum(item['kind'] == kind for item in records) == 8 for kind in kinds),
                    'read/replay/foreign denominator differs')
        duration = raw.get('durationSeconds')
        require(type(duration) in (int, float) and math.isfinite(duration) and 0 < duration <= 45,
                'fixed observation bound exceeded; no retry authorization')
        stages.append(distribution(records, duration)); all_records.extend(records)
        return {item['intentSha256'] for item in records}

    for index, wave in enumerate(waves):
        require(isinstance(wave, dict) and wave.get('concurrency') == CONCURRENCY[index]
                and type(wave.get('concurrency')) is int and wave.get('newIntents') == 24 and type(wave.get('newIntents')) is int
                and wave.get('factsVerified') is True, 'missing original CAS/receipt assertions')
        ids = curve(wave.get('write'), CONCURRENCY[index], True)
        require(curve(wave.get('readReplayForeign'), CONCURRENCY[index], False) == ids,
                'readback replaced original intent identities')
        write_ids.append(ids)
    require(len(set.union(*write_ids)) == 72, 'new commands are not 72 unique original intents')
    for row, phase in zip(extra, ('MAP_HTTP_HELD', 'DISCONNECT', 'BAD_RESPONSE', 'RECOVERED')):
        require(isinstance(row, dict) and row.get('phase') == phase and row.get('factsVerified') is True,
                'missing fixed provider window/original zero-change assertion')
        require(curve(row.get('curve'), 6, False) == write_ids[0], 'fault window replaced original commands')
    require(report.get('coreHttpExpected') == len(all_records) == 240, 'actual sent-response denominator differs')
    return {'sourceSha': source, 'runId': run_id, 'runAttempt': attempt, 'jarSha256': jar_sha,
            'javaPid': report.get('javaPid'),
            'verdict': 'OBSERVED_REPRESENTATIVE_COMBINATION_ONLY', 'curves': stages,
            'scope': 'PARTIAL_CONTROLLED_COMBINATION_SCOPE', 'completeHttpSuiteAccepted': False,
            'classes': {suite: 1}, 'tests': 1,
            'requestPopulation': '240 explicit logical HTTP invocations, one validated response each; wire retries not independently counted',
            'throughputPopulation': 'sum of measured closed-loop curve durations, not setup/wall time or production RPS',
            'total': distribution(all_records, sum(row['durationSeconds'] for row in stages)),
            'wholeSystemCapacityAccepted': False, 'q4q8Complete': False,
            'physicalProfile': 'UNKNOWN_REQUIRES_ORIGINAL_OWNER_CAPS_AND_NETWORK_READBACK',
            'notCovered': ['productionProviders', 'providerPerformanceSlo', 'allGameplayFamilies',
                           'fullPackagedAppBudgetPlacement', 'routeCacheEvictionAndLongProvider',
                           'runtimeInstanceTakeover'],
            'fixtureScope': 'new fixed HTTP + original strict economic fixture; not old mixed/HTTP acceptance'}


def controlled_combination_projection(report, receipt, source, run_id, attempt, jar_sha):
    """Finite public fields, never a raw XML/JSON/stdout dump."""
    controlled_combination_summary(report, receipt, source, run_id, attempt, jar_sha)
    safe = {key: report[key] for key in ('format', 'result', 'sourceSha', 'runId', 'runAttempt', 'jarSha256',
            'schema', 'economicSchema', 'serverUuid', 'coreHttpExpected', 'economicAssertionsVerified', 'innerOwnedCleanupVerified',
            'wholeSystemCapacityAccepted', 'q4q8Complete')}
    require(isinstance(report.get('sourceTree'), str) and re.fullmatch(r'[0-9a-f]{40}', report['sourceTree']),
            'actual source tree missing')
    expected_classes = {'com.chengyinhub.RuoYiApplication', 'com.chengyinhub.web.controller.api.ApiClubLeadController'} | {
        'com.chengyinhub.business.it.' + name for name in ('CrossDomainCombinedLoadMySqlIT', 'CombinedCoreHttpWorkload',
            'CombinedControlledProviders', 'GameplayProcessRecoveryChild', 'CrossDomainStorageFixture', 'CrossDomainTransportStage')}
    expected_classes |= {'com.chengyinhub.quartz.util.CrossDomainSchedulerStage',
                         'com.chengyinhub.business.service.impl.CrossDomainSubscriptionProvider'}
    expected_classes |= {'com.chengyinhub.business.it.' + name for name in ('CombinedControlledProviders$1',
                          'CombinedControlledProviders$Mode', 'CombinedCoreHttpWorkload$HttpStatusFailure',
                          'CrossDomainCombinedLoadMySqlIT$Initializer')}
    classes = report.get('actualClassSha256')
    require(isinstance(classes, dict) and set(classes) == expected_classes
            and all(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) for value in classes.values()),
            'actual original/new class bytes missing')
    safe.update(sourceTree=report['sourceTree'], actualClassSha256=classes)
    require(type(report.get('javaPid')) is int and report['javaPid'] > 0, 'actual test JVM identity missing')
    safe['javaPid'] = report['javaPid']
    runtime = report.get('runtimeObservations')
    require(isinstance(runtime, dict) and type(runtime.get('javaMaxHeapBytes')) is int and runtime['javaMaxHeapBytes'] > 0
            and isinstance(runtime.get('cgroupMemory'), str)
            and re.fullmatch(r'(?:UNKNOWN|max|[1-9][0-9]*)', runtime['cgroupMemory'])
            and isinstance(runtime.get('cgroupCpu'), str)
            and re.fullmatch(r'(?:UNKNOWN|(?:max|[1-9][0-9]*) [1-9][0-9]*)', runtime['cgroupCpu']),
            'runtime observations are measurements/UNKNOWN, never inferred resource admission')
    safe['runtimeObservations'] = {key: runtime[key] for key in ('javaMaxHeapBytes', 'cgroupMemory', 'cgroupCpu')}
    require(report.get('poolCaps') == {'application': 3, 'economicFixture': 3, 'domainPoolMaximumSum': 6},
            'separate domain pool budgets changed')
    safe['poolCaps'] = report['poolCaps']
    retained = report.get('retainedUnknownIds')
    require(isinstance(retained, list) and len(retained) == 6
            and all(type(value) is int and value > 0 for value in retained) and len(set(retained)) == 6,
            'six original UNKNOWN identities required')
    safe['retainedUnknownIds'] = retained
    fact = report.get('economicFactEvidence')
    require(isinstance(fact, dict) and set(fact) == {'order', 'orderId', 'totalFen', 'currency', 'verifiedFactId',
            'verifiedFactHash', 'stableEffectSha256', 'proofSource'} and fact['order'] == 'TPLTM_T19_ORIGINAL'
            and type(fact['orderId']) is int and fact['orderId'] == 8
            and type(fact['totalFen']) is int and fact['totalFen'] == 1990 and fact['currency'] == 'CNY'
            and type(fact['verifiedFactId']) is int and fact['verifiedFactId'] > 0
            and all(isinstance(fact[key], str) and re.fullmatch(r'[0-9a-f]{64}', fact[key])
                    for key in ('verifiedFactHash', 'stableEffectSha256'))
            and fact['proofSource'] == 'CONTROLLED_ORIGINAL_FIXTURE_NOT_PROVIDER_SIGNATURE',
            'original exact fixture fact/effect identity missing')
    safe['economicFactEvidence'] = fact
    def curve(raw):
        return {key: raw[key] for key in ('records', 'durationSeconds', 'configuredConcurrency', 'requestsRecorded')}
    safe['coreWaves'] = [{'concurrency': wave['concurrency'], 'newIntents': 24, 'factsVerified': True,
                          'write': curve(wave['write']), 'readReplayForeign': curve(wave['readReplayForeign'])}
                         for wave in report['coreWaves']]
    safe['additionalReadWindows'] = [{'phase': row['phase'], 'factsVerified': True, 'curve': curve(row['curve'])}
                                     for row in report['additionalReadWindows']]
    samples = report.get('samples')
    require(isinstance(samples, list) and 3 <= len(samples) <= 16, 'bounded actual measurements required')
    sample_fields = ('monotonicNanos', 'heapUsedBytes', 'heapMaxBytes', 'applicationActiveConnections',
                     'applicationIdleConnections', 'fixtureActiveConnections', 'fixtureIdleConnections',
                     'httpActive', 'httpQueued', 'cleanupActive', 'cleanupQueued', 'physicalConnectionsInTwoSchemas')
    safe['samples'] = []
    for sample in samples:
        require(isinstance(sample, dict) and sample.get('phase') in ('BEFORE', 'HELD', 'RECOVERED')
                and all(type(sample.get(key)) is int and sample[key] >= 0 for key in sample_fields),
                'invalid finite JVM/pool/executor measurement')
        cpu = sample.get('processCpuNanos')
        require(cpu == 'UNKNOWN' or type(cpu) is int and cpu >= 0, 'invalid CPU sample')
        safe['samples'].append({**{key: sample[key] for key in sample_fields},
                                'phase': sample['phase'], 'processCpuNanos': cpu})
    require({row['phase'] for row in safe['samples']} == {'BEFORE', 'HELD', 'RECOVERED'},
            'before/held/recovered measurement missing')
    # The test's actual provider counters are finite, safe numeric observations, not body text.
    observations = report.get('observations')
    require(isinstance(observations, list), 'original provider observations required')
    held = [row for row in observations if isinstance(row, dict) and row.get('phase') == 'HELD_COMBINATION']
    require(len(held) == 1 and all(type(held[0].get(key)) is type(value) and held[0].get(key) == value for key, value in {
        'coreHttpRequests': 144, 'callbackResult': 'SUCCESS', 'wrongAmountZeroEffects': True,
        'singleGrant': True, 'schedulerEffects': 1, 'schedulerAttempts': 3,
        'aiPhysicalHandlersHeld': 4, 'mapPhysicalSlotsHeld': 4, 'notificationHandlerHeld': True}.items()),
        'missing fixed held-window/economic/scheduler observation')
    safe['observations'] = [{key: held[0][key] for key in ('phase', 'coreHttpRequests', 'callbackResult',
        'wrongAmountZeroEffects', 'singleGrant', 'schedulerEffects', 'schedulerAttempts', 'aiPhysicalHandlersHeld',
        'mapPhysicalSlotsHeld', 'notificationHandlerHeld')}]
    for index, (phase, state) in enumerate((('DISCONNECT', 'UNKNOWN'), ('BAD_RESPONSE', 'UNKNOWN'), ('RECOVERED', 'RECEIVED'))):
        rows = [row for row in observations if isinstance(row, dict) and row.get('phase') == phase]
        require(len(rows) == 1 and rows[0].get('providerState') == state, 'missing exact provider result window')
        row = rows[0]; counters = row.get('providerCounters')
        require(isinstance(counters, dict) and set(counters) == {'aiPosts', 'mapGets', 'noticePosts', 'activeHandlers',
                'peakHandlers', 'workerLimit', 'queueLimit'} and all(type(value) is int and value >= 0 for value in counters.values())
                and counters['workerLimit'] == counters['queueLimit'] == 4 and counters['peakHandlers'] <= 4
                and counters['activeHandlers'] == 0 and counters['aiPosts'] == counters['noticePosts'] == index + 1
                and 4 + index + 1 <= counters['mapGets'] <= 4 + 4 * (index + 1),
                'provider budget/counter observation invalid')
        require(all(type(row.get(key)) is int and row[key] > 0 for key in ('intentId', 'noticeId')),
                'original fixture identities missing')
        safe['observations'].append({key: row[key] for key in ('phase', 'providerState', 'intentId', 'noticeId', 'providerCounters')})
    return safe


def assert_fault_overlap(blocked, redis_paused):
    require(type(blocked) is int and blocked == 3 and redis_paused is True,
            "three physical application SQL waits plus owned Redis pause required")


def run_curve(items, request, concurrency, stop, seconds=45):
    """Bounded closed-loop client; no pre-submission of an unbounded queue."""
    require(concurrency in CONCURRENCY and len(items) == REQUESTS_PER_STAGE,
            "fixed curve scope required")
    started, deadline = time.monotonic(), time.monotonic() + seconds
    remaining, pending, records = iter(items), set(), []
    peak = 0
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=concurrency)
    try:
        exhausted = False
        while pending or not exhausted:
            while not exhausted and len(pending) < concurrency:
                try:
                    item = next(remaining)
                except StopIteration:
                    exhausted = True
                    break
                pending.add(pool.submit(request, *item))
                peak = max(peak, len(pending))
            if not pending:
                break
            if time.monotonic() >= deadline:
                # Cancel is not proof that a running HTTP request did not execute.
                stop()
                raise RuntimeError("bounded stage deadline; remaining attempts unknown")
            done, pending = concurrent.futures.wait(
                pending, timeout=min(1, max(0, deadline - time.monotonic())),
                return_when=concurrent.futures.FIRST_COMPLETED)
            records.extend(future.result() for future in done)
        return {
            **distribution(records, time.monotonic() - started),
            "records": records, "configuredConcurrency": concurrency,
            "maxOutstandingClientFutures": peak,
            "serverConcurrentRequests": "UNKNOWN_NOT_MEASURED",
            "additionalClientWaitingQueue": 0,
        }
    finally:
        for future in pending:
            future.cancel()
        pool.shutdown(wait=True, cancel_futures=True)


class Probe:
    def __init__(self, name, schema, out, private, port, report, gameplay_binding=None):
        self.name, self.schema, self.out, self.private = name, schema, out, private
        self.report, self.port = report, port
        self.token = None
        self.expected = {}
        self.sequence = itertools.count()
        self.holder = None
        self.pause_intent = False
        self.stopped = False
        self.nonce = "qa_capacity_" + name[8:] + "_"
        self.jar = ROOT / "chengyinhub-admin/target/chengyinhub-admin.jar"
        self.gameplay_binding = (json.loads(json.dumps(gameplay_binding))
                                 if gameplay_binding is not None else None)
        require(not private.is_symlink() and private.stat().st_mode & 0o777 == 0o600,
                "private fixture file changed")
        self.saved = json.loads(private.read_text())
        require(self.saved["schema"] == schema, "private schema mismatch")
        auth.SECRET_VALUES.extend(self.saved["redactions"])
        auth.SECRET_VALUES.extend([
            os.environ["CHENGYIN_AUTH_FIXTURE_DB_PASSWORD"],
            self.saved["password"], "synthetic-local-only"])
        self.objects, self.network = self.inspect()
        self.ids = [obj["Id"] for obj in self.objects]
        self.app, self.mysql, self.redis = self.ids
        properties = {}
        properties_file = ROOT / "ci/upgrade-startup/application.properties"
        require(not properties_file.is_symlink() and properties_file.resolve() == properties_file.absolute(),
                "fixture properties path changed")
        self.properties_sha256 = file_digest(properties_file)["sha256"]
        for line in properties_file.read_text().splitlines():
            if line.strip() and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                require(key not in properties, "duplicate fixture property")
                properties[key] = value
        limits = validate_profile(self.objects, properties, self.gameplay_binding)
        memory = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
        available = int(memory["MemAvailable"].split()[0]) * 1024
        disk = os.statvfs(out)
        report["admission"] = admit(
            limits, available, disk.f_bavail * disk.f_frsize,
            os.cpu_count() or 0, self.jar.stat().st_size)
        self.identity = source_identity()
        with zipfile.ZipFile(self.jar) as archive:
            props = dict(line.split("=", 1) for line in archive.read(
                "BOOT-INF/classes/git.properties").decode().splitlines()
                         if "=" in line and not line.startswith("#"))
        require(props.get("git.commit.id.full", props.get("git.commit.id"))
                == self.identity["sourceSha"], "embedded JAR source mismatch")
        report.update(
            identity=self.identity, taskName=name, schema=schema,
            resourceProfile={"limits": limits, "properties": PROFILE_PROPERTIES},
            ownedIds=self.ids, networkId=self.network["Id"],
            actualImages=[{"id": obj["Image"], "reference": obj["Config"]["Image"]}
                          for obj in self.objects],
            jarBefore=self.jar_identity(),
            jarProof="host/mounted byte digests; no independently downloaded JAR",
            legacyAuthExercise="NOT_RUN_BY_THIS_CHILD",
        )
        self.db = auth.database(port, schema)
        self.initial = self.stamp()
        report["initial"] = self.initial
        report["resourceProfile"]["applicationEntry"] = self.initial["applicationProfile"]

    def save(self):
        auth.write_report(self.out, "capacity-mixed.json", self.report)

    def inspect(self):
        objects = json.loads(auth.command(D + [
            "inspect", self.name + "-app", self.name + "-mysql", self.name + "-redis"]))
        network = json.loads(auth.command(D + ["network", "inspect", self.name]))[0]
        endpoint = auth.command(D + [
            "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"]).decode().strip()
        auth.validate_targets(self.name, self.schema, endpoint, objects, network)
        return objects, network

    def jar_identity(self):
        host = file_digest(self.jar)
        mounted = auth.command(D + [
            "exec", self.app, "sha256sum", "/application.jar"], timeout=20).decode().strip()
        require(mounted == host["sha256"] + "  /application.jar",
                "mounted JAR differs from source-bound host JAR")
        return host

    def rows(self, sql, args=(), db=None):
        with (db or self.db).cursor() as cur:
            cur.execute(sql, args)
            rows = cur.fetchmany(4097)
            require(len(rows) <= 4096, "bounded fixture query exceeded")
            return rows

    def stamp(self):
        objects, network = self.inspect()
        require([obj["Id"] for obj in objects] == self.ids
                and network["Id"] == self.network["Id"], "container/network replaced")
        java_pid = auth.command(D + ["exec", self.app, "sh", "-c", """
for entry in /proc/[0-9]*/cmdline; do
 case "$(readlink "${entry%/cmdline}/exe" 2>/dev/null)" in */java) ;; *) continue ;; esac
 if tr "\\000" "\\n" < "$entry" | grep -qxF /application.jar; then
   pid=${entry#/proc/}; printf '%s\\n' "${pid%/cmdline}"
 fi
done
"""]).decode().strip()
        require(re.fullmatch(r"[1-9][0-9]*", java_pid) is not None,
                "one actual application Java PID required")
        application_profile = self.application_profile(objects, int(java_pid))
        uuid, database = self.rows("SELECT @@server_uuid,DATABASE()")[0]
        require((uuid, database) == (self.saved["serverUuid"], self.schema),
                "database identity changed")
        return {
            "identity": self.identity,
            "ids": self.ids, "networkId": network["Id"],
            "states": [{key: obj["State"][key]
                        for key in ("Pid", "Running", "Paused", "OOMKilled")}
                       for obj in objects],
            "applicationJavaPid": int(java_pid), "databaseUuid": uuid,
            "applicationProfile": application_profile,
        }

    def application_profile(self, objects, java_pid):
        """Recheck immutable inputs and the actual process at each existing curve/fault stamp."""
        app = objects[0]
        cmd = app["Config"]["Cmd"]
        require("java" in cmd, "fixed Java process missing")
        expected_argv = cmd[cmd.index("java"):]
        if self.gameplay_binding is None:
            mysql_ip = objects[1]["NetworkSettings"]["Networks"][self.name]["IPAddress"]
            redis_ip = objects[2]["NetworkSettings"]["Networks"][self.name]["IPAddress"]
            original_argv = ["java", "-Xmx512m", "-Dlogging.config=/fixture/logback.xml",
                "-Dlogback.configurationFile=/fixture/logback.xml", "-jar", "/application.jar",
                "--spring.config.location=file:/fixture/application.properties", "--spring.messages.basename=i18n/messages",
                "--spring.datasource.druid.master.url=jdbc:mysql://" + mysql_ip + ":3306/" + self.schema
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "--spring.data.redis.host=" + redis_ip]
            require(app["Config"].get("Entrypoint") == ["/trace/strace"]
                    and cmd == ["-f", "-s", "0", "-e", "trace=connect,sendto,sendmsg",
                                "-o", "/evidence/network.trace"] + original_argv,
                    "wrong original application entry")
        argv = auth.command(D + ["exec", self.app, "cat", "/proc/" + str(java_pid) + "/cmdline"])
        require(argv == b"\0".join(item.encode() for item in expected_argv) + b"\0",
                "actual application command differs from inspected fixture")
        properties = ROOT / "ci/upgrade-startup/application.properties"
        require(not properties.is_symlink() and properties.resolve() == properties.absolute(), "fixture properties path changed")
        require(file_digest(properties)["sha256"] == self.properties_sha256, "fixture properties changed")
        files = [(properties, "/fixture/application.properties")]
        result = {"entry": "ORIGINAL_AUTHENTICATED_FIXTURE",
                  "propertiesSha256": file_digest(properties)["sha256"],
                  "javaArgvSha256": hashlib.sha256(argv).hexdigest()}
        if self.gameplay_binding is not None:
            binding = self.gameplay_binding
            validate_gameplay_binding(objects, binding)
            require(java_pid == binding["boot"]["javaPid"]
                    and self.identity["sourceSha"] == binding["sourceSha"]
                    and self.properties_sha256 == binding["fixturePropertiesSha256"]
                    and not self.jar.is_symlink() and self.jar.resolve() == self.jar.absolute()
                    and file_digest(self.jar)["sha256"] == binding["jarSha256"], "gameplay source/JAR/PID changed")
            private = self.private.parent / "gameplay"
            config_file, ready_file, identities_file = private / "config.json", private / "ready.json", private / "identities.json"
            for path in (private, config_file, ready_file, identities_file):
                require(not path.is_symlink() and path.resolve() == path.absolute(), "gameplay private path changed")
            require(config_file.stat().st_mode & 0o777 == 0o600
                    and ready_file.stat().st_mode & 0o777 == 0o600
                    and identities_file.stat().st_mode & 0o777 == 0o600, "gameplay private file mode changed")
            require(file_digest(config_file)["sha256"] == binding["configSha256"]
                    and json.loads(ready_file.read_text()) == binding["boot"], "original config/recovered boot changed")
            require(file_digest(identities_file)["sha256"] == binding["actorProof"]["identitiesSha256"],
                    "original actor identity file changed")
            config = json.loads(config_file.read_text())
            require(set(config) == {"sourceSha", "jarSha256", "childClassSha256", "schema", "serverUuid",
                                    "jdbcUrl", "redisHost", "tokenSecret", "capacityItems"}, "unknown gameplay config")
            mysql_ip = objects[1]["NetworkSettings"]["Networks"][self.name]["IPAddress"]
            redis_ip = objects[2]["NetworkSettings"]["Networks"][self.name]["IPAddress"]
            require(all(config[key] == binding[key] for key in ("sourceSha", "jarSha256", "childClassSha256"))
                    and config["schema"] == self.schema and config["serverUuid"] == self.saved["serverUuid"]
                    and config["jdbcUrl"] == "jdbc:mysql://" + mysql_ip + ":3306/" + self.schema
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    and config["redisHost"] == redis_ip and type(config["capacityItems"]) is int
                    and config["capacityItems"] == 72, "gameplay config scope/store mismatch")
            child_root = self.out / "gameplay-child-classes"
            tests = ROOT / "chengyinhub-admin/target/test-classes"
            source = ROOT / ("chengyinhub-admin/src/test/java/" + GAMEPLAY_CHILD_PATH + ".java")
            require(not source.is_symlink() and source.resolve() == source.absolute()
                    and file_digest(source)["sha256"] == binding["childSourceSha256"], "gameplay child source changed")
            require(not child_root.is_symlink() and child_root.resolve() == child_root.absolute(), "child class root changed")
            manifest = {}
            for path in sorted(child_root.rglob("*")):
                require(not path.is_symlink(), "child class path is a symlink")
                if path.is_dir():
                    continue
                relative = path.relative_to(child_root).as_posix()
                require(path.is_file() and (relative == GAMEPLAY_CHILD_PATH + ".class"
                        or relative.startswith(GAMEPLAY_CHILD_PATH + "$") and relative.endswith(".class")),
                        "unexpected mounted child class")
                original = tests / relative
                require(original.is_file() and not original.is_symlink()
                        and original.resolve() == original.absolute(), "original compiled child missing")
                sha = file_digest(path)["sha256"]
                require(sha == file_digest(original)["sha256"], "mounted child differs from original build")
                manifest[relative] = sha
                files.append((path, "/recovery-testclasses/" + relative))
            require(manifest.get(GAMEPLAY_CHILD_PATH + ".class") == binding["childClassSha256"]
                    and digest(manifest) == binding["testClassTreeSha256"], "child class manifest changed")
            mounts = {"/application.jar": (self.jar, False), "/fixture": (properties.parent, False),
                      "/recovery-testclasses": (child_root, False), "/recovery-private": (private, True)}
            for target, (source_path, writable) in mounts.items():
                actual = [mount for mount in app["Mounts"] if mount["Destination"] == target]
                require(len(actual) == 1 and actual[0]["Type"] == "bind"
                        and actual[0]["Source"] == str(source_path) and actual[0]["RW"] is writable,
                        "fixed gameplay mount changed")
            files.extend(((config_file, "/recovery-private/config.json"),
                          (ready_file, "/recovery-private/ready.json"),
                          (identities_file, "/recovery-private/identities.json")))
            result.update(entry="GAMEPLAY_PROCESS_RECOVERY", bindingSha256=digest(binding),
                          childSourceSha256=binding["childSourceSha256"],
                          testClassTreeSha256=binding["testClassTreeSha256"],
                          configSha256=binding["configSha256"], bootId=binding["boot"]["bootId"])
        mounted = auth.command(D + ["exec", self.app, "sha256sum"] + [target for _, target in files]).decode().splitlines()
        require(mounted == [file_digest(path)["sha256"] + "  " + target for path, target in files],
                "mounted fixture/config/class bytes changed")
        return result

    def remember(self, value):
        auth.SECRET_VALUES.append(value)
        self.saved["redactions"] = auth.SECRET_VALUES
        self.private.write_text(json.dumps(self.saved))

    def http(self, kind, argument=None):
        paths = {
            "DICT": "/api/common/dict", "ACTOR": "/getInfo",
            "CAPTCHA": "/captchaImage", "LOGIN": "/login",
            "CLAIM": "/business/refund/manual/claim",
            "RELEASE": "/business/refund/manual/release",
        }
        require(kind in paths, "endpoint not in fixed workload")
        config = 'url = "http://127.0.0.1:8080' + paths[kind] + '"\n'
        if kind not in ("DICT", "CAPTCHA", "LOGIN"):
            require(self.token is not None, "original authenticated actor required")
            config += 'header = "Authorization: Bearer ' + self.token + '"\n'
        if kind == "DICT":
            require(isinstance(argument, str) and argument.startswith(self.nonce)
                    and re.fullmatch(r"[a-z0-9_]+", argument), "foreign dictionary key")
            config += 'data = "dictType=' + argument + '"\n'
        elif kind in ("LOGIN", "CLAIM", "RELEASE"):
            config += 'header = "Content-Type: application/json"\n'
            config += "data = " + json.dumps(json.dumps(argument)) + "\n"
        start = time.monotonic()
        record = {"request": next(self.sequence), "kind": kind,
                  "startedMonotonic": start, "http": None, "businessCode": None}
        body = None
        try:
            result = subprocess.run(
                D + ["exec", "-i", self.app, "curl", "--noproxy", "*",
                     "--silent", "--show-error", "--max-time", "35",
                     "--max-filesize", str(MAX_BODY), "--write-out", "\n%{http_code}",
                     "--config", "-"],
                input=config.encode(), stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, timeout=40)
            if result.returncode:
                record["category"] = ("TRANSPORT_TIMEOUT" if result.returncode == 28
                                      else "TRANSPORT_ERROR")
            elif len(result.stdout) > MAX_BODY + 16:
                record["category"] = "UNKNOWN"
            else:
                raw, status = result.stdout.rsplit(b"\n", 1)
                require(re.fullmatch(rb"[0-9]{3}", status) is not None,
                        "HTTP status not observed")
                body = json.loads(raw)
                record["http"] = int(status)
                record["businessCode"] = body.get("code") if isinstance(body, dict) else None
                record["category"] = classify(
                    kind, record["http"], body, self.expected.get(argument)
                    if kind == "DICT" else None)
        except subprocess.TimeoutExpired:
            record["category"] = "TRANSPORT_TIMEOUT"
        except (ValueError, KeyError, RuntimeError, OSError):
            record["category"] = "UNKNOWN"
        record["finishedMonotonic"] = time.monotonic()
        record["elapsedSeconds"] = record["finishedMonotonic"] - start
        # No response/raw error/headers/payload are retained in the observation.
        return record, body

    def request(self, kind, argument=None):
        return self.http(kind, argument)[0]

    def login(self):
        challenge, body = self.http("CAPTCHA")
        require(challenge["category"] == "SUCCESS"
                and body.get("captchaEnabled") is True and body.get("img"),
                "original real CAPTCHA unavailable")
        key = auth.captcha_key(body.get("uuid"))
        answer = json.loads(auth.command(D + [
            "exec", self.redis, "redis-cli", "--raw", "GET", key]))
        require(isinstance(answer, str) and answer, "fixture CAPTCHA oracle unavailable")
        logged, body = self.http("LOGIN", {
            "username": "admin", "password": self.saved["password"],
            "uuid": body["uuid"], "code": answer})
        require(logged["category"] == "SUCCESS" and isinstance(body.get("token"), str)
                and re.fullmatch(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+",
                                 body["token"]), "original login failed or unknown")
        self.token = body["token"]
        self.remember(self.token)
        require(self.request("ACTOR")["category"] == "SUCCESS", "actor binding failed")
        self.logged_at = time.monotonic()

    def protected(self):
        tables = auth.FINANCIAL_TABLES + AUTHORITY_TABLES
        if self.gameplay_binding is not None:
            tables += GAMEPLAY_EMPTY_TABLES
        result = {}
        self.report["protectedCollection"] = {"result": "COLLECTING", "tables": result}
        for table in tables:
            if table == "ums_member" and self.gameplay_binding is not None:
                with self.db.cursor() as cur:
                    cur.execute("SELECT * FROM ums_member ORDER BY id")
                    values = cur.fetchmany(4097)
                    require(len(values) <= 4096, "bounded fixture query exceeded")
                    self.member_columns = [column[0] for column in cur.description]
                    self.member_rows = values
            else:
                values = self.rows("SELECT * FROM `" + table + "` ORDER BY 1")
            result[table] = {"rows": len(values), "sha256": digest(values)}
            self.save()
            result[table]["ddlSha256"] = digest(self.rows("SHOW CREATE TABLE `" + table + "`"))
            self.save()
        result["accountCredentials"] = digest(self.rows(
            "SELECT user_id,user_name,password,status,del_flag FROM sys_user ORDER BY user_id"))
        self.report["protectedCollection"]["result"] = "COLLECTED"
        self.save()
        return result

    def require_gameplay_no_cash(self, protected):
        binding = self.gameplay_binding
        require(binding is not None, "explicit gameplay profile required")
        validate_gameplay_binding(self.objects, binding)
        empty = tuple(table for table in auth.FINANCIAL_TABLES if table != "ums_member") + GAMEPLAY_EMPTY_TABLES
        bad = [table for table in empty if protected[table]["rows"] != 0]
        if bad:
            raise NoCashFixtureError("GAMEPLAY_UNEXPECTED_ECONOMIC_ROWS", bad)
        proof = binding["actorProof"]
        path = self.private.parent / "gameplay/identities.json"
        identities = json.loads(path.read_text())
        observed = gameplay_actor_proof(identities, self.member_columns, self.member_rows,
            self.schema, self.saved["serverUuid"], proof["seedBootId"], file_digest(path)["sha256"],
            {key: protected[key] for key in AUTHORITY_TABLES + ("accountCredentials",)})
        if observed["authority"] != proof["authority"]:
            raise NoCashFixtureError("GAMEPLAY_ORIGINAL_AUTHORITY_CHANGED",
                                     [key for key in observed["authority"] if observed["authority"][key] != proof["authority"].get(key)])
        if observed != proof:
            raise NoCashFixtureError("GAMEPLAY_ORIGINAL_MEMBER_FACT_CHANGED", ("ums_member",))
        if self.rows("SELECT COUNT(*) FROM refund_application")[0][0] != 0:
            raise NoCashFixtureError("GAMEPLAY_REFUND_FIXTURE_NOT_EMPTY", ("refund_application",))

    def refund(self):
        with self.db.cursor() as cur:
            cur.execute("SELECT * FROM refund_application WHERE id=20")
            row = cur.fetchone()
            require(row is not None, "original lease row missing")
            return dict(zip([item[0] for item in cur.description], row))

    def blocked(self, db=None):
        return int(self.rows(
            "SELECT COUNT(*) FROM information_schema.processlist "
            "WHERE USER='startup_probe' AND DB=%s "
            "AND STATE IN ('Waiting for table metadata lock','Waiting for table level lock') "
            "AND LOCATE('sys_dict_data',COALESCE(INFO,''))>0",
            (self.schema,), db)[0][0])

    def stop(self):
        if not self.stopped:
            obj = json.loads(auth.command(D + ["inspect", self.app]))[0]
            require(obj["Id"] == self.app and obj["Name"] == "/" + self.name + "-app"
                    and obj["Config"]["Labels"]["chengyin.auth-recovery"] == self.name,
                    "refuse to stop foreign application")
            auth.command(D + ["stop", "--time", "10", self.app], timeout=25)
            obj = json.loads(auth.command(D + ["inspect", self.app]))[0]
            require(obj["Id"] == self.app and not obj["State"]["Running"],
                    "unsettled request fencing unknown")
            self.stopped = True

    def sample(self):
        result = {
            "atMonotonic": time.monotonic(),
            "source": "docker stats --no-stream --no-trunc; independent MySQL processlist",
            "applicationPoolWaiters": "UNKNOWN_NO_NATIVE_GAUGE",
            "applicationExecutorQueue": "UNKNOWN_NO_NATIVE_GAUGE",
            "jvmHeapBytes": "UNKNOWN_NOT_SAMPLED",
            "cacheBytes": "UNKNOWN_NOT_SAMPLED",
        }
        try:
            raw = auth.command(D + ["stats", "--no-stream", "--no-trunc",
                                    "--format", "{{json .}}", *self.ids], timeout=5)
            stats = [json.loads(line) for line in raw.splitlines() if line]
            require(len(stats) == 3, "incomplete container sample")
            projected = []
            for obj, expected in zip(stats, self.ids):
                require(obj.get("ID", obj.get("Container")) == expected,
                        "stats identity mismatch")
                cpu = float(obj["CPUPerc"].removesuffix("%"))
                require(math.isfinite(cpu) and cpu >= 0, "invalid CPU sample")
                projected.append({
                    "id": expected, "cpuPercent": cpu,
                    "memoryUsageAndLimit": obj["MemUsage"],
                    "pidsIncludingClientAndTracer": int(obj["PIDs"]),
                })
            result["containers"] = projected
        except Exception:
            result["containers"] = "UNKNOWN_SAMPLE_UNAVAILABLE"
        try:
            with auth.database(self.port, self.schema) as db:
                result["applicationMysqlConnections"] = int(self.rows(
                    "SELECT COUNT(*) FROM information_schema.processlist "
                    "WHERE USER='startup_probe' AND DB=%s", (self.schema,), db)[0][0])
                result["blockedDictionarySql"] = self.blocked(db)
        except Exception:
            result["applicationMysqlConnections"] = "UNKNOWN_SAMPLE_UNAVAILABLE"
        return result

    def stage(self, size, concurrency, keys):
        before = self.stamp()
        require(before == self.initial, "process/resource identity changed")
        samples, stop = [], threading.Event()
        def monitor():
            while not stop.is_set() and len(samples) < 32:
                samples.append(self.sample())
                stop.wait(2)
        thread = threading.Thread(target=monitor, name="capacity-owned-sampler")
        thread.start()
        try:
            items = [item for key in keys for item in (("DICT", key), ("ACTOR", None))]
            curve = run_curve(items, self.request, concurrency, self.stop)
        finally:
            stop.set()
            thread.join(timeout=17)
            require(not thread.is_alive(), "sampler did not terminate")
        curve.update(rowsPerDictionary=size, dictionaryKeys=len(keys),
                     actualOwnedDatasetRows=sum(len(value) for value in self.expected.values()),
                     samples=samples, before=before, after=self.stamp())
        require(curve["after"] == before, "stage process/database changed")
        self.report["curves"].append(curve)
        self.save()
        require(curve["requestsRecorded"] == REQUESTS_PER_STAGE
                and curve["counts"]["SUCCESS"] == REQUESTS_PER_STAGE,
                "normal curve failed or unknown; preserve partial curve")

    def curves(self):
        with self.db.cursor() as cur:
            for size in SIZES:
                keys = [self.nonce + "s" + str(size) + "_k" + str(i) for i in range(12)]
                for key in keys:
                    expected = [("capacity-" + str(i), str(i)) for i in range(size)]
                    self.expected[key] = expected
                    cur.executemany(
                        "INSERT INTO sys_dict_data"
                        "(dict_sort,dict_label,dict_value,dict_type,status) "
                        "VALUES(%s,%s,%s,%s,'0')",
                        [(i, label, value, key) for i, (label, value) in enumerate(expected)])
                require(self.rows(
                    "SELECT COUNT(*) FROM sys_dict_data WHERE LEFT(dict_type,%s)=%s",
                    (len(self.nonce), self.nonce))[0][0] == sum(
                        len(value) for value in self.expected.values()),
                        "owned dataset readback mismatch")
                for concurrency in CONCURRENCY:
                    require(time.monotonic() - self.logged_at < 120,
                            "original authenticated fixture too old; no automatic relogin")
                    self.stage(size, concurrency, keys)

    def mixed(self):
        require(time.monotonic() - self.logged_at < 120, "original session too old")
        require(self.rows("SELECT COUNT(*) FROM refund_application")[0][0] == 0,
                "manual queue fixture is not empty")
        with self.db.cursor() as cur:
            cur.execute(
                "INSERT INTO refund_application"
                "(id,refund_no,source_type,source_id,refund_amount,status,reason,"
                "del_flag,payout_status,create_time,update_time) "
                "VALUES(20,NULL,'registration',11,1,1,'isolated lease fixture',0,2,NOW(),NOW())")
        original = self.refund()
        require(original["refund_no"] is None and original["payout_status"] == 2
                and all(original[key] is None for key in auth.LEASE_COLUMNS - {"update_time"}),
                "not the original never-submitted manual lease fixture")
        claim_record, body = self.http("CLAIM", {"refundId": 20})
        require(claim_record["category"] == "SUCCESS" and isinstance(body.get("data"), str)
                and body["data"], "claim failed/unknown; no retry")
        lease = body["data"]
        self.remember(lease)
        claimed = self.refund()
        auth.assert_refund_transition(original, claimed, auth.LEASE_COLUMNS)
        require(claimed["payout_lease_owner"] == 1 and claimed["payout_lease_token"] == lease
                and self.rows(
                    "SELECT payout_lease_expires_at>NOW(3) FROM refund_application WHERE id=20"
                )[0][0] == 1, "durable original lease binding failed")
        fault = {"claim": claim_record, "claimDurablyVerified": True,
                 "claimedRowSha256": digest(claimed), "samples": []}
        self.report["mixed"] = fault
        self.save()
        pool = concurrent.futures.ThreadPoolExecutor(max_workers=6)
        holder_timer = None
        safety_release = threading.Event()
        try:
            self.holder = auth.database(self.port, self.schema)
            with self.holder.cursor() as cur:
                cur.execute("SELECT CONNECTION_ID(),DATABASE(),CURRENT_USER()")
                connection, schema, user = cur.fetchone()
                require(schema == self.schema and user.startswith("root@"),
                        "lock holder not bound to owned schema/root connection")
                cur.execute("LOCK TABLES sys_dict_data WRITE")
            fault["holderConnectionId"] = connection
            def emergency_release():
                safety_release.set()
                self.holder.close()
            holder_timer = threading.Timer(18, emergency_release)
            holder_timer.start()
            keys = [self.nonce + "fault_" + str(i) for i in range(4)]
            for key in keys:
                self.expected[key] = []
            pending = [pool.submit(self.request, "DICT", key) for key in keys[:3]]
            deadline = time.monotonic() + 3
            waits = []
            while time.monotonic() < deadline:
                waits.append(self.blocked())
                if waits[-1] == 3:
                    break
                time.sleep(.05)
            fault["physicalBlockedSqlCounts"] = waits
            require(waits and waits[-1] == 3, "three real SQL reads were not blocked")
            self.pause_intent = True
            auth.command(D + ["pause", self.redis])
            paused = json.loads(auth.command(D + ["inspect", self.redis]))[0]
            blocked_at_dispatch = self.blocked()
            assert_fault_overlap(blocked_at_dispatch, paused["State"]["Paused"])
            fault["blockedAtFaultDispatch"] = blocked_at_dispatch
            fault["ownedRedisPausedAtDispatch"] = True
            release = pool.submit(self.request, "RELEASE",
                                  {"refundId": 20, "leaseToken": lease})
            actor = pool.submit(self.request, "ACTOR")
            overflow = pool.submit(self.request, "DICT", keys[3])
            fault["samples"].append(self.sample())
            fault["rejectedRelease"] = release.result(timeout=41)
            require(fault["rejectedRelease"]["category"] == "LEGAL_AUTH_REJECTION",
                    "fault command not conclusively auth-rejected; no retry")
            require(self.refund() == claimed, "fault command changed original lease row")
            fault["faultRowUnchanged"] = True
            fault["readAttempts"] = [future.result(timeout=41)
                                     for future in pending + [actor, overflow]]
            require(not safety_release.is_set(), "holder safety deadline released unexpectedly")
            require(all(row["category"] in (
                "SUCCESS", "LEGAL_AUTH_REJECTION", "SYSTEM_FAILURE")
                        for row in fault["readAttempts"]),
                    "mixed read outcome unknown")
            holder_timer.cancel()
            with self.holder.cursor() as cur:
                cur.execute("UNLOCK TABLES")
            self.holder.close()
            self.holder = None
            require(self.rows(
                "SELECT COUNT(*) FROM information_schema.processlist WHERE ID=%s",
                (connection,))[0][0] == 0, "owned holder connection remains")
            fault["holderReleaseAndAbsenceVerified"] = True
            auth.command(D + ["unpause", self.redis])
            self.pause_intent = False
            require(auth.command(D + ["exec", self.redis, "redis-cli", "PING"]).strip()
                    == b"PONG", "owned Redis recovery unknown")
            require(self.blocked() == 0, "owned SQL waits remain")
            recovery = [self.request("ACTOR"), self.request("DICT", keys[3])]
            require(all(row["category"] == "SUCCESS" for row in recovery),
                    "post-fault actor/dictionary recovery failed")
            fault["recoveryReads"] = recovery
            released_record = self.request(
                "RELEASE", {"refundId": 20, "leaseToken": lease})
            require(released_record["category"] == "SUCCESS",
                    "original release failed/unknown; no retry")
            released = self.refund()
            auth.assert_refund_transition(claimed, released, auth.LEASE_COLUMNS)
            require(all(released[key] is None
                        for key in auth.LEASE_COLUMNS - {"update_time"}),
                    "lease release not persisted")
            fault["originalRelease"] = released_record
            fault["originalReleaseDurablyVerified"] = True
            replay = self.request("RELEASE", {"refundId": 20, "leaseToken": lease})
            require(replay["category"] == "SYSTEM_FAILURE" and self.refund() == released,
                    "original release replay not rejected unchanged")
            # A generic 500 is not promoted to a generally legal business category.
            fault["releaseReplay"] = replay
            fault["releaseReplayRejectedAndFullRowUnchanged"] = True
            fault["releasedRowSha256"] = digest(released)
            require(self.stamp() == self.initial, "same-process recovery not proved")
            fault["sameProcessRecoveryVerified"] = True
            self.save()
        finally:
            if holder_timer is not None:
                holder_timer.cancel()
            pool.shutdown(wait=True, cancel_futures=True)


def observe(name, schema, out, private, relay_port, *, gameplay_binding=None):
    """Root/C calls this only inside the existing verified mysql_relay context."""
    report = {
        "result": "NOT_COMPLETED", "curves": [],
        "scope": "fixed authenticated fixture: dictionary/actor reads and no-cash lease only",
        "wholeSystemCapacityAccepted": False, "q4q8Complete": False,
        "providers": "NOT_COVERED_NO_APPROVED_SAFE_PROVIDER_FAULT_POINT",
        "paymentCallbacks": "NOT_COVERED",
        "routeCacheEvictionAndLongProvider": "NOT_COVERED",
        "instanceTakeover": "NOT_COVERED",
        "outerCleanup": "NOT_OBSERVED_YET",
    }
    probe = None
    first_error = None
    try:
        probe = Probe(name, schema, out, private, relay_port, report, gameplay_binding)
        probe.save()
        probe.login()
        protected = probe.protected()
        report["protectedBefore"] = protected
        probe.save()
        if probe.gameplay_binding is None:
            bad = [table for table in auth.FINANCIAL_TABLES if protected[table]["rows"] != 0]
            report["protectedAdmission"] = {"reasonCode": "DEFAULT_FINANCIAL_NOT_EMPTY" if bad else "DEFAULT_FINANCIAL_EMPTY",
                                            "tables": bad}
            probe.save()
            require(all(protected[table]["rows"] == 0 for table in auth.FINANCIAL_TABLES),
                    "financial fixture not empty; no cash workload permitted")
        else:
            probe.require_gameplay_no_cash(protected)
        report["protectedAdmission"] = {"reasonCode": "ORIGINAL_NO_CASH_FACTS_VERIFIED", "tables": []}
        probe.save()
        probe.curves()
        probe.mixed()
        report["protectedAfter"] = probe.protected()
        require(report["protectedAfter"] == protected,
                "financial/authorization protected facts changed")
        report["final"] = probe.stamp()
        require(report["final"] == probe.initial, "same process/database identity changed")
        report["jarAfter"] = probe.jar_identity()
        require(report["jarBefore"] == report["jarAfter"], "JAR bytes changed")
        report["result"] = PASS_OBSERVATION
    except BaseException as exc:
        first_error = exc
        report["result"] = "NOT_RUN_ADMISSION" if isinstance(exc, NotRun) else "FAIL_PARTIAL"
        report["firstFailure"] = {"type": type(exc).__name__}
        if isinstance(exc, NoCashFixtureError):
            report["firstFailure"].update(reasonCode=exc.reason_code, tables=exc.tables)
        elif report.get("protectedAdmission", {}).get("tables"):
            report["firstFailure"].update(report["protectedAdmission"])
        if probe is not None:
            try:
                # Fence any unsettled command before either dependency recovers.
                probe.stop()
                report["failedRunApplicationFenced"] = True
            except Exception as cleanup_error:
                report.setdefault("cleanupFailures", []).append(type(cleanup_error).__name__)
    finally:
        if probe is not None:
            try:
                if probe.holder is not None:
                    probe.holder.close()
                if probe.pause_intent:
                    require(probe.stopped, "failed command not fenced before Redis recovery")
                    auth.command(D + ["unpause", probe.redis])
                probe.db.close()
            except Exception as exc:
                report.setdefault("cleanupFailures", []).append(type(exc).__name__)
                report["result"] = "FAIL_PARTIAL"
                if first_error is None:
                    first_error = exc
        auth.write_report(out, "capacity-mixed.json", report)
    if first_error is not None:
        raise first_error


def validate_observation(report, identity):
    require(report.get("result") == PASS_OBSERVATION, "observation incomplete")
    require(report.get("protectedAdmission", {}).get("reasonCode") == "ORIGINAL_NO_CASH_FACTS_VERIFIED",
            "original no-cash admission not proved")
    require(report.get("identity") == identity, "foreign source/run/attempt")
    require(report.get("initial") == report.get("final"), "runtime identity changed")
    require(report.get("jarBefore") == report.get("jarAfter")
            and re.fullmatch(r"[0-9a-f]{64}", report["jarBefore"]["sha256"]),
            "JAR evidence incomplete")
    require(report.get("protectedBefore") == report.get("protectedAfter"),
            "protected facts differ")
    require(not report.get("cleanupFailures"), "inner cleanup unknown")
    curves = report.get("curves")
    require(isinstance(curves, list) and len(curves) == 9, "missing/extra curve")
    expected = [(size, concurrency) for size in SIZES for concurrency in CONCURRENCY]
    require([(row["rowsPerDictionary"], row["configuredConcurrency"]) for row in curves]
            == expected, "curve identity/order mismatch")
    for row in curves:
        require(row["requestsRecorded"] == REQUESTS_PER_STAGE
                and row["counts"]["SUCCESS"] == REQUESTS_PER_STAGE
                and sum(row["counts"].values()) == REQUESTS_PER_STAGE
                and row["before"] == report["initial"] == row["after"],
                "curve failed/unknown or foreign process")
    fault = report["mixed"]
    assert_fault_overlap(fault["blockedAtFaultDispatch"],
                         fault["ownedRedisPausedAtDispatch"])
    require(fault["rejectedRelease"]["category"] == "LEGAL_AUTH_REJECTION"
            and all(fault.get(key) is True for key in (
                "claimDurablyVerified", "faultRowUnchanged",
                "holderReleaseAndAbsenceVerified", "originalReleaseDurablyVerified",
                "releaseReplayRejectedAndFullRowUnchanged", "sameProcessRecoveryVerified")),
            "mixed recovery/durable facts incomplete")


def finalize(name, schema, out, original_exit):
    """After existing auth.cleanup: independently check exact IDs and exact names."""
    path = out / "capacity-mixed.json"
    report = json.loads(path.read_text())
    report.setdefault("businessResultBeforeCleanup", report["result"])
    report["originalExitCode"] = original_exit
    checks = []
    try:
        identity = source_identity()
        validate_observation(report, identity)
        require(original_exit == 0, "original entry failed")
        require(report["taskName"] == name and report["schema"] == schema,
                "foreign observation scope")
        auth_receipt = json.loads((out / "authenticated-redis-recovery.json").read_text())
        cleanup = auth_receipt["resourceCleanup"]
        require(cleanup["cleanup"] == "CONFIRMED_EXACT_OWNED_REMOVAL",
                "existing outer cleanup unconfirmed")
        expected = {name + "-" + suffix: cid
                    for suffix, cid in zip(("app", "mysql", "redis"), report["ownedIds"])}
        removed = cleanup["ownedRemoved"]
        require(len(removed) == 3 and
                {row["name"]: row["id"] for row in removed} == expected
                and all(row["independentNoSuch"] is True for row in removed),
                "foreign/duplicate/incomplete cleanup receipt")
        for kind, identifiers in (
            ("container", list(expected) + list(expected.values())),
            ("network", [name, report["networkId"]]),
        ):
            for identifier in identifiers:
                result = subprocess.run(
                    D + [kind, "inspect", identifier],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
                auth.require_absent(kind, identifier, result.returncode, result.stderr)
                checks.append({"kind": kind, "identifier": identifier, "absent": True})
        report["outerCleanup"] = {"result": "EXACT_IDS_AND_NAMES_ABSENT", "checks": checks}
        report["result"] = PASS_FINAL
    except BaseException as exc:
        report.setdefault("firstFailure", {"type": type(exc).__name__})
        report["finalizationFailure"] = {"type": type(exc).__name__}
        report["outerCleanup"] = {"result": "UNKNOWN_OR_FAILED", "checks": checks}
        report["result"] = "FAIL_PARTIAL"
        raise
    finally:
        auth.write_report(out, path.name, report)


def main():
    validate_env(os.environ)
    if sys.argv[1:] == ["--check-execution-only"]:
        return
    mode, name, schema, output, private_file, value = sys.argv[1:]
    require(mode in ("observe", "finalize")
            and re.fullmatch(r"cy-auth-[0-9a-f]{32}", name)
            and schema == "cy_e2e_auth_" + name[8:], "fixed owned fixture required")
    out, private = Path(output), Path(private_file)
    require(out.is_dir(), "existing evidence directory required")
    auth.SECRET_VALUES.extend([
        os.environ["CHENGYIN_AUTH_FIXTURE_DB_PASSWORD"], "synthetic-local-only"])
    if mode == "finalize":
        require(re.fullmatch(r"[0-9]{1,3}", value) and int(value) <= 255,
                "original exit code required")
        finalize(name, schema, out, int(value))
        return
    require(value == "0", "CLI never accepts an arbitrary relay endpoint")
    baseline = json.loads((out / "schema-summary.json").read_text())["mysqlReadiness"]
    require(baseline["result"] == "OWNED_MYSQL_TCP_READY", "original readiness missing")
    with auth.mysql_relay(name, baseline) as port:
        observe(name, schema, out, private, port)


if __name__ == "__main__":
    main()
