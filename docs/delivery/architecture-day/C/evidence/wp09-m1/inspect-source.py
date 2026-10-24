"""Read-only lexical inventory for M1 planning. Not AST, compilation, SQL or test execution."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

BASE = '9a2dcf21aef4d7aec7a14065a2601bd86aaad805'
OLD = 'd359cd2de8d4b5f78fc2f998db969ad8ae657327'
LATEST_ROOT = '6ecaea01e0e1a3f3182189615bc00cf9f791fd47'
CTRL = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java'
old = json.loads(subprocess.check_output(['git', 'show', OLD + ':docs/delivery/architecture-day/C/wp09-inventory.json']))
source = Path(CTRL).read_text()
assert Path(CTRL).read_bytes() == subprocess.check_output(['git', 'show', BASE + ':' + CTRL])
# Preserve positions/line numbers when masking literals and comments.
def mask(s):
    return re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), s, flags=re.S)
masked = mask(source)
pattern = re.compile(r'(?m)^    (?:public|private|protected)\s+(?:(?:static|final|synchronized)\s+)*[\w.<>, ?\[\]]+\s+(\w+)\s*\([^;{}]*\)\s*(?:throws[\w., ]+)?\{')
methods = []
for match in pattern.finditer(masked):
    opening = masked.index('{', match.start(), match.end()); level = 1; end = opening + 1
    while level:
        if masked[end] == '{': level += 1
        elif masked[end] == '}': level -= 1
        end += 1
    methods.append(dict(name=match.group(1), start=match.start(), opening=opening, end=end))
helpers = {'resolveCtx', 'activityTimeError', 'isNightNow', 'leadGateError', 'routeAccessError', 'isDelistedNode',
           'hasCompletedNodeFact', 'findNodeProgressRow', 'resolveMode', 'firstTicketMode', 'isPrevDone', 'unlockGateError'}
selected = [m for m in methods if m['name'] in helpers]
assert len(selected) == 14
rows = []
for method in selected:
    body = source[method['start']:method['end']]
    plain = masked[method['opening']:method['end']]
    callers = []
    for other in methods:
        if other is method: continue
        for call in re.finditer(r'\b' + method['name'] + r'\s*\(', masked[other['opening']:other['end']]):
            line = source.count('\n', 0, other['opening'] + call.start()) + 1
            callers.append(dict(symbol=other['name'], line=line))
    rows.append(dict(symbol=method['name'], signature=source[method['start']:method['opening']].strip(),
                     line=source.count('\n', 0, method['start']) + 1,
                     body_sha256=hashlib.sha256(body.encode()).hexdigest(),
                     shared_calls_in_lexical_order=re.findall(r'\b(' + '|'.join(sorted(helpers)) + r')\s*\(', plain),
                     dependency_calls_in_lexical_order=re.findall(r'\b(\w+)\.(\w+)\s*\(', plain),
                     callers=callers))
endpoints = ['nodes', 'checkin', 'checkinOnce', 'answer', 'answerOnce', 'revealPuzzle', 'puzzleHint', 'resolvePreferencePlayCtx',
             'sensorResult', 'arrive', 'arriveOnce', 'photo', 'unlockHint', 'isTopicCompleted', 'advanceProgress']
orders = []
for m in methods:
    if m['name'] not in endpoints: continue
    calls = re.findall(r'\b(' + '|'.join(sorted(helpers | {'hintUnlockCost'})) + r')\s*\(', masked[m['opening']:m['end']])
    if calls: orders.append(dict(symbol=m['name'], signature=source[m['start']:m['opening']].strip(), shared_calls_in_lexical_order=calls))
access_fields = {'cmsActivityService', 'currentPlayActivityMapper', 'cmsRegistrationMapper', 'cmsTopicNodeService',
                 'playerNodeProgressService', 'cmsTopicService', 'omsTicketService', 'clubLeadMapper', 'gameSessionRuntimeService',
                 'cmsTopicChapterService', 'topicRouteRuntimeService'}
current = {str(p) for p in Path('chengyinhub-admin/src/test/java').rglob('*.java') if 'ApiPlayProgressController' in p.read_text()}
assert len(old['controllerReferenceJavaTests']) == 55 and len(old['rawControllerConstructorJavaTests']) == 41
assert not set(old['controllerReferenceJavaTests']) - current
items = []
for path in old['controllerReferenceJavaTests'] + sorted(current - set(old['controllerReferenceJavaTests'])):
    text = Path(path).read_text(); lines = text.splitlines()
    bindings = [dict(line=i+1, statement=line.strip()) for i, line in enumerate(lines)
                if any('"'+field+'"' in line for field in access_fields) and 'setField' in line]
    items.append(dict(path=path, d359_reference=path in old['controllerReferenceJavaTests'],
                      d359_direct_new=path in old['rawControllerConstructorJavaTests'],
                      current_direct_new=bool(re.search(r'new\s+ApiPlayProgressController\s*\(',text)),
                      sha256=hashlib.sha256(Path(path).read_bytes()).hexdigest(),
                      constructor_lines=[i+1 for i,line in enumerate(lines) if re.search(r'new\s+ApiPlayProgressController\s*\(',line)],
                      helper_reflections=[h for h in sorted(helpers) if '"'+h+'"' in text], access_bindings=bindings,
                      calls_current_fixture='CurrentPlayControllerFixtures.install(' in text))
assert len(items) == 59 and sum(x['current_direct_new'] for x in items) == 45
files = [CTRL, 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/IPuzzlePlayService.java',
         'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PuzzlePlayServiceImpl.java',
         'chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/CurrentPlayRegistration.java']
for path in files + [r['path'] for r in items]:
    assert subprocess.check_output(['git','rev-parse',BASE+':'+path]) == subprocess.check_output(['git','rev-parse',LATEST_ROOT+':'+path])
output = dict(latest_root_source_compared=LATEST_ROOT, latest_root_identical_scope_files=len(files)+len(items), status='DOC_ONLY_M1_CONTRACT_NOT_AUTHORIZED_FOR_SOURCE_CHANGES', base=BASE,
              old_plan_commit=subprocess.check_output(['git','rev-parse',OLD]).decode().strip(),
              controller_blob=subprocess.check_output(['git','rev-parse',BASE+':'+CTRL]).decode().strip(),
              counts=dict(shared_implementations=14,d359_reference_files=55,d359_direct_new_files=41,current_reference_files=59,current_direct_new_files=45),
              limitations=['lexical overload callers are conservative union, not AST resolution', 'source counts are not executed tests', 'no Java/MySQL/CI executed'],
              source_files=[dict(path=f,sha256=hashlib.sha256(Path(f).read_bytes()).hexdigest()) for f in files],
              shared_helpers=rows, caller_orders=orders, test_files=items,
              puzzle_direct_construct_files=old['puzzleConstructorJavaTests'])
print(json.dumps(output,ensure_ascii=False,indent=2))
