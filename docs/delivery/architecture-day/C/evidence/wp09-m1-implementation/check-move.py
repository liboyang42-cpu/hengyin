"""Source fingerprints only. Never runs Java, a database, HTTP or CI."""
from pathlib import Path
import subprocess,re,json,hashlib
BASE='9a2dcf21aef4d7aec7a14065a2601bd86aaad805'
CTRL='chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java'
PUZZLE='chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PuzzlePlayServiceImpl.java'
ACCESS='chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/PlayNodeAccessService.java'
def old(path):return subprocess.check_output(['git','show',BASE+':'+path]).decode()
pattern=r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
def mask(s):return re.sub(pattern,lambda m:''.join('\n' if c=='\n' else ' ' for c in m.group()),s,flags=re.S)
def tokens(s):
    return [m.group() for m in re.finditer(pattern+r'|\w+|[^\s]',s,re.S) if not m.group().startswith(('//','/*'))]
def digest(s):return hashlib.sha256(json.dumps(tokens(s),ensure_ascii=False).encode()).hexdigest()
def methods(s):
    ms=mask(s);out=[]
    expr=r'(?m)^    (?:public|private|protected)\s+(?:(?:static|final|synchronized)\s+)*[\w.<>, ?\[\]]+\s+(\w+)\s*\([^;{}]*\)\s*(?:throws[\w., ]+)?\{'
    for m in re.finditer(expr,ms):
        a=ms.index('{',m.start(),m.end());d=1;b=a+1
        while d:d+=(ms[b]=='{')-(ms[b]=='}');b+=1
        signature=s[m.start():a].strip();out.append(dict(name=m.group(1),signature=signature,body=s[a:b],line=s.count('\n',0,m.start())+1))
    return out
orig=methods(old(CTRL));now=methods(Path(CTRL).read_text());access=methods(Path(ACCESS).read_text());puz=methods(Path(PUZZLE).read_text())
shared={'resolveCtx','activityTimeError','isNightNow','leadGateError','routeAccessError','isDelistedNode','hasCompletedNodeFact','findNodeProgressRow','resolveMode','firstTicketMode','isPrevDone','unlockGateError'}
def bysig(rows,sig):
    found=[r for r in rows if r['signature']==sig];assert len(found)==1,sig
    return found[0]
rows=[]
for r in orig:
    name=r['name'];owner=CTRL
    if name in shared:
        sig=r['signature'].replace('private int resolveMode','public int resolveMode').replace('findNodeProgressRow(', 'findNodeProgressEntity(')
        current=bysig(access,sig);owner=ACCESS
        transformed=r['body'].replace('findNodeProgressRow(', 'findNodeProgressEntity(').replace('new AjaxResult(402,denied.getMessage())','new Failure(402,denied.getMessage())').replace('new AjaxResult(402, "请先购买自玩通行证")','new Failure(402, "请先购买自玩通行证")')
        assert tokens(transformed)==tokens(current['body']),name
        kind='moved single algorithm; error carrier/one private symbol adapted'
    elif name=='hintUnlockCost':
        current=bysig(puz,r['signature'].replace('private int','public int'));owner=PUZZLE
        assert tokens(r['body'].replace('cmsPointsSettingService','settingsService'))==tokens(current['body'])
        kind='moved single price algorithm'
    elif name=='unlockHint' and 'String activityId' in r['signature']:
        current=next(m for m in puz if m['name']=='unlockNodeHint');owner=PUZZLE;kind='complete command moved; typed carrier/owner rewiring reviewed'
    else:
        current=bysig(now,r['signature']);assert tokens(r['body'])==tokens(current['body']),r['signature'];kind='unchanged body'
    rows.append(dict(symbol=name,old_signature=r['signature'],old_body_fingerprint=digest(r['body']),old_line=r['line'],target_path=owner,target_signature=current['signature'],new_body_fingerprint=digest(current['body']),new_line=current['line'],classification=kind,old_dependency_fingerprint=hashlib.sha256(json.dumps(re.findall(r'\b(\w+)\.(\w+)\s*\(',mask(r['body']))).encode()).hexdigest(),new_dependency_fingerprint=hashlib.sha256(json.dumps(re.findall(r'\b(\w+)\.(\w+)\s*\(',mask(current['body']))).encode()).hexdigest()))
