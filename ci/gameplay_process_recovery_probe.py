#!/usr/bin/env python3
"""App-only child adapter for the existing isolated authenticated fixture. SOURCE, not acceptance.

No schema/network/Redis creator or cleaner is implemented here. The existing shell owns those
resources, canonical schema builder, server UUID, immutable JAR provenance and terminal cleanup.
"""
import concurrent.futures
import base64
import datetime
import decimal
import hashlib
import importlib.util
import io
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHILD = 'com.chengyinhub.business.it.GameplayProcessRecoveryChild'
CHILD_PATH = CHILD.replace('.', '/')
COMMAND = '/api/club/lead/unlock-chapter'
RECEIPT = '/api/club/lead/unlock-chapter-receipt'
PHASES = ('BEFORE_TX', 'COMMITTED_NO_REPLY', 'ACKED')
JDK_IMAGE = 'maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237'


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


owned = load('gameplay_owned_auth_fixture', 'ci/authenticated_redis_recovery_probe.py')
require, command, DOCKER = owned.require, owned.command, owned.DOCKER


def digest(value):
    if isinstance(value, bytes):
        return hashlib.sha256(value).hexdigest()
    def typed(item):
        if isinstance(item, decimal.Decimal):
            return {'sqlType': 'decimal', 'value': str(item)}
        if isinstance(item, (datetime.date, datetime.datetime, datetime.time)):
            return {'sqlType': type(item).__name__, 'value': item.isoformat()}
        if isinstance(item, bytes):
            return {'sqlType': 'bytes', 'value': base64.b64encode(item).decode()}
        raise TypeError('Unregistered SQL evidence type: ' + type(item).__name__)
    return digest(json.dumps(value, sort_keys=True, separators=(',', ':'), default=typed).encode())


