"""Run from repository root. Source custody/static checks only; no Java/MySQL execution."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

ROOT = Path.cwd()
BASE = 'b0ae8ba46eca8bcc3c2e61abb5a6c4e2536e101c'
REPORT = ROOT / 'docs/delivery/architecture-day/D/w19-owner-intent'
sys.path.insert(0, str(ROOT))
from ci.test_w19_owner_notice_intent import method


def original(path):
    return subprocess.check_output(['git', 'show', BASE + ':' + path]).decode()


producer_path = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantOfferServiceImpl.java'
new = Path(producer_path).read_text()
old = original(producer_path)
old_withdraw = method(old, 'public boolean withdrawOwnChapterOffer(')
new_withdraw = method(new, 'public boolean withdrawOwnChapterOffer(')
start = old_withdraw.index('        businessNotice.sendAfterCommit(')
end = old_withdraw.index('        businessNotice.executeAfterCommit(', start)
new_start = new_withdraw.index('        registerOwnerWithdrawalNotice(')
new_end = new_withdraw.index('        businessNotice.executeAfterCommit(', new_start)
restored_withdraw = new_withdraw[:new_start] + old_withdraw[start:end] + new_withdraw[new_end:]
assert restored_withdraw == old_withdraw
assert new_withdraw[new_end:] == old_withdraw[end:]
print('PASS withdrawal business/permission/lock/CAS/return and player supplier byte-identical outside owner notice call')
restored = new.replace(new_withdraw, old_withdraw)
start = restored.index('    /** 原退出事务保存站内意图')
end = restored.index('    private void invalidateCircleReview(', start)
restored = restored[:start] + restored[end:]
start = restored.index('    @Autowired\n    private ISystemNotificationIntentService systemNotificationIntents;')
end = restored.index('    @Autowired\n    private MerchantChapterApplicationMapper applicationMapper;', start)
restored = restored[:start] + restored[end:]
assert restored[restored.index('@Service'):] == old[old.index('@Service'):]
assert set(re.findall(r'^import .*;', old, re.M)) <= set(re.findall(r'^import .*;', new, re.M))
print('PASS all other production class bytes preserved; additions limited to imports, four injected dependencies and two authorized helpers')

inventory = json.loads((REPORT / 'evidence.json').read_text())
for item in inventory['java_inventory']:
    path = item['path']
    source = Path(path).read_text()
    names = re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\(', source)
    assert names == item['methods'] and len(names) == item['tests'], path
    if not item['new']:
        prior = original(path)
        assert names == re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\(', prior), path
        if '/web/' in path:
            assert source == prior, path
        elif path.endswith('/FreeExploreNodeSupplyMySqlIT.java'):
            for name in names:
                assert method(source, 'void ' + name + '(') == method(prior, 'void ' + name + '('), name
        elif path.endswith('/MerchantChapterOfferWithdrawTest.java'):
            for name in names:
                a = method(prior, 'void ' + name + '(')
                b = method(source, 'void ' + name + '(')
                if name == 'exitNotifiesOnlyTheServerSelectedAffectedPaidPlayersAfterCommit':
                    a_start = a.index('        verify(notices).sendAfterCommit(')
                    b_start = b.index('        verify(intents).enqueue(')
                    a_end = a.index('        ArgumentCaptor<', a_start)
                    b_end = b.index('        ArgumentCaptor<', b_start)
                    b = b[:b_start] + a[a_start:a_end] + b[b_end:]
                assert a == b, name
    print('INVENTORY UNEXECUTED ' + Path(path).name + ' ' + str(len(names)))
print('PASS old 10/8/10+8 identities and assertions preserved except owner fanout verification replaced by original intent verification')
for item in inventory['unchanged_shared_sources']:
    data = Path(item['path']).read_bytes()
    assert data == original(item['path']).encode(), item['path']
    assert hashlib.sha256(data).hexdigest() == item['sha256']
    print('PASS unchanged shared source ' + item['path'])
print('RUN python3 -m unittest ci.test_w19_owner_notice_intent', flush=True)
subprocess.run([sys.executable, '-m', 'unittest', 'ci.test_w19_owner_notice_intent'], check=True, stderr=subprocess.STDOUT)
subprocess.run(['git', 'diff', '--check'], check=True)
print('PASS git diff --check')
pattern = re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16}')
owned = [ROOT / p for p in inventory['source_files']]
owned += [p for p in REPORT.iterdir() if p.is_file() and p.name not in ('CANDIDATE.sha256', 'verification.txt')]
for path in owned:
    assert not pattern.search(path.read_bytes()), str(path)
print('PASS bounded credential-pattern scan on owned source/tests/docs; zero hits')
print('NOT RUN Maven / javac / JUnit / MySQL / Redis / backend / CI / production DDL; zero real WX calls')
