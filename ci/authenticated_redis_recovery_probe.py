#!/usr/bin/env python3
"""Isolated hosted integration fixture; never a human CAPTCHA or cash-refund acceptance."""
import contextlib
import datetime
import hashlib
import importlib.util
import io
import ipaddress
import json
import os
import re
import select
import socket
import socketserver
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCKER = ['docker', '--context', 'default']
RESTORE_MYSQL_IMAGE = 'mysql:8.0.45@sha256:4af1f8815716546f5b12410f7621f37f93db8dd11a184706ef59111930b8c2ff'
LEASE_COLUMNS = {'payout_lease_owner', 'payout_lease_token', 'payout_lease_expires_at', 'update_time'}
FINANCIAL_TABLES = ('cms_registration', 'ums_member', 'ums_member_balance_detail',
                    'ums_member_points_detail', 'settlement_detail', 'wx_payment_callback_inbox',
                    'reward_drop_log', 'roam_poi_reward_log', 'cms_topic_completion_coupon_reward_log')


def require(ok, reason):
    if not ok:
        raise RuntimeError(reason)


def require_absent(kind, identifier, returncode, stderr):
    require(kind in ('container', 'network'), 'unknown Docker resource category')
    text = stderr.decode(errors='replace') if isinstance(stderr, bytes) else str(stderr)
    target = re.escape(identifier)
    missing = r'Error: No such ' + kind + ': ' + target
    if kind == 'container':
        missing += r'|Error response from daemon: No such container: ' + target
    if kind == 'network':
        missing += r'|Error response from daemon: network ' + target + r' not found'
    require(returncode != 0 and re.fullmatch(missing, text.strip()) is not None,
            'exact ' + kind + ' absence not independently confirmed: ' + redact(text, SECRET_VALUES))


def validate_execution_env(env):
    require(sys.platform.startswith('linux'), 'Linux hosted runner required')
    for key, value in [('GITHUB_ACTIONS', 'true'), ('RUNNER_OS', 'Linux'),
                       ('RUNNER_ENVIRONMENT', 'github-hosted'),
                       ('GITHUB_WORKFLOW', 'Isolated authenticated Redis recovery'),
                       ('GITHUB_JOB', 'isolated-authenticated-redis-recovery'),
                       ('GITHUB_REPOSITORY', 'liboyang42-cpu/hengyin'),
                       ('CHENGYIN_CI_AUTH_REDIS_RECOVERY', 'true')]:
        require(env.get(key) == value, 'wrong hosted scope: ' + key)
    require(env.get('GITHUB_EVENT_NAME') in ('pull_request', 'workflow_dispatch'), 'unsupported event')
    if env.get('CHENGYIN_CI_RESTORE_HTTP_STARTUP') == 'true':
        require(env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'restore startup is manual only')
        require('CHENGYIN_RESTORE_MODE' not in env, 'restore mode belongs to the owned application child')
        require(all(env.get(key, 'false') == 'false' for key in
                    ('CHENGYIN_CI_CAPACITY_MIXED', 'CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY',
                     'CHENGYIN_CI_GAMEPLAY_CAPACITY')), 'restore startup cannot mix workload modes')
    require(not env.get('DOCKER_HOST') and not env.get('DOCKER_CONTEXT'), 'Docker override forbidden')
    require(env.get('GITHUB_WORKFLOW_REF', '').startswith(
        'liboyang42-cpu/hengyin/.github/workflows/isolated-authenticated-redis-recovery.yml@refs/'),
        'wrong workflow file')


def validate_targets(name, schema, endpoint, objects, network):
    require(re.fullmatch(r'cy-auth-[0-9a-f]{32}', name) is not None, 'wrong fixture name')
    require(schema == 'cy_e2e_auth_' + name[8:], 'wrong fixture schema')
    require(endpoint == 'unix:///var/run/docker.sock', 'nonlocal Docker endpoint')
    require(network['Name'] == name and network['Internal'] is True, 'wrong internal network')
    require(network.get('Labels', {}).get('chengyin.auth-recovery') == name, 'foreign network label')
    require(len(objects) == 3, 'three owned objects required')
    for suffix, obj in zip(('app', 'mysql', 'redis'), objects):
        require(obj['Name'] == '/' + name + '-' + suffix
                and re.fullmatch(r'[0-9a-f]{64}', obj['Id']) is not None, 'wrong owned container')
        require(obj.get('Config', {}).get('Labels', {}).get('chengyin.auth-recovery') == name,
                'foreign container label')
        require(set(obj['NetworkSettings']['Networks']) == {name}
                and obj['NetworkSettings']['Networks'][name]['NetworkID'] == network['Id'],
                'wrong container network')
        require(obj['State']['Running'] and not obj['State']['Paused']
                and not obj['State']['OOMKilled'], 'container not healthy')


def validate_bootstrap_state(user_count, role_rows):
    require(user_count == 0 and role_rows == [('admin', '0', '0')], 'bootstrap is not fresh/canonical')


def captcha_key(uuid):
    require(isinstance(uuid, str) and re.fullmatch(r'[0-9a-f]{32}', uuid) is not None,
            'invalid challenge UUID')
    return 'captcha_codes:' + uuid


def assert_refund_transition(before, after, allowed):
    require(before.keys() == after.keys(), 'refund columns changed')
    require(all(before[k] == after[k] for k in before.keys() - set(allowed)),
            'nonlease refund value changed')


def assert_auth_failure(response):
    require(response.get('http') in (200, 401) and response.get('businessCode') == 401
            and not response.get('transportUnknown'), 'release not conclusively rejected by authentication')


def redact(text, secrets=()):
    text = str(text or '')
    for secret in sorted((str(s) for s in secrets if s), key=len, reverse=True):
        text = text.replace(secret, '[REDACTED]')
    text = re.sub(r'(?i)Bearer\s+\S+', 'Bearer [REDACTED]', text)
    text = re.sub(r'\beyJ[\w-]+\.[\w-]+\.[\w-]+', '[REDACTED_JWT]', text)
    text = re.sub(r'(?i)((?:password|passwd|pwd|secret|token|api[_-]?key)\s*[:=]\s*)[^\s,;]+',
                  r'\1[REDACTED]', text)
    return text.encode('utf-8')[:4096].decode('utf-8', errors='ignore')


