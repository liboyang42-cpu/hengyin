"""C-25 check: the NO_SHOW recipient SQL from this branch, run with the session clock in Shanghai time (the rig MySQL runs +00:00
while Java writes Shanghai wall time; production runs +08:00 — same harness note as the club walk)."""
from rig import *
S = load(); act = S['act']
import re
xml = open(CAND + '/chengyinhub-system/src/main/resources/mapper/business/ClubEventOpsMapper.xml').read()
sql = re.search(r'<select id="selectNoShowRecipients"[^>]*>(.*?)</select>', xml, re.S).group(1)
sql = re.sub(r'<!--.*?-->', '', sql, flags=re.S).replace('&lt;', '<').replace('&gt;', '>')
club = cl.q('select club_id from cms_activity where id=%s', (act,))[0][0]
sql = sql.replace('#{clubId}', str(int(club))).replace('#{activityId}', str(int(act)))
c = cl.db(); cur = c.cursor()
cur.execute("set time_zone = '+08:00'")
cur.execute(sql); rows = cur.fetchall()
cur.execute('select now(), start_date, end_date from cms_activity where id=%s', (act,)); clock = cur.fetchall()
cur.close(); c.close()
print('clock (now, start, end):', clock)
print('NO_SHOW recipients (session started, not arrived):', rows)
names = cl.q('select id, nickname from ums_member where id in (%s)' % ','.join(str(r[0]) for r in rows)) if rows else ()
print('who:', names)
