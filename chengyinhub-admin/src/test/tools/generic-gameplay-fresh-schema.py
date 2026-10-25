#!/usr/bin/env python3
"""LOCAL ONLY: base_schema/base_seed/quartz + ledger-ordered migration DDL.

Default: dry run with every skipped statement printed and recorded in --report.
--execute requires a NEW cy_e2e_* schema on 127.0.0.1 MySQL 8; never drops a DB.
Production data assertions/cleanup/seeds are not HTTP fixture data; base_seed is.
No migration files are changed. No Docker, download or non-standard dependency.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT))
from ci.sql_splitter import split_statements, executable_statement, unterminated_token, delimiter_error
from ci.run_migration import statement_assertion
from ci.migration_ledger import build_manifest
from scripts.e2e.build_schema import (scratch_schema_sql, migrations_after_baseline, notice_forward_plan,
                                      NOTICE_OLD, NOTICE_V2, NOTICE_FORWARD_FILES)


def plan_file(path, migration=False, data_only=False):
    text = path.read_text(encoding='utf-8')
    error = unterminated_token(text) or delimiter_error(text)
    if error:
        raise ValueError('Invalid SQL: ' + error)
    parts = split_statements(text)
    # Include quoted DDL used by SET/PREPARE and MySQL executable comments.
    persistent = any(re.search(r'''(?is)(?:^\s*(?:/\*!\d+\s*)?|['"])\s*(?:CREATE\s+(?!TEMPORARY\b)|ALTER\s+|DROP\s+(?!TEMPORARY\b)|RENAME\s+)''', executable_statement(s)) for s in parts)
    if data_only and persistent:
        raise ValueError('Data-only policy contains persistent DDL: ' + str(path))
    applied, skipped, assertions = [], [], []
    for index, statement in enumerate(parts, 1):
        assertion = statement_assertion(statement)  # Unknown metadata fails closed.
        sql = scratch_schema_sql(executable_statement(statement).strip())
        reason = ('runner assertion requires production readback' if migration and assertion else
                  'data-only migration; fixture uses base_seed' if migration and (data_only or not persistent) else
                  'migration backfill/cleanup/seed; fixture uses base_seed' if migration and
                  re.match(r'(?i)\s*(?:INSERT|REPLACE|UPDATE|DELETE|TRUNCATE)\b', sql) else None)
        if reason:
            skipped.append({'statement': index, 'reason': reason, 'sql': sql})
        else:
            if assertion:
                if assertion != 'scalar-true':
                    raise ValueError('Unsupported baseline assertion: ' + assertion)
                assertions.append(assertion)
            if re.search(r'(?is)\b(?:FEDERATED|OUTFILE|DUMPFILE|LOAD_FILE|sys_exec|sys_eval)\b|\b(?:INSTALL\s+PLUGIN|CREATE\s+SERVER|LOAD\s+DATA)\b|^\s*(?:USE\b|(?:CREATE|DROP|ALTER)\s+DATABASE\b)', sql):
                raise ValueError('Unsafe SQL: ' + str(path) + ' #' + str(index))
            if re.search(r'(?i)47\.103\.124\.64|47\.100\.84\.122|\bchengyinhubdb\s*\.', sql):
                raise ValueError('Production-qualified SQL: ' + str(path))
            if migration and re.search(r'''(?i)['"](?:UPDATE|DELETE|INSERT|REPLACE|TRUNCATE)\s''', sql):
                raise ValueError('Dynamic data cleanup needs explicit local policy: ' + str(path))
            applied.append(sql)
    return {'path': str(path.relative_to(ROOT)), 'apply': applied, 'skipped': skipped,
            'assertions': assertions,
            'sourceSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'transaction': bool(re.search(r'(?im)^\s*--\s*runner:\s*requires=transaction\s*$', text))}


def build_plan():
    baseline = json.loads((ROOT / 'scripts/e2e/base_schema_20260818_migrations.json').read_text())
    policy = json.loads((ROOT / 'scripts/e2e/schema_replay_policy_20260921.json').read_text())
    if (baseline.get('schemaVersion') != 1 or policy.get('schemaVersion') != 1 or
            len(baseline['migrations']) != baseline['migrationCount'] or
            len(baseline['reconciledMigrations']) != baseline['reconciledCount']):
        raise ValueError('Invalid baseline manifest/policy')
    for record in [baseline['baseSchema'], policy['baselineSeed']]:
        if hashlib.sha256((ROOT / record['path']).read_bytes()).hexdigest() != record['sha256']:
            raise ValueError('Baseline fingerprint changed: ' + record['path'])
    included = {m['path'] for m in baseline['migrations'] + baseline['reconciledMigrations']}
    if len(included) != baseline['migrationCount'] + baseline['reconciledCount']:
        raise ValueError('Duplicate folded migration')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    manifest = build_manifest(ROOT, target_commit=head, dependency_path=ROOT / 'ci/migration-dependencies.json')
    ordered = manifest['migrations']
    # Reuse full canonical source/baseline/data-only custody checks. Keep data-only
    # files below so their original HTTP-fixture skips remain explicit in the report.
    migrations_after_baseline(ordered)
    pending = [m for m in ordered if m['path'] not in included]
    notice_forward = notice_forward_plan(ordered, pending)
    selected = notice_forward['selected'] if notice_forward else pending
    files = [plan_file(ROOT / record['path']) for record in [baseline['baseSchema'], policy['baselineSeed']]]
    # The frozen baseline already contains all 11 Quartz tables and their foreign keys.
    for m in selected:
        files.append(plan_file(ROOT / m['path'], True, m['path'] in policy['dataOnlyMigrations']))
    result = {'mode': 'DRY_RUN', 'baselineIncluded': sorted(included), 'files': files,
              'ordering': 'existing ledger dependency topology',
              'manifest': {'targetCommit': head, 'migrationCount': len(ordered), 'ordering': manifest['ordering']},
              'appliedStatements': sum(len(f['apply']) for f in files),
              'skippedStatements': sum(len(f['skipped']) for f in files)}
    if notice_forward:
        result['noticeForward'] = {'state': 'PLANNED_UNAPPLIED',
                                  'oldMigrationId': NOTICE_OLD, 'replacementMigrationId': NOTICE_V2,
                                  'fingerprints': notice_forward['fingerprints']}
    return result


def mysql_command(args):
    if args.host != '127.0.0.1' or not re.fullmatch(r'cy_e2e_[A-Za-z0-9_]+', args.database):
        raise ValueError('Only 127.0.0.1 and a new cy_e2e_* fixture schema are allowed')
    if not 1 <= args.port <= 65535:
        raise ValueError('Invalid local port')
    mysql = shutil.which('mysql')
    if not mysql:
        raise ValueError('Local mysql client is missing')
    return [mysql, '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
            '--port=' + str(args.port), '--user=' + args.user, '--binary-mode=1',
            '--local-infile=0', '--batch', '--skip-column-names']


def safe_failure_diagnostic(error, plan):
    # Never publish exception messages, SQL, stderr, connection values or file contents.
    allowed_types = {'CalledProcessError', 'TimeoutExpired', 'ValueError', 'OSError',
                     'FileNotFoundError', 'PermissionError', 'JSONDecodeError'}
    diagnostic = {'exceptionType': type(error).__name__ if type(error).__name__ in allowed_types else 'Exception'}
    phase = plan.get('currentPhase')
    if phase in ('plan-build', 'mysql-version', 'schema-absence', 'schema-create', 'schema-apply'):
        diagnostic['phase'] = phase
    stderr = error.stderr if isinstance(error, subprocess.CalledProcessError) else None
    if isinstance(stderr, str):
        match = re.search(r'^ERROR[ \t]+([0-9]{3,5})(?:[ \t]+\([A-Z0-9]+\))?(?:[ \t]+at line[ \t]+([0-9]+))?:', stderr, re.MULTILINE)
        if match:
            diagnostic['mysqlErrorCode'] = int(match.group(1))
            if match.group(2):
                diagnostic['mysqlLine'] = int(match.group(2))
    current = plan.get('currentFile')
    files = plan.get('files', [])
    matching = [(i, f) for i, f in enumerate(files, 1) if f.get('path') == current]
    if (len(matching) == 1 and isinstance(current, str) and
            re.fullmatch(r'(?:sql/|chengyinhub-admin/sql/)[A-Za-z0-9_./-]+\.sql', current) and
            '..' not in pathlib.PurePosixPath(current).parts):
        index, file = matching[0]
        diagnostic.update(file=current, fileIndex=index)
    return diagnostic


def execute(args, plan):
    notice_forward = plan.get('noticeForward')
    notice_paths = {NOTICE_FORWARD_FILES[key][0] for key in (NOTICE_OLD, NOTICE_V2)}
    if notice_forward is not None or any(file.get('path') in notice_paths for file in plan['files']):
        # Rebuild through the same canonical contract before any MySQL call. A stale
        # hash, injected old file, edited skip/apply payload or partial pair is not executable.
        expected = build_plan()
        if plan.get('mode') != 'DRY_RUN' or 'completed' in plan:
            raise ValueError('Notice forward execution plan drift: state')
        for key in ('noticeForward', 'manifest', 'baselineIncluded', 'ordering',
                    'files', 'appliedStatements', 'skippedStatements'):
            if key not in plan or plan[key] != expected.get(key):
                raise ValueError('Notice forward execution plan drift: ' + key)
    command = mysql_command(args)
    env = dict(os.environ, MYSQL_PWD=os.environ.get('CHENGYIN_MYBATIS_IT_PASSWORD', ''))

    def run(sql, database=False):
        return subprocess.run(command + (['--database=' + args.database] if database else []),
                              input=sql, text=True, capture_output=True, env=env,
                              timeout=120, check=True).stdout.strip()

    plan['currentPhase'] = 'mysql-version'
    if not run('SELECT VERSION();').startswith('8.'):
        raise ValueError('Local fixture requires MySQL 8')
    plan['currentPhase'] = 'schema-absence'
    if run("SELECT SCHEMA_NAME FROM information_schema.schemata WHERE SCHEMA_NAME='" + args.database + "';"):
        raise ValueError('Existing schema refused; choose a new fixture name')
    # Baseline tables use general_ci; temporary migration tables inherit the schema collation.
    plan['currentPhase'] = 'schema-create'
    run('CREATE DATABASE `' + args.database + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;')
    plan.update(mode='EXECUTING', database=args.database, completed=[])
    if notice_forward is not None:
        notice_forward['state'] = 'REPLACED_UNAPPLIED'
        print('REPLACED_UNAPPLIED %s -> %s fingerprints=%s' %
              (NOTICE_OLD, NOTICE_V2, json.dumps(notice_forward['fingerprints'], sort_keys=True)))
    for file in plan['files']:
        plan['currentPhase'] = 'schema-apply'
        plan['currentFile'] = file['path']
        if file['apply']:
            if any('$$' in sql for sql in file['apply']):
                raise ValueError('Local delimiter collision: ' + file['path'])
            sqls = file['apply']
            if file['transaction']:
                sqls = ['START TRANSACTION'] + sqls + ['COMMIT']
            output = run('DELIMITER $$\n' + '\n'.join(sql + '$$' for sql in sqls) + '\nDELIMITER ;\n', True)
            if file.get('assertions') and output.splitlines() != ['1'] * len(file['assertions']):
                raise ValueError('Baseline assertion failed: ' + file['path'] + ': ' + repr(output))
        plan['completed'].append(file['path'])
    plan['mode'] = 'SUCCEEDED'
    plan.pop('currentFile', None)
    plan.pop('currentPhase', None)


def execution_profile_error(profile, platform, env):
    if platform != 'linux':
        return 'Database execution requires the dedicated test VPS or GitHub-hosted Linux; macOS backend execution is forbidden'
    if profile == 'vps':
        if env.get('GITHUB_ACTIONS') == 'true':
            return 'GitHub Actions must use the honest github-hosted profile, not the VPS flag'
        return None if env.get('CHENGYIN_VPS_TEST_RUN') == '1' else 'Database execution requires the dedicated test VPS (CHENGYIN_VPS_TEST_RUN=1)'
    if profile == 'github-hosted':
        if (env.get('GITHUB_ACTIONS') == 'true' and
                env.get('GITHUB_REPOSITORY') == 'liboyang42-cpu/hengyin' and
                env.get('RUNNER_ENVIRONMENT') == 'github-hosted' and
                env.get('CHENGYIN_VPS_TEST_RUN', '') == ''):
            return None
        return 'Explicit public-repository GitHub-hosted execution is required; never impersonate the VPS'
    return 'Unknown execution profile'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--profile', choices=('vps', 'github-hosted'), default='vps')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=3316)
    parser.add_argument('--user', default='root')
    parser.add_argument('--database', default='cy_e2e_generic_gameplay')
    parser.add_argument('--report', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.execute:
        error = execution_profile_error(args.profile, sys.platform, os.environ)
        if error:
            parser.error(error)
    plan = {'mode': 'PLANNING', 'files': [], 'currentPhase': 'plan-build'}
    try:
        plan = build_plan()
        for file in plan['files']:
            for skip in file['skipped']:
                print('SKIP %s #%s: %s\n  %s' % (file['path'], skip['statement'], skip['reason'], skip['sql']))
        if args.execute:
            execute(args, plan)
    except Exception as error:
        plan['mode'] = 'FAILED'
        plan['failureDiagnostic'] = safe_failure_diagnostic(error, plan)
        raise
    finally:
        args.report.write_text(json.dumps(plan, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: plan[k] for k in ('mode', 'appliedStatements', 'skippedStatements')}))


if __name__ == '__main__':
    main()
