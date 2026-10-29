#!/usr/bin/env python3
"""HTTP fixture receipt verifier and pure negative controls; never starts Java/storage."""
import argparse
import importlib.util
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'chengyinhub-admin/src/test/tools'
EXPECTED = {'GenericGameplayHttpE2eIT': 26, 'FreezeGameHttpE2eIT': 8,
            'FreezeReuseThemeHttpE2eIT': 2}
PACKAGE = 'com.chengyinhub.business.it.'
COMBINATION_SUITE = 'CrossDomainCombinedLoadMySqlIT'


def reviewed_count(simple):
    return 1 if simple == COMBINATION_SUITE else EXPECTED[simple]
FIXED_HTTP_MESSAGE_SOURCES = {
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImpl.java': {
        '该玩法不属于当前共享局': 'ADVANCED_SHARED_SCOPE_MISMATCH',
        '该玩法不属于当前任务实例': 'ADVANCED_TASK_INSTANCE_MISMATCH',
        '共享玩法权威版本缺失，请重新建立玩法实例': 'ADVANCED_AUTHORITY_MISSING',
        '共享玩法权威版本缺失': 'ADVANCED_AUTHORITY_MISSING',
        '状态已更新，请刷新后重试': 'ADVANCED_STATE_CONFLICT',
        '本局未开启相册提交': 'ALBUM_SUBMISSION_DISABLED',
        '相册需提交1至20张照片': 'ALBUM_PHOTO_COUNT_INVALID',
        '相册照片地址不合法': 'ALBUM_PHOTO_ADDRESS_INVALID',
    },
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/GameplayCheckEngine.java': {
        '本局未启用可信检定尝试': 'CHECK_TRUSTED_ATTEMPT_DISABLED',
        '本次检定已提交': 'CHECK_ALREADY_SUBMITTED',
        '请先掷骰': 'CHECK_ROLL_REQUIRED',
        '幸运值不足': 'CHECK_LUCK_INSUFFICIENT',
        '检定难度档位无效': 'CHECK_TIER_INVALID',
    },
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/GameSessionRuntimeServiceImpl.java': {
        '本局权威版本已更新，请刷新': 'GAME_AUTHORITY_CONFLICT',
        '本局权威版本已更新': 'GAME_AUTHORITY_CONFLICT',
        '本队已完成最后章节，资产与回执可继续查看': 'GAME_ROUTE_FINISHED',
        '仅本场现有队长可消耗队伍物资': 'GAME_RESOURCE_CAPTAIN_REQUIRED',
        '检定不属于当前实例': 'GAME_CHECK_INSTANCE_MISMATCH',
        '检定未启用结果规则': 'GAME_CHECK_OUTCOME_RULES_MISSING',
        '本局已更新': 'GAME_STATE_CONFLICT',
    },
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/GameSharedRunControl.java': {
        '本局暂停或由人工账本接管，当前只能读取': 'GAME_RUN_PAUSED_OR_MANUAL',
    },
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicRouteRuntimeServiceImpl.java': {
        '本局已更新，请刷新': 'GAME_STATE_CONFLICT',
        'Outcome does not belong to the current attempt': 'GAME_OUTCOME_ATTEMPT_MISMATCH',
    },
    'chengyinhub-common/src/main/java/com/chengyinhub/common/utils/UserSafeMessage.java': {
        '系统繁忙，请稍后重试': 'SYSTEM_BUSY_MASKED',
    },
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/AppMediaReferenceGuard.java': {
        '媒体来源未经审核，请重新上传': 'MEDIA_SOURCE_UNREVIEWED',
        '媒体不属于当前账号，请重新上传': 'MEDIA_FOREIGN_OWNER',
        '媒体尚未审核通过，暂不能公开': 'MEDIA_NOT_APPROVED',
    },
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/AppMediaPublicationGuard.java': {
        '媒体配置格式不正确': 'MEDIA_CONFIGURATION_INVALID',
        '暂不支持外部链接，请删除后再发布': 'MEDIA_EXTERNAL_LINK',
        '主题不存在，不能审核媒体': 'THEME_PUBLICATION_MISSING',
        '节点模板不存在，不能公开': 'THEME_TEMPLATE_MISSING',
        '媒体所属内容不存在': 'MEDIA_CONTENT_MISSING',
    },
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/QuotaServiceImpl.java': {
        '请先登录': 'AUTH_REQUIRED',
        '用户不存在': 'MEMBER_MISSING',
        '当前身份权限配置缺失，请联系管理员': 'ROLE_CONFIG_MISSING',
        '当前身份不可创建主题': 'THEME_ROLE_FORBIDDEN',
    },
    'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiTopicController.java': {
        '主题发布失败，请稍后重试': 'TOPIC_PUBLISH_FAILED',
        '主题不存在或无权查看': 'THEME_OWNER_OR_MISSING',
        '无权编辑该主题': 'THEME_OWNER_FORBIDDEN',
        '无权操作该主题': 'THEME_OWNER_FORBIDDEN',
    },
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MemberIdentityService.java': {
        '请先登记实名信息(姓名与身份证号),再发布': 'PUBLISHER_IDENTITY_MISSING',
    },
}


def fixed_http_failure_categories(text):
    fixed = {message: category for messages in FIXED_HTTP_MESSAGE_SOURCES.values()
             for message, category in messages.items()}
    categories = set()
    for quoted in re.findall(r'"msg"\s*:\s*("(?:[^"\\]|\\.)*")', text):
        try:
            categories.add(fixed.get(json.loads(quoted), 'UNKNOWN'))
        except ValueError:
            categories.add('UNKNOWN')
    return sorted(categories or {'UNKNOWN'})


HTTP_SOURCE_STAGES = frozenset({'TEST_BODY', 'PUBLICATION_SETUP', 'PLAYER_ENTRY_FIXTURE', 'PLAYER_INITIAL_STATE',
                               'PLAYER_ARRIVAL', 'PLAYER_SCAN_READBACK', 'PLAYER_QUEST_START', 'PLAYER_COMPONENT_START'})
HTTP_SOURCE_ENDPOINTS = frozenset({'OTHER', 'TEMPLATE_DRAFT', 'TEMPLATE_READ', 'TOPIC_CREATE', 'TOPIC_UPDATE',
                                  'STATE_KEYS', 'TOPIC_DETAIL', 'TOPIC_PUBLISH', 'THEME_STATE', 'CHECKIN',
                                  'GAMEPLAY_ACTION', 'ADVANCED_START', 'ADVANCED_STATE', 'ADVANCED_ACTION', 'THEME_ACTION', 'THEME_CARD'})


def suppressed_source_context(text):
    contexts = set()
    for line in text.splitlines():
        if 'HTTP_SOURCE_CONTEXT' not in line:
            continue
        match = re.fullmatch(r'[ \t]*Suppressed: java\.lang\.AssertionError: HTTP_SOURCE_CONTEXT stage=([A-Z_]+) endpoint=([A-Z_]+)', line)
        if not match or match[1] not in HTTP_SOURCE_STAGES or match[2] not in HTTP_SOURCE_ENDPOINTS:
            return 'UNKNOWN'
        contexts.add(match.groups())
    if not contexts:
        return None
    if len(contexts) != 1:
        return 'UNKNOWN'
    stage, endpoint = next(iter(contexts))
    return {'stage': stage, 'endpoint': endpoint}


def selection(value):
    names = value.split(',')
    if names == [COMBINATION_SUITE]:
        return names
    if not names or len(names) != len(set(names)) or any(n not in EXPECTED for n in names):
        raise ValueError('Select only the exact reviewed HTTP classes, without duplicates')
    return names