def gate(env):
    owned.validate_execution_env(env)
    require(env.get('CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY') == 'true', 'explicit gameplay child admission required')
    require(re.fullmatch(r'[0-9a-f]{40}', env.get('GITHUB_SHA', '')) is not None, 'full candidate SHA required')
    require(command(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']).decode().strip() == env['GITHUB_SHA'], 'checkout mismatch')


def inspect(name):
    return json.loads(command(DOCKER + ['inspect', name], timeout=10))[0]


def private_json(path, data):
    require(not path.is_symlink(), 'private path must not be a symlink')
    pending = path.with_suffix(path.suffix + '.new')
    fd = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(data, stream, separators=(',', ':'))
    pending.replace(path)


def assert_store_identity(before, after):
    for name in ('mysql', 'redis'):
        require(before[name] == after[name], 'persistent fixture process changed: ' + name)


def store_identity(name):
    result = {}
    for kind in ('mysql', 'redis'):
        row = inspect(name + '-' + kind)
        require(row['Name'] == '/' + name + '-' + kind
                and row['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name
                and set(row['NetworkSettings']['Networks']) == {name}
                and row['State']['Running'] and not row['State']['Paused'] and not row['State']['OOMKilled'], 'foreign/unhealthy store')
        result[kind] = {key: row['State'][key] for key in ('Pid', 'StartedAt')}
        result[kind]['id'] = row['Id']
        result[kind]['networkId'] = row['NetworkSettings']['Networks'][name]['NetworkID']
    return result


def capacity_profile_binding(report, boot, app, network_id):
    """Bind F's fixed second entry to this already verified, recovered physical boot."""
    require(app['State']['Running'] and not app['State']['Paused'] and not app['State']['OOMKilled'],
            'capacity requires the original live recovered app')
    for key in ('sourceSha', 'jarSha256', 'childClassSha256', 'configSha256'):
        require(boot[key] == report[key], 'capacity boot differs from original recovery evidence')
    require(boot['port'] == 8080 and type(boot['javaPid']) is int and boot['javaPid'] > 0,
            'capacity requires the original Java PID and port')
    proof = report['gameplayActorProof']
    require(proof['schema'] == report['schema'] and proof['serverUuid'] == report['serverUuid']
            and proof['seedBootId'] == report['initialBootId']
            and proof['identitiesSha256'] == report['identitiesSha256']
            and {label: {key: actor[key] for key in ('memberId', 'tokenSha256')}
                 for label, actor in proof['actors'].items()} == report['originalActors'],
            'capacity actors differ from original source-seeded identities')
    return {
        **{key: report[key] for key in ('sourceSha', 'jarSha256', 'childSourceSha256',
                                      'childClassSha256', 'testClassTreeSha256', 'configSha256', 'fixturePropertiesSha256')},
        'boot': dict(boot), 'appCid': app['Id'], 'appPid': app['State']['Pid'],
        'appStartedAt': app['State']['StartedAt'], 'networkId': network_id,
        'storeProcessIdentity': {key: dict(value) for key, value in report['storeProcessIdentity'].items()},
        'actorProof': json.loads(json.dumps(proof)),
    }


def require_physical_exit(cid, before, after, exit_code):
    require(before['Id'] == cid == after['Id'] and re.fullmatch(r'[0-9a-f]{64}', cid) is not None,
            'process container identity changed')
    require(before['State']['Running'] and before['State']['Pid'] > 0, 'no live original process')
    require(not after['State']['Running'] and not after['State']['OOMKilled']
            and after['State']['ExitCode'] == 137 and exit_code == 137, 'SIGKILL exit not proved')


def require_receipt(rows, request, actor, expected_count):
    require(len(rows) == expected_count, 'original receipt cardinality differs')
    if not rows:
        return None
    row = rows[0]
    request_hash = digest((str(request['activityId']) + ':' + str(actor) + ':'
                           + str(request['expectedChapterId']) + ':' + str(request['targetChapterId'])).encode())
    require(row['activity_id'] == request['activityId'] and row['actor_member_id'] == actor
            and row['request_id'] == 'lead:' + digest(request['commandId'].encode())[:48]
            and row['request_hash'] == request_hash and row['action'] == 'LEAD_UNLOCK_CHAPTER'
            and row['actor_perspective'] == 'CLUB_LEAD' and row['outcome'] == 'APPLIED', 'receipt identity/terminal mismatch')
    body = json.loads(row['result_json'])
    require(body.get('code') == 200 and body.get('data') == {
        'commandId': request['commandId'], 'expectedChapterId': request['expectedChapterId'],
        'targetChapterId': request['targetChapterId']}, 'original receipt semantics differ')
    return body


def require_one_chapter_effect(before, after, request):
    require(len(before['state']) == 1 and len(after['state']) == 1, 'chapter state cardinality changed')
    expected = dict(before['state'][0], current_chapter_id=request['targetChapterId'])
    require(after['state'][0] == expected, 'command changed more than the original single chapter CAS')


def capacity_plan(identities):
    """72 bounded fresh original intents; never member+answer permanent keys or order multipliers."""
    seen = set()
    activities = set()
    commands = set()
    plan = []
    for wave, concurrency in enumerate((1, 3, 6)):
        keys = ['CAPACITY_%02d' % i for i in range(wave * 24, wave * 24 + 24)]
        for key in keys:
            request = identities[key]
            identity = (request['activityId'], request['commandId'])
            require(identity not in seen and request['activityId'] not in activities and request['commandId'] not in commands
                    and request['expectedChapterId'] != request['targetChapterId'], 'capacity intent/target reused')
            seen.add(identity)
            activities.add(request['activityId'])
            commands.add(request['commandId'])
        plan.append((concurrency, keys))
    return plan


def observe_core_workload(identities, http, rows, run_curve, stop, evidence=None):
    """Business adapter for F.run_curve(24 tuples, request(*item), concurrency, stop, seconds=45).

    Tuple items contain a fixed kind and a private fixture key, never bearer/payload. HTTP is the
    real protected production endpoint; SQL is a separate autocommit observer on the owned server.
    F owns curve/distribution statistics. This function owns all gameplay assertions.
    """
    a, b = identities['a'], identities['b']
    lock = threading.Lock()
    stop_guard = threading.Lock()
    active_stage = {'token': None}
    evidence = evidence if evidence is not None else {}
    evidence.update({'waves': [], 'status': 'NOT_COMPLETED', 'expectedHttpRequests': 144, 'newIntents': 72,
                     'latencyScope': 'Owned Docker-exec transport plus ownership guards, not server-only service latency'})
    waves = evidence['waves']

    def fact(key):
        request = identities[key]
        return {'state': rows('SELECT * FROM club_lead_state WHERE activity_id=%s', (request['activityId'],)),
                'receipt': rows('SELECT * FROM game_command_receipt WHERE activity_id=%s AND actor_member_id=%s AND request_id=%s',
                                (request['activityId'], a['memberId'], 'lead:' + digest(request['commandId'].encode())[:48]))}

    def all_game():
        return {table: rows('SELECT * FROM `' + table + '` ORDER BY id')
                for table in ('club_lead_state', 'game_command_receipt', 'business_audit_event')}

    financial = {table: digest(rows('SELECT * FROM `' + table + '` ORDER BY id')) for table in owned.FINANCIAL_TABLES}
    for concurrency, keys in capacity_plan(identities):
        before = {key: fact(key) for key in keys}
        for key in keys:
            require(before[key]['state'][0]['current_chapter_id'] == identities[key]['expectedChapterId'], 'capacity initial state differs')
            require_receipt(before[key]['receipt'], identities[key], a['memberId'], 0)
        observations, failures = [], []
        stopped = threading.Event()
        wave = {'concurrency': concurrency, 'newCommands': 24, 'readReplayForeign': 24,
                'originalIntentSha256': digest([identities[key]['commandId'] for key in keys]), 'status': 'NOT_COMPLETED'}
        waves.append(wave)

        def deadline_stop():
            # F's stop argument is physical owned termination, never a sampler or NOT_SENT proof.
            with lock:
                if stopped.is_set():
                    return
                stopped.set()
            evidence['deadlineStop'] = stop()

        def stopped_facts():
            facts = [{'commandId': identities[key]['commandId'], 'activityId': identities[key]['activityId'],
                      'requestSha256': digest(identities[key]), 'outcome': 'UNKNOWN_NOT_YET_READ'} for key in keys]
            wave.update({'status': 'CURVE_DEADLINE_UNKNOWN_NOT_A_RECOVERY_BOUNDARY', 'originalIntents': facts,
                         'stopProof': evidence.get('deadlineStop', {'status': 'TERMINATION_NOT_CONFIRMED'})})
            for index, key in enumerate(keys):
                observed = fact(key)
                outcome = 'UNKNOWN_PRESERVE_ORIGINAL'
                if len(observed['receipt']) == 1 and observed['receipt'][0]['outcome'] == 'APPLIED':
                    require_receipt(observed['receipt'], identities[key], a['memberId'], 1)
                    require_one_chapter_effect(before[key], observed, identities[key])
                    outcome = 'APPLIED_OBSERVED_BY_SQL'
                facts[index].update({'factSha256': digest(observed), 'outcome': outcome})

        def curve(items):
            require(len(items) == 24, 'F curve requires exactly 24 fixed tuple items')
            token = object()
            with stop_guard:
                active_stage['token'] = token

            def stage_stop():
                with stop_guard:
                    if active_stage['token'] is token:
                        deadline_stop()
            try:
                result = run_curve(items, request, concurrency, stage_stop, seconds=45)
            except BaseException as failure:
                wave.setdefault('firstFailureType', type(failure).__name__)
                if stopped.is_set():
                    try:
                        stopped_facts()
                    except BaseException as fact_failure:
                        wave['independentFactReadFailureType'] = type(fact_failure).__name__
                raise  # The first F/termination failure is not replaced by a SQL/readback failure.
            finally:
                with stop_guard:
                    if active_stage['token'] is token:
                        active_stage['token'] = None
            if stopped.is_set():
                stopped_facts()
                raise RuntimeError('curve deadline stopped or could not confirm child; original intents UNKNOWN unless SQL proves APPLIED; no replay')
            return result

        def request(kind, key):
            started = time.monotonic()
            try:
                require(key in keys and kind in ('command', 'replay', 'receipt', 'foreign'), 'undeclared workload item')
                frozen = {field: identities[key][field] for field in ('activityId', 'commandId', 'expectedChapterId', 'targetChapterId')}
                if kind == 'receipt':
                    import urllib.parse
                    result = http(RECEIPT + '?' + urllib.parse.urlencode({field: frozen[field] for field in ('activityId', 'commandId')}), a['token'])
                else:
                    result = http(COMMAND, b['token'] if kind == 'foreign' else a['token'], frozen)
                if kind == 'foreign':
                    require(result.get('code') != 200 and not result.get('data')
                            and not str(result.get('msg', '')).startswith('已开启:'), 'foreign workload disclosed/wrote a result')
                else:
                    require(result.get('code') == 200 and result.get('data') == {field: frozen[field]
                            for field in ('commandId', 'expectedChapterId', 'targetChapterId')}, 'workload did not return original business result')
                # F's fixed category protocol; success remains provisional until the SQL checks below.
                observation = {'category': 'SUCCESS' if kind != 'foreign' else 'LEGAL_AUTH_REJECTION',
                               'elapsedSeconds': time.monotonic() - started, 'kind': kind, 'fixtureKey': key}
                with lock:
                    observations.append(observation)
                return observation
            except BaseException as failure:
                observation = {'category': 'UNKNOWN', 'elapsedSeconds': time.monotonic() - started,
                               'reasonCode': 'GAMEPLAY_OBSERVATION_UNVERIFIED',
                               'stage': {'command': 'COMMAND', 'replay': 'REPLAY', 'receipt': 'RECEIPT',
                                         'foreign': 'FOREIGN'}.get(kind, 'UNDECLARED_STAGE'),
                               'kind': kind, 'fixtureKey': key}
                with lock:
                    failures.append(type(failure).__name__)
                    observations.append(observation)
                return observation

        writes = curve([('command', key) for key in keys])
        wave['writeCurve'] = writes
        require(not failures and len(observations) == 24, 'curve omitted or suppressed a real first command')
        for key in keys:
            after = fact(key)
            require_one_chapter_effect(before[key], after, identities[key])
            require_receipt(after['receipt'], identities[key], a['memberId'], 1)
        committed = all_game()
        readbacks = curve([(('replay', 'receipt', 'foreign')[index % 3], key) for index, key in enumerate(keys)])
        wave['readbackCurve'] = readbacks
        require(not failures and len(observations) == 48, 'curve omitted/suppressed a protected read/replay/rejection')
        require(all_game() == committed, 'capacity replay/foreign request changed game facts')
        require({table: digest(rows('SELECT * FROM `' + table + '` ORDER BY id')) for table in owned.FINANCIAL_TABLES}
                == financial, 'non-cash workload changed financial facts')
        wave.update({'committedGameFactsSha256': digest(committed), 'status': 'ASSERTIONS_PASSED'})
    evidence.update({'realHttpRequests': 144, 'status': 'BUSINESS_ASSERTIONS_PASSED',
                     'scope': 'Chapter command/receipt/replay/foreign refusal only; no cash/provider/all-Q8 claim'})
    return evidence


def run(name, schema, out, credentials):
    gate(os.environ)
    capacity = os.environ.get('CHENGYIN_CI_GAMEPLAY_CAPACITY', 'false')
    require(capacity in ('true', 'false'), 'invalid capacity mode')
    capacity_module = None
    if capacity == 'true':
        require((ROOT / 'ci/capacity_mixed_probe.py').is_file(), 'root-composed F source required; no replacement statistics')
        capacity_module = load('gameplay_shared_f_capacity', 'ci/capacity_mixed_probe.py')
    require(re.fullmatch(r'cy-auth-[0-9a-f]{32}', name) is not None and schema == 'cy_e2e_auth_' + name[8:], 'wrong owned namespace')
    require(credentials.name == 'credentials.json' and credentials.stat().st_mode & 0o777 == 0o600
            and not credentials.is_symlink() and credentials.resolve() == credentials.absolute(), 'private credential owner mismatch')
    require(out.is_dir() and not out.is_symlink() and out.resolve() == out.absolute(), 'owned evidence directory required')
    saved = json.loads(credentials.read_text())
    require(saved['schema'] == schema, 'wrong private schema')
    owned.SECRET_VALUES.extend(saved['redactions'])
    require(command(DOCKER + ['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).decode().strip()
            == 'unix:///var/run/docker.sock', 'nonlocal Docker daemon')
    mysql_endpoint = owned.mysql_endpoint(name)
    mysql = inspect(name + '-mysql')
    redis = inspect(name + '-redis')
    network = json.loads(command(DOCKER + ['network', 'inspect', name]))[0]
    require(network['Internal'] and network.get('Labels', {}).get('chengyin.auth-recovery') == name
            and redis['Name'] == '/' + name + '-redis'
            and redis['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name
            and set(redis['NetworkSettings']['Networks']) == {name}
            and redis['NetworkSettings']['Networks'][name]['NetworkID'] == network['Id'], 'foreign fixture network')
    # No attach/reuse of an application from a different phase, even with a matching label.
    prior = subprocess.run(DOCKER + ['container', 'inspect', name + '-app'], capture_output=True, timeout=10)
    owned.require_absent('container', name + '-app', prior.returncode, prior.stderr)
    stores = store_identity(name)
    jar = ROOT / 'chengyinhub-admin/target/chengyinhub-admin.jar'
    tests = ROOT / 'chengyinhub-admin/target/test-classes'
    main_class = tests / (CHILD_PATH + '.class')
    require(main_class.is_file() and not main_class.is_symlink(), 'compiled test child required from the same candidate build')
    # Do not add every test class/configuration to a normal application's component scan.
    # Mount only this fixed child and its compiler-generated nested classes, all byte-identical.
    child_classes = out / 'gameplay-child-classes'
    child_classes.mkdir(mode=0o700)
    compiled = [path for path in sorted(main_class.parent.glob('GameplayProcessRecoveryChild*.class'))
                if path.name == 'GameplayProcessRecoveryChild.class' or path.name.startswith('GameplayProcessRecoveryChild$')]
    require(main_class in compiled and all(path.is_file() and not path.is_symlink() for path in compiled), 'unexpected child class output')
    for path in compiled:
        destination = child_classes / path.relative_to(tests)
        destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        destination.chmod(0o600)
        require(digest(path.read_bytes()) == digest(destination.read_bytes()), 'compiled child copy changed')
    with zipfile.ZipFile(jar) as artifact:
        require(not any(CHILD_PATH in item for item in artifact.namelist()), 'normal JAR contains the test child')
        for entry in artifact.namelist():
            if entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(artifact.read(entry))) as dependency:
                    require(not any(CHILD_PATH in item for item in dependency.namelist()), 'published dependency contains the test child')
        props = dict(line.split('=', 1) for line in artifact.read('BOOT-INF/classes/git.properties').decode().splitlines()
                     if '=' in line and not line.startswith('#'))
        require(props.get('git.commit.id.full', props.get('git.commit.id')) == os.environ['GITHUB_SHA'], 'JAR source mismatch')
    require(json.loads((out / 'schema-summary.json').read_text())['nativeJarSha256'] == digest(jar.read_bytes()), 'schema/JAR provenance mismatch')
    private = credentials.parent / 'gameplay'
    private.mkdir(mode=0o700)  # exclusive: never resume a half-created host run by deleting its evidence
    app = None
    pool = None
    started = time.monotonic()
    deadline = started + 900
    report = {'result': 'NOT_COMPLETED', 'sourceSha': os.environ['GITHUB_SHA'], 'jarSha256': digest(jar.read_bytes()),
              'schema': schema, 'serverUuid': saved['serverUuid'], 'storeProcessIdentity': stores,
              'fixturePropertiesSha256': digest((ROOT / 'ci/upgrade-startup/application.properties').read_bytes()),
              'childSourceSha256': digest((ROOT / ('chengyinhub-admin/src/test/java/' + CHILD_PATH + '.java')).read_bytes()),
              'childClassSha256': digest(main_class.read_bytes()), 'testClassTreeSha256': digest({
                  str(p.relative_to(child_classes)): digest(p.read_bytes()) for p in sorted(child_classes.rglob('*.class'))}),
              'cases': [], 'cleanup': 'PENDING_ORIGINAL_OUTER_OWNER',
              'limits': {'wallSeconds': 900, 'appMemoryBytes': 1073741824, 'appCpu': 1, 'httpSeconds': 100},
              'scope': 'Real chapter command and receipt; not provider/cash/runtime-epoch/all-T13 acceptance'}
    token_secret = secrets.token_urlsafe(48)
    owned.SECRET_VALUES.append(token_secret)
    private_json(credentials, {**saved, 'redactions': owned.SECRET_VALUES})
    config = {'sourceSha': os.environ['GITHUB_SHA'], 'jarSha256': report['jarSha256'],
              'childClassSha256': report['childClassSha256'], 'schema': schema, 'serverUuid': saved['serverUuid'],
              'jdbcUrl': 'jdbc:mysql://' + mysql_endpoint['ip'] + ':3306/' + schema
                         + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
              'redisHost': redis['NetworkSettings']['Networks'][name]['IPAddress'], 'tokenSecret': token_secret,
              'capacityItems': 72 if capacity_module else 0}
    private_json(private / 'config.json', config)
    report['configSha256'] = digest((private / 'config.json').read_bytes())

    def alive_budget():
        require(time.monotonic() < deadline, 'host wall budget exceeded')
        assert_store_identity(stores, store_identity(name))

    def ready(previous_boot=None):
        for _ in range(150):
            alive_budget()
            if (private / 'ready.json').is_file():
                value = json.loads((private / 'ready.json').read_text())
                if value['bootId'] != previous_boot:
                    require(digest(jar.read_bytes()) == report['jarSha256'] and digest({
                        str(p.relative_to(child_classes)): digest(p.read_bytes()) for p in sorted(child_classes.rglob('*.class'))
                    }) == report['testClassTreeSha256'], 'JAR/test class tree changed across child boots')
                    require(value['sourceSha'] == report['sourceSha'] and value['jarSha256'] == report['jarSha256']
                            and value['childClassSha256'] == report['childClassSha256']
                            and value['configSha256'] == report['configSha256'] and value['javaPid'] > 0 and value['port'] == 8080,
                            'child provenance/port mismatch')
                    require(inspect(app)['State']['Running'], 'child not running at ready')
                    return value
            require(inspect(app)['State']['Running'], 'child exited before ready')
            time.sleep(1)
        raise RuntimeError('bounded child startup timeout')

    def http(path, token=None, fields=None):
        alive_budget()
        args = DOCKER + ['exec', '-i', app, 'curl', '--silent', '--show-error', '--noproxy', '*',
                         '--connect-timeout', '5', '--max-time', '95', '--config', '-']
        text = 'url = "http://127.0.0.1:8080' + path + '"\n'
        if token:
            text += 'header = "Authorization: Bearer ' + token + '"\n'
        if fields is not None:
            import urllib.parse
            if 'commandId' in fields:
                require(re.fullmatch(r'[A-Za-z0-9:_-]{1,80}', fields['commandId']) is not None, 'invalid original command identity')
                text += 'header = "X-Request-Id: ' + fields['commandId'] + '"\n'
            text += 'header = "Content-Type: application/x-www-form-urlencoded"\n'
            text += 'data = ' + json.dumps(urllib.parse.urlencode(fields)) + '\n'
        return json.loads(command(args, text.encode(), timeout=100))

    try:
        strace = shutil.which('strace')
        require(strace and (out / 'trace-libs').is_dir(), 'reuse outer tracing preparation')
        launched = command(DOCKER + ['run', '-d', '--name', name + '-app', '--label', 'chengyin.auth-recovery=' + name,
                '--network', name, '--dns', '127.0.0.1', '--memory=1g', '--memory-swap=1g', '--cpus=1',
                '--read-only', '--tmpfs', '/tmp:rw,size=256m', '--cap-drop', 'ALL', '--cap-add', 'SYS_PTRACE',
                '--user', str(os.getuid()) + ':' + str(os.getgid()), '-e', 'LD_LIBRARY_PATH=/trace-libs',
                '-e', 'GITHUB_SHA=' + os.environ['GITHUB_SHA'], '-e', 'CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY=true',
                '-v', strace + ':/trace/strace:ro', '-v', str(out / 'trace-libs') + ':/trace-libs:ro',
                '-v', str(jar) + ':/application.jar:ro', '-v', str(child_classes) + ':/recovery-testclasses:ro',
                '-v', str(ROOT / 'ci/upgrade-startup') + ':/fixture:ro', '-v', str(out) + ':/evidence',
                '-v', str(private) + ':/recovery-private', '--entrypoint', '/trace/strace', JDK_IMAGE,
                '-A', '-f', '-s', '0', '-e', 'trace=connect,sendto,sendmsg', '-o', '/evidence/gameplay-network.trace',
                'java', '-Xmx512m', '-Dlogging.config=/fixture/logback.xml', '-Dlogback.configurationFile=/fixture/logback.xml',
                '-Dloader.path=/recovery-testclasses', '-Dloader.main=' + CHILD,
                '-cp', '/application.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher'], timeout=120)
        app = launched.decode().strip()
        require(re.fullmatch(r'[0-9a-f]{64}', app) is not None and inspect(name + '-app')['Id'] == app,
                'created app CID not independently confirmed')
        objects = [inspect(app), mysql, redis]
        owned.validate_targets(name, schema, 'unix:///var/run/docker.sock', objects, network)
        require(objects[0]['HostConfig']['Memory'] == 1073741824 and objects[0]['HostConfig']['NanoCpus'] == 1000000000
                and not objects[0]['HostConfig'].get('PortBindings'), 'app budget/publication mismatch')
        boot = ready()
        identities_file = private / 'identities.json'
        require(identities_file.is_file() and not identities_file.is_symlink()
                and identities_file.stat().st_mode & 0o777 == 0o600, 'original private actor identity file required')
        identities = json.loads(identities_file.read_text())
        report['initialBootId'] = boot['bootId']
        report['identitiesSha256'] = digest(identities_file.read_bytes())
        a, b = identities['a'], identities['b']
        owned.SECRET_VALUES.extend([a['token'], b['token']])
        private_json(credentials, {**saved, 'redactions': owned.SECRET_VALUES})
        report['originalActors'] = {label: {'memberId': row['memberId'], 'tokenSha256': digest(row['token'].encode())}
                                    for label, row in [('a', a), ('b', b)]}
        report['ownedAppCid'] = app
        with owned.mysql_relay(name, {'endpoint': mysql_endpoint}) as port, owned.database(port, schema) as db:
            def rows(sql, params=()):
                with db.cursor() as cursor:
                    cursor.execute('SELECT @@server_uuid,DATABASE()')
                    require(cursor.fetchone() == (saved['serverUuid'], schema), 'independent SQL identity differs')
                    cursor.execute(sql, params)
                    keys = [item[0] for item in cursor.description]
                    return [dict(zip(keys, row)) for row in cursor.fetchall()]

            def fact(request):
                return {'state': rows('SELECT * FROM club_lead_state WHERE activity_id=%s', (request['activityId'],)),
                        'receipt': rows('SELECT * FROM game_command_receipt WHERE activity_id=%s AND actor_member_id=%s AND request_id=%s',
                                        (request['activityId'], a['memberId'], 'lead:' + digest(request['commandId'].encode())[:48]))}

            def finance():
                # Every original financial/auth fact table is checked independently, never just HTTP200.
                return {table: {'rows': len(value), 'sha256': digest(value)} for table in owned.FINANCIAL_TABLES
                        for value in [rows('SELECT * FROM `' + table + '` ORDER BY id')]}

            def all_game_facts():
                return {table: rows('SELECT * FROM `' + table + '` ORDER BY id')
                        for table in ('club_lead_state', 'game_command_receipt', 'business_audit_event')}

            financial = finance()
            report['financialBefore'] = financial
            owned.write_report(out, 'gameplay-process-recovery.json', report)
            if capacity_module:
                # Capture once before any kill/command; the observer must not invent a new live baseline.
                empty = {table: {'rows': len(value), 'sha256': digest(value)}
                         for table in capacity_module.GAMEPLAY_EMPTY_TABLES
                         for value in [rows('SELECT * FROM `' + table + '` ORDER BY id')]}
                report['gameplayEconomicStoresBefore'] = empty
                owned.write_report(out, 'gameplay-process-recovery.json', report)
                bad = [table for table, value in {**financial, **empty}.items()
                       if table != 'ums_member' and value['rows'] != 0]
                if bad:
                    raise capacity_module.NoCashFixtureError('GAMEPLAY_UNEXPECTED_ECONOMIC_ROWS', bad)
                members = rows('SELECT * FROM ums_member ORDER BY id')
                columns = list(members[0]) if members else []
                authority = {table: {'rows': len(value),
                    'sha256': capacity_module.digest([tuple(row.values()) for row in value]),
                    'ddlSha256': capacity_module.digest([tuple(row.values()) for row in rows('SHOW CREATE TABLE `' + table + '`')])}
                    for table in capacity_module.AUTHORITY_TABLES
                    for value in [rows('SELECT * FROM `' + table + '` ORDER BY 1')]}
                authority['accountCredentials'] = capacity_module.digest([tuple(row.values()) for row in rows(
                    'SELECT user_id,user_name,password,status,del_flag FROM sys_user ORDER BY user_id')])
                report['gameplayActorProof'] = capacity_module.gameplay_actor_proof(
                    identities, columns, [tuple(row[column] for column in columns) for row in members],
                    schema, saved['serverUuid'], report['initialBootId'], report['identitiesSha256'], authority)
                owned.write_report(out, 'gameplay-process-recovery.json', report)
            for phase in PHASES:
                original = identities[phase]
                request = {key: original[key] for key in ('activityId', 'commandId', 'expectedChapterId', 'targetChapterId')}
                before = fact(request)
                require(len(before['state']) == 1 and before['state'][0]['current_chapter_id'] == request['expectedChapterId'], 'wrong initial chapter')
                require_receipt(before['receipt'], request, a['memberId'], 0)
                case = {'boundary': phase, 'commandId': request['commandId'], 'activityId': request['activityId'],
                        'requestSha256': digest(request), 'beforeSha256': digest(before), 'firstResponseReceived': False}
                report['cases'].append(case)
                # Only the test observer is armed; no production switch/header is added.
                if phase != 'ACKED':
                    private_json(private / 'arm.json', {**request, 'stage': phase})
                    pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
                    pending = pool.submit(http, COMMAND, a['token'], request)
                    for _ in range(100):
                        alive_budget()
                        if (private / 'boundary.json').exists():
                            boundary = json.loads((private / 'boundary.json').read_text())
                            require(boundary['stage'] == phase and boundary['bootId'] == boot['bootId']
                                    and boundary['javaPid'] == boot['javaPid'] and boundary['commandId'] == request['commandId']
                                    and boundary['activityId'] == request['activityId'], 'wrong physical boundary')
                            break
                        require(not pending.done(), 'HTTP returned before the selected boundary')
                        time.sleep(.1)
                    else:
                        raise RuntimeError('real HTTP did not reach the selected boundary')
                else:
                    response = http(COMMAND, a['token'], request)
                    require(response.get('code') == 200, 'original acknowledged command rejected')
                    case['firstResponseReceived'] = True
                at_kill = fact(request)
                if phase == 'BEFORE_TX':
                    require(at_kill == before, 'pre-transaction command already changed facts')
                else:
                    require_one_chapter_effect(before, at_kill, request)
                    stored = require_receipt(at_kill['receipt'], request, a['memberId'], 1)
                    if phase == 'ACKED':
                        require(response == stored, 'acknowledged response differs from committed receipt')
                original_process = inspect(app)
                require(original_process['Name'] == '/' + name + '-app'
                        and original_process['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name
                        and set(original_process['NetworkSettings']['Networks']) == {name}, 'kill target no longer owned')
                cmdline = command(DOCKER + ['exec', app, 'cat', '/proc/' + str(boot['javaPid']) + '/cmdline'])
                require(b'org.springframework.boot.loader.launch.PropertiesLauncher' in cmdline
                        and ('-Dloader.main=' + CHILD).encode() in cmdline, 'ready PID does not identify the child JVM')
                case['originalProcessCommandSha256'] = digest(cmdline)
                command(DOCKER + ['kill', '--signal', 'KILL', app])
                exit_code = int(command(DOCKER + ['wait', app], timeout=20).strip())
                killed = inspect(app)
                require_physical_exit(app, original_process, killed, exit_code)
                case['physicalExit'] = {'code': exit_code, 'oomKilled': False, 'oldJavaPid': boot['javaPid'],
                                        'oldHostPid': original_process['State']['Pid'], 'oldBootId': boot['bootId'],
                                        'originalStartedAt': original_process['State']['StartedAt'],
                                        'finishedAt': killed['State']['FinishedAt']}
                if phase != 'ACKED':
                    try:
                        pending.result(timeout=10)
                    except Exception:
                        pass  # Transport loss is expected; SQL, not this exception, proves commit/noncommit.
                    else:
                        raise RuntimeError('lost-reply case unexpectedly returned a complete body')
                    pool.shutdown(wait=True)
                    pool = None
                    (private / 'arm.json').unlink()
                    (private / 'boundary.json').unlink()
                require(fact(request) == at_kill, 'facts changed after actual process death')
                recovery_started = time.monotonic()
                command(DOCKER + ['start', app], timeout=20)
                old_boot = boot['bootId']
                boot = ready(old_boot)
                case['newBootId'] = boot['bootId']
                case['recoverySeconds'] = round(time.monotonic() - recovery_started, 3)
                require(boot['bootId'] != old_boot, 'not an independent application restart')
                import urllib.parse
                receipt_path = RECEIPT + '?' + urllib.parse.urlencode({key: request[key] for key in ('activityId', 'commandId')})
                readback = http(receipt_path, a['token'])
                if phase == 'BEFORE_TX':
                    require(readback.get('code') == 520, 'unsubmitted original unexpectedly has a terminal receipt')
                    # 520 alone is UNKNOWN. Only the controlled pre-TX boundary plus independent SQL
                    # permits this fixture's exact original command to be executed after restart.
                else:
                    require(readback == stored, 'original actor/token cannot recover its original committed result')
                replay = http(COMMAND, a['token'], request)
                require(replay.get('code') == 200, 'same original intent did not recover')
                after = fact(request)
                canonical = require_receipt(after['receipt'], request, a['memberId'], 1)
                require_one_chapter_effect(before, after, request)
                require(replay == canonical and after['state'][0]['current_chapter_id'] == request['targetChapterId'], 'replay skipped a chapter')
                if phase != 'BEFORE_TX':
                    require(after == at_kill, 'committed replay changed a row')
                negative_before = fact(request)
                all_before = all_game_facts()
                for token in (None, b['token']):
                    for path, body in [(receipt_path, None), (COMMAND, request)]:
                        denied = http(path, token, body)
                        require(denied.get('code') != 200 and not denied.get('data') and denied.get('msg') != canonical.get('msg'),
                                'foreign/anonymous replay disclosed a terminal result')
                for changed in (dict(request, targetChapterId=original['thirdChapterId']),
                                dict(request, expectedChapterId=request['targetChapterId'])):
                    require(http(COMMAND, a['token'], changed).get('code') == 409, 'changed canonical input accepted')
                wrong_target = dict(request, activityId=identities[PHASES[(PHASES.index(phase) + 1) % 3]]['activityId'])
                require(http(COMMAND, a['token'], wrong_target).get('code') != 200, 'wrong activity accepted original input')
                require(fact(request) == negative_before and all_game_facts() == all_before and finance() == financial,
                        'rejected command changed original/other actor/target/event/financial facts')
                later = dict(request, commandId=request['commandId'] + '-next', expectedChapterId=request['targetChapterId'],
                             targetChapterId=original['thirdChapterId'])
                require(http(COMMAND, a['token'], later).get('code') == 200, 'legal next command failed')
                advanced = fact(request)
                require(advanced['state'][0]['current_chapter_id'] == original['thirdChapterId'], 'next intent did not advance exactly once')
                require(http(COMMAND, a['token'], request) == canonical and http(receipt_path, a['token']) == canonical,
                        'original terminal result lost after entity state changed')
                require(fact(request) == advanced and finance() == financial, 'original replay produced another effect')
                case.update({'result': 'ASSERTIONS_PASSED', 'afterSha256': digest(advanced),
                             'originalReceiptId': advanced['receipt'][0]['id'], 'receiptSha256': digest(advanced['receipt']),
                             'financialTables': financial, 'runtimeEpoch': 'NOT_APPLICABLE_NO_SUCH_INPUT_ON_THIS_COMMAND',
                             'originalExpectedAndTargetConflictsRejected': True, 'rejectedAllGameFactsSha256': digest(all_before)})
            if capacity_module:
                def curve_stop():
                    current = inspect(app)
                    require(current['Name'] == '/' + name + '-app'
                            and current['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name
                            and set(current['NetworkSettings']['Networks']) == {name}
                            and current['NetworkSettings']['Networks'][name]['NetworkID'] == network['Id'], 'curve stop target not owned')
                    command(DOCKER + ['kill', '--signal', 'KILL', app])
                    code = int(command(DOCKER + ['wait', app], timeout=20).strip())
                    ended = inspect(app)
                    require_physical_exit(app, current, ended, code)
                    report['capacityDeadlineStop'] = {'kind': 'CURVE_DEADLINE_NOT_ONE_OF_THREE_RECOVERY_BOUNDARIES',
                        'appCid': app, 'bootId': boot['bootId'], 'exitCode': code, 'finishedAt': ended['State']['FinishedAt'],
                        'originalIntentsRemainUnknownUnlessSqlProvesApplied': True}
                    assert_store_identity(stores, store_identity(name))
                    return report['capacityDeadlineStop']
                report['coreBusinessCurve'] = {}
                observe_core_workload(identities, http, rows, capacity_module.run_curve, curve_stop, report['coreBusinessCurve'])
                # F's original mixed/actor/dict/lease observer remains separately scoped. Same app,
                # server UUID, namespace and relay; no second schema/startup/cleaner.
                require(rows('SELECT COUNT(*) AS n FROM refund_application')[0]['n'] == 0, 'F owns its original empty refund fixture')
                mixed_before = inspect(app)
                require(mixed_before['State']['Running'], 'F mixed observer needs the healthy recovered app')
                mixed_health = http(RECEIPT + '?' + urllib.parse.urlencode({field: identities['ACKED'][field]
                                    for field in ('activityId', 'commandId')}), a['token'])
                require(mixed_health.get('code') == 200, 'actual protected receipt unhealthy before F observe')
                capacity_module.observe(name, schema, out, credentials, port,
                    gameplay_binding=capacity_profile_binding(report, boot, mixed_before, network['Id']))
                mixed_after = inspect(app)
                require(mixed_after['Id'] == mixed_before['Id'] and mixed_after['State']['Running']
                        and {key: mixed_after['State'][key] for key in ('Pid', 'StartedAt')}
                        == {key: mixed_before['State'][key] for key in ('Pid', 'StartedAt')}, 'app changed inside F observe')
                report['fObserveProcessStamp'] = {'appCid': app, 'bootId': boot['bootId'],
                    'pid': mixed_before['State']['Pid'], 'startedAt': mixed_before['State']['StartedAt'],
                    'scope': 'Only F observe window, not the earlier physical restarts'}
                report['fObserveInvoked'] = True
            else:
                report['coreBusinessCurve'] = {'status': 'NOT_REQUESTED_EXPLICIT_MODE_REQUIRED'}
            alive_budget()
        command(DOCKER + ['stop', '--time', '10', app], timeout=25)
        verifier = load('gameplay_owned_network_verifier', 'ci/upgrade_startup_network.py')
        report['observedNetworkCalls'] = verifier.verify((out / 'gameplay-network.trace').read_text(), mysql_endpoint['ip'], config['redisHost'])
        report['result'] = 'BUSINESS_ASSERTIONS_PASSED_CLEANUP_PENDING'
    except BaseException as failure:
        report['result'] = 'FAIL_OR_UNKNOWN_NO_RETRY'
        report['failureType'] = type(failure).__name__
        report['reason'] = owned.redact(str(failure), owned.SECRET_VALUES)
        report.setdefault('firstFailure', {'type': type(failure).__name__,
            'reasonCode': getattr(failure, 'reason_code', 'GAMEPLAY_BUSINESS_FAILED'),
            'tables': getattr(failure, 'tables', [])})
        raise
    finally:
        report['elapsedSeconds'] = round(time.monotonic() - started, 3)
        owned.write_report(out, 'gameplay-process-recovery.json', report)
        # Original credentials must survive for the outer owner's redacted log capture/cleanup.
        # Only exact files owned by this adapter are removed. Unknown leftovers fail closed.
        if app is None:
            discovered = subprocess.run(DOCKER + ['container', 'inspect', name + '-app'], capture_output=True, timeout=10)
            if discovered.returncode == 0:
                app = json.loads(discovered.stdout)[0]['Id']
            else:
                owned.require_absent('container', name + '-app', discovered.returncode, discovered.stderr)
        if app is not None:
            row = inspect(app)
            require(row['Name'] == '/' + name + '-app'
                    and row['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name, 'cleanup app identity differs')
            if row['State']['Running']:
                command(DOCKER + ['stop', '--time', '10', app], timeout=25)
        identity_path = private / 'identities.json'
        if identity_path.exists():
            require(identity_path.is_file() and not identity_path.is_symlink(), 'private identity target changed')
            identity = json.loads(identity_path.read_text())
            owned.SECRET_VALUES.extend(identity[actor]['token'] for actor in ('a', 'b'))
            private_json(credentials, {**saved, 'redactions': owned.SECRET_VALUES})
        if pool is not None:
            pool.shutdown(wait=True, cancel_futures=True)
        for filename in ('config.json', 'identities.json', 'ready.json', 'arm.json', 'boundary.json'):
            path = private / filename
            if path.exists():
                require(path.is_file() and not path.is_symlink(), 'private cleanup target changed')
                path.unlink()
        private.rmdir()


def finalize(name, schema, out, original_exit):
    """Called only after the original owner's container/network/private cleanup has finished."""
    gate(os.environ)
    path = out / 'gameplay-process-recovery.json'
    report = json.loads(path.read_text())
    report.setdefault('businessResultBeforeCleanup', report['result'])
    if report.get('failureType'):
        report.setdefault('firstFailure', {'type': report['failureType'], 'reasonCode': 'RECORDED_GAMEPLAY_BUSINESS_FAILURE'})
    try:
        require(original_exit == 0, 'original shell/cleanup did not succeed')
        if os.environ.get('CHENGYIN_CI_GAMEPLAY_CAPACITY') == 'true':
            shared = load('gameplay_final_f_capacity', 'ci/capacity_mixed_probe.py')
            result = shared.finalize(name, schema, out, original_exit)
            require(result is None or type(result) is int and result == 0, 'F scoped mixed finalization failed')
        require(report['sourceSha'] == os.environ['GITHUB_SHA'] and report['schema'] == schema
                and schema == 'cy_e2e_auth_' + name[8:], 'final receipt source/namespace mismatch')
        require(report['result'] == 'BUSINESS_ASSERTIONS_PASSED_CLEANUP_PENDING'
                and [case['boundary'] for case in report['cases']] == list(PHASES)
                and all(case['result'] == 'ASSERTIONS_PASSED' and case['physicalExit']['code'] == 137
                        and case['physicalExit']['oomKilled'] is False
                        and case['newBootId'] != case['physicalExit']['oldBootId'] for case in report['cases']), 'business cases incomplete')
        outer = json.loads((out / 'authenticated-redis-recovery.json').read_text())
        cleanup = outer['resourceCleanup']
        require(cleanup['cleanup'] == 'CONFIRMED_EXACT_OWNED_REMOVAL' and outer.get('applicationLogPreserved') is True,
                'original cleanup/log capture unconfirmed')
        expected = {name + '-app': report['ownedAppCid'], name + '-mysql': report['storeProcessIdentity']['mysql']['id'],
                    name + '-redis': report['storeProcessIdentity']['redis']['id']}
        require(len(cleanup['ownedRemoved']) == 3 and {row['name']: row['id'] for row in cleanup['ownedRemoved']} == expected
                and all(row['independentNoSuch'] is True for row in cleanup['ownedRemoved']), 'cleanup removed different/unknown resources')
        if os.environ.get('CHENGYIN_CI_GAMEPLAY_CAPACITY') == 'true':
            require(report.get('fObserveInvoked') is True and report['coreBusinessCurve']['realHttpRequests'] == 144,
                    'business adapter/mixed observer not executed')
        report['cleanup'] = cleanup['cleanup']
        report['outerCleanupReceiptSha256'] = digest((out / 'authenticated-redis-recovery.json').read_bytes())
        report['result'] = 'PASS_SCOPED_GAMEPLAY_PROCESS_RECOVERY'
    except BaseException as failure:
        report['result'] = 'FAIL_OR_UNKNOWN_CLEANUP_OR_SCOPE'
        report['finalizationFailureType'] = type(failure).__name__
        report.setdefault('firstFailure', {'type': type(failure).__name__, 'reasonCode': 'FINALIZATION_FAILED'})
        raise
    finally:
        owned.write_report(out, path.name, report)


if __name__ == '__main__':
    if len(sys.argv) == 2 and sys.argv[1] == '--check-host-only':
        gate(os.environ)
    elif len(sys.argv) == 6 and sys.argv[1] == 'run':
        run(sys.argv[2], sys.argv[3], Path(sys.argv[4]), Path(sys.argv[5]))
    elif len(sys.argv) == 6 and sys.argv[1] == 'finalize':
        finalize(sys.argv[2], sys.argv[3], Path(sys.argv[4]), int(sys.argv[5]))
    else:
        raise SystemExit('Expected --check-host-only, run NAME SCHEMA OUT PRIVATE_CREDENTIALS, or finalize NAME SCHEMA OUT ORIGINAL_EXIT')
