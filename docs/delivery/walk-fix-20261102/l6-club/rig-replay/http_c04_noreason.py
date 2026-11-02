"""Round 3 C-04: director UNLOCK_CHAPTER with no reason, read back game_session.current_chapter_id."""
import sys, time
from rig import *
S = load(); act = S['act']; out = sys.argv[1]
tok, _ = login('e2e-openid-9008')
v = cl.http("GET", "/api/game/session/view?activityId=%s&perspective=CLUB" % act, token=tok)
d = v.get('data') or {}; lines = []
def log(*a): lines.append(' '.join(str(x) for x in a)); print(*a)
opts = (d.get('club') or {}).get('chapterOptions') or []
log('before: currentChapterId', d.get('currentChapterId'), 'chapterOptions', J(opts))
body = {"activityId": act, "perspective": "CLUB", "action": "UNLOCK_CHAPTER", "requestId": "l6-unlock-noreason-%d" % int(time.time() * 1000),
        "expectedRevision": d.get('revision') or 0, "payload": {"chapterId": opts[0]['chapterId']} if opts else {}}
log('request payload (no reason):', J(body['payload']))
log('director UNLOCK_CHAPTER ->', J(cl.http("POST", "/api/game/session/command", token=tok, json_body=body)))
log('game_session read-back', cl.q('select id,status,current_chapter_id,revision from game_session where activity_id=%s', (act,)))
open(out, 'w').write('\n'.join(lines) + '\n')
