#!/usr/bin/env python3
"""Only source/assembly checks. Does not compile or execute Java/DB/provider requests."""
from pathlib import Path
import ast,json,re,subprocess,tempfile,hashlib
ROOT=Path(__file__).resolve().parents[5]
BASE='341bfc2e7e0167e84f65fa9084d439605c267ff3'
def original(path):return subprocess.check_output(['git','show',BASE+':'+str(path)],cwd=ROOT,text=True)
reader=subprocess.check_output(['git','show','10426a532822ee8053cd75e3d77da5b6393d30c2:docs/delivery/architecture-day/E/VOICE-TRANSPORT/verify-static.py'],cwd=ROOT,text=True)
function=next(n for n in ast.parse(reader).body if isinstance(n,ast.FunctionDef) and n.name=='methods')
namespace={'re':re};exec(compile(ast.Module(body=[function],type_ignores=[]),'<frozen-reader>','exec'),namespace);parse_methods=namespace['methods']
def methods(source):return parse_methods(re.sub(r'(?m)^( {4})@Test[ \t]+(?=void )',r'\1',source))
files=['chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiMerchantNpcVoiceTest.java',
'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/NodeNpcServiceTest.java',
'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/NpcVoiceVersionRaceTest.java']
renamed={'enrollMarksFailedWhenUpstreamRejects':'enrollRecordsUnknownWhenUpstreamResponseIsUnconfirmed',
'voiceEnroll_upstreamFailureClearsVoiceId':'voiceEnroll_unconfirmedFailureKeepsOriginalUnknownIdentity'}
def cases(s):return re.findall(r'@Test\b(?:(?!@Test).)*?\bvoid\s+(\w+)\s*\(',s,re.S)
summary=[]
for path,expected in zip(files,[19,26,11]):
 old,new=original(path),(ROOT/path).read_text();before,after=methods(old),methods(new)
 assert len(cases(new))==expected
 for case in cases(old):assert renamed.get(case,case) in cases(new),('lost case',case)
 assert not re.search(r'@Disabled|assumeTrue|@Ignore|enabled\s*=\s*false',new)
 assert '"voiceEnrollmentService",' in new and 'NpcVoiceEnrollmentFixture(' in new
 unchanged=[name for name in cases(old) if any(k[0]==name and k in after and before[k]==after[k] for k in before)]
 summary.append({'file':Path(path).name,'original_cases':len(cases(old)),'current_cases':len(cases(new)),'unchanged_case_bodies':len(unchanged)})
 # Scope/permission/consent/media negatives and original reset/query fencing remain actual checks.
 for case in cases(old):
  if any(token in case.lower() for token in ['consent','foreign','frozen','scope','takedown','preset','blocked','bad','rejectsbad','requiresprofile','requiresaved','rowowned','noowner','nonowner','oldreadypoll','failedreadiness']):
   key=next(k for k in before if k[0]==case)
   normalize_scope=lambda text: re.sub(r'\s+','',re.sub(r'\b(?:row|mine|other)\.setScopeType\(2\);','',text))
   assert normalize_scope(before[key])==normalize_scope(after[key]),case
# Frozen current-bug witness is untouched; fix must satisfy it, not relax its reset assertion.
race=(ROOT/files[2]).read_text();assert 'assertEquals(0, h.status()); assertNull(h.stored.getVoiceId());' in race
service_path='chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/NpcVoiceEnrollmentService.java'
before,after=methods(original(service_path)),methods((ROOT/service_path).read_text())
assert [k[0] for k in before if before[k]!=after[k]]==['status','refresh']
for key in before:
 if key[0] in ['enroll','inspect','finish','provenNotSent','outsideBusinessTransaction','fingerprint','validate']:
  assert before[key]==after[key]
assert 'return snapshot(scopeType, scopeId, npcId, hash).profile();' in (ROOT/service_path).read_text()
# Voice wire values/parser/error outcomes are unchanged apart from payload-free logs and private reply correlation.
voice='chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/DashScopeVoiceClient.java'
old,new=original(voice),(ROOT/voice).read_text();before,after=methods(old),methods(new)
def normalize(body):
 body=re.sub(r'log\.error\([\s\S]*?\);','',body)
 body=re.sub(r'        String correlationId = [^\n]*\n','',body)
 body=body.replace('        String requestId = safeRequestId(json, correlationId);\n','')
 body=body.replace('ProviderReply reply = post','JSONObject out = post').replace('        JSONObject out = reply.output();\n','')
 body=body.replace('body, "查询音色").output();','body, "查询音色");').replace('return new ProviderReply(out, requestId);','return out;')
 return re.sub(r'\s+','',body)
for key in before:
 assert key in after,key
 assert normalize(before[key])==normalize(after[key]),('non-log voice body changed',key)
for call in re.findall(r'log\.error\([\s\S]*?\);',new):
 assert not any(token in call for token in ['raw','toJSONString','getMessage','getCause','getString("message")','what, e)'])
assert 'requestId={} action={} category={}' in new
assert 'throw new AiServiceException(what + "失败,请稍后再试", e);' in new
# Explicit cross-owner assembly delta, exactly 3 projection fields, no service/guard change.
path=Path('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiChapterMerchantNodeController.java')
patch=ROOT/'docs/delivery/architecture-day/E/VOICE-INTENT-FIXTURES/node-status.review.patch'
with tempfile.TemporaryDirectory(prefix='e-voice-projection-') as directory:
 temp=Path(directory);target=temp/path;target.parent.mkdir(parents=True);target.write_text(original(path))
 subprocess.run(['git','apply','--check',str(patch)],cwd=temp,check=True)
 subprocess.run(['git','apply',str(patch)],cwd=temp,check=True)
 updated=target.read_text();old_methods=methods(original(path));new_methods=methods(updated)
 assert [k[0] for k in old_methods if old_methods[k]!=new_methods[k]]==['npcVoiceStatus']
 for field in ['voiceIntentId','voiceIntentState','voiceReasonCode']:assert updated.count('data.put("'+field+'"')==1
for name,count in [('NpcVoiceEnrollmentServiceTest.java',15),('DashScopeVoiceLoggingTest.java',1),('NpcVoiceNodeStatusProjectionTest.java',4)]:
 p=next(ROOT.rglob(name));assert len(cases(p.read_text()))==count
for p in ROOT.rglob('*.java'):methods(p.read_text())
print('STATIC PASS:',json.dumps(summary))
print('STATIC PASS: original claim/identity/quota-proof methods exact; snapshot reset fix; voice wire/parser/outcome unchanged after removing logs/reply shim; shared node 3-field patch applies; Java lexical delimiters valid')
print('NOT_RUN: Java/JUnit/MySQL/provider/CI/full gitleaks. Shared node projection requires root applying the supplied patch.')
