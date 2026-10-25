#!/usr/bin/env python3
"""SOURCE controls for one-build transfer. All bytes are synthetic; no Java or Docker."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

SPEC = importlib.util.spec_from_file_location('restore_build', Path(__file__).with_name('restore_business_reconciliation_build.py'))
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)


class RestoreBuildControls(unittest.TestCase):
    identity = {'sourceSha': 'a' * 40, 'runId': '123', 'runAttempt': '1'}

    def source(self, root):
        for name, data in {
                build.JAR: b'same synthetic candidate JAR',
                'chengyinhub-admin/target/classes/git.properties': b'git.commit.id=' + b'a' * 40,
                'chengyinhub-admin/target/test-classes/restore/Child.class': b'controlled child bytes',
                'chengyinhub-common/target/classes/restore/Guard.class': b'controlled guard bytes',
                'chengyinhub-admin/target/restore-lib/group/module/1/module-1.jar': b'controlled original dependency',
                'chengyinhub-admin/target/classes/.retained-resource': b'original hidden resource'}.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    def test_one_build_is_byte_identical_in_two_distinct_job_workspaces(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp).resolve(); source = base / 'source'; source.mkdir(); self.source(source)
            stage = base / 'stage'; receipt = build.pack(source, stage, self.identity)
            for name in ('first', 'second'):
                target = base / name; target.mkdir()
                self.assertEqual(receipt, build.unpack(target, stage, self.identity))
                for relative, checksum in receipt['files'].items():
                    self.assertEqual(checksum, build.digest(target / relative))
                cp = (target / 'chengyinhub-admin/target/restore-dependency-classpath.txt').read_text().strip()
                self.assertEqual(str(target / 'chengyinhub-admin/target/restore-lib/group/module/1/module-1.jar'), cp)
                self.assertNotIn(str(source), cp)

    def test_source_run_attempt_and_every_hash_are_verified_before_first_target_write(self):
        for case in ('source', 'run', 'attempt', 'hash', 'extra'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp:
                base = Path(temp).resolve(); source = base / 'source'; source.mkdir(); self.source(source)
                stage = base / 'stage'; build.pack(source, stage, self.identity)
                receipt = json.loads((stage / build.MANIFEST).read_text())
                key = {'source': 'sourceSha', 'run': 'runId', 'attempt': 'runAttempt'}.get(case)
                if key:
                    receipt[key] = 'foreign'
                elif case == 'hash':
                    (stage / build.JAR).write_bytes(b'changed')
                else:
                    (stage / 'unexpected.env').write_text('controlled fixture only')
                (stage / build.MANIFEST).write_text(json.dumps(receipt))
                target = base / 'target'; target.mkdir()
                with self.assertRaises(ValueError):
                    build.unpack(target, stage, self.identity)
                self.assertEqual([], list(target.iterdir()))

    def test_existing_job_output_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp).resolve(); source = base / 'source'; source.mkdir(); self.source(source)
            stage = base / 'stage'; build.pack(source, stage, self.identity)
            target = base / 'target'; existing = target / build.JAR
            existing.parent.mkdir(parents=True); existing.write_bytes(b'existing task bytes')
            with self.assertRaises(ValueError):
                build.unpack(target, stage, self.identity)
            self.assertEqual(b'existing task bytes', existing.read_bytes())

    def test_links_in_a_class_directory_or_received_artifact_are_rejected(self):
        for case in ('pack', 'unpack'):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp:
                base = Path(temp).resolve(); source = base / 'source'; source.mkdir(); self.source(source)
                stage = base / 'stage'
                if case == 'pack':
                    link = source / 'chengyinhub-admin/target/classes/linked'
                    link.symlink_to(source / 'chengyinhub-common/target/classes', target_is_directory=True)
                    with self.assertRaises(ValueError):
                        build.pack(source, stage, self.identity)
                else:
                    build.pack(source, stage, self.identity)
                    jar = stage / build.JAR; jar.unlink(); jar.symlink_to(source / build.JAR)
                    target = base / 'target'; target.mkdir()
                    with self.assertRaises(ValueError):
                        build.unpack(target, stage, self.identity)
                    self.assertEqual([], list(target.iterdir()))

    def test_only_fixed_module_target_outputs_are_transferable(self):
        for name in ('../private.env', '/tmp/private', 'chengyinhub-admin/src/private.env',
                     'chengyinhub-admin/target/../../secret', 'chengyinhub-admin/target/settings.xml',
                     'chengyinhub-system/target/test-classes/Foreign.class',
                     'chengyinhub-admin/target/restore-lib/settings.xml', 'other/target/classes/A.class'):
            with self.subTest(name=name):
                self.assertFalse(build.allowed(name))
        for name in (build.JAR, 'chengyinhub-admin/target/classes/application.yml',
                     'chengyinhub-common/target/classes/A.class',
                     'chengyinhub-admin/target/test-classes/A.class',
                     'chengyinhub-admin/target/restore-lib/group/name/1/name-1.jar'):
            self.assertTrue(build.allowed(name))

    def test_workflow_builds_once_then_distributes_one_attempt_bound_artifact(self):
        workflow = (build.ROOT / '.github/workflows/isolated-restore-business-reconciliation.yml').read_text()
        self.assertEqual(1, workflow.count('mvn -B -pl chengyinhub-admin -am -DskipTests install'))
        producer, consumers = workflow.split('  isolated-restore-business-reconciliation:\n', 1)
        self.assertIn('dependency:copy-dependencies -DincludeScope=test', producer)
        self.assertIn('-Dmdep.useRepositoryLayout=true', producer)
        self.assertIn('include-hidden-files: true', producer)
        self.assertIn('test "${CHENGYIN_RESTORE_MODE+x}" != x', producer)
        self.assertIn('jar_sha256: ${{ steps.build.outputs.jar_sha256 }}', producer)
        self.assertIn('id: build', producer)
        self.assertIn('python3 -I ci/restore_business_reconciliation_build.py pack', producer)
        self.assertNotIn('mvn ', consumers)
        self.assertIn('python3 -I ci/restore_business_reconciliation_build.py unpack', consumers)
        self.assertEqual(2, workflow.count('name: restore-business-build-${{ github.run_attempt }}'))

    def test_pack_output_is_the_actual_staged_jar_digest_for_this_run(self):
        from unittest import mock
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve(); checkout = root / 'source'; checkout.mkdir(); self.source(checkout)
            output = root / 'job-output'
            env = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
                   'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                   'GITHUB_JOB': 'restore-source-build', 'GITHUB_SHA': self.identity['sourceSha'],
                   'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1', 'RUNNER_TEMP': str(root),
                   'GITHUB_OUTPUT': str(output)}
            with mock.patch.object(build, 'ROOT', checkout), mock.patch.dict(build.os.environ, env, clear=True), \
                 mock.patch.object(build.sys, 'argv', ['build', 'pack']), \
                 mock.patch.object(build.subprocess, 'check_output', return_value=self.identity['sourceSha'] + '\n'):
                build.main()
            receipt = json.loads((root / 'restore-business-build' / build.MANIFEST).read_text())
            self.assertEqual('jar_sha256=' + build.digest(checkout / build.JAR) + '\n', output.read_text())
            self.assertEqual(self.identity, {key: receipt[key] for key in self.identity})
            for value in ('', 'false', 'true', 'invalid'):
                with mock.patch.dict(build.os.environ, {'CHENGYIN_RESTORE_MODE': value}), \
                     mock.patch.object(build.sys, 'argv', ['build', 'pack']), \
                     self.subTest(value=value), self.assertRaises(ValueError):
                    build.main()


class RestoreManualRouterControls(unittest.TestCase):
    def sources(self):
        caller = (build.ROOT / '.github/workflows/backend-jar.yml').read_text()
        callee = (build.ROOT / '.github/workflows/isolated-restore-business-reconciliation.yml').read_text()
        return caller, callee

    def admission(self, source, name, end):
        return textwrap.dedent(source.split('      - name: ' + name + '\n        run: |\n', 1)[1].split(end, 1)[0])

    def invoke(self, script, **changes):
        env = {'SOURCE_SHA': 'a' * 40, 'GITHUB_SHA': 'a' * 40, 'WORKFLOW_SHA': 'a' * 40,
               'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'RUN_HTTP_FIXTURES': 'false', 'RUN_RESTORE_FIXTURES': 'false'}
        env.update(changes)
        # Execute only the real fixed admission block; no checkout, build, provider or resource command.
        return subprocess.run(['/bin/bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', script],
                              env=env, capture_output=True, timeout=5).returncode

    def test_original_jar_http_and_restore_modes_are_exclusive_before_child_jobs(self):
        caller, _ = self.sources()
        script = self.admission(caller, 'Validate source and exclusive mode', '\n  jar:\n')
        for http, restore, success in [('false', 'false', True), ('true', 'false', True),
                                       ('false', 'true', True), ('true', 'true', False)]:
            with self.subTest(http=http, restore=restore):
                self.assertEqual(success, self.invoke(script, RUN_HTTP_FIXTURES=http,
                                                     RUN_RESTORE_FIXTURES=restore) == 0)
        # The original build/HTTP inputs may name another reviewed commit; only restore requires the workflow commit.
        for http in ('false', 'true'):
            self.assertEqual(0, self.invoke(script, RUN_HTTP_FIXTURES=http, SOURCE_SHA='b' * 40))

    def test_restore_admission_rejects_foreign_source_context_and_unknown_modes(self):
        caller, _ = self.sources()
        script = self.admission(caller, 'Validate source and exclusive mode', '\n  jar:\n')
        for change in [{'SOURCE_SHA': value} for value in ('', 'a' * 39, 'A' * 40, 'g' * 40, 'b' * 40)] + [
                {'WORKFLOW_SHA': 'b' * 40}, {'GITHUB_SHA': 'b' * 40},
                {'GITHUB_REPOSITORY': 'foreign/repository'}, {'GITHUB_EVENT_NAME': 'push'},
                {'RUN_HTTP_FIXTURES': ''}, {'RUN_RESTORE_FIXTURES': 'invalid'}]:
            with self.subTest(change=change):
                self.assertNotEqual(0, self.invoke(script, **dict({'RUN_RESTORE_FIXTURES': 'true'}, **change)))

    def test_registered_router_reuses_only_the_same_commit_seven_cohort_workflow(self):
        caller, callee = self.sources()
        self.assertRegex(caller, r'run_restore_fixtures:\n        description:[^\n]+\n        required: false\n        type: boolean\n        default: false')
        self.assertEqual(3, caller.count('    needs: admission\n'))
        jar = caller.split('\n  jar:\n', 1)[1].split('\n  http-fixtures:\n', 1)[0]
        self.assertIn('&& !inputs.run_http_fixtures && !inputs.run_restore_fixtures\n', jar)
        restore = caller.split('\n  restore-fixtures:\n', 1)[1]
        self.assertIn("    if: github.repository == 'liboyang42-cpu/hengyin' && github.event_name == 'workflow_dispatch' && inputs.run_restore_fixtures && !inputs.run_http_fixtures\n", restore)
        self.assertIn('    uses: ./.github/workflows/isolated-restore-business-reconciliation.yml\n', restore)
        self.assertIn('      source_sha: ${{ inputs.source_sha }}\n', restore)
        self.assertNotIn('runs-on:', restore); self.assertNotIn('steps:', restore); self.assertNotIn('mvn ', restore)
        self.assertIn('      WORKFLOW_SHA: ${{ github.workflow_sha }}\n', caller)
        self.assertIn('  WORKFLOW_SHA: ${{ github.workflow_sha }}\n', callee)
        self.assertIn('  SOURCE_SHA: ${{ inputs.source_sha }}\n', callee)
        self.assertNotIn('inputs.source_sha ||', callee)
        self.assertRegex(callee, r'  workflow_call:\n    inputs:\n      source_sha:\n        description:[^\n]+\n        required: true\n        type: string')
        for workflow in (caller, callee):
            self.assertIn('  workflow_dispatch:', workflow)
            self.assertNotRegex(workflow, r'(?m)^\s+(?:push|pull_request|schedule|secrets|GITHUB_SHA):')
            self.assertNotIn('secrets.', workflow)
            self.assertIn('contents: read', workflow)
        self.assertEqual(1, caller.count('run: mvn -B -q -DskipTests package'))
        self.assertEqual(1, callee.count('mvn -B -pl chengyinhub-admin -am -DskipTests install'))
        self.assertIn('max-parallel: 20', callee)
        self.assertIn('needs: [restore-source-build, isolated-restore-business-reconciliation]', callee)

    def test_reusable_validates_real_source_before_checkout_and_preserves_downstream_identity(self):
        _, callee = self.sources()
        script = self.admission(callee, 'Validate caller source before checkout', '\n      - uses:')
        self.assertEqual(0, self.invoke(script))
        for change in [{'SOURCE_SHA': value} for value in ('', 'a' * 39, 'A' * 40, 'b' * 40)] + [
                {'GITHUB_SHA': 'b' * 40}, {'WORKFLOW_SHA': 'b' * 40}, {'WORKFLOW_SHA': ''},
                {'GITHUB_REPOSITORY': 'foreign/repository'}, {'GITHUB_EVENT_NAME': 'workflow_call'}]:
            with self.subTest(change=change):
                self.assertNotEqual(0, self.invoke(script, **change))
        self.assertLess(callee.index('name: Validate caller source before checkout'),
                        callee.index('uses: actions/checkout@v4'))
        consumer = callee.split('      - name: Verify and reuse the exact build without compiling again\n', 1)[1].split('      - name:', 1)[0]
        summary = callee.split('      - name: Validate caller source before summary\n', 1)[1].split('      - uses:', 1)[0]
        for source in (consumer, summary):
            self.assertIn('test "$SOURCE_SHA" = "$GITHUB_SHA"', source)
            self.assertIn('test "$SOURCE_SHA" = "$WORKFLOW_SHA"', source)


if __name__ == '__main__':
    unittest.main()
