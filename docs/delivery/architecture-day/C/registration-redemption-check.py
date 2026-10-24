#!/usr/bin/env python3
"""One extraction's exact source/fixture comparison. Never runs Java/DB/CI."""
from pathlib import Path
import re,subprocess,json,hashlib
ROOT=Path(__file__).resolve().parents[4]
BASE='bdb6be21035f2d26810850c411b665e37343b82e'
C='chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java'
A='chengyinhub-admin/src/main/java/com/chengyinhub/web/service/registration/RegistrationRedemptionApplication.java'
def before(p): return subprocess.check_output(['git','show',BASE+':'+p],cwd=ROOT,text=True)
def span(s,pos):
 start=s.index('{',pos);i=start;depth=0;state='code'
 while i<len(s):
  c=s[i];pair=s[i:i+2]
  if state=='line':
   if c=='\n':state='code'
  elif state=='comment':
   if pair=='*/':state='code';i+=1
  elif state in ('"',"'"):
   if c=='\\':i+=1
   elif c==state:state='code'
  elif pair=='//':state='line';i+=1
  elif pair=='/*':state='comment';i+=1
  elif c in ('"',"'"):state=c
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if depth==0:return start,i+1
  i+=1
 raise AssertionError(pos)
def methods(s):
 out={}
 for m in re.finditer(r'^    (?:public|private|protected|static)[^\n=;{]*\b(\w+)\(([^)]*)\)\s*\{',s,re.M):
  a,b=span(s,m.end()-1);out[m[1],m[2]]=s[m.start():b]
 return out
old=before(C);new=(ROOT/C).read_text();app=(ROOT/A).read_text()
start=old.index('    /** 核销码密钥:');end=old.index('    /**\n     * 支付回调通知',start)
orig=methods(old[start:end]);moved=methods(app);unmoved=methods(old[:start]+old[end:]);current=methods(new)
for sig,text in unmoved.items():assert current[sig]==text, ('unowned method drift',sig)
assert re.findall(r'    @(?:Operation|PostMapping|Parameter|Parameters|Anonymous)[^\n]*',old)==re.findall(r'    @(?:Operation|PostMapping|Parameter|Parameters|Anonymous)[^\n]*',new)
assert [s for s in methods(old) if methods(old)[s].startswith('    public ')]==[s for s in current if current[s].startswith('    public ')]
actors={'scanQrCodeChapter','scanQrCodeStation','scanDynamicCode','scanGroupMemberTicket','scanQrCode','replayOriginalSeat'}
proof=[]
for (name,args),text in orig.items():
 expected=text
 if name in actors:
  expected=expected.replace(name+'('+args+')',name+'(Actor actorContext, '+args+')',1)
 for callee in ['scanQrCode','scanGroupMemberTicket','replayOriginalSeat']:
  expected=re.sub(r'(?<!AjaxResult )\b'+callee+r'\(',callee+'(actorContext, ',expected)
 expected=expected.replace('getAppUserId()', 'actorContext.currentMemberId()')
 expected=re.sub(r'(?<![\w.])error\(', 'AjaxResult.error(',expected)
 expected=expected.replace('    static AjaxResult clubSessionVerificationTooEarly(', '    public static AjaxResult clubSessionVerificationTooEarly(')
 signature=(name,('Actor actorContext, '+args) if name in actors else args)
 assert moved[signature]==expected,('moved method drift',name,args)
 proof.append({'method':name,'originalParameters':args,'adaptedBodySha256':hashlib.sha256(expected.encode()).hexdigest()})
assert not re.search(r'@Transactional|extends BaseController|\bgetAppUserId\(',re.sub(r'/\*[\s\S]*?\*/|//[^\n]*','',app))
fixtures=[]
files=json.loads(Path(__file__).with_name('registration-redemption-fixtures.json').read_text())
for p in files:
 was=before(p);now=(ROOT/p).read_text();normalized=now
 normalized=re.sub(r'RegistrationRedemptionTestFixtures\.application\((controller|scanner)\)',r'\1',normalized)
 normalized=normalized.replace('com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.clubSessionVerificationTooEarly(', 'ApiRegistrationController.clubSessionVerificationTooEarly(')
 if p.endswith('/ApiRegistrationCompletionCouponTest.java'):
  # The migrated fixture's real leaf owner is already wired. These two legacy setters had no reader.
  for field,value in [('redemptionMapper','redemptionMapper'),('topicCompletionAwardService','completionAwardService')]:
   stale='        ReflectionTestUtils.setField(controller, "'+field+'", '+value+');\n'
   assert was.count(stale)==1
   was=was.replace(stale,'',1)
 if p.endswith('/RegistrationSeatEndpointTest.java'):
  normalized=normalized.replace('    private com.chengyinhub.web.service.registration.RegistrationRedemptionApplication scannerApplication;\n','')
  normalized=normalized.replace('        scannerApplication = spy(new com.chengyinhub.web.service.registration.RegistrationRedemptionApplication());\n        ReflectionTestUtils.setField(scanner, "registrationRedemptionApplication", scannerApplication);\n','')
  normalized=normalized.replace('verify(scannerApplication, never()).scanQrCode(any(), anyString(), anyString(), anyLong());','verify(scanner, never()).scanQrCode(anyString(), anyString(), anyLong());')
  normalized=normalized.replace('doReturn(originalPath).when(scannerApplication).scanQrCode(new com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.Actor(81L, false), "activity", code);','doReturn(originalPath).when(scanner).scanQrCode("activity", code);')
  normalized=normalized.replace('verify(scannerApplication).scanQrCode(new com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.Actor(81L, false), "activity", code);','verify(scanner).scanQrCode("activity", code);')
 assert normalized==was,('fixture drift',p)
 cases=re.findall(r'@(Test|ParameterizedTest)\b',was)
 assertions='\n'.join(l for l in was.splitlines() if re.match(r'\s*(assert\w+|verify\w*)\(',l))
 fixtures.append({'file':p,'originalTestDeclarations':len(cases),'originalAssertionLinesSha256':hashlib.sha256(assertions.encode()).hexdigest()})
source_contracts=[]
for path in [
 'chengyinhub-admin/src/test/java/com/chengyinhub/framework/aspectj/DeadlockRetryEntryPointContractTest.java',
 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationEquityAtomicityContractTest.java']:
 was=before(path);normalized=(ROOT/path).read_text()
 normalized=normalized.replace('src/main/java/com/chengyinhub/web/service/registration/RegistrationRedemptionApplication.java', 'src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java')
 normalized=normalized.replace('        assertFalse(com.chengyinhub.web.service.registration.RegistrationRedemptionApplication.class.isAnnotationPresent(Transactional.class));\n','')
 assert normalized==was,('source contract drift beyond actual owner',path)
 source_contracts.append({'file':path,'testDeclarations':len(re.findall(r'@(Test|ParameterizedTest)\b',was))})
result={'baseline':BASE,'unmovedMethods':len(unmoved),'movedMethods':proof,'originalHttpMetadataAndPublicSignaturesPreserved':True,'originalScanContextBodyPreserved':True,'fixtures':fixtures,'testDeclarationsPreserved':sum(x['originalTestDeclarations'] for x in fixtures),'newJavaCasesNotRun':4,'sourceContractFixtures':source_contracts}
# The ScanContext class (including its own private constructor/fields) stays verbatim.
needle='    private static final class ScanContext {';a,b=span(old,old.index(needle));x,y=span(app,app.index(needle));assert old[a:b]==app[x:y]
print(json.dumps(result,ensure_ascii=False,indent=2))