old_private=[r for r in orig if r['signature'].startswith('private ')]
assert len(old_private)==68 and len({r['name'] for r in old_private})==58
old_puzzle=methods(old(PUZZLE))
for r in old_puzzle:assert tokens(r['body'])==tokens(bysig(puz,r['signature'])['body']),r['signature']
# Exact original debit fragment, including all monetary fields, uniqueness catch and order.
unlock=next(r['body'] for r in puz if r['name']=='unlockNodeHint')
old_unlock=next(r['body'] for r in orig if r['name']=='unlockHint' and 'String activityId' in r['signature'])
assert unlock.index('detailService.insertUmsMemberPointsDetail')<unlock.index('memberService.deductPoints')
assert 'lockMember(' not in unlock and 'UUID' not in unlock and 'expectedVersion' not in unlock
assert 'accessService.routeAccessError(memberId, ctx.activityId(), ctx.topicId(), nodeScope(node), null)' in unlock
# Compare economic sequence, replacing only owner and typed result mapping; retain string literals exactly.
a=old_unlock.index('        UmsMember member =');b=old_unlock.index('\n        Map<String, Object> data =',old_unlock.index('umsMemberService.deductPoints'))
old_economy=old_unlock[a:b]
old_economy=old_economy.replace('umsMemberService','memberService').replace('umsMemberPointsDetailService','detailService').replace('return error(', 'return hintFailure(')
map_pattern=r'Map<String, Object> data = new LinkedHashMap<>\(\);\s*data.put\("hint1", tpl.getHint1\(\)\);\s*data.put\("hint2", tpl.getHint2\(\)\);\s*data.put\("cost", 0\);\s*return AjaxResult.success\(data\);'
old_economy=re.sub(map_pattern,'return hintResult(tpl, 0);',old_economy)
a=unlock.index('        UmsMember member =');b=unlock.index('\n        return hintResult(tpl, cost);',a)
assert tokens(old_economy)==tokens(unlock[a:b])
# Constructor call arity across the repository, preserving original six Puzzle APIs.
constructors=[]
for p in Path('chengyinhub-admin/src/test/java').rglob('*.java'):
    s=p.read_text();ms=mask(s)
    for m in re.finditer(r'new\s+(?:[\w.]+\.)?PuzzlePlayServiceImpl\s*\(',s):
        d=1;i=m.end();commas=0
        while d:
            c=ms[i]
            if c=='(' :d+=1
            elif c==')':d-=1
            elif c==',' and d==1:commas+=1
            i+=1
        assert commas+1==9,(p,commas+1)
        constructors.append(dict(path=str(p),line=s.count('\n',0,m.start())+1,arity=9))
# Root-owned test file is unchanged; review patch remains applicable.
hint_it='chengyinhub-admin/src/test/java/com/chengyinhub/business/it/PlayHintUnlockMySqlIT.java'
assert Path(hint_it).read_text()==old(hint_it)
subprocess.run(['git','apply','--check','docs/delivery/architecture-day/C/evidence/wp09-m1-implementation/PlayHintUnlockMySqlIT.review.patch'],check=True)
# No original source-level @Test case removed from the 59-file plan.
plan=json.loads(Path('docs/delivery/architecture-day/C/wp09-m1-source-inventory.json').read_text());test_rows=[]
for item in plan['test_files']:
    path=item['path'];before=old(path);after=Path(path).read_text()
    case_pattern=r'@(?:Test|ParameterizedTest|RepeatedTest)(?:\([^)]*\))?\s+(?:public\s+)?void\s+(\w+)'
    a=set(re.findall(case_pattern,before));b=set(re.findall(case_pattern,after));assert a<=b,(path,a-b)
    test_rows.append(dict(path=path,source_case_count_before=len(a),source_case_count_after=len(b),original_cases_preserved=True,changed=before!=after))
# Changed and new Java source lexical balance is explicitly not compilation.
java_paths=set(subprocess.check_output(['git','diff',BASE,'--name-only']).decode().splitlines())
java_paths.update(subprocess.check_output(['git','ls-files','--others','--exclude-standard']).decode().splitlines())
lexical=[]
for f in sorted(java_paths):
    if not f.endswith('.java'):continue
    s=mask(Path(f).read_text());stack=[]
    for c in s:
        if c in '([{':stack.append(c)
        elif c in ')]}':assert stack and stack.pop()==dict(zip(')]}','([{'))[c],f
    assert not stack,f;lexical.append(dict(path=f,sha256=hashlib.sha256(Path(f).read_bytes()).hexdigest()))
output=dict(base=BASE,status='SOURCE_CHECK_ONLY_NOT_JAVA_MYSQL_VALIDATION',original_controller_method_count=len(orig),original_private_implementations=68,original_unique_private_names=58,
    shared_algorithms_transferred=14,original_puzzle_method_bodies_unchanged=len(old_puzzle),economic_fragment_token_equal=True,
    no_original_test_cases_removed=True,root_hint10_unchanged_and_patch_applicable=True,method_migration=rows,
    constructors=constructors,original_test_files=test_rows,java_lexical_checks=lexical,
    direct_application_source_tests=len(re.findall(r'@Test\s+void',Path('chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/NodeHintUnlockApplicationTest.java').read_text())),
    java_compile='NOT RUN',junit='NOT RUN',mysql='NOT RUN',http='NOT RUN',ci_dispatch='NOT PERFORMED')
# One entry per source symbol/file keeps the review diff small; all fingerprints remain reproducible.
print('{')
entries=list(output.items())
for index,(key,value) in enumerate(entries):
    suffix=',' if index<len(entries)-1 else ''
    if isinstance(value,list):
        print('  '+json.dumps(key)+': [')
        for i,row in enumerate(value):
            print('    '+json.dumps(row,ensure_ascii=False,separators=(',',':'))+(',' if i<len(value)-1 else ''))
        print('  ]'+suffix)
    else: print('  '+json.dumps(key)+': '+json.dumps(value,ensure_ascii=False)+suffix)
print('}')
