#!/usr/bin/env python3
"""Fixed hosted resource owner for the existing restore probe, never a production runner."""
from __future__ import annotations

import importlib.util
import contextlib
import hashlib
import ipaddress
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


class OwnerGuardFailure(ValueError):
    def __init__(self, code):
        self.guard_code = code
        super().__init__('Owned resource predicate failed')


class InspectAbsenceUnproven(RuntimeError):
    guard_code = 'INSPECT_ABSENCE_UNPROVEN'


def require(value, reason, guard_code=None):
    if not value:
        if guard_code is not None:
            raise OwnerGuardFailure(guard_code)
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
        try:
            original_helpers().require_absent(kind, identity, result.returncode, result.stderr)
        except RuntimeError:
            raise InspectAbsenceUnproven('Owned resource absence remains unproven') from None
        return None
    try:
        rows = json.loads(result.stdout)
    except json.JSONDecodeError:
        raise OwnerGuardFailure('INSPECT_JSON_INVALID') from None
    require(len(rows) == 1, 'ambiguous resource identity', 'INSPECT_IDENTITY_COUNT')
    return rows[0]


def owned_object(kind, row, scope, side=None):
    name = 'cy-restore-' + scope
    require(re.fullmatch('[a-f0-9]{32}', scope), 'wrong scope', 'OWNER_SCOPE_INVALID')
    require(re.fullmatch('[a-f0-9]{64}', row['Id']), 'full resource ID required', 'OWNER_FULL_ID_INVALID')
    if kind == 'container':
        require(side in ('source', 'target') and row['Name'] == '/' + name + '-' + side,
                'foreign container name', 'OWNER_CONTAINER_NAME')
        require(row['Config'].get('Labels', {}).get('chengyin.restore-business') == scope,
                'foreign container label', 'OWNER_CONTAINER_LABEL')
        require(row['Config']['Image'] == IMAGE, 'foreign container image', 'OWNER_CONTAINER_IMAGE')
        require(set(row['NetworkSettings']['Networks']) <= {name}, 'foreign container network', 'OWNER_CONTAINER_NETWORK')
    else:
        require(kind == 'network' and row['Name'] == name, 'foreign network name', 'OWNER_NETWORK_NAME')
        require(row['Internal'] is True, 'foreign network mode', 'OWNER_NETWORK_NOT_INTERNAL')
        require(row.get('Labels', {}).get('chengyin.restore-business') == scope,
                'foreign network label', 'OWNER_NETWORK_LABEL')


def relay_endpoint(scope, side, cid, network_id):
    require(side in ('source', 'target'), 'fixed MySQL side required')
    name = 'cy-restore-' + scope
    obj, network = inspect('container', cid), inspect('network', network_id)
    require(obj is not None and network is not None, 'original relay resources missing', 'RELAY_RESOURCE_MISSING')
    owned_object('container', obj, scope, side); owned_object('network', network, scope)
    require(obj['Id'] == cid and network['Id'] == network_id, 'original relay identities changed', 'RELAY_IDENTITY_CHANGED')
    require(set(obj['NetworkSettings']['Networks']) == {name}, 'relay has another network', 'RELAY_NETWORK_CHANGED')
    attachment = obj['NetworkSettings']['Networks'][name]
    address = ipaddress.ip_address(attachment['IPAddress'])
    require(attachment['NetworkID'] == network_id and address.version == 4 and address.is_private
            and not address.is_loopback and not address.is_unspecified
            and any(address in ipaddress.ip_network(c['Subnet']) for c in network['IPAM']['Config'] if ':' not in c['Subnet']),
            'relay address not in original network', 'RELAY_ADDRESS_INVALID')
    state = obj['State']
    require(state['Running'] and not state['Paused'] and not state['OOMKilled'] and not state['Restarting']
            and state['Pid'] > 0, 'relay target not running', 'RELAY_TARGET_NOT_RUNNING')
    require(all(value is None for value in obj['NetworkSettings']['Ports'].values()),
            'relay mode must not expose any published port', 'RELAY_PUBLISHED_PORT')
    return {'id': cid, 'networkId': network_id, 'ip': str(address), 'pid': state['Pid']}


