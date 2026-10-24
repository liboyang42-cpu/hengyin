#!/usr/bin/env python3
"""Source identity and SQL contract check only; Java/MySQL/provider execution is NOT_RUN."""
import ast
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[5]
BASE='0d32d888e29c6d5ef51322b10d7aca736be856ff'
source=subprocess.check_output(['git','show','10426a532822ee8053cd75e3d77da5b6393d30c2:docs/delivery/architecture-day/E/VOICE-TRANSPORT/verify-static.py'],cwd=ROOT,text=True)
function=next(n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='methods')
ns={'re':re};exec(compile(ast.Module(body=[function],type_ignores=[]),'<frozen-parser>','exec'),ns);methods=ns['methods']
for path,changed in [
 ('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiMerchantController.java',{'npcVoiceEnroll','npcVoiceStatus'}),
 ('chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/NodeNpcService.java',{'voiceEnroll','voiceStatus'}),
 ('chengyinhub-system/src/main/java/com/chengyinhub/business/domain/NpcProfile.java',set())]:
 old=subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT,text=True);new=(ROOT/path).read_text();before,after=methods(old),methods(new)
 assert set(before)<=set(after)
 assert {k[0] for k in before if before[k]!=after[k]}==changed
 for k in before:
  if k[0] in changed:
   assert 'voiceEnrollmentService.' in after[k]
   assert 'voiceCloneClient.enroll(' not in after[k]
   assert 'voiceCloneClient.isReady(' not in after[k]
 for fragment in ['requireCurrentAgreement','requireOwnLiveNode','requireOwnNode','requireAttachment']:
  assert old.count(fragment)==new.count(fragment),(path,fragment)
print('PASS: only four authorized caller bodies change; all other method bodies/constructors/reset remain exact; original guard occurrences retained')
for path in ROOT.rglob('*.java'):
 if any(t in str(path) for t in ['NpcVoiceEnrollment','NpcProfile.java','NodeNpcService.java','ApiMerchantController.java']):methods(path.read_text())
mapper=ET.parse(ROOT/'chengyinhub-system/src/main/resources/mapper/business/NpcVoiceEnrollmentMapper.xml').getroot()
for select in mapper.findall('select'):
 assert select.get('useCache')=='false' and select.get('flushCache')=='true'
 assert 'FOR UPDATE' in ''.join(select.itertext())
assert [x.get('id') for x in mapper.findall('select')]==['lockProfile','find','findUnresolved']
for update in mapper.findall('update'):
 text=''.join(update.itertext())
 assert 'sample_reference=' not in text and 'sample_sha256=' not in text.split('WHERE')[0]
 assert 'scope_id=' not in text.split('WHERE')[0]
sql=(ROOT/'chengyinhub-admin/sql/migration_npc_voice_enrollment_intent_20261024.sql').read_text()
assert len(re.findall(r'^CREATE TABLE ',sql,re.M))==1
assert not re.search(r'^\s*(ALTER|DROP|TRUNCATE|DELETE)\b',sql,re.M|re.I)
assert 'UNIQUE KEY uk_nvei_sample(npc_id,sample_sha256)' in sql
assert sql.index('information_schema.table_constraints')<sql.index('CREATE TABLE npc_voice_enrollment_intent')
assert len(re.findall(r'  CONSTRAINT chk_nvei_',sql))==4
service=(ROOT/'chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/NpcVoiceEnrollmentService.java').read_text()
assert service.count('provider.enroll(')==1 and 'Thread.sleep' not in service
assert service.index('Decision claimed = tx(')<service.index('voiceId = provider.enroll(')
assert service.index('finish(scopeType, scopeId, npcId, hash, "PENDING"')<service.index('return refresh(')
assert 'PROPAGATION_REQUIRES_NEW' in service and 'isActualTransactionActive()' in service
assert 'getSendDisposition() == HttpRequestException.SendDisposition.NOT_SENT' in service
assert 'getClass() == HttpRequestException.class' in service
assert 'getMessage()' not in service and 'getStatusCode()' not in service
counts={}
for name,expected in [('NpcVoiceEnrollmentServiceTest.java',14),('NpcVoiceEnrollmentCallerTest.java',4),('NpcVoiceEnrollmentMySqlIT.java',3)]:
 files=list(ROOT.rglob(name));assert len(files)==1
 count=len(re.findall(r'@Test\b',files[0].read_text()));assert count==expected,(name,count);counts[name]=count
print('PASS: all locking reads have both cache flags; immutable input/scope not updated; unique durable identity; only one external create site; committed-claim/result ordering; test source cases',counts)
print('NOT_RUN: Java/JUnit/MySQL, real provider, CI, full gitleaks; old fixture assertion migration requires root review')
