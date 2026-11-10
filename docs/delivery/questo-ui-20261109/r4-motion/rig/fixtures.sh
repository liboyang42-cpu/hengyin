#!/bin/bash
# r4-motion rig fixtures (HARNESS, rig DB only): self-play theme + two-chapter theme + referral token + team seed
set -u
cd "$(dirname "$0")"
export CAND=$HOME/walk-rig/candidate PATH=$HOME/walk-rig/work/bin:$PATH RIG_HOME=$HOME/walk-rig
PY=$HOME/walk-rig/venv/bin/python
$PY free_orient_theme.py > free.txt 2>&1; T1=$(grep -o 'TOPIC_ID=[0-9]*' free.txt | tail -1 | cut -d= -f2); echo "SELF_TOPIC=$T1"
$PY two_chapter_theme.py > two.txt 2>&1; T2=$(grep -o 'TOPIC_ID=[0-9]*' two.txt | tail -1 | cut -d= -f2); echo "TWO_TOPIC=$T2"
$PY issue_referral.py "$T1" > referral.txt 2>&1; echo "REFERRAL: $(head -c 300 referral.txt)"
sed "s/990014/$T1/g" seed-team.sql.txt > seed-team.sql
$PY - <<PYEOF > team.txt 2>&1
import os, sys, pathlib
sys.path.insert(0, str(pathlib.Path(os.environ['CAND']) / 'scripts' / 'e2e'))
for p in ('HTTP_PROXY','HTTPS_PROXY','http_proxy','https_proxy','ALL_PROXY','all_proxy'): os.environ.pop(p, None)
from chain_lib import ex, q
sql = open('seed-team.sql').read()
for stmt in [s.strip() for s in sql.split(';') if s.strip() and not s.strip().startswith('--')]:
    body = '\n'.join(l for l in stmt.splitlines() if not l.strip().startswith('--')).strip()
    if body: ex(body)
ex("UPDATE cms_topic SET team_mode=1 WHERE id=$T1")
print('team', q("SELECT id, owner_id, leader_member_id, joined_count FROM play_team WHERE id=501"))
print('topic', q("SELECT id, team_mode, self_play FROM cms_topic WHERE id=$T1"))
PYEOF
cat team.txt
echo "SELF_TOPIC=$T1 TWO_TOPIC=$T2" > ids.txt
