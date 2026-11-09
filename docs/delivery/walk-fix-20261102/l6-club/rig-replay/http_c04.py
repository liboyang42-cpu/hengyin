"""C-04 HTTP: director projection + old lead-entry unlock + (if offered) director UNLOCK_CHAPTER, read back."""
import sys, time
from rig import *
S = load(); act = S['act']; outdir = sys.argv[1]
tok, _ = login('e2e-openid-9008')
def view(): return cl.http("GET", "/api/game/session/view?activityId=%s&perspective=CLUB" % act, token=tok)
v = view(); json.dump(v, open(outdir + '/http-C-04.json', 'w'), ensure_ascii=False, indent=1, default=str)
d = v.get('data') or {}; lines = []
def log(*a): lines.append(' '.join(str(x) for x in a)); print(*a)
log('director status', d.get('status'), 'currentChapterId', d.get('currentChapterId'), 'availableActions', d.get('availableActions'))
log('chapterOptions', J((d.get('club') or {}).get('chapterOptions')))
ch = cl.q('select id,name,sort_id from cms_topic_chapter where topic_id=%s order by sort_id', (S['topic'],))
log('topic chapters', ch)
r = cl.http('POST', '/api/club/lead/unlock-chapter', token=tok, form={'activityId': act, 'commandId': 'l6-lead-unlock-%d' % int(time.time()), 'expectedChapterId': 0, 'targetChapterId': ch[0][0]})
log('POST /api/club/lead/unlock-chapter (old play-page entry) ->', J(r))
opts = (d.get('club') or {}).get('chapterOptions') or []
# 玩家视角探针:第 2 章的站点在放行前/后是否被章节闸挡住(经 /api/play/puzzle/hint → leadGateError)
ptok, _ = login('walk-player-1')
ch2_nodes = cl.q('select n.id from cms_topic_node n where n.topic_id=%s and n.chapter_id=%s order by n.id limit 1', (S['topic'], ch[1][0])) if len(ch) > 1 else []
def probe(tag):
    if not ch2_nodes: log('no chapter-2 node to probe'); return
    r = cl.http('POST', '/api/play/puzzle/hint', token=ptok, form={'activityId': act, 'nodeId': ch2_nodes[0][0], 'topicId': S['topic'], 'level': 1})
    log('player chapter-2 node probe (%s) -> %s' % (tag, J(r)[:200]))
probe('before release')
if 'UNLOCK_CHAPTER' in (d.get('availableActions') or []) and opts:
    body = {"activityId": act, "perspective": "CLUB", "action": "UNLOCK_CHAPTER", "requestId": "l6-unlock-%d" % int(time.time() * 1000),
            "expectedRevision": d.get('revision') or 0, "payload": {"chapterId": opts[0]['chapterId'], "reason": "全队到齐"}}
    log('director UNLOCK_CHAPTER ->', J(cl.http("POST", "/api/game/session/command", token=tok, json_body=body)))
    log('game_session read-back', cl.q('select id,status,current_chapter_id,revision from game_session where activity_id=%s', (act,)))
    probe('after release')
else:
    log('director offers no UNLOCK_CHAPTER for this session')
open(outdir + '/http-C-04.txt', 'w').write('\n'.join(lines) + '\n')
