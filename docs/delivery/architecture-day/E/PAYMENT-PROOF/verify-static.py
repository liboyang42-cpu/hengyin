#!/usr/bin/env python3
"""Static assembly/closure evidence only. Does not run Java, MySQL, Redis, SDK, or provider."""
from pathlib import Path
import re, subprocess, tempfile, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[5]
DOCS=Path(__file__).resolve().parent
BASE="e4bdaceb8c1eb800b38c93601bbbd82ca15b26e5"
J="chengyinhub-system/src/main/java/com/chengyinhub/business/"
T="chengyinhub-admin/src/test/java/com/chengyinhub/business/"
def original(path):return subprocess.check_output(["git","show",BASE+":"+path],cwd=ROOT,text=True)
def read(path):return (ROOT/path).read_text()
def mask(source):
    return re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',
                    lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), source, flags=re.S)


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

def tests(source):return re.findall(r"@Test\s+(?:public\s+)?void\s+(\w+)\(",source)
def calls(source,name):
    clean=mask(source)
    for match in re.finditer(r"\b"+name+r"\s*\(",clean):
        start=match.end();depth=0;count=1
        for end in range(start,len(clean)):
            c=clean[end]
            if c in "([{":depth+=1
            elif c in ")]}":
                if c==")" and depth==0:break
                depth-=1
            elif c=="," and depth==0:count+=1
        yield count
for name,allowed,expected in [("ClubMembershipServiceImpl","applyWechatInvoiceObservation",73),("MerchantCommerceService","reconcileOrder",18)]:
    path=J+"service/impl/"+name+".java";a,b=methods(original(path)),methods(read(path))
    assert sum(value==b.get(key) for key,value in a.items())==expected
    assert [key[0] for key,value in a.items() if key in b and value!=b[key]]==[allowed]
    assert all(n==5 for method in ["handlePaidInvoice","handlePaidOrder"] for n in calls(read(path),method))
    before=re.findall(r"wechatPayService\.(createGenericOrder|queryOrderStatus|closePaymentAttempt)\(",original(path))
    assert before==re.findall(r"wechatPayService\.(createGenericOrder|queryOrderStatus|closePaymentAttempt)\(",read(path))
    print(name,"unchanged existing method bodies",expected,"SDK sites preserved",len(before))
for name,count in [("ClubMembershipServiceImplTest",78),("MerchantCommerceServiceTest",19)]:
    path=T+"service/"+name+".java";assert tests(original(path))==tests(read(path));assert len(tests(read(path)))==count
    print(name,"old cases retained",count)
for name,count in [("service/VerifiedDomainPaymentProofTest.java",9),("it/ClmCommercePaymentProofMySqlIT.java",6)]:
    assert len(tests(read(T+name)))==count;print(name,"new source cases NOT_RUN",count)
for name,selects in [("ClubMembershipMapper",["selectInvoiceByIdForUpdate","selectInvoiceByOutTradeNoForUpdate"]),("OmsOrderMapper",["selectCommerceOrderForUpdate"])]:
    tree=ET.fromstring(read("chengyinhub-system/src/main/resources/mapper/business/"+name+".xml"))
    for sid in selects:
        el=tree.find("select[@id='"+sid+"']");assert el.attrib["useCache"]=="false" and el.attrib["flushCache"]=="true"
    old=ET.fromstring(original("chengyinhub-system/src/main/resources/mapper/business/"+name+".xml"))
    current={el.attrib.get("id"):el for el in tree}
    for el in old:
        if el.tag in ("insert","update","delete"):
            current_el=current[el.attrib.get("id")];el.tail=None;current_el.tail=None
            assert ET.tostring(el)==ET.tostring(current_el),el.attrib.get("id")
    print(name,"original writes exact; proof read/current CAS parsed")
sql=read("chengyinhub-admin/sql/migration_20261024_clm_commerce_payment_proof.sql")
clean=re.sub(r"--[^\n]*","",sql)
assert len(re.findall(r"\bALTER TABLE\b",clean))==2
assert not re.search(r"\b(?:INSERT|UPDATE|DELETE|DROP|TRUNCATE|CREATE TABLE)\b",clean.replace("ON UPDATE RESTRICT","").replace("ON DELETE RESTRICT",""))
assert clean.count("VARBINARY(64)")==2 and clean.count("OCTET_LENGTH(verified_payment_fact_hash)=64")==2
assert clean.index("information_schema.table_constraints")<clean.index("ALTER TABLE")
# Assemble only in a temporary directory: shared production/test files remain root-owned.
with tempfile.TemporaryDirectory(prefix="e-proof-assembly-") as tmp:
    paths=[]
    for patch in ["shared-api.review.patch","caller-fixtures.review.patch"]:
        paths+=re.findall(r"^--- a/(.+)$",(DOCS/patch).read_text(),re.M)
    for path in paths:
        target=Path(tmp)/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(original(path))
    for patch in ["shared-api.review.patch","caller-fixtures.review.patch"]:
        subprocess.run(["git","apply",str(DOCS/patch)],cwd=tmp,check=True)
    for path in paths:
        new=(Path(tmp)/path).read_text()
        assert not subprocess.check_output(["git","diff",BASE,"--",path],cwd=ROOT,text=True),"shared owner source edited: "+path
        assert all(n==5 for name in ["handlePaidInvoice","handlePaidOrder"] for n in calls(new,name)),path
        if path.endswith("Test.java"):
            assert tests(new)==tests(original(path)),path
    router=Path(tmp)/ (J+"service/impl/WechatPaymentCallbackRouter.java")
    assert "verifiedPaymentReference(facts)" in router.read_text()
    # G's already integrated typed branch must be byte-for-byte preserved.
    start=original(J+"service/impl/WechatPaymentCallbackRouter.java").index('                new Owner("coop-deposit"')
    end=original(J+"service/impl/WechatPaymentCallbackRouter.java").index('                new Owner("registration"',start)
    assert original(J+"service/impl/WechatPaymentCallbackRouter.java")[start:end] in router.read_text()
print("STATIC PASS: both patches apply; all known E APIs/callers typed; shared source untouched; old test names retained")
print("NOT_RUN: Java compile/JUnit/MySQL/DDL/provider/CI; cross-owner G fixture closure and full-checkout census belong to root integration")
