#!/usr/bin/env python3
"""Fixed hosted resource owner for the existing restore probe, never a production runner."""
from __future__ import annotations

import importlib.util
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'mysql:8.0.45@sha256:4af1f8815716546f5b12410f7621f37f93db8dd11a184706ef59111930b8c2ff'
DOCKER = ['docker', '--context', 'default']
PROBE = ROOT / 'ci/restore_business_reconciliation_probe.py'
COHORTS = ('payment-gap', 'withdrawal-complete', 'withdrawal-gap', 'refund-complete',
           'refund-gap', 'voice-complete', 'voice-gap')


def require(value, reason):
    if not value:
        raise ValueError(reason)


def original_helpers():
    spec = importlib.util.spec_from_file_location('restore_owned_helpers', ROOT / 'ci/authenticated_redis_recovery_probe.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    return helper


def domain_readers():
    spec = importlib.util.spec_from_file_location('restore_fixed_domains', ROOT / 'ci/restore_business_domain_facts.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    return helper


def hosted(env, head, endpoint):
    expected = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted',
                'RUNNER_OS': 'Linux', 'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin',
                'GITHUB_EVENT_NAME': 'workflow_dispatch',
                'GITHUB_JOB': 'isolated-restore-business-reconciliation',
                'CHENGYIN_CI_RESTORE_BUSINESS': 'true'}
    require(all(env.get(key) == value for key, value in expected.items()), 'wrong hosted restore scope')
    require(re.fullmatch('[0-9a-f]{40}', head) and env.get('GITHUB_SHA') == head, 'wrong source SHA')
    require(endpoint == 'unix:///var/run/docker.sock', 'wrong Docker daemon')
    require(env.get('CHENGYIN_RESTORE_BUSINESS_COHORT') in COHORTS, 'unregistered restore cohort')
    require('CHENGYIN_RESTORE_MODE' not in env, 'restore mode must be set only on fixed drill children')
    require(not any(env.get(key) for key in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'JAVA_TOOL_OPTIONS',
                                            'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')), 'ambient execution override')


def write_private(path, value):
    with Path(path).open('x', encoding='utf-8') as handle:
        os.chmod(path, 0o600)
        handle.write(value)
        handle.flush()
        os.fsync(handle.fileno())


def classpath(root, dependencies):
    entries = [root / 'chengyinhub-admin/target/test-classes']
    entries.extend(sorted(root.glob('chengyinhub-*/target/classes')))
    entries.extend(Path(value) for value in dependencies.strip().split(os.pathsep) if value)
    result = []
    maven = (Path.home() / '.m2/repository').resolve()
    for entry in entries:
        require(entry.is_absolute() and entry.exists() and not entry.is_symlink(), 'missing/linked classpath entry')
        canonical = entry.resolve()
        require(str(canonical) == str(entry), 'noncanonical classpath entry')
        owned = root in canonical.parents and '/target/' in str(canonical)
        dependency = maven in canonical.parents and canonical.suffix == '.jar'
        require(owned or dependency, 'foreign classpath entry')
        if canonical not in result:
            result.append(canonical)
    require(result and result[0] == root / 'chengyinhub-admin/target/test-classes', 'fixed child classes first')
    return os.pathsep.join(str(value) for value in result)


def inspect(kind, identity):
    result = subprocess.run(DOCKER + [kind, 'inspect', identity], capture_output=True, timeout=20)
    if result.returncode:
        original_helpers().require_absent(kind, identity, result.returncode, result.stderr)
        return None
    rows = json.loads(result.stdout)
    require(len(rows) == 1, 'ambiguous resource identity')
    return rows[0]


