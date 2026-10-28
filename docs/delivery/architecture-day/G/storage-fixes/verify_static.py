#!/usr/bin/env python3
"""Source/static checks only. Does not compile Java, connect to MySQL, or simulate money."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT / 'ci'))
from migration_gates import check_sql_text
from sql_splitter import split_statements, executable_statement
BASE = '61834b1ba96fcfb025d8442955b69f1d6b35d5f3'
IT = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/Wp04CashEvidenceExpandMySqlIT.java'
OUT = ROOT / 'docs/delivery/architecture-day/G/storage-fixes'


def methods(text):
    out = {}
    for match in re.finditer(r'@Test void (\w+)\([^)]*\)(?: throws Exception)? \{', text):
        start = match.end() - 1
        position = start
        depth = 0
        quote = None
        line = block = False
        while position < len(text):
            c, following = text[position], text[position + 1:position + 2]
            if line:
                if c == '\n':
                    line = False
            elif block:
                if c == '*' and following == '/':
                    block = False
                    position += 1
            elif quote:
                if c == '\\':
                    position += 1
                elif c == quote:
                    quote = None
            elif c in ['"', "'"]:
                quote = c
            elif c == '/' and following == '/':
                line = True
                position += 1
            elif c == '/' and following == '*':
                block = True
                position += 1
            elif c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    out[match.group(1)] = text[start:position + 1]
                    break
            position += 1
        else:
            raise AssertionError('method body missing: ' + match.group(1))
    return out


def main():
    old = subprocess.check_output(['git', 'show', BASE + ':' + IT], cwd=ROOT, text=True)
    new = (ROOT / IT).read_text()
    prior, current = methods(old), methods(new)
    assert len(prior) == 30 and len(current) == 45
    for name, body in prior.items():
        assert current[name] == body, name
    marker = '    private void expand()'
    assert old[old.index(marker):] == new[new.index(marker):]
    assert old[:old.index('    @Test void ')] == new[:new.index('    @Test void ')]
    print('ORIGINAL_30_BODIES_AND_FIXTURE_HEADER_HELPER_TAIL_BYTE_IDENTICAL PASS')
    patch = subprocess.check_output(['git', 'show', '4b6bbf16ccd6cc554f6a26d02875db36312b08a8:docs/delivery/architecture-day/H/g-618-structure/structure-negative.review.patch'], cwd=ROOT, text=True)
    added = '\n'.join(line[1:] for line in patch.splitlines() if line.startswith('+') and not line.startswith('+++'))
    negative = methods(added)
    assert len(negative) == 4
    for name, body in negative.items():
        if name == 'interruptedEmptyMigrationNeedsSafeRollbackBeforeAdjustmentAlterExists':
            body = body.replace('()->run("_deploy_g_cash_evidence_empty_rollback_20261023.sql")',
                '()->GCashEvidenceEmptyRecovery.recoverEmpty(source,sql.toAbsolutePath().normalize().getParent().getParent())')
        assert current[name] == body, name
    print('H4_BODIES_IDENTICAL_EXCEPT_AUTHORIZED_STAGE_RECOVERY_ROUTE PASS')
    (OUT / 'original30-preserved.json').write_text(json.dumps({
        'baseline': BASE,
        'original_methods': [{'method_name': name, 'body_sha256': hashlib.sha256(body.encode()).hexdigest()}
                             for name, body in prior.items()],
        'unchanged_fixture_helper_tail_sha256': hashlib.sha256(old[old.index(marker):].encode()).hexdigest(),
        'total_authored_test_methods': len(current),
        'new_methods': sorted(current.keys() - prior.keys()),
        'java_mysql_executed_here': 0,
    }, ensure_ascii=False, indent=2) + '\n')
    sql_root = ROOT / 'chengyinhub-admin/sql'
    migration = (sql_root / 'migration_20261023_g_cash_evidence_expand.sql').read_text()
    precheck = (sql_root / '_precheck_g_cash_evidence_20261023.sql').read_text()
    first = migration[:migration.index('CREATE TABLE coop_settlement_cash_close')]
    tables = re.findall(r'CREATE TABLE (\w+)', migration)
    columns = re.findall(r'ADD (cash_\w+) ', migration)
    indexes = re.findall(r'ADD (?:UNIQUE )?KEY (\w+)\(', migration)
    constraints = re.findall(r'CONSTRAINT ((?:fk|ck)_g_cash_\w+) ', migration)
    assert tuple(map(len, [tables, columns, indexes, constraints])) == (3, 7, 8, 16)
    for name in tables + columns + indexes + constraints:
        assert "'" + name + "'" in first and "'" + name + "'" in precheck, name
    print('COMPLETE_RESERVED_OBJECT_LISTS tables3 columns7 indexes8 constraints16 PASS')
    for name, count in [('migration_20261023_g_cash_evidence_expand.sql', 7),
                        ('_precheck_g_cash_evidence_20261023.sql', 1),
                        ('_deploy_g_cash_evidence_empty_rollback_20261023.sql', 7)]:
        text = (sql_root / name).read_text()
        assert check_sql_text(str(sql_root / name), text) == []
        canonical = [executable_statement(s).strip() for s in split_statements(text) if executable_statement(s).strip()]
        assert len(canonical) == count
        naive = [s.strip() for s in re.sub(r'(?m)^\s*--.*$', '', text).split(';') if s.strip()]
        assert naive == canonical  # This version-specific recovery splitter is valid for this frozen SQL.
        print('SQL_STATIC_GATE_AND_PINNED_RECOVERY_SPLIT PASS ' + name + ' ' + str(count))
    xml = ROOT / 'chengyinhub-system/src/main/resources/mapper/business/CoopSettlementCashEvidenceMapper.xml'
    mapper = ET.parse(xml).getroot()
    for query in mapper.findall('select'):
        assert query.attrib.get('useCache') == 'false' and query.attrib.get('flushCache') == 'true'
    print('XML_PARSE_AND_EXISTING_CURRENT_READ_FLAGS PASS')
    subprocess.run(['bash', '-n', str(sql_root / '_deploy_g_cash_evidence_empty_recovery.sh')], check=True)
    print('RECOVERY_LAUNCHER_BASH_SYNTAX PASS; NOT_EXECUTED')
    subprocess.run(['git', 'diff', '--check', BASE], cwd=ROOT, check=True)
    print('GIT_DIFF_CHECK PASS')
    subprocess.run(['python3', 'ci/secret-file-gate.py'], cwd=ROOT, check=True)
    print('AUTHORED45; JAVA_COMPILED0; MYSQL_EXECUTED0; CI_DISPATCHED0; PRODUCTION_DDL0')


if __name__ == '__main__':
    main()
