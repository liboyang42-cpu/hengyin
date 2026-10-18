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


    def full_fixture(self, root):
        jar = self.fixture(root)
        with zipfile.ZipFile(jar) as archive:
            entries = [(n, archive.read(n)) for n in archive.namelist()]
        for name in ('application.yml', 'application-druid.yml'):
            relative = 'chengyinhub-admin/src/main/resources/' + name
            source = root / relative; source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(b'synthetic: ${SYNTHETIC_ENV}\n')
            target = root / 'chengyinhub-admin/target/classes' / name
            target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(source.read_bytes())
            entries.append(('BOOT-INF/classes/' + name, source.read_bytes()))
        devtools = root / 'chengyinhub-admin/src/main/resources/META-INF/spring-devtools.properties'
        devtools.parent.mkdir(parents=True, exist_ok=True); devtools.write_bytes(b'synthetic-devtools=true\n')
        compiled = root / 'chengyinhub-admin/target/classes/META-INF/spring-devtools.properties'
        compiled.parent.mkdir(parents=True, exist_ok=True); compiled.write_bytes(devtools.read_bytes())
        entries.append(('META-INF/spring-devtools.properties', devtools.read_bytes()))
        common = root / 'chengyinhub-common'; common.mkdir()
        (common / 'pom.xml').write_text('<project/>')
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, 'w') as archive:
            for name in ('META-INF/MANIFEST.MF', 'META-INF/maven/com.chengyinhub/chengyinhub-common/pom.properties', 'META-INF/maven/com.chengyinhub/chengyinhub-common/pom.xml'):
                archive.writestr(name, b'synthetic-build-metadata')
        entries.append(('BOOT-INF/lib/chengyinhub-common-1.jar', nested.getvalue()))
        upstream = io.BytesIO()
        with zipfile.ZipFile(upstream, 'w') as archive:
            archive.writestr('META-INF/spring.factories', b'synthetic-upstream-registration')
            archive.writestr('META-INF/spring-configuration-metadata.json', b'{}')
        entries.append(('BOOT-INF/lib/upstream-1.jar', upstream.getvalue()))
        entries.append(('BOOT-INF/classes/', b''))
        entries.append(('BOOT-INF/classes/git.properties', b'git.commit.id=' + b'a' * 40 + b'\n'))
        self.rewrite_zip(jar, entries)
        return jar

    def rewrite_zip(self, jar, entries):
        import warnings
        with zipfile.ZipFile(jar, 'w') as archive, warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            for name, value in entries: archive.writestr(name, value)

    def test_full_main_resources_include_configuration_and_preserve_default_mapper_contract(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); jar = self.full_fixture(root)
            report = MODULE.audit_resources(root, jar, all_main_resources=True)
            self.assertTrue(report['ready'], report['problems']); self.assertEqual(4, report['resourceCount'])
            self.assertEqual(1, MODULE.audit_resources(root, jar)['resourceCount'])

    def test_full_mode_rejects_stale_source_compiled_and_packaged_configuration(self):
        for layer in ('source', 'compiled', 'packaged', 'wrong-devtools-location', 'duplicate-devtools-location', 'stale-devtools'):
            with self.subTest(layer=layer), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); jar = self.full_fixture(root)
                if layer == 'source':
                    (root / 'chengyinhub-admin/src/main/resources/application.yml').write_bytes(b'changed')
                elif layer == 'compiled':
                    (root / 'chengyinhub-admin/target/classes/application.yml').write_bytes(b'changed')
                else:
                    with zipfile.ZipFile(jar) as archive:
                        entries = [(n, archive.read(n)) for n in archive.namelist()]
                    if layer == 'packaged':
                        entries = [(n, b'changed' if n == 'BOOT-INF/classes/application.yml' else value) for n, value in entries]
                    else:
                        name = 'META-INF/spring-devtools.properties'
                        data = next(v for n, v in entries if n == name)
                        if layer == 'wrong-devtools-location':
                            entries = [('BOOT-INF/classes/' + n if n == name else n, v) for n, v in entries]
                        elif layer == 'duplicate-devtools-location': entries.append(('BOOT-INF/classes/' + name, data))
                        else: entries = [(n, b'stale' if n == name else v) for n, v in entries]
                    self.rewrite_zip(jar, entries)
                self.assertFalse(MODULE.audit_resources(root, jar, True)['ready'])

    def test_full_mode_rejects_outer_and_nested_duplicate_zip_entries(self):
        for mode in ('outer-config', 'outer-git', 'own-library', 'dependency-library'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); jar = self.full_fixture(root)
                with zipfile.ZipFile(jar) as archive:
                    entries = [(n, archive.read(n)) for n in archive.namelist()]
                if mode.startswith('outer'):
                    name = 'BOOT-INF/classes/' + ('application.yml' if mode == 'outer-config' else 'git.properties')
                    entries.append(next(row for row in entries if row[0] == name))
                else:
                    nested = io.BytesIO()
                    import warnings
                    with zipfile.ZipFile(nested, 'w') as archive, warnings.catch_warnings():
                        warnings.simplefilter('ignore', UserWarning)
                        archive.writestr('duplicate', b'one'); archive.writestr('duplicate', b'two')
                    if mode == 'own-library': entries[0] = (entries[0][0], nested.getvalue())
                    else: entries.append(('BOOT-INF/lib/fixture-1.jar', nested.getvalue()))
                self.rewrite_zip(jar, entries)
                report = MODULE.audit_resources(root, jar, True)
                self.assertFalse(report['ready']); self.assertTrue(any(p['problem'] == 'duplicate-or-corrupt-zip-entry' for p in report['problems']))

    def test_full_mode_rejects_extra_credentials_and_unreviewed_resources(self):
        for name in ('.env.local', 'apiclient_key.pem', 'runtime-secret.txt', 'META-INF/spring.factories', 'META-INF/spring-configuration-metadata.json'):
            for layer in ('application', 'own-library', 'zero-source-library', 'archive-root'):
                with self.subTest(name=name, layer=layer), tempfile.TemporaryDirectory() as temp:
                    root = Path(temp); jar = self.full_fixture(root)
                    with zipfile.ZipFile(jar) as archive:
                        entries = [(n, archive.read(n)) for n in archive.namelist()]
                    if layer == 'application': entries.append(('BOOT-INF/classes/' + name, b'synthetic-not-a-secret'))
                    elif layer == 'archive-root':
                        if name not in ('.env.local', 'apiclient_key.pem'): continue
                        entries.append((name, b'synthetic-not-a-secret'))
                    else:
                        index = 0 if layer == 'own-library' else next(i for i, row in enumerate(entries) if row[0] == 'BOOT-INF/lib/chengyinhub-common-1.jar')
                        nested = io.BytesIO(entries[index][1])
                        with zipfile.ZipFile(nested, 'a') as archive: archive.writestr(name, b'synthetic-not-a-secret')
                        entries[index] = (entries[index][0], nested.getvalue())
                    self.rewrite_zip(jar, entries)
                    self.assertFalse(MODULE.audit_resources(root, jar, True)['ready'])

    def test_full_mode_rejects_symlink_resource(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); jar = self.full_fixture(root)
            (root / 'chengyinhub-admin/src/main/resources/alias.yml').symlink_to('application.yml')
            with self.assertRaises(ValueError): MODULE.audit_resources(root, jar, True)

if __name__ == "__main__":
    unittest.main()
