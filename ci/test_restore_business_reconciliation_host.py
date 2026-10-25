#!/usr/bin/env python3
"""SOURCE controls for the resource wrapper; no Docker, DB or Java is launched here."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('restore_host', Path(__file__).with_name('restore_business_reconciliation_host.py'))
host = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(host)
SHA = 'a' * 40
SCOPE = 'b' * 32


class RestoreHostControls(unittest.TestCase):
    def environment(self):
        return {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
                'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                'GITHUB_JOB': 'isolated-restore-business-reconciliation',
                'CHENGYIN_CI_RESTORE_BUSINESS': 'true', 'GITHUB_SHA': SHA,
                'CHENGYIN_RESTORE_BUSINESS_COHORT': 'payment-gap'}

    def test_manual_exact_host_source_and_no_ambient_overrides(self):
        env = self.environment()
        host.hosted(env, SHA, 'unix:///var/run/docker.sock')
        for key in env:
            with self.subTest(key=key), self.assertRaises(ValueError):
                host.hosted(dict(env, **{key: 'foreign'}), SHA, 'unix:///var/run/docker.sock')
        for key in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
                    '_JAVA_OPTIONS', 'CLASSPATH', 'CHENGYIN_RESTORE_MODE'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                host.hosted(dict(env, **{key: 'foreign'}), SHA, 'unix:///var/run/docker.sock')
        with self.assertRaises(ValueError):
            host.hosted(env, SHA, 'ssh://foreign')
        with self.assertRaises(ValueError):
            host.hosted(dict(env, CHENGYIN_RESTORE_MODE=''), SHA, 'unix:///var/run/docker.sock')

    def test_classpath_uses_only_current_target_and_existing_maven_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            classes = root / 'chengyinhub-admin/target/test-classes'
            classes.mkdir(parents=True)
            main = root / 'chengyinhub-common/target/classes'
            main.mkdir(parents=True)
            self.assertEqual([str(classes), str(main)], host.classpath(root, '').split(os.pathsep))
            foreign = root / 'foreign.jar'
            foreign.touch()
            with self.assertRaises(ValueError):
                host.classpath(root, str(foreign))
            classes.rmdir()
            classes.symlink_to(main, target_is_directory=True)
            with self.assertRaises(ValueError):
                host.classpath(root, '')

    def test_cleanup_rechecks_exact_ids_labels_and_network_before_removal(self):
        with tempfile.TemporaryDirectory() as temp:
            worker = host.Host(Path(temp), Path(temp), Path(temp), SCOPE, 'payment-gap')
            worker.created = {'source': 'c' * 64, 'target': 'd' * 64, 'network': 'e' * 64}
            def row(kind, identity):
                side = 'source' if identity == 'c' * 64 else 'target'
                if kind == 'network':
                    return {'Id': 'e' * 64, 'Name': worker.name, 'Internal': True,
                            'Labels': {'chengyin.restore-business': SCOPE}}
                return {'Id': identity, 'Name': '/' + worker.name + '-' + side,
                        'Config': {'Image': host.IMAGE, 'Labels': {'chengyin.restore-business': SCOPE}},
                        'NetworkSettings': {'Networks': {worker.name: {}}}}
            replies = [row('container', 'd' * 64), None, row('container', 'c' * 64), None,
                       row('network', 'e' * 64), None]
            with mock.patch.object(host, 'inspect', side_effect=replies), mock.patch.object(worker, 'command') as run:
                self.assertEqual([], worker.cleanup())
            self.assertEqual([host.DOCKER + ['container', 'rm', '-f', 'd' * 64],
                              host.DOCKER + ['container', 'rm', '-f', 'c' * 64],
                              host.DOCKER + ['network', 'rm', 'e' * 64]],
                             [call.args[0] for call in run.call_args_list])
            bad = row('container', 'd' * 64)
            bad['Config']['Labels']['chengyin.restore-business'] = 'foreign'
            worker.created = {'target': 'd' * 64}
            with mock.patch.object(host, 'inspect', return_value=bad), mock.patch.object(worker, 'command') as run:
                self.assertEqual(['target'], worker.cleanup())
                run.assert_not_called()

    def test_finalize_runs_after_resource_cleanup_before_private_removal_and_keeps_first_failure(self):
        for original, cleanup, final_failure, expected in ((0, [], False, 0), (7, [], False, 7),
                                                           (0, ['source'], False, 1), (9, ['target'], True, 9)):
            with self.subTest(original=original, cleanup=cleanup), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); private = root / 'private'; private.mkdir(); output = root / 'out'; output.mkdir()
                worker = host.Host(root, private, output, SCOPE, 'refund-complete')
                worker.resources.write_text('{}'); (output / 'reconciliation.json').write_text('{}')
                order = []
                def remove():
                    order.append('resources-removed')
                    return cleanup
                def finalize(args, **kwargs):
                    self.assertEqual(['resources-removed'], order)
                    self.assertTrue(worker.resources.is_file())
                    self.assertIn('--original-exit', args)
                    self.assertEqual(str(expected), args[-1])
                    order.append('finalized')
                    if final_failure:
                        raise ValueError('fixed failure')
                with mock.patch.object(worker, 'cleanup', side_effect=remove), \
                     mock.patch.object(worker, 'command', side_effect=finalize), \
                     mock.patch.dict(os.environ, {'GITHUB_SHA': SHA}):
                    self.assertEqual(expected, worker.finish(original))
                self.assertEqual(['resources-removed', 'finalized'], order)
                self.assertFalse(private.exists())
                receipt = json.loads((output / 'host.json').read_text())
                self.assertEqual(original, receipt['originalExit'])
                self.assertEqual(expected, receipt['exitCode'])

    def test_missing_probe_receipt_never_turns_bootstrap_failure_into_success(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); private = root / 'private'; private.mkdir(); output = root / 'out'; output.mkdir()
            worker = host.Host(root, private, output, SCOPE, 'withdrawal-gap')
            with mock.patch.object(worker, 'cleanup', return_value=[]), mock.patch.object(worker, 'command') as run, \
                 mock.patch.dict(os.environ, {'GITHUB_SHA': SHA}):
                self.assertEqual(1, worker.finish(0))
                run.assert_not_called()
            self.assertFalse(json.loads((output / 'host.json').read_text())['probeFinalized'])

    def test_probe_normal_exit_and_timeout_signal_only_the_session_created_by_this_owner(self):
        for stage in ('normal', 'term', 'kill'):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as temp:
                root = Path(temp).resolve()
                worker = host.Host(root, root, root, SCOPE, 'voice-complete')
                child = mock.Mock(pid=123456)
                child.poll.return_value = 0 if stage == 'normal' else None
                expired = subprocess.TimeoutExpired('fixed-probe', 1050)
                child.wait.side_effect = [0] if stage == 'normal' else (
                    [expired, 143] if stage == 'term' else
                    [expired, subprocess.TimeoutExpired('fixed-probe', 15), 137])
                with mock.patch.object(host.subprocess, 'Popen', return_value=child) as launch, \
                     mock.patch.object(host.os, 'killpg') as terminate:
                    if stage == 'normal':
                        self.assertEqual(0, worker.run_probe())
                        terminate.assert_not_called()
                    else:
                        with self.assertRaises(subprocess.TimeoutExpired):
                            worker.run_probe()
                        expected = [mock.call(123456, signal.SIGTERM)]
                        if stage == 'kill':
                            expected.append(mock.call(123456, signal.SIGKILL))
                        self.assertEqual(expected, terminate.call_args_list)
                    args = launch.call_args.args[0]
                    self.assertEqual([host.sys.executable, '-I', str(host.PROBE), 'run'], args[:4])
                    self.assertTrue(launch.call_args.kwargs['start_new_session'])
                self.assertEqual(0o600, (root / 'probe.log').stat().st_mode & 0o777)

    def test_workflow_is_manual_only_and_never_exports_private_or_raw_evidence(self):
        source = (host.ROOT / '.github/workflows/isolated-restore-business-reconciliation.yml').read_text()
        self.assertIn('  workflow_dispatch:', source)
        self.assertNotIn('pull_request:', source)
        self.assertNotIn('  push:', source)
        self.assertIn('runs-on: ubuntu-24.04', source)
        self.assertIn('-DskipTests install', source)
        paths = source.split('          path: |\n', 1)[1].split('  restore-business-summary:\n', 1)[0].splitlines()
        self.assertEqual(['host.json', 'reconciliation.json', 'finalized.json'], [line.strip().rsplit('/', 1)[-1] for line in paths])
        self.assertNotIn('**', '\n'.join(paths))
        self.assertNotIn('CHENGYIN_RESTORE_MODE:', source)

    def test_each_actual_cohort_has_one_fixed_matrix_job_and_unique_artifact_identity(self):
        source = (host.ROOT / '.github/workflows/isolated-restore-business-reconciliation.yml').read_text()
        matrix = source.split('        cohort:\n', 1)[1].split('    env:\n', 1)[0]
        actual = [line.strip()[2:] for line in matrix.splitlines() if line.strip().startswith('- ')]
        self.assertEqual(list(host.COHORTS), actual)
        self.assertEqual(7, len(set(actual)))
        self.assertIn('max-parallel: 20', source)
        self.assertIn('fail-fast: false', source)
        self.assertIn('needs: restore-source-build', source)
        self.assertEqual(1, source.count('python3 -B ci/test_restore_business_reconciliation_probe.py'))
        self.assertEqual(1, source.count('python3 -B ci/test_restore_business_reconciliation_host.py'))
        controls = source.split('  restore-source-build:\n', 1)[1].split('  isolated-restore-business-reconciliation:\n', 1)[0]
        self.assertIn('python3 -B ci/test_restore_business_reconciliation_host.py', controls)
        self.assertEqual(1, source.count('mvn -B -pl chengyinhub-admin -am -DskipTests install'))
        self.assertNotIn('mvn ', source.split('  isolated-restore-business-reconciliation:\n', 1)[1])
        self.assertIn('python3 -I ci/restore_business_reconciliation_build.py unpack', source)
        self.assertIn('CHENGYIN_RESTORE_BUSINESS_COHORT: ${{ matrix.cohort }}', source)
        self.assertIn('name: isolated-restore-business-reconciliation-${{ matrix.cohort }}-${{ github.run_attempt }}', source)
        for value in host.COHORTS:
            host.hosted(dict(self.environment(), CHENGYIN_RESTORE_BUSINESS_COHORT=value), SHA,
                        'unix:///var/run/docker.sock')
        for value in ('', 'VOICE-COMPLETE', 'withdrawal', 'foreign-profile'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                host.hosted(dict(self.environment(), CHENGYIN_RESTORE_BUSINESS_COHORT=value), SHA,
                            'unix:///var/run/docker.sock')

    def receipts(self, root):
        for index, cohort in enumerate(host.COHORTS):
            directory = root / ('isolated-restore-business-reconciliation-' + cohort + '-1')
            directory.mkdir()
            base = {'sourceSha': SHA, 'cohort': cohort, 'scope': '%032x' % index}
            complete = cohort.endswith('-complete')
            business = dict(base, runId='123', runAttempt='1', jarSha256='c' * 64,
                            canonicalToolSha256='d' * 64, processExit=0,
                            schemaCleanup='CONFIRMED_EXACT_CREATED_SCHEMAS_ABSENT',
                            times={'normalOriginalReadbackAvailableAt': 'fixed-test-time' if complete else None},
                            businessResult='SCOPED_COMPLETE_FACTS_NORMAL_BOOT_READBACK_VERIFIED' if complete
                            else 'SCOPED_DIFFERENCES_VERIFIED_RELEASE_BLOCKED')
            final = dict(base, originalExit=0, absenceFailures=[], resourceCleanup='EXACT_ABSENCE',
                         dispatchRelease=False, candidateState='TEST_OWNED_RECONCILED' if complete else 'BLOCKED',
                         result='TEST_COHORT_NORMAL_READBACK_COMPLETE_NO_PRODUCTION_RELEASE' if complete
                         else 'SCOPED_OBSERVATION_COMPLETE_RELEASE_BLOCKED')
            owner = dict(base, originalExit=0, exitCode=0, cleanupFailures=[],
                         resourceCleanup='EXACT_ABSENCE', probeFinalized=True)
            for name, value in [('host', owner), ('reconciliation', business), ('finalized', final)]:
                (directory / (name + '.json')).write_text(json.dumps(value))

    def test_summary_routes_only_three_complete_pairs_to_the_original_C_verifier(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve(); self.receipts(root)
            verifier = mock.Mock()
            verifier.summarize_completed_cohorts.return_value = {'coverageScope': 'C_FIXED_RESTORE_COHORTS_ONLY'}
            with mock.patch.object(host, 'domain_readers', return_value=verifier):
                result = host.summarize_receipts(root, SHA, '123', '1')
            pairs = verifier.summarize_completed_cohorts.call_args.args[0]
            self.assertEqual(['withdrawal-complete', 'refund-complete', 'voice-complete'],
                             [pair['business']['cohort'] for pair in pairs])
            self.assertEqual(7, len(result['cohorts']))
            self.assertFalse(result['productionDispatchRelease'])
            self.assertFalse(result['globalProductionFenceVerified'])

    def test_summary_rejects_missing_mixed_or_unclean_receipts_before_original_verifier(self):
        cases = ('missing', 'source', 'run', 'attempt', 'jar', 'scope', 'cleanup', 'final', 'positive_gap',
                 'gap_boot', 'private', 'symlink')
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp:
                root = Path(temp).resolve(); self.receipts(root)
                directory = root / 'isolated-restore-business-reconciliation-voice-gap-1'
                if case == 'missing':
                    shutil.rmtree(directory)
                elif case == 'private':
                    (directory / 'private.cnf').write_text('controlled sentinel')
                elif case == 'symlink':
                    path = directory / 'finalized.json'; original = path.read_text(); path.unlink()
                    outside = root.parent / (root.name + '-controlled.json'); outside.write_text(original)
                    self.addCleanup(outside.unlink)
                    path.symlink_to(outside)
                else:
                    name = 'reconciliation'
                    if case in ('cleanup', 'final'):
                        name = 'host' if case == 'cleanup' else 'finalized'
                    if case == 'positive_gap':
                        directory = root / 'isolated-restore-business-reconciliation-voice-complete-1'
                    path = directory / (name + '.json'); value = json.loads(path.read_text())
                    if case == 'source': value['sourceSha'] = 'f' * 40
                    elif case == 'run': value['runId'] = 'foreign'
                    elif case == 'attempt': value['runAttempt'] = '2'
                    elif case == 'jar': value['jarSha256'] = 'e' * 64
                    elif case == 'scope': value['scope'] = '0' * 32
                    elif case == 'cleanup': value['cleanupFailures'] = ['source']
                    elif case == 'final': value['result'] = 'FAIL'
                    elif case == 'positive_gap': value['businessResult'] = 'SCOPED_DIFFERENCES_VERIFIED_RELEASE_BLOCKED'
                    elif case == 'gap_boot': value['times']['normalOriginalReadbackAvailableAt'] = 'unapproved'
                    path.write_text(json.dumps(value))
                verifier = mock.Mock()
                with mock.patch.object(host, 'domain_readers', return_value=verifier), self.assertRaises(ValueError):
                    host.summarize_receipts(root, SHA, '123', '1')
                verifier.summarize_completed_cohorts.assert_not_called()

    def test_summary_job_is_always_scoped_to_the_same_seven_attempt_artifacts(self):
        source = (host.ROOT / '.github/workflows/isolated-restore-business-reconciliation.yml').read_text()
        summary = source.split('  restore-business-summary:\n', 1)[1]
        self.assertIn('needs: [restore-source-build, isolated-restore-business-reconciliation]', summary)
        self.assertIn('if: ${{ always() }}', summary)
        self.assertIn('pattern: isolated-restore-business-reconciliation-*-${{ github.run_attempt }}', summary)
        self.assertIn('python3 -I ci/restore_business_reconciliation_host.py --summarize', summary)
        self.assertNotIn('merge-multiple: true', summary)
        self.assertNotIn('private', summary)


if __name__ == '__main__':
    unittest.main()
