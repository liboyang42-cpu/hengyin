import sys, pymysql
c = pymysql.connect(host='127.0.0.1', port=3316, user='root', password='', database='cy_e2e_51', charset='utf8mb4', autocommit=True)
cur = c.cursor()
for sql in sys.argv[1:]:
    try: cur.execute(sql)
    except Exception as e: print('SQLERR', e); continue
    if cur.description:
        print(' | '.join(d[0] for d in cur.description))
        for r in cur.fetchall(): print(' | '.join('' if v is None else str(v) for v in r))
    else: print('rows', cur.rowcount)
