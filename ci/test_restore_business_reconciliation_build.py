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

    def test_changed_journal_unit_reuses_install_before_packing_one_candidate(self):
        workflow = (build.ROOT / '.github/workflows/isolated-restore-business-reconciliation.yml').read_text()
        producer, consumers = workflow.split('  isolated-restore-business-reconciliation:\n', 1)
        install = 'mvn -B -pl chengyinhub-admin -am -DskipTests install'
        unit = 'mvn -B -pl chengyinhub-admin -Dtest=RestoreRefundObservationSourceTest test'
        gate = 'python3 -I ci/assert_it_ran.py chengyinhub-admin/target/surefire-reports RestoreRefundObservationSourceTest'
        pack = 'python3 -I ci/restore_business_reconciliation_build.py pack'
        self.assertEqual(1, producer.count(unit))
        self.assertLess(producer.index(install), producer.index(unit))
        self.assertLess(producer.index(unit), producer.index(gate))
        self.assertLess(producer.index(gate), producer.index(pack))
        self.assertIn('name: restore-business-source-unit-${{ github.run_attempt }}', producer)
        self.assertIn('TEST-com.chengyinhub.business.service.impl.RestoreRefundObservationSourceTest.xml', producer)
        for module in ('test_restore_business_reconciliation_probe', 'test_restore_business_domain_facts',
                       'test_restore_business_reconciliation_host', 'test_restore_business_reconciliation_build',
                       'test_combined_http_budget', 'test_restore_application_http_probe', 'test_http_e2e_entry'):
            command = 'python3 -B ci/' + module + '.py'
            self.assertEqual(1, producer.count(command))
            self.assertLess(producer.index(command), producer.index(install))
        self.assertNotIn('mvn ', consumers)

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

    def test_voice_diagnostic_runs_only_impacted_unit_and_controls_before_pack(self):
        workflow = (build.ROOT / '.github/workflows/isolated-restore-business-reconciliation.yml').read_text()
        producer, consumers = workflow.split('  isolated-restore-business-reconciliation:\n', 1)
        branch = producer.split('          if [[ "$RESTORE_SCOPE" = voice-gap-diagnostic ]]; then\n', 1)[1]
        voice, remainder = branch.split('          else\n', 1)
        self.assertIn('mvn -B -pl chengyinhub-admin -Dtest=RestoreVoiceReconciliationFixtureTest test', voice)
        self.assertIn('python3 -I ci/assert_it_ran.py chengyinhub-admin/target/surefire-reports RestoreVoiceReconciliationFixtureTest', voice)
        self.assertNotIn('RestoreRefundObservationSourceTest', voice)
        self.assertIn('RestoreRefundObservationSourceTest', remainder.split('          fi\n', 1)[0])
        self.assertIn('TEST-com.chengyinhub.business.service.ai.RestoreVoiceReconciliationFixtureTest.xml', producer)
        unrelated = producer.split('          if [[ "$RESTORE_SCOPE" != voice-gap-diagnostic ]]; then\n', 1)[1].split('          fi\n', 1)[0]
        for module in ('test_combined_http_budget', 'test_restore_application_http_probe', 'test_http_e2e_entry'):
            self.assertIn('python3 -B ci/' + module + '.py', unrelated)
        for module in ('test_restore_business_reconciliation_probe', 'test_restore_business_domain_facts',
                       'test_restore_business_reconciliation_host', 'test_restore_business_reconciliation_build'):
            self.assertIn('python3 -B ci/' + module + '.py', producer.split('          if [[ "$RESTORE_SCOPE" != voice-gap-diagnostic ]]; then\n', 1)[0])
        self.assertEqual(1, producer.count('mvn -B -pl chengyinhub-admin -am -DskipTests install'))
        self.assertNotIn('mvn ', consumers)


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
               'RUN_HTTP_FIXTURES': 'false', 'RUN_RESTORE_FIXTURES': 'false', 'RESTORE_SCOPE': 'full-seven'}
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

    def test_diagnostic_scope_is_explicit_fixed_and_legal_only_for_restore_without_http(self):
        caller, callee = self.sources()
        parent = self.admission(caller, 'Validate source and exclusive mode', '\n  jar:\n')
        child = self.admission(callee, 'Validate caller source before checkout', '\n      - uses:')
        for scope in ('payment-gap-diagnostic', 'voice-gap-diagnostic'):
            for http, restore, success in [('false', 'false', False), ('true', 'false', False),
                                           ('false', 'true', True), ('true', 'true', False)]:
                with self.subTest(scope=scope, http=http, restore=restore):
                    self.assertEqual(success, self.invoke(parent, RESTORE_SCOPE=scope,
                        RUN_HTTP_FIXTURES=http, RUN_RESTORE_FIXTURES=restore) == 0)
            self.assertEqual(0, self.invoke(child, RESTORE_SCOPE=scope))
        for invalid in ('', 'payment-gap', 'voice-gap', 'all', 'full-seven,voice-gap', 'foreign'):
            with self.subTest(scope=invalid):
                self.assertNotEqual(0, self.invoke(parent, RESTORE_SCOPE=invalid, RUN_RESTORE_FIXTURES='true'))
                self.assertNotEqual(0, self.invoke(child, RESTORE_SCOPE=invalid))
        for source in (caller, callee):
            self.assertRegex(source, r'restore_scope:\n        description:[^\n]+\n        required: false\n        type: choice\n        default: full-seven\n        options:\n          - full-seven\n          - payment-gap-diagnostic\n          - voice-gap-diagnostic')
        self.assertIn('      restore_scope: ${{ inputs.restore_scope }}\n', caller)
        called = callee.split('  workflow_call:', 1)[1].split('permissions:', 1)[0]
        self.assertRegex(called, r'restore_scope:\n        description:[^\n]+\n        required: false\n        type: string\n        default: full-seven')

    def test_diagnostic_job_names_and_summary_fence_never_claim_full_seven_acceptance(self):
        caller, callee = self.sources()
        self.assertIn("name: ${{ inputs.restore_scope != 'full-seven' && format('DIAGNOSTIC_PARTIAL {0} restore', inputs.restore_scope) || 'Run seven isolated restore cohorts' }}", caller)
        self.assertIn("name: ${{ inputs.restore_scope != 'full-seven' && format('DIAGNOSTIC_PARTIAL {0}', matrix.cohort) || format('Restore {0}', matrix.cohort) }}", callee)
        consumer, summary = callee.split('  restore-business-summary:\n', 1)
        self.assertIn("    if: ${{ always() && inputs.restore_scope == 'full-seven' }}\n", summary)
        self.assertNotIn('--summarize', consumer)
        self.assertEqual(1, summary.count('python3 -I ci/restore_business_reconciliation_host.py --summarize'))
        marker = consumer.split('      - name: Mark DIAGNOSTIC_PARTIAL scope without a full verdict\n', 1)[1]
        self.assertIn("if: ${{ always() && inputs.restore_scope != 'full-seven' }}", marker)
        self.assertIn('NOT_FULL:', marker)
        self.assertIn('seven-receipt/three-positive verifier were not run', marker)
        self.assertIn('"$GITHUB_STEP_SUMMARY"', marker)
        self.assertIn('pattern: isolated-restore-business-reconciliation-*-${{ github.run_attempt }}', summary)

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