def owned_object(kind, row, scope, side=None):
    name = 'cy-restore-' + scope
    require(re.fullmatch('[a-f0-9]{32}', scope), 'wrong scope')
    require(re.fullmatch('[a-f0-9]{64}', row['Id']), 'full resource ID required')
    if kind == 'container':
        require(side in ('source', 'target') and row['Name'] == '/' + name + '-' + side,
                'foreign container name')
        require(row['Config'].get('Labels', {}).get('chengyin.restore-business') == scope
                and row['Config']['Image'] == IMAGE, 'foreign container provenance')
        require(set(row['NetworkSettings']['Networks']) <= {name}, 'foreign container network')
    else:
        require(kind == 'network' and row['Name'] == name and row['Internal'] is True
                and row.get('Labels', {}).get('chengyin.restore-business') == scope, 'foreign network')


class Host:
    def __init__(self, root, private, output, scope, cohort):
        self.root, self.private, self.output, self.scope = root, private, output, scope
        require(cohort in COHORTS, 'unregistered restore cohort')
        self.cohort = cohort
        self.name = 'cy-restore-' + scope
        self.created = {}
        self.resources = private / 'resources.json'
        self.stage = 'preparation'

    def command(self, args, env=None, timeout=120):
        result = subprocess.run(args, capture_output=True, env=env, timeout=timeout)
        require(result.returncode == 0, 'owned command failed at ' + self.stage)
        return result.stdout.decode().strip()

    def provision(self):
        self.stage = 'image'
        self.command(DOCKER + ['pull', IMAGE])
        self.stage = 'network'
        require(inspect('network', self.name) is None, 'network already exists')
        self.created['network'] = None
        network_id = self.command(DOCKER + ['network', 'create', '--internal', '--label',
                                              'chengyin.restore-business=' + self.scope, self.name])
        self.created['network'] = network_id
        resources = {'scope': self.scope, 'sourceSha': os.environ['GITHUB_SHA'], 'cohort': self.cohort,
                     'schema': 'cy_restore_' + self.scope + '_funds_core_it',
                     'testClasspathFile': str(self.private / 'test-classpath.txt')}
        for side in ('source', 'target'):
            self.stage = side + '-create'
            name = self.name + '-' + side
            require(inspect('container', name) is None, 'container already exists')
            password = secrets.token_urlsafe(24)
            env = dict(os.environ, MYSQL_ROOT_PASSWORD=password)
            self.created[side] = None
            cid = self.command(DOCKER + ['create', '--name', name,
                '--cidfile', str(self.private / (side + '.cid')),
                '--label', 'chengyin.restore-business=' + self.scope, '--network', self.name,
                '--memory=1g', '--memory-swap=1g', '--cpus=1', '--pids-limit=256',
                '--tmpfs', '/var/lib/mysql:rw,size=768m', '-p', '127.0.0.1::3306',
                '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'MYSQL_ROOT_HOST=%', IMAGE,
                '--skip-log-bin', '--performance-schema=OFF', '--event-scheduler=OFF'], env=env)
            require(re.fullmatch('[0-9a-f]{64}', cid), 'missing full CID')
            self.created[side] = cid
            self.command(DOCKER + ['start', cid])
            row = inspect('container', cid)
            owned_object('container', row, self.scope, side)
            binding = row['NetworkSettings']['Ports'].get('3306/tcp')
            require(binding and len(binding) == 1 and binding[0]['HostIp'] == '127.0.0.1', 'loopback port required')
            port = int(binding[0]['HostPort'])
            require(1024 < port < 65536, 'invalid owned port')
            defaults = self.private / (side + '.cnf')
            write_private(defaults, '[client]\nhost=127.0.0.1\nport=%d\nuser=root\npassword=%s\nprotocol=tcp\n' % (port, password))
            self.stage = side + '-ready'
            deadline = time.monotonic() + 65
            identity = None
            while time.monotonic() < deadline:
                ready = subprocess.run(['mysql', '--defaults-extra-file=' + str(defaults),
                    '--batch', '--skip-column-names'],
                    input=b'SELECT @@server_uuid,VERSION(),@@global.event_scheduler', capture_output=True, timeout=5)
                if ready.returncode == 0:
                    identity = ready.stdout.decode().strip().split('\t')
                    break
                time.sleep(1)
            require(identity and len(identity) == 3 and identity[1:] == ['8.0.45', 'OFF'], 'wrong server identity')
            require(re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', identity[0]), 'invalid server UUID')
            resources[side] = {'cid': cid, 'uuid': identity[0], 'port': port, 'defaultsFile': str(defaults)}
        require(resources['source']['uuid'] != resources['target']['uuid'], 'source and target UUID must differ')
        require(resources['source']['port'] != resources['target']['port'], 'source and target ports must differ')
        write_private(self.resources, json.dumps(resources, sort_keys=True))

    def cleanup(self):
        failures = []
        for kind, key in (('container', 'target'), ('container', 'source'), ('network', 'network')):
            if key not in self.created:
                continue
            # An interrupted create is recovered only by its fresh exact name and original label.
            expected = self.created[key]
            identity = expected or (self.name if key == 'network' else self.name + '-' + key)
            try:
                row = inspect(kind, identity)
                if row is None:
                    continue
                owned_object(kind, row, self.scope, None if key == 'network' else key)
                require(expected is None or expected == row['Id'], 'resource identity changed')
                command = DOCKER + (['container', 'rm', '-f', row['Id']] if kind == 'container'
                                    else ['network', 'rm', row['Id']])
                self.command(command, timeout=30)
                require(inspect(kind, row['Id']) is None, 'exact absence not confirmed')
            except BaseException:
                failures.append(key)
        return failures

    def finish(self, original_exit):
        failures = self.cleanup()
        result = original_exit or (1 if failures else 0)
        finalized = False
        if self.resources.exists() and (self.output / 'reconciliation.json').is_file():
            try:
                self.stage = 'finalize'
                self.command([sys.executable, '-I', str(PROBE), 'finalize', '--resources', str(self.resources),
                              '--output', str(self.output), '--original-exit', str(result)], timeout=75)
                finalized = True
            except BaseException:
                result = result or 1
        else:
            result = result or 1
        try:
            require(self.private.is_dir() and not self.private.is_symlink(), 'private root changed')
            shutil.rmtree(self.private)
            require(not self.private.exists(), 'private cleanup unconfirmed')
        except BaseException:
            failures.append('private')
            result = result or 1
        receipt = {'sourceSha': os.environ['GITHUB_SHA'], 'scope': self.scope, 'cohort': self.cohort,
                   'originalExit': original_exit, 'exitCode': result, 'cleanupFailures': failures,
                   'resourceCleanup': 'EXACT_ABSENCE' if not failures else 'UNKNOWN',
                   'probeFinalized': finalized, 'lastStage': self.stage,
                   'acceptance': 'NOT_FULL_T20_OR_PRODUCTION_ACCEPTANCE'}
        write_private(self.output / 'host.json', json.dumps(receipt, sort_keys=True, indent=2))
        return result

    def run_probe(self):
        invocation = [sys.executable, '-I', str(PROBE), 'run', '--resources', str(self.resources),
                      '--output', str(self.output), '--private', str(self.private)]
        with (self.private / 'probe.log').open('xb') as log:
            os.chmod(log.name, 0o600)
            child = subprocess.Popen(invocation, stdout=log, stderr=log, start_new_session=True)
            try:
                result = child.wait(timeout=1050)
                return 128 - result if result < 0 else result
            finally:
                if child.poll() is None:
                    # This session contains only this exact probe and its fixed test children.
                    os.killpg(child.pid, signal.SIGTERM)
                    try:
                        child.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        os.killpg(child.pid, signal.SIGKILL)
                        child.wait(timeout=10)


def main():
    helper = original_helpers()
    head = helper.command(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']).decode().strip()
    endpoint = helper.command(DOCKER + ['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).decode().strip()
    hosted(os.environ, head, endpoint)
    for client in ('mysql', 'mysqldump'):
        require(shutil.which(client), 'existing hosted tool missing: ' + client)
    for key in ('RUNNER_TEMP',):
        require(os.environ.get(key) and Path(os.environ[key]).is_absolute(), 'owned runner temporary root missing')
    temp = Path(os.environ['RUNNER_TEMP']).resolve()
    evidence = temp / 'isolated-restore-business-reconciliation'
    evidence.mkdir(mode=0o700)
    private = Path(tempfile.mkdtemp(prefix='cy-restore-private-', dir=temp))
    private.chmod(0o700)
    host = Host(ROOT, private, evidence, uuid.uuid4().hex, os.environ['CHENGYIN_RESTORE_BUSINESS_COHORT'])
    code = 1
    def interrupted(signum, frame):
        raise InterruptedError('owned restore entry interrupted')
    previous_signals = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGTERM, signal.SIGHUP)}
    try:
        dependencies = (ROOT / 'chengyinhub-admin/target/restore-dependency-classpath.txt').read_text()
        write_private(private / 'test-classpath.txt', classpath(ROOT, dependencies))
        host.provision()
        host.stage = 'probe'
        code = host.run_probe()
    except BaseException:
        code = code or 1
    finally:
        for sig, handler in previous_signals.items():
            signal.signal(sig, signal.SIG_IGN)
        try:
            code = host.finish(code)
        finally:
            for sig, handler in previous_signals.items():
                signal.signal(sig, handler)
    return code


def summarize_receipts(root, source_sha, run_id, attempt, expected_build=None):
    expected = {'isolated-restore-business-reconciliation-' + cohort + '-' + attempt for cohort in COHORTS}
    require(root.is_dir() and not root.is_symlink() and root.resolve() == root.absolute(), 'owned receipt root required')
    require({path.name for path in root.iterdir()} == expected, 'seven exact cohort artifacts required')
    positive, rows, identities, scopes = [], [], set(), set()
    for cohort in COHORTS:
        directory = root / ('isolated-restore-business-reconciliation-' + cohort + '-' + attempt)
        require(directory.is_dir() and not directory.is_symlink(), 'regular cohort artifact directory required')
        require({p.name for p in directory.iterdir()} == {'host.json', 'reconciliation.json', 'finalized.json'},
                'only the three public cohort JSON receipts are allowed')
        receipts = {}
        for name in ('host', 'reconciliation', 'finalized'):
            path = directory / (name + '.json')
            require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 8 * 1024 * 1024,
                    'bounded regular JSON receipt required')
            value = json.loads(path.read_text())
            require(isinstance(value, dict), 'receipt object required')
            receipts[name] = value
        host, business, final = receipts['host'], receipts['reconciliation'], receipts['finalized']
        scope = business.get('scope')
        require(isinstance(scope, str) and re.fullmatch('[a-f0-9]{32}', scope) and scope not in scopes,
                'cohort resources must have independent exact scopes')
        scopes.add(scope)
        for receipt in receipts.values():
            require(receipt.get('sourceSha') == source_sha and receipt.get('scope') == scope
                    and receipt.get('cohort') == cohort, 'mixed cohort/source/resource receipts')
        require(business.get('runId') == run_id and business.get('runAttempt') == attempt,
                'foreign workflow run or attempt')
        require(host.get('originalExit') == 0 and host.get('exitCode') == 0
                and host.get('cleanupFailures') == [] and host.get('resourceCleanup') == 'EXACT_ABSENCE'
                and host.get('probeFinalized') is True, 'host failure/cleanup cannot be ignored')
        require(business.get('processExit') == 0
                and business.get('schemaCleanup') == 'CONFIRMED_EXACT_CREATED_SCHEMAS_ABSENT'
                and final.get('originalExit') == 0 and final.get('absenceFailures') == []
                and final.get('resourceCleanup') == 'EXACT_ABSENCE' and final.get('dispatchRelease') is False,
                'same-cohort final cleanup/release fence missing')
        jar, tool = business.get('jarSha256'), business.get('canonicalToolSha256')
        require(isinstance(jar, str) and re.fullmatch('[a-f0-9]{64}', jar)
                and isinstance(tool, str) and re.fullmatch('[a-f0-9]{64}', tool), 'full JAR/tool hashes required')
        identities.add((jar, tool))
        if cohort.endswith('-complete'):
            require(business.get('businessResult') == 'SCOPED_COMPLETE_FACTS_NORMAL_BOOT_READBACK_VERIFIED'
                    and final.get('candidateState') == 'TEST_OWNED_RECONCILED', 'complete cohort cannot be replaced by a gap')
            positive.append({'business': business, 'final': final})
        else:
            require(business.get('businessResult') == 'SCOPED_DIFFERENCES_VERIFIED_RELEASE_BLOCKED'
                    and final.get('result') == 'SCOPED_OBSERVATION_COMPLETE_RELEASE_BLOCKED'
                    and final.get('candidateState') == 'BLOCKED'
                    and not business.get('times', {}).get('normalOriginalReadbackAvailableAt'),
                    'gap must remain blocked without normal boot')
        rows.append({'cohort': cohort, 'scope': scope, 'businessResult': business['businessResult'],
                     'finalResult': final['result']})
    require(len(identities) == 1, 'cohorts must reuse one exact JAR and canonical backup tool')
    if expected_build is not None:
        require(isinstance(expected_build, tuple) and len(expected_build) == 2
                and all(isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value)
                        for value in expected_build), 'verified once-build JAR/tool hashes required')
        require(identities == {expected_build}, 'cohort JAR/tool differs from this workflow build/source')
    completed = domain_readers().summarize_completed_cohorts(positive)
    return {'sourceSha': source_sha, 'runId': run_id, 'runAttempt': attempt,
            'jarSha256': next(iter(identities))[0], 'canonicalToolSha256': next(iter(identities))[1],
            'onceBuildBound': expected_build is not None,
            'result': 'SCOPED_SEVEN_RESTORE_COHORTS_COMPLETE_NO_PRODUCTION_RELEASE',
            'cohorts': rows, 'completeFacts': completed, 'productionDispatchRelease': False,
            'globalProductionFenceVerified': False}


