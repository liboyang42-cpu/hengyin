"""Lightweight source checks only; no Java, DB, backend, channel or CI execution."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[4]
base = '3bc4bb08caaba4cc300f2df25f90c4a070033bfc'
service_path = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CashWithdrawServiceImpl.java'
test_root = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/'
unit_path = test_root + 'service/impl/CashWithdrawFundEvidenceTest.java'
it_path = test_root + 'service/impl/CashWithdrawFundEvidenceMySqlIT.java'
fixture_path = test_root + 'service/impl/CashWithdrawEvidenceFixture.java'
state_path = test_root + 'service/CashWithdrawStateMachineTest.java'

def old(path):
    return subprocess.check_output(['git', 'show', base + ':' + path], cwd=root, text=True)

def method(source, name):
    start = re.search(r'\b' + re.escape(name) + r'\s*\([^)]*\)\s*(?:throws[^\{]+)?\{', source).end()
    end, depth = start, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

before, after = old(service_path), (root / service_path).read_text()
assert 'prepareChannelQuery' not in before
sync = method(after, 'syncOne')
assert sync.index('prepareChannelQuery') < sync.index('queryTransferResult')
prepare = method(after, 'prepareChannelQuery')
assert 'TransactionTemplate(transactionManager)' in prepare
assert prepare.index('selectByIdForUpdate') < prepare.index('checkedFacts') < prepare.index('onlyLocalFacts')
assert 'CashWithdrawFundFact.DEBIT.equals(f.getKind()) || CashWithdrawFundFact.REFUND.equals(f.getKind())' in prepare
assert 'requireRefundEvidence(original)' in prepare and 'updateStatusIfProcessing(manual)' in prepare
assert all(s not in prepare for s in ('wechatPayService.', 'appendFact(', 'addBalance(', 'setLastQueryTime('))
for name in ('requireRefundEvidence', 'recordSubmissionIntent', 'manualRefund', 'refundFailedClaim', 'recordChannelFact'):
    assert method(before, name) == method(after, name), name
assert 'intent == null && facts.size() == 1' in method(after, 'requireRefundEvidence')
print('PASS SOURCE: recovery locks original before channel I/O; DEBIT-only takes existing manual path with no channel/fact/credit write')
print('PASS SOURCE: strict refund/intent/channel gates unchanged; old UNKNOWN is not classified as local-only')

# Removing the new call restores the original source ordering that poisoned DEBIT-only recovery.
negative = sync.replace('        w = prepareChannelQuery(w.getId());\n        if (w == null) return;\n', '')
assert 'prepareChannelQuery' not in negative and 'queryTransferResult' in negative
assert 'recordChannelFact(id, CashWithdrawFundFact.UNKNOWN, q)' in negative
print('PASS SOURCE NEGATIVE CONTROL: removing recovery call restores query/UNKNOWN before any local-only classification')

fixture = (root / fixture_path).read_text()
assert method(fixture, 'seedDebit') == method(old(fixture_path), 'seedDebit')
before_state, after_state = old(state_path), (root / state_path).read_text()
adapted = []
for name in re.findall(r'\bvoid\s+(\w+)\s*\([^)]*\)\s*(?:throws[^\{]+)?\{', before_state):
    b, a = method(before_state, name), method(after_state, name)
    if b == a:
        continue
    assert name.startswith('sync_'), name
    normalized = a.replace('evidence.seedSubmissionIntent(1L);', 'evidence.seedDebit(1L);')
    normalized = normalized.replace('        when(mapper.selectById(1L)).thenReturn(old); // Recovery reloads the current persisted receipt under its lock.\n', '')
    assert normalized == b, name
    adapted.append(name)
assert len(adapted) == 9, adapted
print('PASS SOURCE: exactly nine channel-query fixtures gain SUBMIT_INTENT; original assertions/manual DEBIT-only fixtures unchanged')
for name in adapted:
    print('ADAPTED METHOD: ' + name)

it = (root / it_path).read_text()
assert 'Frozen displaySnapshot =' in it and 'thenReturn(displaySnapshot)' in it
assert 'thenReturn(new WithdrawalFrozenAmountResolver' not in it
assert 'loseCommitReplyAt(3)' in method(it, 'committedRefundWithLostCommitReplyNeverReopensOrCreditsAgain')
for name in ('processExitAfterDebitCommit', 'processExitBeforeIntentRecoversOriginalThroughTwoPeopleWithoutChannelQuery',
             'concurrentNeverSubmittedRecoveryAndCheckersPreserveOneOriginalRefund',
             'recoveryRacingOriginalIntentClaimCannotBothAuthorizeRefundAndSubmission',
             'oldUnknownWithoutIntentRemainsInvestigationAndCannotRefund'):
    assert method(it, name)
for path in (unit_path, it_path):
    source = (root / path).read_text()
    assert not re.search(r'@(Disabled|EnabledIf|DisabledIf)|Assumptions\.|assumeTrue\(|org\.h2', source)
    print('NOT RUN: ' + path.rsplit('/', 1)[-1] + ' @Test count=' + str(len(re.findall(r'@Test\b', source))))
print('PASS SOURCE: root displaySnapshot fixture correction retained; committed-debit process-exit injection and same-original races present')

changes = set(subprocess.check_output(['git', 'diff', '--name-only', base], cwd=root, text=True).splitlines())
changes.update(subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines())
assert {p for p in changes if not p.startswith('docs/delivery/architecture-day/A-P2/')} <= {
    service_path, unit_path, it_path, fixture_path, state_path}, changes
for path, expected in (('/workspace/hengyin-a', base),
                       ('/workspace/hengyin-a-wp12', 'dee20f264de16d7b155c414cd5ebcfffff82908d')):
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=path, text=True).strip() == expected
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=path)
subprocess.run(['git', 'diff', '--check', base], cwd=root, check=True)
print('PASS SOURCE: only five scoped Java files and A-P2 docs changed; frozen A/WP12 worktrees unchanged; diff check passed')
print('NOT RUN: Java compilation/tests, real MySQL/Redis/backend/channel integration, CI dispatch; no DDL executed')
