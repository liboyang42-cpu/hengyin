import hashlib,json,re,subprocess,sys
from pathlib import Path
sys.path.insert(0,str(Path.cwd()))
from ci.test_signup_intent_producer import method
BASE='68eea620c5812d10392a7eb986bed5b5a3d17ea1'
report=Path('docs/delivery/architecture-day/D/signup-intent')
def parent(p): return subprocess.check_output(['git','show',f'{BASE}:{p}']).decode()
p='chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java'
old=parent(p); new=Path(p).read_text()
marker='private String completePaymentSuccess('
a=method(old,marker); b=method(new,marker)
restored=b.replace('        final boolean freshPaymentClaimed = paymentTransitioned;\n','').replace('registration.getActivityTitle(), freshPaymentClaimed);','registration.getActivityTitle());')
assert restored==a
print('PASS completePaymentSuccess outside authorized capture/forward is byte-identical to parent')
marker='private void registerPaymentAfterCommit('
old_notice=method(old,marker); new_notice=method(new,marker)
restored=new.replace(new_notice,old_notice).replace('    @Autowired\n    private com.chengyinhub.business.service.ISystemNotificationIntentService systemNotificationIntents;\n\n','').replace('        final boolean freshPaymentClaimed = paymentTransitioned;\n','').replace('registration.getActivityTitle(), freshPaymentClaimed);','registration.getActivityTitle());')
assert restored==old
print('PASS entire producer source outside authorized method/injection/capture/forward is byte-identical')
a=old_notice.index('            for (Long registrationId'); b=old_notice.index('            try {',a)
x=new_notice.index('            for (Long registrationId'); y=new_notice.index('            try {',x)
assert old_notice[a:b]==new_notice[x:y]
marker='            try {\n                if (notifyService != null)'
assert old_notice[old_notice.index(marker):]==new_notice[new_notice.index(marker):]
print('PASS QR loop and WX/synchronization tail are byte-identical')
e=json.loads((report/'evidence.json').read_text())
for item in e['unchanged_shared_sources']:
 data=Path(item['path']).read_bytes()
 assert data==parent(item['path']).encode()
 assert hashlib.sha256(data).hexdigest()==item['sha256']
 print('PASS unchanged shared source '+item['path'])
for item in e['java_inventory']:
 s=Path(item['path']).read_text()
 names=re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\(',s)
 assert names==item['methods'] and len(names)==item['tests']
 prior='' if item['path'].endswith('/RegistrationSignupIntentTest.java') else parent(item['path'])
 old_names=re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\(',prior)
 assert set(old_names)<=set(names)
 print('INVENTORY '+str(len(names))+' UNEXECUTED Java tests '+Path(item['path']).name+'; old method identities retained='+str(len(old_names)))
print('NOTE Java inventory/source preservation checks do not compile or execute Java')
cmd=[sys.executable,'-m','unittest','ci.test_signup_intent_producer','ci.test_official_broadcast_delivery_migration']
print('RUN '+' '.join(cmd),flush=True)
subprocess.run(cmd,check=True,stderr=subprocess.STDOUT)
subprocess.run(['git','diff','--check'],check=True)
print('PASS git diff --check')
owned=[p,'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/RegistrationSignupIntentTest.java','chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/SignupNoticeAfterCommitPersistenceMySqlIT.java','chengyinhub-admin/src/test/java/com/chengyinhub/business/service/StandaloneCheckoutSignupNoticeAcceptanceTest.java','ci/test_signup_intent_producer.py']
owned+= [str(q) for q in report.iterdir() if q.is_file() and q.name not in ('CANDIDATE.sha256','verification.txt')]
pattern=re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16}')
for p in owned: assert not pattern.search(Path(p).read_bytes()),p
print('PASS bounded credential-pattern scan on all owned source/tests/docs (no pattern hits)')
print('NOT RUN: Maven / javac / JUnit / MySQL / Redis / backend / CI / production DDL')
