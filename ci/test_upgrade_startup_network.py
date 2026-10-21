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

    def capture(self, directory, name, fail=False, resolution_defect=None, report_result=None):
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
    vdso = '0x00007f1838e65000'
    # Source-based synthetic glibc display, not a sampled runtime auxiliary vector.
    metadata = ['AT_SYSINFO_EHDR: ' + vdso, 'AT_PAGESZ: 4096',
                'AT_EXECFN: /trace-runtime/loader', 'AT_PLATFORM: x86_64']
    if defect == 'anonymous-full-auxv':
        metadata += ['AT_MINSIGSTKSZ: 3632', 'AT_HWCAP: bfebfbff', 'AT_HWCAP2: 0x2',
                     'AT_CLKTCK: 100', 'AT_PHDR: 0x1000', 'AT_PHENT: 56', 'AT_PHNUM: 9',
                     'AT_BASE: 0x0', 'AT_FLAGS: 0x0', 'AT_ENTRY: 0x2000', 'AT_UID: 1001',
                     'AT_EUID: 1001', 'AT_GID: 1001', 'AT_EGID: 1001', 'AT_SECURE: 0',
                     'AT_RANDOM: 0x3000', 'AT_??? (0x1b): 0x1c', 'AT_??? (0x1c): 0x20',
                     'AT_FPUCW: 0x37f']
    if defect in ('auxv-missing', 'anonymous-no-auxv'):
        metadata.pop(0)
    elif defect == 'auxv-duplicate':
        metadata.append(metadata[0])
    elif defect == 'auxv-malformed':
        metadata[0] = 'AT_SYSINFO_EHDR: no-address'
    elif defect == 'auxv-zero':
        metadata[0] = 'AT_SYSINFO_EHDR: 0x0'
    elif defect == 'auxv-no-hex-prefix':
        metadata[0] = 'AT_SYSINFO_EHDR: 00007f1838e65000'
    elif defect == 'metadata-missing-colon':
        metadata[0] = 'AT_SYSINFO_EHDR ' + vdso
    elif defect == 'metadata-space':
        metadata[0] = ' ' + metadata[0]
    elif defect == 'metadata-tab':
        metadata[0] = chr(9) + metadata[0]
    elif defect == 'metadata-unknown':
        metadata.append('AT_FAKE: 1')
    elif defect == 'metadata-invalid-number':
        metadata[1] = 'AT_PAGESZ: /trace-runtime/lib/libforeign.so'
    elif defect == 'metadata-duplicate':
        metadata.append(metadata[1])
    elif defect == 'numeric-unknown':
        metadata.append('AT_??? (0x999): 0x1')
    elif defect == 'numeric-forged-vdso':
        metadata.append('AT_??? (0x21): ' + vdso)
    elif defect == 'numeric-duplicate':
        metadata += ['AT_??? (0x1b): 0x1c', 'AT_RSEQ_FEATURE_SIZE: 28']
    elif defect == 'numeric-malformed':
        metadata.append('AT_??? (0x1b): /trace-runtime/lib/libforeign.so')
    elif defect == 'numeric-tab':
        metadata.append(chr(9) + 'AT_??? (0x1b): 0x1c')
    print('\\n'.join(metadata))
    if defect == 'blank-row':
        print('')
    address = '0x5678' if defect in ('vdso-mismatch', 'anonymous-mismatch') else vdso
    anonymous = chr(9) + ' (' + address + ')'
    named = 'linux-vdso.so.1 (' + address + ')'
    print(anonymous if defect.startswith('anonymous') else named)
    if defect in ('anonymous-duplicate', 'anonymous-mixed'):
        print(anonymous if defect == 'anonymous-duplicate' else named)
    if defect == 'named-duplicate':
        print(named)
    if defect == 'metadata-late':
        print('AT_UID: 1001')
    if defect not in ('missing', 'anonymous-missing-lib'):
        target = '/lib/x86_64-linux-gnu/libc.so.6' if defect == 'fallback' else '/trace-runtime/lib/libc.so.6'
        print('libc.so.6 => ' + target + ' (0x1234)')
    print('/lib64/ld-linux-x86-64.so.2 => /trace-runtime/loader (0x1234)')
    if defect == 'unknown':
        print('libforeign.so.1 => /trace-runtime/lib/libforeign.so.1 (0x1234)')
    if defect == 'duplicate':
        print('libc.so.6 => /trace-runtime/lib/libc.so.6 (0x1234)')
    if defect == 'malformed':
        print(chr(9) + 'SYNTHETIC_UNKNOWN' + chr(27) + '[31m ' + chr(0x8def) + chr(9))