def redact_log(text, secrets):
    # Preserve full diagnostic logs; short CAPTCHA digits are never global replacement keys.
    lines = []
    for line in text.splitlines(keepends=True):
        for secret in sorted((s for s in secrets if s), key=len, reverse=True):
            line = line.replace(secret, '[REDACTED]')
        line = re.sub(r'(?i)Bearer\s+\S+', 'Bearer [REDACTED]', line)
        line = re.sub(r'\beyJ[\w-]+\.[\w-]+\.[\w-]+', '[REDACTED_JWT]', line)
        line = re.sub(r'(?i)([\"\']?(?:password|passwd|pwd|secret|authorization|leaseToken|token)[\"\']?\s*[:=]\s*)[\"\']?[^\s,;}\"\']+', r'\1[REDACTED]', line)
        if re.search(r'captcha|验证码', line, re.I):
            line = re.sub(r'(?i)([\"\']?(?:code|answer|uuid|img)[\"\']?\s*[:=]\s*)[\"\']?[^\s,;}\"\']+', r'\1[REDACTED]', line)
        lines.append(line)
    return ''.join(lines)


def command(args, data=None, timeout=20):
    result = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        # Do not include argv/input: login JSON and subprocess environments contain fixture secrets.
        raise RuntimeError('fixture_command_failed_' + str(result.returncode)
                           + ': ' + redact(result.stderr.decode(errors='replace'), SECRET_VALUES))
    return result.stdout


SECRET_VALUES = []


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def database(port, schema=None, autocommit=True):
    import pymysql
    require(str(port).isdigit() and 1 <= int(port) <= 65535, 'invalid loopback port')
    return pymysql.connect(host='127.0.0.1', port=int(port), user='root',
                           password=os.environ['CHENGYIN_AUTH_FIXTURE_DB_PASSWORD'],
                           database=schema, charset='utf8mb4', autocommit=autocommit,
                           connect_timeout=5, read_timeout=10, write_timeout=10)


def write_report(out, name, report):
    text = json.dumps(report, ensure_ascii=False, indent=2, default=str) + '\n'
    require(all(not secret or secret not in text for secret in SECRET_VALUES), 'secret in evidence')
    path = out / name
    path.write_text(text)
    path.chmod(0o600)


def mysql_endpoint(name, suffix='mysql'):
    require(suffix in ('mysql', 'restore-mysql'), 'unknown owned MySQL slot')
    obj = json.loads(command(DOCKER + ['inspect', name + '-' + suffix], timeout=5))[0]
    network = json.loads(command(DOCKER + ['network', 'inspect', name], timeout=5))[0]
    require(obj['Name'] == '/' + name + '-' + suffix
            and obj['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name
            and re.fullmatch(r'[0-9a-f]{64}', obj['Id']) is not None,
            'MySQL relay target not owned')
    if suffix == 'restore-mysql':
        require(obj['Config'].get('Image') == RESTORE_MYSQL_IMAGE, 'restore MySQL image changed')
    require(network['Name'] == name and network['Internal'] is True
            and network.get('Labels', {}).get('chengyin.auth-recovery') == name
            and set(obj['NetworkSettings']['Networks']) == {name}, 'MySQL relay network not owned')
    attachment = obj['NetworkSettings']['Networks'][name]
    address = ipaddress.ip_address(attachment['IPAddress'])
    require(attachment['NetworkID'] == network['Id'] and address.version == 4
            and address.is_private and not address.is_loopback
            and any(address in ipaddress.ip_network(c['Subnet']) for c in network['IPAM']['Config']
                    if ':' not in c['Subnet']), 'MySQL relay address not in owned network')
    require(obj['State']['Running'] and not obj['State']['Paused'] and not obj['State']['OOMKilled']
            and not obj['State']['Restarting'] and obj['State']['Pid'] > 0, 'MySQL relay target not running')
    return {'id': obj['Id'], 'networkId': network['Id'], 'ip': str(address), 'pid': obj['State']['Pid']}


@contextlib.contextmanager
def mysql_relay(name, baseline, suffix='mysql'):
    """Host-only transport for the original loopback-only builder/bootstrap."""
    expected = baseline['endpoint']
    with _owned_mysql_relay(expected, lambda: mysql_endpoint(name, suffix)) as server:
        yield server.server_address[1]


@contextlib.contextmanager
def _owned_mysql_relay(expected, current_endpoint, *, loopback_port=0):
    """Internal reuse seam: caller proves one original MySQL endpoint, never a CLI target/port option."""
    require(type(loopback_port) is int and loopback_port in (0, 33080), 'unreviewed relay listener')
    if loopback_port:
        require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
                and os.environ.get('GITHUB_REPOSITORY') == 'liboyang42-cpu/hengyin'
                and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
                and os.environ.get('HTTP_SCOPE') == 'controlled-combination'
                and os.environ.get('CHENGYIN_HTTP_E2E_TESTS') == 'CrossDomainCombinedLoadMySqlIT'
                and re.fullmatch('[0-9a-f]{40}', os.environ.get('SOURCE_SHA', '')),
                'fixed combined relay requires original manual owned scope')
    require(current_endpoint() == expected, 'MySQL relay identity changed before bind')
    closing = threading.Event()
    sockets = set()
    guard = threading.Lock()
    failures = []

    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            upstream = None
            try:
                require(current_endpoint() == expected, 'MySQL relay identity changed before connect')
                upstream = socket.create_connection((expected['ip'], 3306), timeout=5)
                with guard:
                    sockets.update((self.request, upstream))
                self.request.settimeout(5)
                upstream.settimeout(5)
                active = time.monotonic()
                readers = [self.request, upstream]
                while readers and not closing.is_set():
                    readable, _, _ = select.select(readers, [], [], 0.2)
                    require(time.monotonic() - active < 120, 'MySQL relay idle timeout')
                    for source in readable:
                        data = source.recv(65536)
                        destination = upstream if source is self.request else self.request
                        if not data:
                            destination.shutdown(socket.SHUT_WR)
                            readers.remove(source)
                            continue
                        destination.sendall(data)
                        active = time.monotonic()
            except BaseException as exc:
                if not closing.is_set():
                    failures.append(type(exc).__name__ + ': ' + redact(str(exc), SECRET_VALUES))
            finally:
                with guard:
                    sockets.discard(self.request)
                    sockets.discard(upstream)
                if upstream:
                    upstream.close()

    if loopback_port == 0:
        server = socketserver.ThreadingTCPServer(('127.0.0.1', 0), Handler)
    else:
        class RelayServer(socketserver.ThreadingTCPServer):
            # Fixed-port reuse covers closed TIME_WAIT tuples, never a live listener or SO_REUSEPORT.
            allow_reuse_address = True

        server = RelayServer(('127.0.0.1', 33080), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.1})
    thread.start()
    phase_error = None
    try:
        yield server
        require(current_endpoint() == expected, 'MySQL relay identity changed after phase')
    except BaseException as exc:
        phase_error = exc
        raise
    finally:
        closing.set()
        try:
            server.shutdown()
        except BaseException as exc:
            failures.append('listener shutdown: ' + type(exc).__name__)
        with guard:
            for active_socket in sockets:
                try:
                    active_socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                try:
                    active_socket.close()
                except OSError as exc:
                    failures.append('socket close: ' + type(exc).__name__)
        try:
            server.server_close()  # Joins every accepted-connection worker.
        except BaseException as exc:
            failures.append('worker close/join: ' + type(exc).__name__)
        thread.join(timeout=5)
        if thread.is_alive():
            failures.append('listener thread did not exit')
        if phase_error is None:
            require(not failures, 'MySQL relay failed: ' + '; '.join(failures))


