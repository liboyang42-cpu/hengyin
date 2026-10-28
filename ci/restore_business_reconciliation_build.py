#!/usr/bin/env python3
"""Share one exact compiled restore candidate across this fixed workflow's seven jobs."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('chengyinhub-admin', 'chengyinhub-framework', 'chengyinhub-system',
           'chengyinhub-quartz', 'chengyinhub-generator', 'chengyinhub-common')
JAR = 'chengyinhub-admin/target/chengyinhub-admin.jar'
MANIFEST = 'restore-build.json'


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def allowed(name):
    path = PurePosixPath(name)
    if path.is_absolute() or str(path) != name or '..' in path.parts:
        return False
    parts = path.parts
    if len(parts) < 3 or parts[0] not in MODULES or parts[1] != 'target':
        return False
    if name == JAR:
        return True
    if len(parts) < 4:
        return False
    if parts[2] == 'classes':
        return True
    return parts[0] == 'chengyinhub-admin' and (parts[2] == 'test-classes'
            or (parts[2] == 'restore-lib' and path.suffix == '.jar'))


def regular(path, root):
    require(path.is_file() and not path.is_symlink() and path.resolve() == path.absolute(), 'linked/nonregular build file')
    require(root in path.parents, 'build file escaped its owned root')


def pack(root, stage, identity):
    require(not stage.exists(), 'build stage must be fresh')
    stage.mkdir(mode=0o700)
    files = [root / JAR]
    for module in MODULES:
        classes = root / module / 'target/classes'
        if classes.is_dir():
            tree = list(classes.rglob('*'))
            require(not classes.is_symlink() and not any(path.is_symlink() for path in tree), 'linked compiled directory')
            files.extend(path for path in tree if path.is_file())
    tests = root / 'chengyinhub-admin/target/test-classes'
    require(tests.is_dir(), 'compiled test child required')
    tree = list(tests.rglob('*'))
    require(not tests.is_symlink() and not any(path.is_symlink() for path in tree), 'linked test directory')
    files.extend(path for path in tree if path.is_file())
    library_root = root / 'chengyinhub-admin/target/restore-lib'
    tree = list(library_root.rglob('*'))
    require(not library_root.is_symlink() and not any(path.is_symlink() for path in tree), 'linked test dependency directory')
    libraries = sorted(path for path in tree if path.is_file() and path.suffix == '.jar')
    require(libraries, 'existing Maven test dependencies required')
    files.extend(libraries)
    rows = {}
    for path in sorted(set(files)):
        regular(path, root)
        relative = path.relative_to(root).as_posix()
        require(allowed(relative), 'unregistered build path')
        destination = stage / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        rows[relative] = digest(destination)
        require(rows[relative] == digest(path), 'build bytes changed during staging')
    receipt = dict(identity, jarSha256=rows[JAR], files=rows)
    (stage / MANIFEST).write_text(json.dumps(receipt, sort_keys=True, indent=2) + '\n')
    return receipt


def unpack(root, stage, identity):
    regular(stage / MANIFEST, stage)
    receipt = json.loads((stage / MANIFEST).read_text())
    require(all(receipt.get(key) == value for key, value in identity.items()), 'foreign build source/run/attempt')
    rows = receipt.get('files')
    require(isinstance(rows, dict) and rows and rows.get(JAR) == receipt.get('jarSha256'), 'missing exact JAR manifest')
    actual = {p.relative_to(stage).as_posix() for p in stage.rglob('*') if p.is_file()} - {MANIFEST}
    require(actual == set(rows), 'missing or extra staged build file')
    for relative, checksum in rows.items():
        require(allowed(relative) and re.fullmatch('[0-9a-f]{64}', checksum), 'unsafe build manifest path/hash')
        source = stage / relative
        regular(source, stage)
        require(digest(source) == checksum, 'staged build hash mismatch')
        target = root / relative
        require(not target.exists() and target.resolve() == target.absolute(), 'fresh target build output required')
    # Verify every path and digest before writing the first candidate output.
    for relative, checksum in rows.items():
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(stage / relative, target)
        require(digest(target) == checksum, 'restored build hash mismatch')
    libraries = sorted((root / 'chengyinhub-admin/target/restore-lib').rglob('*.jar'))
    require(libraries, 'test dependency classpath missing')
    output = root / 'chengyinhub-admin/target/restore-dependency-classpath.txt'
    require(not output.exists(), 'classpath output already present')
    output.write_text(os.pathsep.join(str(path.resolve()) for path in libraries) + '\n')
    return receipt


def main():
    require(sys.argv[1:] in (['pack'], ['unpack']), 'fixed build transfer phase only')
    require('CHENGYIN_RESTORE_MODE' not in os.environ, 'restore mode must be absent in the build parent')
    for key, value in {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
                       'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin', 'GITHUB_EVENT_NAME': 'workflow_dispatch'}.items():
        require(os.environ.get(key) == value, 'wrong hosted build transfer scope')
    identity = {key: os.environ[name] for key, name in (('sourceSha', 'GITHUB_SHA'), ('runId', 'GITHUB_RUN_ID'),
                                                       ('runAttempt', 'GITHUB_RUN_ATTEMPT'))}
    require(re.fullmatch('[a-f0-9]{40}', identity['sourceSha']) and all(identity.values()), 'full build identity required')
    require(subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()
            == identity['sourceSha'], 'build checkout mismatch')
    stage = Path(os.environ['RUNNER_TEMP']).resolve() / 'restore-business-build'
    if sys.argv[1] == 'pack':
        require(os.environ.get('GITHUB_JOB') == 'restore-source-build', 'wrong build producer')
        receipt = pack(ROOT, stage, identity)
        # The summary binds every cohort to this verified once-build digest.
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            output.write('jar_sha256=' + receipt['jarSha256'] + '\n')
    else:
        require(os.environ.get('GITHUB_JOB') == 'isolated-restore-business-reconciliation', 'wrong build consumer')
        unpack(ROOT, stage, identity)


if __name__ == '__main__':
    main()
