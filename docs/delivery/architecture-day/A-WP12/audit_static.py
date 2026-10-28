"""Source audit with generated inventories. No Java/DB/backend/network execution."""
from pathlib import Path
import json
import re
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[4]
out = Path(__file__).resolve().parent
base = '2ec68899099a1e250df0949a8383bf85b5d3ffe0'
controller = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationController.java'
test = 'chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/business/CmsRegistrationControllerSafetyTest.java'

def stripped(source):
    body = source.split('private static void stripImmutablePaymentFacts', 1)[1].split('\n    }', 1)[0]
    return set(re.findall(r'\.set(\w+)\(null\)', body))

before = subprocess.check_output(['git', 'show', base + ':' + controller], cwd=root, text=True)
after = (root / controller).read_text()
assert 'RegistrationStatus' not in stripped(before)
assert 'RegistrationStatus' in stripped(after)
assert stripped(after) - stripped(before) == {'RegistrationStatus'}
for target in (1, 2, 3, 4):
    before_write = target if 'RegistrationStatus' not in stripped(before) else None
    after_write = target if 'RegistrationStatus' not in stripped(after) else None
    assert before_write == target and after_write is None
print('PASS: source counterexample before fix forwards lifecycle 1/2/3/4; after fix forwards none')
xml = ET.parse(root / 'chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml').getroot()
assert xml.find("update[@id='updateCmsRegistration']/trim/if[@test='registrationStatus != null']") is not None
# Removing just the added protection must restore the same source counterexample.
assert 'RegistrationStatus' not in stripped(after.replace('        r.setRegistrationStatus(null);\n', ''))
assert re.findall(r'@PreAuthorize\([^\n]+', before) == re.findall(r'@PreAuthorize\([^\n]+', after)
print('PASS: unchanged dynamic MyBatis predicate skips null; single-line negative control restores the hole; permissions unchanged')

mappers = ['CmsRegistration', 'CmsRegistrationMerchant', 'RefundApplication', 'RegistrationEntitlement',
           'CmsRegistrationScan', 'Redemption', 'WxPaymentCallbackInbox', 'WxPaymentCallbackOutbox',
           'UmsMemberWithdrawal', 'MemberCashWithdraw']
state = re.compile(r'\b(?:payment_status|registration_status|verification_status|payout_status|audit_status|status|state|del_flag|points_returned)\b')
inventory = []
for name in mappers:
    path = root / f'chengyinhub-system/src/main/resources/mapper/business/{name}Mapper.xml'
    text = path.read_text()
    doc = ET.fromstring(text)
    for statement in doc:
        if statement.tag not in {'insert', 'update', 'delete'}:
            continue
        body = ' '.join(' '.join(statement.itertext()).split())
        tokens = sorted(set(state.findall(body)))
        if not tokens and statement.tag != 'delete':
            continue
        ident = statement.attrib['id']
        marker = re.search(r'<' + statement.tag + r'\s+id="' + re.escape(ident) + '"', text)
        inventory.append({'mapper': str(path.relative_to(root)), 'line': text[:marker.start()].count('\n') + 1,
                          'statement': ident, 'type': statement.tag, 'state_tokens': tokens,
                          'where': re.split(r'\bwhere\b', body, maxsplit=1, flags=re.I)[-1] if re.search(r'\bwhere\b', body, re.I) else None,
                          'dynamic_predicates': [node.attrib.get('test') for node in statement.findall('.//if')]})
(out / 'state-write-inventory.json').write_text(json.dumps({'base': base,
    'meaning': 'source SQL state writes/predicates and raw deletes, not runtime reachability or executed SQL',
    'items': inventory}, ensure_ascii=False, indent=2) + '\n')
print(f'PASS: parsed state-token mutations/predicates and deletes in {len(mappers)} scoped mappers: {len(inventory)} statements indexed')

