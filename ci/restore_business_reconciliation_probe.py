#!/usr/bin/env python3
"""Fixed hosted payment restore drill. SOURCE ONLY until the coordinator wires owned resources.

No provider HTTP, repair, dispatch, arbitrary SQL/command/profile, container launcher or READY transition.
Canonical backup/restore owns ALL dump/manifest/target verification; it is never patched here.
"""
from __future__ import annotations

import argparse
import configparser
import datetime as dt
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import select
import signal
import subprocess
import sys
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CHILD = 'com.chengyinhub.business.service.RestoreBusinessReconciliationFixture'
MYSQL_IMAGE = 'mysql:8.0.45@sha256:4af1f8815716546f5b12410f7621f37f93db8dd11a184706ef59111930b8c2ff'
CASES = {'historical_paid', 'late_success', 'lost_success', 'not_sent', 'unknown'}
TABLES = ('cms_registration', 'wx_pay_order_intent', 'wx_verified_payment_fact',
          'cms_registration_payment_fact', 'ums_member', 'ums_member_points_detail',
          'ums_member_balance_detail', 'refund_application', 'cms_registration_scan',
          'cms_activity', 'oms_ticket', 'settlement_rule', 'settlement_claim', 'settlement_detail')
HIGH_IDS = {'cms_registration': 'id',
            'wx_verified_payment_fact': 'id', 'ums_member_points_detail': 'id',
            'settlement_rule': 'id', 'settlement_claim': 'id', 'settlement_detail': 'id'}
CHILD_STAGES = {'ADMISSION', 'SOURCE_IDENTITY', 'SOURCE_SCHEMA', 'SOURCE_RESET', 'SOURCE_ASSEMBLY',
                'HISTORICAL_PAYMENT', 'SOURCE_TIMELINE', 'BACKUP_BOUNDARY', 'AFTER_BACKUP', 'FAULT_BOUNDARY', 'OWNER_OBSERVATION'}
CHILD_CAUSES = {'UNKNOWN', 'java.lang.IllegalArgumentException', 'java.lang.IllegalStateException',
    'java.lang.NullPointerException', 'java.lang.AssertionError', 'java.lang.ClassNotFoundException',
    'java.lang.NoClassDefFoundError', 'java.lang.ExceptionInInitializerError', 'java.lang.reflect.InvocationTargetException',
    'java.io.IOException', 'java.nio.file.NoSuchFileException', 'java.sql.SQLException',
    'java.sql.SQLSyntaxErrorException', 'java.sql.SQLIntegrityConstraintViolationException',
    'java.sql.SQLNonTransientConnectionException', 'java.sql.SQLTransientConnectionException',
    'org.opentest4j.AssertionFailedError', 'org.springframework.jdbc.BadSqlGrammarException',
    'org.springframework.jdbc.CannotGetJdbcConnectionException', 'org.springframework.jdbc.datasource.init.ScriptStatementFailedException',
    'org.springframework.dao.DataIntegrityViolationException', 'org.springframework.beans.factory.BeanCreationException',
    'com.chengyinhub.common.exception.ServiceException'}
CHILD_SQL_STATES = {'08001', '08003', '08004', '08006', '08S01', '28000', '42000', '42S01', '42S02', '42S21',
                    '42S22', '23000', '22001', '22003', 'HY000', 'HY001', 'HYT00', 'HYT01', '40001'}
CHILD_ORIGINS = {CHILD, 'com.chengyinhub.business.service.RegistrationFundsMybatisIntegrationTest',
    'com.chengyinhub.business.service.impl.RestoreWithdrawalReconciliationFixture',
    'com.chengyinhub.business.service.impl.RestoreRefundReconciliationFixture',
    'com.chengyinhub.business.service.impl.RefundRetryAndRemainderMySqlIT',
    'com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl',
    'com.chengyinhub.business.service.impl.SettlementEntryServiceImpl',
    'com.chengyinhub.business.service.impl.SettlementClaimServiceImpl',
    'com.chengyinhub.business.service.impl.RegistrationTopicFence',
    'com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService',
    'com.chengyinhub.business.service.PaymentCallbackInboxStubs',
    'com.chengyinhub.business.service.impl.FundsRecoveryProcessProbe$Store',
    'com.chengyinhub.business.service.ai.RestoreVoiceReconciliationFixture'}
CALLBACK_REASONS = {'UNKNOWN', 'MUTATION_EXCEPTION', 'PAYLOAD_MISSING', 'REQUIRED_FIELD_MISSING', 'CALLBACK_INSIDE_TRANSACTION',
    'VERIFIED_FACT_MISSING_OR_CONFLICTING', 'AMOUNT_MISMATCH', 'INBOX_IDENTITY_CONFLICT', 'VERIFIED_CASH_RECOVERY_NOT_COMPLETED',
    'CALLBACK_NOT_COMPLETED', 'PAYMENT_STATE_CONFLICT', 'PAYMENT_ATTEMPT_CONFLICT', 'VERIFIED_ORIGINAL_ORDER_MISMATCH',
    'CLOSED_REGISTRATION_ORIGINAL_PAYMENT_RECOVERY_REQUIRED', 'TOPIC_CLOSED_ORIGINAL_PAYMENT_RECOVERY_REQUIRED',
    'ORDER_DISAPPEARED', 'FAILURE_STATE_CONFLICT', 'REFUND_REQUIRES_VERIFIED_ORIGINAL_REFUND'}
HOST_STAGES = {'SOURCE_START','SOURCE_BACKUP_BOUNDARY','SOURCE_CAPTURE','CANONICAL_BACKUP',
    'SOURCE_FAULT_BOUNDARY','FAULT_CAPTURE','SOURCE_STOP','TARGET_SCHEMA_CREATE','CANONICAL_RESTORE',
    'RESTORED_CAPTURE','DOMAIN_EVALUATE','OWNER_BEFORE','OWNER_BOOT','OWNER_EVIDENCE','OWNER_AFTER',
    'OWNER_VERIFY','FINAL_JOURNAL','RESULT_PROJECTION'}
HOST_STEPS = {'SCHEMA_ABSENCE','LAUNCH','WAIT_BOUNDARY','TABLE_ROWS','HIGH_WATER','JOURNAL',
    'SAVE_PRIVATE','CANONICAL_CALL','SIGNAL_CHILD','CHILD_EXIT','DATABASE_OPTIONS','CREATE_SCHEMA',
    'EVALUATE','READ_EVIDENCE','VERIFY','PROJECT'}
HOST_REASON_CODES = {
    'host': {'target CREATE failed; never adopt existing schema':'TARGET_CREATE_REJECTED',
        'missing typed table definition':'TYPED_TABLE_METADATA_MISSING',
        'typed row shape changed':'TYPED_ROW_SHAPE_CHANGED',
        'unregistered table':'TABLE_NOT_REGISTERED',
        'source fixture database options unavailable':'DATABASE_OPTIONS_UNAVAILABLE',
        'source fixture database options unsupported':'DATABASE_OPTIONS_UNSUPPORTED',
        'source fixture failed':'SOURCE_CHILD_NONZERO',
        'actual owner boot/readback failed':'OWNER_CHILD_NONZERO',
        'owner process identity':'OWNER_PROCESS_IDENTITY_MISMATCH'},
    'canonical': {
        'MySQL command failed; no backup or restore is considered verified':'CANONICAL_MYSQL_REJECTED',
        'Restore database definition differs; create the empty target with matching source options':'CANONICAL_DATABASE_DEFINITION_MISMATCH',
        'Restore target must be empty; existing data is never dropped':'CANONICAL_TARGET_NOT_EMPTY',
        'Restore requires a different MySQL instance of the same version':'CANONICAL_UUID_OR_VERSION_MISMATCH',
        'Backup hash differs from its receipt':'CANONICAL_BACKUP_HASH_MISMATCH',
        'Restore failed verification; isolate target for investigation, do not retry over it':'CANONICAL_POSTIMPORT_MISMATCH',
        'The isolated restore server must have its event scheduler disabled':'CANONICAL_SCHEDULER_NOT_DISABLED',
        'Unsupported SQL mode for verifiable table definition quoting':'CANONICAL_SQL_MODE_UNSUPPORTED',
        'Column charset differs from actual metadata':'CANONICAL_COLUMN_CHARSET_MISMATCH',
        'Column collation differs from actual metadata':'CANONICAL_COLUMN_COLLATION_MISMATCH'}}
HOST_FUNCTIONS={'host':{'run','require','table_rows','capture','journal','reconcile','activity_receivables',
                      'private_file','save','wait_phase','database_create_sql'},
                'canonical':{'restore','backup','identify','mysql','snapshot','definition_hash',
                             'table_charset_definition','manifest_definitions','batch_cell','object_rows'},
                'domain':{'require','evaluate','rows','journal','voice_versions','require_original_receipts','verify_owner',
                          'verify_saved_refund_observation'}}