def summary_main():
    for key, value in {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
                       'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                       'GITHUB_JOB': 'restore-business-summary'}.items():
        require(os.environ.get(key) == value, 'wrong restore summary scope')
    source = os.environ['GITHUB_SHA']
    require(re.fullmatch('[a-f0-9]{40}', source) and subprocess.check_output(
        ['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip() == source, 'summary source mismatch')
    temporary = Path(os.environ['RUNNER_TEMP']).resolve()
    output = temporary / 'restore-business-summary'
    output.mkdir(mode=0o700)
    code = 0
    try:
        result = summarize_receipts(temporary / 'restore-business-receipts', source,
                                    os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'],
                                    expected_build=(os.environ.get('RESTORE_BUILD_JAR_SHA256', ''),
                                                    hashlib.sha256((ROOT / 'scripts/db-backup-restore.py').read_bytes()).hexdigest()))
    except Exception as failure:
        code = 1
        result = {'sourceSha': source, 'runId': os.environ['GITHUB_RUN_ID'],
                  'runAttempt': os.environ['GITHUB_RUN_ATTEMPT'], 'result': 'FAIL', 'reason': str(failure)[:400],
                  'productionDispatchRelease': False, 'globalProductionFenceVerified': False}
    write_private(output / 'summary.json', json.dumps(result, sort_keys=True, indent=2))
    return code


if __name__ == '__main__':
    try:
        require(sys.argv[1:] in ([], ['--summarize']), 'fixed restore host entry only')
        sys.exit(summary_main() if sys.argv[1:] else main())
    except Exception:
        sys.exit('Restore hosted entry failed; no production resources or release authorized')