suites = [
 'web/controller/business/CmsRegistrationControllerSafetyTest.java',
 'web/controller/api/RegistrationConsentGateTest.java',
 'web/controller/api/ApiRegistrationOwnerScopeTest.java',
 'web/controller/api/ApiRegistrationVerifyGateTest.java',
 'web/controller/api/MerchantRegistrationEditGateTest.java',
 'web/controller/api/ApiRegistrationMerchantOperatorScopeTest.java',
 'web/controller/api/RegistrationMerchantAuditTimeContractTest.java',
 'web/controller/business/CmsRegistrationMerchantControllerSelectTest.java',
 'web/controller/business/CmsRegistrationMerchantControllerAwardBypassTest.java',
 'web/controller/business/RefundControllerManualClaimGateTest.java',
 'web/controller/business/RefundControllerReviewPayoutTest.java',
 'business/service/impl/RegistrationOrderServiceBoundaryTest.java',
 'business/service/RegistrationPaymentRetryContractTest.java',
 'business/service/RegistrationPaymentAtomicityContractTest.java',
 'business/service/RegistrationCancelExactlyOnceContractTest.java',
 'business/service/RegistrationFundsMybatisIntegrationTest.java',
 'business/service/RegistrationEquityMybatisIntegrationTest.java',
 'business/service/RegistrationCurrentUseGateTest.java',
 'business/service/RegistrationVerifyAndSettleTest.java',
 'business/service/WholeOrderVerificationReverseTest.java',
 'business/service/PerkLedgerServiceTest.java',
 'business/service/DispatchServiceImplTest.java',
 'business/service/MerchantCancelAuditGateTest.java',
 'business/service/impl/ActivityCancelTest.java',
 'business/service/RefundPayoutMybatisIntegrationTest.java',
 'business/service/RefundNotificationMybatisIntegrationTest.java',
 'business/service/impl/RefundRetryAndRemainderMySqlIT.java',
 'business/it/WxPaymentCallbackFencingMySqlIT.java',
 'business/it/P0RedemptionChainMySqlIT.java',
 'business/it/RedemptionRewardClawbackMyBatisTest.java',
 'business/mapper/VerifyRefundCasMutualExclusionMyBatisTest.java',
 'business/mapper/PerkLedgerMapperSqlTest.java',
 'business/task/OrderCloseUnpaidCursorTest.java',
 'business/task/OrderClosePayingReconcileTaskContractTest.java',
 'business/task/RegistrationAutoVerifyTaskTest.java',
 'web/controller/business/UmsMemberWithdrawalControllerSafetyTest.java',
 'business/service/impl/BankWithdrawalCommandServiceImplTest.java',
 'business/service/impl/WithdrawalPayoutStateMybatisTest.java']
coverage = []
for relative in suites:
    path = root / 'chengyinhub-admin/src/test/java/com/chengyinhub' / relative
    src = path.read_text()
    kind = ('MySQL-configured source' if any(s in src for s in ('MysqlIntegrationTestConfig', 'MybatisItDatabase', 'MySqlIntegrationTestSupport', 'P0AcceptanceSchema')) else
            'source/reflection contract' if 'Files.read' in src else
            'H2/mapper fixture source' if 'org.h2' in src else 'unit/mock or XML-bound SQL source')
    coverage.append({'file': str(path.relative_to(root)), 'kind': kind, 'execution': 'NOT_RUN',
                     'test_annotations': len(re.findall(r'@(?:Test|ParameterizedTest|RepeatedTest)\b', src)),
                     'methods': re.findall(r'\bvoid\s+(\w+)\s*\(', src)})
(out / 'test-inventory.json').write_text(json.dumps(coverage, ensure_ascii=False, indent=2) + '\n')
print(f'PASS: {len(coverage)} existing suites indexed by source type; every Java suite remains NOT_RUN')
changes = set(subprocess.check_output(['git', 'diff', '--name-only', base], cwd=root, text=True).splitlines())
changes.update(subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines())
assert {p for p in changes if not p.startswith('docs/delivery/architecture-day/A-WP12/')} <= {controller, test}, changes
assert subprocess.check_output(['git', 'rev-parse', 'codex/architecture-day-a-withdraw'], cwd=root, text=True).strip() == '3bc4bb08caaba4cc300f2df25f90c4a070033bfc'
assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd='/workspace/hengyin-a')
subprocess.run(['git', 'diff', '--check', base], cwd=root, check=True)
print('PASS: frozen A commit/worktree unchanged; only one production boundary and its existing safety test modified')
print('NOT RUN: Java compilation, Java/H2/MySQL tests, backend/Redis/WeChat integration')
