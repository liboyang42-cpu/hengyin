import importlib.util
import hashlib
import json
import re
import pathlib
import os
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('startup_network', pathlib.Path(__file__).with_name('upgrade_startup_network.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def connection(address, port):
    return f'10 connect(9, {{sa_family=AF_INET, sin_port=htons({port}), sin_addr=inet_addr("{address}")}}, 16) = -1 EINPROGRESS'


class NetworkGuardTest(unittest.TestCase):
    def test_actual_numeric_mysql_redis_and_loopback(self):
        trace = '\n'.join([connection('172.18.0.2', 3306), connection('172.18.0.3', 6379), connection('127.0.0.1', 9999)])
        self.assertEqual(3, gate.verify(trace, '172.18.0.2', '172.18.0.3'))

    def test_actual_ipv4_mapped_trace_format(self):
        trace = '10 connect(9, {sa_family=AF_INET6, sin6_port=htons(3306), sin6_flowinfo=htonl(0), inet_pton(AF_INET6, "::ffff:172.18.0.2", &sin6_addr), sin6_scope_id=0}, 28) = -1 EINPROGRESS'
        self.assertEqual(1, gate.verify(trace, '172.18.0.2', '172.18.0.3'))

    def test_external_attempt_fails_even_when_connection_failed(self):
        with self.assertRaisesRegex(ValueError, 'Unexpected destination'):
            gate.verify(connection('203.0.113.1', 443), '172.18.0.2', '172.18.0.3')

    def test_internal_unapproved_port_fails(self):
        with self.assertRaisesRegex(ValueError, 'Unexpected destination'):
            gate.verify(connection('172.18.0.2', 443), '172.18.0.2', '172.18.0.3')

    def test_dns_fails_even_on_loopback(self):
        with self.assertRaisesRegex(ValueError, 'DNS'):
            gate.verify(connection('127.0.0.1', 53), '172.18.0.2', '172.18.0.3')

    def test_empty_trace_cannot_claim_no_external_calls(self):
        with self.assertRaisesRegex(ValueError, 'No socket observations'):
            gate.verify('', '172.18.0.2', '172.18.0.3')

    def test_unparsed_inet_call_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'Unrecognized IP destination'):
            gate.verify('connect(9, {sa_family=AF_INET6, unknown}, 28)', '172.18.0.2', '172.18.0.3')


class StartupExecutionTargetTest(unittest.TestCase):
    def test_mac_unapproved_linux_and_self_hosted_reject_before_docker(self):
        script = pathlib.Path(__file__).with_name('upgrade-startup-smoke.sh')
        for system, overrides in [('Darwin', {}), ('Linux', {}),
                ('Linux', {'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ENVIRONMENT': 'self-hosted'}),
                ('Linux', {'CHENGYIN_VPS_TEST_RUN': '1', 'CHENGYIN_VPS_SOURCE_ON_DAEMON': '1', 'DOCKER_CONTEXT': 'default'})]:
            with self.subTest(system=system, overrides=overrides), tempfile.TemporaryDirectory() as temp:
                directory = pathlib.Path(temp)
                marker = directory / 'docker-called'
                (directory / 'uname').write_text('#!/bin/sh\nprintf "%s\\n" "' + system + '"\n')
                (directory / 'docker').write_text('#!/bin/sh\ntouch "' + str(marker) + '"\nexit 99\n')
                for path in directory.iterdir(): path.chmod(0o755)
                env = os.environ.copy()
                for key in ('GITHUB_ACTIONS', 'RUNNER_OS', 'RUNNER_ENVIRONMENT', 'CHENGYIN_VPS_TEST_RUN',
                            'CHENGYIN_VPS_SOURCE_ON_DAEMON', 'DOCKER_CONTEXT', 'DOCKER_HOST'):
                    env.pop(key, None)
                env.update(overrides)
                env['PATH'] = str(directory) + os.pathsep + env['PATH']
                result = subprocess.run(['/bin/bash', str(script)], env=env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(marker.exists(), result.stderr)

    def test_hosted_runner_and_approved_vps_reject_wrong_endpoint_before_any_runtime(self):
        script = pathlib.Path(__file__).with_name('upgrade-startup-smoke.sh')
        for overrides, context in [({'GITHUB_ACTIONS': 'true', 'RUNNER_OS': 'Linux', 'RUNNER_ENVIRONMENT': 'github-hosted'}, 'default'),
                ({'CHENGYIN_VPS_TEST_RUN': '1', 'CHENGYIN_VPS_SOURCE_ON_DAEMON': '1', 'DOCKER_CONTEXT': 'chengyin-aws'}, 'chengyin-aws')]:
            with self.subTest(context=context), tempfile.TemporaryDirectory() as temp:
                directory = pathlib.Path(temp); calls = directory / 'calls'
                (directory / 'uname').write_text('#!/bin/sh\necho Linux\n')
                (directory / 'docker').write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "' + str(calls) + '"\necho unix:///unapproved.sock\n')
                for path in directory.iterdir(): path.chmod(0o755)
                env = os.environ.copy()
                for key in ('GITHUB_ACTIONS', 'RUNNER_OS', 'RUNNER_ENVIRONMENT', 'CHENGYIN_VPS_TEST_RUN',
                            'CHENGYIN_VPS_SOURCE_ON_DAEMON', 'DOCKER_CONTEXT', 'DOCKER_HOST'):
                    env.pop(key, None)
                env.update(overrides); env['PATH'] = str(directory) + os.pathsep + env['PATH']
                result = subprocess.run(['/bin/bash', str(script)], env=env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('endpoint differs', result.stderr)
                lines = calls.read_text().splitlines()
                self.assertEqual(1, len(lines))
                self.assertTrue(lines[0].startswith('--context ' + context + ' context inspect ' + context))


SCRIPT = pathlib.Path(__file__).with_name('upgrade-startup-smoke.sh')


def shell_function(name):
    match = re.search(r'^' + name + r'\(\) \{\n.*?^\}\n', SCRIPT.read_text(), re.M | re.S)
    if match is None:
        raise AssertionError('Missing startup trace function: ' + name)
    return match.group(0)


class TraceRuntimeBundleTest(unittest.TestCase):
    def prepare(self, directory, defect=None):
        commands = directory / 'commands'
        commands.mkdir()
        libraries = directory / ('host libs' if defect == 'space-path' else 'host-libs')
        libraries.mkdir()
        binary, loader = directory / 'strace', libraries / 'ld-linux-x86-64.so.2'
        libc = libraries / 'libc.so.6'
        tinfo = libraries / ('libtinfo.so.6.5' if defect == 'versioned-library' else 'libtinfo.so.6')
        for path in (binary, loader, libc, tinfo):
            path.write_bytes(('synthetic ELF fixture ' + path.name).encode())
        interpreter = str(loader)
        readelf = '      [Requesting program interpreter: ' + interpreter + ']\n'
        if defect == 'missing-interpreter':
            readelf = ''
        elif defect == 'multiple-interpreters':
            readelf += readelf
        rows = [
            'linux-vdso.so.1 (0x1234)',
            'libc.so.6 => ' + str(libc) + ' (0x1234)',
            'libtinfo.so.6 => ' + str(tinfo) + ' (0x1234)',
            str(loader) + ' (0x1234)',
        ]
        if defect == 'missing-libc':
            rows.pop(1)
        elif defect == 'missing-loader':
            rows.pop()
        elif defect == 'missing-library':
            tinfo.unlink()
        elif defect == 'unresolved-library':
            rows[2] = 'libtinfo.so.6 => not found'
        elif defect == 'duplicate-soname':
            rows.insert(2, rows[1])
        elif defect == 'wrong-loader':
            other = libraries / 'other-loader'
            other.write_bytes(b'not the ELF interpreter')
            rows[-1] = str(other) + ' (0x1234)'
        for name, output in [('readelf', readelf), ('ldd', '\n'.join(rows) + '\n')]:
            command = commands / name
            command.write_text("#!/bin/sh\ntest \"${LC_ALL:-}\" = C || exit 8\ncat <<'FIXTURE'\n" + output + 'FIXTURE\n'
                               + ('exit 9\n' if defect == 'ldd-failed' and name == 'ldd' else ''))
            command.chmod(0o755)
        env = os.environ.copy()
        env.update(STRACE=str(binary), OUT=str(directory), PATH=str(commands) + os.pathsep + env['PATH'])
        result = subprocess.run(['/bin/bash', '-euc', shell_function('prepare_trace_runtime')
                                 + '\nprepare_trace_runtime'], env=env, capture_output=True, text=True)
        return result, binary, loader, libc, tinfo

    def test_complete_tool_runtime_copies_matching_loader_libc_and_all_libraries(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = pathlib.Path(temp)
            result, *sources = self.prepare(directory, 'versioned-library')
            self.assertEqual(0, result.returncode, result.stderr)
            bundle = directory / 'trace-runtime'
            manifest = json.loads((bundle / 'manifest.json').read_text())['files']
            expected = dict(zip(['strace', 'loader', 'lib/libc.so.6', 'lib/libtinfo.so.6'], sources))
            self.assertEqual(set(expected), set(manifest))
            for relative, source in expected.items():
                target = bundle / relative
                self.assertEqual(source.read_bytes(), target.read_bytes())
                self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), manifest[relative])
                self.assertEqual(0, target.stat().st_mode & 0o222)
            self.assertEqual(0, bundle.stat().st_mode & 0o222)

    def test_incomplete_conflicting_or_unresolved_closure_fails_before_bundle(self):
        for defect in ('missing-interpreter', 'multiple-interpreters', 'missing-libc',
                       'missing-loader', 'missing-library', 'unresolved-library',
                       'duplicate-soname', 'wrong-loader', 'space-path', 'ldd-failed'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as temp:
                directory = pathlib.Path(temp)
                result, *_ = self.prepare(directory, defect)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((directory / 'trace-runtime').exists())


class TraceDockerCommandTest(unittest.TestCase):
    def assert_readonly_tool_aliases(self, argv, directory):
        for target in ('/trace-runtime', '/evidence/trace-runtime'):
            self.assertIn(str(directory) + '/trace-runtime:' + target + ':ro', argv)

    def capture(self, directory, name, fail=False, resolution_defect=None):
        env = os.environ.copy()
        env.update(OUT=str(directory), RUN_NAME='synthetic-startup', JDK_IMAGE='pinned-image',
                   JAR='/owned jar/app.jar', FIXTURE='/owned fixture', MYSQL_IP='172.18.0.2',
                   REDIS_IP='172.18.0.3', CAPTURE=str(directory / 'argv.json'),
                   RESOLUTION_DEFECT=resolution_defect or '')
        bundle = directory / 'trace-runtime'
        bundle.mkdir()
        (bundle / 'manifest.json').write_text(json.dumps({
            'interpreter': '/lib64/ld-linux-x86-64.so.2',
            'files': {'strace': 'fixture', 'loader': 'fixture', 'lib/libc.so.6': 'fixture'}}))
        docker = """docker() {
  python3 - "$@" <<'PY_CAPTURE'
import json, os, pathlib, sys
with pathlib.Path(os.environ['CAPTURE']).open('a') as capture:
    capture.write(json.dumps(sys.argv[1:]) + '\\n')
if '--list' in sys.argv:
    defect = os.environ['RESOLUTION_DEFECT']
    print('linux-vdso.so.1 (0x1234)')
    if defect != 'missing':
        target = '/lib/x86_64-linux-gnu/libc.so.6' if defect == 'fallback' else '/trace-runtime/lib/libc.so.6'
        print('libc.so.6 => ' + target + ' (0x1234)')
    print('/lib64/ld-linux-x86-64.so.2 => /trace-runtime/loader (0x1234)')
    if defect == 'unknown':
        print('libforeign.so.1 => /trace-runtime/lib/libforeign.so.1 (0x1234)')
    if defect == 'duplicate':
        print('libc.so.6 => /trace-runtime/lib/libc.so.6 (0x1234)')
elif '--version' in sys.argv:
    print('strace -- version synthetic')
PY_CAPTURE
""" + ('  return 9\n' if fail else '') + '}\n'
        command = docker + shell_function(name) + '\n' + name + '\ntouch "$OUT/reached"\n'
        result = subprocess.run(['/bin/bash', '-euc', command], env=env, capture_output=True, text=True)
        calls = [json.loads(line) for line in (directory / 'argv.json').read_text().splitlines()]
        return result, calls

    def test_actual_app_argv_scopes_loader_path_to_tracer_and_preserves_network_audit(self):
        with tempfile.TemporaryDirectory() as temp:
            result, calls = self.capture(pathlib.Path(temp), 'start_traced_application')
            self.assertEqual(1, len(calls))
            argv = calls[0]
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('/trace-runtime/loader', argv[argv.index('--entrypoint') + 1])
            self.assert_readonly_tool_aliases(argv, pathlib.Path(temp))
            self.assertEqual(['--inhibit-cache', '--library-path', '/trace-runtime/lib',
                              '/trace-runtime/strace'], argv[argv.index('pinned-image') + 1:
                                                            argv.index('pinned-image') + 5])
            java = argv.index('java')
            tool = argv[argv.index('/trace-runtime/strace') + 1:java]
            self.assertEqual(['-f', '-s', '0', '-e', 'trace=connect,sendto,sendmsg',
                              '-E', 'LD_LIBRARY_PATH', '-E', 'LD_PRELOAD', '-E', 'LD_AUDIT',
                              '-o', '/evidence/network.trace'], tool)
            self.assertNotIn('--library-path', argv[java:])
            self.assertFalse(any(value.startswith(('LD_LIBRARY_PATH=', 'LD_PRELOAD=', 'LD_AUDIT='))
                                 for value in argv))
            self.assertEqual('SYS_PTRACE', argv[argv.index('--cap-add') + 1])
            self.assertEqual('synthetic-startup', argv[argv.index('--network') + 1])
            self.assertIn('/owned jar/app.jar:/application.jar:ro', argv)
            self.assertIn('/owned fixture:/fixture:ro', argv)
            self.assertIn('-jar', argv[java:])
            self.assertIn('/application.jar', argv[java:])

    def test_removing_nested_readonly_tool_alias_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = pathlib.Path(temp)
            result, calls = self.capture(directory, 'start_traced_application')
            self.assertEqual(0, result.returncode, result.stderr)
            argv = calls[0]
            argv.remove(str(directory) + '/trace-runtime:/evidence/trace-runtime:ro')
            with self.assertRaises(AssertionError):
                self.assert_readonly_tool_aliases(argv, directory)

    def test_actual_tool_preflight_is_readonly_no_network_and_failure_stops(self):
        for fail in (False, True):
            with self.subTest(fail=fail), tempfile.TemporaryDirectory() as temp:
                directory = pathlib.Path(temp)
                result, calls = self.capture(directory, 'check_trace_runtime', fail)
                self.assertEqual(1 if fail else 2, len(calls))
                for argv in calls:
                    self.assertEqual('none', argv[argv.index('--network') + 1])
                    self.assertIn('--read-only', argv)
                    self.assertIn('--rm', argv)
                    self.assertNotIn('java', argv)
                    self.assertNotIn('SYS_PTRACE', argv)
                    self.assertEqual('/trace-runtime/loader', argv[argv.index('--entrypoint') + 1])
                self.assertIn('--list', calls[0])
                if not fail:
                    self.assertEqual('--version', calls[1][-1])
                self.assertEqual(not fail, result.returncode == 0)
                self.assertEqual(not fail, (directory / 'reached').exists())

    def test_target_loader_fallback_missing_unknown_and_duplicate_paths_fail_closed(self):
        for defect in ('fallback', 'missing', 'unknown', 'duplicate'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as temp:
                directory = pathlib.Path(temp)
                result, calls = self.capture(directory, 'check_trace_runtime', resolution_defect=defect)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(1, len(calls))
                self.assertIn('--list', calls[0])
                self.assertFalse((directory / 'reached').exists())


if __name__ == '__main__':
    unittest.main()
