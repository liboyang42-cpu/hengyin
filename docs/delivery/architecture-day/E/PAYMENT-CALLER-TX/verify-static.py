#!/usr/bin/env python3
"""Apply exact review hunks to scratch sources, check boundaries/body identities; no Java/DB/SDK execution."""
from pathlib import Path
import ast,re,subprocess,tempfile,json
ROOT=Path(__file__).resolve().parents[5]
BASE='0cb8115ee34e4647a76c76bda608fae62bbd51e1'
DOCS=Path(__file__).resolve().parent
IMPL='chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/'
TEST='chengyinhub-admin/src/test/java/com/chengyinhub/business/service/'
paths=[IMPL+'ClubMembershipServiceImpl.java',IMPL+'MerchantCommerceService.java',
       TEST+'ClubMembershipServiceImplTest.java',TEST+'MerchantCommerceServiceTest.java']
def original(path):return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT,text=True)
reader=subprocess.check_output(['git','show','10426a532822ee8053cd75e3d77da5b6393d30c2:docs/delivery/architecture-day/E/VOICE-TRANSPORT/verify-static.py'],cwd=ROOT,text=True)
function=next(n for n in ast.parse(reader).body if isinstance(n,ast.FunctionDef) and n.name=='methods')
ns={'re':re};exec(compile(ast.Module(body=[function],type_ignores=[]),'<frozen-reader>','exec'),ns)
def methods(source):return ns['methods'](re.sub(r'(?m)^( {4})@Test[ \t]+(?=void )',r'\1',source))
def cases(source):return re.findall(r'@Test\b(?:(?!@Test).)*?\bvoid\s+(\w+)\s*\(',source,re.S)
renamed={'renewalSuspendsAmbientTransactionAroundWechatAndRestoresCallerAfterShortTransactions':'renewalRejectsAmbientTransactionWithoutSuspendingCallerLocks',
         'resumeSuspendsAmbientTransactionAroundWechatQueryAndRestoresCaller':'resumeRejectsAmbientTransactionWithoutSuspendingCallerLocks',
         'explicitCancelSuspendsAmbientTransactionAroundWechatAndRestoresCaller':'explicitCancelRejectsAmbientTransactionWithoutSuspendingCallerLocks'}
summary=[]
with tempfile.TemporaryDirectory(prefix='e-payment-caller-') as directory:
 scratch=Path(directory)
 for path in paths:
  target=scratch/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(original(path))
 for name in ['callers.review.patch','fixtures.review.patch']:
  patch=DOCS/name
  subprocess.run(['git','apply','--check',str(patch)],cwd=scratch,check=True)
  subprocess.run(['git','apply',str(patch)],cwd=scratch,check=True)
 for path,entrypoints in [(paths[0],{'renew','resumeInvoice','closeInvoice'}),(paths[1],{'createOrder','reconcileOrder'})]:
  old,new=original(path),(scratch/path).read_text();before,after=methods(old),methods(new)
  assert set(before)<=set(after)
  for key in before:
   if key[0] in entrypoints:
    assert after[key].replace('        requirePaymentOutsideTransaction();\n','',1)==before[key],key
    assert after[key].splitlines()[1].strip()=='requirePaymentOutsideTransaction();'
   elif key[0]=='transaction':
    assert 'PROPAGATION_REQUIRES_NEW' in after[key]
   else:assert after[key]==before[key],('non-entry policy/body changed',key)
  assert len(after)==len(before)+1
  assert 'isActualTransactionActive()' in new
  assert old.count('@Transactional')==new.count('@Transactional')
  assert new.count('Propagation.NEVER)')==len(entrypoints)
  assert 'Propagation.NOT_SUPPORTED)' not in new
  # Same SDK/lock sites and order. No new channel/amount/receipt or local mutation is introduced.
  old_sites=re.findall(r'wechatPayService\.\w+\([^;]*;',old)
  new_sites=re.findall(r'wechatPayService\.\w+\([^;]*;',new)
  assert old_sites==new_sites
  summary.append({'file':Path(path).name,'guarded_entries':sorted(entrypoints),'unchanged_payment_call_sites':len(old_sites),'other_method_bodies_preserved':len(before)-len(entrypoints)-(1 if Path(path).name.startswith('Club') else 0)})
 for path in paths[2:]:
  old,new=original(path),(scratch/path).read_text();before,after=methods(old),methods(new)
  for case in cases(old):
   assert renamed.get(case,case) in cases(new),('lost test',case)
   if case not in renamed:
    key=next(k for k in before if k[0]==case);assert before[key]==after[key],('old business assertion changed',case)
  assert len(cases(new))==len(cases(old))+2
  assert not re.search(r'@Disabled|assumeTrue|@Ignore|enabled\s*=\s*false',new)
  summary.append({'file':Path(path).name,'old_cases':len(cases(old)),'new_cases':len(cases(new)),'retired_ambient_suspension_expectations':3 if 'Club' in path else 0})
  methods(new)
print('STATIC PASS:',json.dumps(summary,ensure_ascii=False))
print('STATIC PASS: 2 production/2 fixture patches apply; paid/grant/close CAS bodies, scope, snapshots, SDK call sites and 0cb API preserved; 3 ambient expectations explicitly migrate to NEVER; no removed cases/skip')
print('NOT_RUN: Java/JUnit/MySQL/provider/CI/full gitleaks. This does not validate B SDK/fact-owner implementation or physical lock behavior.')