def process_start_ticks(pid):
    # Linux proc field22; comm may contain spaces/parentheses, so split after its final closing parenthesis.
    return Path('/proc', str(pid), 'stat').read_text().rsplit(')', 1)[1].split()[19]


def validate_relay_descriptor(scope, side, target, network, obj):
    relay = target['relay']; endpoint = relay['endpoint']
    require(target['transport'] == 'OWNED_MYSQL_LOOPBACK_RELAY' and relay['host'] == '127.0.0.1'
            and type(target['port']) is int and 1024 < target['port'] < 65536,
            'fixed loopback relay required', 'RELAY_BIND_INVALID')
    require(endpoint == relay_endpoint(scope, side, target['cid'], network['Id'])
            and endpoint['id'] == obj['Id'], 'relay original endpoint changed', 'RELAY_IDENTITY_CHANGED')
    pid, fd, inode = relay['ownerPid'], relay['fd'], relay['inode']
    require(all(type(v) is int and v > 0 for v in (pid, fd, inode))
            and process_start_ticks(pid) == relay['ownerStartTicks']
            and os.readlink(Path('/proc', str(pid), 'fd', str(fd))) == 'socket:[' + str(inode) + ']',
            'original relay listener owner changed', 'RELAY_LISTENER_OWNER')
    local = '0100007F:' + format(target['port'], '04X')
    matches = [line.split() for line in Path('/proc/net/tcp').read_text().splitlines()[1:]
               if len(line.split()) > 9 and line.split()[9] == str(inode)]
    require(len(matches) == 1 and matches[0][1] == local and matches[0][3] == '0A',
            'relay must be the original live IPv4 loopback listener', 'RELAY_LISTENER_BINDING')


def command_operation(args):
    # Fixed labels only: never publish argv, paths, SQL, environment values or arbitrary commands.
    if args[:len(DOCKER)] == DOCKER:
        tail = args[len(DOCKER):]
        for prefix, operation in ((['pull'], 'DOCKER_PULL'), (['network', 'create'], 'DOCKER_NETWORK_CREATE'),
                (['create'], 'DOCKER_CONTAINER_CREATE'), (['start'], 'DOCKER_CONTAINER_START'),
                (['container', 'rm'], 'DOCKER_CONTAINER_REMOVE'), (['network', 'rm'], 'DOCKER_NETWORK_REMOVE')):
            if tail[:len(prefix)] == prefix:
                return operation
    if args[:4] == [sys.executable, '-I', str(PROBE), 'finalize']:
        return 'PROBE_FINALIZE'
    return 'OWNED_COMMAND_UNKNOWN'


def safe_command_reason(operation, stderr):
    # Match bounded raw bytes, emit only fixed reviewed excerpts. Never echo the matched line.
    if operation.startswith('DOCKER_') and stderr is not None:
        text = stderr[:16384].lower()
        for needles, code, excerpt in (
                ((b'cannot connect to the docker daemon', b'error during connect'),
                 'DOCKER_DAEMON_UNAVAILABLE', 'Docker daemon connection failed; existence remains unknown'),
                ((b'permission denied',), 'DOCKER_PERMISSION_DENIED', 'Docker reported permission denied'),
                ((b'unknown flag:',), 'DOCKER_OPTION_UNSUPPORTED', 'Docker reported an unsupported option'),
                ((b'invalid mount config for type "tmpfs"', b'invalid tmpfs'),
                 'DOCKER_TMPFS_REJECTED', 'Docker rejected tmpfs configuration'),
                ((b'conflicting options:',), 'DOCKER_OPTION_CONFLICT', 'Docker reported conflicting options'),
                ((b'conflict. the container name ',), 'DOCKER_CONTAINER_NAME_CONFLICT', 'Docker reported a container name conflict'),
                ((b'port is already allocated', b'address already in use'),
                 'DOCKER_PORT_CONFLICT', 'Docker reported an allocated address or port'),
                ((b'no space left on device',), 'DOCKER_STORAGE_UNAVAILABLE', 'Docker reported no space left on device')):
            if any(needle in text for needle in needles):
                return code, excerpt
    return 'UNKNOWN', 'No reviewed safe reason matched; do not infer the Docker rejection cause'


