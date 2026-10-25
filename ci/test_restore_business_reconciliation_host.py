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
        matrix = source.split("        cohort: ${{ fromJSON(inputs.restore_scope == 'payment-gap-diagnostic' && '", 1)[1].split("') }}", 1)[0]
        diagnostic, full = matrix.split("' || '", 1)
        self.assertEqual(['payment-gap'], json.loads(diagnostic))
        actual = json.loads(full)
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
        self.assertIn("    if: ${{ always() && inputs.restore_scope == 'full-seven' }}\n", summary)
        self.assertIn('pattern: isolated-restore-business-reconciliation-*-${{ github.run_attempt }}', summary)
        self.assertIn('python3 -I ci/restore_business_reconciliation_host.py --summarize', summary)
        self.assertNotIn('merge-multiple: true', summary)
        self.assertNotIn('private', summary)
        self.assertIn('RESTORE_BUILD_JAR_SHA256: ${{ needs.restore-source-build.outputs.jar_sha256 }}', summary)

    def test_all_cohorts_must_match_the_once_build_not_just_one_another(self):
        for expected in (('c' * 64, 'd' * 64), ('e' * 64, 'd' * 64),
                         ('c' * 64, 'e' * 64), ('', 'd' * 64)):
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as temp:
                root = Path(temp).resolve(); self.receipts(root)
                verifier = mock.Mock()
                verifier.summarize_completed_cohorts.return_value = {'coverageScope': 'C_FIXED_RESTORE_COHORTS_ONLY'}
                with mock.patch.object(host, 'domain_readers', return_value=verifier):
                    if expected == ('c' * 64, 'd' * 64):
                        result = host.summarize_receipts(root, SHA, '123', '1', expected_build=expected)
                        self.assertTrue(result['onceBuildBound'])
                        self.assertEqual(expected, (result['jarSha256'], result['canonicalToolSha256']))
                        verifier.summarize_completed_cohorts.assert_called_once()
                    else:
                        with self.assertRaises(ValueError):
                            host.summarize_receipts(root, SHA, '123', '1', expected_build=expected)
                        verifier.summarize_completed_cohorts.assert_not_called()


    def test_source_create_failure_keeps_fixed_invocation_and_private_stderr_digest_for_every_cohort(self):
        secret = 'SYNTHETIC_PASSWORD_NEVER_PUBLISH'
        raw = ('unrecognized rejection ' + secret + ' /private/owner/source.cnf SELECT private_provider_payload').encode()
        for cohort in host.COHORTS:
            with self.subTest(cohort=cohort), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); private = root / 'private'; private.mkdir(); output = root / 'out'; output.mkdir()
                worker = host.Host(root, private, output, SCOPE, cohort)
                network = {'Id': 'e' * 64, 'Name': worker.name, 'Internal': True,
                           'Labels': {'chengyin.restore-business': SCOPE}}
                replies = [subprocess.CompletedProcess([], 0, b'pulled', b''),
                           subprocess.CompletedProcess([], 0, ('e' * 64).encode(), b''),
                           subprocess.CompletedProcess([], 125, b'PRIVATE_STDOUT', raw),
                           subprocess.CompletedProcess([], 0, b'removed', b'')]
                env = dict(self.environment(), GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2')
                with mock.patch.dict(os.environ, env, clear=True), \
                     mock.patch.object(host.secrets, 'token_urlsafe', return_value=secret), \
                     mock.patch.object(host, 'inspect', side_effect=[None, None, None, network, None]), \
                     mock.patch.object(host.subprocess, 'run', side_effect=replies) as run:
                    with self.assertRaises(ValueError):
                        worker.provision()
                    self.assertEqual({'network': 'e' * 64, 'source': None}, worker.created)
                    self.assertEqual(1, worker.finish(1))
                command = run.call_args_list[2]
                self.assertEqual(host.DOCKER + ['create', '--name', worker.name + '-source',
                    '--cidfile', str(private / 'source.cid'), '--label', 'chengyin.restore-business=' + SCOPE,
                    '--network', worker.name, '--memory=1g', '--memory-swap=1g', '--cpus=1', '--pids-limit=256',
                    '--tmpfs', '/var/lib/mysql:rw,size=768m', '-p', '127.0.0.1::3306',
                    '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'MYSQL_ROOT_HOST=%', host.IMAGE,
                    '--skip-log-bin', '--performance-schema=OFF', '--event-scheduler=OFF'], command.args[0])
                self.assertEqual(secret, command.kwargs['env']['MYSQL_ROOT_PASSWORD'])
                self.assertEqual(4, run.call_count, 'one create only; cleanup is not another provision attempt')
                receipt = json.loads((output / 'host.json').read_text())
                self.assertEqual((SHA, SCOPE, cohort, '123', '2'),
                    tuple(receipt[k] for k in ('sourceSha', 'scope', 'cohort', 'runId', 'runAttempt')))
                failure = receipt['firstFailure']
                self.assertEqual(('source-create', 'DOCKER_CONTAINER_CREATE', 'COMMAND_EXIT', 125, 'UNKNOWN'),
                    tuple(failure[k] for k in ('stage', 'operation', 'failureType', 'returnCode', 'reasonCode')))
                self.assertEqual(host.hashlib.sha256(raw).hexdigest(), failure['stderrSha256'])
                self.assertEqual(len(raw), failure['stderrBytes']); self.assertTrue(failure['stderrCaptured'])
                self.assertEqual(1, receipt['originalExit']); self.assertEqual(1, receipt['exitCode'])
                self.assertFalse(receipt['probeFinalized']); self.assertEqual('EXACT_ABSENCE', receipt['resourceCleanup'])
                self.assertEqual([], receipt['cleanupFailures']); self.assertFalse(private.exists())
                public = json.dumps(receipt)
                for value in (secret, str(private), 'source.cnf', 'SELECT', 'private_provider_payload', 'PRIVATE_STDOUT', 'MYSQL_ROOT_PASSWORD'):
                    self.assertNotIn(value, public)

    def test_known_docker_reasons_publish_only_fixed_excerpts_never_the_matching_raw_line(self):
        samples = [(b'Cannot connect to the Docker daemon', 'DOCKER_DAEMON_UNAVAILABLE'),
                   (b'permission denied', 'DOCKER_PERMISSION_DENIED'),
                   (b'unknown flag: --private', 'DOCKER_OPTION_UNSUPPORTED'),
                   (b'invalid mount config for type "tmpfs"', 'DOCKER_TMPFS_REJECTED'),
                   (b'conflicting options: private', 'DOCKER_OPTION_CONFLICT'),
                   (b'Conflict. The container name /private is already in use', 'DOCKER_CONTAINER_NAME_CONFLICT'),
                   (b'port is already allocated', 'DOCKER_PORT_CONFLICT'),
                   (b'no space left on device', 'DOCKER_STORAGE_UNAVAILABLE')]
        for raw, expected in samples:
            raw += b' SYNTHETIC_SECRET /private/owned.cnf provider=private'
            with self.subTest(expected=expected):
                code, excerpt = host.safe_command_reason('DOCKER_CONTAINER_CREATE', raw)
                self.assertEqual(expected, code)
                for secret in ('SYNTHETIC_SECRET', '/private', 'owned.cnf', 'provider='):
                    self.assertNotIn(secret, excerpt)
                self.assertEqual('UNKNOWN', host.safe_command_reason('PROBE_FINALIZE', raw)[0])
        self.assertEqual('UNKNOWN', host.safe_command_reason('DOCKER_CONTAINER_CREATE', b'novel error')[0])


    def test_create_timeout_lost_ack_still_removes_only_exact_owned_container_and_network(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); private = root / 'private'; private.mkdir(); output = root / 'out'; output.mkdir()
            worker = host.Host(root, private, output, SCOPE, 'withdrawal-gap'); worker.stage = 'source-create'
            worker.created = {'source': None, 'network': 'e' * 64}
            container = {'Id': 'c' * 64, 'Name': '/' + worker.name + '-source',
                         'Config': {'Image': host.IMAGE, 'Labels': {'chengyin.restore-business': SCOPE}},
                         'NetworkSettings': {'Networks': {worker.name: {}}}}
            network = {'Id': 'e' * 64, 'Name': worker.name, 'Internal': True,
                       'Labels': {'chengyin.restore-business': SCOPE}}
            raw = b'PRIVATE_TIMEOUT_STDERR'
            timeout = subprocess.TimeoutExpired('PRIVATE_COMMAND_NOT_FOR_JSON', 120, stderr=raw)
            with mock.patch.dict(os.environ, {'GITHUB_SHA': SHA, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2'}), \
                 mock.patch.object(host.subprocess, 'run', side_effect=[timeout,
                     subprocess.CompletedProcess([], 0, b'c', b''), subprocess.CompletedProcess([], 0, b'e', b'')]) as run, \
                 mock.patch.object(host, 'inspect', side_effect=[container, None, network, None]):
                with self.assertRaises(subprocess.TimeoutExpired):
                    worker.command(host.DOCKER + ['create', '--name', worker.name + '-source'])
                self.assertEqual(7, worker.finish(7))
            self.assertEqual([host.DOCKER + ['container', 'rm', '-f', 'c' * 64],
                              host.DOCKER + ['network', 'rm', 'e' * 64]], [c.args[0] for c in run.call_args_list[1:]])
            receipt = json.loads((output / 'host.json').read_text())
            self.assertEqual('COMMAND_TIMEOUT', receipt['firstFailure']['failureType'])
            self.assertIsNone(receipt['firstFailure']['returnCode'])
            self.assertEqual(host.hashlib.sha256(raw).hexdigest(), receipt['firstFailure']['stderrSha256'])
            self.assertEqual(7, receipt['originalExit']); self.assertEqual(7, receipt['exitCode'])
            self.assertEqual('EXACT_ABSENCE', receipt['resourceCleanup'])
            self.assertNotIn('PRIVATE_', json.dumps(receipt))

    def test_cleanup_daemon_error_is_unknown_not_absent_and_cannot_overwrite_first_create_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); private = root / 'private'; private.mkdir(); output = root / 'out'; output.mkdir()
            worker = host.Host(root, private, output, SCOPE, 'voice-gap'); worker.stage = 'source-create'
            worker.created = {'source': None}
            with mock.patch.object(host.subprocess, 'run', return_value=subprocess.CompletedProcess([], 125, b'', b'original rejection')):
                with self.assertRaises(ValueError):
                    worker.command(host.DOCKER + ['create'])
            original = dict(worker.first_failure)
            with mock.patch.object(host.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, b'', b'Cannot connect to the Docker daemon')), \
                 mock.patch.dict(os.environ, {'GITHUB_SHA': SHA}):
                self.assertEqual(1, worker.finish(1))
            receipt = json.loads((output / 'host.json').read_text())
            self.assertEqual(original, receipt['firstFailure'])
            self.assertEqual(['source'], receipt['cleanupFailures'])
            self.assertEqual('UNKNOWN', receipt['resourceCleanup'])
            self.assertEqual(1, receipt['exitCode'])

    def test_recorded_command_failure_cannot_turn_green_even_if_caller_passes_zero(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); private = root / 'private'; private.mkdir(); output = root / 'out'; output.mkdir()
            worker = host.Host(root, private, output, SCOPE, 'payment-gap'); worker.stage = 'source-create'
            worker.remember_failure('COMMAND_EXIT', 'DOCKER_CONTAINER_CREATE', 125, b'unknown rejection')
            worker.resources.write_text('{}'); (output / 'reconciliation.json').write_text('{}')
            with mock.patch.object(worker, 'command') as finalize, mock.patch.dict(os.environ, {'GITHUB_SHA': SHA}):
                self.assertEqual(1, worker.finish(0))
            self.assertEqual('1', finalize.call_args.args[0][-1])
            self.assertEqual(1, json.loads((output / 'host.json').read_text())['exitCode'])

    def test_command_start_failure_has_no_invented_stderr_and_success_keeps_original_stdout(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            worker = host.Host(root, root, root, SCOPE, 'refund-complete'); worker.stage = 'target-create'
            with mock.patch.object(host.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'c' * 64 + b'\n', b'')):
                self.assertEqual('c' * 64, worker.command(host.DOCKER + ['create']))
            self.assertIsNone(worker.first_failure)
            with mock.patch.object(host.subprocess, 'run', side_effect=FileNotFoundError('/private/command')):
                with self.assertRaises(FileNotFoundError):
                    worker.command(host.DOCKER + ['create'])
            self.assertEqual('target-create', worker.first_failure['stage'])
            self.assertEqual('COMMAND_START_ERROR', worker.first_failure['failureType'])
            self.assertFalse(worker.first_failure['stderrCaptured'])
            self.assertIsNone(worker.first_failure['stderrSha256'])
            self.assertNotIn('/private', json.dumps(worker.first_failure))


    def test_owned_resource_predicates_have_fixed_codes_and_preserve_value_error_contract(self):
        name = 'cy-restore-' + SCOPE
        original = {'Id': 'c' * 64, 'Name': '/' + name + '-source',
                    'Config': {'Image': host.IMAGE, 'Labels': {'chengyin.restore-business': SCOPE}},
                    'NetworkSettings': {'Networks': {name: {}}}}
        mutations = [(['Id'], 'PRIVATE_INVALID_ID', 'OWNER_FULL_ID_INVALID'),
                     (['Name'], '/PRIVATE_NAME', 'OWNER_CONTAINER_NAME'),
                     (['Config', 'Labels', 'chengyin.restore-business'], 'PRIVATE_LABEL', 'OWNER_CONTAINER_LABEL'),
                     (['Config', 'Image'], 'PRIVATE_IMAGE', 'OWNER_CONTAINER_IMAGE'),
                     (['NetworkSettings', 'Networks'], {'PRIVATE_NETWORK': {}}, 'OWNER_CONTAINER_NETWORK')]
        host.owned_object('container', original, SCOPE, 'source')
        for keys, value, code in mutations:
            row = json.loads(json.dumps(original)); target = row
            for key in keys[:-1]: target = target[key]
            target[keys[-1]] = value
            with self.subTest(code=code), self.assertRaises(ValueError) as caught:
                host.owned_object('container', row, SCOPE, 'source')
            self.assertIsInstance(caught.exception, host.OwnerGuardFailure)
            self.assertEqual(code, caught.exception.guard_code)
            self.assertNotIn('PRIVATE', str(caught.exception))
        network = {'Id': 'e' * 64, 'Name': name, 'Internal': True,
                   'Labels': {'chengyin.restore-business': SCOPE}}
        for key, value, code in [('Name', 'PRIVATE_NAME', 'OWNER_NETWORK_NAME'),
                                  ('Internal', False, 'OWNER_NETWORK_NOT_INTERNAL'),
                                  ('Labels', {'chengyin.restore-business': 'PRIVATE_LABEL'}, 'OWNER_NETWORK_LABEL')]:
            with self.subTest(code=code), self.assertRaises(ValueError) as caught:
                host.owned_object('network', dict(network, **{key: value}), SCOPE)
            self.assertEqual(code, caught.exception.guard_code)

    def test_provision_reports_precise_guard_or_substage_without_reinterpreting_owner_failure_as_docker_exit(self):
        cases = [('cid', 'container-cid', 'CREATED_CID_INVALID'),
                 ('label', 'container-ownership', 'OWNER_CONTAINER_LABEL'),
                 ('image', 'container-ownership', 'OWNER_CONTAINER_IMAGE'),
                 ('missing', 'loopback-binding', 'BINDING_MISSING'),
                 ('multiple', 'loopback-binding', 'BINDING_COUNT'),
                 ('address', 'loopback-binding', 'BINDING_NOT_LOOPBACK'),
                 ('parse', 'loopback-port-parse', None),
                 ('range', 'loopback-port-range', 'BINDING_PORT_RANGE'),
                 ('config', 'client-config-write', None)]
        for case, substage, code in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); worker = host.Host(root, root, root, SCOPE, 'payment-gap')
                binding = [{'HostIp': '127.0.0.1', 'HostPort': '33080'}]
                if case == 'missing': binding = None
                elif case == 'multiple': binding *= 2
                elif case == 'address': binding[0]['HostIp'] = '0.0.0.0'
                elif case == 'parse': binding[0]['HostPort'] = 'PRIVATE_PORT'
                elif case == 'range': binding[0]['HostPort'] = '1024'
                row = {'Id': 'c' * 64, 'Name': '/' + worker.name + '-source',
                       'Config': {'Image': 'PRIVATE_IMAGE' if case == 'image' else host.IMAGE,
                                  'Labels': {'chengyin.restore-business': 'PRIVATE_LABEL' if case == 'label' else SCOPE}},
                       'NetworkSettings': {'Networks': {worker.name: {}}, 'Ports': {'3306/tcp': binding}}}
                replies = [subprocess.CompletedProcess([], 0, b'pulled', b''),
                           subprocess.CompletedProcess([], 0, ('e' * 64).encode(), b''),
                           subprocess.CompletedProcess([], 0, b'PRIVATE_CID' if case == 'cid' else ('c' * 64).encode(), b''),
                           subprocess.CompletedProcess([], 0, b'started', b'')]
                with mock.patch.dict(os.environ, {'GITHUB_SHA': SHA}), \
                     mock.patch.object(host, 'inspect', side_effect=[None, None, row]), \
                     mock.patch.object(host.subprocess, 'run', side_effect=replies) as run, \
                     mock.patch.object(host, 'write_private', side_effect=ValueError('PRIVATE_CONFIG')) as write:
                    with self.assertRaises(ValueError) as caught:
                        worker.provision()
                    worker.remember_failure('HOST_EXCEPTION_UNKNOWN', error=caught.exception)
                evidence = worker.first_failure
                self.assertEqual(('source-create', substage, code),
                                 (evidence['stage'], evidence['substage'], evidence['guardCode']))
                self.assertEqual('OWNER', evidence['operation'])
                self.assertEqual('HOST_EXCEPTION_UNKNOWN', evidence['failureType'])
                self.assertIsNone(evidence['returnCode']); self.assertFalse(evidence['stderrCaptured'])
                self.assertEqual(3 if case == 'cid' else 4, run.call_count)
                self.assertEqual(1 if case == 'config' else 0, write.call_count)
                self.assertNotIn('PRIVATE', json.dumps(evidence))
                # Cleanup cannot overwrite the first guard, or remove a container failing the same original label guard.
                if case == 'label':
                    network = {'Id': 'e' * 64, 'Name': worker.name, 'Internal': True,
                               'Labels': {'chengyin.restore-business': SCOPE}}
                    before = dict(evidence)
                    with mock.patch.object(host, 'inspect', side_effect=[row, network, None]), \
                         mock.patch.object(worker, 'command') as remove:
                        self.assertEqual(['source'], worker.cleanup())
                    self.assertEqual([mock.call(host.DOCKER + ['network', 'rm', 'e' * 64], timeout=30)], remove.call_args_list)
                    self.assertEqual(before, worker.first_failure)

    def test_inspect_guard_codes_keep_original_absence_proof_and_never_accept_daemon_failure(self):
        for raw, code in [(b'not json PRIVATE', 'INSPECT_JSON_INVALID'), (b'[]', 'INSPECT_IDENTITY_COUNT'),
                          (b'[{},{}]', 'INSPECT_IDENTITY_COUNT')]:
            with self.subTest(code=code), mock.patch.object(host.subprocess, 'run',
                    return_value=subprocess.CompletedProcess([], 0, raw, b'')), self.assertRaises(ValueError) as caught:
                host.inspect('container', 'c' * 64)
            self.assertEqual(code, caught.exception.guard_code)
            self.assertNotIn('PRIVATE', str(caught.exception))
        for stderr, absent in [(b'Error: No such container: ' + ('c' * 64).encode(), True),
                               (b'Cannot connect to the Docker daemon PRIVATE', False)]:
            with self.subTest(absent=absent), mock.patch.object(host.subprocess, 'run',
                    return_value=subprocess.CompletedProcess([], 1, b'', stderr)):
                if absent: self.assertIsNone(host.inspect('container', 'c' * 64))
                else:
                    with self.assertRaises(RuntimeError) as caught:
                        host.inspect('container', 'c' * 64)
                    self.assertIsInstance(caught.exception, host.InspectAbsenceUnproven)
                    self.assertEqual('INSPECT_ABSENCE_UNPROVEN', caught.exception.guard_code)
                    self.assertNotIn('PRIVATE', str(caught.exception))

    def test_untyped_owner_value_error_message_cannot_forge_guard_code_or_replace_first_substage(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); worker = host.Host(root, root, root, SCOPE, 'payment-gap')
            worker.stage = 'source-create'; worker.substage = 'loopback-port-parse'
            worker.remember_failure('HOST_EXCEPTION_UNKNOWN', error=ValueError('CREATED_CID_INVALID PRIVATE'))
            first = dict(worker.first_failure)
            self.assertIsNone(first['guardCode']); self.assertEqual('ValueError', first['errorType'])
            self.assertNotIn('PRIVATE', json.dumps(first))
            worker.substage = 'cleanup'
            worker.remember_failure('HOST_EXCEPTION_UNKNOWN', error=host.OwnerGuardFailure('OWNER_CONTAINER_LABEL'))
            self.assertEqual(first, worker.first_failure)


if __name__ == '__main__':
    unittest.main()