def reviewed_case_identities(simple):
    source = (ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it' / (simple + '.java')).read_text()
    plain = re.findall(r'(?m)^\s*@Test(?:\s+@Order\([1-9][0-9]*\))?\s+void\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(\s*\)', source)
    plain_count = {'GenericGameplayHttpE2eIT': 15, 'FreezeGameHttpE2eIT': 8, 'FreezeReuseThemeHttpE2eIT': 2,
                   COMBINATION_SUITE: 1}
    if len(plain) != plain_count[simple] or len(set(plain)) != len(plain):
        raise ValueError('Reviewed ordinary HTTP test declarations changed')
    aliases = {name: name for name in plain}
    reviewed = {
        'r5HttpTypeInUnlimitedAndTimerDeadlineSurviveReentry': ('boolean', [False, True]),
        'r5HttpCheckRollRerollSubmitHasAuthoritativeActions': ('String', ['easy', 'medium', 'hard']),
        'r5HttpSettledFeedbackCanReopenThroughTheComponentStartPath': ('String', ['PASS', 'FAIL', 'CLOSED']),
        'r5HttpTeamCheckRerollIsCaptainOnlyAndSharedSettlementIsIdempotent': ('String', ['easy', 'medium', 'hard']),
    } if simple == 'GenericGameplayHttpE2eIT' else {}
    declared = {}
    for field, values, method, kind in re.findall(
            r'@org\.junit\.jupiter\.params\.ParameterizedTest\s+'
            r'@org\.junit\.jupiter\.params\.provider\.ValueSource\(\s*(booleans|strings)\s*=\s*\{([^}]*)\}\s*\)\s+'
            r'void\s+([A-Za-z_$][A-Za-z0-9_$]*)\((boolean|String)\s+[A-Za-z_$][A-Za-z0-9_$]*\)', source):
        if method in declared or field != ('booleans' if kind == 'boolean' else 'strings'):
            raise ValueError('Unexpected parameterized HTTP declaration')
        declared[method] = (kind, json.loads('[' + values + ']'))
    if declared != reviewed or source.count('@org.junit.jupiter.params.ParameterizedTest') != len(reviewed):
        raise ValueError('Reviewed HTTP parameter methods/types/values changed')
    for method, (kind, values) in reviewed.items():
        for index in range(1, len(values) + 1):
            canonical = method + '(' + kind + ')[' + str(index) + ']'
            aliases[canonical] = canonical
            if kind == 'String':
                aliases[method + '(java.lang.String)[' + str(index) + ']'] = canonical
    if len(set(aliases.values())) != reviewed_count(simple):
        raise ValueError('Reviewed expanded HTTP count changed')
    return aliases


def verified_xml(path, simple):
    root = ET.parse(path).getroot()
    cases = root.findall('testcase')
    expected = reviewed_count(simple)
    if root.tag != 'testsuite' or root.get('name') != PACKAGE + simple:
        raise ValueError('Unexpected suite identity')
    if int(root.get('tests', '-1')) != expected or len(cases) != expected:
        raise ValueError('Actual testcase count differs from the reviewed class')
    if any(int(root.get(k, '-1')) != 0 for k in ('failures', 'errors', 'skipped')):
        raise ValueError('HTTP tests must have zero failures/errors/skips')
    allowed = reviewed_case_identities(simple)
    identities = [(c.get('classname'), allowed.get(c.get('name'))) for c in cases]
    if len(set(identities)) != expected or {name for _, name in identities} != set(allowed.values()) or any(c != PACKAGE + simple for c, _ in identities):
        raise ValueError('Unexpected/duplicate testcase identity')
    if any(node.tag in ('failure', 'error', 'skipped', 'rerunFailure', 'rerunError', 'flakyFailure', 'flakyError')
           for c in cases for node in c.iter()):
        raise ValueError('Actual failed/error/skipped testcase')
    # Never publish Surefire properties, system streams, failure bodies or arbitrary attributes.
    safe = ET.Element('testsuite', name=PACKAGE + simple, tests=str(expected),
                      failures='0', errors='0', skipped='0')
    for case in cases:
        ET.SubElement(safe, 'testcase', classname=case.get('classname'), name=case.get('name'))
    if safe.find('properties') is not None or safe.find('system-out') is not None:
        raise ValueError('Unsafe XML publication')
    return safe


def receipt_identity(receipt, source=None):
    if not isinstance(receipt, dict):
        raise ValueError('Invalid HTTP receipt identity')
    classes = receipt.get('classes')
    if not isinstance(classes, list) or not all(isinstance(name, str) for name in classes):
        raise ValueError('Missing reviewed HTTP class identity')
    names = selection(','.join(classes))
    if source and receipt.get('sourceSha') != source:
        raise ValueError('Wrong source receipt')
    if not isinstance(receipt.get('sourceSha'), str) or not re.fullmatch('[0-9a-f]{40}', receipt['sourceSha']):
        raise ValueError('Missing exact source SHA')
    if (receipt.get('profile') not in ('vps', 'github-hosted') or not isinstance(receipt.get('database'), str)
            or not re.fullmatch(r'cy_e2e_w1_http_[0-9_]+', receipt['database'])):
        raise ValueError('Missing execution profile / owned schema identity')
    return {key: receipt[key] for key in ('classes', 'sourceSha', 'profile', 'database')}


def execution_identity(classes=None):
    return receipt_identity({'classes': selection(classes if classes is not None else os.environ.get('CHENGYIN_HTTP_E2E_TESTS', '')),
                             'sourceSha': os.environ.get('SOURCE_SHA', ''),
                             'profile': os.environ.get('CHENGYIN_HTTP_E2E_PROFILE', 'vps'),
                             'database': os.environ.get('CHENGYIN_HTTP_E2E_DATABASE', '')})


def validate_final(receipt, source=None):
    names = receipt_identity(receipt, source)['classes']
    if receipt.get('fixtureReportVerified') is not True:
        raise ValueError('Missing genuine completed fixture report')
    if receipt.get('state') != 'PASS' or receipt.get('javaExit') != 0:
        raise ValueError('Java/verification did not pass')
    if receipt.get('tests') != sum(reviewed_count(n) for n in names) or receipt.get('xmlVerified') is not True:
        raise ValueError('Missing actual exact-count XML proof')
    if receipt.get('cleanup') != {'mysqlAbsent': True, 'redisStoppedAndPortFree': True}:
        raise ValueError('Missing independent cleanup proof')
    return names


def summarize(paths, source):
    seen = []
    for path in paths:
        receipt=json.loads(path.read_text())
        if receipt.get('profile') != 'github-hosted':
            raise ValueError('Summary requires honest hosted receipts')
        seen.extend(validate_final(receipt, source))
    if sorted(seen) != sorted(EXPECTED):
        raise ValueError('All three classes exactly once are required')
    return {'sourceSha': source, 'state': 'PASS_HTTP_FIXTURE_ONLY', 'classes': EXPECTED,
            'tests': 36, 'failures': 0, 'errors': 0, 'skipped': 0,
            'cleanupAllVerified': True, 'businessAccepted': False, 'migrationAccepted': False}


def generic_diagnostic_receipt(paths, source):
    if not isinstance(source, str) or not re.fullmatch('[0-9a-f]{40}', source):
        raise ValueError('Missing exact partial-diagnostic source SHA')
    if len(paths) != 1:
        raise ValueError('Generic diagnostic scope requires exactly one receipt')
    receipt = json.loads(paths[0].read_text())
    identity = receipt_identity(receipt, source)
    if identity['profile'] != 'github-hosted' or identity['classes'] != ['GenericGameplayHttpE2eIT']:
        raise ValueError('Generic diagnostic scope excludes other class receipts')
    return receipt


def verify_combination_budget(directory, report, receipt, *, after_storage_cleanup):
    spec = importlib.util.spec_from_file_location('http_combination_budget', ROOT / 'ci/combined_http_budget.py')
    budget = importlib.util.module_from_spec(spec); spec.loader.exec_module(budget)
    verify = budget.require_final_budget if after_storage_cleanup else budget.require_subject_budget
    verify(directory, {'sourceSha': receipt['sourceSha'], 'runId': report['runId'],
                       'attempt': report['runAttempt'], 'class': COMBINATION_SUITE},
           report['javaPid'], receipt['jarSha256'], receipt['database'])


def scoped_summary(paths, source, scope):
    if scope == 'all-three':
        return summarize(paths, source)
    if scope == 'controlled-combination':
        if len(paths) != 1:
            raise ValueError('Controlled combination requires one own exact receipt')
        receipt = json.loads(paths[0].read_text())
        if receipt_identity(receipt, source)['classes'] != [COMBINATION_SUITE]:
            raise ValueError('Controlled combination excludes old/foreign HTTP receipts')
        validate_final(receipt, source)
        if receipt.get('combinationEvidence') != 'VERIFIED_SCOPED_OBSERVATION_ONLY':
            raise ValueError('Missing controlled observation verification')
        try:
            report = json.loads((paths[0].parent / 'controlled-combination.json').read_text())
            spec = importlib.util.spec_from_file_location('http_combination_capacity', ROOT / 'ci/capacity_mixed_probe.py')
            capacity = importlib.util.module_from_spec(spec); spec.loader.exec_module(capacity)
            safe = capacity.controlled_combination_projection(report, receipt, source, os.environ.get('GITHUB_RUN_ID'),
                                                               os.environ.get('GITHUB_RUN_ATTEMPT'), receipt.get('jarSha256'))
            verify_combination_budget(paths[0].parent, safe, receipt, after_storage_cleanup=True)
            result = capacity.controlled_combination_summary(safe, receipt, source, os.environ.get('GITHUB_RUN_ID'),
                                                           os.environ.get('GITHUB_RUN_ATTEMPT'), receipt.get('jarSha256'))
            result.update(physicalProfile='FIXED_TEST_PROCESS_AND_OWNED_RESOURCES_PROVEN',
                          subjectBudgetVerified=True, backingStorageCleanupVerified=True)
            return result
        except (OSError, ValueError, TypeError, RuntimeError, KeyError):
            raise ValueError('Missing or invalid controlled same-run observation') from None
    if scope != 'generic-only':
        raise ValueError('Unknown HTTP diagnostic scope')
    receipt = generic_diagnostic_receipt(paths, source)
    validate_final(receipt, source)
    return {'sourceSha': source, 'state': 'PASS_PARTIAL_DIAGNOSTIC_SCOPE', 'scope': 'PARTIAL_DIAGNOSTIC_SCOPE',
            'classes': {'GenericGameplayHttpE2eIT': EXPECTED['GenericGameplayHttpE2eIT']}, 'tests': receipt['tests'],
            'failures': 0, 'errors': 0, 'skipped': 0, 'cleanupAllVerified': True,
            'businessAccepted': False, 'migrationAccepted': False, 'completeHttpSuiteAccepted': False}


def safe_receipt_diagnostic(value):
    if not isinstance(value, dict):
        return {}
    allowed = {'stage': {'schema', 'redis-start', 'redis-ready', 'java-tmp', 'java-tests', 'xml-verify', 'cleanup', 'unknown'},
               'reportState': {'UNAVAILABLE', 'INVALID', 'DRY_RUN', 'PLANNING', 'EXECUTING', 'FAILED', 'SUCCEEDED'},
               'phase': {'plan-build', 'mysql-version', 'schema-absence', 'schema-create', 'schema-apply'},
               'exceptionType': {'CalledProcessError', 'TimeoutExpired', 'ValueError', 'OSError', 'FileNotFoundError', 'PermissionError', 'JSONDecodeError', 'Exception'}}
    result = {key: value[key] for key, values in allowed.items() if isinstance(value.get(key), str) and value[key] in values}
    for key, minimum, maximum in [('exitCode', 0, 255), ('javaExit', 0, 255), ('mysqlErrorCode', 1, 99999),
                                  ('mysqlLine', 1, 10000000), ('fileIndex', 1, 10000000)]:
        item = value.get(key)
        if type(item) is int and minimum <= item <= maximum:
            result[key] = item
    file = value.get('file')
    if (isinstance(file, str) and re.fullmatch(r'(?:sql/|chengyinhub-admin/sql/)[A-Za-z0-9_./-]+\.sql', file)
            and '..' not in pathlib.PurePosixPath(file).parts and (ROOT / file).is_file()):
        result['file'] = file
    return result


def failure_summary(paths, source):
    if not isinstance(source, str) or not re.fullmatch('[0-9a-f]{40}', source):
        raise ValueError('Missing exact failure-summary source SHA')
    receipts, seen = [], set()
    for path in paths:
        receipt = json.loads(path.read_text())
        identity = receipt_identity(receipt, source)
        if identity['profile'] != 'github-hosted' or seen.intersection(identity['classes']):
            raise ValueError('Failure summary requires unique hosted class identities')
        seen.update(identity['classes'])
        cleanup = receipt.get('cleanup', {})
        if not isinstance(cleanup, dict):
            cleanup = {}
        try:
            validate_final(receipt, source)
            state = 'PASS'
        except ValueError:
            state = 'FAIL' if receipt.get('state') == 'FAIL' else 'INVALID' if receipt.get('state') == 'PASS' else 'UNKNOWN'
        projected = dict(identity, state=state, diagnostic=safe_receipt_diagnostic(receipt.get('diagnostic')),
                         cleanup={key: cleanup.get(key) if type(cleanup.get(key)) is bool else None
                                  for key in ('mysqlAbsent', 'redisStoppedAndPortFree')})
        if state == 'PASS':
            projected.update(tests=receipt['tests'], xmlVerified=True, fixtureReportVerified=True, javaExit=0)
        receipts.append(projected)
    return {'sourceSha': source, 'state': 'FAIL_HTTP_FIXTURE_ONLY', 'receipts': receipts,
            'missingClasses': sorted(set(EXPECTED) - seen), 'businessAccepted': False, 'migrationAccepted': False}


def reviewed_case_call_frame(simple, canonical, trace, source):
    # Only the existing reviewed Java test layout; unfamiliar layout stays unlocated.
    if (simple not in EXPECTED and simple != COMBINATION_SUITE) or canonical not in set(reviewed_case_identities(simple).values()):
        return None
    method = canonical.split('(', 1)[0]
    declarations = list(re.finditer(r'(?m)^([ \t]+)(?:@Test(?:\s+@Order\([1-9][0-9]*\))?\s+)?void\s+'
                                    + re.escape(method) + r'\([^)]*\)[^{\n]*\{[ \t]*$', source))
    if len(declarations) != 1:
        return None
    declaration = declarations[0]
    closing = re.search(r'(?m)^' + re.escape(declaration.group(1)) + r'\}[ \t]*$', source[declaration.end():])
    if closing is None:
        return None
    first = source.count('\n', 0, declaration.start()) + 1
    last = source.count('\n', 0, declaration.end() + closing.start()) + 1
    pattern = (r'\bat ' + re.escape(PACKAGE + simple + '.' + method) + r'\('
               + re.escape(simple + '.java') + r':([0-9]{1,7})\)')
    for line in re.findall(pattern, trace):
        if first <= int(line) <= last:
            return simple + '.java:' + str(int(line))
    return None


def safe_diagnostic(report_path, stage, exit_code, java_exit):
    # Project only reviewed metadata. Raw SQL/stderr/messages and environment never leave the runner.
    stages = {'schema', 'redis-start', 'redis-ready', 'java-tmp', 'java-tests', 'xml-verify', 'cleanup'}
    result = {'stage': stage if stage in stages else 'unknown',
              'exitCode': exit_code if type(exit_code) is int and 0 <= exit_code <= 255 else None,
              'javaExit': java_exit if type(java_exit) is int and 0 <= java_exit <= 255 else None}
    try:
        report = json.loads(report_path.read_text())
    except (OSError, ValueError):
        result['reportState'] = 'UNAVAILABLE'
        return result
    if not isinstance(report, dict):
        result['reportState'] = 'INVALID'
        return result
    result['reportState'] = report.get('mode') if report.get('mode') in ('DRY_RUN', 'PLANNING', 'EXECUTING', 'FAILED', 'SUCCEEDED') else 'INVALID'
    failure = report.get('failureDiagnostic')
    if not isinstance(failure, dict):
        return result
    if failure.get('phase') in ('plan-build', 'mysql-version', 'schema-absence', 'schema-create', 'schema-apply'):
        result['phase'] = failure['phase']
    if failure.get('exceptionType') in ('CalledProcessError', 'TimeoutExpired', 'ValueError', 'OSError', 'FileNotFoundError', 'PermissionError', 'JSONDecodeError', 'Exception'):
        result['exceptionType'] = failure['exceptionType']
    for key, maximum in [('mysqlErrorCode', 99999), ('mysqlLine', 10000000)]:
        value = failure.get(key)
        if type(value) is int and 1 <= value <= maximum:
            result[key] = value
    file = failure.get('file')
    files = report.get('files')
    if (isinstance(files, list) and isinstance(file, str) and
            re.fullmatch(r'(?:sql/|chengyinhub-admin/sql/)[A-Za-z0-9_./-]+\.sql', file) and
            '..' not in pathlib.PurePosixPath(file).parts):
        matching = [(i, f) for i, f in enumerate(files, 1) if isinstance(f, dict) and f.get('path') == file]
        if len(matching) == 1:
            index, item = matching[0]
            result.update(file=file, fileIndex=index)
    return result


def safe_maven_failure(log_path):
    result = {'category': 'unknown'}
    try:
        with log_path.open('rb') as stream:
            stream.seek(0, 2)
            stream.seek(max(0, stream.tell() - 1024 * 1024))
            text = stream.read().decode('utf-8', errors='replace')
    except OSError:
        return result
    text = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', text)
    allowed_help = {'DependencyResolutionException', 'PluginResolutionException', 'MojoExecutionException',
                    'MojoFailureException', 'LifecycleExecutionException', 'ProjectBuildingException',
                    'ModelBuildingException', 'NoPluginFoundForPrefixException'}
    help_types = re.findall(r'/display/MAVEN/([A-Za-z]+Exception)\b', text)
    for name in reversed(help_types):
        if name in allowed_help:
            result['helpException'] = name
            break
    plugins = {'org.apache.maven.plugins': {'maven-compiler-plugin', 'maven-surefire-plugin',
               'maven-resources-plugin', 'maven-clean-plugin', 'maven-jar-plugin', 'maven-install-plugin',
               'maven-dependency-plugin', 'maven-enforcer-plugin'},
               'org.springframework.boot': {'spring-boot-maven-plugin'},
               'org.jacoco': {'jacoco-maven-plugin'}}
    matches = re.findall(r'Failed to execute goal ([a-z][a-z0-9_.]{0,99}):([a-z][a-z0-9-]{0,79}):([0-9][A-Za-z0-9_.-]{0,39}):([A-Za-z][A-Za-z0-9-]{0,39})(?=\s|$)', text)
    for group, artifact, version, goal in reversed(matches):
        if artifact in plugins.get(group, set()):
            result['plugin'] = group + ':' + artifact
            if goal in {'test', 'compile', 'testCompile', 'resources', 'testResources', 'clean', 'jar',
                        'repackage', 'install', 'check', 'prepare-agent', 'report', 'enforce', 'properties',
                        'copy-dependencies', 'resolve'}:
                result['goal'] = goal
            break
    if 'plugin' not in result:
        spec = importlib.util.spec_from_file_location('http_offline_plugin_diagnostic', ROOT / 'ci/combined_http_budget.py')
        owner = importlib.util.module_from_spec(spec); spec.loader.exec_module(owner)
        result.update(owner.safe_plugin_resolution(text))
    # Only public releases pinned by this source's Boot/JUnit BOM and Surefire.
    reviewed_runtime = {
        'org.junit.platform:junit-platform-commons:1.12.2',
        'org.junit.platform:junit-platform-engine:1.12.2',
        'org.junit.platform:junit-platform-launcher:1.12.2',
        'org.junit.platform:junit-platform-commons:6.0.3',
        'org.junit.platform:junit-platform-engine:6.0.3',
        'org.junit.platform:junit-platform-launcher:6.0.3',
        'org.junit.jupiter:junit-jupiter-api:6.0.3',
        'org.junit.jupiter:junit-jupiter-engine:6.0.3',
        'org.junit.jupiter:junit-jupiter-params:6.0.3',
        'org.apache.maven.surefire:surefire-junit-platform:3.5.6',
        'org.apiguardian:apiguardian-api:1.1.2',
        'org.jspecify:jspecify:1.0.0',
        'org.opentest4j:opentest4j:1.3.0',
    }
    missing = set()
    pattern = (r'Cannot access central \(https://repo\.maven\.apache\.org/maven2/?\) in offline mode and the artifact '
               r'([a-z][a-z0-9_.]{0,99}):([a-z][a-z0-9-]{0,79}):jar:([0-9][0-9.]{0,20}) '
               r'has not been downloaded from it before\.')
    for group, artifact, version in re.findall(pattern, text):
        coordinate = group + ':' + artifact + ':' + version
        if coordinate in reviewed_runtime:
            missing.add(coordinate)
    if missing:
        result['missingRuntimeArtifacts'] = sorted(missing)
    if 'offline mode' in text and ('Cannot access ' in text or 'has not been downloaded' in text):
        result['category'] = 'offline-resolution'
    elif any(marker in text for marker in ('Failed to load ApplicationContext', 'Error occurred in starting fork',
                                          'The forked VM terminated without properly saying goodbye',
                                          'Could not find or load main class')):
        result['category'] = 'startup'
    elif result.get('plugin', '').endswith(':maven-compiler-plugin') or 'COMPILATION ERROR' in text:
        result['category'] = 'compile'
    elif 'There are test failures.' in text:
        result['category'] = 'test'
    return result


def fixed_photo_prepare_failure(suite, source):
    # Diagnostic only: a known failed case, one bounded original observation and fixed values.
    if (not re.fullmatch(r'[1-9][0-9]*', os.environ.get('GITHUB_RUN_ID', ''))
            or not re.fullmatch(r'[1-9][0-9]*', os.environ.get('GITHUB_RUN_ATTEMPT', ''))
            or not re.fullmatch(r'[0-9a-f]{40}', source or '')
            or suite.tag != 'testsuite' or suite.get('name') != PACKAGE + COMBINATION_SUITE):
        return None
    cases = suite.findall('testcase')
    if (len(cases) != 1 or cases[0].get('classname') != PACKAGE + COMBINATION_SUITE
            or cases[0].get('name') != 'realCommandsRemainIsolatedAcrossHeldProvidersAndOriginalEconomicRecovery'
            or not (cases[0].findall('failure') + cases[0].findall('error'))):
        return None
    lines = [line[len('CONTROLLED_COMBINATION_OBSERVATIONS '):]
             for output in suite.iter('system-out') for line in (output.text or '').splitlines()
             if line.startswith('CONTROLLED_COMBINATION_OBSERVATIONS ')]
    if len(lines) != 1 or len(lines[0].encode()) > 128 * 1024:
        return None
    try:
        report = json.loads(lines[0])
        if (not isinstance(report, dict) or report.get('sourceSha') != source
                or report.get('runId') != os.environ.get('GITHUB_RUN_ID')
                or report.get('runAttempt') != os.environ.get('GITHUB_RUN_ATTEMPT')
                or report.get('format') != 'CONTROLLED_COMBINATION_V1'
                or report.get('result') != 'FAIL_OR_UNKNOWN'
                or report.get('wholeSystemCapacityAccepted') is not False or report.get('q4q8Complete') is not False):
            return None
        value = report.get('photoPreparationFailure')
        types = {'java.lang.IllegalStateException', 'java.lang.IllegalArgumentException', 'java.sql.SQLException',
                 'java.sql.SQLTimeoutException', 'java.sql.SQLTransientConnectionException', 'java.sql.SQLNonTransientConnectionException',
                 'java.sql.SQLIntegrityConstraintViolationException', 'java.sql.SQLSyntaxErrorException', 'java.sql.SQLTransactionRollbackException',
                 'org.springframework.transaction.CannotCreateTransactionException', 'org.springframework.jdbc.CannotGetJdbcConnectionException',
                 'org.springframework.dao.DataAccessResourceFailureException', 'org.springframework.dao.TransientDataAccessResourceException',
                 'org.springframework.dao.CannotAcquireLockException', 'org.springframework.dao.QueryTimeoutException',
                 'org.mybatis.spring.MyBatisSystemException', 'com.chengyinhub.common.exception.ServiceException', 'UNKNOWN_TYPE'}
        states = {'08001', '08003', '08006', '08S01', '23000', '40001', '42000', '42S02', 'HY000', 'HYT00', 'HYT01', 'UNKNOWN'}
        codes = {'0', '1040', '1045', '1049', '1054', '1062', '1146', '1205', '1213', '1317', '2003', '2006', '2013', 'UNKNOWN'}
        if (not isinstance(value, dict) or set(value) != {'phase', 'exceptionTypes', 'sql'}
                or value['phase'] not in ('TX_BEGIN', 'PHOTO_PREPARE', 'UNKNOWN')
                or not isinstance(value['exceptionTypes'], list) or not 1 <= len(value['exceptionTypes']) <= 8
                or any(not isinstance(item, str) or item not in types for item in value['exceptionTypes'])
                or value['exceptionTypes'] != sorted(set(value['exceptionTypes']))
                or not isinstance(value['sql'], list) or len(value['sql']) > 32):
            return None
        for row in value['sql']:
            if (not isinstance(row, dict) or set(row) != {'sqlState', 'vendorCode'}
                    or not isinstance(row['sqlState'], str) or row['sqlState'] not in states
                    or not isinstance(row['vendorCode'], str) or row['vendorCode'] not in codes):
                return None
        return value
    except (ValueError, TypeError, KeyError):
        return None


def fixed_startup_exception_types(text):
    # Only public framework/JDK exception names, never cause messages or bean values.
    reviewed = {
        'org.springframework.beans.factory.BeanCreationException',
        'org.springframework.beans.factory.UnsatisfiedDependencyException',
        'org.springframework.beans.factory.NoSuchBeanDefinitionException',
        'org.springframework.beans.factory.NoUniqueBeanDefinitionException',
        'java.lang.IllegalStateException', 'java.lang.IllegalArgumentException',
        'java.lang.NumberFormatException', 'java.lang.SecurityException', 'java.lang.UnsupportedOperationException',
        'java.lang.ExceptionInInitializerError', 'java.lang.OutOfMemoryError', 'java.sql.SQLException',
        'org.springframework.context.ApplicationContextException',
        'org.springframework.jdbc.CannotGetJdbcConnectionException', 'org.springframework.jdbc.BadSqlGrammarException',
        'org.springframework.dao.DataAccessResourceFailureException', 'org.springframework.dao.QueryTimeoutException',
        'org.springframework.dao.CannotAcquireLockException', 'org.springframework.dao.DuplicateKeyException',
        'org.springframework.dao.DataIntegrityViolationException', 'org.springframework.dao.TransientDataAccessResourceException',
        'org.springframework.dao.PermissionDeniedDataAccessException', 'org.springframework.jdbc.UncategorizedSQLException',
        'com.fasterxml.jackson.databind.JsonMappingException', 'com.fasterxml.jackson.databind.exc.InvalidDefinitionException',
        'java.lang.ClassNotFoundException', 'java.lang.NoClassDefFoundError', 'java.lang.NoSuchMethodError',
        'java.net.BindException', 'java.sql.SQLSyntaxErrorException',
        'java.sql.SQLInvalidAuthorizationSpecException', 'java.sql.SQLNonTransientConnectionException',
    }
    names = re.findall(r'(?m)^[ \t]*(?:Caused by: |Suppressed: )?([A-Za-z][A-Za-z0-9_.$]{0,199})(?=:|$)',
                       text[-65536:])
    return sorted(set(names) & reviewed)


def fixed_startup_source_frames(text):
    # Exact C-reviewed source signatures are location hints, never a claim about the deepest cause.
    reviewed = {
        ("com.chengyinhub.business.it.GenericGameplayHttpE2eIT$LocalInitializer", "initialize", "GenericGameplayHttpE2eIT.java"): ("GENERIC_INITIALIZER", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/GenericGameplayHttpE2eIT.java", 1243),
        ("com.chengyinhub.business.it.GenericGameplayHttpE2eIT", "assertFixtureMapperColumns", "GenericGameplayHttpE2eIT.java"): ("SCHEMA_COLUMN_PREFLIGHT", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/GenericGameplayHttpE2eIT.java", 1243),
        ("com.chengyinhub.business.it.GenericGameplayHttpE2eIT", "registerContentSecurityFixture", "GenericGameplayHttpE2eIT.java"): ("DYNAMIC_CONTENT_STUB", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/GenericGameplayHttpE2eIT.java", 1243),
        ("com.chengyinhub.business.it.GenericGameplayHttpE2eIT$ReviewedMediaFixture", "configure", "GenericGameplayHttpE2eIT.java"): ("MEDIA_FIXTURE_SETTINGS", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/GenericGameplayHttpE2eIT.java", 1243),
        ("com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT$Initializer", "configureBoundedPool", "CrossDomainCombinedLoadMySqlIT.java"): ("BOUNDED_POOL_PROPERTIES", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainCombinedLoadMySqlIT.java", 411),
        ("com.chengyinhub.framework.config.DruidConfig", "masterDataSource", "DruidConfig.java"): ("MASTER_DATASOURCE_FACTORY", "chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/DruidConfig.java", 126),
        ("com.chengyinhub.framework.config.properties.DruidProperties", "dataSource", "DruidProperties.java"): ("DRUID_PROPERTY_ASSEMBLY", "chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/properties/DruidProperties.java", 89),
        ("com.alibaba.druid.pool.DruidDataSource", "init", "DruidDataSource.java"): ("DRUID_POOL_INIT", None, 3666),
        ("com.chengyinhub.framework.config.MyBatisConfig", "sqlSessionFactory", "MyBatisConfig.java"): ("MYBATIS_FACTORY", "chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/MyBatisConfig.java", 132),
        ("com.chengyinhub.framework.config.RedisConfig", "redisTemplate", "RedisConfig.java"): ("REDIS_TEMPLATE_FACTORY", "chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/RedisConfig.java", 69),
        ("com.chengyinhub.business.service.ai.LlmClientConfig", "aiHttpAdmission", "LlmClientConfig.java"): ("AI_ADMISSION_FACTORY", "chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/LlmClientConfig.java", 88),
        ("com.chengyinhub.business.service.ai.LlmClientConfig", "visionLlmClient", "LlmClientConfig.java"): ("VISION_CLIENT_FACTORY", "chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/LlmClientConfig.java", 88),
        ("com.chengyinhub.business.service.ai.PhotoCheckIntentService", "<init>", "PhotoCheckIntentService.java"): ("PHOTO_INTENT_CONSTRUCTOR", "chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/PhotoCheckIntentService.java", 234),
        ("com.chengyinhub.business.it.GameplayProcessRecoveryChild", "seedCombination", "GameplayProcessRecoveryChild.java"): ("COMBINATION_IDENTITY_SEED", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/GameplayProcessRecoveryChild.java", 237),
        ("com.chengyinhub.business.it.GameplayProcessRecoveryChild", "seedRows", "GameplayProcessRecoveryChild.java"): ("ORIGINAL_ACTOR_AND_GAME_ROWS", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/GameplayProcessRecoveryChild.java", 237),
        ("com.chengyinhub.business.it.GameplayProcessRecoveryChild", "require", "GameplayProcessRecoveryChild.java"): ("ORIGINAL_FIXTURE_REQUIRE", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/GameplayProcessRecoveryChild.java", 237),
        ("com.chengyinhub.business.service.ai.PhotoCheckIntentService", "prepare", "PhotoCheckIntentService.java"): ("PHOTO_INTENT_PREPARE", "chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/PhotoCheckIntentService.java", 430),
        ("com.chengyinhub.business.service.ai.PhotoCheckIntentService", "executeOnce", "PhotoCheckIntentService.java"): ("PHOTO_INTENT_EXECUTE_ONCE", "chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/PhotoCheckIntentService.java", 430),
        ("com.chengyinhub.business.it.CrossDomainStorageFixture", "command", "CrossDomainStorageFixture.java"): ("ORIGINAL_PHOTO_COMMAND", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainStorageFixture.java", 577),
        ("com.chengyinhub.business.it.CrossDomainStorageFixture", "photo", "CrossDomainStorageFixture.java"): ("ORIGINAL_PHOTO_SERVICE_ASSEMBLY", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainStorageFixture.java", 577),
        ("com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT", "digest", "CrossDomainCombinedLoadMySqlIT.java"): ("SQL_FACT_SNAPSHOT", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainCombinedLoadMySqlIT.java", 411),
        ("com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT", "actorDigest", "CrossDomainCombinedLoadMySqlIT.java"): ("ACTOR_ORIGINAL_FACT_SNAPSHOT", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainCombinedLoadMySqlIT.java", 411),
        ("com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT", "economicDigest", "CrossDomainCombinedLoadMySqlIT.java"): ("ECONOMIC_ORIGINAL_FACT_SNAPSHOT", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainCombinedLoadMySqlIT.java", 411),
        ("com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT", "unknownDigest", "CrossDomainCombinedLoadMySqlIT.java"): ("UNKNOWN_ORIGINAL_FACT_SNAPSHOT", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainCombinedLoadMySqlIT.java", 411),
        ("com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT", "retainedDigest", "CrossDomainCombinedLoadMySqlIT.java"): ("RETAINED_ORIGINAL_FACT_SNAPSHOT", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainCombinedLoadMySqlIT.java", 411),
        ("com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT", "realCommandsRemainIsolatedAcrossHeldProvidersAndOriginalEconomicRecovery", "CrossDomainCombinedLoadMySqlIT.java"): ("CASE_BODY", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainCombinedLoadMySqlIT.java", 411),
        ("com.chengyinhub.business.it.CrossDomainStorageFixture", "open", "CrossDomainStorageFixture.java"): ("INDEPENDENT_ECONOMIC_ASSEMBLY", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CrossDomainStorageFixture.java", 577),
        ("com.chengyinhub.business.it.CombinedControlledProviders", "<init>", "CombinedControlledProviders.java"): ("CONTROLLED_PROVIDER_CASE_ASSEMBLY", "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/CombinedControlledProviders.java", 120),
    }
    result = []; bounds = {}
    for owner, method, filename, line in re.findall(
            r'(?m)^[ \t]*at ([A-Za-z_][A-Za-z0-9_.$]*)\.([A-Za-z_$<>][A-Za-z0-9_$<>]*)\(([A-Za-z_$][A-Za-z0-9_$]*\.java):([1-9][0-9]{0,5})\)',
            text[-65536:]):
        key = (owner, method, filename)
        if key not in reviewed:
            continue
        phase, source, maximum = reviewed[key]
        if source:
            try:
                path = ROOT / source
                if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(ROOT.resolve()):
                    continue
                if source not in bounds:
                    bounds[source] = len(path.read_text().splitlines())
                maximum = bounds[source]
            except OSError:
                continue
        if int(line) > maximum:
            continue
        frame = {'phase': phase, 'sourceFrame': filename + ':' + line}
        if frame not in result:
            result.append(frame)
        if len(result) == 8:
            break
    return result


def safe_execution_failure(path):
    """Project the original budget owner's fixed cause only for this exact execution."""
    try:
        expected = {'sourceSha': os.environ.get('SOURCE_SHA', ''),
                    'runId': os.environ.get('GITHUB_RUN_ID', ''),
                    'attempt': os.environ.get('GITHUB_RUN_ATTEMPT', ''), 'class': COMBINATION_SUITE}
        if (os.environ.get('CHENGYIN_HTTP_E2E_TESTS') != COMBINATION_SUITE or
                not re.fullmatch('[0-9a-f]{40}', expected['sourceSha']) or
                any(not re.fullmatch('[1-9][0-9]*', expected[key]) for key in ('runId', 'attempt')) or
                path.is_symlink() or not path.is_file() or path.stat().st_size > 2 * 1024 * 1024):
            return {}
        report = json.loads(path.read_text())
        if not isinstance(report, dict) or any(report.get(key) != value for key, value in expected.items()):
            return {}
        failure = report.get('firstFailure')
        if (not isinstance(failure, dict) or
                failure.get('phase') not in ('PREPARE', 'PREPARED', 'APP_CREATE', 'APP_EXECUTION',
                                              'MAVEN_EXITED_AWAIT_CLEANUP', 'NAMESPACE_PORT_PROOF', 'UNKNOWN') or
                failure.get('type') not in ('ValueError', 'OSError', 'FileNotFoundError', 'PermissionError',
                                            'TimeoutExpired', 'JSONDecodeError', 'KeyError', 'TypeError',
                                            'UnicodeDecodeError', 'BadZipFile', 'StopIteration', 'UNKNOWN')):
            return {}
        spec = importlib.util.spec_from_file_location('http_budget_failure_projection', ROOT / 'ci/combined_http_budget.py')
        owner = importlib.util.module_from_spec(spec); spec.loader.exec_module(owner)
        code = failure.get('reasonCode')
        code = code if isinstance(code, str) and code in owner.SAFE_RESOURCE_CODES else 'UNCLASSIFIED_RESOURCE_FAILURE'
        return {'phase': failure['phase'], 'type': failure['type'], 'reasonCode': code}
    except (OSError, ValueError, TypeError):
        return {}


def cli():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    v = sub.add_parser('verify'); v.add_argument('--classes', required=True)
    v.add_argument('--reports', type=pathlib.Path, required=True); v.add_argument('--output', type=pathlib.Path, required=True)
    f = sub.add_parser('finalize'); f.add_argument('--output', type=pathlib.Path, required=True)
    f.add_argument('--java-exit', type=int, required=True); f.add_argument('--mysql-clean', choices=('true', 'false'), required=True)
    f.add_argument('--redis-clean', choices=('true', 'false'), required=True)
    s = sub.add_parser('summarize'); s.add_argument('--receipts', type=pathlib.Path, required=True)
    s.add_argument('--source', required=True); s.add_argument('--output', type=pathlib.Path, required=True)
    s.add_argument('--scope', choices=('all-three', 'generic-only', 'controlled-combination'), default='all-three')
    a = p.parse_args()
    if a.command == 'summarize':
        paths = list(a.receipts.rglob('receipt.json'))
        try:
            result = scoped_summary(paths, a.source, a.scope)
        except (ValueError, RuntimeError):
            if a.scope == 'controlled-combination':
                if paths and (len(paths) != 1 or receipt_identity(json.loads(paths[0].read_text()), a.source)['classes'] != [COMBINATION_SUITE]):
                    raise ValueError('Controlled combination excludes foreign/duplicate receipts') from None
                result = failure_summary(paths, a.source)
                result.update(scope='PARTIAL_CONTROLLED_COMBINATION_SCOPE', completeHttpSuiteAccepted=False,
                              wholeSystemCapacityAccepted=False, q4q8Complete=False,
                              missingClasses=[] if paths else [COMBINATION_SUITE])
                if not paths:
                    result.update(executionEvidence='NO_VERIFIED_HTTP_RECEIPT', cleanupAllVerified=False)
                a.output.parent.mkdir(parents=True, exist_ok=True)
                a.output.write_text(json.dumps(result, indent=2) + '\n')
                raise ValueError('Controlled combination did not pass its scoped verifier') from None
            if a.scope == 'generic-only':
                generic_diagnostic_receipt(paths, a.source)
            result = failure_summary(paths, a.source)
            if a.scope == 'generic-only':
                result.update(scope='PARTIAL_DIAGNOSTIC_SCOPE', completeHttpSuiteAccepted=False)
            a.output.parent.mkdir(parents=True, exist_ok=True)
            a.output.write_text(json.dumps(result, indent=2) + '\n')
            print('HTTP_FIXTURE_SUMMARY_FAILED ' + json.dumps(result, sort_keys=True), file=sys.stderr)
            raise ValueError('HTTP fixtures did not pass; see safe failure summary') from None
        a.output.parent.mkdir(parents=True, exist_ok=True); a.output.write_text(json.dumps(result, indent=2) + '\n'); return
    a.output.mkdir(parents=True, exist_ok=True)
    dest = a.output / 'receipt.json'
    if a.command == 'verify':
        identity = execution_identity(a.classes)
        names = identity['classes']
        if names == [COMBINATION_SUITE]:
            # Only this selected scope's derived file; never publish an earlier run's observation.
            (a.output / 'controlled-combination.json').unlink(missing_ok=True)
        receipt = {**identity,
                   'state': 'PENDING_CLEANUP', 'xmlVerified': False, 'publicXmlDerivedSanitized': True, 'originalXml': {}, 'migrationAccepted': False,
                   'businessAccepted': False}
        try:
            fixture_path=pathlib.Path(os.environ['CHENGYIN_HTTP_E2E_SCHEMA_REPORT'])
            fixture=json.loads(fixture_path.read_text())
            if (fixture.get('mode') != 'SUCCEEDED' or fixture.get('database') != receipt['database'] or
                    len(fixture.get('files', [])) <= 100 or
                    [f['path'] for f in fixture['files']] != fixture.get('completed')):
                raise ValueError('Missing genuine completed fresh-schema fixture')
            receipt.update(fixtureReportVerified=True, fixtureFiles=len(fixture['files']),
                           fixtureReportSha256=hashlib.sha256(fixture_path.read_bytes()).hexdigest())
            for n in names:
                original = a.reports / ('TEST-' + PACKAGE + n + '.xml')
                try:
                    safe = verified_xml(original, n)
                except (OSError, ValueError, ET.ParseError):
                    # Exact expected suite only; never expose failure text, properties or streams.
                    failure = {'class': n, 'state': 'MISSING'}
                    if original.exists():
                        failure['state'] = 'INVALID'
                        try:
                            failed = ET.parse(original).getroot()
                            if failed.tag == 'testsuite' and failed.get('name') == PACKAGE + n:
                                failure['state'] = 'FAILED_VERIFICATION'
                                photo_failure = fixed_photo_prepare_failure(failed, receipt['sourceSha'])
                                if photo_failure is not None:
                                    failure['photoPreparationFailure'] = photo_failure
                                for key in ('tests', 'failures', 'errors', 'skipped'):
                                    value = failed.get(key, '')
                                    if re.fullmatch(r'[0-9]{1,6}', value):
                                        failure[key] = int(value)
                                source_dir = ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it'
                                source = (source_dir / (n + '.java')).read_text()
                                known_cases = reviewed_case_identities(n)
                                source_lines = {name + '.java': len((source_dir / (name + '.java')).read_text().splitlines())
                                                for name in (*EXPECTED, COMBINATION_SUITE, 'CombinedCoreHttpWorkload', 'CombinedControlledProviders')}
                                cases = failed.findall('testcase')
                                failure.update(testcaseCount=len(cases), uniqueMethodCount=len({case.get('name') for case in cases}),
                                               failureNodeCount=sum(len(case.findall('failure')) for case in cases),
                                               errorNodeCount=sum(len(case.findall('error')) for case in cases),
                                               retryNodeCount=sum(len(case.findall(tag)) for case in cases
                                                                  for tag in ('rerunFailure', 'rerunError', 'flakyFailure', 'flakyError')))
                                methods, codes, case_categories, case_frames, case_calls, case_contexts = [], set(), {}, {}, {}, {}
                                case_exception_types = {}; case_startup_frames = {}
                                for case in cases:
                                    errors = case.findall('failure') + case.findall('error')
                                    method = case.get('name', '')
                                    if (errors and case.get('classname') == PACKAGE + n and
                                            method in known_cases):
                                        methods.append(known_cases[method])
                                        categories = case_categories.setdefault(known_cases[method], set())
                                        context = suppressed_source_context('\n'.join(error.text or '' for error in errors))
                                        if context is not None:
                                            previous = case_contexts.get(known_cases[method], context)
                                            case_contexts[known_cases[method]] = context if previous == context else 'UNKNOWN'
                                        for error in errors:
                                            types = fixed_startup_exception_types(error.get('type', '') + '\n' + (error.text or ''))
                                            if types:
                                                case_exception_types.setdefault(known_cases[method], set()).update(types)
                                            for frame in fixed_startup_source_frames(error.text or ''):
                                                frames = case_startup_frames.setdefault(known_cases[method], [])
                                                if frame not in frames and len(frames) < 8:
                                                    frames.append(frame)
                                            text = error.get('message', '') + '\n' + (error.text or '')
                                            categories.update(fixed_http_failure_categories(text))
                                            codes.update(int(code) for code in re.findall(r'"code"\s*:\s*([1-5][0-9]{2}|601)(?=\s*[,}])', text))
                                        call_frame = reviewed_case_call_frame(n, known_cases[method],
                                                                            '\n'.join(error.text or '' for error in errors), source)
                                        if call_frame:
                                            case_calls[known_cases[method]] = call_frame
                                        for error in errors:
                                            frames = []
                                            for filename, line in re.findall(r'\bat com\.chengyinhub\.[A-Za-z0-9_.$]+\(([A-Za-z_$][A-Za-z0-9_$]*\.java):([0-9]{1,7})\)', error.text or ''):
                                                if filename in source_lines and 1 <= int(line) <= source_lines[filename]:
                                                    frame = filename + ':' + str(int(line))
                                                    if frame not in frames:
                                                        frames.append(frame)
                                                    if len(frames) == 8:
                                                        break
                                            if call_frame and call_frame not in frames:
                                                frames.append(call_frame)
                                            if frames:
                                                case_frames.setdefault(known_cases[method], frames)
                                                if 'sourceFrame' not in failure:
                                                    failure['sourceFrame'] = frames[0]
                                                    if len(frames) >= 2:
                                                        failure['callerFrame'] = frames[1]
                                                break
                                if case_exception_types:
                                    failure['caseStartupExceptionTypes'] = {method: sorted(types)
                                                                          for method, types in sorted(case_exception_types.items())}
                                if case_startup_frames:
                                    failure['caseStartupSourceFrames'] = dict(sorted(case_startup_frames.items()))
                                failure['caseSourceFrames'] = dict(sorted(case_frames.items()))
                                if case_calls:
                                    failure['caseCallFrames'] = dict(sorted(case_calls.items()))
                                if case_contexts:
                                    failure['caseSourceContexts'] = dict(sorted(case_contexts.items()))
                                    values = list(case_contexts.values())
                                    failure['sourceContext'] = (values[0] if len(values) == len(case_categories)
                                                                and all(value == values[0] for value in values) else 'UNKNOWN')
                                failure['failedMethods'] = list(dict.fromkeys(methods))[:reviewed_count(n)]
                                failure['responseCodes'] = sorted(codes)
                                failure['caseMessageCategories'] = {case: sorted(categories) for case, categories in sorted(case_categories.items())}
                                failure['messageCategories'] = sorted(set().union(*case_categories.values()) or {'UNKNOWN'})
                        except (OSError, ET.ParseError):
                            pass
                    (a.output / 'xml-failure.json').write_text(json.dumps(failure, sort_keys=True) + '\n')
                    print('HTTP_FIXTURE_XML_DIAGNOSTIC ' + json.dumps(failure, sort_keys=True), file=sys.stderr)
                    raise ValueError('HTTP test XML verification failed; see safe diagnostic') from None
                raw = original.read_bytes()
                receipt['originalXml'][n] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                ET.ElementTree(safe).write(a.output / ('TEST-' + PACKAGE + n + '.xml'), encoding='utf-8', xml_declaration=True)
            receipt.update(tests=sum(reviewed_count(n) for n in names), xmlVerified=True)
        finally:
            dest.write_text(json.dumps(receipt, indent=2) + '\n')
    else:
        metadata_error = False
        original = os.environ.get('CHENGYIN_HTTP_E2E_ORIGINAL_EXIT', '')
        original_exit = int(original) if re.fullmatch(r'[0-9]{1,3}', original) and int(original) <= 255 else None
        try:
            identity = execution_identity()
            receipt = json.loads(dest.read_text()) if dest.exists() else {**identity, 'state': 'FAIL', 'xmlVerified': False}
            if receipt_identity(receipt) != identity:
                raise ValueError('HTTP execution identity changed before finalization')
            report_path = pathlib.Path(os.environ.get('CHENGYIN_HTTP_E2E_SCHEMA_REPORT', ''))
            if report_path.is_file():
                report = json.loads(report_path.read_text())
                if not isinstance(report, dict) or report.get('database') != identity['database']:
                    raise ValueError('HTTP schema report belongs to another execution')
        except (OSError, ValueError, TypeError, AttributeError):
            metadata_error = True
            receipt = {'state': 'FAIL', 'xmlVerified': False}
        receipt.update(javaExit=a.java_exit, cleanup={'mysqlAbsent': a.mysql_clean == 'true',
                                                   'redisStoppedAndPortFree': a.redis_clean == 'true'})
        receipt['state'] = 'PASS' if (original_exit == 0 and a.java_exit == 0 and receipt.get('xmlVerified') is True and
                                     all(receipt['cleanup'].values())) else 'FAIL'
        if receipt['state'] == 'PASS':
            try:
                validate_final(receipt)
            except ValueError:
                receipt['state'] = 'FAIL'
        if receipt['state'] == 'PASS' and receipt.get('classes') == [COMBINATION_SUITE]:
            try:
                spec = importlib.util.spec_from_file_location('http_combination_capacity', ROOT / 'ci/capacity_mixed_probe.py')
                capacity = importlib.util.module_from_spec(spec); spec.loader.exec_module(capacity)
                jar = ROOT / 'chengyinhub-admin/target/chengyinhub-admin.jar'
                receipt['jarSha256'] = capacity.file_digest(jar)['sha256']
                original = ROOT / 'chengyinhub-admin/target/surefire-reports' / ('TEST-' + PACKAGE + COMBINATION_SUITE + '.xml')
                if original.stat().st_size > 32 * 1024 * 1024 or capacity.file_digest(original)['sha256'] != receipt['originalXml'][COMBINATION_SUITE]['sha256']:
                    raise ValueError('Original controlled XML changed or exceeds fixed bound')
                lines = [line[len('CONTROLLED_COMBINATION_OBSERVATIONS '):]
                         for output in ET.parse(original).getroot().iter('system-out')
                         for line in (output.text or '').splitlines()
                         if line.startswith('CONTROLLED_COMBINATION_OBSERVATIONS ')]
                if len(lines) != 1 or len(lines[0].encode()) > 128 * 1024:
                    raise ValueError('Missing unique bounded controlled observation')
                safe = capacity.controlled_combination_projection(json.loads(lines[0]), receipt, receipt['sourceSha'],
                        os.environ.get('GITHUB_RUN_ID'), os.environ.get('GITHUB_RUN_ATTEMPT'), receipt['jarSha256'])
                verify_combination_budget(a.output, safe, receipt, after_storage_cleanup=False)
                (a.output / 'controlled-combination.json').write_text(json.dumps(safe, sort_keys=True) + '\n')
                receipt['combinationEvidence'] = 'VERIFIED_SCOPED_OBSERVATION_ONLY'
            except (OSError, ValueError, RuntimeError, TypeError, KeyError, ET.ParseError):
                receipt['state'] = 'FAIL'; receipt['combinationEvidence'] = 'MISSING_OR_INVALID'
        dest.write_text(json.dumps(receipt, indent=2) + '\n')
        if receipt['state'] != 'PASS':
            diagnostic = safe_diagnostic(pathlib.Path(os.environ.get('CHENGYIN_HTTP_E2E_SCHEMA_REPORT', '')),
                                         os.environ.get('CHENGYIN_HTTP_E2E_STAGE', ''),
                                         original_exit, a.java_exit)
            if diagnostic['stage'] == 'xml-verify' and a.java_exit != 0:
                diagnostic['mavenFailure'] = safe_maven_failure(a.output.parent / 'run.log')
                execution_failure = safe_execution_failure(a.output / 'combined-http-budget.json')
                if execution_failure:
                    diagnostic['executionFailure'] = execution_failure
            (a.output / 'diagnostic.json').write_text(json.dumps(diagnostic, sort_keys=True) + '\n')
            receipt.update(stage=diagnostic['stage'], originalExit=diagnostic['exitCode'], diagnostic=diagnostic)
            dest.write_text(json.dumps(receipt, indent=2) + '\n')
            print('HTTP_FIXTURE_DIAGNOSTIC ' + json.dumps(diagnostic, sort_keys=True), file=sys.stderr)
            if metadata_error:
                raise ValueError('Invalid HTTP execution metadata; see safe diagnostic') from None
            raise ValueError('HTTP fixture failed; see safe diagnostic') from None
        validate_final(receipt)


class HttpReceiptTest(unittest.TestCase):
    def sample(self, simple='FreezeReuseThemeHttpE2eIT'):
        root = ET.Element('testsuite', name=PACKAGE + simple, tests=str(reviewed_count(simple)), failures='0', errors='0', skipped='0')
        for name in sorted(set(reviewed_case_identities(simple).values())):
            ET.SubElement(root, 'testcase', classname=PACKAGE + simple, name=name)
        return root

    def verify(self, root, simple='FreezeReuseThemeHttpE2eIT'):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / 'test.xml'; ET.ElementTree(root).write(p)
            return verified_xml(p, simple)

    def test_reviewed_ordinary_and_value_source_expansion_is_26_8_2(self):
        self.assertEqual({'GenericGameplayHttpE2eIT': 26, 'FreezeGameHttpE2eIT': 8,
                          'FreezeReuseThemeHttpE2eIT': 2}, EXPECTED)
        self.assertEqual(36, sum(EXPECTED.values()))
        for simple, count in EXPECTED.items():
            aliases = reviewed_case_identities(simple)
            self.assertEqual(count, len(set(aliases.values())))
            self.verify(self.sample(simple), simple)
        simple = 'GenericGameplayHttpE2eIT'
        path = ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it' / (simple + '.java')
        source = path.read_text()
        baseline = reviewed_case_identities(simple)
        self.assertEqual(11, sum('(' in n for n in set(baseline.values())))
        mutants = [source.replace('booleans={false,true}', 'booleans={true,false}'),
                   source.replace('strings={"easy","medium","hard"}', 'strings={"easy","medium","other"}'),
                   source.replace('strings={"easy","medium","hard"}', 'strings={"easy","medium"}'),
                   source.replace('String tier', 'Object tier'),
                   source.replace('r5HttpTypeInUnlimitedAndTimerDeadlineSurviveReentry', 'unknownParameterMethod'),
                   source + '\n@Test void unreviewedExtraHttpTest() {}']
        for changed in mutants:
            self.assertNotEqual(source, changed)
            with mock.patch.object(pathlib.Path, 'read_text', return_value=changed):
                with self.assertRaises(ValueError):
                    reviewed_case_identities(simple)
        with mock.patch.object(pathlib.Path, 'read_text', return_value=source + '\nprivate void fixtureHelperOnly() {}'):
            self.assertEqual(baseline, reviewed_case_identities(simple))

    def test_parameter_case_type_index_value_unknown_missing_duplicate_and_skip_are_red(self):
        simple = 'GenericGameplayHttpE2eIT'
        method = 'r5HttpTypeInUnlimitedAndTimerDeadlineSurviveReentry'
        canonical = method + '(boolean)[1]'
        for name in [method + '(String)[1]', method + '(boolean)[0]', method + '(boolean)[3]',
                     canonical + ' false', 'unknownMethod(boolean)[1]', method + '(false)[1]']:
            root = self.sample(simple)
            next(c for c in root.findall('testcase') if c.get('name') == canonical).set('name', name)
            with self.assertRaises(ValueError): self.verify(root, simple)
        root = self.sample(simple); root.remove(root.find('testcase'))
        with self.assertRaises(ValueError): self.verify(root, simple)
        root = self.sample(simple); ET.SubElement(next(c for c in root.findall('testcase') if c.get('name') == canonical), 'skipped')
        with self.assertRaises(ValueError): self.verify(root, simple)
        root = self.sample(simple)
        for case in root.findall('testcase'):
            case.set('name', case.get('name').replace('(String)', '(java.lang.String)'))
        self.verify(root, simple)
        string_method = 'r5HttpCheckRollRerollSubmitHasAuthoritativeActions'
        next(c for c in root.findall('testcase') if c.get('name') == string_method + '(java.lang.String)[2]').set('name', string_method + '(String)[1]')
        with self.assertRaises(ValueError): self.verify(root, simple)

    def test_exact_reviewed_classes_only(self):
        self.assertEqual(list(EXPECTED), selection(','.join(EXPECTED)))
        for bad in ['', 'OtherTest', '../FreezeReuseThemeHttpE2eIT', 'FreezeGameHttpE2eIT,FreezeGameHttpE2eIT', 'FreezeGameHttpE2eIT#skip']:
            with self.assertRaises(ValueError): selection(bad)

    def test_real_counts_and_no_skips_or_hidden_case_failures(self):
        for key in ['tests', 'failures', 'errors', 'skipped']:
            root = self.sample(); root.set(key, '1')
            with self.assertRaises(ValueError): self.verify(root)
        for tag in ['skipped', 'failure', 'error', 'rerunFailure', 'rerunError', 'flakyFailure', 'flakyError']:
            root = self.sample(); ET.SubElement(root.find('testcase'), tag)
            with self.assertRaises(ValueError): self.verify(root)
        for changed in ['remove', 'duplicate', 'wrongclass']:
            root = self.sample()
            if changed == 'remove': root.remove(root.find('testcase'))
            elif changed == 'duplicate': root.findall('testcase')[1].set('name', root.find('testcase').get('name'))
            else: root.find('testcase').set('classname', 'OtherTest')
            with self.assertRaises(ValueError): self.verify(root)
        with self.assertRaises(FileNotFoundError): verified_xml(pathlib.Path('/nonexistent/http-test.xml'), 'FreezeReuseThemeHttpE2eIT')

    def test_published_xml_has_no_secret_properties_or_streams(self):
        root = self.sample(); props = ET.SubElement(root, 'properties')
        ET.SubElement(props, 'property', name='password', value='SYNTHETIC_NEVER_PUBLISH')
        ET.SubElement(root, 'system-out').text = 'SYNTHETIC_NEVER_PUBLISH'
        safe = ET.tostring(self.verify(root)).decode()
        self.assertNotIn('SYNTHETIC_NEVER_PUBLISH', safe); self.assertNotIn('properties', safe)

    def test_shell_exit_zero_does_not_replace_xml_or_cleanup(self):
        base = {'profile':'github-hosted','database':'cy_e2e_w1_http_123_456','fixtureReportVerified':True,'sourceSha': 'a'*40, 'classes': ['FreezeReuseThemeHttpE2eIT'], 'state': 'PASS', 'javaExit': 0,
                'tests': 2, 'xmlVerified': True, 'cleanup': {'mysqlAbsent': True, 'redisStoppedAndPortFree': True}}
        self.assertEqual(['FreezeReuseThemeHttpE2eIT'], validate_final(base))
        for key in ['xmlVerified', 'javaExit', 'tests', 'cleanup', 'sourceSha', 'profile', 'database', 'fixtureReportVerified']:
            bad = dict(base); bad.pop(key)
            with self.assertRaises(ValueError): validate_final(bad)
        for key in base['cleanup']:
            bad = dict(base, cleanup=dict(base['cleanup'], **{key: False}))
            with self.assertRaises(ValueError): validate_final(bad)

    def test_summary_requires_all_three_once_same_source(self):
        with tempfile.TemporaryDirectory() as d:
            paths=[]
            for n, count in EXPECTED.items():
                p=pathlib.Path(d)/(n+'.json'); p.write_text(json.dumps({'profile':'github-hosted','database':'cy_e2e_w1_http_123_456','fixtureReportVerified':True,'sourceSha':'a'*40,'classes':[n],'state':'PASS','javaExit':0,'tests':count,'xmlVerified':True,'cleanup':{'mysqlAbsent':True,'redisStoppedAndPortFree':True}}));paths.append(p)
            self.assertEqual(36,summarize(paths,'a'*40)['tests'])
            for bad in [paths[:-1],paths+[paths[0]]]:
                with self.assertRaises(ValueError):summarize(bad,'a'*40)
            with self.assertRaises(ValueError):summarize(paths,'b'*40)


    def test_existing_baseline_quartz_tables_and_foreign_keys_are_not_replayed(self):
        spec = importlib.util.spec_from_file_location('quartz_schema', TOOLS / 'generic-gameplay-fresh-schema.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        plan = module.build_plan()
        self.assertNotIn('sql/quartz.sql', [f['path'] for f in plan['files']])
        baseline = next(f for f in plan['files'] if f['path'] == 'sql/base_schema_20260818.sql')
        tables = {'qrtz_blob_triggers', 'qrtz_calendars', 'qrtz_cron_triggers', 'qrtz_fired_triggers',
                  'qrtz_job_details', 'qrtz_locks', 'qrtz_paused_trigger_grps', 'qrtz_scheduler_state',
                  'qrtz_simple_triggers', 'qrtz_simprop_triggers', 'qrtz_triggers'}
        def definitions(files):
            rows = []
            for file in files:
                for sql in file['apply']:
                    match = re.match(r'(?is)^CREATE TABLE [`]?([A-Za-z0-9_]+)[`]?(?:\s|\()', sql)
                    if match and match.group(1).lower() in tables:
                        rows.append((match.group(1).lower(), sql))
            return rows
        original = dict(definitions([baseline]))
        self.assertEqual(tables, set(original))
        replayed = definitions(plan['files'])
        self.assertEqual(11, len(replayed))
        self.assertEqual(tables, {name for name, _ in replayed})
        for child, parent in [('qrtz_blob_triggers', 'qrtz_triggers'), ('qrtz_cron_triggers', 'qrtz_triggers'),
                              ('qrtz_simple_triggers', 'qrtz_triggers'), ('qrtz_simprop_triggers', 'qrtz_triggers'),
                              ('qrtz_triggers', 'qrtz_job_details')]:
            self.assertIn('FOREIGN KEY', original[child])
            self.assertRegex(original[child], r'(?is)REFERENCES [`]?' + parent + r'[`]?\s*\(')

    def test_owned_cleanup_is_required_and_old_fixture_sql_contract_unchanged(self):
        shell=(TOOLS/'generic-gameplay-http-e2e.sh').read_text()
        self.assertIn("r.get('database')==os.environ['CHENGYIN_HTTP_E2E_DATABASE']",shell)
        self.assertIn("r.get('mode') in ('EXECUTING','FAILED','SUCCEEDED')",shell)
        self.assertEqual(2,shell.count("SELECT SCHEMA_NAME FROM information_schema.schemata WHERE SCHEMA_NAME='$CHENGYIN_HTTP_E2E_DATABASE'"))
        self.assertIn('DROP DATABASE \`$CHENGYIN_HTTP_E2E_DATABASE\`',shell)
        self.assertIn('redis-cli -h 127.0.0.1 -p "$CHENGYIN_HTTP_E2E_REDIS_PORT" -n 15',shell)
        self.assertIn('probe.bind',shell)
        self.assertNotIn('DROP DATABASE IF EXISTS',shell)

    def test_workflow_is_manual_three_isolated_standard_jobs_with_pinned_mysql(self):
        workflow=(ROOT/'.github/workflows/gameplay-http-e2e.yml').read_text()
        self.assertIn('  workflow_dispatch:',workflow);self.assertNotIn('  push:',workflow)
        self.assertIn('contents: read',workflow);self.assertNotIn('secrets.',workflow)
        self.assertIn('max-parallel: 20',workflow);self.assertIn('timeout-minutes: 45',workflow)
        self.assertIn('test_class: ${{ fromJSON(inputs.http_scope == \'controlled-combination\' && \'["CrossDomainCombinedLoadMySqlIT"]\' || inputs.http_scope == \'generic-only\' && \'["GenericGameplayHttpE2eIT"]\' || \'["GenericGameplayHttpE2eIT", "FreezeGameHttpE2eIT", "FreezeReuseThemeHttpE2eIT"]\') }}',workflow)
        self.assertIn('runs-on: ubuntu-latest',workflow);self.assertNotIn('CHENGYIN_VPS_TEST_RUN:',workflow)
        self.assertIn('mysql:8.0.45@sha256:4af1f8815716546f5b12410f7621f37f93db8dd11a184706ef59111930b8c2ff',workflow)
        self.assertIn('-p 127.0.0.1:33080:3306',workflow)
        self.assertIn('mvn -B -q -pl chengyinhub-admin -am test -DskipTests',workflow)
        self.assertIn('test "$HTTP_JOBS_RESULT" = success',workflow)
        self.assertIn('path: chengyinhub-admin/target/http-e2e/public/',workflow)
        self.assertNotIn('path: chengyinhub-admin/target/http-e2e\n',workflow)



class ManualRouterTest(unittest.TestCase):
    def test_registered_jar_manual_router_defaults_to_jar_and_calls_same_commit_http_only_on_opt_in(self):
        caller = (ROOT / '.github/workflows/backend-jar.yml').read_text()
        callee = (ROOT / '.github/workflows/gameplay-http-e2e.yml').read_text()

        def contract(parent, child):
            self.assertRegex(parent, r'run_http_fixtures:\n        description:[^\n]+\n        required: false\n        type: boolean\n        default: false')
            self.assertRegex(child, r'  workflow_call:\n    inputs:\n      source_sha:\n        description:[^\n]+\n        required: true\n        type: string')
            jar = parent.split('\n  jar:\n', 1)[1].split('\n  http-fixtures:\n', 1)[0]
            http = parent.split('\n  http-fixtures:\n', 1)[1].split('  restore-fixtures:\n', 1)[0]
            self.assertIn("    if: github.repository == 'liboyang42-cpu/hengyin' && !inputs.run_http_fixtures && !inputs.run_restore_fixtures\n", jar)
            self.assertIn("    if: github.repository == 'liboyang42-cpu/hengyin' && github.event_name == 'workflow_dispatch' && inputs.run_http_fixtures && !inputs.run_restore_fixtures\n", http)
            self.assertIn('    needs: admission\n', jar)
            self.assertIn('    needs: admission\n', http)
            self.assertIn('    uses: ./.github/workflows/gameplay-http-e2e.yml\n', http)
            self.assertIn('    with:\n      source_sha: ${{ inputs.source_sha }}\n', http)
            self.assertNotIn('runs-on:', http); self.assertNotIn('steps:', http)
            self.assertNotIn('mvn ', http)
            self.assertEqual(1, parent.count('run: mvn -B -q -DskipTests package'))
            self.assertIn('  workflow_dispatch:', parent); self.assertIn('  workflow_dispatch:', child)
            for workflow in [parent, child]:
                self.assertIn('contents: read', workflow)
                self.assertNotRegex(workflow, r'(?m)^\s+(?:push|pull_request|schedule|secrets):')
                self.assertNotIn('secrets.', workflow)
            parent_group = re.search(r'(?m)^  group: (.+)$', parent).group(1)
            child_group = re.search(r'(?m)^  group: (.+)$', child).group(1)
            self.assertNotEqual(parent_group, child_group, 'Caller cannot hold the callee concurrency group')

        contract(caller, callee)
        mutations = [
            (caller.replace('default: false', 'default: true'), callee),
            (caller.replace('&& !inputs.run_http_fixtures', ''), callee),
            (caller.replace(" && inputs.run_http_fixtures && !inputs.run_restore_fixtures\n", " && !inputs.run_restore_fixtures\n"), callee),
            (caller.replace('uses: ./.github/workflows/gameplay-http-e2e.yml',
                            'uses: liboyang42-cpu/hengyin/.github/workflows/gameplay-http-e2e.yml@master'), callee),
            (caller.replace('    with:\n      source_sha:', '    secrets: inherit\n    with:\n      source_sha:'), callee),
            (caller.replace('  workflow_dispatch:', '  push:'), callee),
            (caller.replace('  group: backend-jar-', '  group: gameplay-http-'), callee),
            (caller, callee.replace('  workflow_call:', '  unused_call:')),
            (caller, callee.replace('  workflow_call:\n    inputs:\n      source_sha:\n        description: Reviewed full40-character public candidate SHA\n        required: true\n        type: string',
                                   '  workflow_call:\n    inputs:\n      source_sha:\n        description: Reviewed full40-character public candidate SHA\n        required: true\n        type: boolean')),
        ]
        for parent, child in mutations:
            with self.assertRaises(AssertionError):
                contract(parent, child)


class FailureDiagnosticTest(unittest.TestCase):
    def test_photo_prepare_projection_is_fixed_source_bound_and_diagnostic_only(self):
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        expected = {'phase': 'PHOTO_PREPARE', 'exceptionTypes': ['java.sql.SQLException'],
                    'sql': [{'sqlState': '40001', 'vendorCode': '1213'}]}
        original = {'sourceSha': 'a' * 40, 'runId': '17', 'runAttempt': '2',
                    'format': 'CONTROLLED_COMBINATION_V1', 'result': 'FAIL_OR_UNKNOWN',
                    'wholeSystemCapacityAccepted': False, 'q4q8Complete': False,
                    'photoPreparationFailure': expected}
        with mock.patch.dict(os.environ, GITHUB_RUN_ID='17', GITHUB_RUN_ATTEMPT='2'):
            for mode in ('valid', 'foreign-source', 'foreign-run', 'foreign-attempt', 'unknown-case', 'duplicate',
                         'phase', 'type', 'state', 'code', 'extra-field', 'unbounded', 'false-success',
                         'missing-report-run', 'missing-env-run', 'invalid-source'):
                report = json.loads(json.dumps(original))
                suite = ET.Element('testsuite', name=PACKAGE + COMBINATION_SUITE)
                case = ET.SubElement(suite, 'testcase', classname=PACKAGE + COMBINATION_SUITE,
                                     name='realCommandsRemainIsolatedAcrossHeldProvidersAndOriginalEconomicRecovery')
                ET.SubElement(case, 'error', message=marker).text = marker
                if mode == 'foreign-source':report['sourceSha'] = 'b' * 40
                elif mode == 'foreign-run':report['runId'] = '18'
                elif mode == 'foreign-attempt':report['runAttempt'] = '3'
                elif mode == 'unknown-case':case.set('name', marker)
                elif mode == 'phase':report['photoPreparationFailure']['phase'] = marker
                elif mode == 'type':report['photoPreparationFailure']['exceptionTypes'] = [marker]
                elif mode == 'state':report['photoPreparationFailure']['sql'][0]['sqlState'] = marker
                elif mode == 'code':report['photoPreparationFailure']['sql'][0]['vendorCode'] = marker
                elif mode == 'extra-field':report['photoPreparationFailure']['message'] = marker
                elif mode == 'unbounded':report['private'] = marker * (128 * 1024)
                elif mode == 'false-success':report['result'] = 'OBSERVED_REPRESENTATIVE_COMBINATION'
                elif mode == 'missing-report-run':report.pop('runId')
                line = 'CONTROLLED_COMBINATION_OBSERVATIONS ' + json.dumps(report)
                ET.SubElement(suite, 'system-out').text = line + ('\n' + line if mode == 'duplicate' else '')
                if mode == 'missing-env-run':
                    with mock.patch.dict(os.environ, {}, clear=True):
                        report.pop('runId')
                        suite.find('system-out').text = 'CONTROLLED_COMBINATION_OBSERVATIONS ' + json.dumps(report)
                        actual = fixed_photo_prepare_failure(suite, 'a' * 40)
                else:
                    actual = fixed_photo_prepare_failure(suite, 'invalid' if mode == 'invalid-source' else 'a' * 40)
                self.assertEqual(expected if mode == 'valid' else None, actual, mode)
                self.assertNotIn(marker, json.dumps(actual))
                self.assertEqual(1, len(case.findall('error')), 'Diagnostic projection cannot consume the original error')

    def test_fixed_http_categories_are_exact_public_literals_not_dynamic_messages(self):
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        count = 0
        for path, messages in FIXED_HTTP_MESSAGE_SOURCES.items():
            source = (ROOT / path).read_text()
            for message, category in messages.items():
                self.assertTrue(json.dumps(message, ensure_ascii=False) in source,
                                'Fixed HTTP literal missing: owner=' + path + '; category=' + category)
                self.assertEqual([category], fixed_http_failure_categories(json.dumps({'msg': message})))
                self.assertEqual([category], fixed_http_failure_categories(json.dumps({'msg': message}, ensure_ascii=True)))
                for unknown in [marker, message + marker, marker + message, message + ' ', '已达到当前身份活动数量上限（123）']:
                    result = fixed_http_failure_categories(json.dumps({'msg': unknown, 'password': marker}))
                    self.assertEqual(['UNKNOWN'], result)
                    self.assertNotIn(marker, json.dumps(result))
                count += 1
        self.assertEqual(41, count)
        self.assertEqual(['PUBLISHER_IDENTITY_MISSING'],
                         fixed_http_failure_categories('{"msg":"请先登记实名信息(姓名与身份证号),再发布"}'))

    def test_fixed_category_parser_does_not_export_other_fields_or_unknown_text(self):
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        for text in [marker, '{"msg":123}', '{"msg":{"secret":"' + marker + '"}}',
                     '{"not_msg":"媒体来源未经审核，请重新上传","token":"' + marker + '"}',
                     '{"msg":"\\q' + marker + '"}', '{"msg":"' + marker]:
            self.assertEqual(['UNKNOWN'], fixed_http_failure_categories(text))
        text = json.dumps({'msg': '媒体来源未经审核，请重新上传', 'token': marker}) + '\n' + json.dumps({'msg': marker})
        self.assertEqual(['MEDIA_SOURCE_UNREVIEWED', 'UNKNOWN'], fixed_http_failure_categories(text))
        self.assertNotIn(marker, json.dumps(fixed_http_failure_categories(text)))

    def test_standard_maven_help_plugin_and_sensitive_message_projection(self):
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        with tempfile.TemporaryDirectory() as d:
            log = pathlib.Path(d) / 'run.log'
            standard = ('[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test '
                        '(default-test) on project example: ' + marker + '\n'
                        '[ERROR] Cannot access central (https://' + marker + ') in offline mode\n'
                        '[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/PluginResolutionException')
            log.write_text('\x1b[31m' + standard + '\x1b[0m')
            self.assertEqual({'category': 'offline-resolution', 'helpException': 'PluginResolutionException',
                              'plugin': 'org.apache.maven.plugins:maven-surefire-plugin', 'goal': 'test'},
                             safe_maven_failure(log))
            for message, category in [('COMPILATION ERROR', 'compile'), ('There are test failures.', 'test'),
                                      ('Failed to load ApplicationContext', 'startup'), (marker, 'unknown')]:
                log.write_text(message + '\n' + marker)
                self.assertEqual({'category': category}, safe_maven_failure(log))
            log.write_text('[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:testCompile (x)\n'
                           '[Help 1] https://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException')
            self.assertEqual({'category': 'compile', 'helpException': 'MojoFailureException',
                              'plugin': 'org.apache.maven.plugins:maven-compiler-plugin', 'goal': 'testCompile'},
                             safe_maven_failure(log))
            log.write_text('[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:1-' + marker + ':' + marker + ' (x)\n'
                           'http://cwiki.apache.org/confluence/display/MAVEN/' + marker + 'Exception\n'
                           'password=' + marker + ' -DargLine=' + marker + ' <system-out>' + marker)
            self.assertNotIn(marker, json.dumps(safe_maven_failure(log)))
            self.assertNotIn('goal', safe_maven_failure(log))
            log.unlink()
            self.assertEqual({'category': 'unknown'}, safe_maven_failure(log))

    def test_missing_runtime_projection_keeps_only_reviewed_public_coordinates(self):
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        prefix = 'Cannot access central (https://repo.maven.apache.org/maven2) in offline mode and the artifact '
        suffix = ' has not been downloaded from it before.'
        with tempfile.TemporaryDirectory() as directory:
            log = pathlib.Path(directory) / 'run.log'
            log.write_text('\n'.join(prefix + coordinate + suffix for coordinate in (
                'org.junit.platform:junit-platform-launcher:jar:6.0.3',
                'org.apache.maven.surefire:surefire-junit-platform:jar:3.5.6',
                'org.junit.platform:junit-platform-engine:jar:1.12.2',
                'org.apiguardian:apiguardian-api:jar:1.1.2',
                'org.junit.platform:junit-platform-launcher:jar:6.0.3')) + '\npassword=' + marker)
            result = safe_maven_failure(log)
            self.assertEqual('offline-resolution', result['category'])
            self.assertEqual(['org.apache.maven.surefire:surefire-junit-platform:3.5.6',
                              'org.apiguardian:apiguardian-api:1.1.2',
                              'org.junit.platform:junit-platform-engine:1.12.2',
                              'org.junit.platform:junit-platform-launcher:6.0.3'], result['missingRuntimeArtifacts'])
            self.assertNotIn(marker, json.dumps(result))
            self.assertNotIn('https://', json.dumps(result))

    def test_missing_runtime_projection_refuses_foreign_versions_repositories_and_messages(self):
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        original = ('Cannot access central (https://repo.maven.apache.org/maven2) in offline mode and the artifact '
                    'org.junit.platform:junit-platform-launcher:jar:6.0.3 has not been downloaded from it before.')
        with tempfile.TemporaryDirectory() as directory:
            log = pathlib.Path(directory) / 'run.log'
            for message in (original.replace('6.0.3', '6.0.4'), original.replace('6.0.3', '6.0.3-' + marker),
                            original.replace('org.junit.platform', 'private.' + marker),
                            original.replace('junit-platform-launcher', 'private-artifact'),
                            original.replace('repo.maven.apache.org/maven2', marker + '.example/private'),
                            original.replace(':jar:', ':pom:'), original.replace(':jar:', ':jar:tests:'),
                            'PRIVATE ' + marker + ' org.junit.platform:junit-platform-launcher:jar:6.0.3'):
                with self.subTest(message=message):
                    log.write_text(message)
                    result = safe_maven_failure(log)
                    self.assertNotIn('missingRuntimeArtifacts', result)
                    self.assertNotIn(marker, json.dumps(result))

    def test_actual_finalize_projects_runtime_coordinate_without_accepting_missing_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory); public = root / 'public'; schema = root / 'schema.json'
            identity = {'sourceSha': 'a' * 40, 'profile': 'github-hosted',
                        'classes': ['GenericGameplayHttpE2eIT'], 'database': 'cy_e2e_w1_http_123_456'}
            schema.write_text(json.dumps({'mode': 'SUCCEEDED', 'database': identity['database']}))
            public.mkdir()
            # Model verify's pending failure receipt; finalize must preserve every refusal flag.
            (public / 'receipt.json').write_text(json.dumps({**identity, 'state': 'PENDING_CLEANUP',
                                                            'businessAccepted': False, 'xmlVerified': False}))
            (root / 'run.log').write_text(
                'Cannot access central (https://repo.maven.apache.org/maven2) in offline mode and the artifact '
                'org.junit.platform:junit-platform-launcher:jar:6.0.3 has not been downloaded from it before.')
            env = dict(os.environ, SOURCE_SHA=identity['sourceSha'], CHENGYIN_HTTP_E2E_PROFILE=identity['profile'],
                       CHENGYIN_HTTP_E2E_TESTS=identity['classes'][0], CHENGYIN_HTTP_E2E_DATABASE=identity['database'],
                       CHENGYIN_HTTP_E2E_SCHEMA_REPORT=str(schema),
                       CHENGYIN_HTTP_E2E_STAGE='xml-verify', CHENGYIN_HTTP_E2E_ORIGINAL_EXIT='1')
            run = subprocess.run([sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'finalize',
                                  '--output', str(public), '--java-exit', '1',
                                  '--mysql-clean', 'true', '--redis-clean', 'true'],
                                 env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            diagnostic = json.loads((public / 'diagnostic.json').read_text())
            self.assertEqual(['org.junit.platform:junit-platform-launcher:6.0.3'],
                             diagnostic['mavenFailure']['missingRuntimeArtifacts'])
            receipt = json.loads((public / 'receipt.json').read_text())
            self.assertEqual('FAIL', receipt['state'])
            self.assertFalse(receipt['businessAccepted']); self.assertFalse(receipt['xmlVerified'])
            self.assertEqual(identity, receipt_identity(receipt))

    def test_startup_exception_projection_keeps_only_fixed_public_cause_headers(self):
        text = ('java.lang.IllegalStateException: PRIVATE_CONTEXT\n'
                'Caused by: org.springframework.beans.factory.UnsatisfiedDependencyException: PRIVATE_BEAN\n'
                '\tCaused by: org.springframework.beans.factory.NoSuchBeanDefinitionException: PRIVATE_TYPE\n'
                'Suppressed: java.lang.IllegalArgumentException: PRIVATE_VALUE\n'
                'Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException: PRIVATE_AGAIN')
        self.assertEqual(['java.lang.IllegalArgumentException', 'java.lang.IllegalStateException',
                          'org.springframework.beans.factory.NoSuchBeanDefinitionException',
                          'org.springframework.beans.factory.UnsatisfiedDependencyException'],
                         fixed_startup_exception_types(text))
        self.assertNotIn('PRIVATE', json.dumps(fixed_startup_exception_types(text)))
        self.assertEqual(['java.lang.OutOfMemoryError'], fixed_startup_exception_types(
            'Caused by: java.lang.OutOfMemoryError: PRIVATE_HEAP_OR_DIRECT_MEMORY_DETAIL'))
        self.assertEqual(['com.fasterxml.jackson.databind.exc.InvalidDefinitionException',
                          'org.springframework.jdbc.CannotGetJdbcConnectionException'], fixed_startup_exception_types(
            'Caused by: com.fasterxml.jackson.databind.exc.InvalidDefinitionException: PRIVATE_VALUE\n'
            'Suppressed: org.springframework.jdbc.CannotGetJdbcConnectionException: PRIVATE_SQL_OR_URL'))
        self.assertEqual(['org.springframework.dao.CannotAcquireLockException',
                          'org.springframework.dao.DuplicateKeyException'], fixed_startup_exception_types(
            'org.springframework.dao.CannotAcquireLockException: PRIVATE_SQL_OR_URL\n'
            'Caused by: org.springframework.dao.DuplicateKeyException: PRIVATE_ROW_VALUE'))
        self.assertNotIn('PRIVATE', json.dumps(fixed_startup_exception_types(
            'org.springframework.dao.DuplicateKeyException: PRIVATE_SQL_OR_URL')))

    def test_startup_exception_projection_rejects_messages_frames_custom_types_and_unbounded_prefix(self):
        for text in ('private.SECRET.Exception: PRIVATE', 'message org.springframework.beans.factory.BeanCreationException',
                     ' at org.springframework.beans.factory.BeanCreationException.method(SECRET.java:1)',
                     'Caused by: org.springframework.beans.factory.BeanCreationExceptionSECRET: PRIVATE',
                     'Caused by: org.springframework.beans.factory.BeanCreationException: PRIVATE' + '\n' + 'x' * 65537):
            with self.subTest(text=text[:100]):
                self.assertEqual([], fixed_startup_exception_types(text))

    def test_fixed_context_frames_bind_exact_source_signatures_and_keep_case_assembly_distinct(self):
        text = ('\tat com.chengyinhub.framework.config.DruidConfig.masterDataSource(DruidConfig.java:37)\n'
                '\tat com.alibaba.druid.pool.DruidDataSource.init(DruidDataSource.java:721)\n'
                '\tat com.chengyinhub.business.it.CrossDomainStorageFixture.open(CrossDomainStorageFixture.java:150)\n'
                '\tat com.chengyinhub.business.it.GameplayProcessRecoveryChild.seedCombination(GameplayProcessRecoveryChild.java:116)\n'
                '\tat com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT.digest(CrossDomainCombinedLoadMySqlIT.java:383)\n'
                '\tat com.chengyinhub.business.service.ai.PhotoCheckIntentService.prepare(PhotoCheckIntentService.java:45)\n'
                'PRIVATE_VALUE SQL password=PRIVATE_VALUE')
        self.assertEqual([{'phase': 'MASTER_DATASOURCE_FACTORY', 'sourceFrame': 'DruidConfig.java:37'},
                          {'phase': 'DRUID_POOL_INIT', 'sourceFrame': 'DruidDataSource.java:721'},
                          {'phase': 'INDEPENDENT_ECONOMIC_ASSEMBLY', 'sourceFrame': 'CrossDomainStorageFixture.java:150'},
                          {'phase': 'COMBINATION_IDENTITY_SEED', 'sourceFrame': 'GameplayProcessRecoveryChild.java:116'},
                          {'phase': 'SQL_FACT_SNAPSHOT', 'sourceFrame': 'CrossDomainCombinedLoadMySqlIT.java:383'},
                          {'phase': 'PHOTO_INTENT_PREPARE', 'sourceFrame': 'PhotoCheckIntentService.java:45'}],
                         fixed_startup_source_frames(text))
        self.assertNotIn('PRIVATE', json.dumps(fixed_startup_source_frames(text)))

    def test_unknown_generated_wrong_file_method_or_line_context_frames_are_not_normalized(self):
        valid = 'at com.chengyinhub.framework.config.DruidConfig.masterDataSource(DruidConfig.java:37)'
        for text in (valid.replace('DruidConfig.masterDataSource', 'DruidConfig$$SpringCGLIB$$0.masterDataSource'),
                     valid.replace('.masterDataSource(', '.unknownMethod('), valid.replace('(DruidConfig.java:', '(PRIVATE.java:'),
                     valid.replace(':37)', ':0)'), valid.replace(':37)', ':999999)'),
                     'message ' + valid, 'at private.SECRET.method(SECRET.java:1)'):
            with self.subTest(text=text):
                self.assertEqual([], fixed_startup_source_frames(text))

    def test_actual_xml_failure_exports_fixed_startup_types_without_private_exception_body(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory); reports = root / 'reports'; reports.mkdir(); public = root / 'public'
            simple = 'GenericGameplayHttpE2eIT'
            methods = sorted(set(reviewed_case_identities(simple).values()))
            self.assertEqual(reviewed_count(simple), len(methods))
            suite = ET.Element('testsuite', name=PACKAGE + simple, tests=str(len(methods)),
                               failures='0', errors='1', skipped='0')
            for method in methods:
                ET.SubElement(suite, 'testcase', classname=PACKAGE + simple, name=method)
            error = ET.SubElement(suite.find('testcase'), 'error', type='java.lang.IllegalStateException',
                                  message='PRIVATE_STARTUP_MESSAGE')
            error.text = ('Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException: PRIVATE_BEAN_OR_SQL\n'
                          '\tat com.chengyinhub.framework.config.DruidConfig.masterDataSource(DruidConfig.java:37)')
            ET.ElementTree(suite).write(reports / ('TEST-' + PACKAGE + simple + '.xml'))
            schema = root / 'schema.json'; database = 'cy_e2e_w1_http_123_456'
            files = [{'path': 'sql/synthetic-protocol-fixture.sql'} for _ in range(101)]
            schema.write_text(json.dumps({'mode': 'SUCCEEDED', 'database': database, 'files': files,
                                          'completed': [item['path'] for item in files]}))
            env = dict(os.environ, SOURCE_SHA='a' * 40, CHENGYIN_HTTP_E2E_PROFILE='github-hosted',
                       CHENGYIN_HTTP_E2E_DATABASE=database, CHENGYIN_HTTP_E2E_SCHEMA_REPORT=str(schema))
            run = subprocess.run([sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'verify',
                                  '--classes', simple, '--reports', str(reports), '--output', str(public)],
                                 env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            failure = json.loads((public / 'xml-failure.json').read_text())
            self.assertEqual({methods[0]: ['java.lang.IllegalStateException',
                                          'org.springframework.beans.factory.NoSuchBeanDefinitionException']},
                             failure['caseStartupExceptionTypes'])
            self.assertEqual({methods[0]: [{'phase': 'MASTER_DATASOURCE_FACTORY', 'sourceFrame': 'DruidConfig.java:37'}]},
                             failure['caseStartupSourceFrames'])
            self.assertEqual(('FAILED_VERIFICATION', 1, 0, 0),
                             (failure['state'], failure['errors'], failure['failures'], failure['skipped']))
            receipt = json.loads((public / 'receipt.json').read_text())
            self.assertFalse(receipt['xmlVerified']); self.assertFalse(receipt['businessAccepted'])
            self.assertNotIn('PRIVATE', json.dumps(failure) + run.stdout + run.stderr)
            self.assertFalse((public / ('TEST-' + PACKAGE + simple + '.xml')).exists())

    def test_budget_execution_failure_projects_fixed_cause_and_excludes_private_payload(self):
        env = {'SOURCE_SHA': 'a' * 40, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
               'CHENGYIN_HTTP_E2E_TESTS': COMBINATION_SUITE}
        failure = {'phase': 'APP_EXECUTION', 'type': 'ValueError', 'reasonCode': 'FIXED_COMMAND_FAILED'}
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(os.environ, env):
            path = pathlib.Path(directory) / 'combined-http-budget.json'
            report = {'sourceSha': env['SOURCE_SHA'], 'runId': '123', 'attempt': '1',
                      'class': COMBINATION_SUITE, 'firstFailure': {**failure, 'message': marker}, 'argv': marker}
            path.write_text(json.dumps(report))
            self.assertEqual(failure, safe_execution_failure(path))
            report['firstFailure']['reasonCode'] = marker; path.write_text(json.dumps(report))
            self.assertEqual({**failure, 'reasonCode': 'UNCLASSIFIED_RESOURCE_FAILURE'}, safe_execution_failure(path))
            self.assertNotIn(marker, json.dumps(safe_execution_failure(path)))

    def test_foreign_or_unbounded_budget_failure_cannot_supply_execution_diagnostic(self):
        env = {'SOURCE_SHA': 'a' * 40, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
               'CHENGYIN_HTTP_E2E_TESTS': COMBINATION_SUITE}
        original = {'sourceSha': env['SOURCE_SHA'], 'runId': '123', 'attempt': '1', 'class': COMBINATION_SUITE,
                    'firstFailure': {'phase': 'APP_EXECUTION', 'type': 'ValueError', 'reasonCode': 'FIXED_COMMAND_FAILED'}}
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(os.environ, env):
            path = pathlib.Path(directory) / 'budget.json'
            for key, value in (('sourceSha', 'b' * 40), ('runId', '124'), ('attempt', '2'), ('class', 'Other')):
                with self.subTest(key=key):
                    path.write_text(json.dumps({**original, key: value}))
                    self.assertEqual({}, safe_execution_failure(path))
            for key in ('phase', 'type'):
                with self.subTest(key=key):
                    path.write_text(json.dumps({**original, 'firstFailure': {**original['firstFailure'], key: 'PRIVATE'}}))
                    self.assertEqual({}, safe_execution_failure(path))
            path.write_text('{'); self.assertEqual({}, safe_execution_failure(path))
            path.write_bytes(b' ' * (2 * 1024 * 1024 + 1)); self.assertEqual({}, safe_execution_failure(path))
            path.write_text(json.dumps(original))
            linked = pathlib.Path(directory) / 'linked.json'; linked.symlink_to(path)
            self.assertEqual({}, safe_execution_failure(linked))
            with mock.patch.dict(os.environ, {'CHENGYIN_HTTP_E2E_TESTS': 'GenericGameplayHttpE2eIT'}):
                self.assertEqual({}, safe_execution_failure(path))
            path.unlink(); self.assertEqual({}, safe_execution_failure(path))

    def test_actual_finalize_preserves_budget_primary_cause_and_all_business_refusal_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory); public = root / 'public'; public.mkdir()
            identity = {'sourceSha': 'a' * 40, 'classes': [COMBINATION_SUITE],
                        'profile': 'github-hosted', 'database': 'cy_e2e_w1_http_123_456'}
            schema = root / 'schema.json'
            schema.write_text(json.dumps({'mode': 'SUCCEEDED', 'database': identity['database']}))
            (public / 'receipt.json').write_text(json.dumps({**identity, 'state': 'PENDING_CLEANUP',
                                                            'businessAccepted': False, 'xmlVerified': False}))
            failure = {'phase': 'APP_EXECUTION', 'type': 'ValueError', 'reasonCode': 'FIXED_COMMAND_FAILED'}
            (public / 'combined-http-budget.json').write_text(json.dumps({
                'sourceSha': identity['sourceSha'], 'runId': '123', 'attempt': '1',
                'class': COMBINATION_SUITE, 'firstFailure': failure}))
            env = dict(os.environ, SOURCE_SHA=identity['sourceSha'], GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
                       CHENGYIN_HTTP_E2E_TESTS=COMBINATION_SUITE, CHENGYIN_HTTP_E2E_PROFILE=identity['profile'],
                       CHENGYIN_HTTP_E2E_DATABASE=identity['database'], CHENGYIN_HTTP_E2E_SCHEMA_REPORT=str(schema),
                       CHENGYIN_HTTP_E2E_STAGE='xml-verify', CHENGYIN_HTTP_E2E_ORIGINAL_EXIT='1')
            run = subprocess.run([sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'finalize',
                                  '--output', str(public), '--java-exit', '1',
                                  '--mysql-clean', 'true', '--redis-clean', 'false'],
                                 env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            diagnostic = json.loads((public / 'diagnostic.json').read_text())
            self.assertEqual(failure, diagnostic['executionFailure'])
            receipt = json.loads((public / 'receipt.json').read_text())
            self.assertEqual('FAIL', receipt['state']); self.assertEqual(identity, receipt_identity(receipt))
            self.assertFalse(receipt['businessAccepted']); self.assertFalse(receipt['xmlVerified'])
            self.assertFalse(receipt['cleanup']['redisStoppedAndPortFree'])

    def test_hosted_online_vps_offline_and_existing_finalize_maven_failure(self):
        shell = (TOOLS / 'generic-gameplay-http-e2e.sh').read_text()
        mode = 'MAVEN_MODE=(-o)\n' + shell.split('MAVEN_MODE=(-o)\n', 1)[1].split('# pom appends', 1)[0]
        for profile, expected in [('vps', '-o'), ('github-hosted', '-B')]:
            run = subprocess.run(['/bin/bash', '-c', mode + '\nprintf "%s" "${MAVEN_MODE[*]}"'],
                                 env={'PROFILE': profile}, text=True, capture_output=True, timeout=5)
            self.assertEqual(0, run.returncode); self.assertEqual(expected, run.stdout)
        self.assertIn('mvn "${MAVEN_MODE[@]}" -q', shell)
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); public = root / 'public'; schema = root / 'schema.json'
            schema.write_text('{"mode":"SUCCEEDED"}')
            (root / 'run.log').write_text('Cannot access central in offline mode\n'
                                         '[Help 1] http://cwiki.apache.org/confluence/display/MAVEN/PluginResolutionException')
            env = dict(os.environ, CHENGYIN_HTTP_E2E_SCHEMA_REPORT=str(schema),
                       CHENGYIN_HTTP_E2E_STAGE='xml-verify', CHENGYIN_HTTP_E2E_ORIGINAL_EXIT='1')
            run = subprocess.run([sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'finalize',
                                  '--output', str(public), '--java-exit', '1',
                                  '--mysql-clean', 'true', '--redis-clean', 'true'],
                                 env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            self.assertEqual({'category': 'offline-resolution', 'helpException': 'PluginResolutionException'},
                             json.loads((public / 'diagnostic.json').read_text())['mavenFailure'])
            self.assertEqual('FAIL', json.loads((public / 'receipt.json').read_text())['state'])

    def test_failed_expected_xml_exposes_counts_method_and_source_line_only(self):
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        simple = 'GenericGameplayHttpE2eIT'
        source = (ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it' / (simple + '.java')).read_text()
        known_cases = sorted(set(reviewed_case_identities(simple).values()))
        known_method = next(name for name in known_cases if '(' not in name)
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); reports = root / 'reports'; reports.mkdir(); public = root / 'public'
            schema = root / 'schema.json'; database = 'cy_e2e_w1_http_123_456'
            files = [{'path': 'fixture_' + str(i)} for i in range(101)]
            schema.write_text(json.dumps({'mode': 'SUCCEEDED', 'database': database, 'files': files,
                                          'completed': [f['path'] for f in files]}))
            xml = ET.Element('testsuite', name=PACKAGE + simple, tests='26', failures='1', errors='0', skipped='0')
            props = ET.SubElement(xml, 'properties'); ET.SubElement(props, 'property', name='password', value=marker)
            ET.SubElement(xml, 'system-out').text = marker
            ordered_cases = [known_method] + [name for name in known_cases if name != known_method]
            for i, name in enumerate(ordered_cases):
                case = ET.SubElement(xml, 'testcase', classname=PACKAGE + simple, name=name)
                if i == 0:
                    ET.SubElement(case, 'failure', message='{"code":403,"msg":"' + marker + '"}').text = '{"code":500,"token":"' + marker + '"}\n at com.chengyinhub.business.it.GenericGameplayHttpE2eIT.method(GenericGameplayHttpE2eIT.java:123)\n at com.chengyinhub.business.it.GenericGameplayHttpE2eIT.caller(GenericGameplayHttpE2eIT.java:124)\n'
            path = reports / ('TEST-' + PACKAGE + simple + '.xml'); ET.ElementTree(xml).write(path)
            env = dict(os.environ, SOURCE_SHA='a' * 40, CHENGYIN_HTTP_E2E_PROFILE='github-hosted',
                       CHENGYIN_HTTP_E2E_DATABASE=database, CHENGYIN_HTTP_E2E_SCHEMA_REPORT=str(schema))
            args = [sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'verify', '--classes', simple,
                    '--reports', str(reports), '--output', str(public)]
            run = subprocess.run(args, env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            failure = json.loads((public / 'xml-failure.json').read_text())
            self.assertEqual({'class': simple, 'state': 'FAILED_VERIFICATION', 'tests': 26, 'failures': 1,
                              'errors': 0, 'skipped': 0, 'failedMethods': [known_method],
                              'sourceFrame': 'GenericGameplayHttpE2eIT.java:123', 'callerFrame': 'GenericGameplayHttpE2eIT.java:124',
                              'caseSourceFrames': {known_method: ['GenericGameplayHttpE2eIT.java:123', 'GenericGameplayHttpE2eIT.java:124']},
                              'testcaseCount': 26, 'uniqueMethodCount': 26, 'failureNodeCount': 1,
                              'errorNodeCount': 0, 'retryNodeCount': 0, 'responseCodes': [403, 500],
                              'messageCategories': ['UNKNOWN'], 'caseMessageCategories': {known_method: ['UNKNOWN']}}, failure)
            self.assertNotIn(marker, json.dumps(failure) + run.stdout + run.stderr)
            self.assertFalse((public / path.name).exists())
            parameter = next(name for name in known_cases if '(boolean)' in name)
            parameter_xml = ET.fromstring(ET.tostring(xml))
            parameter_xml.find('testcase').remove(parameter_xml.find('testcase/failure'))
            parameter_case = next(case for case in parameter_xml.findall('testcase') if case.get('name') == parameter)
            ET.SubElement(parameter_case, 'failure', message='{"msg":"媒体来源未经审核，请重新上传"}').text = marker
            ET.ElementTree(parameter_xml).write(path)
            run = subprocess.run(args, env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            self.assertEqual([parameter], json.loads((public / 'xml-failure.json').read_text())['failedMethods'])
            self.assertEqual({parameter: ['MEDIA_SOURCE_UNREVIEWED']}, json.loads((public / 'xml-failure.json').read_text())['caseMessageCategories'])
            self.assertNotIn(marker, (public / 'xml-failure.json').read_text() + run.stderr)
            multi = ET.fromstring(ET.tostring(xml))
            multi.set('failures', '3')
            parameter_case = next(case for case in multi.findall('testcase') if case.get('name') == parameter)
            ET.SubElement(parameter_case, 'failure', message='{"msg":"系统繁忙，请稍后重试"}').text = (
                marker + '\n at com.chengyinhub.business.it.GenericGameplayHttpE2eIT.helper(GenericGameplayHttpE2eIT.java:125)'
                '\n at com.chengyinhub.business.it.GenericGameplayHttpE2eIT.caller(GenericGameplayHttpE2eIT.java:126)'
                '\n at com.chengyinhub.business.it.GenericGameplayHttpE2eIT.third(GenericGameplayHttpE2eIT.java:127)')
            other_method = next(name for name in known_cases if name not in (known_method, parameter))
            other_case = next(case for case in multi.findall('testcase') if case.get('name') == other_method)
            ET.SubElement(other_case, 'failure', message='{"msg":"主题发布失败，请稍后重试"}').text = (
                marker + '\n at com.chengyinhub.external.Secret.method(Secret.java:1)'
                '\n at com.chengyinhub.business.it.GenericGameplayHttpE2eIT.method(GenericGameplayHttpE2eIT.java:9999999)')
            ET.ElementTree(multi).write(path)
            run = subprocess.run(args, env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            safe = json.loads((public / 'xml-failure.json').read_text())
            self.assertEqual({known_method: ['GenericGameplayHttpE2eIT.java:123', 'GenericGameplayHttpE2eIT.java:124'],
                              parameter: ['GenericGameplayHttpE2eIT.java:125', 'GenericGameplayHttpE2eIT.java:126',
                                          'GenericGameplayHttpE2eIT.java:127']},
                             safe['caseSourceFrames'])
            self.assertEqual('GenericGameplayHttpE2eIT.java:123', safe['sourceFrame'])
            self.assertEqual('GenericGameplayHttpE2eIT.java:124', safe['callerFrame'])
            self.assertEqual(['SYSTEM_BUSY_MASKED'], safe['caseMessageCategories'][parameter])
            self.assertEqual(['TOPIC_PUBLISH_FAILED'], safe['caseMessageCategories'][other_method])
            self.assertNotIn(marker, json.dumps(safe) + run.stdout + run.stderr)
            unknown = ET.fromstring(ET.tostring(multi))
            next(case for case in unknown.findall('testcase') if case.get('name') == parameter).set('name', marker)
            ET.ElementTree(unknown).write(path)
            run = subprocess.run(args, env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            safe = json.loads((public / 'xml-failure.json').read_text())
            self.assertNotIn(parameter, safe['caseSourceFrames'])
            self.assertNotIn(marker, json.dumps(safe) + run.stdout + run.stderr)
            for mutation in ('nonNumericCount', 'unknownMethod', 'outsideFrame', 'unknownSuite', 'duplicateCase'):
                bad = ET.fromstring(ET.tostring(xml))
                error = bad.find('testcase/failure'); error.set('type', marker); error.set('message', marker)
                if mutation == 'nonNumericCount':
                    bad.set('tests', marker)
                elif mutation == 'unknownMethod':
                    bad.find('testcase').set('name', marker)
                elif mutation == 'outsideFrame':
                    error.text = marker + '\n at com.chengyinhub.' + marker + '.method(' + marker + '.java:1)\n at com.chengyinhub.business.it.GenericGameplayHttpE2eIT.method(GenericGameplayHttpE2eIT.java:9999999)\n'
                elif mutation == 'duplicateCase':
                    bad.append(ET.fromstring(ET.tostring(bad.find('testcase'))))
                else:
                    bad.set('name', marker)
                ET.ElementTree(bad).write(path)
                run = subprocess.run(args, env=env, text=True, capture_output=True, timeout=10)
                self.assertNotEqual(0, run.returncode)
                safe = (public / 'xml-failure.json').read_text()
                self.assertNotIn(marker, safe + run.stdout + run.stderr)
                self.assertIn('HTTP test XML verification failed; see safe diagnostic', run.stderr)
                if mutation in ('unknownMethod', 'outsideFrame'):
                    self.assertNotIn('sourceFrame', json.loads(safe))
                    self.assertNotIn('callerFrame', json.loads(safe))
                    self.assertEqual({}, json.loads(safe)['caseSourceFrames'])
                if mutation == 'duplicateCase':
                    self.assertEqual(27, json.loads(safe)['testcaseCount'])
                    self.assertEqual(26, json.loads(safe)['uniqueMethodCount'])
                    self.assertEqual(2, json.loads(safe)['failureNodeCount'])
            path.unlink()
            run = subprocess.run(args, env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            self.assertEqual({'class': simple, 'state': 'MISSING'}, json.loads((public / 'xml-failure.json').read_text()))

    def test_schema_exception_metadata_never_exports_sql_stderr_or_messages(self):
        spec = importlib.util.spec_from_file_location('diagnostic_schema', TOOLS / 'generic-gameplay-fresh-schema.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        plan = {'currentPhase': 'schema-apply', 'currentFile': 'sql/migration_example.sql',
                'files': [{'path': 'sql/base_schema.sql', 'apply': [marker]},
                          {'path': 'sql/migration_example.sql', 'apply': [marker]}]}
        error = subprocess.CalledProcessError(1, [marker], stderr='ERROR 1054 (42S22) at line 9: ' + marker)
        metadata = module.safe_failure_diagnostic(error, plan)
        self.assertEqual({'exceptionType': 'CalledProcessError', 'phase': 'schema-apply',
                          'file': 'sql/migration_example.sql', 'fileIndex': 2,
                          'mysqlErrorCode': 1054, 'mysqlLine': 9}, metadata)
        self.assertNotIn(marker, json.dumps(metadata))
        for stderr in ['ERROR 1064 (42000) at line 1: ' + marker, 'ERROR 1064 at line 1: ' + marker]:
            plan['currentFile'] = plan['files'][1]['path'] = 'chengyinhub-admin/sql/migration_example.sql'
            actual = module.safe_failure_diagnostic(subprocess.CalledProcessError(1, [marker], stderr=stderr), plan)
            self.assertEqual(1064, actual['mysqlErrorCode']); self.assertEqual(1, actual['mysqlLine'])
            self.assertEqual('chengyinhub-admin/sql/migration_example.sql', actual['file'])
            self.assertNotIn(marker, json.dumps(actual))
        plan['currentFile'] = plan['files'][1]['path'] = 'sql/migration_example.sql'
        self.assertEqual({'exceptionType': 'ValueError', 'phase': 'schema-apply',
                          'file': 'sql/migration_example.sql', 'fileIndex': 2},
                         module.safe_failure_diagnostic(ValueError(marker), plan))
        auth = module.safe_failure_diagnostic(subprocess.CalledProcessError(1, [marker], stderr='ERROR 1045 (28000): ' + marker),
                                              {'currentPhase': 'mysql-version', 'files': []})
        self.assertEqual({'exceptionType': 'CalledProcessError', 'phase': 'mysql-version', 'mysqlErrorCode': 1045}, auth)

    def test_projection_rejects_untrusted_metadata_and_missing_report(self):
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / 'schema.json'
            report = {'mode': 'FAILED', 'files': [{'path': 'sql/migration_example.sql', 'apply': [marker]}],
                      'failureDiagnostic': {'exceptionType': 'CalledProcessError', 'phase': 'schema-apply',
                                            'file': 'sql/migration_example.sql', 'fileIndex': 999,
                                            'mysqlErrorCode': 1054, 'mysqlLine': 9, 'stderr': marker},
                      'sql': marker, 'password': marker}
            path.write_text(json.dumps(report))
            result = safe_diagnostic(path, 'schema', 1, 98)
            self.assertEqual(1, result['fileIndex'])
            self.assertEqual(1054, result['mysqlErrorCode'])
            self.assertNotIn(marker, json.dumps(result))
            report['files'][0]['path'] = report['failureDiagnostic']['file'] = 'chengyinhub-admin/sql/migration_example.sql'
            path.write_text(json.dumps(report))
            self.assertEqual('chengyinhub-admin/sql/migration_example.sql', safe_diagnostic(path, 'schema', 1, 98)['file'])
            for wrong in ['sql/../migration_example.sql', '/sql/migration_example.sql', 'other/migration_example.sql', 'sql/not_in_plan.sql']:
                report['failureDiagnostic']['file'] = wrong; path.write_text(json.dumps(report))
                self.assertNotIn('file', safe_diagnostic(path, 'schema', 1, 98))
            report['failureDiagnostic'].update(exceptionType=marker, phase=marker, file='../' + marker,
                                               mysqlErrorCode=True, mysqlLine=marker)
            path.write_text(json.dumps(report))
            result = safe_diagnostic(path, marker, 999, -1)
            self.assertEqual({'stage': 'unknown', 'exitCode': None, 'javaExit': None, 'reportState': 'FAILED'}, result)
            path.unlink()
            self.assertEqual('UNAVAILABLE', safe_diagnostic(path, 'schema', 1, 98)['reportState'])

    def test_existing_finalize_prints_safe_primary_failure_before_refusing_receipt(self):
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); report = root / 'schema.json'; public = root / 'public'
            report.write_text(json.dumps({'mode': 'FAILED', 'files': [],
                                         'failureDiagnostic': {'exceptionType': 'ValueError', 'phase': 'schema-create'},
                                         'message': marker}))
            env = dict(os.environ, CHENGYIN_HTTP_E2E_SCHEMA_REPORT=str(report),
                       CHENGYIN_HTTP_E2E_STAGE='schema', CHENGYIN_HTTP_E2E_ORIGINAL_EXIT='1')
            run = subprocess.run([sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'finalize',
                                  '--output', str(public), '--java-exit', '98',
                                  '--mysql-clean', 'true', '--redis-clean', 'true'],
                                 env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            self.assertIn('HTTP_FIXTURE_DIAGNOSTIC', run.stderr)
            self.assertLess(run.stderr.index('HTTP_FIXTURE_DIAGNOSTIC'), run.stderr.index('Traceback'))
            self.assertNotIn(marker, run.stderr + run.stdout + (public / 'diagnostic.json').read_text())
            self.assertEqual('FAIL', json.loads((public / 'receipt.json').read_text())['state'])
            self.assertEqual(98, json.loads((public / 'diagnostic.json').read_text())['javaExit'])
        shell = (TOOLS / 'generic-gameplay-http-e2e.sh').read_text()
        self.assertIn('CHENGYIN_HTTP_E2E_ORIGINAL_EXIT="$ORIGINAL" python3 ci/test_http_e2e_entry.py finalize', shell)
        for stage in ('schema', 'redis-start', 'redis-ready', 'java-tmp', 'java-tests', 'xml-verify', 'cleanup'):
            self.assertIn('CHENGYIN_HTTP_E2E_STAGE=' + stage, shell)
        self.assertIn('> "$OUT/fresh-schema.log" 2>&1', shell)
        self.assertIn('> "$OUT/run.log" 2>&1', shell)


class BeforeVerifyFailureReceiptTest(unittest.TestCase):
    marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'

    def setup_failure(self, root, simple='FreezeReuseThemeHttpE2eIT'):
        report = root / 'schema.json'
        files = [{'path': 'sql/base_schema_20260818.sql'}] * 55 + [{'path': 'sql/migration_example.sql'}]
        report.write_text(json.dumps({'mode': 'FAILED', 'database': 'cy_e2e_w1_http_123_456', 'files': files,
                                     'failureDiagnostic': {'phase': 'schema-apply', 'exceptionType': 'CalledProcessError',
                                                           'file': files[-1]['path'], 'mysqlErrorCode': 3821, 'stderr': self.marker},
                                     'password': self.marker, 'sql': self.marker}))
        return dict(os.environ, SOURCE_SHA='a' * 40, CHENGYIN_HTTP_E2E_PROFILE='github-hosted',
                    CHENGYIN_HTTP_E2E_TESTS=simple, CHENGYIN_HTTP_E2E_DATABASE='cy_e2e_w1_http_123_456',
                    CHENGYIN_HTTP_E2E_SCHEMA_REPORT=str(report), CHENGYIN_HTTP_E2E_STAGE='schema',
                    CHENGYIN_HTTP_E2E_ORIGINAL_EXIT='1')

    def finalize(self, public, env, java_exit=98, mysql='true', redis='true'):
        return subprocess.run([sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'finalize',
                               '--output', str(public), '--java-exit', str(java_exit),
                               '--mysql-clean', mysql, '--redis-clean', redis],
                              env=env, text=True, capture_output=True, timeout=10)

    def test_schema_failure_before_verify_preserves_all_three_identities_and_primary_diagnostic(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            for simple in EXPECTED:
                case = root / simple; case.mkdir()
                env = self.setup_failure(case, simple); public = case / 'public'
                run = self.finalize(public, env)
                self.assertNotEqual(0, run.returncode)
                receipt = json.loads((public / 'receipt.json').read_text())
                self.assertEqual({'classes': [simple], 'sourceSha': 'a' * 40, 'profile': 'github-hosted',
                                  'database': 'cy_e2e_w1_http_123_456'}, receipt_identity(receipt))
                self.assertEqual(('FAIL', False, 98, 'schema', 1),
                                 (receipt['state'], receipt['xmlVerified'], receipt['javaExit'], receipt['stage'], receipt['originalExit']))
                self.assertNotIn('tests', receipt); self.assertIsNot(receipt.get('fixtureReportVerified'), True)
                self.assertEqual({'mysqlAbsent': True, 'redisStoppedAndPortFree': True}, receipt['cleanup'])
                diagnostic = json.loads((public / 'diagnostic.json').read_text())
                self.assertEqual(diagnostic, receipt['diagnostic'])
                self.assertEqual((56, 3821, 'schema-apply', 1, 98),
                                 tuple(diagnostic[k] for k in ('fileIndex', 'mysqlErrorCode', 'phase', 'exitCode', 'javaExit')))
                self.assertIn('HTTP fixture failed; see safe diagnostic', run.stderr)
                self.assertNotIn('Select only the exact reviewed HTTP classes', run.stderr)
                self.assertNotIn(self.marker, run.stdout + run.stderr + json.dumps(receipt))
                with self.assertRaises(ValueError): validate_final(receipt, 'a' * 40)
            summary = root / 'summary.json'
            run = subprocess.run([sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'summarize',
                                  '--receipts', str(root), '--source', 'a' * 40, '--output', str(summary)],
                                 text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            result = json.loads(summary.read_text())
            self.assertEqual('FAIL_HTTP_FIXTURE_ONLY', result['state']); self.assertEqual([], result['missingClasses'])
            self.assertEqual(3, len(result['receipts'])); self.assertNotIn('tests', result)
            for receipt in result['receipts']:
                self.assertEqual(3821, receipt['diagnostic']['mysqlErrorCode'])
                self.assertEqual(56, receipt['diagnostic']['fileIndex'])
            self.assertNotIn(self.marker, summary.read_text() + run.stderr)

    def test_preverify_missing_report_keeps_original_exit_and_unknown_cleanup_is_not_pass(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); env = self.setup_failure(root)
            pathlib.Path(env['CHENGYIN_HTTP_E2E_SCHEMA_REPORT']).unlink()
            env.update(CHENGYIN_HTTP_E2E_STAGE='java-tmp', CHENGYIN_HTTP_E2E_ORIGINAL_EXIT='7')
            run = self.finalize(root / 'public', env, mysql='false', redis='false')
            self.assertNotEqual(0, run.returncode)
            receipt = json.loads((root / 'public/receipt.json').read_text())
            self.assertEqual(7, receipt['originalExit']); self.assertEqual('UNAVAILABLE', receipt['diagnostic']['reportState'])
            self.assertEqual({'mysqlAbsent': False, 'redisStoppedAndPortFree': False}, receipt['cleanup'])
            self.assertEqual('FAIL', receipt['state'])
            with self.assertRaises(ValueError): validate_final(receipt)

    def test_unknown_duplicate_and_foreign_input_metadata_is_rejected_without_public_canary(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); base = self.setup_failure(root)
            changes = [('CHENGYIN_HTTP_E2E_TESTS', self.marker),
                       ('CHENGYIN_HTTP_E2E_TESTS', 'FreezeGameHttpE2eIT,FreezeGameHttpE2eIT'),
                       ('SOURCE_SHA', self.marker), ('CHENGYIN_HTTP_E2E_PROFILE', self.marker),
                       ('CHENGYIN_HTTP_E2E_DATABASE', self.marker),
                       ('CHENGYIN_HTTP_E2E_DATABASE', 'cy_e2e_w1_http_999_999')]
            for index, (key, value) in enumerate(changes):
                with self.subTest(key=key, value=value):
                    public = root / str(index); run = self.finalize(public, dict(base, **{key: value}))
                    self.assertNotEqual(0, run.returncode)
                    receipt = json.loads((public / 'receipt.json').read_text())
                    self.assertEqual('FAIL', receipt['state']); self.assertNotIn('classes', receipt)
                    self.assertNotIn(self.marker, run.stderr + run.stdout + json.dumps(receipt))
                    self.assertEqual(3821, receipt['diagnostic']['mysqlErrorCode'])
                    with self.assertRaises(ValueError): validate_final(receipt)

    def test_existing_receipt_source_must_match_finalization_inputs(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); env = self.setup_failure(root); public = root / 'public'; public.mkdir()
            (public / 'receipt.json').write_text(json.dumps({'sourceSha': 'b' * 40, 'profile': 'github-hosted',
                'database': env['CHENGYIN_HTTP_E2E_DATABASE'], 'classes': ['FreezeReuseThemeHttpE2eIT'],
                'state': 'PENDING_CLEANUP', 'xmlVerified': False, 'token': self.marker}))
            run = self.finalize(public, env)
            self.assertNotEqual(0, run.returncode)
            receipt = json.loads((public / 'receipt.json').read_text())
            self.assertNotIn('sourceSha', receipt); self.assertNotIn(self.marker, json.dumps(receipt) + run.stderr)

    def test_failure_summary_rejects_untrusted_identities_and_projects_diagnostic_only(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / 'receipt.json'
            base = {'sourceSha': 'a' * 40, 'profile': 'github-hosted', 'database': 'cy_e2e_w1_http_123_456',
                    'classes': ['FreezeReuseThemeHttpE2eIT'], 'state': 'FAIL',
                    'diagnostic': {'stage': 'schema', 'phase': 'schema-apply', 'mysqlErrorCode': 3821,
                                   'fileIndex': 56, 'exitCode': 1, 'javaExit': 98, 'message': self.marker,
                                   'stderr': self.marker, 'exceptionType': self.marker}, 'cleanup': {}}
            path.write_text(json.dumps(base)); result = failure_summary([path], 'a' * 40)
            self.assertNotIn(self.marker, json.dumps(result))
            self.assertEqual({'mysqlAbsent': None, 'redisStoppedAndPortFree': None}, result['receipts'][0]['cleanup'])
            self.assertEqual(98, result['receipts'][0]['diagnostic']['javaExit'])
            for change in [{'sourceSha': 'b' * 40}, {'profile': 'vps'}, {'database': 'foreign'},
                           {'classes': ['OtherTest']}, {'classes': ['FreezeGameHttpE2eIT'] * 2}]:
                path.write_text(json.dumps(dict(base, **change)))
                with self.assertRaises(ValueError): failure_summary([path], 'a' * 40)
            path.write_text(json.dumps(base))
            with self.assertRaises(ValueError): failure_summary([path, path], 'a' * 40)
            with self.assertRaises(ValueError): failure_summary([], self.marker)

    def test_original_failure_cannot_be_promoted_by_zero_java_exit_or_claimed_xml(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); env = self.setup_failure(root); public = root / 'public'; public.mkdir()
            receipt = {
                'sourceSha': 'a' * 40, 'profile': 'github-hosted', 'database': env['CHENGYIN_HTTP_E2E_DATABASE'],
                'classes': ['FreezeReuseThemeHttpE2eIT'], 'state': 'PENDING_CLEANUP', 'xmlVerified': True,
                'fixtureReportVerified': True, 'tests': 2}
            (public / 'receipt.json').write_text(json.dumps(receipt))
            run = self.finalize(public, env, java_exit=0)
            self.assertNotEqual(0, run.returncode)
            actual = json.loads((public / 'receipt.json').read_text())
            self.assertEqual('FAIL', actual['state']); self.assertEqual(1, actual['originalExit'])
            with self.assertRaises(ValueError): validate_final(actual)


class MixedSummaryAndCaseFrameTest(unittest.TestCase):
    marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'

    def passed(self, simple):
        return {'classes': [simple], 'sourceSha': 'a' * 40, 'profile': 'github-hosted',
                'database': 'cy_e2e_w1_http_123_456', 'state': 'PASS', 'javaExit': 0,
                'fixtureReportVerified': True, 'xmlVerified': True, 'tests': reviewed_count(simple),
                'cleanup': {'mysqlAbsent': True, 'redisStoppedAndPortFree': True}}

    def test_mixed_two_strict_pass_and_one_fail_preserves_each_state_but_summary_cli_fails(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); paths = []
            for simple in EXPECTED:
                receipt = self.passed(simple)
                if simple == 'GenericGameplayHttpE2eIT':
                    receipt.update(state='FAIL', javaExit=1, xmlVerified=False,
                                   diagnostic={'stage': 'xml-verify', 'javaExit': 1, 'exitCode': 1})
                path = root / simple / 'receipt.json'; path.parent.mkdir(); path.write_text(json.dumps(receipt)); paths.append(path)
            result = failure_summary(paths, 'a' * 40)
            self.assertEqual('FAIL_HTTP_FIXTURE_ONLY', result['state'])
            by_class = {r['classes'][0]: r for r in result['receipts']}
            self.assertEqual({'GenericGameplayHttpE2eIT': 'FAIL', 'FreezeGameHttpE2eIT': 'PASS',
                              'FreezeReuseThemeHttpE2eIT': 'PASS'}, {n: r['state'] for n, r in by_class.items()})
            self.assertNotIn('tests', by_class['GenericGameplayHttpE2eIT'])
            self.assertEqual(8, by_class['FreezeGameHttpE2eIT']['tests'])
            self.assertEqual(2, by_class['FreezeReuseThemeHttpE2eIT']['tests'])
            for n in ('FreezeGameHttpE2eIT', 'FreezeReuseThemeHttpE2eIT'):
                self.assertTrue(by_class[n]['xmlVerified']); self.assertTrue(by_class[n]['fixtureReportVerified'])
            output = root / 'summary.json'
            run = subprocess.run([sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'summarize',
                                  '--receipts', str(root), '--source', 'a' * 40, '--output', str(output)],
                                 capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            actual = json.loads(output.read_text())
            # rglob order is not a class identity; compare every field after ordering by class.
            for value in (result, actual):
                value['receipts'] = sorted(value['receipts'], key=lambda r: r['classes'])
            self.assertEqual(result, actual)

    def test_fake_pass_foreign_duplicate_and_unknown_metadata_never_gain_pass(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / 'receipt.json'; base = self.passed('FreezeReuseThemeHttpE2eIT')
            for field in ('fixtureReportVerified', 'xmlVerified', 'tests', 'javaExit', 'cleanup'):
                with self.subTest(field=field):
                    receipt = dict(base); receipt.pop(field); path.write_text(json.dumps(receipt))
                    item = failure_summary([path], 'a' * 40)['receipts'][0]
                    self.assertEqual('INVALID', item['state']); self.assertNotIn('tests', item)
            for change in ({'sourceSha': 'b' * 40}, {'classes': ['UnknownTest']},
                           {'classes': ['FreezeReuseThemeHttpE2eIT'] * 2}, {'profile': 'vps'}):
                path.write_text(json.dumps(dict(base, **change)))
                with self.assertRaises(ValueError): failure_summary([path], 'a' * 40)
            receipt = dict(base, state=self.marker, token=self.marker, diagnostic={'message': self.marker})
            path.write_text(json.dumps(receipt)); result = failure_summary([path], 'a' * 40)
            self.assertEqual('UNKNOWN', result['receipts'][0]['state'])
            self.assertNotIn(self.marker, json.dumps(result))
            path.write_text(json.dumps(base))
            with self.assertRaises(ValueError): failure_summary([path, path], 'a' * 40)

    def test_actual_case_frame_survives_two_helpers_in_safe_failed_xml_projection(self):
        simple = 'GenericGameplayHttpE2eIT'
        method = 'realHttpUsesTheProductionControllerAndRejectsAnonymousState'
        source = (ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it' / (simple + '.java')).read_text()
        line = source[:source.index('@Test void ' + method)].count('\n') + 2
        helpers = [source[:source.index('assertEquals(200, response.path("code").asInt()')].count('\n') + 1,
                   source[:source.index('private JsonNode success(JsonNode response)')].count('\n') + 1]
        frame = simple + '.java:' + str(line)
        trace = self.marker + ''.join('\n at ' + PACKAGE + simple + '.' + name + '(' + simple + '.java:' + str(number) + ')'
                                      for name, number in [('appliedResponseData', helpers[0]), ('success', helpers[1]),
                                                           (method, line)])
        trace += '\n at com.chengyinhub.' + self.marker + '.leak(' + self.marker + '.java:1)'
        self.assertEqual(frame, reviewed_case_call_frame(simple, method, trace, source))
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); reports = root / 'reports'; reports.mkdir(); public = root / 'public'
            schema = root / 'schema.json'; files = [{'path': 'fixture_' + str(i)} for i in range(101)]
            schema.write_text(json.dumps({'mode': 'SUCCEEDED', 'database': 'cy_e2e_w1_http_123_456',
                                          'files': files, 'completed': [r['path'] for r in files]}))
            xml = HttpReceiptTest().sample(simple); xml.set('failures', '1')
            case = next(c for c in xml.findall('testcase') if c.get('name') == method)
            ET.SubElement(case, 'failure', message=json.dumps({'code': 500, 'msg': self.marker})).text = trace
            ET.ElementTree(xml).write(reports / ('TEST-' + PACKAGE + simple + '.xml'))
            env = dict(os.environ, SOURCE_SHA='a' * 40, CHENGYIN_HTTP_E2E_PROFILE='github-hosted',
                       CHENGYIN_HTTP_E2E_DATABASE='cy_e2e_w1_http_123_456', CHENGYIN_HTTP_E2E_SCHEMA_REPORT=str(schema))
            run = subprocess.run([sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'verify', '--classes', simple,
                                  '--reports', str(reports), '--output', str(public)], env=env,
                                 capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            failure = json.loads((public / 'xml-failure.json').read_text())
            self.assertEqual({method: frame}, failure['caseCallFrames'])
            self.assertEqual([simple + '.java:' + str(n) for n in helpers] + [frame], failure['caseSourceFrames'][method])
            self.assertEqual(simple + '.java:' + str(helpers[0]), failure['sourceFrame'])
            self.assertEqual(simple + '.java:' + str(helpers[1]), failure['callerFrame'])
            self.assertEqual(['UNKNOWN'], failure['messageCategories'])
            self.assertEqual([500], failure['responseCodes']); self.assertEqual(26, failure['testcaseCount'])
            self.assertNotIn(self.marker, json.dumps(failure) + run.stderr + run.stdout)
            self.assertFalse((public / ('TEST-' + PACKAGE + simple + '.xml')).exists())

    def test_case_frames_require_reviewed_method_exact_class_and_local_source_span(self):
        simple = 'GenericGameplayHttpE2eIT'; canonical = 'r5HttpTypeInUnlimitedAndTimerDeadlineSurviveReentry(boolean)[1]'
        method = canonical.split('(', 1)[0]
        source = (ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it' / (simple + '.java')).read_text()
        line = source[:source.index('void ' + method + '(')].count('\n') + 2
        def frame(name=method, number=line, owner=PACKAGE + simple, filename=simple + '.java'):
            return ' at ' + owner + '.' + name + '(' + filename + ':' + str(number) + ')'
        self.assertEqual(simple + '.java:' + str(line), reviewed_case_call_frame(simple, canonical, frame(), source))
        for trace in [frame(number=279), frame(number=len(source.splitlines())), frame(number=9999999),
                      frame(owner='com.chengyinhub.external.' + simple), frame(name='success'), frame(filename='Unknown.java')]:
            with self.subTest(trace=trace): self.assertIsNone(reviewed_case_call_frame(simple, canonical, trace, source))
        self.assertIsNone(reviewed_case_call_frame(simple, 'unknownMethod', frame(), source))
        # Unsupported formatting cannot be taken as a source span proof.
        changed = source.replace('void ' + method + '(', 'public void ' + method + '(')
        self.assertIsNone(reviewed_case_call_frame(simple, canonical, frame(), changed))


class SourceContextAndPartialScopeTest(unittest.TestCase):
    marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'

    def context_line(self, stage='PLAYER_INITIAL_STATE', endpoint='THEME_STATE'):
        return '\tSuppressed: java.lang.AssertionError: HTTP_SOURCE_CONTEXT stage=' + stage + ' endpoint=' + endpoint

    def test_suppressed_context_accepts_only_fixed_enums_and_conflicts_are_unknown(self):
        for stage in HTTP_SOURCE_STAGES:
            for endpoint in HTTP_SOURCE_ENDPOINTS:
                self.assertEqual({'stage': stage, 'endpoint': endpoint}, suppressed_source_context(self.context_line(stage, endpoint)))
        line = self.context_line()
        expected = {'stage': 'PLAYER_INITIAL_STATE', 'endpoint': 'THEME_STATE'}
        self.assertEqual(expected, suppressed_source_context(line + '\n' + line))
        for text in [self.context_line(self.marker), self.context_line(endpoint=self.marker),
                     line + self.marker, self.marker + line, line + ' ', line.replace('AssertionError', 'Exception'),
                     json.dumps({'msg': line}), json.dumps({'msg': line}, indent=2),
                     line + '\n' + self.context_line('TEST_BODY', 'OTHER'),
                     line + '\n' + self.context_line(endpoint=self.marker)]:
            with self.subTest(text=text):
                actual = suppressed_source_context(text)
                self.assertEqual('UNKNOWN', actual); self.assertNotIn(self.marker, json.dumps(actual))
        self.assertIsNone(suppressed_source_context('No source-context assertion'))

    def test_context_projection_requires_original_known_case_and_never_publishes_dynamic_text(self):
        simple = 'GenericGameplayHttpE2eIT'; method = 'realHttpUsesTheProductionControllerAndRejectsAnonymousState'
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); reports = root / 'reports'; reports.mkdir(); public = root / 'public'
            schema = root / 'schema.json'; files = [{'path': 'fixture_' + str(i)} for i in range(101)]
            schema.write_text(json.dumps({'mode': 'SUCCEEDED', 'database': 'cy_e2e_w1_http_123_456',
                                          'files': files, 'completed': [r['path'] for r in files]}))
            env = dict(os.environ, SOURCE_SHA='a' * 40, CHENGYIN_HTTP_E2E_PROFILE='github-hosted',
                       CHENGYIN_HTTP_E2E_DATABASE='cy_e2e_w1_http_123_456', CHENGYIN_HTTP_E2E_SCHEMA_REPORT=str(schema))
            for mutation in ('valid', 'conflict', 'dynamic-json', 'unknown-case'):
                xml = HttpReceiptTest().sample(simple); xml.set('failures', '1')
                case = next(c for c in xml.findall('testcase') if c.get('name') == method)
                text = self.marker + '\n' + self.context_line()
                if mutation == 'conflict':text += '\n' + self.context_line('TEST_BODY', 'OTHER')
                elif mutation == 'dynamic-json':text = json.dumps({'msg': self.context_line(), 'token': self.marker})
                elif mutation == 'unknown-case':case.set('name', self.marker)
                ET.SubElement(case, 'failure', message=json.dumps({'code': 500, 'msg': self.marker})).text = text
                ET.ElementTree(xml).write(reports / ('TEST-' + PACKAGE + simple + '.xml'))
                run = subprocess.run([sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'verify', '--classes', simple,
                                      '--reports', str(reports), '--output', str(public)], env=env,
                                     capture_output=True, text=True, timeout=10)
                self.assertNotEqual(0, run.returncode)
                failure = json.loads((public / 'xml-failure.json').read_text())
                if mutation == 'unknown-case':
                    self.assertNotIn('sourceContext', failure); self.assertNotIn('caseSourceContexts', failure)
                else:
                    context = {'stage': 'PLAYER_INITIAL_STATE', 'endpoint': 'THEME_STATE'} if mutation == 'valid' else 'UNKNOWN'
                    self.assertEqual(context, failure['sourceContext'])
                    self.assertEqual({method: context}, failure['caseSourceContexts'])
                    self.assertEqual(['UNKNOWN'], failure['messageCategories'])
                self.assertNotIn(self.marker, json.dumps(failure) + run.stderr + run.stdout)

    def test_generic_scope_is_strict_partial_even_when_twenty_six_cases_pass(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); path = root / 'receipt.json'
            base = MixedSummaryAndCaseFrameTest().passed('GenericGameplayHttpE2eIT')
            path.write_text(json.dumps(base)); result = scoped_summary([path], 'a' * 40, 'generic-only')
            self.assertEqual('PASS_PARTIAL_DIAGNOSTIC_SCOPE', result['state'])
            self.assertEqual('PARTIAL_DIAGNOSTIC_SCOPE', result['scope']); self.assertEqual(26, result['tests'])
            self.assertEqual({'GenericGameplayHttpE2eIT': 26}, result['classes'])
            self.assertFalse(result['completeHttpSuiteAccepted']); self.assertFalse(result['businessAccepted'])
            self.assertFalse(result['migrationAccepted'])
            with self.assertRaises(ValueError): scoped_summary([path], 'a' * 40, 'all-three')
            for change in [{'state': 'FAIL'}, {'xmlVerified': False}, {'fixtureReportVerified': False},
                           {'tests': 36}, {'javaExit': 98}, {'cleanup': {}}, {'sourceSha': 'b' * 40},
                           {'classes': ['FreezeGameHttpE2eIT']}, {'classes': ['GenericGameplayHttpE2eIT'] * 2}]:
                path.write_text(json.dumps(dict(base, **change)))
                with self.assertRaises(ValueError): scoped_summary([path], 'a' * 40, 'generic-only')
            path.write_text(json.dumps(base))
            with self.assertRaises(ValueError): scoped_summary([path, path], 'a' * 40, 'generic-only')
            with self.assertRaises(ValueError): scoped_summary([path], 'a' * 40, self.marker)

    def test_partial_cli_cannot_accept_extra_classes_or_borrow_another_source(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d); path = root / 'receipt.json'; output = root / 'summary.json'
            base = MixedSummaryAndCaseFrameTest().passed('GenericGameplayHttpE2eIT')
            command = [sys.executable, str(ROOT / 'ci/test_http_e2e_entry.py'), 'summarize', '--receipts', str(root),
                       '--source', 'a' * 40, '--scope', 'generic-only', '--output', str(output)]
            for change in [{}, {'state': 'FAIL', 'xmlVerified': False}]:
                path.write_text(json.dumps(dict(base, **change)))
                run = subprocess.run(command, capture_output=True, text=True, timeout=10)
                self.assertEqual(0 if not change else 1, run.returncode)
                result = json.loads(output.read_text()); self.assertEqual('PARTIAL_DIAGNOSTIC_SCOPE', result['scope'])
                self.assertEqual('PASS_PARTIAL_DIAGNOSTIC_SCOPE' if not change else 'FAIL_HTTP_FIXTURE_ONLY', result['state'])
            extra = root / 'extra'; extra.mkdir()
            (extra / 'receipt.json').write_text(json.dumps(MixedSummaryAndCaseFrameTest().passed('FreezeGameHttpE2eIT')))
            path.write_text(json.dumps(base)); output.unlink()
            run = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, run.returncode); self.assertFalse(output.exists())

    def test_workflow_choice_is_fixed_default_all_three_and_unknown_scope_stops_before_setup(self):
        workflow = (ROOT / '.github/workflows/gameplay-http-e2e.yml').read_text()
        self.assertRegex(workflow, r'http_scope:\n        description:[^\n]+\n        required: true\n        type: choice\n        default: all-three\n        options:\n          - all-three\n          - generic-only')
        matrix = re.search(r"test_class: \$\{\{ fromJSON\(inputs.http_scope == 'controlled-combination' && '([^']+)' \|\| inputs.http_scope == 'generic-only' && '([^']+)' \|\| '([^']+)'\) \}\}", workflow)
        self.assertIsNotNone(matrix)
        self.assertEqual([COMBINATION_SUITE], json.loads(matrix[1])); self.assertEqual(['GenericGameplayHttpE2eIT'], json.loads(matrix[2])); self.assertEqual(list(EXPECTED), json.loads(matrix[3]))
        self.assertEqual(2, workflow.count("HTTP_SCOPE: ${{ inputs.http_scope || 'all-three' }}"))
        self.assertIn('--scope "$HTTP_SCOPE"', workflow)
        validation = workflow.split('      - name: Validate exact source input\n        run: |\n', 1)[1].split('      - uses:', 1)[0]
        for scope in ['all-three', 'generic-only', 'controlled-combination', self.marker, 'FreezeGameHttpE2eIT', '']:
            run = subprocess.run(['bash', '-c', validation], env=dict(os.environ, SOURCE_SHA='a' * 40, HTTP_SCOPE=scope),
                                 capture_output=True, text=True, timeout=5)
            self.assertEqual(0 if scope in ('all-three', 'generic-only', 'controlled-combination') else 2, run.returncode)
        called = workflow.split('  workflow_call:', 1)[1].split('permissions:', 1)[0]
        self.assertNotIn('http_scope:', called)  # Existing reusable caller remains default full HTTP36.


class ProfileTest(unittest.TestCase):
    def test_schema_guard_honest_hosted_or_original_vps_only(self):
        spec=importlib.util.spec_from_file_location('http_schema',TOOLS/'generic-gameplay-fresh-schema.py'); module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        valid={'GITHUB_ACTIONS':'true','GITHUB_REPOSITORY':'liboyang42-cpu/hengyin','RUNNER_ENVIRONMENT':'github-hosted'}
        self.assertIsNone(module.execution_profile_error('github-hosted','linux',valid))
        self.assertIsNone(module.execution_profile_error('vps','linux',{'CHENGYIN_VPS_TEST_RUN':'1'}))
        for key in valid:
            bad=dict(valid);bad.pop(key);self.assertIsNotNone(module.execution_profile_error('github-hosted','linux',bad))
        for platform,env in [('darwin',valid),('linux',dict(valid,CHENGYIN_VPS_TEST_RUN='1')),('linux',dict(valid,RUNNER_ENVIRONMENT='self-hosted')),('linux',dict(valid,GITHUB_REPOSITORY='liboyang42-cpu/chengyin'))]:
            self.assertIsNotNone(module.execution_profile_error('github-hosted',platform,env))

    def test_shell_rejects_identity_and_selector_before_heavy_tools(self):
        valid={'GITHUB_ACTIONS':'true','GITHUB_REPOSITORY':'liboyang42-cpu/hengyin','RUNNER_ENVIRONMENT':'github-hosted','CHENGYIN_HTTP_E2E_PROFILE':'github-hosted'}
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)
            for name in ['mvn','java','mysql','redis-server','redis-cli','python3','rg']:
                p=root/name;p.write_text('#!/bin/sh\necho HEAVY >&2\nexit 97\n');p.chmod(0o700)
            for system,settings,expected in [('Linux',valid,0),('Darwin',valid,2),('Linux',dict(valid,RUNNER_ENVIRONMENT='self-hosted'),2),('Linux',dict(valid,CHENGYIN_VPS_TEST_RUN='1'),2),('Linux',dict(valid,GITHUB_REPOSITORY='other/repo'),2),('Linux',dict(valid,CHENGYIN_HTTP_E2E_TESTS='OtherTest'),2)]:
                p=root/'uname';p.write_text('#!/bin/sh\necho '+system+'\n');p.chmod(0o700)
                env={k:v for k,v in os.environ.items() if not k.startswith(('CHENGYIN_','GITHUB_','RUNNER_'))};env.update(settings,PATH=str(root)+':/usr/bin:/bin')
                result=subprocess.run(['/bin/bash',str(TOOLS/'generic-gameplay-http-e2e.sh'),'--check-profile'],env=env,text=True,capture_output=True,timeout=5)
                self.assertEqual(expected,result.returncode,result.stderr);self.assertNotIn('HEAVY',result.stderr)


if __name__ == '__main__':
    if len(sys.argv)>1 and sys.argv[1] in ('verify','finalize','summarize'): cli()
    else: unittest.main()