class Host:
    def __init__(self, root, private, output, scope, cohort):
        self.root, self.private, self.output, self.scope = root, private, output, scope
        require(cohort in COHORTS, 'unregistered restore cohort')
        self.cohort = cohort
        self.name = 'cy-restore-' + scope
        self.created = {}
        self.resources = private / 'resources.json'
        self.stage = 'preparation'
        self.substage = None
        self.first_failure = None
        self.relays = contextlib.ExitStack()
        self.mysql_transports = {}

    def native_mysql_ready(self, cid, side, deadline):
        self.substage = 'native-mysql-readiness'
        while time.monotonic() < deadline:
            relay_endpoint(self.scope, side, cid, self.created['network'])
            try:
                result = subprocess.run(DOCKER + ['exec', cid, 'sh', '-c',
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=TCP -h127.0.0.1 '
                    '--connect-timeout=2 -uroot -N -e "SELECT @@server_uuid,VERSION(),@@global.event_scheduler"'],
                    capture_output=True, timeout=5)
                if result.returncode == 0:
                    identity = result.stdout.decode().strip().split('\t')
                    require(len(identity) == 3 and identity[1:] == ['8.0.45', 'OFF']
                            and re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', identity[0]),
                            'native MySQL identity differs', 'RELAY_NATIVE_IDENTITY')
                    return identity
            except subprocess.TimeoutExpired:
                pass
            time.sleep(1)
        require(False, 'original MySQL native TCP readiness deadline', 'RELAY_NATIVE_NOT_READY')

    def start_mysql_relay(self, side, cid):
        deadline = time.monotonic() + 65
        native = self.native_mysql_ready(cid, side, deadline)
        self.substage = 'owned-mysql-relay'
        endpoint = relay_endpoint(self.scope, side, cid, self.created['network'])
        helper = original_helpers()
        server = self.relays.enter_context(helper._owned_mysql_relay(endpoint,
            lambda: relay_endpoint(self.scope, side, cid, self.created['network'])))
        address, port = server.socket.getsockname()
        require(address == '127.0.0.1' and 1024 < port < 65536, 'owned relay not loopback', 'RELAY_BIND_INVALID')
        descriptor = {'host': address, 'ownerPid': os.getpid(), 'ownerStartTicks': process_start_ticks(os.getpid()),
                      'fd': server.socket.fileno(), 'inode': os.fstat(server.socket.fileno()).st_ino, 'endpoint': endpoint}
        return port, descriptor, native, deadline

    def remember_failure(self, failure_type, operation='OWNER', returncode=None, stderr=None, error=None):
        if self.first_failure is not None:
            return  # Cleanup/finalize failures must not replace the original preparation failure.
        if isinstance(stderr, str):
            stderr = stderr.encode('utf-8')
        reason, excerpt = safe_command_reason(operation, stderr)
        self.first_failure = {'stage': self.stage, 'substage': self.substage,
                              'guardCode': error.guard_code if isinstance(error, (OwnerGuardFailure, InspectAbsenceUnproven)) else None,
                              'failureType': failure_type, 'operation': operation,
                              'returnCode': returncode, 'reasonCode': reason, 'safeReasonExcerpt': excerpt,
                              'stderrCaptured': stderr is not None,
                              'stderrBytes': len(stderr) if stderr is not None else None,
                              'stderrSha256': hashlib.sha256(stderr).hexdigest() if stderr is not None else None,
                              'errorType': type(error).__name__ if error is not None else None}

    def command(self, args, env=None, timeout=120):
        operation = command_operation(args)
        try:
            result = subprocess.run(args, capture_output=True, env=env, timeout=timeout)
        except subprocess.TimeoutExpired as failure:
            self.remember_failure('COMMAND_TIMEOUT', operation, stderr=failure.stderr, error=failure)
            raise
        except OSError as failure:
            self.remember_failure('COMMAND_START_ERROR', operation, error=failure)
            raise
        if result.returncode != 0:
            self.remember_failure('COMMAND_EXIT', operation, result.returncode, result.stderr)
            raise ValueError('owned command failed at ' + self.stage)
        return result.stdout.decode().strip()

    def provision(self):
        self.stage = 'image'
        self.substage = 'image-pull'
        self.command(DOCKER + ['pull', IMAGE])
        self.stage = 'network'
        self.substage = 'network-absence'
        require(inspect('network', self.name) is None, 'network already exists', 'NETWORK_ALREADY_EXISTS')
        self.created['network'] = None
        self.substage = 'network-create'
        network_id = self.command(DOCKER + ['network', 'create', '--internal', '--label',
                                              'chengyin.restore-business=' + self.scope, self.name])
        self.created['network'] = network_id
        resources = {'scope': self.scope, 'sourceSha': os.environ['GITHUB_SHA'], 'cohort': self.cohort,
                     'schema': 'cy_restore_' + self.scope + '_funds_core_it',
                     'testClasspathFile': str(self.private / 'test-classpath.txt')}
        for side in ('source', 'target'):
            relay, native, readiness_deadline = None, None, None
            self.stage = side + '-create'
            name = self.name + '-' + side
            self.substage = 'container-absence'
            require(inspect('container', name) is None, 'container already exists', 'CONTAINER_ALREADY_EXISTS')
            self.substage = 'container-environment'
            password = secrets.token_urlsafe(24)
            env = dict(os.environ, MYSQL_ROOT_PASSWORD=password)
            self.created[side] = None
            self.substage = 'container-create'
            cid = self.command(DOCKER + ['create', '--name', name,
                '--cidfile', str(self.private / (side + '.cid')),
                '--label', 'chengyin.restore-business=' + self.scope, '--network', self.name,
                '--memory=1g', '--memory-swap=1g', '--cpus=1', '--pids-limit=256',
                '--tmpfs', '/var/lib/mysql:rw,size=768m', '-p', '127.0.0.1::3306',
                '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'MYSQL_ROOT_HOST=%', IMAGE,
                '--skip-log-bin', '--performance-schema=OFF', '--event-scheduler=OFF'], env=env)
            self.substage = 'container-cid'
            require(re.fullmatch('[0-9a-f]{64}', cid), 'missing full CID', 'CREATED_CID_INVALID')
            self.created[side] = cid
            self.substage = 'container-start'
            self.command(DOCKER + ['start', cid])
            self.substage = 'container-inspect'
            row = inspect('container', cid)
            self.substage = 'container-ownership'
            owned_object('container', row, self.scope, side)
            self.substage = 'loopback-binding'
            binding = row['NetworkSettings']['Ports'].get('3306/tcp')
            if binding:
                self.mysql_transports[side] = 'DOCKER_LOOPBACK_PUBLISH'
                require(binding, 'loopback port required', 'BINDING_MISSING')
                require(len(binding) == 1, 'one loopback binding required', 'BINDING_COUNT')
                require(binding[0]['HostIp'] == '127.0.0.1', 'loopback port required', 'BINDING_NOT_LOOPBACK')
                self.substage = 'loopback-port-parse'
                port = int(binding[0]['HostPort'])
            else:
                self.mysql_transports[side] = 'OWNED_MYSQL_LOOPBACK_RELAY'
                # Docker28 internal-only endpoints do not program external port mappings.
                # Absence is not readiness: use the existing owned transport, then prove native/relay UUID equality.
                port, relay, native, readiness_deadline = self.start_mysql_relay(side, cid)
            self.substage = 'loopback-port-range'
            require(1024 < port < 65536, 'invalid owned port', 'BINDING_PORT_RANGE')
            self.substage = 'client-config-write'
            defaults = self.private / (side + '.cnf')
            write_private(defaults, '[client]\nhost=127.0.0.1\nport=%d\nuser=root\npassword=%s\nprotocol=tcp\n' % (port, password))
            self.stage = side + '-ready'
            self.substage = 'mysql-readiness'
            deadline = readiness_deadline if readiness_deadline is not None else time.monotonic() + 65
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
            if relay is not None:
                require(identity == native, 'relay/native MySQL identities differ', 'RELAY_NATIVE_IDENTITY')
            resources[side] = {'cid': cid, 'uuid': identity[0], 'port': port, 'defaultsFile': str(defaults)}
            if relay is not None:
                resources[side].update(transport='OWNED_MYSQL_LOOPBACK_RELAY', relay=relay)
                validate_relay_descriptor(self.scope, side, resources[side], inspect('network', self.created['network']), row)
        require(resources['source']['uuid'] != resources['target']['uuid'], 'source and target UUID must differ')
        require(resources['source']['port'] != resources['target']['port'], 'source and target ports must differ')
        write_private(self.resources, json.dumps(resources, sort_keys=True))

    def cleanup(self):
        failures = []
        for kind, key in (('container', 'target'), ('container', 'source'), ('network', 'network')):
            if key not in self.created:
                continue
            self.stage, self.substage = 'resource-cleanup', key + '-absence'
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

    def scoped_receipt(self, name):
        try:
            path = self.output / name
            require(path.is_file() and not path.is_symlink() and not path.stat().st_mode & 0o077
                    and path.stat().st_size <= 8 * 1024 * 1024, 'bounded private receipt required')
            row = json.loads(path.read_bytes())
            require(row['sourceSha'] == os.environ['GITHUB_SHA'] and row['scope'] == self.scope
                    and row['cohort'] == self.cohort and row['runId'] == os.environ['GITHUB_RUN_ID']
                    and row['runAttempt'] == os.environ['GITHUB_RUN_ATTEMPT']
                    and row['resourcesSha256'] == hashlib.sha256(self.resources.read_bytes()).hexdigest(),
                    'receipt does not belong to this exact probe')
            return row
        except BaseException:
            return None  # Never copy malformed JSON, exception text or another scope into public evidence.

    def remember_probe_exit(self, code):
        receipt = self.scoped_receipt('reconciliation.json')
        if (code == 0 and receipt is not None and receipt.get('processExit') == 0
                and receipt.get('businessResult') in ('SCOPED_COMPLETE_FACTS_NORMAL_BOOT_READBACK_VERIFIED',
                                                     'SCOPED_DIFFERENCES_VERIFIED_RELEASE_BLOCKED')):
            return
        if self.first_failure is not None:
            return
        self.stage, self.substage = 'probe', 'fixed-probe-exit'
        self.remember_failure('PROBE_RUN_EXIT', 'PROBE_RUN', code)
        self.first_failure['probeReceipt'] = 'MATCHED' if receipt is not None else 'UNAVAILABLE_OR_MISMATCHED'
        if receipt is not None:
            category = receipt.get('primaryFailureType')
            reason = receipt.get('primaryReasonCode')
            self.first_failure['primaryFailureType'] = category if category in (
                'ValueError', 'ChildBoundaryFailure', 'TimeoutError', 'InterruptedError', 'CalledProcessError') else 'UNKNOWN'
            self.first_failure['primaryReasonCode'] = reason if reason in (
                'CHILD_EOF_BEFORE_BOUNDARY', 'CHILD_EXIT_BEFORE_BOUNDARY', 'CHILD_OUTPUT_BUDGET',
                'CHILD_BOUNDARY_TIMEOUT', 'UNKNOWN_PRIVATE_FAILURE') else 'UNKNOWN'
            if receipt.get('hostFailure') is not None:
                try:
                    require(receipt.get('probeSourceSha256') == hashlib.sha256(PROBE.read_bytes()).hexdigest(),
                            'host diagnostic source differs')
                    spec = importlib.util.spec_from_file_location('restore_original_host_diagnostic', PROBE)
                    helper = importlib.util.module_from_spec(spec); spec.loader.exec_module(helper)
                    diagnostic = helper.safe_host_failure(receipt['hostFailure'])
                    require(diagnostic is not None and diagnostic['reasonCode'] == reason, 'invalid fixed host diagnostic')
                    self.first_failure['hostFailure'] = diagnostic
                    self.first_failure['primaryReasonCode'] = diagnostic['reasonCode']
                except BaseException:
                    self.first_failure['hostFailureDiagnostic'] = 'UNKNOWN'
            self.first_failure['reconciliationSha256'] = hashlib.sha256((self.output / 'reconciliation.json').read_bytes()).hexdigest()

    def finalization_recorded(self, original_exit):
        row = self.scoped_receipt('finalized.json')
        business = self.scoped_receipt('reconciliation.json')
        return (row is not None and business is not None and row.get('finalization') == 'RECORDED'
                and type(row.get('originalExit')) is int and row['originalExit'] == original_exit
                and row.get('dispatchRelease') is False and row.get('businessResult') == business.get('businessResult')
                and row.get('resourceCleanup') in ('EXACT_ABSENCE', 'UNKNOWN')
                and row.get('result') in ('FAIL', 'TEST_COHORT_NORMAL_READBACK_COMPLETE_NO_PRODUCTION_RELEASE',
                                         'SCOPED_OBSERVATION_COMPLETE_RELEASE_BLOCKED'))

    def finish(self, original_exit):
        # Record the original probe/child exit before relay-close, cleanup or a deliberately failing finalizer.
        if self.resources.exists() and (self.output / 'reconciliation.json').is_file():
            self.remember_probe_exit(original_exit)
        relay_failures = []
        self.substage = 'relay-close'
        try:
            self.relays.close()  # Drain/join original relay workers while their exact containers still exist.
        except BaseException as failure:
            self.remember_failure('HOST_EXCEPTION_UNKNOWN', error=failure)
            relay_failures.append('relay')
        failures = self.cleanup()
        failures.extend(relay_failures)
        result = original_exit or (1 if failures or self.first_failure is not None else 0)
        finalized = False
        if self.resources.exists() and (self.output / 'reconciliation.json').is_file():
            try:
                self.stage = 'finalize'
                self.substage = 'fixed-probe-finalize'
                self.command([sys.executable, '-I', str(PROBE), 'finalize', '--resources', str(self.resources),
                              '--output', str(self.output), '--original-exit', str(result)], timeout=75)
                finalized = self.finalization_recorded(result)
                require(finalized, 'finalizer did not produce a matching receipt')
            except BaseException:
                finalized = self.finalization_recorded(result)
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
                   'runId': os.environ.get('GITHUB_RUN_ID'), 'runAttempt': os.environ.get('GITHUB_RUN_ATTEMPT'),
                   'originalExit': original_exit, 'exitCode': result, 'cleanupFailures': failures,
                   'resourceCleanup': 'EXACT_ABSENCE' if not failures else 'UNKNOWN',
                   'probeFinalized': finalized, 'lastStage': self.stage,
                   'mysqlTransports': self.mysql_transports,
                   'firstFailure': self.first_failure,
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
                code = 128 - result if result < 0 else result
                self.remember_probe_exit(code)
                return code
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
        host.substage = 'fixed-probe'
        code = host.run_probe()
    except BaseException as failure:
        host.remember_failure('HOST_EXCEPTION_UNKNOWN', error=failure)
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
