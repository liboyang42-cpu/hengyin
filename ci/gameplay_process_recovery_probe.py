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


def store_identity(name, *, allow_redis_pause=False):
    result = {}
    for kind in ('mysql', 'redis'):
        row = inspect(name + '-' + kind)
        require(row['Name'] == '/' + name + '-' + kind
                and row['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name
                and set(row['NetworkSettings']['Networks']) == {name}
                and row['State']['Running']
                and (not row['State']['Paused'] or kind == 'redis' and allow_redis_pause)
                and not row['State']['OOMKilled'], 'foreign/unhealthy store')
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


def observe_core_workload(identities, http, rows, run_curve, stop, evidence=None, *, before_readback=None):
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
        if before_readback is not None:
            wave['subjectTransition'] = before_readback(concurrency)
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


# Fixed additional subject, not an alias for the test-classpath recovery child.
PACKAGED_SUBJECT = 'PACKAGED_RUOYI_MAIN'
PACKAGED_COMMAND = ['java', '-Xmx512m', '-Dlogging.config=/fixture/logback.xml',
                    '-Dlogback.configurationFile=/fixture/logback.xml', '-jar', '/application.jar',
                    '--spring.config.location=file:/fixture/application.properties,file:/recovery-private/packaged.properties',
                    '--spring.messages.basename=i18n/messages']
PACKAGED_CLASSES = (
    'com/chengyinhub/RuoYiApplication',
    'com/chengyinhub/web/controller/api/ApiClubLeadController',
    'com/chengyinhub/business/mapper/ClubLeadMapper',
    'com/chengyinhub/business/domain/GameCommandReceipt',
)


def packaged_gate(env):
    gate(env)
    require(env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and env.get('CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY') == 'true'
            and env.get('CHENGYIN_CI_GAMEPLAY_CAPACITY') == 'true',
            'fixed manual packaged gameplay admission required')


def packaged_jar_proof(jar, source):
    require(jar.is_file() and not jar.is_symlink() and jar.resolve() == jar.absolute(),
            'original packaged JAR path required')
    with zipfile.ZipFile(jar) as archive:
        entries = archive.namelist()
        require(len(entries) == len(set(entries)), 'duplicate packaged entries')
        # JAR manifest continuation is one leading space, never arbitrary stripping.
        lines = archive.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n', '\n').split('\n')
        unfolded = []
        for line in lines:
            if line.startswith(' '):
                require(bool(unfolded), 'invalid manifest continuation')
                unfolded[-1] += line[1:]
            else:
                unfolded.append(line)
        manifest = {}
        for line in unfolded:
            if not line:
                break  # main attributes only; entry sections cannot override the launcher
            require(': ' in line, 'invalid main manifest attribute')
            key, value = line.split(': ', 1)
            require(key.lower() not in {item.lower() for item in manifest}, 'duplicate main manifest attribute')
            manifest[key] = value
        require(manifest.get('Main-Class') == 'org.springframework.boot.loader.launch.JarLauncher'
                and manifest.get('Start-Class') == 'com.chengyinhub.RuoYiApplication'
                and 'Class-Path' not in manifest and 'Loader-Path' not in manifest,
                'fixed real application main required')
        props = dict(line.split('=', 1) for line in archive.read('BOOT-INF/classes/git.properties').decode().splitlines()
                     if '=' in line and not line.startswith('#'))
        require(props.get('git.commit.id.full', props.get('git.commit.id')) == source, 'packaged source mismatch')
        require(not any(CHILD_PATH in entry for entry in entries), 'test child leaked into fat JAR')
        origins = {key: [] for key in PACKAGED_CLASSES}
        for key in PACKAGED_CLASSES:
            entry = 'BOOT-INF/classes/' + key + '.class'
            if entry in entries:
                origins[key].append({'entry': entry, 'classSha256': digest(archive.read(entry))})
        for entry in entries:
            if entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
                library_bytes = archive.read(entry)
                with zipfile.ZipFile(io.BytesIO(library_bytes)) as dependency:
                    names = dependency.namelist()
                    require(len(names) == len(set(names)), 'duplicate dependency entries')
                    require(not any(CHILD_PATH in item for item in names), 'test child in dependency')
                    for key in PACKAGED_CLASSES:
                        if key + '.class' in names:
                            origins[key].append({'entry': entry + '!/' + key + '.class',
                                                 'classSha256': digest(dependency.read(key + '.class')),
                                                 'librarySha256': digest(library_bytes)})
        require(all(len(value) == 1 for value in origins.values()), 'missing or duplicate packaged production class')
        return {'jarSha256': digest(jar.read_bytes()), 'sourceSha': source,
                'mainClass': manifest['Main-Class'], 'startClass': manifest['Start-Class'],
                'manifestSha256': digest(archive.read('META-INF/MANIFEST.MF')),
                'productionClassSha256': {key.replace('/', '.'): value[0]['classSha256'] for key, value in origins.items()},
                'productionClassOwners': {key.replace('/', '.'): value[0] for key, value in origins.items()},
                'testClasspathMountedIntoSubject': False}


def packaged_resource_proof(obj, name, network_id):
    require(obj['Name'] == '/' + name + '-app'
            and re.fullmatch('[0-9a-f]{64}', obj['Id'])
            and obj['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name
            and obj['Config']['Image'] == JDK_IMAGE
            and set(obj['NetworkSettings']['Networks']) == {name}
            and obj['NetworkSettings']['Networks'][name]['NetworkID'] == network_id,
            'foreign packaged application slot')
    host = obj['HostConfig']
    require(host['Memory'] == host['MemorySwap'] == 1073741824
            and host['NanoCpus'] == 1000000000 and host['PidsLimit'] == 256
            and host['ReadonlyRootfs'] is True and not host.get('Privileged')
            and not host.get('PortBindings') and host.get('NetworkMode') == name,
            'packaged application hard budget/isolation differs')
    require(obj['State']['Running'] and not obj['State']['OOMKilled']
            and not obj['State']['Paused'] and obj['State']['Pid'] > 0,
            'packaged application not healthy')
    require(obj['Config']['Entrypoint'] == ['/trace/strace']
            and obj['Config']['Cmd'] == ['-A', '-f', '-s', '0', '-e', 'trace=connect,sendto,sendmsg',
                                         '-o', '/evidence/gameplay-packaged-network.trace'] + PACKAGED_COMMAND,
            'not the real packaged main command')
    env = obj['Config'].get('Env', [])
    require('GITHUB_SHA=' + os.environ['GITHUB_SHA'] in env
            and 'CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY=true' in env
            and not any(item.split('=', 1)[0].startswith(('SPRING_', 'LOADER_'))
                        or item.split('=', 1)[0] in ('CLASSPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')
                        for item in env), 'unreviewed packaged classpath/environment')
    return {'cid': obj['Id'], 'imageId': obj['Image'], 'imageReference': JDK_IMAGE,
            'hostInitPid': obj['State']['Pid'], 'startedAt': obj['State']['StartedAt'],
            'networkId': network_id, 'memoryBytes': host['Memory'], 'memorySwapBytes': host['MemorySwap'],
            'nanoCpus': host['NanoCpus'], 'pidsLimit': host['PidsLimit']}


def packaged_jvm_proof(cmdline, stat, process_cgroup, init_cgroup, cpu, memory, swap, pids):
    require(cmdline == b'\0'.join(item.encode() for item in PACKAGED_COMMAND) + b'\0',
            'actual packaged JVM argv differs')
    close = stat.rfind(b') ')
    fields = stat[close + 2:].split()
    require(close > 0 and len(fields) >= 20 and fields[19].isdigit() and int(fields[19]) > 0,
            'actual JVM start identity unavailable')
    require(process_cgroup == init_cgroup and re.fullmatch(rb'0::[^\r\n]+\n?', process_cgroup),
            'JVM outside exact app cgroup or unsupported hierarchy')
    quota = cpu.strip().split()
    require(len(quota) == 2 and all(item.isdigit() and int(item) > 0 for item in quota)
            and int(quota[0]) == int(quota[1])
            and memory.strip() == b'1073741824' and swap.strip() == b'0' and pids.strip() == b'256',
            'actual packaged cgroup limits differ')
    return {'javaArgvSha256': digest(cmdline), 'javaStartTicks': fields[19].decode(),
            'cgroupSha256': digest(process_cgroup), 'cpuMaxSha256': digest(cpu),
            'actualMemoryMaxBytes': 1073741824, 'actualSwapMaxBytes': 0, 'actualPidsMax': 256}



def packaged_receipt_outcome(response, original):
    require(isinstance(response, dict), 'receipt JSON object required')
    if response.get('code') == 200:
        require(response.get('data') == {key: original[key] for key in
                                       ('commandId', 'expectedChapterId', 'targetChapterId')},
                'receipt returned a different original result')
        return 'SUCCESS'
    if response.get('code') == 401 and not response.get('data'):
        return 'LEGAL_AUTH_REJECTION'
    code = response.get('code')
    return 'SYSTEM_FAILURE' if type(code) is int and 500 <= code <= 599 and code != 520 else 'UNKNOWN'


def packaged_binding(report, boot, app, network_id):
    binding = capacity_profile_binding(report, boot, app, network_id)
    binding['subjectProfile'] = PACKAGED_SUBJECT
    binding['packagedSubject'] = json.loads(json.dumps(report['subjects'][-1]))
    require(binding['packagedSubject']['cid'] == app['Id']
            and binding['packagedSubject']['javaPid'] == boot['javaPid']
            and binding['packagedSubject']['jarSha256'] == report['jarSha256'],
            'packaged mixed observer binding differs')
    return binding


def run_packaged(name, schema, out, credentials):
    """Fixed new scope. No three-boundary replay, alternate resource owner, or provider fiction."""
    packaged_gate(os.environ)
    require(re.fullmatch(r'cy-auth-[0-9a-f]{32}', name) and schema == 'cy_e2e_auth_' + name[8:],
            'wrong packaged namespace')
    require(credentials.name == 'credentials.json' and credentials.is_file()
            and not credentials.is_symlink() and credentials.resolve() == credentials.absolute()
            and credentials.stat().st_mode & 0o777 == 0o600, 'original private credentials required')
    require(out.is_dir() and not out.is_symlink() and out.resolve() == out.absolute(), 'original evidence owner required')
    saved = json.loads(credentials.read_text())
    require(saved['schema'] == schema, 'foreign private schema')
    owned.SECRET_VALUES.extend(saved['redactions'])
    require(command(DOCKER + ['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).decode().strip()
            == 'unix:///var/run/docker.sock', 'nonlocal Docker endpoint')
    endpoint = owned.mysql_endpoint(name)
    stores = store_identity(name)
    mysql, redis = inspect(name + '-mysql'), inspect(name + '-redis')
    network = json.loads(command(DOCKER + ['network', 'inspect', name]))[0]
    require(network['Internal'] and network['Name'] == name
            and network.get('Labels', {}).get('chengyin.auth-recovery') == name
            and stores['mysql']['networkId'] == stores['redis']['networkId'] == network['Id'], 'foreign stores/network')
    absent = subprocess.run(DOCKER + ['container', 'inspect', name + '-app'], capture_output=True, timeout=10)
    owned.require_absent('container', name + '-app', absent.returncode, absent.stderr)
    jar = ROOT / 'chengyinhub-admin/target/chengyinhub-admin.jar'
    artifact = packaged_jar_proof(jar, os.environ['GITHUB_SHA'])
    jar_stamp = (jar.stat().st_size, jar.stat().st_mtime_ns, jar.stat().st_ino)
    require(json.loads((out / 'schema-summary.json').read_text())['nativeJarSha256'] == artifact['jarSha256'],
            'canonical schema and JAR differ')
    tests = ROOT / 'chengyinhub-admin/target/test-classes'
    main_class = tests / (CHILD_PATH + '.class')
    require(main_class.is_file() and not main_class.is_symlink(), 'same-build seed child required')
    child_classes = out / 'gameplay-child-classes'
    child_classes.mkdir(mode=0o700)
    compiled = sorted(main_class.parent.glob('GameplayProcessRecoveryChild*.class'))
    require(main_class in compiled, 'seed child missing')
    for source in compiled:
        require(source.is_file() and not source.is_symlink()
                and (source.name == 'GameplayProcessRecoveryChild.class'
                     or source.name.startswith('GameplayProcessRecoveryChild$')), 'unexpected seed class')
        target = child_classes / source.relative_to(tests)
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        shutil.copyfile(source, target); target.chmod(0o600)
        require(digest(source.read_bytes()) == digest(target.read_bytes()), 'seed class copy drift')
    private = credentials.parent / 'gameplay'
    private.mkdir(mode=0o700)
    deadline = time.monotonic() + 1200
    app = None
    first_failure = None
    report = {'result': 'NOT_COMPLETED', **artifact, 'sourceTree': command(['git', '-C', str(ROOT), 'rev-parse', 'HEAD^{tree}']).decode().strip(),
              'runId': os.environ['GITHUB_RUN_ID'], 'runAttempt': os.environ['GITHUB_RUN_ATTEMPT'],
              'schema': schema, 'serverUuid': saved['serverUuid'], 'storeProcessIdentity': stores,
              'fixturePropertiesSha256': digest((ROOT / 'ci/upgrade-startup/application.properties').read_bytes()),
              'childSourceSha256': digest((ROOT / ('chengyinhub-admin/src/test/java/' + CHILD_PATH + '.java')).read_bytes()),
              'childClassSha256': digest(main_class.read_bytes()), 'testClassTreeSha256': digest({
                  str(p.relative_to(child_classes)): digest(p.read_bytes()) for p in sorted(child_classes.rglob('*.class'))}),
              'subjects': [], 'retiredSlots': [], 'cleanup': 'PENDING_ORIGINAL_OUTER_OWNER',
              'fullPackagedAppBudgetPlacement': 'NOT_YET_PROVED', 'wholeSystemCapacityAccepted': False, 'q4q8Complete': False,
              'classLoadingEvidence': 'UNKNOWN_NO_RUNTIME_CLASS_LOAD_EVENTS',
              'notCovered': ['allGameplayFamilies', 'routeCacheEvictionAndLongProvider', 'productionProviders',
                             'deviceBehavior', 'maximumSystemCapacity', 'cashMovement', 'allGameplayRuntimeLeaseEpochTakeovers'],
              'scope': 'Packaged main chapter HTTP/SQL capacity, original F dependency faults, sequential original-identity takeover',
              'driverPlacement': 'Existing hosted Python/Docker-exec workload driver outside the server app cgroup; not included in server budget',
              'hostDriverPid': os.getpid(), 'hostDriverCgroupSha256': digest(Path('/proc/self/cgroup').read_bytes()),
              'requestManifest': {'curveHttp': 144, 'faultReceiptHttp': 2, 'totalBusinessHttp': 146, 'newIntents': 72,
                                  'concurrency': [1, 3, 6], 'itemsPerCurve': 24, 'stageSeconds': 45},
              'packagedJvmHeapMiB': 512,
              'otherContracts': 'Independent packaged app scope; old combined Maven128/Surefire384/child64 and HTTP240 unchanged'}
    secret = secrets.token_urlsafe(48)
    owned.SECRET_VALUES.append(secret)
    private_json(credentials, {**saved, 'redactions': owned.SECRET_VALUES})
    config = {'sourceSha': report['sourceSha'], 'jarSha256': report['jarSha256'],
              'childClassSha256': report['childClassSha256'], 'schema': schema, 'serverUuid': saved['serverUuid'],
              'jdbcUrl': 'jdbc:mysql://' + endpoint['ip'] + ':3306/' + schema
                         + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
              'redisHost': redis['NetworkSettings']['Networks'][name]['IPAddress'], 'tokenSecret': secret,
              'capacityItems': 72, 'packagedSeedOnly': True}
    private_json(private / 'config.json', config)
    report['configSha256'] = digest((private / 'config.json').read_bytes())
    # Fixed synthetic local config, never inherit host settings/credentials or provider access.
    props = ('spring.datasource.druid.master.url=' + config['jdbcUrl'] + '\n'
             'spring.data.redis.host=' + config['redisHost'] + '\n'
             'token.secret=' + secret + '\ncoupon.qr-secret=' + secret + '\n')
    property_file = private / 'packaged.properties'
    fd = os.open(property_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
        stream.write(props)
    report['packagedPropertiesSha256'] = digest(property_file.read_bytes())
    report_path = 'gameplay-packaged-capacity.json'
    module = load('gameplay_packaged_f_capacity', 'ci/capacity_mixed_probe.py')
    source_identity = module.source_identity()
    require(source_identity['sourceSha'] == report['sourceSha'] and source_identity['sourceTree'] == report['sourceTree'],
            'clean same-source packaged input required')

    def save():
        owned.write_report(out, report_path, report)

    def budget(*, allow_redis_pause=False):
        require(time.monotonic() < deadline, 'fixed packaged 1200-second budget exhausted')
        assert_store_identity(stores, store_identity(name, allow_redis_pause=allow_redis_pause))
        require(jar.is_file() and not jar.is_symlink() and jar.stat().st_size == jar_stamp[0]
                and jar.stat().st_mtime_ns == jar_stamp[1] and jar.stat().st_ino == jar_stamp[2], 'packaged JAR changed')
        require(digest(property_file.read_bytes()) == report['packagedPropertiesSha256'], 'packaged config changed')

    def owner(cid):
        row = inspect(cid)
        require(row['Id'] == cid and row['Name'] == '/' + name + '-app'
                and row['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name
                and row['Config']['Image'] == JDK_IMAGE
                and set(row['NetworkSettings']['Networks']) == {name}
                and row['NetworkSettings']['Networks'][name]['NetworkID'] == network['Id'], 'app owner changed')
        return row

    def retire(remove, expected_exit=None):
        nonlocal app
        require(app is not None, 'no original app to retire')
        row = owner(app)
        if expected_exit is None:
            require(row['State']['Running'], 'normal retirement requires the original live subject')
        if row['State']['Running']:
            # PID 1 is the tracer. Signal the independently identified JVM, then
            # require its original graceful container exit rather than accepting a forced stop.
            process = subject()
            require(process['cid'] == app and process['hostInitPid'] == row['State']['Pid'],
                    'retirement process no longer belongs to the original app')
            report.setdefault('processStopIntents', []).append({
                'cid': app, 'javaPid': process['javaPid'], 'hostJavaPid': process['hostJavaPid'],
                'javaStartTicks': process['javaStartTicks'], 'signal': 'TERM',
                'javaArgvSha256': process['javaArgvSha256']})
            save()
            command(DOCKER + ['exec', app, 'sh', '-c', 'kill -TERM "$1"',
                              'packaged-stop', str(process['javaPid'])], timeout=10)
        code = int(command(DOCKER + ['wait', app], timeout=20).strip())
        ended = owner(app)
        report.setdefault('processExitReadbacks', []).append({
            'cid': app, 'waitExitCode': code, 'inspectExitCode': ended['State']['ExitCode'],
            'running': ended['State']['Running'], 'oomKilled': ended['State']['OOMKilled']})
        save()
        require(not ended['State']['Running'] and not ended['State']['OOMKilled']
                and code == ended['State']['ExitCode']
                and code in ((0, 143) if expected_exit is None else (expected_exit,)), 'app exit/OOM unaccepted')
        # Previous physical boots must be logged before deletion; final slot stays for outer capture.
        log = private / (app + '.log')
        with log.open('xb') as stream:
            log.chmod(0o600)
            result = subprocess.run(DOCKER + ['logs', app], stdout=stream, stderr=subprocess.STDOUT, timeout=30)
        require(result.returncode == 0 and log.stat().st_size <= 16 * 1024 * 1024, 'bounded private app log unavailable')
        record = {'id': app, 'exitCode': code, 'oomKilled': False, 'logBytes': log.stat().st_size,
                  'logSha256': digest(log.read_bytes()), 'independentNoSuch': False, 'nameAbsent': False}
        if remove:
            command(DOCKER + ['rm', app], timeout=20)
            for identifier in (app, name + '-app'):
                absent = subprocess.run(DOCKER + ['container', 'inspect', identifier], capture_output=True, timeout=10)
                owned.require_absent('container', identifier, absent.returncode, absent.stderr)
            record['independentNoSuch'] = True
            record['nameAbsent'] = True
        report['retiredSlots'].append(record); save()
        if remove:
            app = None

    def launch(seed):
        nonlocal app
        budget()
        require(packaged_jar_proof(jar, report['sourceSha']) == artifact, 'launch JAR changed')
        strace = shutil.which('strace')
        require(strace and (out / 'trace-libs').is_dir(), 'original trace preparation required')
        cmd = ['run', '-d', '--name', name + '-app', '--label', 'chengyin.auth-recovery=' + name,
               '--network', name, '--dns', '127.0.0.1', '--memory=1g', '--memory-swap=1g', '--cpus=1', '--pids-limit=256',
               '--read-only', '--tmpfs', '/tmp:rw,size=256m', '--cap-drop', 'ALL', '--cap-add', 'SYS_PTRACE',
               '--user', str(os.getuid()) + ':' + str(os.getgid()), '-e', 'LD_LIBRARY_PATH=/trace-libs',
               '-e', 'GITHUB_SHA=' + report['sourceSha'], '-e', 'CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY=true',
               '-e', 'CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY=true',
               '-v', strace + ':/trace/strace:ro', '-v', str(out / 'trace-libs') + ':/trace-libs:ro',
               '-v', str(jar) + ':/application.jar:ro', '-v', str(ROOT / 'ci/upgrade-startup') + ':/fixture:ro',
               '-v', str(out) + ':/evidence', '-v', str(private) + ':/recovery-private' + ('' if seed else ':ro')]
        if seed:
            cmd += ['-v', str(child_classes) + ':/recovery-testclasses:ro']
        cmd += ['--entrypoint', '/trace/strace', JDK_IMAGE, '-A', '-f', '-s', '0', '-e',
                'trace=connect,sendto,sendmsg', '-o', '/evidence/gameplay-packaged-network.trace']
        cmd += (['java', '-Xmx512m', '-Dlogging.config=/fixture/logback.xml',
                 '-Dlogback.configurationFile=/fixture/logback.xml', '-Dloader.path=/recovery-testclasses',
                 '-Dloader.main=' + CHILD, '-cp', '/application.jar',
                 'org.springframework.boot.loader.launch.PropertiesLauncher'] if seed else PACKAGED_COMMAND)
        created = command(DOCKER + cmd, timeout=120).decode().strip()
        require(re.fullmatch('[0-9a-f]{64}', created), 'created app CID missing')
        app = created
        row = owner(app)
        owned.validate_targets(name, schema, 'unix:///var/run/docker.sock', [row, mysql, redis], network)
        require(row['Id'] == inspect(name + '-app')['Id'], 'actual created app differs')
        if seed:
            return
        packaged_resource_proof(row, name, network['Id'])
        require(json.loads(command(DOCKER + ['image', 'inspect', JDK_IMAGE]))[0]['Id'] == row['Image'],
                'pinned image object differs')
        mounts = {item['Destination']: item for item in row['Mounts']}
        expected = {'/application.jar': (jar, False), '/fixture': (ROOT / 'ci/upgrade-startup', False),
                    '/evidence': (out, True), '/recovery-private': (private, False),
                    '/trace/strace': (Path(strace), False), '/trace-libs': (out / 'trace-libs', False)}
        require(set(mounts) == set(expected), 'unexpected packaged subject mount')
        for target, (source, writable) in expected.items():
            require(mounts[target]['Type'] == 'bind' and mounts[target]['Source'] == str(source)
                    and mounts[target]['RW'] is writable, 'packaged mount differs')

    def http(path, token=None, fields=None, *, allow_redis_pause=False):
        budget(allow_redis_pause=allow_redis_pause)
        require(path == COMMAND or path.startswith(RECEIPT + '?'), 'non-business packaged path')
        import urllib.parse
        text = 'url = "http://127.0.0.1:8080' + path + '"\n'
        if token:
            text += 'header = "Authorization: Bearer ' + token + '"\n'
        if fields is not None:
            require(re.fullmatch('[A-Za-z0-9:_-]{1,80}', fields['commandId']), 'original request identity invalid')
            text += 'header = "X-Request-Id: ' + fields['commandId'] + '"\n'
            text += 'header = "Content-Type: application/x-www-form-urlencoded"\n'
            text += 'data = ' + json.dumps(urllib.parse.urlencode(fields)) + '\n'
        require(owner(app)['State']['Running'], 'actual subject exited')
        return json.loads(command(DOCKER + ['exec', '-i', app, 'curl', '--silent', '--show-error', '--noproxy', '*',
                                          '--connect-timeout', '5', '--max-time', '95', '--config', '-'],
                                  text.encode(), timeout=100))

    def subject():
        budget()
        row = owner(app)
        proof = packaged_resource_proof(row, name, network['Id'])
        # Exactly one actual Java main, not a tracer PID or a separate witness JVM.
        pid = command(DOCKER + ['exec', app, 'sh', '-c',
            'for p in /proc/[0-9]*/exe; do case "$(readlink "$p" 2>/dev/null)" in */java) '
            'n=${p#/proc/}; printf "%s\\n" "${n%/exe}";; esac; done']).decode().strip()
        require(re.fullmatch('[1-9][0-9]*', pid), 'one packaged Java subject required')
        def read(path):
            return command(DOCKER + ['exec', app, 'cat', path], timeout=10)
        proof.update(packaged_jvm_proof(read('/proc/' + pid + '/cmdline'), read('/proc/' + pid + '/stat'),
            read('/proc/' + pid + '/cgroup'), read('/proc/1/cgroup'), read('/sys/fs/cgroup/cpu.max'),
            read('/sys/fs/cgroup/memory.max'), read('/sys/fs/cgroup/memory.swap.max'), read('/sys/fs/cgroup/pids.max')))
        require(command(DOCKER + ['exec', app, 'sha256sum', '/application.jar']).decode().strip()
                == report['jarSha256'] + '  /application.jar', 'mounted packaged JAR differs')
        require(command(DOCKER + ['exec', app, 'readlink', '/proc/' + pid + '/ns/net']).strip()
                == command(DOCKER + ['exec', app, 'readlink', '/proc/1/ns/net']).strip(), 'JVM outside app network namespace')
        proof.update(javaPid=int(pid), jarSha256=report['jarSha256'], sourceSha=report['sourceSha'],
                     javaMain='com.chengyinhub.RuoYiApplication', productionClassSha256=artifact['productionClassSha256'],
                     testClasspathMountedIntoSubject=False)
        current_network = json.loads(command(DOCKER + ['network', 'inspect', name]))[0]
        require(current_network['Id'] == network['Id'] and current_network['Internal'] is True
                and set(current_network['Containers']) == {app, stores['mysql']['id'], stores['redis']['id']},
                'foreign network attachment')
        # Docker top gives the host PID; NSpid/start ticks bind it to this exact namespace JVM.
        processes = command(DOCKER + ['top', app, '-eo', 'pid,comm']).decode().splitlines()[1:]
        host_java = [line.split()[0] for line in processes if len(line.split()) == 2 and line.split()[1] == 'java']
        require(len(host_java) == 1 and re.fullmatch('[1-9][0-9]*', host_java[0]), 'one actual host Java PID required')
        status = Path('/proc/' + host_java[0] + '/status').read_text()
        namespace = re.search(r'^NSpid:\s+([0-9\t ]+)$', status, re.MULTILINE)
        require(namespace is not None and namespace.group(1).split()[0] == host_java[0]
                and namespace.group(1).split()[-1] == pid, 'host/namespace Java identity differs')
        host_group = Path('/proc/' + host_java[0] + '/cgroup').read_bytes()
        require(host_group == Path('/proc/' + str(row['State']['Pid']) + '/cgroup').read_bytes()
                and app.encode() in host_group, 'host Java/init not in exact Docker app cgroup')
        host_stat = Path('/proc/' + host_java[0] + '/stat').read_bytes()
        require(host_stat[host_stat.rfind(b') ') + 2:].split()[19].decode() == proof['javaStartTicks'],
                'host JVM PID reused')
        threads = command(DOCKER + ['exec', app, 'sh', '-c',
            'for t in /proc/' + pid + '/task/[0-9]*/cgroup; do cat "$t"; done']).splitlines()
        group = read('/proc/' + pid + '/cgroup').strip()
        require(1 <= len(threads) <= 256 and all(item == group for item in threads),
                'application worker outside subject cgroup')
        proof.update(hostJavaPid=int(host_java[0]), workerThreadsObserved=len(threads),
                     allObservedJvmThreadsInSameCgroup=True)
        return proof

    try:
        save()
        launch(True)
        # The seed child uses only the original production AppLoginService and canonical mappers.
        code = int(command(DOCKER + ['wait', app], timeout=min(150, max(1, int(deadline - time.monotonic())))).strip())
        require(code == 0 and not owner(app)['State']['OOMKilled'], 'original seed child failed')
        seed_ready = json.loads((private / 'ready.json').read_text())
        require(seed_ready['sourceSha'] == report['sourceSha'] and seed_ready['jarSha256'] == report['jarSha256']
                and seed_ready['childClassSha256'] == report['childClassSha256']
                and seed_ready['configSha256'] == report['configSha256'], 'seed provenance changed')
        identities_file = private / 'identities.json'
        require(identities_file.is_file() and not identities_file.is_symlink()
                and identities_file.stat().st_mode & 0o777 == 0o600, 'original seed identities missing')
        identities = json.loads(identities_file.read_text()); capacity_plan(identities)
        report['identitiesSha256'] = digest(identities_file.read_bytes())
        report['initialBootId'] = seed_ready['bootId']
        report['originalActors'] = {key: {'memberId': identities[key]['memberId'],
                                         'tokenSha256': digest(identities[key]['token'].encode())} for key in ('a', 'b')}
        owned.SECRET_VALUES.extend(identities[key]['token'] for key in ('a', 'b'))
        private_json(credentials, {**saved, 'redactions': owned.SECRET_VALUES})
        retire(True, expected_exit=0)
        with owned.mysql_relay(name, {'endpoint': endpoint}) as port, owned.database(port, schema) as db:
            def rows(sql, params=None):
                with db.cursor() as cursor:
                    cursor.execute('SELECT @@server_uuid,DATABASE()')
                    require(cursor.fetchone() == (saved['serverUuid'], schema), 'independent packaged SQL owner differs')
                    cursor.execute(sql, params)
                    keys = [item[0] for item in cursor.description]
                    values = cursor.fetchmany(4097)
                    require(len(values) <= 4096, 'bounded packaged facts exceeded')
                    return [dict(zip(keys, row)) for row in values]

            members = rows('SELECT * FROM ums_member ORDER BY id')
            columns = list(members[0]) if members else []
            authority = {table: {'rows': len(value), 'sha256': module.digest([tuple(row.values()) for row in value]),
                                'ddlSha256': module.digest([tuple(row.values()) for row in rows('SHOW CREATE TABLE `' + table + '`')])}
                         for table in module.AUTHORITY_TABLES
                         for value in [rows('SELECT * FROM `' + table + '` ORDER BY 1')]}
            authority['accountCredentials'] = module.digest([tuple(row.values()) for row in rows(
                'SELECT user_id,user_name,password,status,del_flag FROM sys_user ORDER BY user_id')])
            report['gameplayActorProof'] = module.gameplay_actor_proof(identities, columns,
                [tuple(row[column] for column in columns) for row in members],
                schema, saved['serverUuid'], report['initialBootId'], report['identitiesSha256'], authority)

            def boot_main():
                launch(False)
                ready_deadline = min(deadline, time.monotonic() + 150)
                while time.monotonic() < ready_deadline:
                    require(owner(app)['State']['Running'], 'packaged main exited during startup')
                    # Only a fixed original startup marker is used to wait, never to accept business.
                    log = command(DOCKER + ['logs', '--tail', '200', app], timeout=10)
                    if '城瘾API启动成功'.encode() in log:
                        value = subject()
                        report['subjects'].append(value); save()
                        # A host observation key, explicitly not a fixture HTTP boot attestation.
                        boot_id = app + ':' + value['javaStartTicks']
                        boot = {key: report[key] for key in ('sourceSha', 'jarSha256', 'childClassSha256', 'configSha256')}
                        boot.update(bootId=boot_id, javaPid=value['javaPid'], port=8080)
                        report['currentBootObservation'] = boot
                        report['bootIdSource'] = 'EXACT_CID_AND_JVM_START_TICKS_HOST_OBSERVATION'
                        return boot
                    time.sleep(1)
                raise RuntimeError('bounded original packaged startup failed')

            boot = boot_main()
            report['fullPackagedAppBudgetPlacement'] = 'PROVED_FOR_FIXED_MAIN_AND_TASK_CGROUP'
            def stop_curve():
                current = owner(app)
                command(DOCKER + ['kill', '--signal', 'KILL', app])
                exit_code = int(command(DOCKER + ['wait', app], timeout=20).strip())
                require_physical_exit(app, current, owner(app), exit_code)
                report['curveDeadline'] = {'exitCode': exit_code, 'originalIntents': 'UNKNOWN_UNLESS_INDEPENDENT_SQL_PROVES_APPLIED'}
                save()
                return report['curveDeadline']

            def takeover(concurrency):
                nonlocal boot
                if concurrency != 6:
                    return None
                before = {table: rows('SELECT * FROM `' + table + '` ORDER BY id')
                          for table in ('club_lead_state', 'game_command_receipt', 'business_audit_event')}
                previous = report['subjects'][-1]
                retire(True)
                boot = boot_main()
                current = report['subjects'][-1]
                require(current['cid'] != previous['cid']
                        and (current['cid'], current['javaStartTicks']) != (previous['cid'], previous['javaStartTicks']),
                        'not an independent sequential application instance')
                after = {table: rows('SELECT * FROM `' + table + '` ORDER BY id') for table in before}
                require(after == before, 'boot/takeover changed original gameplay facts')
                value = {'oldCid': previous['cid'], 'newCid': current['cid'],
                         'originalFactsSha256': digest(before), 'identitiesSha256': report['identitiesSha256'],
                         'status': 'FACTS_UNCHANGED_AWAIT_ORIGINAL_HTTP_REPLAY',
                         'oldExitedAndAbsentBeforeNewLaunch': True}
                report['instanceTakeover'] = value; save()
                return value

            report['financialBefore'] = {table: {'rows': len(value), 'sha256': digest(value)}
                for table in owned.FINANCIAL_TABLES for value in [rows('SELECT * FROM `' + table + '` ORDER BY id')]}
            report['coreBusinessCurve'] = {}
            observe_core_workload(identities, http, rows, module.run_curve, stop_curve,
                                  report['coreBusinessCurve'], before_readback=takeover)
            require(report['instanceTakeover']['status'] == 'FACTS_UNCHANGED_AWAIT_ORIGINAL_HTTP_REPLAY'
                    and report['coreBusinessCurve']['status'] == 'BUSINESS_ASSERTIONS_PASSED',
                    'original takeover result not independently verified')
            report['instanceTakeover']['status'] = 'ORIGINAL_24_IDENTITY_READ_REPLAY_FOREIGN_AND_ALL_SQL_FACTS_VERIFIED'
            require(rows('SELECT COUNT(*) AS n FROM refund_application')[0]['n'] == 0, 'F original refund seed is not empty')
            # F must explicitly validate PACKAGED_RUOYI_MAIN; never fall back to its child/default profile.
            binding = packaged_binding(report, boot, owner(app), network['Id'])
            report['businessFaultReadbacks'] = []
            def business_probe(stage):
                expected_stage = ('FAULT_HELD', 'RECOVERED')
                index = len(report['businessFaultReadbacks'])
                require(index < 2 and stage == expected_stage[index], 'duplicate/out-of-order fixed business fault probe')
                current_redis = inspect(stores['redis']['id'])
                require(current_redis['Id'] == stores['redis']['id']
                        and current_redis['State']['Paused'] is (stage == 'FAULT_HELD'), 'actual dependency fault stage differs')
                waits = rows("SELECT COUNT(*) AS n FROM information_schema.processlist WHERE USER='startup_probe' "
                    "AND DB=%s AND STATE IN ('Waiting for table metadata lock','Waiting for table level lock') "
                    "AND LOCATE('sys_dict_data',COALESCE(INFO,''))>0", (schema,))[0]['n']
                require(waits == (3 if stage == 'FAULT_HELD' else 0), 'actual three SQL waits/recovery not proved')
                original = identities['CAPACITY_48']
                actor = identities['a']
                def original_facts():
                    value = {'state': rows('SELECT * FROM club_lead_state WHERE activity_id=%s', (original['activityId'],)),
                             'receipt': rows('SELECT * FROM game_command_receipt WHERE activity_id=%s AND actor_member_id=%s AND request_id=%s',
                                 (original['activityId'], actor['memberId'], 'lead:' + digest(original['commandId'].encode())[:48])),
                             'audit': rows('SELECT * FROM business_audit_event ORDER BY id')}
                    require_receipt(value['receipt'], original, actor['memberId'], 1)
                    require(len(value['state']) == 1
                            and value['state'][0]['current_chapter_id'] == original['targetChapterId'], 'original chapter fact changed')
                    return value
                before = original_facts()
                record = {'stage': stage, 'category': 'UNKNOWN', 'elapsedSeconds': 0,
                          'originalIntentSha256': digest(original), 'beforeFactsSha256': digest(before),
                          'afterFactsSha256': None, 'resultSha256': None}
                # Exactly one GET per fixed stage; no new economic/gameplay intent or retry.
                report['businessFaultReadbacks'].append(record); save()
                tick = time.monotonic()
                import urllib.parse
                budget(allow_redis_pause=stage == 'FAULT_HELD')
                text = ('url = "http://127.0.0.1:8080' + RECEIPT + '?' + urllib.parse.urlencode({
                    key: original[key] for key in ('activityId', 'commandId')}) + '"\n'
                    + 'header = "Authorization: Bearer ' + actor['token'] + '"\n')
                try:
                    transport = subprocess.run(DOCKER + ['exec', '-i', app, 'curl', '--noproxy', '*',
                        '--silent', '--show-error', '--max-time', '35', '--max-filesize', str(1024 * 1024),
                        '--write-out', '\n%{http_code}', '--config', '-'],
                        input=text.encode(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=40)
                    if transport.returncode:
                        record['category'] = 'TRANSPORT_TIMEOUT' if transport.returncode == 28 else 'TRANSPORT_ERROR'
                    else:
                        require(len(transport.stdout) <= 1024 * 1024 + 16, 'bounded business receipt exceeded')
                        raw, status = transport.stdout.rsplit(b'\n', 1)
                        require(re.fullmatch(rb'[0-9]{3}', status), 'business HTTP status not observed')
                        result = json.loads(raw)
                        category = module.classify('BUSINESS_RECEIPT', int(status), result)
                        if category == 'SUCCESS':
                            require(packaged_receipt_outcome(result, original) == 'SUCCESS',
                                    'original result contract differs')
                            expected_result = json.loads(before['receipt'][0]['result_json'])
                            require(digest(result) == digest(expected_result), 'response differs from original durable receipt')
                            record['resultSha256'] = digest(expected_result)
                        record['category'] = category
                except subprocess.TimeoutExpired:
                    record['category'] = 'TRANSPORT_TIMEOUT'
                finally:
                    record['elapsedSeconds'] = time.monotonic() - tick
                    save()
                after = original_facts()
                record['afterFactsSha256'] = digest(after)
                require(after == before, 'read-only fault probe changed original facts')
                require(0 <= record['elapsedSeconds'] <= 41, 'fixed fault business read bound exceeded')
                require(record['category'] not in ('UNKNOWN', 'CONTRACT_MISMATCH', 'NOT_SENT'),
                        'fault business read remains unverified')
                save()
                if stage == 'RECOVERED':
                    require(record['category'] == 'SUCCESS' and record['resultSha256'] is not None,
                            'original result did not recover after real dependency release')
                return dict(record)
            module.observe(name, schema, out, credentials, port, gameplay_binding=binding,
                           packaged_subject=True, business_probe=business_probe)
            require([row['stage'] for row in report['businessFaultReadbacks']] == ['FAULT_HELD', 'RECOVERED'],
                    'F omitted the actual business fault/recovery reads')
            report['actualBusinessHttpInvocations'] = 146
            final_subject = subject()
            require({k: v for k, v in final_subject.items() if k != 'workerThreadsObserved'}
                    == {k: v for k, v in report['subjects'][-1].items() if k != 'workerThreadsObserved'},
                    'subject changed inside fixed mixed fault/recovery window')
            report['finalSubjectProof'] = final_subject
            report['financialAfter'] = {table: {'rows': len(value), 'sha256': digest(value)}
                for table in owned.FINANCIAL_TABLES for value in [rows('SELECT * FROM `' + table + '` ORDER BY id')]}
            require(report['financialAfter'] == report['financialBefore'], 'packaged capacity changed economic facts')
            report['fObserveInvoked'] = True
            require(report['coreBusinessCurve']['realHttpRequests'] == 144, 'fixed new business curve denominator changed')
        require(packaged_jar_proof(jar, report['sourceSha']) == artifact, 'final packaged JAR changed')
        retire(False)
        verifier = load('gameplay_packaged_network_verifier', 'ci/upgrade_startup_network.py')
        report['observedNetworkCalls'] = verifier.verify((out / 'gameplay-packaged-network.trace').read_text(),
                                                         endpoint['ip'], config['redisHost'])
        report['result'] = 'PACKAGED_BUSINESS_ASSERTIONS_PASSED_CLEANUP_PENDING'
    except BaseException as failure:
        first_failure = failure
        report['result'] = 'FAIL_OR_UNKNOWN_NO_RETRY'
        report.setdefault('firstFailure', {'type': type(failure).__name__, 'reasonCode': 'PACKAGED_SCOPE_NOT_ACCEPTED'})
        raise
    finally:
        try:
            # Fence only the exact created app; keep the last slot for the original owner's logs/removal.
            # Errors stay errors. An OOM/nonzero boot is never accepted by retire() or finalization.
            if app is None:
                discovered = subprocess.run(DOCKER + ['container', 'inspect', name + '-app'], capture_output=True, timeout=10)
                if discovered.returncode == 0:
                    app = json.loads(discovered.stdout)[0]['Id']; owner(app)
                else:
                    owned.require_absent('container', name + '-app', discovered.returncode, discovered.stderr)
            if app is not None and owner(app)['State']['Running']:
                command(DOCKER + ['stop', '--time', '10', app], timeout=25)
            identity_path = private / 'identities.json'
            if identity_path.exists():
                require(identity_path.is_file() and not identity_path.is_symlink()
                        and identity_path.stat().st_mode & 0o777 == 0o600, 'private seed identities changed')
                frozen = json.loads(identity_path.read_text())
                owned.SECRET_VALUES.extend(frozen[label]['token'] for label in ('a', 'b'))
                current_credentials = json.loads(credentials.read_text())
                redactions = list(dict.fromkeys(current_credentials['redactions'] + owned.SECRET_VALUES))
                private_json(credentials, {**current_credentials, 'redactions': redactions})
            for path in private.iterdir():
                require(path.is_file() and not path.is_symlink()
                        and (path.name in ('config.json', 'ready.json', 'identities.json', 'packaged.properties')
                             or re.fullmatch(r'[0-9a-f]{64}\.log', path.name)), 'unknown private packaged leftover')
                path.unlink()
            private.rmdir()

        except BaseException as cleanup_failure:
            report.setdefault('cleanupFailures', []).append({'type': type(cleanup_failure).__name__})
            report['result'] = 'FAIL_OR_UNKNOWN_NO_RETRY'
            if first_failure is None:
                report.setdefault('firstFailure', {'type': type(cleanup_failure).__name__, 'reasonCode': 'PACKAGED_INNER_CLEANUP_FAILED'})
                raise
        finally:
            save()



def validate_packaged_business(report):
    require(set(report['productionClassSha256']) == {key.replace('/', '.') for key in PACKAGED_CLASSES},
            'fixed packaged production class evidence incomplete')
    require(report['wholeSystemCapacityAccepted'] is False and report['q4q8Complete'] is False,
            'packaged scope cannot promote whole capacity')
    require(report['actualBusinessHttpInvocations'] == 146
            and [row['stage'] for row in report['businessFaultReadbacks']] == ['FAULT_HELD', 'RECOVERED']
            and all(row['beforeFactsSha256'] == row['afterFactsSha256']
                    and re.fullmatch('[0-9a-f]{64}', row['beforeFactsSha256'])
                    and re.fullmatch('[0-9a-f]{64}', row['originalIntentSha256'])
                    and row['category'] not in ('UNKNOWN', 'CONTRACT_MISMATCH', 'NOT_SENT')
                    and 0 <= row['elapsedSeconds'] <= 41 for row in report['businessFaultReadbacks'])
            and report['businessFaultReadbacks'][1]['category'] == 'SUCCESS'
            and re.fullmatch('[0-9a-f]{64}', report['businessFaultReadbacks'][1]['resultSha256'] or ''),
            'two physical business fault/readback observations incomplete')
    core = report['coreBusinessCurve']
    require(core['status'] == 'BUSINESS_ASSERTIONS_PASSED' and core['realHttpRequests'] == 144
            and core['newIntents'] == 72 and len(core['waves']) == 3, 'fixed packaged denominator incomplete')
    for index, (wave, concurrency) in enumerate(zip(core['waves'], (1, 3, 6))):
        require(wave['concurrency'] == concurrency and wave['newCommands'] == wave['readReplayForeign'] == 24
                and wave['status'] == 'ASSERTIONS_PASSED', 'packaged wave incomplete')
        expected = {'CAPACITY_%02d' % i for i in range(index * 24, (index + 1) * 24)}
        for field, kinds, counts in (
            ('writeCurve', {'command'}, {'SUCCESS': 24}),
            ('readbackCurve', {'replay', 'receipt', 'foreign'}, {'SUCCESS': 16, 'LEGAL_AUTH_REJECTION': 8}),
        ):
            curve = wave[field]
            require(curve['configuredConcurrency'] == concurrency and len(curve['records']) == 24
                    and curve['requestsRecorded'] == 24
                    and {row['fixtureKey'] for row in curve['records']} == expected
                    and {row['kind'] for row in curve['records']} == kinds
                    and all(curve['counts'].get(key) == value for key, value in counts.items())
                    and sum(curve['counts'].values()) == 24, 'packaged curve categories/identities incomplete')
        if index < 2:
            require(wave['subjectTransition'] is None, 'unexpected instance transition')
        else:
            require(wave['subjectTransition'] == report['instanceTakeover'], 'takeover original wave differs')
    require(report['financialBefore'] == report['financialAfter']
            and set(report['financialBefore']) == set(owned.FINANCIAL_TABLES),
            'original economic zero-effect proof incomplete')
    for subject in report['subjects']:
        require(subject['sourceSha'] == report['sourceSha'] and subject['jarSha256'] == report['jarSha256']
                and subject['productionClassSha256'] == report['productionClassSha256']
                and subject['testClasspathMountedIntoSubject'] is False
                and subject['allObservedJvmThreadsInSameCgroup'] is True
                and (subject['memoryBytes'], subject['memorySwapBytes'], subject['nanoCpus'], subject['pidsLimit'])
                    == (1073741824, 1073741824, 1000000000, 256)
                and (subject['actualMemoryMaxBytes'], subject['actualSwapMaxBytes'], subject['actualPidsMax'])
                    == (1073741824, 0, 256), 'packaged runtime source/budget differs')


def finalize_packaged(name, schema, out, original_exit):
    packaged_gate(os.environ)
    path = out / 'gameplay-packaged-capacity.json'
    report = json.loads(path.read_text())
    report.setdefault('businessResultBeforeCleanup', report['result'])
    try:
        require(original_exit == 0 and not report.get('cleanupFailures')
                and report['result'] == 'PACKAGED_BUSINESS_ASSERTIONS_PASSED_CLEANUP_PENDING',
                'original packaged business/entry failed')
        require(report['sourceSha'] == os.environ['GITHUB_SHA']
                and report['runId'] == os.environ['GITHUB_RUN_ID']
                and report['runAttempt'] == os.environ['GITHUB_RUN_ATTEMPT']
                and report['schema'] == schema == 'cy_e2e_auth_' + name[8:], 'foreign packaged receipt')
        require(report['wholeSystemCapacityAccepted'] is False and report['q4q8Complete'] is False,
                'scoped evidence cannot promote whole capacity')
        require(len(report['subjects']) == 2 and len({row['cid'] for row in report['subjects']}) == 2
                and report['fullPackagedAppBudgetPlacement'] == 'PROVED_FOR_FIXED_MAIN_AND_TASK_CGROUP'
                and report['instanceTakeover']['status'] == 'ORIGINAL_24_IDENTITY_READ_REPLAY_FOREIGN_AND_ALL_SQL_FACTS_VERIFIED'
                and report['coreBusinessCurve']['status'] == 'BUSINESS_ASSERTIONS_PASSED'
                and report['coreBusinessCurve']['realHttpRequests'] == 144 and report.get('fObserveInvoked') is True,
                'fixed packaged workload incomplete')
        validate_packaged_business(report)
        retired = report['retiredSlots']
        require(len(retired) == 3 and retired[0]['exitCode'] == 0
                and all(row['exitCode'] in (0, 143) and row['oomKilled'] is False for row in retired)
                and all(row['independentNoSuch'] is True and row['nameAbsent'] is True for row in retired[:2])
                and retired[-1]['id'] == report['subjects'][-1]['cid'], 'original slot exits incomplete')
        exits = report.get('processExitReadbacks', [])
        require(len(exits) == 3 and all(
            row['cid'] == old['id'] and row['waitExitCode'] == row['inspectExitCode'] == old['exitCode']
            and row['running'] is False and row['oomKilled'] is False
            for row, old in zip(exits, retired)), 'independent original process exits incomplete')
        stops = report.get('processStopIntents', [])
        require(len(stops) == 2 and all(
            row['cid'] == original['cid'] and row['javaPid'] == original['javaPid']
            and row['hostJavaPid'] == original['hostJavaPid']
            and row['javaStartTicks'] == original['javaStartTicks']
            and row['javaArgvSha256'] == original['javaArgvSha256'] and row['signal'] == 'TERM'
            for row, original in zip(stops, report['subjects'])),
            'original JVM stop targets not proved')
        outer = json.loads((out / 'authenticated-redis-recovery.json').read_text())
        cleanup = outer['resourceCleanup']
        expected = {name + '-app': report['subjects'][-1]['cid'],
                    name + '-mysql': report['storeProcessIdentity']['mysql']['id'],
                    name + '-redis': report['storeProcessIdentity']['redis']['id']}
        require(cleanup['cleanup'] == 'CONFIRMED_EXACT_OWNED_REMOVAL' and outer.get('applicationLogPreserved') is True
                and len(cleanup['ownedRemoved']) == 3
                and {row['name']: row['id'] for row in cleanup['ownedRemoved']} == expected
                and all(row['independentNoSuch'] is True for row in cleanup['ownedRemoved']),
                'original exact cleanup/log capture missing')
        for kind, identifiers in (('container', [*expected, *expected.values(), *[row['id'] for row in retired[:2]]]),
                                  ('network', [name, report['subjects'][0]['networkId']])):
            for identifier in identifiers:
                absent = subprocess.run(DOCKER + [kind, 'inspect', identifier], capture_output=True, timeout=10)
                owned.require_absent(kind, identifier, absent.returncode, absent.stderr)
        module = load('gameplay_packaged_final_f_capacity', 'ci/capacity_mixed_probe.py')
        result = module.finalize(name, schema, out, original_exit)
        require(result is None or type(result) is int and result == 0, 'fixed mixed observation finalization failed')
        report['cleanup'] = 'CONFIRMED_EXACT_OWNED_REMOVAL'
        report['result'] = 'PASS_SCOPED_PACKAGED_MAIN_CAPACITY_AND_TAKEOVER'
    except BaseException as failure:
        report.setdefault('firstFailure', {'type': type(failure).__name__, 'reasonCode': 'PACKAGED_FINALIZATION_NOT_ACCEPTED'})
        report['finalizationFailureType'] = type(failure).__name__
        report['result'] = 'FAIL_OR_UNKNOWN_CLEANUP_OR_SCOPE'
        raise
    finally:
        owned.write_report(out, path.name, report)


if __name__ == '__main__':
    if len(sys.argv) == 2 and sys.argv[1] == '--check-host-only':
        gate(os.environ)
    elif len(sys.argv) == 2 and sys.argv[1] == '--check-packaged-host-only':
        packaged_gate(os.environ)
    elif len(sys.argv) == 6 and sys.argv[1] == 'packaged-run':
        run_packaged(sys.argv[2], sys.argv[3], Path(sys.argv[4]), Path(sys.argv[5]))
    elif len(sys.argv) == 6 and sys.argv[1] == 'packaged-finalize':
        finalize_packaged(sys.argv[2], sys.argv[3], Path(sys.argv[4]), int(sys.argv[5]))
    elif len(sys.argv) == 6 and sys.argv[1] == 'run':
        run(sys.argv[2], sys.argv[3], Path(sys.argv[4]), Path(sys.argv[5]))
    elif len(sys.argv) == 6 and sys.argv[1] == 'finalize':
        finalize(sys.argv[2], sys.argv[3], Path(sys.argv[4]), int(sys.argv[5]))
    else:
        raise SystemExit('Expected fixed host-check/run/finalize or packaged host-check/run/finalize and original owned arguments')