def mysql_ready(name, schema, out, private, port, suffix='mysql'):
    """Wait for the one created server, not its socket-only initialization server."""
    require(suffix in ('mysql', 'restore-mysql'), 'unknown owned MySQL readiness slot')
    report_name = 'schema-summary.json' if suffix == 'mysql' else 'restore-mysql-readiness.json'
    deadline = time.monotonic() + 120
    cid = None
    facts = {'result': 'NOT_READY'}
    try:
        while time.monotonic() < deadline:
            obj = json.loads(command(DOCKER + ['inspect', name + '-' + suffix], timeout=5))[0]
            require(obj['Name'] == '/' + name + '-' + suffix
                    and obj['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name
                    and re.fullmatch(r'[0-9a-f]{64}', obj['Id']) is not None,
                    'MySQL readiness target not owned')
            cid = cid or obj['Id']
            require(obj['Id'] == cid, 'MySQL readiness target replaced')
            facts.update(id=cid, state={k: obj['State'][k] for k in
                         ('Pid', 'Running', 'Paused', 'Restarting', 'ExitCode', 'OOMKilled')},
                         requestedPortBindings=obj['HostConfig'].get('PortBindings'),
                         actualPorts=obj['NetworkSettings'].get('Ports'))
            write_report(out, report_name, {'mysqlReadiness': facts})
            require(obj['State']['Running'] and not obj['State']['OOMKilled']
                    and not obj['State']['Restarting'], 'MySQL exited or unhealthy before TCP readiness')
            try:
                check = subprocess.run(DOCKER + ['exec', cid, 'sh', '-c',
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=TCP -h127.0.0.1 '
                    '--connect-timeout=2 -uroot -N -e "SELECT @@server_uuid,VERSION()"'],
                    capture_output=True, timeout=5)
                if check.returncode == 0:
                    uuid, version = check.stdout.decode().strip().split('\t')
                    require(version == '8.0.45' and re.fullmatch(r'[a-f0-9-]{36}', uuid) is not None,
                            'unexpected MySQL native identity/version')
                    facts.update(endpoint=mysql_endpoint(name, suffix), serverUuid=uuid, version=version)
                    if suffix == 'restore-mysql':
                        source = json.loads((out / 'schema-summary.json').read_text())['mysqlReadiness']
                        require(source['result'] == 'OWNED_MYSQL_TCP_READY'
                                and source['version'] == version and source['serverUuid'] != uuid
                                and source['id'] != cid
                                and source['endpoint']['networkId'] == facts['endpoint']['networkId'],
                                'restore target is not an independent same-version server in the owned network')
                    facts['result'] = 'OWNED_MYSQL_TCP_READY'
                    break
            except subprocess.TimeoutExpired:
                pass
            time.sleep(1)
        require(facts['result'] == 'OWNED_MYSQL_TCP_READY', 'MySQL TCP readiness deadline')
    except BaseException as exc:
        facts.update(result='FAIL_NO_RESTART', failureType=type(exc).__name__,
                     reason=redact(str(exc), SECRET_VALUES))
        raise
    finally:
        if cid:
            try:
                logs = subprocess.run(DOCKER + ['logs', '--tail', '100', cid],
                                      capture_output=True, timeout=10)
                facts.update(logExit=logs.returncode,
                             logTail=redact((logs.stdout + logs.stderr).decode(errors='replace'), SECRET_VALUES))
            except (OSError, subprocess.TimeoutExpired) as exc:
                facts['logFailureType'] = type(exc).__name__
        write_report(out, report_name, {'mysqlReadiness': facts})


def prepare(name, schema, out, private, port):
    import secrets
    import crypt
    bootstrap = module('auth_fixture_bootstrap', 'scripts/bootstrap-test-admin.py')
    bootstrap.validate_runtime_target('127.0.0.1', int(port))
    owned = json.loads(command(DOCKER + ['inspect', name + '-mysql']))[0]
    require(owned['Name'] == '/' + name + '-mysql'
            and owned['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name
            and set(owned['NetworkSettings']['Networks']) == {name}, 'initial MySQL ownership changed')
    require(not owned['HostConfig'].get('PortBindings'), 'MySQL published ports forbidden')
    network = json.loads(command(DOCKER + ['network', 'inspect', name]))[0]
    require(network['Internal'] is True and network.get('Labels', {}).get('chengyin.auth-recovery') == name
            and owned['NetworkSettings']['Networks'][name]['NetworkID'] == network['Id'],
            'initial MySQL network ownership changed')
    jar = ROOT / 'chengyinhub-admin/target/chengyinhub-admin.jar'
    with zipfile.ZipFile(jar) as archive:
        properties = dict(line.split('=', 1) for line in archive.read('BOOT-INF/classes/git.properties').decode().splitlines()
                          if '=' in line and not line.startswith('#'))
    require(properties.get('git.commit.id.full', properties.get('git.commit.id')) == os.environ['GITHUB_SHA'],
            'native Jar checkout differs from actual workflow checkout')
    with database(port) as db, db.cursor() as cur:
        cur.execute('SELECT @@server_uuid,VERSION()')
        uuid, version = cur.fetchone()
        baseline = json.loads((out / 'schema-summary.json').read_text())['mysqlReadiness']
        require((uuid, version) == (baseline['serverUuid'], baseline['version']),
                'loopback relay differs from native MySQL identity/version')
        cur.execute('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s', (schema,))
        require(cur.fetchone()[0] == 0, 'schema already exists; no replay')
    bootstrap.validate_target(schema, uuid, True)
    builder = module('auth_fixture_builder', 'scripts/e2e/build_schema.py')
    old_args = sys.argv
    log = io.StringIO()
    exit_code = None
    try:
        sys.argv = ['build_schema.py', '--schema', schema, '--host', '127.0.0.1', '--port', str(port),
                    '--user', 'root',
                    # token_urlsafe 约 1/64 以「-」开头,分开传会被 argparse 当成选项(偶发 exit 2);用 = 形式
                    '--password=' + os.environ['CHENGYIN_AUTH_FIXTURE_DB_PASSWORD']]
        with contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
            exit_code = builder.main()
    finally:
        sys.argv = old_args
        readiness = json.loads((out / 'schema-summary.json').read_text())
        write_report(out, 'schema-summary.json', {**readiness,
            'schema': schema, 'serverUuid': uuid, 'builderExit': exit_code,
            'actualCheckout': os.environ['GITHUB_SHA'], 'embeddedCheckoutMatched': True,
            'nativeJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
            'builderLogSha256': hashlib.sha256(log.getvalue().encode()).hexdigest(),
            'builderLogTail': redact(log.getvalue()[-4096:], SECRET_VALUES),
            'postDeployAndDataAssertionsNotBusinessAcceptance': True})
    require(exit_code == 0, 'full schema builder failed; no continuation')
    with database(port, schema) as db, db.cursor() as cur:
        cur.execute('SELECT @@server_uuid')
        require(cur.fetchone()[0] == uuid, 'database identity changed')
        cur.execute('SELECT COUNT(*) FROM sys_user')
        count = cur.fetchone()[0]
        cur.execute('SELECT role_key,status,del_flag FROM sys_role WHERE role_id=1')
        validate_bootstrap_state(count, list(cur.fetchall()))
        for table in ('sys_user', 'sys_user_role', 'sys_role', 'refund_application') + FINANCIAL_TABLES:
            cur.execute('SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s', (schema, table))
            require(cur.fetchone() == ('InnoDB',), 'required business/authentication table missing or not InnoDB')
        cur.execute("SELECT COUNT(*) FROM sys_config WHERE config_key='sys.account.captchaEnabled'")
        require(cur.fetchone()[0] == 0, 'CAPTCHA override is not allowed')
    password = 'Aa1!' + secrets.token_hex(7)
    SECRET_VALUES.append(password)
    bootstrap.validate_password(password)
    with database(port, schema, autocommit=False) as db:
        bootstrap.initialize(db, 'admin', password, uuid, crypt)
    with database(port, schema) as db, db.cursor() as cur:
        cur.execute("CREATE USER 'startup_probe'@'%%' IDENTIFIED BY %s", ('synthetic-local-only',))
        cur.execute('GRANT SELECT,INSERT,UPDATE,DELETE ON `' + schema + '`.* TO \'startup_probe\'@\'%\'')
    fd = os.open(private, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump({'password': password, 'serverUuid': uuid, 'schema': schema,
                   'redactions': SECRET_VALUES}, stream)


def prepare_restore_runtime_user(name, schema, out, private, port):
    """Prepare only the original least-privilege app user on the owned restored server."""
    validate_execution_env(os.environ)
    require(os.environ.get('CHENGYIN_CI_RESTORE_HTTP_STARTUP') == 'true'
            and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'restore runtime user not admitted')
    require(re.fullmatch(r'cy-auth-[0-9a-f]{32}', name) is not None
            and schema == 'cy_e2e_auth_' + name[8:], 'fixed restore user scope required')
    require(private.is_file() and not private.is_symlink() and private.stat().st_mode & 0o777 == 0o600,
            'original private restore identity required')
    saved = json.loads(private.read_text())
    source = json.loads((out / 'schema-summary.json').read_text())['mysqlReadiness']
    target = json.loads((out / 'restore-mysql-readiness.json').read_text())['mysqlReadiness']
    require(saved['schema'] == schema and saved['serverUuid'] == source['serverUuid']
            and source['result'] == target['result'] == 'OWNED_MYSQL_TCP_READY'
            and source['version'] == target['version'] == '8.0.45'
            and source['serverUuid'] != target['serverUuid'] and source['id'] != target['id'],
            'restored runtime user target differs from original scope')
    require(re.fullmatch(r'[a-f0-9]{40}', os.environ.get('GITHUB_SHA', '')) is not None,
            'restore runtime user needs the exact source identity')
    SECRET_VALUES.extend(saved.get('redactions', []))
    SECRET_VALUES.append('synthetic-local-only')
    with mysql_relay(name, target, suffix='restore-mysql') as relay_port, database(relay_port) as db, db.cursor() as cur:
        cur.execute('SELECT @@server_uuid')
        require(cur.fetchone() == (target['serverUuid'],), 'restored server identity changed')
        cur.execute('SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s', (schema,))
        require(cur.fetchone() == (1,), 'exact restored schema must exist before runtime user setup')
        cur.execute('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s',
                    (schema, 'sys_user'))
        require(cur.fetchone() == (1,), 'restored authentication table missing')
        cur.execute('SELECT COUNT(*) FROM mysql.user WHERE User=%s AND Host=%s', ('startup_probe', '%'))
        require(cur.fetchone() == (0,), 'runtime user already exists; do not rebind its credentials or grants')
        cur.execute("CREATE USER 'startup_probe'@'%%' IDENTIFIED BY %s", ('synthetic-local-only',))
        cur.execute('GRANT SELECT,INSERT,UPDATE,DELETE ON `' + schema + "`.* TO 'startup_probe'@'%'")
        grantee = "'startup_probe'@'%'"
        cur.execute('SELECT TABLE_SCHEMA,PRIVILEGE_TYPE,IS_GRANTABLE FROM information_schema.SCHEMA_PRIVILEGES '
                    'WHERE GRANTEE=%s ORDER BY TABLE_SCHEMA,PRIVILEGE_TYPE', (grantee,))
        privileges = list(cur.fetchall())
        require(privileges == [(schema, action, 'NO') for action in ('DELETE', 'INSERT', 'SELECT', 'UPDATE')],
                'runtime schema privilege scope widened')
        cur.execute("SELECT COUNT(*) FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=%s AND PRIVILEGE_TYPE<>'USAGE'",
                    (grantee,))
        require(cur.fetchone() == (0,), 'runtime global privilege scope widened')
    write_report(out, 'restore-runtime-user.json', {'sourceSha': os.environ['GITHUB_SHA'], 'schema': schema,
                 'serverUuid': target['serverUuid'], 'mysqlCid': target['id'], 'runtimeUser': 'startup_probe',
                 'privileges': privileges, 'result': 'OWNED_RESTORED_RUNTIME_USER_PREPARED'})


def restore_http_not_started(name, schema, out, summary):
    """Only an explicit same-run pre-start C failure can waive an impossible app log.

    Called after exact outer cleanup. This recognizes absence, never business success.
    Missing/old/corrupt reports retain the existing unavailable-log failure.
    """
    env = os.environ
    if (env.get('CHENGYIN_CI_RESTORE_HTTP_STARTUP') != 'true'
            or env.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or env.get('GITHUB_REPOSITORY') != 'liboyang42-cpu/hengyin'
            or not re.fullmatch('[a-f0-9]{40}', env.get('GITHUB_SHA', ''))
            or not all(re.fullmatch('[1-9][0-9]*', env.get(key, ''))
                       for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'))
            or not re.fullmatch('cy-auth-[a-f0-9]{32}', name)
            or schema != 'cy_e2e_auth_' + name[8:]):
        return False
    path = out / 'restore-http-application.json'
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
            return False
        report = json.loads(path.read_text())
        if not isinstance(report, dict):
            return False
        if (report.get('sourceSha') != env['GITHUB_SHA'] or report.get('schema') != schema
                or report.get('runId') != env['GITHUB_RUN_ID'] or report.get('runAttempt') != env['GITHUB_RUN_ATTEMPT']
                or not isinstance(report.get('jarSha256'), str)
                or not re.fullmatch('[a-f0-9]{64}', report['jarSha256'])
                or report.get('applicationStartAttempted') is not False or report.get('boots') != []
                or report.get('completeBoots') != [] or report.get('checks') != []
                or report.get('ownedAppCids') != [] or report.get('removedAppCids') != []
                or report.get('applicationStop') != 'NO_ACTIVE_APP_SLOT' or report.get('result') != 'NOT_COMPLETED'
                or report.get('privateCleanup') != 'EXACT_OWN_SUBTREE_REMOVED'
                or report.get('firstFailurePhase') not in ('PRE_APPLICATION_PREPARATION', 'SOURCE_DATABASE_PREPARATION')
                or not isinstance(report.get('firstFailureType'), str)
                or not re.fullmatch('[A-Za-z_][A-Za-z0-9_]{0,95}', report['firstFailureType'])
                or report.get('productionDispatchRelease') is not False):
            return False
        expected = {name + '-' + suffix: report.get(key) for suffix, key in
                    (('mysql', 'sourceMysqlCid'), ('restore-mysql', 'targetMysqlCid'), ('redis', 'redisCid'))}
        removed = summary.get('ownedRemoved', [])
        return (summary.get('cleanup') == 'CONFIRMED_EXACT_OWNED_REMOVAL'
                and all(isinstance(cid, str) and re.fullmatch('[a-f0-9]{64}', cid) for cid in expected.values())
                and len(set(expected.values())) == 3 and len(removed) == 3
                and all(isinstance(row, dict) and row.get('independentNoSuch') is True for row in removed)
                and {row.get('name'): row.get('id') for row in removed} == expected)
    except (OSError, ValueError, TypeError):
        return False


def cleanup(name, schema, out, private, port):
    """Resolve even a lost docker-run reply by exact name and its original ownership label."""
    if private.exists():
        require(not private.is_symlink() and private.stat().st_mode & 0o777 == 0o600,
                'private cleanup input changed')
        SECRET_VALUES.extend(json.loads(private.read_text()).get('redactions', []))
    path = out / 'authenticated-redis-recovery.json'
    try:
        report = json.loads(path.read_text())
        if not isinstance(report, dict):
            raise ValueError('report root must be an object')
    except FileNotFoundError:
        report = {'result': 'FAIL_BEFORE_BUSINESS_PROBE'}
    except (OSError, UnicodeError, ValueError) as exc:
        report = {'result': 'FAIL_UNKNOWN', 'reportReadFailure': {'type': type(exc).__name__}}
    # The shell exits on either failed pre-app phase, before docker run ...-app.
    pre_app_failure = (report.get('fixturePhase') in ('mysql-ready', 'prepare', 'restore-mysql-ready')
                       and report.get('result') == 'FAIL_OR_UNKNOWN_NO_RETRY'
                       and bool(report.get('failureType')) and not report.get('events'))
    summary = {'cleanup': 'NOT_CONFIRMED', 'ownedRemoved': []}
    logs_available = False
    app_absent = False
    suffixes = ('app', 'mysql', 'redis')
    if os.environ.get('CHENGYIN_CI_RESTORE_HTTP_STARTUP') == 'true':
        suffixes = ('app', 'restore-mysql', 'mysql', 'redis')
    for suffix in suffixes:
        target = name + '-' + suffix
        inspected = subprocess.run(DOCKER + ['container', 'inspect', target], capture_output=True, timeout=20)
        if inspected.returncode:
            require_absent('container', target, inspected.returncode, inspected.stderr)
            if suffix == 'app':
                app_absent = True
            continue
        obj = json.loads(inspected.stdout)[0]
        require(obj['Name'] == '/' + target and obj['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name,
                'cleanup container not owned')
        cid = obj['Id']
        require(re.fullmatch(r'[0-9a-f]{64}', cid) is not None, 'invalid owned CID')
        if suffix == 'restore-mysql':
            require(obj['Config'].get('Image') == RESTORE_MYSQL_IMAGE
                    and set(obj['NetworkSettings']['Networks']) <= {name}, 'restore MySQL provenance changed')
            readiness = out / 'restore-mysql-readiness.json'
            require(not readiness.is_symlink(), 'restore readiness is not an owned regular receipt')
            if readiness.is_file():
                recorded = json.loads(readiness.read_text())['mysqlReadiness'].get('id')
                require(recorded is None or recorded == cid, 'restore MySQL CID changed before cleanup')
        if suffix == 'app':
            # Fence an unsettled HTTP request before bringing its authentication store back.
            if obj['State']['Running']:
                command(DOCKER + ['stop', '--time', '10', cid], timeout=25)
                stopped = json.loads(command(DOCKER + ['inspect', cid]))[0]
                require(stopped['Id'] == cid and not stopped['State']['Running'], 'application stop unknown')
            try:
                raw = subprocess.run(DOCKER + ['logs', cid], capture_output=True, timeout=20)
                logs_available = raw.returncode == 0
                path = out / 'application.log'
                path.write_text(redact_log((raw.stdout + raw.stderr).decode(errors='replace'), SECRET_VALUES))
                path.chmod(0o600)
            except (OSError, subprocess.TimeoutExpired):
                logs_available = False
        if suffix == 'redis' and obj['State']['Paused']:
            command(DOCKER + ['unpause', cid])
            restored = json.loads(command(DOCKER + ['inspect', cid]))[0]
            require(restored['Id'] == cid and restored['State']['Running'] and not restored['State']['Paused'],
                    'outer Redis recovery state unknown')
            require(command(DOCKER + ['exec', cid, 'redis-cli', 'PING']).strip() == b'PONG',
                    'outer Redis recovery PING unknown')
        command(DOCKER + ['rm', '-f', cid], timeout=35)
        for identifier in (cid, target):
            check = subprocess.run(DOCKER + ['container', 'inspect', identifier], capture_output=True, timeout=15)
            require_absent('container', identifier, check.returncode, check.stderr)
        summary['ownedRemoved'].append({'name': target, 'id': cid, 'independentNoSuch': True})
    inspected = subprocess.run(DOCKER + ['network', 'inspect', name], capture_output=True, timeout=15)
    if inspected.returncode == 0:
        network = json.loads(inspected.stdout)[0]
        require(network['Name'] == name and network.get('Labels', {}).get('chengyin.auth-recovery') == name
                and network['Internal'] is True, 'cleanup network not owned')
        command(DOCKER + ['network', 'rm', network['Id']])
    else:
        require_absent('network', name, inspected.returncode, inspected.stderr)
    check = subprocess.run(DOCKER + ['network', 'inspect', name], capture_output=True, timeout=15)
    require_absent('network', name, check.returncode, check.stderr)
    summary['cleanup'] = 'CONFIRMED_EXACT_OWNED_REMOVAL'
    path = out / 'authenticated-redis-recovery.json'
    report['resourceCleanup'] = summary
    report['applicationLogPreserved'] = logs_available
    restore_pre_start = (report.get('result') == 'FAIL_BEFORE_BUSINESS_PROBE'
                         and not report.get('reportReadFailure')
                         and restore_http_not_started(name, schema, out, summary))
    not_started = app_absent and (pre_app_failure or restore_pre_start)
    report['applicationLogStatus'] = ('PRESERVED' if logs_available else
                                      'NOT_STARTED' if not_started else 'UNAVAILABLE')
    write_report(out, path.name, report)
    require(not report.get('reportReadFailure'),
            'original report unreadable; original phase unknown after exact resource cleanup')
    require(logs_available or not_started, 'application logs unavailable after exact resource cleanup')


def record_phase_failure(out, mode, exc):
    failure = {'fixturePhase': mode, 'result': 'FAIL_OR_UNKNOWN_NO_RETRY',
               'failureType': type(exc).__name__, 'reason': redact(str(exc), SECRET_VALUES)}
    path = out / 'authenticated-redis-recovery.json'
    try:
        report = json.loads(path.read_text())
        if not isinstance(report, dict):
            raise ValueError('report root must be an object')
    except FileNotFoundError:
        report = {}
    except (OSError, UnicodeError, ValueError) as read_exc:
        report = {'reportReadFailure': {'type': type(read_exc).__name__}}
    if report.get('reportReadFailure'):
        failure['result'] = 'FAIL_UNKNOWN'
    first_failure = bool(report.get('fixturePhase') and report.get('failureType'))
    if mode == 'cleanup':
        report['cleanupFailure'] = failure
        report.setdefault('resourceCleanup', {'cleanup': 'NOT_CONFIRMED'})
    if not first_failure:
        report.update(failure)
    write_report(out, path.name, report)
    return failure


def exercise(name, schema, out, private, port):
    saved = json.loads(private.read_text())
    require(saved['schema'] == schema and private.stat().st_mode & 0o777 == 0o600,
            'private fixture input mismatch')
    SECRET_VALUES.append(saved['password'])
    objects = json.loads(command(DOCKER + ['inspect', name + '-app', name + '-mysql', name + '-redis']))
    network = json.loads(command(DOCKER + ['network', 'inspect', name]))[0]
    endpoint = command(DOCKER + ['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).decode().strip()
    validate_targets(name, schema, endpoint, objects, network)
    app, mysql, redis = [o['Id'] for o in objects]
    states = [{k: o['State'][k] for k in ('Pid', 'Running', 'Paused', 'OOMKilled')} for o in objects]
    report = {'schema': schema, 'serverUuid': saved['serverUuid'], 'events': [],
              'scope': 'Authenticated refund lease recovery only; no cash movement or human CAPTCHA acceptance',
              'ownedIds': [app, mysql, redis], 'initialState': states, 'result': 'NOT_COMPLETED'}
    pause_intent = False
    logged_in_at = None

    def mark(stage, **facts):
        saved['redactions'] = SECRET_VALUES
        private.write_text(json.dumps(saved))
        report['events'].append({'stage': stage, 'atUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), **facts})
        write_report(out, 'authenticated-redis-recovery.json', report)

    def http(path, payload=None, token=None):
        args = DOCKER + ['exec', '-i', app, 'curl', '--noproxy', '*', '--silent', '--show-error',
                         '--max-time', '35', '--write-out', '\n%{http_code}',
                         '--config', '-']
        config = 'url = "http://127.0.0.1:8080' + path + '"\n'
        if token:
            config += 'header = "Authorization: Bearer ' + token + '"\n'
        if payload is not None:
            config += 'header = "Content-Type: application/json"\n'
            config += 'data = ' + json.dumps(json.dumps(payload)) + '\n'
        raw = command(args, config.encode(), timeout=40).decode()
        body, status = raw.rsplit('\n', 1)
        value = json.loads(body)
        return {'http': int(status), 'businessCode': value.get('code')}, value

    def challenge():
        status, body = http('/captchaImage')
        require(status == {'http': 200, 'businessCode': 200} and body.get('captchaEnabled') is True
                and body.get('img'), 'real CAPTCHA not enabled')
        key = captcha_key(body.get('uuid'))
        raw = command(DOCKER + ['exec', redis, 'redis-cli', '--raw', 'GET', key]).decode().strip()
        answer = json.loads(raw)
        require(isinstance(answer, str) and answer, 'challenge oracle unavailable')
        return body['uuid'], answer, key

    def login(uuid, answer):
        return http('/login', {'username': 'admin', 'password': saved['password'], 'uuid': uuid, 'code': answer})

    messages = dict(line.split('=', 1) for line in (ROOT / 'chengyinhub-admin/src/main/resources/i18n/messages.properties').read_text().splitlines()
                    if '=' in line and not line.startswith('#'))

    def rejected_captcha(status, body, message_key, classification):
        require(status['http'] == 200 and status['businessCode'] != 200 and not body.get('token')
                and body.get('msg') == messages[message_key], 'unexpected CAPTCHA failure category')
        mark('captcha_negative_control', classification=classification, **status)

    with database(port, schema) as db:
        with db.cursor() as identity:
            identity.execute('SELECT @@server_uuid,VERSION()')
            baseline = json.loads((out / 'schema-summary.json').read_text())['mysqlReadiness']
            require(identity.fetchone() == (saved['serverUuid'], baseline['version']),
                    'exercise relay MySQL identity/version mismatch')
        def rows(sql, args=()):
            with db.cursor() as cur:
                cur.execute(sql, args)
                return cur.fetchall()

        def refund():
            with db.cursor() as cur:
                cur.execute('SELECT * FROM refund_application WHERE id=20')
                values = cur.fetchone()
                require(values is not None, 'refund fixture disappeared')
                return dict(zip([d[0] for d in cur.description], values))

        def refund_hash(value):
            return hashlib.sha256(json.dumps(value, default=str, sort_keys=True).encode()).hexdigest()

        def protected():
            result = {}
            for table in FINANCIAL_TABLES + ('sys_role', 'sys_role_menu', 'sys_menu', 'sys_user_role', 'user_permission'):
                values = rows('SELECT * FROM `' + table + '` ORDER BY 1')
                encoded = sorted(json.dumps(row, default=str) for row in values)
                result[table] = {'rows': len(values), 'sha256': hashlib.sha256(json.dumps(encoded).encode()).hexdigest(),
                                 'createSha256': hashlib.sha256(str(rows('SHOW CREATE TABLE `' + table + '`')[0][1]).encode()).hexdigest()}
            credentials = rows('SELECT user_id,user_name,password,status,del_flag FROM sys_user ORDER BY user_id')
            result['systemAccountCredentials'] = {'rows': len(credentials), 'sha256': hashlib.sha256(json.dumps(credentials, default=str).encode()).hexdigest()}
            return result

        try:
            require(rows('SELECT @@server_uuid,DATABASE()')[0] == (saved['serverUuid'], schema), 'wrong database')
            uuid, answer, key = challenge()
            status, body = login(uuid, answer + '!')
            rejected_captcha(status, body, 'user.jcaptcha.error', 'WRONG_CODE')
            require(command(DOCKER + ['exec', redis, 'redis-cli', 'EXISTS', key]).strip() == b'0', 'CAPTCHA not consumed')
            status, body = login('0' * 32, 'invalid')
            rejected_captcha(status, body, 'user.jcaptcha.expire', 'UNKNOWN_UUID_EXPIRED')
            uuid, answer, key = challenge()
            status, body = login(uuid, answer)
            require(status == {'http': 200, 'businessCode': 200} and isinstance(body.get('token'), str)
                    and body['token'], 'normal administrator login failed')
            token = body['token']; SECRET_VALUES.append(token)
            require(re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', token) is not None,
                    'invalid returned JWT/header shape')
            logged_in_at = time.monotonic()
            saved['redactions'] = SECRET_VALUES; private.write_text(json.dumps(saved))
            mark('normal_login_http_settled', **status)
            status, body = http('/getInfo', token=token)
            require(status['businessCode'] == 200 and body.get('user', {}).get('userId') == 1,
                    'normal login actor is not canonical administrator')
            mark('original_actor_get_info', actor=1, **status)
            require(rows('SELECT role_id FROM sys_user_role WHERE user_id=1') == ((1,),), 'bootstrap role changed')
            status, body = login(uuid, answer)
            rejected_captcha(status, body, 'user.jcaptcha.expire', 'CONSUMED_UUID_EXPIRED')
            mark('normal_captcha_login_and_negative_controls', actor=1, integrationOracleNotHumanUi=True)
            require(rows('SELECT COUNT(*) FROM refund_application')[0][0] == 0, 'refund schema not empty')
            with db.cursor() as cur:
                cur.execute("INSERT INTO refund_application(id,refund_no,source_type,source_id,refund_amount,status,reason,del_flag,payout_status,create_time,update_time) VALUES(20,NULL,'registration',11,1,1,'isolated lease fixture',0,2,NOW(),NOW())")
            original = refund()
            require(original['refund_no'] is None and original['del_flag'] == 0 and original['payout_status'] == 2
                    and all(original[k] is None for k in LEASE_COLUMNS - {'update_time'}), 'not a never-submitted manual queue fixture')
            protection = protected()
            report['protectedBefore'] = protection
            mark('claim_request_intent', refundId=20, actor=1, refundNoInitiallyNull=True,
                 originalRefundRowSha256=refund_hash(original))
            status, body = http('/business/refund/manual/claim', {'refundId': 20}, token)
            require(status['businessCode'] == 200 and isinstance(body.get('data'), str) and body['data'], 'claim failed or unknown; no retry')
            lease = body['data']; SECRET_VALUES.append(lease)
            saved['redactions'] = SECRET_VALUES; private.write_text(json.dumps(saved))
            claimed = refund(); assert_refund_transition(original, claimed, LEASE_COLUMNS)
            require(claimed['payout_lease_owner'] == 1 and claimed['payout_lease_token'] == lease
                    and rows('SELECT payout_lease_expires_at>NOW(3) FROM refund_application WHERE id=20')[0][0] == 1,
                    'claim durable owner/token/live expiry mismatch')
            claim_status = status
            status, body = http('/business/refund/manual/claim', {'refundId': 20}, token)
            require(status['businessCode'] != 200 and refund() == claimed, 'duplicate live claim changed row')
            mark('duplicate_live_claim_rejected', **status)
            status, body = http('/business/refund/manual/release', {'refundId': 20, 'leaseToken': 'wrong-fixture-fence'}, token)
            require(status['businessCode'] != 200 and refund() == claimed, 'wrong fence changed row')
            mark('wrong_fence_release_rejected', **status)
            require(protected() == protection, 'financial or authorization state changed')
            mark('claim_persisted_and_live_duplicate_wrong_fence_rejected',
                 claimedRefundRowSha256=refund_hash(claimed), durableActor=1,
                 responseLeaseTokenSha256=hashlib.sha256(lease.encode()).hexdigest(),
                 databaseLeaseTokenSha256=hashlib.sha256(claimed['payout_lease_token'].encode()).hexdigest(),
                 liveLeaseChecked=True, **claim_status)
            require(time.monotonic() - logged_in_at < 120, 'session fixture too old before fault')
            pause_intent = True; mark('exact_owned_redis_pause_intent')
            command(DOCKER + ['pause', redis])
            require(json.loads(command(DOCKER + ['inspect', redis]))[0]['State']['Paused'], 'Redis not paused')
            mark('fault_release_request_intent', originalActor=1, originalRefundId=20)
            status, _ = http('/business/refund/manual/release', {'refundId': 20, 'leaseToken': lease}, token)
            assert_auth_failure(status)
            require(time.monotonic() - logged_in_at < 180, 'session fixture elapsed bound exceeded')
            require(refund() == claimed and protected() == protection, 'fault release changed durable state')
            mark('fault_release_http_settled_auth401_full_row_unchanged',
                 unchangedRefundRowSha256=refund_hash(refund()), **status)
            command(DOCKER + ['unpause', redis]); pause_intent = False
            require(command(DOCKER + ['exec', redis, 'redis-cli', 'PING']).strip() == b'PONG', 'Redis not recovered')
            status, body = http('/getInfo', token=token)
            require(status['businessCode'] == 200 and body.get('user', {}).get('userId') == 1, 'original session not recovered')
            mark('recovered_original_actor_get_info', actor=1, **status)
            mark('recovery_original_release_request_intent')
            status, _ = http('/business/refund/manual/release', {'refundId': 20, 'leaseToken': lease}, token)
            require(status['businessCode'] == 200, 'original release failed or unknown; no retry')
            released = refund(); assert_refund_transition(claimed, released, LEASE_COLUMNS)
            require(all(released[k] is None for k in LEASE_COLUMNS - {'update_time'}), 'lease not cleared')
            mark('original_release_http_settled', releasedRefundRowSha256=refund_hash(released), **status)
            status, _ = http('/business/refund/manual/release', {'refundId': 20, 'leaseToken': lease}, token)
            require(status['businessCode'] != 200 and refund() == released, 'replayed release changed row')
            mark('replayed_release_rejected', **status)
            require(protected() == protection, 'financial or permission state changed')
            mark('original_actor_released_and_replay_rejected', releasedRefundRowSha256=refund_hash(released),
                 unchangedFinancialAndPermissionHashes=True)
            after = json.loads(command(DOCKER + ['inspect', app, mysql, redis]))
            report['finalStateBeforeOuterCleanup'] = [{k: o['State'][k] for k in ('Pid', 'Running', 'Paused', 'OOMKilled')} for o in after]
            require(report['finalStateBeforeOuterCleanup'] == states,
                    'process restart/dependency loss/OOM')
            report.update(result='PASS_AUTHENTICATED_REFUND_LEASE_REDIS_RECOVERY', protected=protection,
                          zeroRowsAreOnlyNoNewRowsNotRealPaymentEvidence=True)
        except BaseException as exc:
            report.update(result='FAIL_OR_UNKNOWN_NO_RETRY', failure={'type': type(exc).__name__,
                          'reason': redact(str(exc), SECRET_VALUES)})
            raise
        finally:
            try:
                if report['result'] != 'PASS_AUTHENTICATED_REFUND_LEASE_REDIS_RECOVERY':
                    current_app = json.loads(command(DOCKER + ['inspect', app]))[0]
                    require(current_app['Id'] == app and current_app['Name'] == '/' + name + '-app'
                            and current_app['Config'].get('Labels', {}).get('chengyin.auth-recovery') == name,
                            'failed request application ownership unknown')
                    if current_app['State']['Running']:
                        command(DOCKER + ['stop', '--time', '10', app], timeout=25)
                    require(not json.loads(command(DOCKER + ['inspect', app]))[0]['State']['Running'],
                            'failed request application not fenced')
                    report['unsettledRequestFencedBeforeRedisRecovery'] = True
                if pause_intent:
                    current = json.loads(command(DOCKER + ['inspect', redis]))[0]
                    require(current['Id'] == redis and current['Name'] == '/' + name + '-redis', 'cleanup ownership unknown')
                    if current['State']['Paused']:
                        command(DOCKER + ['unpause', redis])
                    require(command(DOCKER + ['exec', redis, 'redis-cli', 'PING']).strip() == b'PONG', 'cleanup Redis unknown')
                report['finalRefundRowSha256'] = hashlib.sha256(json.dumps(refund(), default=str, sort_keys=True).encode()).hexdigest() if any(e['stage'] == 'claim_request_intent' for e in report['events']) else None
                report['protectedAtSettlement'] = protected()
            except BaseException as exc:
                report.update(result='FAIL_CLEANUP_OR_READBACK_UNKNOWN', cleanupFailureType=type(exc).__name__)
                raise
            finally:
                write_report(out, 'authenticated-redis-recovery.json', report)


def main():
    validate_execution_env(os.environ)
    if sys.argv[1:] == ['--check-execution-only']:
        return
    mode, name, schema, output, private_file, port = sys.argv[1:]
    require(re.fullmatch(r'cy-auth-[0-9a-f]{32}', name) is not None
            and schema == 'cy_e2e_auth_' + name[8:], 'fixed fixture scope required')
    require(mode in ('mysql-ready', 'prepare', 'exercise', 'cleanup', 'restore-mysql-ready', 'restore-runtime-user'), 'unknown fixture phase')
    if mode in ('restore-mysql-ready', 'restore-runtime-user'):
        require(os.environ.get('CHENGYIN_CI_RESTORE_HTTP_STARTUP') == 'true'
                and os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'restore readiness not admitted')
    require(command(DOCKER + ['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).strip()
            == b'unix:///var/run/docker.sock', 'not local hosted daemon')
    SECRET_VALUES.extend([os.environ['CHENGYIN_AUTH_FIXTURE_DB_PASSWORD'], 'synthetic-local-only'])
    out = Path(output); require(out.is_dir(), 'evidence directory missing')
    private = Path(private_file)
    try:
        if mode in ('prepare', 'exercise'):
            baseline = json.loads((out / 'schema-summary.json').read_text())['mysqlReadiness']
            require(baseline['result'] == 'OWNED_MYSQL_TCP_READY', 'native MySQL readiness missing')
            with mysql_relay(name, baseline) as relay_port:
                {'prepare': prepare, 'exercise': exercise}[mode](name, schema, out, private, relay_port)
        elif mode == 'restore-mysql-ready':
            mysql_ready(name, schema, out, private, port, suffix='restore-mysql')
        elif mode == 'restore-runtime-user':
            prepare_restore_runtime_user(name, schema, out, private, port)
        else:
            {'mysql-ready': mysql_ready, 'cleanup': cleanup}[mode](name, schema, out, private, port)
    except BaseException as exc:
        failure = record_phase_failure(out, mode, exc)
        print(json.dumps(failure))
        raise SystemExit(1)


if __name__ == '__main__':
    main()
