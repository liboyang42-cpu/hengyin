#!/usr/bin/env python3
"""Narrow E source equivalence; no Java, SQL execution, provider, or shared-owner untouched assertion."""
from pathlib import Path
import re, subprocess
ROOT=Path(__file__).resolve().parents[5]
BASE="def951bef36a94417d90259906f6bda6abe6efb3"
J="chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
T="chengyinhub-admin/src/test/java/com/chengyinhub/business/"
def old(path):return subprocess.check_output(["git","show",BASE+":"+path],cwd=ROOT,text=True)
def new(path):return (ROOT/path).read_text()
def source_methods(s):return methods(re.sub(r"(?m)^( {4})@Test[ \t]+(?=void )",r"\1",s))
def cases(s):return re.findall(r"@Test\s+(?:public\s+)?void\s+(\w+)\(",s)


def methods(source):
    masked = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',
                    lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), source, flags=re.S)
    pattern = re.compile(r'^    (?![ \t])(?:(?:public|private|protected|static|final|synchronized)\s+)*'
                         r'(?:[\w.$<>?,\[\] ]+?\s+)?(?P<name>\w+)\s*\([^;{}]*?\)\s*'
                         r'(?:throws\s+[\w.,\s]+)?\{', re.M)
    result = {}
    for match in pattern.finditer(masked):
        begin = match.end() - 1
        depth = 0
        for end in range(begin, len(masked)):
            if masked[end] == '{': depth += 1
            elif masked[end] == '}':
                depth -= 1
                if depth == 0: break
        header = source[match.start():begin]
        parameters = re.sub(r'\s+', '', header[header.index('('):header.rindex(')') + 1])
        result[(match['name'], parameters)] = source[begin:end + 1]
    return result

for service,helper,complete in [("ClubMembershipServiceImpl","currentInvoicePayment","completeInvoiceProof"),("MerchantCommerceService","currentCommercePayment","completeCommerceProof")]:
    path=J+service+".java";a,b=source_methods(old(path)),source_methods(new(path))
    key=lambda methods,name:next(k for k in methods if k[0]==name)
    assert [k[0] for k in a if a[k]!=b.get(k)]==["applyVerifiedPayment",complete]
    fresh=b[key(b,"applyVerifiedPayment")]
    assert fresh.replace("if (!"+helper+"(verified)) return false;", "if (!wechatPayService.lockPaymentAttemptForUpdate(outTradeNo, verified.facts().wxOutTradeNo())) return false;")==a[key(a,"applyVerifiedPayment")]
    assert b[key(b,complete)].replace("        if (!"+helper+"(verified)) return false;\n","")==a[key(a,complete)]
    adapter=b[key(b,helper)]
    assert adapter.count("requireConsumablePayment(verified.reference())")==1
    assert "verified.equals(consumable)" in adapter and "catch (WechatVerifiedPaymentFactService.ConflictException rejected)" in adapter
    assert not re.search(r"Jdbc|SELECT|closed_confirmed_at|catch \((?:RuntimeException|Exception|IllegalStateException)",adapter)
    assert len([k for k in b if k not in a])==1
    print(service,"all original bodies exact except two explicit guard insertion/replacement sites; no JDBC/intent policy")
for name,count in [("service/ClubMembershipServiceImplTest.java",78),("service/MerchantCommerceServiceTest.java",19)]:
    assert old(T+name)==new(T+name);assert len(cases(new(T+name)))==count
    print(name,"entire original fixture/cases exact",count)
path=T+"service/VerifiedDomainPaymentProofTest.java";a,b=source_methods(old(path)),source_methods(new(path))
assert len(cases(old(path)))==9 and len(cases(new(path)))==11
for name in cases(old(path)):
    ka=next(k for k in a if k[0]==name);kb=next(k for k in b if k[0]==name)
    if name=="currentAttemptDenialHasNoPaidGrantOrProofWrite":
        expected=a[ka].replace('when(f.pay.lockPaymentAttemptForUpdate(f.no,f.no)).thenReturn(false);','doThrow(new WechatVerifiedPaymentFactService.ConflictException()).when(f.proof.reader).requireConsumablePayment(f.ref);')
        assert expected==b[kb]
    else:assert a[ka]==b[kb],name
print("proof unit old 9 cases/assertions retained; only old current-denial fixture switches to shared typed guard; total11 NOT_RUN")
path=T+"it/ClmCommercePaymentProofMySqlIT.java";a,b=source_methods(old(path)),source_methods(new(path))
assert len(cases(old(path)))==6 and len(cases(new(path)))==8
assert all(value==b.get(k) for k,value in a.items())
assert "latestClosedOriginalSuccessRetainsItsFactAndManualTicketWithoutLocalConsumption" in cases(new(path))
assert "boundPaidReplayCannotEraseALaterClosedAttemptManualHandoff" in cases(new(path))
print("MySQL original6 cases plus setup/cleanup/helper bodies exact; new2 real SQL cases; total8 NOT_RUN")
fixture=new(T+"service/VerifiedPaymentProofFixtures.java")
assert 'when(reader.requireConsumablePayment(any())).thenAnswer(call -> reader.requireVerified(call.getArgument(0)))' in fixture
assert 'unissued fixture Reference' in fixture
print("STATIC PASS; actual common dependency is root ce4cd7d8baddcb62aad85b212fd0d89b9fd4aec1; no shared-source untouched assertion")
print("NOT_RUN: Java/JUnit/MySQL/CI/provider; canonical schema and shared common source owned by root")
