#!/usr/bin/env python3
"""SOURCE controls for one-build transfer. All bytes are synthetic; no Java or Docker."""
import importlib.util
import json
from pathlib import Path
import tempfile
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
        self.assertIn('python3 -I ci/restore_business_reconciliation_build.py pack', producer)
        self.assertNotIn('mvn ', consumers)
        self.assertIn('python3 -I ci/restore_business_reconciliation_build.py unpack', consumers)
        self.assertEqual(2, workflow.count('name: restore-business-build-${{ github.run_attempt }}'))


if __name__ == '__main__':
    unittest.main()
