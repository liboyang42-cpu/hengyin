"""RIG SCHEMA GAP: apply a repo migration the rig's base snapshot did not include, statement by statement with the
repo's own sql_splitter, against the throwaway cy_e2e_51 only. Usage: rig_apply_migration.py <file.sql>"""
import sys, pymysql
sys.path.insert(0, '/root/walk-rig/candidate/ci')
from sql_splitter import split_statements, executable_statement
c = pymysql.connect(host='127.0.0.1', port=3316, user='root', password='', database='cy_e2e_51', charset='utf8mb4', autocommit=True)
cur = c.cursor()
for st in split_statements(open(sys.argv[1], encoding='utf-8').read()):
    if not executable_statement(st): continue
    try:
        cur.execute(st)
        r = cur.fetchall() if cur.description else None
        if r and len(r) == 1 and len(r[0]) == 1: print('assert/select ->', r[0][0])
    except Exception as e:
        print('ERR', str(e)[:200], '|', st.strip()[:120].replace('\n', ' '))
print('done')