def safe_host_failure(row):
    """The outer fixed host may copy ONLY this finite JSON projection, after its original scope/hash checks."""
    required={'stage','step','reasonCode','locations'};optional={'side','ownerPhase','tableIndex','idIndex'}
    if type(row) is not dict or not required<=set(row)<=required|optional:return None
    codes={code for group in HOST_REASON_CODES.values() for code in group.values()}|set(ChildBoundaryFailure.REASONS)|{'UNKNOWN_PRIVATE_FAILURE'}
    if any(type(row[k]) is not str for k in ('stage','step','reasonCode')):return None
    if row['stage'] not in HOST_STAGES|{'UNKNOWN'} or row['step'] not in HOST_STEPS|{'UNKNOWN'} or row['reasonCode'] not in codes:return None
    if type(row['locations']) is not list or len(row['locations'])>8:return None
    for location in row['locations']:
        if (type(location) is not dict or set(location)!={'source','function','line'}
                or type(location['source']) is not str or type(location['function']) is not str
                or location['function'] not in HOST_FUNCTIONS.get(location['source'],set())
                or type(location['line']) is not int or not 0<location['line']<=100000):return None
    if row['reasonCode']!='UNKNOWN_PRIVATE_FAILURE' and not row['locations']:return None
    if 'side' in row and (type(row['side']) is not str or row['side'] not in ('source','target')):return None
    if 'ownerPhase' in row and (type(row['ownerPhase']) is not str or row['ownerPhase'] not in (
            'RESTORED_GUARD','RESTORED_OWNER','RESTORED_INVALID_OWNER','RESTORED_EMPTY_OWNER','NORMAL_READBACK')):return None
    if any(type(row[k]) is not int or not 0<=row[k]<256 for k in ('tableIndex','idIndex') if k in row):return None
    return dict(row,locations=[dict(location) for location in row['locations']])


def host_failure(failure, progress):
    """Finite source locations and reviewed literal predicates only; no traceback formatting/locals/message."""
    progress=progress if type(progress) is dict else {}
    observed={'stage':progress.get('stage') if type(progress.get('stage')) is str and progress['stage'] in HOST_STAGES else 'UNKNOWN',
              'step':progress.get('step') if type(progress.get('step')) is str and progress['step'] in HOST_STEPS else 'UNKNOWN',
              'reasonCode':'UNKNOWN_PRIVATE_FAILURE','locations':[]}
    for key in ('side','ownerPhase'):
        allowed={'side':{'source','target'},'ownerPhase':{'RESTORED_GUARD','RESTORED_OWNER','RESTORED_INVALID_OWNER','RESTORED_EMPTY_OWNER','NORMAL_READBACK'}}[key]
        if type(progress.get(key)) is str and progress[key] in allowed:observed[key]=progress[key]
    for key in ('tableIndex','idIndex'):
        if type(progress.get(key)) is int and 0<=progress[key]<256:observed[key]=progress[key]
    trusted_root=Path(__file__).resolve().parents[1]
    sources={str(Path(__file__).resolve()):('host',HOST_FUNCTIONS['host']),
             str((trusted_root/'scripts/db-backup-restore.py').resolve()):('canonical',HOST_FUNCTIONS['canonical']),
             str((trusted_root/'ci/restore_business_domain_facts.py').resolve()):('domain',HOST_FUNCTIONS['domain'])}
    trace=failure.__traceback__;last=None
    while trace is not None:
        frame=trace.tb_frame;entry=sources.get(str(Path(frame.f_code.co_filename).resolve()))
        last=(entry,frame.f_code.co_name)
        if entry and frame.f_code.co_name in entry[1] and 0<trace.tb_lineno<=100000:
            observed['locations'].append({'source':entry[0],'function':frame.f_code.co_name,'line':trace.tb_lineno})
        trace=trace.tb_next
    observed['locations']=observed['locations'][-8:]
    if type(failure) is ValueError and last and last[0] and last[1] in last[0][1] and len(failure.args)==1 and type(failure.args[0]) is str:
        observed['reasonCode']=HOST_REASON_CODES.get(last[0][0],{}).get(failure.args[0],'UNKNOWN_PRIVATE_FAILURE')
    if isinstance(failure,ChildBoundaryFailure):observed['reasonCode']=failure.code
    return observed


def database_create_sql(tool, source, database):
    # Fixed fixture options, read from the original source rather than guessed from the target default.
    raw=tool.mysql(source,'SELECT JSON_ARRAY(DEFAULT_CHARACTER_SET_NAME,DEFAULT_COLLATION_NAME) '
                   'FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=DATABASE()')
    rows=[json.loads(line) for line in raw.splitlines() if line]
    require(len(rows)==1 and type(rows[0]) is list and len(rows[0])==2,'source fixture database options unavailable')
    charset,collation=rows[0]
    require(charset=='utf8mb4' and type(collation) is str and re.fullmatch('utf8mb4_[a-z0-9_]{1,55}',collation),
            'source fixture database options unsupported')
    return 'CREATE DATABASE '+tool.quoted(database)+' CHARACTER SET utf8mb4 COLLATE '+collation


class ChildBoundaryFailure(ValueError):
    REASONS = {'CHILD_EOF_BEFORE_BOUNDARY': 'fixture child exited before fixed boundary',
               'CHILD_EXIT_BEFORE_BOUNDARY': 'fixture child failed',
               'CHILD_OUTPUT_BUDGET': 'fixture output exceeds fixed line budget',
               'CHILD_BOUNDARY_TIMEOUT': 'fixed boundary deadline exceeded; no restart or replay'}
    def __init__(self, code):
        self.code = code
        super().__init__(self.REASONS[code])


def child_failure(path, expected):
    """Bounded typed evidence only. Raw stderr/messages/paths never enter a public receipt."""
    evidence = {'diagnosticState': 'UNKNOWN', 'stderrBytes': None, 'stderrSha256': None}
    try:
        path = private_file(path)
        size = path.stat().st_size; evidence['stderrBytes'] = size
        if size > 1048576:
            evidence['diagnosticState'] = 'EXCEEDS_FIXED_BUDGET'; return evidence
        with path.open('rb') as stream: raw = stream.read(1048577)
        if len(raw) != size or path.stat().st_size != size:
            evidence['diagnosticState'] = 'UNSTABLE_OR_EXCESS_STDERR'; return evidence
        evidence['stderrSha256'] = sha(raw)
        prefix = b'RESTORE_CHILD_FAILURE_V1:'
        rows = [line[len(prefix):] for line in raw.splitlines() if line.startswith(prefix)]
        if not rows:
            evidence['diagnosticState'] = 'NO_TYPED_FAILURE'
            headers = {('Error: Could not find or load main class ' + expected['childClass']).encode(): 'JVM_MAIN_CLASS_NOT_FOUND',
                       ('Error: Unable to initialize main class ' + expected['childClass']).encode(): 'JVM_MAIN_CLASS_INITIALIZATION'}
            matches = {code for line in raw.splitlines() for header, code in headers.items() if line == header}
            if len(matches) == 1:
                evidence.update(diagnosticState='REVIEWED_LAUNCHER_FAILURE', launcherCategory=matches.pop())
            return evidence
        require(len(rows) == 1 and len(rows[0]) <= 8192, 'one bounded typed failure required')
        row = json.loads(rows[0])
        base_keys = {'protocol', 'stage', 'sourceSha', 'scope', 'boot', 'cohort', 'causeTypes', 'sqlState', 'sqlErrorCode', 'origin'}
        require(set(row) in (base_keys, base_keys | {'callback'}, base_keys | {'voice'}),
                'typed failure shape differs')
        require(row['protocol'] == 'RESTORE_CHILD_FAILURE_V1' and row['stage'] in CHILD_STAGES
                and all(row[key] == expected[key] for key in ('sourceSha', 'scope', 'boot', 'cohort')),
                'typed failure belongs to another original child')
        require(isinstance(row['causeTypes'], list) and 1 <= len(row['causeTypes']) <= 8
                and all(type(value) is str and value in CHILD_CAUSES for value in row['causeTypes']), 'unreviewed failure cause')
        require(row['sqlState'] is None or (type(row['sqlState']) is str and row['sqlState'] in CHILD_SQL_STATES),
                'invalid SQL state category')
        require(row['sqlErrorCode'] is None or (row['sqlState'] is not None and type(row['sqlErrorCode']) is int
                and 0 <= row['sqlErrorCode'] <= 65535), 'invalid SQL error category')
        origin = row['origin']
        require(origin is None or (type(origin) is dict and set(origin) == {'class', 'line'}
                and type(origin['class']) is str and origin['class'] in CHILD_ORIGINS
                and type(origin['line']) is int and 0 < origin['line'] <= 100000), 'unreviewed source origin')
        if 'callback' in row:
            callback = row['callback']
            require(expected['childClass'] == CHILD and expected['cohort'] == 'payment-gap'
                    and row['stage'] in ('HISTORICAL_PAYMENT', 'AFTER_BACKUP')
                    and type(callback) is dict and set(callback) == {'result', 'reason'}
                    and type(callback['result']) is str and callback['result'] in ('FAIL', 'NULL', 'OTHER')
                    and type(callback['reason']) is str and callback['reason'] in CALLBACK_REASONS,
                    'unreviewed callback rejection category')
        if 'voice' in row:
            voice = row['voice']
            require(expected['childClass'] == 'com.chengyinhub.business.service.ai.RestoreVoiceReconciliationFixture'
                    and expected['cohort'] in ('voice-complete', 'voice-gap')
                    and type(voice) is dict and set(voice) == {
                        'operation', 'expected', 'intentState', 'profileState', 'selection', 'providerStep'},
                    'unreviewed voice observation shape')
            allowed = {
                'operation': ('SOURCE_KNOWN', 'SOURCE_UNKNOWN', 'OWNER_KNOWN', 'NORMAL_REPLAY', 'OWNER_UNKNOWN'),
                'expected': ('READY', 'UNKNOWN'),
                'intentState': ('PREPARED', 'IN_FLIGHT', 'UNKNOWN', 'NOT_SENT', 'PENDING', 'READY', 'MISSING', 'UNREVIEWED'),
                'profileState': ('EMPTY', 'PENDING', 'READY', 'FAILED', 'MISSING', 'UNREVIEWED'),
                'selection': ('SELECTED', 'SUPERSEDED', 'MISSING'),
                'providerStep': ('NONE', 'INPUT', 'SUBMIT_ROW', 'SUBMIT_JOURNAL', 'QUERY_ROW', 'QUERY_JOURNAL'),
            }
            require(all(type(voice[key]) is str and voice[key] in values for key, values in allowed.items()),
                    'unreviewed voice observation enum')
            source = voice['operation'].startswith('SOURCE_')
            require((source and expected.get('phase') == 'SOURCE' and row['stage'] == 'SOURCE_TIMELINE')
                    or (not source and expected.get('phase') in ('RESTORED_OWNER', 'RESTORED_INVALID_OWNER',
                        'RESTORED_EMPTY_OWNER', 'NORMAL_READBACK') and row['stage'] == 'OWNER_OBSERVATION'),
                    'voice observation phase mismatch')
            require(voice['operation'] != 'NORMAL_REPLAY' or expected.get('phase') == 'NORMAL_READBACK',
                    'normal voice replay belongs to normal readback only')
        evidence.update(diagnosticState='TYPED_FAILURE', javaFailure=row)
    except BaseException:
        evidence['diagnosticState'] = 'UNREADABLE_OR_UNTRUSTED'
    return evidence


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def sha(value):
    return hashlib.sha256(value).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def classpath_evidence(entries, tool):
    result = []
    for entry in entries:
        path = Path(entry)
        if path.is_dir():
            files = sorted(p for p in path.rglob('*') if p.is_file())
            proof = [(str(p.relative_to(path)), tool.digest(p)) for p in files]
            result.append({'kind': 'compiled-directory', 'files': len(files), 'sha256': sha(canonical(proof))})
        else:
            result.append({'kind': 'dependency-jar', 'sha256': tool.digest(path)})
    return result # Paths and machine-local dependency roots never enter public evidence.


