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
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'chengyinhub-admin/src/test/tools'
EXPECTED = {'GenericGameplayHttpE2eIT': 14, 'FreezeGameHttpE2eIT': 8,
            'FreezeReuseThemeHttpE2eIT': 2}
PACKAGE = 'com.chengyinhub.business.it.'


def selection(value):
    names = value.split(',')
    if not names or len(names) != len(set(names)) or any(n not in EXPECTED for n in names):
        raise ValueError('Select only the exact reviewed HTTP classes, without duplicates')
    return names


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
    identities = [(c.get('classname'), c.get('name')) for c in cases]
    if len(set(identities)) != expected or any(c != PACKAGE + simple or not re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*', n or '') for c, n in identities):
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
            'tests': 24, 'failures': 0, 'errors': 0, 'skipped': 0,
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
                safe = verified_xml(original, n)
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
            (a.output / 'diagnostic.json').write_text(json.dumps(diagnostic, sort_keys=True) + '\n')
            print('HTTP_FIXTURE_DIAGNOSTIC ' + json.dumps(diagnostic, sort_keys=True), file=sys.stderr)
        validate_final(receipt)


class HttpReceiptTest(unittest.TestCase):
    def sample(self, simple='FreezeReuseThemeHttpE2eIT'):
        root = ET.Element('testsuite', name=PACKAGE + simple, tests=str(EXPECTED[simple]), failures='0', errors='0', skipped='0')
        for i in range(EXPECTED[simple]): ET.SubElement(root, 'testcase', classname=PACKAGE + simple, name='case' + str(i))
        return root

    def verify(self, root):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / 'test.xml'; ET.ElementTree(root).write(p)
            return verified_xml(p, 'FreezeReuseThemeHttpE2eIT')

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
            elif changed == 'duplicate': root.findall('testcase')[1].set('name', 'case0')
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
            self.assertEqual(24,summarize(paths,'a'*40)['tests'])
            for bad in [paths[:-1],paths+[paths[0]]]:
                with self.assertRaises(ValueError):summarize(bad,'a'*40)
            with self.assertRaises(ValueError):summarize(paths,'b'*40)


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
