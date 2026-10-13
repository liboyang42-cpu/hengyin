import importlib.util
import io
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
import zipfile

PATH = Path(__file__).resolve().parents[1] / "scripts/verify-candidate-resources.py"
SPEC = importlib.util.spec_from_file_location("candidate_resources", PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ResourceProofTest(unittest.TestCase):
    def fixture(self, root, compiled=b"current", packaged=b"current", extra=False):
        module = root / "chengyinhub-system"
        source = module / "src/main/resources/mapper/business/ImMessageMapper.xml"
        target = module / "target/classes/mapper/business/ImMessageMapper.xml"
        source.parent.mkdir(parents=True)
        target.parent.mkdir(parents=True)
        source.write_bytes(b"current")
        target.write_bytes(compiled)
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as archive:
            archive.writestr("mapper/business/ImMessageMapper.xml", packaged)
            if extra:
                archive.writestr("mapper/business/DeletedMapper.xml", b"leftover")
        jar = root / "candidate.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("BOOT-INF/lib/chengyinhub-system-1.jar", nested.getvalue())
        return jar

    def test_three_identical_resource_layers_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            report = MODULE.audit_resources(root, self.fixture(root))
            self.assertTrue(report["ready"])
            self.assertEqual(1, report["resourceCount"])

    def test_stale_compiled_or_packaged_resource_fails_even_when_source_is_current(self):
        for compiled, packaged in ((b"old", b"current"), (b"current", b"old"), (b"old", b"old")):
            with self.subTest(compiled=compiled, packaged=packaged), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                report = MODULE.audit_resources(root, self.fixture(root, compiled, packaged))
                self.assertFalse(report["ready"])
                self.assertTrue(report["problems"])

    def test_missing_compiled_and_duplicate_packaged_mappers_are_rejected(self):
        for mode in ('missing-compiled', 'duplicate-packaged'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); jar = self.fixture(root)
                if mode == 'missing-compiled':
                    (root / 'chengyinhub-system/target/classes/mapper/business/ImMessageMapper.xml').unlink()
                else:
                    nested = io.BytesIO()
                    with zipfile.ZipFile(nested, 'w') as archive:
                        import warnings
                        with warnings.catch_warnings():
                            warnings.simplefilter('ignore', UserWarning)
                            for _ in range(2): archive.writestr('mapper/business/ImMessageMapper.xml', b'current')
                    with zipfile.ZipFile(jar, 'w') as archive:
                        archive.writestr('BOOT-INF/lib/chengyinhub-system-1.jar', nested.getvalue())
                self.assertFalse(MODULE.audit_resources(root, jar)['ready'])

    def test_retained_dependency_checker_rejects_duplicate_libraries_before_version_checks(self):
        checker = PATH.with_name('verify_retained_dependency_package.py')
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / 'duplicate-libs.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    for _ in range(2): archive.writestr('BOOT-INF/lib/fixture-1.jar', b'synthetic')
            result = subprocess.run([sys.executable, str(checker), str(jar)], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Duplicate packaged library paths refused', result.stderr)

    def test_removed_mapper_left_in_jar_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            report = MODULE.audit_resources(root, self.fixture(root, extra=True))
            self.assertFalse(report["ready"])
            self.assertEqual("unexpected-packaged-mapper", report["problems"][0]["problem"])


if __name__ == "__main__":
    unittest.main()