def private_file(path):
    path = Path(path)
    require(not path.is_symlink() and path.is_file() and not path.stat().st_mode & 0o077,
            'private regular owner-only file required')
    return path.resolve()


def save(path, data):
    # Evidence is exclusive-create; no overwriting the first failure or a previous drill.
    with Path(path).open('x', encoding='utf-8') as out:
        os.chmod(path, 0o600)
        json.dump(data, out, ensure_ascii=False, sort_keys=True, indent=2)
        out.flush()
        os.fsync(out.fileno())


def hosted(env):
    for key, expected in {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted',
                          'RUNNER_OS': 'Linux', 'GITHUB_REPOSITORY': 'liboyang42-cpu/hengyin',
                          'GITHUB_JOB': 'isolated-restore-business-reconciliation',
                          'CHENGYIN_CI_RESTORE_BUSINESS': 'true'}.items():
        require(env.get(key) == expected, 'wrong isolated hosted scope: ' + key)
    require(env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'explicit manual hosted drill only')
    require(not env.get('DOCKER_HOST') and not env.get('DOCKER_CONTEXT'), 'Docker override forbidden')
    require(re.fullmatch('[a-f0-9]{40}', env.get('GITHUB_SHA', '')), 'full source SHA required')
    require(all(re.fullmatch('[1-9][0-9]*',env.get(key,'')) for key in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT')),
            'same-run/attempt identity required')


def mysql_options(path, port):
    path = private_file(path)
    cfg = configparser.ConfigParser(interpolation=None)
    cfg.read(path)
    require(set(cfg.sections()) == {'client'}, 'defaults may only contain a client section')
    values = dict(cfg['client'])
    require(set(values) == {'host', 'port', 'user', 'password', 'protocol'}, 'fixed defaults fields required')
    require(values['host'] == '127.0.0.1' and values['protocol'].lower() == 'tcp'
            and int(values['port']) == port and values['user'] == 'root'
            and re.fullmatch('[A-Za-z0-9_-]{24,64}', values['password']), 'owned fixture client only')
    return values


def validate_resources(resources, inspect, network):
    scope = resources.get('scope', '')
    require(re.fullmatch('[a-f0-9]{32}', scope), 'scope UUID required')
    name = 'cy-restore-' + scope
    require(resources['schema'] == 'cy_restore_' + scope + '_funds_core_it', 'foreign schema')
    require(network['Name'] == name and network['Internal'] is True
            and network['Labels'].get('chengyin.restore-business') == scope, 'foreign network')
    require(len(inspect) == 2, 'two isolated MySQL instances required')
    ports, ids = set(), set()
    for side, obj in zip(('source', 'target'), inspect):
        target = resources[side]
        require(re.fullmatch('[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', target['uuid']),
                'exact MySQL UUID required')
        require(obj['Id'] == target['cid'] and re.fullmatch('[a-f0-9]{64}', obj['Id'])
                and obj['Name'] == '/' + name + '-' + side, 'foreign exact container identity')
        require(obj['Config']['Image'] == MYSQL_IMAGE
                and obj['Config']['Labels'].get('chengyin.restore-business') == scope, 'unapproved image/label')
        require(obj['State']['Running'] and not obj['State']['Paused'] and not obj['State']['OOMKilled'], 'unhealthy MySQL')
        require(obj['HostConfig']['Memory'] == 1073741824 and obj['HostConfig']['NanoCpus'] == 1000000000,
                'fixed one-CPU one-GiB budget per MySQL')
        require(set(obj['NetworkSettings']['Networks']) == {name}
                and obj['NetworkSettings']['Networks'][name]['NetworkID'] == network['Id'], 'foreign network attachment')
        binding = obj['NetworkSettings']['Ports'].get('3306/tcp')
        if 'transport' in target:
            require(target['transport'] == 'OWNED_MYSQL_LOOPBACK_RELAY', 'unknown MySQL transport')
            module('restore_owned_mysql_host', 'ci/restore_business_reconciliation_host.py').validate_relay_descriptor(
                scope, side, target, network, obj)
        else:
            require(binding == [{'HostIp': '127.0.0.1', 'HostPort': str(target['port'])}], 'loopback owned port only')
        require(all(key == '3306/tcp' or value is None for key, value in obj['NetworkSettings']['Ports'].items()),
                'no other published container ports')
        require(1024 < int(target['port']) < 65536, 'invalid fixture port')
        ports.add(int(target['port'])); ids.add(obj['Id'])
    require(len(ports) == len(ids) == 2, 'source/target must be distinct')


def journal(path, scope):
    records, previous, commands, states, submits = [], '0' * 64, {}, {}, {}
    allowed = {None: {'SUBMIT', 'NOT_SENT'}, 'SUBMIT': {'ACCEPTED', 'UNKNOWN', 'SUCCESS'},
               'ACCEPTED': {'SUCCESS'}, 'UNKNOWN': set(), 'SUCCESS': set(), 'NOT_SENT': set()}
    raw = private_file(path).read_bytes()
    require(raw.endswith(b'\n') and len(raw) <= 1024 * 1024, 'provider journal incomplete or exceeds budget')
    for number, line in enumerate(raw.splitlines(), 1):
        outer = json.loads(line)
        require(set(outer) == {'payload', 'sha256'} and sha(outer['payload'].encode()) == outer['sha256'], 'provider hash mismatch')
        row = json.loads(outer['payload'])
        require(row['sequence'] == number and row['previous'] == previous, 'provider journal gap/truncation')
        previous = outer['sha256']
        command = row['command']; case = command['case']; state = row['state']
        require(set(command) == {'case', 'scope', 'domain', 'bizId', 'attemptNo', 'businessNo', 'wxNo',
                'fingerprint', 'totalFen', 'providerId', 'mchid', 'appid', 'currency', 'origin'},
                'original composite payment command shape differs')
        require(case in CASES and command['scope'] == scope and command['domain'] == 'REGISTRATION', 'foreign provider identity')
        require(command['mchid'] == 'mch-funds-it' and command['appid'] == 'wx-funds-it'
                and command['currency'] == 'CNY' and type(command['totalFen']) is int and command['totalFen'] > 0,
                'provider original money/authority invalid')
        require(type(command['bizId']) is int and command['bizId'] > 0, 'original business ID missing')
        if case == 'historical_paid':
            require(command['fingerprint'] is None and command['attemptNo'] is None
                    and command['origin'] == 'HISTORICAL_MASTER_ONLY:T20260724PAY:original-funds-fixture'
                    and command['bizId'] == 11 and command['businessNo'] == command['wxNo'] == 'T20260724PAY', 'legacy origin cannot be inferred')
        else:
            require(command['origin'] == 'FROZEN_ORIGINAL' and type(command['attemptNo']) is int
                    and 0 < command['attemptNo'] <= 2147483647
                    and type(command['fingerprint']) is str
                    and re.fullmatch('[a-f0-9]{64}', command['fingerprint']), 'frozen original missing')
        require(all(type(command[key]) is str and re.fullmatch('[A-Za-z0-9_-]{1,64}', command[key])
                    for key in ('businessNo', 'wxNo', 'providerId')), 'original payment number missing')
        require(case not in commands or commands[case] == command, 'provider original identity/amount changed')
        require(state in allowed.get(states.get(case), set()), 'duplicate submit or invalid provider transition')
        instant = dt.datetime.fromisoformat(row['at'].replace('Z', '+00:00'))
        require(instant.tzinfo is not None, 'provider timestamp not UTC-aware')
        if records:
            require(instant >= dt.datetime.fromisoformat(records[-1]['at'].replace('Z', '+00:00')), 'provider time regressed')
        commands[case] = command; states[case] = state
        submits[case] = submits.get(case, 0) + int(state == 'SUBMIT')
        records.append(row)
    return {'records': records, 'commands': commands, 'states': states, 'submits': submits, 'head': previous, 'sha256': sha(raw)}


def table_rows(tool, args, table):
    fixed_domains = module('restore_fixed_domain_tables', 'ci/restore_business_domain_facts.py')
    require(table in TABLES or table in fixed_domains.ALL_TABLES, 'unregistered table')
    raw_columns = tool.mysql(args, "SELECT JSON_ARRAY(COLUMN_NAME,COLUMN_TYPE) FROM information_schema.COLUMNS"
                             " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + table + "' ORDER BY ORDINAL_POSITION")
    columns = [json.loads(line) for line in raw_columns.splitlines() if line]
    require(columns and all(re.fullmatch('[a-zA-Z0-9_]+', c[0]) for c in columns), 'missing typed table definition')
    # Fixed table allowlist; metadata supplies column names only, never a query supplied by the runner.
    expression = ','.join(('HEX(' + tool.quoted(c[0]) + ')' if re.match(r'^(?:binary|varbinary|\w*blob|bit)\b', c[1], re.I)
                          else 'CAST(' + tool.quoted(c[0]) + ' AS CHAR CHARACTER SET utf8mb4)') for c in columns)
    raw = tool.mysql(args, 'SELECT JSON_ARRAY(' + expression + ') FROM ' + tool.quoted(table))
    rows = [json.loads(line) for line in raw.splitlines() if line]
    return {'columns': columns, 'rows': sorted(rows, key=lambda row: canonical(row))}


def capture(tool, args, tables=TABLES, high_ids=HIGH_IDS, *, progress=None):
    result = {'tables': {}, 'highWater': {}}
    for index,table in enumerate(tables):
        if progress is not None:progress.update(step='TABLE_ROWS',tableIndex=index);progress.pop('idIndex',None)
        result['tables'][table]=table_rows(tool,args,table)
    for index,(table, key) in enumerate(high_ids.items()):
        if progress is not None:progress.update(step='HIGH_WATER',idIndex=index);progress.pop('tableIndex',None)
        row = json.loads(tool.mysql(args, "SET SESSION information_schema_stats_expiry=0; SELECT JSON_ARRAY(COALESCE((SELECT MAX(" + tool.quoted(key) + ") FROM "
                + tool.quoted(table) + "),0),AUTO_INCREMENT) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"
                " AND TABLE_NAME='" + table + "'"))
        result['highWater'][table] = {'max': int(row[0]), 'next': None if row[1] is None else int(row[1])}
    return result


def dictionaries(snapshot, table):
    data = snapshot['tables'][table]
    names = [c[0] for c in data['columns']]
    require(len(names) == len(set(names)) and all(len(row) == len(names) for row in data['rows']), 'typed row shape changed')
    return [dict(zip(names, row)) for row in data['rows']]


def reconcile(backup, fault, restored, provider_before, provider_after):
    require(backup == restored, 'independent typed rows or AUTO_INCREMENT differ after canonical restore')
    require(set(provider_after['commands']) == CASES, 'all five original cases required')
    require(provider_after['records'][:len(provider_before['records'])] == provider_before['records'], 'provider history was rolled back/replaced')
    require(provider_after['states'] == {'historical_paid': 'SUCCESS', 'late_success': 'SUCCESS',
            'lost_success': 'SUCCESS', 'not_sent': 'NOT_SENT', 'unknown': 'UNKNOWN'}, 'wrong fixed timeline')
    require(provider_after['submits'] == {'historical_paid': 1, 'late_success': 1, 'lost_success': 1,
            'not_sent': 0, 'unknown': 1}, 'controlled provider duplicate or missing submit')
    differences = []
    for case in sorted(CASES):
        c = provider_after['commands'][case]
        local = [r for r in dictionaries(restored, 'cms_registration') if r['id'] == str(c['bizId'])]
        source = [r for r in dictionaries(fault, 'cms_registration') if r['id'] == str(c['bizId'])]
        require(len(source) == 1 and source[0]['registration_no'] == c['businessNo'], 'original source identity lost/changed')
        require(Decimal(source[0]['wechat_payment_amount']) * 100 == c['totalFen'], 'original business money differs')
        for candidate in local:
            require(candidate['registration_no'] == c['businessNo'], 'ID collision with another original business')
        consumed = [r for r in dictionaries(restored, 'cms_registration_payment_fact') if r['registration_id'] == str(c['bizId'])]
        source_consumed = [r for r in dictionaries(fault, 'cms_registration_payment_fact') if r['registration_id'] == str(c['bizId'])]
        for snapshot, receipts in ((restored, consumed), (fault, source_consumed)):
            for receipt in receipts:
                originals = [r for r in dictionaries(snapshot, 'wx_verified_payment_fact') if r['id'] == receipt['payment_fact_id']]
                require(len(originals) == 1, 'original receipt/reference mismatch')
                paid = originals[0]
                require(paid['fact_kind'] == 'PAYMENT' and paid['mchid'] == c['mchid'] and paid['appid'] == c['appid']
                        and paid['currency'] == c['currency'] and int(paid['total_fen']) == c['totalFen']
                        and paid['business_out_trade_no'] == c['businessNo'] and paid['wx_out_trade_no'] == c['wxNo']
                        and paid['transaction_id'] == c['providerId'] and paid['intent_request_hash'] == c['fingerprint'],
                        'provider original money/identity not consumed exactly')
        frozen = [r for r in dictionaries(restored, 'wx_pay_order_intent')
                  if r['biz_type'] == 'REGISTRATION' and r['biz_id'] == str(c['bizId'])]
        source_frozen = [r for r in dictionaries(fault, 'wx_pay_order_intent')
                         if r['biz_type'] == 'REGISTRATION' and r['biz_id'] == str(c['bizId'])]
        if case != 'historical_paid':
            require(len(source_frozen) == 1, 'missing original source frozen identity')
            for f in source_frozen + frozen:
                require(f['mchid'] == c['mchid'] and f['out_trade_no'] == c['wxNo']
                        and f['request_hash'] == c['fingerprint'] and f['business_out_trade_no'] == c['businessNo']
                        and f['appid'] == c['appid'] and f['trade_type'] == 'JSAPI' and f['currency'] == c['currency']
                        and int(f['attempt_no']) == c['attemptNo'] and int(f['total_fen']) == c['totalFen']
                        and f['closed_confirmed_at'] is None, 'original frozen request/amount differs')
        if case == 'historical_paid':
            require(len(local) == len(consumed) == 1 and not frozen and not source_frozen
                    and c['attemptNo'] is None and c['fingerprint'] is None
                    and local[0]['payment_status'] == '2', 'historical paid fact lost/rewritten')
            status = 'ORIGINAL_SUCCESS_RETAINED'
        elif case == 'lost_success':
            require(not local and not frozen and not consumed and len(source_consumed) == 1
                    and source[0]['payment_status'] == '2', 'lost whole-intent scenario was not actually produced')
            status = 'UNKNOWN_MISSING_WHOLE_ORIGINAL_MANUAL_BLOCK'
        else:
            require(len(local) == len(frozen) == 1 and not consumed and local[0]['payment_status'] == '1', 'unacknowledged original mutated')
            status = {'late_success': 'EXTERNAL_SUCCESS_LOCAL_UNAPPLIED_MANUAL_BLOCK',
                      'unknown': 'UNKNOWN_RETAIN_ORIGINAL_NO_RESEND',
                      'not_sent': 'CONTROLLED_NOT_SENT_RETAIN_ORIGINAL_NO_AUTOMATIC_DISPATCH'}[case]
        differences.append({'case': case, 'bizId': c['bizId'], 'mchid': c['mchid'], 'attemptNo': c['attemptNo'],
                            'businessNo': c['businessNo'], 'wxNo': c['wxNo'],
                            'fingerprint': c['fingerprint'], 'totalFen': c['totalFen'], 'status': status})
    risks = []
    for table in HIGH_IDS:
        external = fault['highWater'][table]['max']
        next_id = restored['highWater'][table]['next']
        if next_id is None or next_id <= external:
            risks.append({'table': table, 'restoreNext': next_id, 'preFaultMax': external,
                          'status': 'POSSIBLE_ID_REUSE_DO_NOT_ALLOCATE'})
    # A checked backup's sequence value is NOT permission to allocate lost externally used numbers.
    require(any(r['table'] == 'cms_registration' for r in risks), 'lost-ID high-water risk not observed')
    # This table has no AUTO_INCREMENT/id. Its retained per-business attempt sequence is the real reuse boundary.
    intent_water = []
    identity_fields = ('mchid', 'biz_type', 'biz_id', 'business_out_trade_no')
    source_intents = dictionaries(fault, 'wx_pay_order_intent')
    restored_intents = dictionaries(restored, 'wx_pay_order_intent')
    for identity in sorted({tuple(row[key] for key in identity_fields) for row in source_intents}):
        old = [row for row in source_intents if tuple(row[key] for key in identity_fields) == identity]
        retained = [row for row in restored_intents if tuple(row[key] for key in identity_fields) == identity]
        high = dict(zip(identity_fields, identity), preFaultAttemptMax=max(int(row['attempt_no']) for row in old),
                    restoreAttemptMax=max((int(row['attempt_no']) for row in retained), default=0))
        intent_water.append(high)
        if high['restoreAttemptMax'] < high['preFaultAttemptMax']:
            risks.append(dict(high, table='wx_pay_order_intent', status='POSSIBLE_ORIGINAL_ATTEMPT_REUSE_DO_NOT_ALLOCATE'))
    for snap in (backup, fault, restored):
        for receipt in dictionaries(snap, 'cms_registration_payment_fact'):
            originals = [r for r in dictionaries(snap, 'wx_verified_payment_fact') if r['id'] == receipt['payment_fact_id']]
            orders = [r for r in dictionaries(snap, 'cms_registration') if r['id'] == receipt['registration_id']]
            require(len(originals) == len(orders) == 1 and orders[0]['registration_no'] == receipt['registration_no']
                    and originals[0]['fact_hash'] == receipt['payment_fact_hash']
                    and originals[0]['transaction_id'] == receipt['transaction_id'], 'original receipt/reference mismatch')
        points = dictionaries(snap, 'ums_member_points_detail')
        require(points and all(any(m['id'] == p['member_id'] for m in dictionaries(snap, 'ums_member')) for p in points), 'real ledger/actor reference missing')
        running = Decimal('100') # Explicit original fixture opening points, never a substituted debit.
        for entry in sorted(points, key=lambda p: int(p['id'])):
            require(entry['member_id'] == '7' and Decimal(entry['current_points']) == running,
                    'committed points ledger original balance discontinuity')
            running += Decimal(entry['change_points'])
            require(Decimal(entry['after_points']) == running, 'committed points ledger amount mismatch')
            if entry['event_type'] == '3':
                require(any(r['id'] == entry['event_id'] for r in dictionaries(snap, 'cms_registration')),
                        'reward points reference a missing original registration')
        require(len(dictionaries(snap, 'ums_member')) == 1
                and Decimal(dictionaries(snap, 'ums_member')[0]['point']) == running,
                'committed member points and full ledger do not conserve points')
        for frozen in dictionaries(snap, 'wx_pay_order_intent'):
            require(any(r['id'] == frozen['biz_id'] and r['registration_no'] == frozen['business_out_trade_no']
                        for r in dictionaries(snap, 'cms_registration')), 'orphan original payment intent')
        require(not dictionaries(snap, 'ums_member_balance_detail') and not dictionaries(snap, 'refund_application'), 'fixture touched unowned cash/refund boundary')
        activity_receivables(snap)
    return {'drill': 'PAYMENT_RESTORE_DIFFERENCES_OBSERVED', 'candidateState': 'BLOCKED',
            'dispatchRelease': False, 'differences': differences, 'highWaterRisks': risks, 'intentAttemptHighWater': intent_water,
            'allDomainsRecoveryFence': 'NOT_IMPLEMENTED_BY_THIS_SOURCE',
            'notCovered': ['live-provider verification or money', 'refund/transfer/provider-task production reconciliation',
                           'safe missing-intent backfill', 'production-wide recovery epoch/dispatch fence',
                           'two-person recovery release', 'acceptable RPO/RTO threshold']}


def activity_receivables(snapshot):
    """ACTIVITY claim context, registration detail source: read originals without paying or repairing them."""
    claims = dictionaries(snapshot, 'settlement_claim'); details = dictionaries(snapshot, 'settlement_detail')
    paid = [r for r in dictionaries(snapshot, 'cms_registration')
            if r['owner_type'] == '2' and r['payment_status'] == r['registration_status'] == '2']
    expected_details = 0
    for order in paid:
        activities = [r for r in dictionaries(snapshot, 'cms_activity') if r['id'] == order['owner_id']]
        require(len(activities) == 1, 'original paid activity missing')
        require(order['purchase_kind'] == '1' and order['ticket_id'] == '9'
                and activities[0]['topic_id'] is None and activities[0]['status'] == '1'
                and activities[0]['publish_status'] == '1' and activities[0]['del_flag'] == '0',
                'fixed ordinary activity cash source differs')
        merchant = activities[0]['member_id']; gross = Decimal(order['payable_amount'])
        platform = (gross * Decimal(order['fee_platform_rate'])).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
        expected = {'merchant': (merchant, gross - platform)}
        if platform > 0: expected['platform'] = ('0', platform)
        original = [r for r in details if r['source_type'] == 'registration' and r['source_id'] == order['id']]
        require(len(original) == len(expected) and {r['receiver_type'] for r in original} == set(expected),
                'original activity receivable roster differs')
        for detail in original:
            receiver, amount = expected[detail['receiver_type']]
            require(detail['receiver_id'] == receiver and detail['merchant_id'] == merchant
                    and detail['batch_id'] == '0' and detail['status'] == '0'
                    and detail['entry_kind'] == detail['event_key'] == 'ORIGIN'
                    and Decimal(detail['commission_amount']) == 0
                    and Decimal(detail['gross_amount']) == Decimal(detail['settlement_amount']) == amount
                    and detail['idempotency_key'] == 'registration:' + order['id'] + ':' + detail['receiver_type'] + ':' + receiver,
                    'original activity receivable identity/money differs')
            owned = [r for r in claims if r['id'] == detail['claim_id']]
            require(len(owned) == 1, 'original activity claim missing')
            claim = owned[0]
            require(claim['commercial_context_type'] == 'ACTIVITY' and claim['commercial_context_id'] == order['owner_id']
                    and claim['fulfillment_type'] == 'REGISTRATION' and claim['fulfillment_id'] == order['id']
                    and claim['beneficiary_role'] == detail['receiver_type'].upper() and claim['beneficiary_id'] == receiver
                    and claim['service_unit'] == 'MERCHANT_PARTICIPATION' and claim['settlement_route'] == 'REGISTRATION_SPLIT'
                    and claim['source_contract_type'] == 'DIRECT_REGISTRATION' and claim['source_contract_id'] == order['id']
                    and claim['status'] == 'ACTIVE', 'original activity claim binding differs')
        require(sum((Decimal(r['settlement_amount']) for r in original), Decimal(0)) == gross,
                'original activity receivable cash does not conserve')
        expected_details += len(expected)
    require(len(details) == len(claims) == expected_details
            and len({r['claim_id'] for r in details}) == len(details), 'orphan or duplicate activity economic effect')


def wait_phase(child, phase, deadline):
    marker = ('RESTORE_BUSINESS_PHASE:' + phase).encode()
    pending = b''
    while time.monotonic() < deadline:
        ready, _, _ = select.select([child.stdout], [], [], min(1, max(0, deadline - time.monotonic())))
        if ready:
            block = os.read(child.stdout.fileno(), 65536)
            if not block:
                try: child.wait(timeout=1)
                except subprocess.TimeoutExpired: pass
                raise ChildBoundaryFailure('CHILD_EOF_BEFORE_BOUNDARY')
            pending += block
            if len(pending) >= 1024 * 1024: raise ChildBoundaryFailure('CHILD_OUTPUT_BUDGET')
            while b'\n' in pending:
                line, pending = pending.split(b'\n', 1)
                if line.strip() == marker:
                    return
        if child.poll() is not None: raise ChildBoundaryFailure('CHILD_EXIT_BEFORE_BOUNDARY')
    raise ChildBoundaryFailure('CHILD_BOUNDARY_TIMEOUT')


def child_environment(resources, options, journal_path):
    for forbidden in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'):
        require(not os.environ.get(forbidden), 'JVM argument injection forbidden')
    # Pass task-owned credentials explicitly; never inherit payment/AI/production credentials or host tokens.
    env = {key:os.environ[key] for key in ('PATH','JAVA_HOME','HOME','LANG','LC_ALL','TMPDIR','GITHUB_SHA',
            'RUNNER_ENVIRONMENT','CHENGYIN_CI_RESTORE_BUSINESS') if key in os.environ}
    env.update({'CHENGYIN_RESTORE_SCOPE': resources['scope'], 'CHENGYIN_RESTORE_SOURCE_UUID': resources['source']['uuid'],
                'CHENGYIN_TEST_MYSQL_HOST': '127.0.0.1', 'CHENGYIN_TEST_MYSQL_PORT': str(resources['source']['port']),
                'CHENGYIN_TEST_MYSQL_DATABASE_BASE': 'cy_restore_' + resources['scope'],
                'CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX': '', 'CHENGYIN_TEST_MYSQL_USER': 'root',
                'CHENGYIN_TEST_MYSQL_PASSWORD': options['password'], 'CHENGYIN_RESTORE_PROVIDER_JOURNAL': str(journal_path),
                'CHENGYIN_FUNDS_MYSQL_IT': '1', 'B_PAYMENT_FACT_DDL_APPROVED': '1',
                'A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED': '1', 'TZ': 'UTC'})
    for forbidden in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'):
        require(not env.get(forbidden), 'JVM argument injection forbidden')
    return env


def run(resources_path, output, private):
    hosted(os.environ)
    output, private = Path(output).resolve(), Path(private).resolve()
    require(output != private and output not in private.parents and private not in output.parents, 'private/public roots must be distinct')
    require(output.is_dir() and private.is_dir() and not list(output.iterdir()) and not private.stat().st_mode & 0o077, 'fresh owned evidence directories required')
    resources = json.loads(private_file(resources_path).read_text())
    cohort = resources.get('cohort', 'payment-gap')
    domains = module('restore_fixed_domain_readers', 'ci/restore_business_domain_facts.py')
    require(cohort == 'payment-gap' or cohort in domains.SPECS, 'only seven fixed restore cohorts')
    domain = None if cohort == 'payment-gap' else domains.SPECS[cohort]
    child_name = CHILD if domain is None else domain['child']
    tables = TABLES if domain is None else domain['tables']
    high_ids = HIGH_IDS if domain is None else domain['ids']
    read_journal = journal if domain is None else lambda path,scope:domains.journal(path,scope,cohort)
    def snapshot(side):
        progress['side']=side
        return capture(tool, args[side], tables, high_ids,progress=progress)
    require(Path(resources_path).resolve().parent == private, 'resource manifest must remain in this private root')
    tool = module('canonical_restore_tool', 'scripts/db-backup-restore.py')
    auth = module('original_owned_helpers', 'ci/authenticated_redis_recovery_probe.py')
    require(resources['sourceSha'] == os.environ['GITHUB_SHA'] == subprocess.check_output(
            ['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(), 'wrong source')
    docker = ['docker', '--context', 'default']
    network = json.loads(auth.command(docker + ['network', 'inspect', 'cy-restore-' + resources['scope']]))[0]
    objects = [json.loads(auth.command(docker + ['inspect', resources[side]['cid']]))[0] for side in ('source', 'target')]
    validate_resources(resources, objects, network)
    args, options = {}, {}
    for side in ('source', 'target'):
        target = resources[side]
        require(Path(target['defaultsFile']).resolve().parent == private, 'defaults must remain in this private root')
        options[side] = mysql_options(target['defaultsFile'], target['port'])
        args[side] = argparse.Namespace(defaults_file=private_file(target['defaultsFile']), database=resources['schema'],
                                       server_uuid=target['uuid'], confirm_test_target=True)
        # identify needs an existing database; inspect server identity before creating either schema.
        command = ['mysql', '--defaults-extra-file=' + str(args[side].defaults_file), '--batch', '--raw', '--skip-column-names']
        result = subprocess.run(command, input=b'SELECT @@server_uuid,VERSION(),@@global.event_scheduler', capture_output=True, timeout=20)
        require(result.returncode == 0, 'owned MySQL readiness failed')
        identity = result.stdout.decode().strip().split('\t')
        require(len(identity) == 3 and identity[0] == target['uuid'] and identity[1] == '8.0.45'
                and identity[2] == 'OFF', 'wrong exact UUID/version/event scheduler')
        target['version'] = identity[1]
    require(resources['source']['uuid'] != resources['target']['uuid'], 'distinct MySQL UUIDs required')
    # Only this fixed test class and same-source compiled classes/dependencies; no arbitrary launch command.
    cpfile = private_file(resources['testClasspathFile'])
    cp = cpfile.read_text().strip()
    require(cp and all(Path(p).is_absolute() and Path(p).exists() for p in cp.split(os.pathsep)), 'absolute generated test classpath required')
    entries = cp.split(os.pathsep)
    require(all(str(Path(p).resolve()) == p for p in entries), 'canonical same-build classpath paths required')
    require(entries[0] == str(ROOT / 'chengyinhub-admin/target/test-classes')
            and all((p.startswith(str(ROOT) + '/') and '/target/' in p)
                    or (p.startswith(str(Path.home() / '.m2/repository') + '/') and p.endswith('.jar'))
                    for p in entries), 'same-build test classes/Maven dependencies only')
    child_class = ROOT / 'chengyinhub-admin/target/test-classes' / (child_name.replace('.', '/') + '.class')
    require(child_class.is_file(), 'compiled test child missing; host must build once from this exact source')
    jar = ROOT / 'chengyinhub-admin/target/chengyinhub-admin.jar'
    with zipfile.ZipFile(jar) as package:
        require(not any(('ReconciliationFixture' in p and 'Restore' in p) or 'RestoreProviderJournal' in p
                        or 'FundsRecoveryProcessProbe' in p or 'RestoreChildDiagnostics' in p
                        for p in package.namelist()), 'test entry must not be packaged')
        # admin pom uses generateGitPropertiesFile=true/default outputDirectory/git.properties,
        # includeOnlyProperties=^git.commit.id$. No synthetic META-INF fallback/build stamp is accepted.
        build = dict(line.split('=', 1) for line in package.read('BOOT-INF/classes/git.properties').decode().splitlines()
                     if '=' in line and not line.startswith('#'))
        require(build.get('git.commit.id') == resources['sourceSha'], 'JAR source identity mismatch')
    (private / 'provider').mkdir(mode=0o700)
    (private / 'backup').mkdir(mode=0o700)
    provider = private / 'provider/store.jsonl'
    with provider.open('xb') as stream:
        os.chmod(provider, 0o600); stream.flush(); os.fsync(stream.fileno())
    env = child_environment(resources, options['source'], provider)
    env.update({'CHENGYIN_RESTORE_COHORT':cohort,'CHENGYIN_RESTORE_MODE':'false',
                'CHENGYIN_RESTORE_BOOT':uuid.uuid4().hex})
    if domain is not None:
        # Reused refund test POJO validates its unused suite-config name in its constructor.
        # Its lifecycle is never called: actual datasource/schema/UUID are explicitly supplied by this bridge.
        env['CHENGYIN_TEST_MYSQL_DATABASE_BASE']='cy_restore_'+resources['scope'][:12]
    child, result, primary, created = None, {}, None, []
    child_runs = []
    unconfirmed_children = []
    def stop_exact(process, phase):
        if process.poll() is None:
            try:
                process.kill(); process.wait(timeout=15)
            except BaseException:
                unconfirmed_children.append(phase + '-child-exit-unconfirmed')
    diagnostic_class = ROOT / 'chengyinhub-admin/target/test-classes/com/chengyinhub/business/service/RestoreChildDiagnostics.class'
    require(diagnostic_class.is_file(), 'same-source typed diagnostic class missing')
    child_identity = {'sourceSha': resources['sourceSha'], 'scope': resources['scope'], 'cohort': cohort,
        'runId': os.environ['GITHUB_RUN_ID'], 'runAttempt': os.environ['GITHUB_RUN_ATTEMPT'],
        'childClass': child_name, 'testChildSha256': tool.digest(child_class), 'jarSha256': tool.digest(jar),
        'classpathFileSha256': tool.digest(cpfile), 'diagnosticClassSha256': tool.digest(diagnostic_class)}
    resources_digest=tool.digest(Path(resources_path));probe_digest=tool.digest(Path(__file__))
    progress={}
    def mark(stage,step,side=None,owner=None):
        progress.clear();progress.update(stage=stage,step=step)
        if side is not None:progress['side']=side
        if owner is not None:progress['ownerPhase']=owner
    source_creation_pending = False
    target_creation_pending = False
    started = time.monotonic()
    def deadline(signum, frame):
        raise TimeoutError('fixed restore drill deadline')
    old_alarm = signal.signal(signal.SIGALRM, deadline)
    old_signals={s:signal.signal(s,deadline) for s in (signal.SIGTERM,signal.SIGINT,signal.SIGHUP)}
    signal.alarm(600 if domain is None else 900)
    try:
        mark('SOURCE_START','SCHEMA_ABSENCE')
        # Confirm both named schemas absent; never adopt or remove a pre-existing target.
        for side in ('source', 'target'):
            check = subprocess.run(['mysql', '--defaults-extra-file=' + str(args[side].defaults_file), '--batch', '--skip-column-names'],
                    input=("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + resources['schema'] + "'").encode(), capture_output=True, timeout=20)
            require(check.returncode == 0 and check.stdout.strip() == b'0', 'pre-existing schema forbidden')
        # Failure before the fixed boundary may have partially created a schema. Do NOT adopt/drop it as confirmed ownership.
        source_creation_pending = True
        mark('SOURCE_START','LAUNCH','source')
        with (private / 'child.stderr').open('xb') as errors:
            os.chmod(errors.name, 0o600)
            child = subprocess.Popen(['java', '-Xmx512m', '-cp', cp, child_name], cwd=ROOT / 'chengyinhub-admin',
                                     env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, bufsize=0)
            child_runs.append((child, Path(errors.name), dict(child_identity, phase='SOURCE', boot=env['CHENGYIN_RESTORE_BOOT'])))
            mark('SOURCE_BACKUP_BOUNDARY','WAIT_BOUNDARY','source')
            wait_phase(child, 'BACKUP_READY', started + 180)
            created.append('source'); source_creation_pending = False
            mark('SOURCE_CAPTURE','TABLE_ROWS','source');before = snapshot('source')
            mark('SOURCE_CAPTURE','JOURNAL','source');p_before = read_journal(provider, resources['scope'])
            mark('SOURCE_CAPTURE','SAVE_PRIVATE','source')
            save(private / 'before.json', before)
            save(private / 'provider/before.json', p_before)
            args['source'].output = private / 'backup/backup.sql'
            mark('CANONICAL_BACKUP','CANONICAL_CALL','source')
            backup_started = now(); tool.backup(args['source']); backup_finished = now();backup_tick=time.monotonic()
            manifest = json.loads(Path(str(args['source'].output) + '.json').read_text())
            mark('SOURCE_FAULT_BOUNDARY','SIGNAL_CHILD','source');child.stdin.write(b'AFTER_BACKUP\n'); child.stdin.flush()
            mark('SOURCE_FAULT_BOUNDARY','WAIT_BOUNDARY','source')
            wait_phase(child, 'FAULT_READY', started + 300)
            fault_at = now();fault_gap=time.monotonic()-backup_tick
            mark('FAULT_CAPTURE','TABLE_ROWS','source');fault = snapshot('source')
            mark('FAULT_CAPTURE','JOURNAL','source');p_after = read_journal(provider, resources['scope'])
            mark('FAULT_CAPTURE','SAVE_PRIVATE','source')
            save(private / 'fault.json', fault)
            save(private / 'provider/after.json', p_after)
            mark('SOURCE_STOP','SIGNAL_CHILD','source');child.stdin.write(b'STOP\n'); child.stdin.flush()
            mark('SOURCE_STOP','CHILD_EXIT','source');require(child.wait(timeout=20) == 0, 'source fixture failed')
        # Recreate only matching DATABASE options. No tables, cleanup SQL or seed are applied to the target.
        mark('TARGET_SCHEMA_CREATE','DATABASE_OPTIONS','source')
        create = database_create_sql(tool,args['source'],resources['schema'])
        command = ['mysql', '--defaults-extra-file=' + str(args['target'].defaults_file)]
        target_creation_pending = True
        mark('TARGET_SCHEMA_CREATE','CREATE_SCHEMA','target')
        made = subprocess.run(command, input=create.encode(), capture_output=True, timeout=20)
        require(made.returncode == 0, 'target CREATE failed; never adopt existing schema')
        created.append('target'); target_creation_pending = False
        args['target'].backup = args['source'].output
        restore_started, tick = now(), time.monotonic()
        mark('CANONICAL_RESTORE','CANONICAL_CALL','target')
        tool.restore(args['target']) # Every original guard, checksum, trigger/definition and AUTO_INCREMENT check remains active.
        restore_finished, elapsed = now(), time.monotonic() - tick
        mark('RESTORED_CAPTURE','TABLE_ROWS','target');restored = snapshot('target')
        mark('RESTORED_CAPTURE','SAVE_PRIVATE','target')
        save(private / 'restored.json', restored)
        mark('DOMAIN_EVALUATE','EVALUATE','target')
        proof = (reconcile(before, fault, restored, p_before, p_after) if domain is None else
                 domains.evaluate(cohort, before, fault, restored, p_before, p_after))
        # Exercise the actual disabled production gateway in a separate test-only JVM, without source setup or target writes.
        owner_proofs=[]; normal_available_at=None;normal_available_elapsed=None
        if domain is None:
            mark('OWNER_BOOT','LAUNCH','target','RESTORED_GUARD')
            guarded_env = dict(env, CHENGYIN_RESTORE_PHASE='RESTORED_GUARD',
                               CHENGYIN_RESTORE_GUARD_FILE=str(private / 'provider/gateway.json'))
            with (private / 'guard.stderr').open('xb') as errors:
                os.chmod(errors.name, 0o600)
                guard = subprocess.Popen(['java', '-Xmx256m', '-cp', cp, CHILD], cwd=ROOT / 'chengyinhub-admin',
                                         env=guarded_env, stdout=subprocess.DEVNULL, stderr=errors)
                child_runs.append((guard, Path(errors.name), dict(child_identity, phase='RESTORED_GUARD', boot=env['CHENGYIN_RESTORE_BOOT'])))
                try:
                    mark('OWNER_BOOT','CHILD_EXIT','target','RESTORED_GUARD')
                    require(guard.wait(timeout=40) == 0, 'disabled gateway probe failed')
                finally: stop_exact(guard, 'guard')
            mark('OWNER_EVIDENCE','READ_EVIDENCE','target','RESTORED_GUARD')
            gateway = json.loads(private_file(private / 'provider/gateway.json').read_text())
            require(gateway == {'state': 'SDK_DISABLED_ONLY', 'createRejected': True,
                                'allDispatchDomainsFenced': False, 'recoveryRelease': 'BLOCKED'}, 'gateway boundary changed')
            require(snapshot('target') == restored and journal(provider, resources['scope']) == p_after,
                    'restore or guard created an economic effect/provider duplicate')
        else:
            # Separate real JVM boots, never mutate a running production flag or auto-release a gap.
            target_resources=dict(resources,source=resources['target'])
            for phase in ['RESTORED_OWNER','RESTORED_INVALID_OWNER','RESTORED_EMPTY_OWNER'] + (['NORMAL_READBACK'] if proof['normalBootEligible'] else []):
                mode={'RESTORED_OWNER':'true','RESTORED_INVALID_OWNER':'invalid-fixture-value','RESTORED_EMPTY_OWNER':'','NORMAL_READBACK':'false'}[phase]
                boot=uuid.uuid4().hex
                evidence=private / ('owner-'+phase+'.json')
                phase_env=child_environment(target_resources,options['target'],provider)
                phase_env.update({'CHENGYIN_RESTORE_PHASE':phase,'CHENGYIN_RESTORE_COHORT':cohort,
                                  'CHENGYIN_RESTORE_MODE':mode,'CHENGYIN_RESTORE_BOOT':boot,
                                  'CHENGYIN_TEST_MYSQL_DATABASE_BASE':'cy_restore_'+resources['scope'][:12],
                                  'CHENGYIN_RESTORE_OWNER_EVIDENCE':str(evidence)})
                mark('OWNER_BEFORE','TABLE_ROWS','target',phase);previous_rows=snapshot('target')
                mark('OWNER_BEFORE','JOURNAL','target',phase);previous_external=read_journal(provider,resources['scope'])
                before_path=private / ('owner-'+phase+'-before.json');after_path=private / ('owner-'+phase+'-after.json')
                mark('OWNER_BEFORE','SAVE_PRIVATE','target',phase);save(before_path,previous_rows)
                with (private / ('owner-'+phase+'.stderr')).open('xb') as errors:
                    os.chmod(errors.name, 0o600)
                    mark('OWNER_BOOT','LAUNCH','target',phase)
                    process=subprocess.Popen(['java','-Xmx512m','-cp',cp,child_name],cwd=ROOT/'chengyinhub-admin',
                                             env=phase_env,stdout=subprocess.DEVNULL,stderr=errors)
                    child_runs.append((process, Path(errors.name), dict(child_identity, phase=phase, boot=boot)))
                    try:
                        mark('OWNER_BOOT','CHILD_EXIT','target',phase)
                        require(process.wait(timeout=60)==0,'actual owner boot/readback failed')
                    finally: stop_exact(process, phase)
                mark('OWNER_EVIDENCE','READ_EVIDENCE','target',phase)
                observed=json.loads(private_file(evidence).read_text())
                require(observed['pid']==process.pid and observed['boot']==boot and observed['startedAt'],'owner process identity')
                mark('OWNER_AFTER','TABLE_ROWS','target',phase);current_rows=snapshot('target')
                mark('OWNER_AFTER','JOURNAL','target',phase);current_external=read_journal(provider,resources['scope'])
                mark('OWNER_AFTER','SAVE_PRIVATE','target',phase);save(after_path,current_rows)
                mark('OWNER_VERIFY','VERIFY','target',phase)
                observation=domains.verify_owner(cohort,observed,previous_rows,current_rows,previous_external,current_external,phase)
                owner_proofs.append(dict(observation,pid=observed['pid'],boot=boot,startedAt=observed['startedAt'],startupMode=mode,
                    fullBeforeRowsSha256=tool.digest(before_path),fullAfterRowsSha256=tool.digest(after_path),
                    providerBeforeSha256=previous_external['sha256'],providerAfterSha256=current_external['sha256']))
                if phase=='NORMAL_READBACK':normal_available_at=now();normal_available_elapsed=time.monotonic()-tick
            gateway={'state':'ACTUAL_OWNER_BOOT_OBSERVED','coveredOwners':[domain['owner']],
                     'allDispatchDomainsFenced':False,'recoveryRelease':'TEST_COHORT_ONLY' if normal_available_at else 'BLOCKED'}
            observed_methods={method for o in owner_proofs if o['realOwnerBlocked'] for method in o['blockedMethods']}
            proof['declaredDispatchMethods']=list(domain['blocked'])
            proof['observedBlockedMethods']=sorted(observed_methods)
            proof['allDeclaredDispatchMethodsFenced']=observed_methods==set(domain['blocked'])
        mark('FINAL_JOURNAL','JOURNAL');final_provider=read_journal(provider,resources['scope'])
        mark('RESULT_PROJECTION','PROJECT')
        result = {**proof, 'sourceSha': resources['sourceSha'], 'jarSha256': tool.digest(jar),
                  'runtimeKind':'TEST_CLASSPATH_PRODUCTION_OWNERS','fullHttpApplicationStartup':'NOT_COVERED_BY_THIS_FIXTURE',
                  'cohort':cohort,'runId':os.environ['GITHUB_RUN_ID'],'runAttempt':os.environ['GITHUB_RUN_ATTEMPT'],
                  'sourceCid':resources['source']['cid'],'targetCid':resources['target']['cid'],
                  'resourcesSha256': resources_digest,
                  'testChildSha256': tool.digest(child_class), 'classpathFileSha256': tool.digest(cpfile),
                  'classpathProofs': classpath_evidence(entries, tool),
                  'probeSourceSha256': probe_digest,
                  'canonicalToolSha256': tool.digest(ROOT / 'scripts/db-backup-restore.py'),
                  'scope': resources['scope'], 'sourceUuid': resources['source']['uuid'], 'targetUuid': resources['target']['uuid'],
                  'manifestSha256': tool.digest(Path(str(args['source'].output) + '.json')),
                  'providerHead': final_provider['head'], 'providerJournalSha256': final_provider['sha256'], 'disabledGateway': gateway,
                  'providerAfterFaultSha256':p_after['sha256'],
                  'providerBeforeSha256': tool.digest(private / 'provider/before.json'),
                  'times': {'backupStarted': backup_started, 'backupFinished': backup_finished, 'fault': fault_at,
                            'restoreStarted': restore_started, 'restoreFinished': restore_finished,
                            'actualBackupToFaultSeconds': fault_gap,
                            'actualRestoreVerificationSeconds': elapsed,'normalOriginalReadbackAvailableAt':normal_available_at,
                            'rpo': ('UNKNOWN_UNAPPROVED_BUSINESS_MEASURE' if normal_available_at is None else
                                    {'scope':cohort,'observedLostOriginalFacts':0,
                                     'backupToFaultSeconds':fault_gap,
                                     'productionSlo':'UNKNOWN_NOT_CLAIMED'}),
                            'rto': 'UNKNOWN_RECOVERY_RELEASE_BLOCKED' if normal_available_at is None else normal_available_elapsed},
                  'actualOwnerObservations':owner_proofs,
                  'rowProofs': {label: {t: {'rows': len(s['tables'][t]['rows']), 'sha256': sha(canonical(s['tables'][t]))}
                                for t in tables} for label, s in [('backup', before), ('fault', fault), ('restored', restored)]},
                  'businessResult': ('SCOPED_COMPLETE_FACTS_NORMAL_BOOT_READBACK_VERIFIED' if normal_available_at else
                                     'SCOPED_DIFFERENCES_VERIFIED_RELEASE_BLOCKED'), 'cleanup': 'PENDING'}
    except BaseException as failure:
        primary = failure
        try:diagnostic=host_failure(failure,progress)
        except BaseException:diagnostic={'stage':'UNKNOWN','step':'UNKNOWN','reasonCode':'UNKNOWN_PRIVATE_FAILURE','locations':[]}
        result = {'sourceSha': resources['sourceSha'], 'scope': resources['scope'], 'businessResult': 'FAIL',
                  'cohort': cohort, 'runId': os.environ['GITHUB_RUN_ID'], 'runAttempt': os.environ['GITHUB_RUN_ATTEMPT'],
                  'resourcesSha256': resources_digest,'jarSha256':child_identity['jarSha256'],'probeSourceSha256':probe_digest,
                  'candidateState': 'BLOCKED', 'dispatchRelease': False, 'primaryFailureType': type(failure).__name__,
                  'primaryReasonCode': diagnostic['reasonCode'],'hostFailure':diagnostic,
                  'primaryReason': str(failure) if isinstance(failure, ChildBoundaryFailure) else 'private diagnostics required',
                  'domainContractFailure': domains.failure_context(failure),
                  'rpo': 'UNKNOWN', 'rto': 'UNKNOWN', 'cleanup': 'PENDING'}
        # Preserve inspectable failed restore state before exact-owned cleanup; never retry import.
        for side in created:
            try:
                save(private / ('failure-' + side + '.json'), tool.snapshot(args[side]))
            except BaseException:
                result.setdefault('failureSnapshotUnknown', []).append(side)
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, old_alarm)
        for signum,previous_handler in old_signals.items():signal.signal(signum,previous_handler)
        cleanup_failed = ['source-create-unconfirmed'] if source_creation_pending else []
        if target_creation_pending: cleanup_failed.append('target-create-unconfirmed')
        if child is not None:
            # Exact Popen child only. Never kill by name/pattern or touch MySQL/Redis.
            stop_exact(child, 'source')
        cleanup_failed.extend(unconfirmed_children)
        for side in reversed(created):
            try:
                # Do not invoke canonical identify here if source failed before CREATE; verify UUID independently.
                target = args[side]
                cmd = ['mysql', '--defaults-extra-file=' + str(target.defaults_file), '--batch', '--skip-column-names']
                check = subprocess.run(cmd, input=b'SELECT @@server_uuid', capture_output=True, timeout=20)
                require(check.returncode == 0 and check.stdout.decode().strip() == target.server_uuid, 'cleanup UUID mismatch')
                drop = subprocess.run(cmd, input=('DROP DATABASE IF EXISTS ' + tool.quoted(target.database)).encode(), capture_output=True, timeout=20)
                require(drop.returncode == 0, 'owned schema removal failed')
                absent = subprocess.run(cmd, input=("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + target.database + "'").encode(), capture_output=True, timeout=20)
                require(absent.returncode == 0 and absent.stdout.strip() == b'0', 'owned schema absence unconfirmed')
            except BaseException:
                cleanup_failed.append(side)
        result['schemaCleanup'] = 'UNKNOWN' if cleanup_failed else 'CONFIRMED_EXACT_CREATED_SCHEMAS_ABSENT'
        result['childExits'] = [dict(identity,
            pid=process.pid, returnCode=process.poll(),
            **child_failure(path, identity)) for process, path, identity in child_runs]
        result['cleanupFailures'] = cleanup_failed
        result['cleanup'] = 'PENDING_OUTER_EXACT_RESOURCE_CLEANUP'
        # Original error cannot be overwritten by a successful or unsuccessful cleanup.
        result['processExit'] = 1 if primary is not None or cleanup_failed else 0
        save(output / 'reconciliation.json', result)
    if primary is not None:
        raise ValueError('isolated drill failed; original category retained in receipt') from None
    require(not cleanup_failed, 'cleanup unknown; business result cannot authorize release')
    return result


def finalize(resources_path, output, original_exit):
    hosted(os.environ)
    resources = json.loads(private_file(resources_path).read_text())
    result = json.loads((Path(output) / 'reconciliation.json').read_text())
    require(result['sourceSha'] == resources['sourceSha'] == os.environ['GITHUB_SHA']
            and result['scope'] == resources['scope']
            and result['resourcesSha256'] == sha(Path(resources_path).read_bytes()), 'cannot finalize another source/scope/resource manifest')
    require(re.fullmatch('[a-f0-9]{32}', resources['scope'])
            and all(re.fullmatch('[a-f0-9]{64}', resources[side]['cid']) for side in ('source', 'target')),
            'exact resource identities required')
    auth = module('original_cleanup_proof', 'ci/authenticated_redis_recovery_probe.py')
    failures = []
    for kind, identifier in [('container', resources['source']['cid']), ('container', resources['target']['cid']),
                             ('network', 'cy-restore-' + resources['scope'])]:
        check = subprocess.run(['docker', '--context', 'default', kind, 'inspect', identifier], capture_output=True, timeout=20)
        try: auth.require_absent(kind, identifier, check.returncode, check.stderr)
        except BaseException: failures.append(kind + ':' + identifier)
    good = (original_exit == 0 and result['processExit'] == 0 and not failures
            and result['schemaCleanup'] == 'CONFIRMED_EXACT_CREATED_SCHEMAS_ABSENT')
    positive=result['businessResult']=='SCOPED_COMPLETE_FACTS_NORMAL_BOOT_READBACK_VERIFIED'
    save(Path(output) / 'finalized.json', {'sourceSha': result['sourceSha'], 'scope': resources['scope'],
         'cohort':resources.get('cohort','payment-gap'),
         'runId': os.environ['GITHUB_RUN_ID'], 'runAttempt': os.environ['GITHUB_RUN_ATTEMPT'],
         'resourcesSha256': sha(Path(resources_path).read_bytes()), 'finalization': 'RECORDED',
         'originalExit': original_exit, 'result': ('TEST_COHORT_NORMAL_READBACK_COMPLETE_NO_PRODUCTION_RELEASE' if positive else
             'SCOPED_OBSERVATION_COMPLETE_RELEASE_BLOCKED') if good else 'FAIL',
         'candidateState': 'TEST_OWNED_RECONCILED' if good and positive else 'BLOCKED', 'dispatchRelease': False, 'resourceCleanup': 'UNKNOWN' if failures else 'EXACT_ABSENCE',
         'absenceFailures': failures, 'businessResult': result['businessResult']})
    require(good, 'original failure or incomplete cleanup; cannot finalize green')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('run', 'finalize'))
    parser.add_argument('--resources', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--private', type=Path)
    parser.add_argument('--original-exit', type=int)
    args = parser.parse_args()
    try:
        if args.phase == 'run':
            require(args.private is not None, 'private root required'); run(args.resources, args.output, args.private)
        else:
            require(args.original_exit is not None, 'original exit required'); finalize(args.resources, args.output, args.original_exit)
    except BaseException:
        parser.exit(1, 'Restore business drill failed/blocked; inspect scoped receipt, never retry dispatch\n')


if __name__ == '__main__':
    main()
