#!/usr/bin/env python3
"""SOURCE controls for the existing restore HTTP host call; no services/Java are launched.

The EXIT/runner controls use only shell functions in place of every external effect.
These controls do not prove the actual application, database, provider or cleanup worked.
"""
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SHELL = ROOT / 'ci/authenticated-redis-recovery.sh'
WORKFLOW = ROOT / '.github/workflows/isolated-authenticated-redis-recovery.yml'
RESTORE_IF = 'if [ "${CHENGYIN_CI_RESTORE_HTTP_STARTUP:-false}" = true ]; then'
FLAG = "CHENGYIN_CI_RESTORE_HTTP_STARTUP: ${{ github.event_name == 'workflow_dispatch' && inputs.probe_mode == 'restore-application-startup' && 'true' || 'false' }}"
BUDGET = "timeout-minutes: ${{ github.event_name == 'workflow_dispatch' && inputs.probe_mode == 'restore-application-startup' && 55 || 25 }}"
READY = 'python3 -B "$ROOT/ci/authenticated_redis_recovery_probe.py" restore-mysql-ready "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json" 0'
RUN = 'timeout --signal=TERM --kill-after=20s 1550s python3 -I "$ROOT/ci/restore_application_http_probe.py" run "$RUN_NAME" "$SCHEMA" "$OUT" "$PRIVATE/credentials.json"'
FINAL = 'python3 -I "$ROOT/ci/restore_application_http_probe.py" finalize "$RUN_NAME" "$SCHEMA" "$OUT" "$result"'
OLD_UPLOAD = "if: ${{ !cancelled() && steps.recovery.outcome != 'skipped' && !(github.event_name == 'workflow_dispatch' && inputs.probe_mode == 'restore-application-startup') }}"
NEW_UPLOAD = "if: ${{ !cancelled() && steps.recovery.outcome != 'skipped' && github.event_name == 'workflow_dispatch' && inputs.probe_mode == 'restore-application-startup' }}"
PUBLIC = ['restore-http-application.json', 'restore-http-provenance.json', 'restore-http-cleanup.json']


def restore_branch(shell):
    tail = shell.split('REDIS_IP=$(docker inspect', 1)[1]
    return RESTORE_IF + tail.split(RESTORE_IF, 1)[1].split(
        'if [ "${CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY:-false}" = true ]; then', 1)[0]


def contract_problems(shell, workflow):
    """Check only these two fixed entry files, not a production/SQL scanner."""
    problems = []
    options = re.search(r'        options:\n((?:          - [^\n]+\n)+)', workflow)
    expected = ['recovery', 'capacity-mixed', 'gameplay-process-recovery',
                'gameplay-capacity-mixed', 'packaged-gameplay-capacity', 'restore-application-startup']
    if not options or re.findall(r'- ([^\n]+)', options[1]) != expected:
        problems.append('six exact choices retaining original relative order required')
    for text, count in ((FLAG, 2), ('        default: recovery', 1), (BUDGET, 1),
                        ('python3 -B ci/test_restore_application_http_probe.py', 1),
                        ('python3 -B ci/test_restore_application_http_entry.py', 1),
                        ('mvn -B -DskipTests package', 1), (OLD_UPLOAD, 1), (NEW_UPLOAD, 1)):
        if workflow.count(text) != count:
            problems.append('fixed mode/build/control/upload contract changed')
    if ('CHENGYIN_RESTORE_MODE:' in workflow or 'export CHENGYIN_RESTORE_MODE' in shell
            or '-DCHENGYIN_RESTORE_MODE' in shell):
        problems.append('restore business flag must stay absent in parent')
    try:
        controls = workflow.split('      - name: Verify hosted target and pure negative controls\n', 1)[1].split(
            '      - name: Install existing pinned schema client\n', 1)[0]
        conditional = controls.split('if [ "$CHENGYIN_CI_RESTORE_HTTP_STARTUP" = true ]; then\n', 1)[1].split('          fi', 1)[0]
        if not all('python3 -B ci/' + name in conditional for name in
                   ('test_restore_application_http_probe.py', 'test_restore_application_http_entry.py')):
            problems.append('restore controls must execute once in the existing check stage')
        branch = restore_branch(shell)
        if not all(text in branch for text in (READY, RUN, 'test "$GITHUB_EVENT_NAME" = workflow_dispatch',
                '--name "$RUN_NAME-restore-mysql"', '--label "chengyin.auth-recovery=$RUN_NAME" --network "$RUN_NAME"',
                '--memory=1g --memory-swap=1g --cpus=1 --tmpfs /var/lib/mysql:rw,size=768m',
                '-e MYSQL_ROOT_PASSWORD -e MYSQL_ROOT_HOST=% "$MYSQL_IMAGE"', '  exit 0\nfi\n')):
            problems.append('exact original target owner/runner/readiness contract required')
        if branch.index(READY) > branch.index(RUN) or '--publish' in branch or ' -p ' in branch:
            problems.append('target native readiness must precede run without published ports')
        cleanup = shell.split('cleanup() {\n', 1)[1].split('\n}\ntrap cleanup EXIT', 1)[0]
        if (FINAL not in cleanup or 'local result=$? unknown=0' not in cleanup
                or cleanup.index('authenticated_redis_recovery_probe.py" cleanup') > cleanup.index(FINAL)
                or cleanup.index('rmdir "$PRIVATE" || unknown=1') > cleanup.index(FINAL)
                or cleanup.index(RESTORE_IF) > cleanup.index('GAMEPLAY_PROCESS_RECOVERY')):
            problems.append('restore finalize must follow original outer/private cleanup and preserve first exit')
        upload = workflow.split('      - name: Preserve only the three scoped restore HTTP JSON receipts\n', 1)[1]
        paths = upload.split('          path: |\n', 1)[1].splitlines()
        prefix = '${{ runner.temp }}/isolated-authenticated-redis-recovery/cy-auth.*/'
        if [line.strip() for line in paths if line.strip()] != [prefix + name for name in PUBLIC]:
            problems.append('only three exact public restore JSON paths allowed')
    except (IndexError, ValueError):
        problems.append('fixed host-call structure absent')
    return problems


