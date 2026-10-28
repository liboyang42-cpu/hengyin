"""SOURCE entry controls; synthetic XML/receipts here do not establish executed business cases."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
from ci import test_controlled_combination_probe as controls_source
from ci import test_combined_http_budget as budget_controls_source

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('controlled_http_entry', ROOT / 'ci/test_http_e2e_entry.py')
entry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entry)
SUITE = 'CrossDomainCombinedLoadMySqlIT'


class ControlledCombinationEntryControls(unittest.TestCase):
    def profile_environment(self, directory, env):
        for name in ('mvn', 'java', 'mysql', 'redis-server', 'redis-cli', 'python3', 'rg'):
            tool = directory / name
            tool.write_text('#!/bin/sh\necho HEAVY >&2\nexit 97\n'); tool.chmod(0o700)
        uname = directory / 'uname'
        uname.write_text('#!/bin/sh\necho Linux\n'); uname.chmod(0o700)
        return dict(env, PATH=str(directory) + ':/usr/bin:/bin')

    def test_original_default_class_set_and_thirty_six_count_remain_exact(self):
        self.assertEqual({'GenericGameplayHttpE2eIT': 26, 'FreezeGameHttpE2eIT': 8,
                          'FreezeReuseThemeHttpE2eIT': 2}, entry.EXPECTED)
        self.assertEqual(36, sum(entry.EXPECTED.values()))
        self.assertEqual([SUITE], entry.selection(SUITE)); self.assertEqual(1, entry.reviewed_count(SUITE))
        self.assertEqual(1, len(entry.reviewed_case_identities(SUITE)))
        for value in (SUITE + ',GenericGameplayHttpE2eIT', SUITE + ',' + SUITE, SUITE + '$Nested', '', 'FutureSuite'):
            with self.subTest(value=value), self.assertRaises(ValueError): entry.selection(value)

    def xml(self, path):
        method = next(iter(entry.reviewed_case_identities(SUITE)))
        root = ET.Element('testsuite', name=entry.PACKAGE + SUITE, tests='1', failures='0', errors='0', skipped='0')
        ET.SubElement(root, 'testcase', classname=entry.PACKAGE + SUITE, name=method)
        ET.ElementTree(root).write(path); return root

    def test_new_xml_uses_the_existing_exact_method_skip_error_and_duplicate_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'suite.xml'; self.xml(path)
            self.assertEqual('1', entry.verified_xml(path, SUITE).get('tests'))
            for kind in ('skipped', 'error', 'failure'):
                root = self.xml(path); ET.SubElement(root.find('testcase'), kind)
                ET.ElementTree(root).write(path)
                with self.subTest(kind=kind), self.assertRaises(ValueError): entry.verified_xml(path, SUITE)
            root = self.xml(path); root.find('testcase').set('classname', entry.PACKAGE + 'Foreign')
            ET.ElementTree(root).write(path)
            with self.assertRaises(ValueError): entry.verified_xml(path, SUITE)
            root = self.xml(path); root.append(copy.deepcopy(root.find('testcase'))); ET.ElementTree(root).write(path)
            with self.assertRaises(ValueError): entry.verified_xml(path, SUITE)

    def scoped_fixture(self, directory):
        report, receipt = controls_source.ControlledCombinationControls().fixture()
        receipt.update(jarSha256='b' * 64, combinationEvidence='VERIFIED_SCOPED_OBSERVATION_ONLY')
        path = directory / 'receipt.json'; path.write_text(json.dumps(receipt))
        from ci.test_controlled_combination_probe import probe
        safe = probe.controlled_combination_projection(report, receipt, 'a' * 40, '123', '2', 'b' * 64)
        (directory / 'controlled-combination.json').write_text(json.dumps(safe))
        budget = budget_controls_source.CombinedBudgetControls().subject_report()
        budget.update(sourceSha=receipt['sourceSha'], runId=report['runId'], attempt=report['runAttempt'],
                      database=receipt['database'])
        budget['mysqlBootstrap'].update({key: budget[key] for key in ('sourceSha', 'runId', 'attempt', 'class')})
        budget['source']['artifactJarSha256'] = receipt['jarSha256']
        budget['javaProcesses'][1]['namespacePid'] = report['javaPid']
        (directory / 'combined-http-budget.json').write_text(json.dumps(budget))
        storage = {key: budget[key] for key in ('sourceSha', 'runId', 'attempt', 'class', 'mysql')}
        storage.update(mysqlAbsent=True, networkAbsent=True, beforeCleanup={'Running': True, 'OOMKilled': False})
        (directory / 'combined-http-storage-cleanup.json').write_text(json.dumps(storage))
        return path, receipt

    def test_entry_budget_precedes_outer_storage_cleanup_without_skipping_subject_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path, receipt = self.scoped_fixture(root)
            report = json.loads((root / 'controlled-combination.json').read_text())
            (root / 'combined-http-storage-cleanup.json').unlink()
            entry.verify_combination_budget(root, report, receipt, after_storage_cleanup=False)
            with self.assertRaisesRegex(ValueError, 'BOUNDED_STORAGE_PROOF_REQUIRED'):
                entry.verify_combination_budget(root, report, receipt, after_storage_cleanup=True)
            report['javaPid'] += 1
            with self.assertRaisesRegex(ValueError, 'ACTUAL_JUNIT_SUBJECT_PID_NOT_PROVEN'):
                entry.verify_combination_budget(root, report, receipt, after_storage_cleanup=False)

    def test_summary_rejects_missing_budget_foreign_storage_or_nonzero_original_exit(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(os.environ, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2'):
            root = Path(directory)
            for mutation in ('missing', 'foreign-storage', 'nonzero-exit'):
                path, receipt = self.scoped_fixture(root)
                if mutation == 'missing':
                    (root / 'combined-http-budget.json').unlink()
                else:
                    target = root / ('combined-http-storage-cleanup.json' if mutation == 'foreign-storage'
                                     else 'combined-http-budget.json')
                    value = json.loads(target.read_text())
                    value['sourceSha' if mutation == 'foreign-storage' else 'originalExit'] = 'c' * 40 if mutation == 'foreign-storage' else 1
                    target.write_text(json.dumps(value))
                with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                    entry.scoped_summary([path], 'a' * 40, 'controlled-combination')

    def test_no_receipt_cli_writes_failed_scope_without_borrowing_old_classes_or_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); receipts = root / 'receipts'; receipts.mkdir()
            output = root / 'summary.json'
            args = ['entry', 'summarize', '--receipts', str(receipts), '--source', 'a' * 40,
                    '--scope', 'controlled-combination', '--output', str(output)]
            with mock.patch('sys.argv', args), self.assertRaises(ValueError):
                entry.cli()
            result = json.loads(output.read_text())
            self.assertEqual('FAIL_HTTP_FIXTURE_ONLY', result['state'])
            self.assertEqual([SUITE], result['missingClasses']); self.assertEqual([], result['receipts'])
            self.assertEqual('NO_VERIFIED_HTTP_RECEIPT', result['executionEvidence'])
            self.assertFalse(result['cleanupAllVerified']); self.assertNotIn('tests', result)
            self.assertFalse(result['wholeSystemCapacityAccepted']); self.assertFalse(result['q4q8Complete'])
            self.assertFalse(result['businessAccepted']); self.assertFalse(result['migrationAccepted'])
            output.unlink()
            _, receipt = controls_source.ControlledCombinationControls().fixture()
            receipt['classes'] = ['GenericGameplayHttpE2eIT']
            (receipts / 'receipt.json').write_text(json.dumps(receipt))
            with mock.patch('sys.argv', args), self.assertRaises(ValueError):
                entry.cli()
            self.assertFalse(output.exists())

    def test_explicit_partial_summary_never_borrows_original_three_suites(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(os.environ, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2'):
            path, receipt = self.scoped_fixture(Path(directory))
            result = entry.scoped_summary([path], 'a' * 40, 'controlled-combination')
            self.assertEqual('PARTIAL_CONTROLLED_COMBINATION_SCOPE', result['scope'])
            self.assertEqual({SUITE: 1}, result['classes']); self.assertFalse(result['completeHttpSuiteAccepted'])
            self.assertFalse(result['wholeSystemCapacityAccepted']); self.assertFalse(result['q4q8Complete'])
            with self.assertRaises(ValueError): entry.scoped_summary([path], 'a' * 40, 'all-three')
            with self.assertRaises(ValueError): entry.scoped_summary([path, path], 'a' * 40, 'controlled-combination')
            receipt['classes'] = ['GenericGameplayHttpE2eIT']; path.write_text(json.dumps(receipt))
            with self.assertRaises(ValueError): entry.scoped_summary([path], 'a' * 40, 'controlled-combination')

    def test_unknown_cleanup_missing_observation_and_foreign_run_do_not_promote(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.dict(os.environ, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2'):
            root = Path(directory); path, receipt = self.scoped_fixture(root)
            receipt['cleanup']['mysqlAbsent'] = None; path.write_text(json.dumps(receipt))
            with self.assertRaises(ValueError): entry.scoped_summary([path], 'a' * 40, 'controlled-combination')
            path, receipt = self.scoped_fixture(root); (root / 'controlled-combination.json').unlink()
            with self.assertRaises(ValueError): entry.scoped_summary([path], 'a' * 40, 'controlled-combination')
            self.scoped_fixture(root)
            with mock.patch.dict(os.environ, GITHUB_RUN_ATTEMPT='3'), self.assertRaises(ValueError):
                entry.scoped_summary([path], 'a' * 40, 'controlled-combination')

    def test_workflow_has_one_fixed_partial_choice_and_unchanged_default_matrix(self):
        workflow = (ROOT / '.github/workflows/gameplay-http-e2e.yml').read_text()
        matrix = re.search(r"test_class: \$\{\{ fromJSON\(inputs.http_scope == 'controlled-combination' && '([^']+)' \|\| inputs.http_scope == 'generic-only' && '([^']+)' \|\| '([^']+)'\) \}\}", workflow)
        self.assertIsNotNone(matrix)
        self.assertEqual([SUITE], json.loads(matrix[1])); self.assertEqual(['GenericGameplayHttpE2eIT'], json.loads(matrix[2]))
        self.assertEqual(list(entry.EXPECTED), json.loads(matrix[3]))
        self.assertIn('default: all-three', workflow); self.assertIn('max-parallel: 20', workflow)
        self.assertIn('PARTIAL controlled combination', workflow)
        self.assertIn('python3 -I ci/combined_http_budget.py warm-cache', workflow)
        warm = budget_controls_source.B.fixed_cache_command()[1]
        self.assertIn("'org.apache.maven.plugins:maven-dependency-plugin:3.10.0:get'", warm)
        self.assertIn("'-Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.6' '-Dtransitive=true'", warm)
        self.assertNotIn('http_scope:', workflow.split('  workflow_call:', 1)[1].split('permissions:', 1)[0])

    def test_shell_admits_only_exact_manual_hosted_single_class_without_starting_tools(self):
        script = ROOT / 'chengyinhub-admin/src/test/tools/generic-gameplay-http-e2e.sh'
        base = dict(os.environ, GITHUB_ACTIONS='true', GITHUB_EVENT_NAME='workflow_dispatch',
                    GITHUB_REPOSITORY='liboyang42-cpu/hengyin', RUNNER_ENVIRONMENT='github-hosted',
                    CHENGYIN_HTTP_E2E_PROFILE='github-hosted', CHENGYIN_HTTP_E2E_TESTS=SUITE,
                    HTTP_SCOPE='controlled-combination', CHENGYIN_HTTP_E2E_REDIS_PORT='6387')
        base.pop('CHENGYIN_VPS_TEST_RUN', None)
        with tempfile.TemporaryDirectory() as directory:
            base = self.profile_environment(Path(directory), base)
            for change, accepted in (({}, True), ({'GITHUB_EVENT_NAME': 'pull_request'}, False),
                                     ({'HTTP_SCOPE': 'all-three'}, False), ({'CHENGYIN_HTTP_E2E_TESTS': SUITE + ',GenericGameplayHttpE2eIT'}, False),
                                     ({'RUNNER_ENVIRONMENT': 'self-hosted'}, False)):
                result = subprocess.run(['/bin/bash', str(script), '--check-profile'], env=dict(base, **change),
                                        text=True, capture_output=True, timeout=5)
                self.assertEqual(0 if accepted else 2, result.returncode, result.stderr)
                self.assertNotIn('HEAVY', result.stderr)

    def test_shell_copies_each_explicit_http_redis_port_and_only_this_scopes_db_and_gate(self):
        script = ROOT / 'chengyinhub-admin/src/test/tools/generic-gameplay-http-e2e.sh'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); capture = root / 'exports'; hook = root / 'bash-env'
            hook.write_text('trap \'printf "%s\\n" "$CHENGYIN_AI_REDIS_IT_PORT/$CHENGYIN_AI_REDIS_IT_DATABASE/$B_PAYMENT_FACT_DDL_APPROVED" > "$F_CONFIG_CAPTURE"\' EXIT\n')
            for port in ('6387', '6391'):
                env = dict(os.environ, GITHUB_ACTIONS='true', GITHUB_EVENT_NAME='workflow_dispatch',
                           GITHUB_REPOSITORY='liboyang42-cpu/hengyin', RUNNER_ENVIRONMENT='github-hosted',
                           CHENGYIN_HTTP_E2E_PROFILE='github-hosted', CHENGYIN_HTTP_E2E_TESTS=SUITE,
                           HTTP_SCOPE='controlled-combination', CHENGYIN_HTTP_E2E_REDIS_PORT=port,
                           BASH_ENV=str(hook), F_CONFIG_CAPTURE=str(capture))
                env.pop('CHENGYIN_VPS_TEST_RUN', None)
                env = self.profile_environment(root, env)
                result = subprocess.run(['/bin/bash', str(script), '--check-profile'], env=env,
                                        text=True, capture_output=True, timeout=5)
                self.assertEqual(0, result.returncode); self.assertEqual(port + '/15/1', capture.read_text().strip())
                self.assertNotIn('HEAVY', result.stderr)


if __name__ == '__main__':
    unittest.main()
