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
        '本局暂停或由人工账本接管，当前只能读取': 'GAME_RUN_PAUSED_OR_MANUAL',
        '本队已完成最后章节，资产与回执可继续查看': 'GAME_ROUTE_FINISHED',
        '仅本场现有队长可消耗队伍物资': 'GAME_RESOURCE_CAPTAIN_REQUIRED',
        '检定不属于当前实例': 'GAME_CHECK_INSTANCE_MISMATCH',
        '检定未启用结果规则': 'GAME_CHECK_OUTCOME_RULES_MISSING',
        '本局已更新': 'GAME_STATE_CONFLICT',
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


def selection(value):
    names = value.split(',')
    if not names or len(names) != len(set(names)) or any(n not in EXPECTED for n in names):
        raise ValueError('Select only the exact reviewed HTTP classes, without duplicates')
    return names


def reviewed_case_identities(simple):
    source = (ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it' / (simple + '.java')).read_text()
    plain = re.findall(r'(?m)^\s*@Test(?:\s+@Order\([1-9][0-9]*\))?\s+void\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(\s*\)', source)
    plain_count = {'GenericGameplayHttpE2eIT': 15, 'FreezeGameHttpE2eIT': 8, 'FreezeReuseThemeHttpE2eIT': 2}
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
    if len(set(aliases.values())) != EXPECTED[simple]:
        raise ValueError('Reviewed expanded HTTP count changed')
    return aliases


def verified_xml(path, simple):
    root = ET.parse(path).getroot()
    cases = root.findall('testcase')
    expected = EXPECTED[simple]
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


def validate_final(receipt, source=None):
    names = selection(','.join(receipt.get('classes', [])))
    if source and receipt.get('sourceSha') != source:
        raise ValueError('Wrong source receipt')
    if not re.fullmatch('[0-9a-f]{40}', receipt.get('sourceSha', '')):
        raise ValueError('Missing exact source SHA')
    if receipt.get('profile') not in ('vps', 'github-hosted') or not re.fullmatch(r'cy_e2e_w1_http_[0-9_]+', receipt.get('database', '')):
        raise ValueError('Missing execution profile / owned schema identity')
    if receipt.get('fixtureReportVerified') is not True:
        raise ValueError('Missing genuine completed fixture report')
    if receipt.get('state') != 'PASS' or receipt.get('javaExit') != 0:
        raise ValueError('Java/verification did not pass')
    if receipt.get('tests') != sum(EXPECTED[n] for n in names) or receipt.get('xmlVerified') is not True:
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
    a = p.parse_args()
    if a.command == 'summarize':
        result = summarize(list(a.receipts.rglob('receipt.json')), a.source)
        a.output.parent.mkdir(parents=True, exist_ok=True); a.output.write_text(json.dumps(result, indent=2) + '\n'); return
    a.output.mkdir(parents=True, exist_ok=True)
    dest = a.output / 'receipt.json'
    if a.command == 'verify':
        names = selection(a.classes)
        receipt = {'sourceSha': os.environ.get('SOURCE_SHA', ''), 'profile': os.environ.get('CHENGYIN_HTTP_E2E_PROFILE', 'vps'),
                   'database': os.environ.get('CHENGYIN_HTTP_E2E_DATABASE', ''), 'classes': names,
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
                                for key in ('tests', 'failures', 'errors', 'skipped'):
                                    value = failed.get(key, '')
                                    if re.fullmatch(r'[0-9]{1,6}', value):
                                        failure[key] = int(value)
                                source_dir = ROOT / 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it'
                                source = (source_dir / (n + '.java')).read_text()
                                known_cases = reviewed_case_identities(n)
                                source_lines = {name + '.java': len((source_dir / (name + '.java')).read_text().splitlines())
                                                for name in EXPECTED}
                                cases = failed.findall('testcase')
                                failure.update(testcaseCount=len(cases), uniqueMethodCount=len({case.get('name') for case in cases}),
                                               failureNodeCount=sum(len(case.findall('failure')) for case in cases),
                                               errorNodeCount=sum(len(case.findall('error')) for case in cases),
                                               retryNodeCount=sum(len(case.findall(tag)) for case in cases
                                                                  for tag in ('rerunFailure', 'rerunError', 'flakyFailure', 'flakyError')))
                                methods, codes, case_categories, case_frames = [], set(), {}, {}
                                for case in cases:
                                    errors = case.findall('failure') + case.findall('error')
                                    method = case.get('name', '')
                                    if (errors and case.get('classname') == PACKAGE + n and
                                            method in known_cases):
                                        methods.append(known_cases[method])
                                        categories = case_categories.setdefault(known_cases[method], set())
                                        for error in errors:
                                            text = error.get('message', '') + '\n' + (error.text or '')
                                            categories.update(fixed_http_failure_categories(text))
                                            codes.update(int(code) for code in re.findall(r'"code"\s*:\s*([1-5][0-9]{2}|601)(?=\s*[,}])', text))
                                        for error in errors:
                                            frames = []
                                            for filename, line in re.findall(r'\bat com\.chengyinhub\.[A-Za-z0-9_.$]+\(([A-Za-z_$][A-Za-z0-9_$]*\.java):([0-9]{1,7})\)', error.text or ''):
                                                if filename in source_lines and 1 <= int(line) <= source_lines[filename]:
                                                    frame = filename + ':' + str(int(line))
                                                    if frame not in frames:
                                                        frames.append(frame)
                                                    if len(frames) == 8:
                                                        break
                                            if frames:
                                                case_frames.setdefault(known_cases[method], frames)
                                                if 'sourceFrame' not in failure:
                                                    failure['sourceFrame'] = frames[0]
                                                    if len(frames) >= 2:
                                                        failure['callerFrame'] = frames[1]
                                                break
                                failure['caseSourceFrames'] = dict(sorted(case_frames.items()))
                                failure['failedMethods'] = list(dict.fromkeys(methods))[:EXPECTED[n]]
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
            receipt.update(tests=sum(EXPECTED[n] for n in names), xmlVerified=True)
        finally:
            dest.write_text(json.dumps(receipt, indent=2) + '\n')
    else:
        receipt = json.loads(dest.read_text()) if dest.exists() else {'state': 'FAIL', 'xmlVerified': False}
        receipt.update(javaExit=a.java_exit, cleanup={'mysqlAbsent': a.mysql_clean == 'true',
                                                   'redisStoppedAndPortFree': a.redis_clean == 'true'})
        receipt['state'] = 'PASS' if (a.java_exit == 0 and receipt.get('xmlVerified') is True and
                                     all(receipt['cleanup'].values())) else 'FAIL'
        dest.write_text(json.dumps(receipt, indent=2) + '\n')
        if receipt['state'] != 'PASS':
            original = os.environ.get('CHENGYIN_HTTP_E2E_ORIGINAL_EXIT', '')
            diagnostic = safe_diagnostic(pathlib.Path(os.environ.get('CHENGYIN_HTTP_E2E_SCHEMA_REPORT', '')),
                                         os.environ.get('CHENGYIN_HTTP_E2E_STAGE', ''),
                                         int(original) if re.fullmatch(r'[0-9]{1,3}', original) else None, a.java_exit)
            if diagnostic['stage'] == 'xml-verify' and a.java_exit != 0:
                diagnostic['mavenFailure'] = safe_maven_failure(a.output.parent / 'run.log')
            (a.output / 'diagnostic.json').write_text(json.dumps(diagnostic, sort_keys=True) + '\n')
            print('HTTP_FIXTURE_DIAGNOSTIC ' + json.dumps(diagnostic, sort_keys=True), file=sys.stderr)
        validate_final(receipt)


class HttpReceiptTest(unittest.TestCase):
    def sample(self, simple='FreezeReuseThemeHttpE2eIT'):
        root = ET.Element('testsuite', name=PACKAGE + simple, tests=str(EXPECTED[simple]), failures='0', errors='0', skipped='0')
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
        self.assertIn('test_class: [GenericGameplayHttpE2eIT, FreezeGameHttpE2eIT, FreezeReuseThemeHttpE2eIT]',workflow)
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
            http = parent.split('\n  http-fixtures:\n', 1)[1]
            self.assertIn("    if: github.repository == 'liboyang42-cpu/hengyin' && !inputs.run_http_fixtures\n", jar)
            self.assertIn("    if: github.repository == 'liboyang42-cpu/hengyin' && github.event_name == 'workflow_dispatch' && inputs.run_http_fixtures\n", http)
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
            (caller.replace(" && inputs.run_http_fixtures\n", "\n"), callee),
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
    def test_fixed_http_categories_are_exact_public_literals_not_dynamic_messages(self):
        marker = 'SYNTHETIC_PRIVATE_NEVER_PUBLISH'
        count = 0
        for path, messages in FIXED_HTTP_MESSAGE_SOURCES.items():
            source = (ROOT / path).read_text()
            for message, category in messages.items():
                self.assertIn(json.dumps(message, ensure_ascii=False), source)
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
