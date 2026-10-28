#!/usr/bin/env python3
"""Checks source identities and lexical boundaries, never compiles/runs Java or business tests."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).resolve().parent
BASE='e683b5a0ebc44cc217ac75e378c271fd96c80ae0'
CONTRACT='ee96ffe45bab6126136e1c5582be53601c4f6fc3'
PREFIX='chengyinhub-system/src/main/java/com/chengyinhub/business/service/'
PRODUCTION=[PREFIX+p for p in ['IAiCreatorService.java','impl/AiCreatorServiceImpl.java',
 'ai/AiThemeDraftPlanRejectedException.java','ai/AiGatewayService.java','ai/impl/AiGatewayServiceImpl.java']]
TESTS=['chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiCreatorPreparedThemeDraftTest.java',
 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayPreparedThemeDraftTest.java']


def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def before(path):return git('show',BASE+':'+path)
def digest(data):return hashlib.sha256(data).hexdigest()
def mask(source):
 return re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/',
  lambda m:''.join('\n' if c=='\n' else ' ' for c in m.group()),source)
def method(source,name):
 code=mask(source)
 match=re.search(r'(?m)^\s*(?:public|private|protected)[^;={]*?\b'+name+r'\s*\([^)]*\)\s*\{',code)
 assert match,name
 start=match.end()-1;depth=0
 for i in range(start,len(code)):
  if code[i]=='{':depth+=1
  if code[i]=='}':
   depth-=1
   if depth==0:return source[match.start():i+1].strip()
 raise AssertionError('unterminated method '+name)


def main():
 allowed=set(PRODUCTION+TESTS)
 changed=git('diff','--name-only',BASE).decode().splitlines()
 untracked=git('ls-files','--others','--exclude-standard').decode().splitlines()
 assert all(p in allowed or p.startswith('docs/delivery/architecture-day/A-theme-prepared/') for p in changed+untracked)
 files={}
 for p in PRODUCTION+TESTS:
  data=(ROOT/p).read_bytes();code=mask(data.decode());stack=[]
  for c in code:
   if c in '([{':stack.append(c)
   elif c in ')]}':assert stack and stack.pop()=={')':'(',']':'[','}':'{'}[c],p
  assert not stack,p
  exists=subprocess.run(['git','cat-file','-e',BASE+':'+p],cwd=ROOT,capture_output=True).returncode==0
  files[p]={'base_sha256':digest(before(p)) if exists else None,'candidate_sha256':digest(data)}
 protected=[PREFIX+p for p in ['ai/OpenAiCompatibleLlmClient.java','ai/LlmClient.java','ai/AiHttpAdmission.java',
  'ai/AiQuotaRecoveryService.java','ai/LlmClientConfig.java','ai/PhotoCheckIntentService.java','ai/PhotoCheckVisionService.java',
  'impl/AiThemeDraftRequestService.java','impl/RoleServiceImpl.java']]
 protected+=['chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAiController.java',
 'chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/ai/AiThemeDraftReq.java',
 'chengyinhub-xcx/pages/publish/simple/index.js',
 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiCreatorServiceImplTest.java',
 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayServiceImplTest.java',
 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayNotSentQuotaTest.java',
 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayContentBlockReasonTest.java',
 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/impl/AiGatewayRateLimitReasonTest.java']
 frozen={}
 for p in protected:
  old=before(p);assert (ROOT/p).read_bytes()==old,p
  frozen[p]=digest(old)
 unchanged={
 PREFIX+'impl/AiCreatorServiceImpl.java':['merchantPlan','themeDraftQuota','nodeGenerate','templateFill','safetyPrecheck',
  'chapterOverview','merchantTaskSuggest','clubDesign','merchantInsight','resolveRole','parseThemePlan','parseAiJson',
  'isMockModel','isValidThemeDraft','isValidTemplateFill','playerThemeDraftUsed','secondsUntilNextLocalMidnight'],
 PREFIX+'ai/impl/AiGatewayServiceImpl.java':['remainingQuota','dailyQuota','consumeQuota','planQuotaReservation','refundQuota',
  'stageQuota','retainQuota','retainQuotaAfterResult','traceIdOf','receiptOf','rateGroupOf','dailyLimitOf','resolveClient',
  'requireAvailableClient','checkTextFully','resolveOpenid','estimateSingle','truncate']}
 method_hashes={}
 for p,names in unchanged.items():
  old=before(p).decode();new=(ROOT/p).read_text()
  for name in names:
   prior=method(old,name);assert prior==method(new,name),(p,name)
   method_hashes[p+'#'+name]=digest(prior.encode())
 creator=(ROOT/(PREFIX+'impl/AiCreatorServiceImpl.java')).read_text()
 gateway=(ROOT/(PREFIX+'ai/impl/AiGatewayServiceImpl.java')).read_text()
 for name in ['themeDraftPrepared','consumePlayerThemeDraftPlan']:
  body=method(creator,name);assert 'simpleUUID' not in body and 'IdUtils' not in body
 assert 'gateway.planQuotaReservation(memberId, "theme_draft", nonce)' in method(creator,'planThemeDraft')
 assert 'm == null ? "player"' not in method(creator,'resolvePreparedRole')
 assert 'plan.counterKey(), plan.receiptKey()' in method(creator,'consumePlayerThemeDraftPlan')
 assert 'plan.originalDay()' in method(creator,'consumePlayerThemeDraftPlan')
 assert 'plan.executeBefore()' not in method(creator,'consumePlayerThemeDraftPlan')
 snapshot=method(gateway,'themeDraftSnapshot')
 assert 'getApiKey' not in snapshot and 'getProviders' not in snapshot and 'providerProps' not in snapshot
 assert 'configured.configuredModel()' in snapshot and 'getRawQuery()' in snapshot
 execution=method(gateway,'generateThemeDraftPrepared')
 assert 'simpleUUID' not in execution and 'consumeQuota(' not in execution and 'planQuotaReservation(' not in execution
 assert 'plan.reservation(), snapshot.dailyLimit()' in execution and 'plan.nonce(), snapshot, plan, openid' in execution
 assert 'snapshot.client()' in method(gateway,'generateReserved')
 for p in [PREFIX+'impl/AiCreatorServiceImpl.java',PREFIX+'ai/impl/AiGatewayServiceImpl.java']:
  old=before(p).decode();new=(ROOT/p).read_text()
  for token in ['RESERVE_QUOTA_SCRIPT','REFUND_QUOTA_SCRIPT']:
   pat=r'private static final org\.springframework\.data\.redis\.core\.script\.DefaultRedisScript<Long> '+token+r'\s*=.*?Long\.class\);'
   assert re.search(pat,old,re.S).group()==re.search(pat,new,re.S).group(),(p,token)
 legacy=before('chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/AiCreatorServiceImplTest.java').decode()
 assert len(re.findall(r'@Test\b',legacy))==35
 assert '@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})' in legacy
 counts={}
 for p in TESTS:
  text=(ROOT/p).read_text();single=len(re.findall(r'@Test\b',text));parameters=[]
  for group in re.findall(r'@ValueSource\((.*?)\)',text,re.S):
   values=group.split('{',1)[1].rsplit('}',1)[0]
   parameters.append(len(re.findall(r'"(?:\\.|[^"\\])*"',values)) if 'strings=' in group else len(values.split(',')))
  counts[p]={'single_source_tests':single,'parameterized_source_cases':parameters,
             'source_expected_cases':single+sum(parameters),'executed':0}
 assert sum(v['source_expected_cases'] for v in counts.values())==66
 contract=git('show',CONTRACT+':docs/delivery/architecture-day/E/THEME-DURABILITY-02/CONTRACT.md').decode()
 assert 'public record AiThemeDraftPlan(Long memberId, String role, String pool,' in contract
 assert 'String reservation, String creatorConfigHash, String gatewayConfigHash,' in contract
 plan_path=PREFIX+'ai/AiThemeDraftPlan.java'
 assert not (ROOT/plan_path).exists(),'E Plan source must remain separately owned; review integration identity'
 evidence={'baseline':BASE,'contract_sha':CONTRACT,'files':files,'protected_files_byte_preserved':frozen,
  'unrelated_methods_byte_preserved':method_hashes,'legacy_creator_regression_source_cases_preserved':37,
  'new_test_source_counts':counts,'new_test_source_expected_total':66,'java_compile':'NOT_RUN',
  'java_unit_executed':0,'mysql_redis_http_model_executed':0,'ci_dispatches':0,
  'external_dependency':{'path':plan_path,'status':'ABSENT_IN_BASE; E owns contract record; joint compile required'},
  'pool_literals':{'player':'player-draft','club':'gen','merchant':'gen'},
  'lexical_delimiters':'PASS_NOT_A_JAVA_COMPILER','field_and_signature_checks':'SOURCE_ONLY_NOT_RUNTIME_PROOF'}
 print(json.dumps(evidence,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