class RestoreHttpEntryControls(unittest.TestCase):
    def test_real_fixed_entry_contract_preserves_original_modes_and_one_build(self):
        self.assertEqual([], contract_problems(SHELL.read_text(), WORKFLOW.read_text()))

    def test_mode_default_budget_gates_and_public_paths_negative_controls(self):
        shell, workflow = SHELL.read_text(), WORKFLOW.read_text()
        cases = [('workflow', '        default: recovery', '        default: restore-application-startup'),
                 ('workflow', FLAG, 'CHENGYIN_CI_RESTORE_HTTP_STARTUP: true'),
                 ('workflow', '&& 55 || 25', '&& 55 || 55'),
                 ('workflow', OLD_UPLOAD, "if: ${{ !cancelled() }}"),
                 ('workflow', NEW_UPLOAD, "if: ${{ always() }}"),
                 ('workflow', 'cy-auth.*/restore-http-cleanup.json', '**/private/**'),
                 ('workflow', 'python3 -B ci/test_restore_application_http_probe.py', 'true'),
                 ('shell', READY, READY.replace('restore-mysql-ready', 'mysql-ready')),
                 ('shell', RUN, RUN.replace('1550s', '1500s')),
                 ('shell', FINAL, FINAL.replace('"$result"', '0')),
                 ('shell', '--name "$RUN_NAME-restore-mysql"', '--publish 3306:3306 --name "$RUN_NAME-restore-mysql"')]
        for target, old, new in cases:
            with self.subTest(target=target, old=old):
                original = shell if target == 'shell' else workflow
                self.assertIn(old, original)
                mutated = original.replace(old, new)
                self.assertTrue(contract_problems(mutated if target == 'shell' else shell,
                                                  mutated if target == 'workflow' else workflow))
        self.assertTrue(contract_problems(shell, workflow + '\n    CHENGYIN_RESTORE_MODE: ""\n'))

    def test_actual_exit_function_keeps_first_failure_and_finalizes_after_outer_cleanup(self):
        source = SHELL.read_text()
        cleanup = 'cleanup() {\n' + source.split('cleanup() {\n', 1)[1].split(
            '\n}\ntrap cleanup EXIT', 1)[0] + '\n}'
        # Every external operation is replaced by a shell function, as in the original capacity controls.
        for body, outer, private, finalizer, expected, passed in (
                (0, 0, 0, 0, 0, 0), (7, 0, 0, 0, 7, 7), (124, 1, 0, 1, 124, 124),
                (0, 19, 0, 1, 1, 1), (0, 0, 23, 1, 1, 1), (0, 0, 0, 29, 1, 0)):
            with self.subTest(body=body, outer=outer, private=private, finalizer=finalizer):
                stub = '''set -u
ROOT=/source-only
RUN_NAME=owned
SCHEMA=owned_schema
OUT=/public-only
PRIVATE=/private-only
CHENGYIN_CI_RESTORE_HTTP_STARTUP=true
CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY=false
CHENGYIN_CI_CAPACITY_MIXED=false
python3() {
  case "$3" in
    cleanup) printf 'OUTER_CLEANUP\\n'; return OUTER_STATUS ;;
    finalize)
      [[ "$2" == */restore_application_http_probe.py && "$#" == 7 ]] || return 97
      printf 'FINALIZE:%s\\n' "$7"; return FINALIZER_STATUS ;;
    *) return 98 ;;
  esac
}
rm() { return 0; }
rmdir() { printf 'PRIVATE_CLEANUP\\n'; return PRIVATE_STATUS; }
'''.replace('OUTER_STATUS', str(outer)).replace('PRIVATE_STATUS', str(private)).replace('FINALIZER_STATUS', str(finalizer))
                result = subprocess.run(['bash', '-c', stub + cleanup + '\ntrap cleanup EXIT\nexit ' + str(body)],
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(expected, result.returncode, result.stderr)
                lines = result.stdout.splitlines()
                self.assertEqual(1, sum(line.startswith('FINALIZE:') for line in lines))
                self.assertLess(lines.index('OUTER_CLEANUP'), lines.index('PRIVATE_CLEANUP'))
                self.assertLess(lines.index('PRIVATE_CLEANUP'), lines.index('FINALIZE:' + str(passed)))

    def test_actual_restore_branch_runs_once_after_native_readiness_without_old_exercise(self):
        branch = restore_branch(SHELL.read_text())
        for event, ready, run, expected in (('workflow_dispatch', 0, 0, 0), ('workflow_dispatch', 0, 7, 7),
                                             ('workflow_dispatch', 17, 0, 17), ('pull_request', 0, 0, 1)):
            with self.subTest(event=event, ready=ready, run=run):
                stub = '''set -eu
ROOT=/source-only
RUN_NAME=owned
SCHEMA=owned_schema
OUT=/public-only
PRIVATE=/private-only
MYSQL_IMAGE=pinned-original-image
CHENGYIN_CI_RESTORE_HTTP_STARTUP=true
GITHUB_EVENT_NAME=__EVENT_VALUE__
# A separate witness FD survives the real docker command's stdout redirection.
exec 3>&1
printf 'EVENT:%s\\n' "$GITHUB_EVENT_NAME" >&3
docker() { printf 'CREATE_TARGET\\n' >&3; return 0; }
python3() {
  case "$3" in
    restore-mysql-ready) printf 'NATIVE_READY\\n'; return READY_STATUS ;;
    run) [[ "$#" == 7 && "$2" == */restore_application_http_probe.py ]] || return 97
         printf 'RUN_ONCE\\n'; return RUN_STATUS ;;
    *) return 98 ;;
  esac
}
timeout() {
  [[ "$1" == --signal=TERM && "$2" == --kill-after=20s && "$3" == 1550s ]] || return 96
  shift 3; "$@"
}
'''.replace('__EVENT_VALUE__', event).replace('READY_STATUS', str(ready)).replace('RUN_STATUS', str(run))
                result = subprocess.run(['bash', '-c', stub + branch], capture_output=True, text=True, timeout=5)
                self.assertEqual(expected, result.returncode, result.stderr)
                lines = result.stdout.splitlines()
                self.assertIn('EVENT:' + event, lines)
                self.assertEqual(1 if event == 'workflow_dispatch' else 0, lines.count('CREATE_TARGET'))
                self.assertEqual(1 if event == 'workflow_dispatch' else 0, lines.count('NATIVE_READY'))
                should_run = event == 'workflow_dispatch' and ready == 0
                self.assertEqual(1 if should_run else 0, lines.count('RUN_ONCE'))
                if should_run:
                    self.assertLess(lines.index('CREATE_TARGET'), lines.index('NATIVE_READY'))
                    self.assertLess(lines.index('NATIVE_READY'), lines.index('RUN_ONCE'))


if __name__ == '__main__':
    unittest.main()
