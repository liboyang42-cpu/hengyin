"""Source/XML checks only, not a Java/DB/runtime concurrency test."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[4]
base = 'dee20f264de16d7b155c414cd5ebcfffff82908d'
xml_path = 'chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMerchantMapper.xml'
interface_path = 'chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CmsRegistrationMerchantMapper.java'
service_path = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationMerchantServiceImpl.java'
unit_path = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/MerchantCancelAuditGateTest.java'
it_path = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/MerchantRegistrationCancellationMySqlIT.java'
before = ET.fromstring(subprocess.check_output(['git', 'show', base + ':' + xml_path], cwd=root, text=True))
after = ET.parse(root / xml_path).getroot()
sql = lambda name: ' '.join(' '.join(after.find("update[@id='" + name + "']").itertext()).split())
winner, cancel = sql('confirmWinnerIfPending'), sql('cancelUnwonByMember')
old_winner = ' '.join(before.find("update[@id='confirmWinnerIfPending']").itertext())
def require_guards(win, cancellation):
    assert 'and del_flag = 0' in win
    assert all(s in cancellation for s in ('where id = #{id}', 'member_id = #{memberId}', 'and del_flag = 0',
                                         'audit_status is null or audit_status in (0, 2)'))
assert 'del_flag' not in old_winner
require_guards(winner, cancel)
assignment = cancel.split('where', 1)[0]
assert all(s not in assignment for s in ('member_id =', 'audit_status =', 'status =', 'reason =', 'address_name ='))
assert set(re.findall(r'#\{(\w+)\}', cancel)) == {'id', 'memberId', 'username', 'updateTime'}
interface = (root / interface_path).read_text()
for name in ('id', 'memberId', 'username', 'updateTime'):
    assert '@Param("' + name + '")' in interface
for node in before:
    if node.tag == 'update' and node.attrib['id'] == 'confirmWinnerIfPending':
        continue
    assert ET.tostring(after.find(node.tag + "[@id='" + node.attrib['id'] + "']")) == ET.tostring(node), node.attrib
print('PASS SOURCE: cancellation binds current owner/active/unwon atomically; winner rejects deleted/unknown-deletion; other XML statements unchanged')

old_service = subprocess.check_output(['git', 'show', base + ':' + service_path], cwd=root, text=True)
new_service = (root / service_path).read_text()
old_tail = old_service.split('public boolean cancelByMerchant', 1)[1]
new_tail = new_service.split('public boolean cancelByMerchant', 1)[1]
assert 'updateCmsRegistrationMerchant(registration)' in old_tail
assert 'updateCmsRegistrationMerchant(registration)' not in new_tail
assert 'cancelUnwonByMember(' in new_tail and 'setDelFlag(' not in new_tail
assert old_service.split('public boolean cancelByMerchant', 1)[0] == new_service.split('public boolean cancelByMerchant', 1)[0]
print('PASS SOURCE COUNTEREXAMPLE: baseline forwards stale owner/audit/status snapshot by ID; fix replaces only cancel write and preserves readable denials')
for win, cancellation in ((winner.replace('and del_flag = 0', ''), cancel),
                          (winner, cancel.replace('and member_id = #{memberId}', ''))):
    try:
        require_guards(win, cancellation)
    except AssertionError:
        pass
    else:
        raise AssertionError('missing predicate negative control was not rejected')
print('PASS SOURCE NEGATIVE CONTROLS: removing winner deletion or cancellation owner predicates restores missing protection; not a DB execution')

for path, expected in ((unit_path, 7), (it_path, 8)):
    source = (root / path).read_text()
    assert len(re.findall(r'@Test\b', source)) == expected
    assert not re.search(r'@(Disabled|EnabledIf|DisabledIf)|Assumptions\.|assumeTrue\(|org\.h2', source)
    print('SOURCE ONLY, NOT RUN:', path.rsplit('/', 1)[-1], expected, 'tests')
it = (root / it_path).read_text()
assert 'P0AcceptanceSchema.baseTables' in it and 'ItSchema.splitStatements' in it
assert 'Explicit controlled test binding required' in it
changes = set(subprocess.check_output(['git', 'diff', '--name-only', base], cwd=root, text=True).splitlines())
changes.update(subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines())
assert {p for p in changes if not p.startswith('docs/delivery/architecture-day/A-merchant-cancel/')} <= {
    xml_path, interface_path, service_path, unit_path, it_path}, changes
for path, expected in (('/workspace/hengyin-a-wp12', base),
                       ('/workspace/hengyin-a-p2', '88fae34ca16597b69f55271078d028658e438ffb'),
                       ('/workspace/hengyin-a-rollback-fixture', '2d9da065e21d56d453ce671b2ad9a51cee72dd25'),
                       ('/workspace/hengyin-a-trigger-delimiter', '2f01f4eb7cb10b5dd89bafd18c90204f7a95b362')):
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=path, text=True).strip() == expected
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=path)
subprocess.run(['git', 'diff', '--check', base], cwd=root, check=True)
print('PASS SOURCE: five scoped source/test files only; frozen candidates unchanged; diff check passed')
print('NOT RUN: Java compilation/tests, real MySQL locks/concurrency, HTTP authorization, B/G/channel integration; no DDL or CI dispatch')
