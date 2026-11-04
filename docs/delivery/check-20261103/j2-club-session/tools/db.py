#!/usr/bin/env python3
"""Read-back helper for the rig DB (throwaway cy_e2e_51). Usage: db.py "SELECT ..." """
import sys, pymysql
c = pymysql.connect(host='127.0.0.1', port=3316, user='root', password='', database='cy_e2e_51', charset='utf8mb4')
cur = c.cursor()
for sql in sys.argv[1:]:
    cur.execute(sql)
    if cur.description:
        print(' | '.join(d[0] for d in cur.description))
        for r in cur.fetchall(): print(' | '.join('' if v is None else str(v) for v in r))
    else:
        c.commit(); print('rows', cur.rowcount)