elif '--version' in sys.argv:
    print('strace -- version synthetic')
PY_CAPTURE
""" + ('  return 9\n' if fail else '') + '}\n'
        report = '' if report_result is None else ('report_trace_runtime_evidence() { touch "$OUT/report-called"; return ' + str(report_result) + '; }\n')
        command = docker + report + shell_function(name) + '\n' + name + '\ntouch "$OUT/reached"\n'
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
            self.assertFalse(any('LD_SHOW_AUXV' in value for value in argv))
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
                self.assertIn('LD_DEBUG=files', calls[0])
                if not fail:
                    self.assertFalse(any('LD_DEBUG' in value for value in calls[-1]))
                self.assertEqual('-e', calls[0][calls[0].index('LD_SHOW_AUXV=1') - 1])
                if not fail:
                    self.assertEqual('--version', calls[1][-1])
                    self.assertFalse(any('LD_SHOW_AUXV' in value for value in calls[1]))
                    self.assertIn('vDSO=0x7f1838e65000; kind=named; bundled dependencies=2', result.stdout)
                self.assertEqual(not fail, result.returncode == 0)
                self.assertEqual(not fail, (directory / 'reached').exists())

    def test_unrecognized_target_line_is_numbered_escaped_and_still_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = pathlib.Path(temp)
            result, calls = self.capture(directory, 'check_trace_runtime', resolution_defect='malformed')
            raw_line = '\tSYNTHETIC_UNKNOWN\x1b[31m \u8def\t'
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(1, len(calls))
            self.assertIn('Unrecognized target trace runtime dependency at line 8: ' + ascii(raw_line),
                          result.stderr)
            self.assertNotIn(raw_line, result.stderr)
            self.assertNotIn('\x1b', result.stderr)
            self.assertNotIn('\u8def', result.stderr)
            self.assertFalse((directory / 'reached').exists())

    def test_anonymous_mapping_requires_same_process_auxv_and_keeps_version_check(self):
        for fixture in ('anonymous', 'anonymous-full-auxv'):
            with self.subTest(fixture=fixture), tempfile.TemporaryDirectory() as temp:
                directory = pathlib.Path(temp)
                result, calls = self.capture(directory, 'check_trace_runtime', resolution_defect=fixture)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(2, len(calls))
                self.assertEqual('-e', calls[0][calls[0].index('LD_SHOW_AUXV=1') - 1])
                self.assertFalse(any('LD_SHOW_AUXV' in value for value in calls[1]))
                self.assertEqual('--version', calls[1][-1])
                self.assertIn('\t (0x00007f1838e65000)', (directory / 'trace-resolved').read_text().splitlines())
                self.assertIn('PASS target trace runtime same-process vDSO=0x7f1838e65000; kind=anonymous; bundled dependencies=2',
                              result.stdout)
                self.assertTrue((directory / 'reached').exists())

    def test_evidence_output_cannot_approve_original_runtime_rejection(self):
        for report_result in (0, 9):
            with self.subTest(report_result=report_result), tempfile.TemporaryDirectory() as temp:
                directory = pathlib.Path(temp)
                result, calls = self.capture(directory, 'check_trace_runtime',
                    resolution_defect='anonymous-mismatch', report_result=report_result)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('reason=address_mismatch', result.stderr)
                self.assertTrue((directory / 'report-called').exists())
                self.assertFalse((directory / 'reached').exists())
                self.assertEqual(1, len(calls))
                self.assertNotIn('PASS target trace runtime', result.stdout)

    def test_report_only_reads_metadata_escapes_rows_and_filters_image_environment(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = pathlib.Path(temp)
            bundle = directory / 'trace-runtime'; bundle.mkdir()
            (bundle / 'loader').write_bytes(b'synthetic non-ELF diagnostic fixture')
            (bundle / 'manifest.json').write_text(json.dumps({'interpreter': '/lib64/ld-linux-x86-64.so.2',
                'files': {'loader': 'recorded-hash'}}))
            (directory / 'trace-resolved').write_text('AT_SYSINFO_EHDR: 0x1234\n\t (0x5678)\nlinux-vdso.so.1 (0x1234)\n')
            (directory / 'trace-loader-debug').write_text('link map\x1b[31m\n')
            command = ("docker() { printf '%s\\n' \'[\"LD_TRACE_LOADED_OBJECTS=2\",\"LD_PRELOAD=preload-value-must-not-appear\",\"UNRELATED_VALUE=must-not-appear\"]\'; }\n"
                + shell_function('report_trace_runtime_evidence') + '\nreport_trace_runtime_evidence\n')
            result = subprocess.run(['/bin/bash', '-euc', command], capture_output=True, text=True,
                env={**os.environ, 'OUT': str(directory), 'JDK_IMAGE': 'pinned-image'})
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('linux-vdso.so.1 (0x1234)', result.stdout)
            self.assertIn('"LD_TRACE_LOADED_OBJECTS": "2"', result.stdout)
            self.assertIn('"LD_PRELOAD": {"present": true}', result.stdout)
            self.assertIn('recorded-hash', result.stdout)
            self.assertIn(hashlib.sha256((bundle / 'loader').read_bytes()).hexdigest(), result.stdout)
            self.assertIn('\\x1b', result.stdout)
            self.assertNotIn('\x1b', result.stdout)
            self.assertNotIn('must-not-appear', result.stdout)
            redacted = subprocess.run(['/bin/bash', '-euc', command.replace(
                'LD_TRACE_LOADED_OBJECTS=2', 'LD_TRACE_LOADED_OBJECTS=non-numeric-must-not-appear')],
                capture_output=True, text=True,
                env={**os.environ, 'OUT': str(directory), 'JDK_IMAGE': 'pinned-image'})
            self.assertEqual(0, redacted.returncode, redacted.stderr)
            self.assertIn('"LD_TRACE_LOADED_OBJECTS": "<non-numeric>"', redacted.stdout)
            self.assertNotIn('must-not-appear', redacted.stdout)

    def test_auxv_identity_metadata_and_anonymous_mapping_fail_closed(self):
        for defect in ('auxv-missing', 'auxv-duplicate', 'auxv-malformed', 'auxv-zero',
                       'auxv-no-hex-prefix', 'metadata-missing-colon', 'metadata-space', 'blank-row',
                       'vdso-mismatch', 'named-duplicate', 'anonymous-no-auxv',
                       'anonymous-mismatch', 'anonymous-duplicate', 'anonymous-mixed',
                       'metadata-tab', 'metadata-late', 'metadata-unknown',
                       'metadata-invalid-number', 'metadata-duplicate', 'anonymous-missing-lib',
                       'numeric-unknown', 'numeric-forged-vdso', 'numeric-duplicate',
                       'numeric-malformed', 'numeric-tab'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as temp:
                directory = pathlib.Path(temp)
                result, calls = self.capture(directory, 'check_trace_runtime', resolution_defect=defect)
                self.assertNotEqual(0, result.returncode, defect)
                self.assertNotIn('PASS target trace runtime', result.stdout)
                if defect in ('vdso-mismatch', 'anonymous-mismatch'):
                    kind = 'anonymous' if defect == 'anonymous-mismatch' else 'named'
                    self.assertIn('at line 5: reason=address_mismatch '
                                  'expected=0x7f1838e65000 actual=0x5678 '
                                  'vdso_seen=False kind=' + kind + ' previous_kind=none raw=',
                                  result.stderr)
                elif defect in ('named-duplicate', 'anonymous-duplicate', 'anonymous-mixed'):
                    previous = 'named' if defect == 'named-duplicate' else 'anonymous'
                    kind = 'anonymous' if defect == 'anonymous-duplicate' else 'named'
                    self.assertIn('at line 6: reason=duplicate_mapping '
                                  'expected=0x7f1838e65000 actual=0x7f1838e65000 '
                                  'vdso_seen=True kind=' + kind + ' previous_kind=' + previous + ' raw=',
                                  result.stderr)
                self.assertEqual(1, len(calls))
                self.assertIn('--list', calls[0])
                self.assertFalse((directory / 'reached').exists())

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
