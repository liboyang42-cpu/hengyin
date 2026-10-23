#!/usr/bin/env python3
"""Static-only scope/source/seed checks. Does not execute Java, SQL or business effects."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]
BASE = '2ec68899099a1e250df0949a8383bf85b5d3ffe0'
REPORT = ROOT / 'docs/delivery/architecture-day/G/WP08'
PAIRS = {
    'imNotificationDeliveryTask.deliverPending()': 'com.chengyinhub.business.task.ImNotificationDeliveryTask',
    'roamRewardDeliveryTask.run()': 'com.chengyinhub.business.task.RoamRewardDeliveryTask',
}

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT).decode()

def sha(data):
    return hashlib.sha256(data).hexdigest()

def main():
    assert git('config','--get','remote.origin.url').strip() == 'https://github.com/liboyang42-cpu/hengyin.git'
    subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],cwd=ROOT,check=True)
    manifest = json.loads((REPORT/'source-hashes.json').read_text())
    files = manifest['files']
    changed = set(git('diff','--name-only',BASE).splitlines()) | set(git('ls-files','--others','--exclude-standard').splitlines())
    assert all(p in files or p.startswith('docs/delivery/architecture-day/G/WP08/') for p in changed), sorted(changed-set(files))
    for path, expected in files.items():
        assert sha((ROOT/path).read_bytes()) == expected, path
    identity = sha(''.join(f'{p}\0{files[p]}\n' for p in sorted(files)).encode())
    assert identity == manifest['source_sha256']
    for path, expected in manifest['protected_files'].items():
        before = subprocess.check_output(['git','show',f'{BASE}:{path}'],cwd=ROOT)
        assert (ROOT/path).read_bytes() == before and sha(before) == expected, path
    code_path = 'chengyinhub-quartz/src/main/java/com/chengyinhub/quartz/util/ScheduleUtils.java'
    code = (ROOT/code_path).read_text()
    for target, clazz in PAIRS.items():
        entry = f'            Map.entry("{target}", "{clazz}"),\n'
        assert code.count(entry) == 1
        code = code.replace(entry,'')
    assert code == git('show',f'{BASE}:{code_path}'), 'production delta must be exactly two entries'
    sql_path = 'chengyinhub-admin/sql/migration_20261023_g_wp08_delivery_jobs_paused.sql'
    sql = '\n'.join(line for line in (ROOT/sql_path).read_text().splitlines() if not line.lstrip().startswith('--'))
    assert not re.search(r'\b(?:CREATE|ALTER|DROP|UPDATE|DELETE|TRUNCATE|REPLACE|CALL)\s', sql, re.I)
    assert len(re.findall(r'\bINSERT INTO sys_job\b',sql)) == 2
    assert sql.count('START TRANSACTION;') == 1 and sql.count('COMMIT;') == 1
    inserts = re.findall(r"INSERT INTO sys_job\([^;]+;",sql)
    for target, cron in [('imNotificationDeliveryTask.deliverPending()','0 0/1 * * * ?'),('roamRewardDeliveryTask.run()','0 0/10 * * * ?')]:
        insert = next(i for i in inserts if "'"+target+"'" in i)
        assert "'"+cron+"','3','1','1','system'" in insert
        assert 'WHERE NOT EXISTS' in insert and 'TRIM(invoke_target)' in insert
    assert 'HAVING COUNT(*)>1' in sql and sql.index('HAVING COUNT(*)>1') < sql.index('INSERT INTO')
    assert 'REGEXP_LIKE' in sql and sql.index('REGEXP_LIKE') < sql.index('INSERT INTO')
    test = (ROOT/'chengyinhub-admin/src/test/java/com/chengyinhub/quartz/util/ScheduleUtilsTest.java').read_text()
    for target, clazz in PAIRS.items():
        assert f'Arguments.of("{target}", "{clazz}")' in test
    for path in changed:
        if path.endswith('verify_static.py'): continue
        data = (ROOT/path).read_text(errors='replace')
        assert not re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\bghp_[A-Za-z0-9]{30,}|\bgithub_pat_[A-Za-z0-9_]{30,}|\bAKIA[A-Z0-9]{16}\b', data), path
    subprocess.run(['git','diff','--check',BASE],cwd=ROOT,check=True)
    print(json.dumps({'kind':'STATIC_ONLY','source_sha256':identity,'source_files':len(files),
                     'protected_files_unchanged':len(manifest['protected_files']),'production_delta':'EXACTLY_TWO_MAP_ENTRIES',
                     'new_seed':'TWO_INSERT_IF_MISSING_PAUSED_ORIGINAL_CRON_NO_DDL',
                     'java':'NOT_RUN','mysql':'NOT_RUN','permissions_and_operational_activation':'NOT_VERIFIED'},ensure_ascii=False,indent=2))

if __name__ == '__main__': main()
