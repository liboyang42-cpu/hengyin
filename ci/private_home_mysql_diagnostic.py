#!/usr/bin/env python3
"""Test-only W19 canonical observations; no application activation or production target."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sql_splitter import split_statements

ROOT = Path(__file__).resolve().parent.parent
FIXTURE = ROOT / 'ci/fixtures/private_home_review_candidate_20261003.sql'
EXPECTED_SHA256 = '35651fe5402baf83c4b843caa9b34a05b21fcd94be203e38aaf4f5fb9dfe0438'
NAMES = ('chk_private_home', 'chk_private_home_mutation', 'chk_private_home_proof', 'chk_private_home_restore')


def main():
    data = FIXTURE.read_bytes()
    assert hashlib.sha256(data).hexdigest() == EXPECTED_SHA256, 'Frozen candidate bytes changed'
    statements = split_statements(data.decode('utf-8'))
    container = 'cy-w19-diagnostic-' + uuid.uuid4().hex
    started = False
    outcomes = []
    canonical_by_charset = {}

    def command(args, text=None):
        result = subprocess.run(args, input=text, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            print('W19_DIAGNOSTIC_COMMAND_ERROR ' + result.stderr.strip(), flush=True)
            result.check_returncode()
        return result.stdout.strip()

    def sql(query, schema=None, charset='utf8mb4'):
        args = ['docker', 'exec', '-i', container, 'mysql', '-uroot', '-h127.0.0.1',
                '--default-character-set=' + charset, '-N', '-B', '-r']
        if schema:
            args += [schema]
        return command(args, query)

    def apply(schema, charset):
        observations = []
        for statement in statements:
            result = sql(statement, schema, charset)
            if result:
                observations.append(result)
        assert len(observations) == 1, observations
        return observations[0]

    try:
        command(['docker', 'run', '-d', '--name', container, '--network', 'none',
                 '-e', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', 'mysql:8.0.45'])
        started = True
        # Internal loopback TCP avoids treating the image's temporary socket-only initializer as ready.
        for attempt in range(90):
            result = subprocess.run(['docker', 'exec', container, 'mysqladmin', 'ping',
                                     '-h127.0.0.1', '-uroot', '--silent'],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if result.returncode == 0:
                break
            time.sleep(2)
        else:
            raise RuntimeError('Disposable MySQL did not become healthy')
        identity = sql("SELECT CONCAT(VERSION(), '|', @@version_comment)")
        assert identity == '8.0.45|MySQL Community Server - GPL', identity
        print('W19_DIAGNOSTIC_ENGINE ' + identity, flush=True)
        print('W19_DIAGNOSTIC_FIXTURE_SHA256 ' + EXPECTED_SHA256, flush=True)
        for charset in ('latin1', 'utf8mb4'):
            for iteration in range(2):
                schema = 'w19_diagnostic_' + uuid.uuid4().hex
                sql('CREATE DATABASE `' + schema + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin')
                label = dict(charset=charset, iteration=iteration)
                try:
                    fresh = apply(schema, charset)
                    # Observe canonical evidence even when predicted digest guard returns zero.
                    rows = sql("SELECT c.constraint_name,c.check_clause,SHA2(c.check_clause,256),t.enforced "
                               "FROM information_schema.check_constraints c JOIN information_schema.table_constraints t "
                               "ON t.constraint_schema=c.constraint_schema AND t.constraint_name=c.constraint_name "
                               "WHERE c.constraint_schema=DATABASE() ORDER BY c.constraint_name", schema, charset)
                    observed = []
                    hashes = {}
                    for row in rows.splitlines():
                        name, clause, digest, enforced = row.split('\t')
                        observed.append(name)
                        assert enforced == 'YES'
                        hashes[name] = digest
                        print('W19_CHECK_CANONICAL ' + json.dumps(dict(label, name=name, clause=clause,
                                                                     raw_sha256=digest, enforced=enforced)), flush=True)
                    assert tuple(observed) == NAMES, observed
                    canonical_by_charset[(charset, iteration)] = hashes
                    indexes = sql("SELECT table_name,index_name,non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                                  "FROM information_schema.statistics WHERE table_schema=DATABASE() "
                                  "GROUP BY table_name,index_name,non_unique ORDER BY table_name,index_name", schema, charset)
                    print('W19_INDEX_SHAPES ' + json.dumps(dict(label, rows=indexes.splitlines())), flush=True)
                    repeat = None
                    try:
                        repeat = apply(schema, charset)
                    except subprocess.CalledProcessError:
                        pass  # Record as failure after independent negative evidence and cleanup.
                    sql('ALTER TABLE player_private_home DROP CHECK chk_private_home, '
                        'ADD CONSTRAINT chk_private_home CHECK (owner_id > 0)', schema, charset)
                    weak = sql(statements[-1], schema, charset)
                    result = dict(label, fresh=fresh, repeat=repeat, weak=weak, activation_cleared=False)
                    print('W19_REHEARSAL_RESULT ' + json.dumps(result), flush=True)
                    outcomes.append(result)
                finally:
                    sql('DROP DATABASE `' + schema + '`')
                    assert sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + schema + "'") == '0'
                    print('W19_DIAGNOSTIC_SCHEMA_DROPPED=true ' + json.dumps(label), flush=True)
        assert len(outcomes) == 4
        assert all(r['fresh'] == r['repeat'] == '1' and r['weak'] == '0' for r in outcomes), outcomes
        assert all(v == canonical_by_charset[('latin1', 0)] for v in canonical_by_charset.values()), 'Connection-dependent CHECK expression'
        print('W19_DIAGNOSTIC_REHEARSAL_PASSED=true ACTIVATION_CLEARED=false', flush=True)
    finally:
        if started:
            command(['docker', 'rm', '-f', '-v', container])
            assert container not in command(['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines()
            print('W19_DIAGNOSTIC_CONTAINER_REMOVED=true', flush=True)


if __name__ == '__main__':
    main()
