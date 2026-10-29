#!/usr/bin/env python3
"""轻量源码/隔离检查；不执行 Java/SQL，不产生资金运行结论。"""
import hashlib, json, re, subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[5]
OUT=Path(__file__).resolve().parent
BASE='a42172dc6e1565fa06b81c294de137c74f5fd979'
SYS='chengyinhub-system/src/main/'
TEST='chengyinhub-admin/src/test/java/'
FILES=[
 SYS+'java/com/chengyinhub/business/domain/dto/TopicSettlementCashBasis.java',
 SYS+'java/com/chengyinhub/business/mapper/CoopSettlementEvidenceMapper.java',
 SYS+'java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java',
 SYS+'resources/mapper/business/CoopSettlementEvidenceMapper.xml',
 TEST+'com/chengyinhub/business/service/TopicSettlementCashBasisTest.java',
 TEST+'com/chengyinhub/business/it/Wp04CurrentCashBasisMySqlIT.java',
 TEST+'com/chengyinhub/business/service/impl/Wp04TopicSettlementEntryGuardsTest.java',
 TEST+'com/chengyinhub/business/service/impl/PreparedCoopPlanFixture.java',
 TEST+'com/chengyinhub/business/service/impl/CoopSettlementTest.java',
 TEST+'com/chengyinhub/business/service/impl/CoopSettlementGroupBasisTest.java',
 TEST+'com/chengyinhub/business/service/impl/CoopSettlementPerMerchantTest.java',
 TEST+'com/chengyinhub/business/service/SettlementRateConvergenceTest.java',
 TEST+'com/chengyinhub/web/controller/api/DoublePaymentReproductionTest.java',
]
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT)
def read(path): return (ROOT/path).read_text()
def method(text,name):
    start=text.index(name+'(')
    brace=text.index('{',start)
    # Replace literals/comments with spaces without changing indices.
    clean=re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',lambda m:' '*len(m[0]),text,flags=re.S)
    depth=0
    for i in range(brace,len(text)):
        depth+=(clean[i]=='{')-(clean[i]=='}')
        if depth==0: return text[brace:i+1]
    raise AssertionError(name)
changed=set(git('diff',BASE,'--name-only').decode().splitlines())
changed.update(git('ls-files','--others','--exclude-standard').decode().splitlines())
assert all(p in FILES or p.startswith('docs/delivery/architecture-day/G/WP04-finance-p1/') for p in changed),changed-set(FILES)
assert set(FILES)<=changed
cms=FILES[2]; current=read(cms); old=git('show',BASE+':'+cms).decode()
for name in ['resolvePendingDepositPaymentForSettlement','payoutOneDueCoopSettlement']:
    assert method(current,name)==method(old,name),name
print('PASS: exact 13 source/test scope; B deposit and payout methods unchanged')
frozen=[
 SYS+'java/com/chengyinhub/business/mapper/CompletedRouteFactMapper.java',
 SYS+'java/com/chengyinhub/business/service/support/CompletedRouteFactReader.java',
 SYS+'resources/mapper/business/CompletedRouteFactMapper.xml',
 TEST+'com/chengyinhub/business/service/CompletedRouteFactReaderTest.java',
 TEST+'com/chengyinhub/business/it/CompletedRouteFactMapperMySqlIT.java',
 TEST+'com/chengyinhub/business/service/ArchitectureDayGSettlementGuardsTest.java',
 TEST+'com/chengyinhub/web/controller/api/ArchitectureDayGCompletionFallbackTest.java',
]
for p in frozen: assert (ROOT/p).read_bytes()==git('show','051c1c108653eeb1cbc4f3b69f8ae25728c99e34:'+p),p
it=TEST+'com/chengyinhub/business/it/Wp04CoopSettlementEvidenceMySqlIT.java'
assert (ROOT/it).read_bytes()==git('show',BASE+':'+it)
print('PASS: frozen 051c seven files and old SQL IT untouched; root harness delta safe to retain')
import xml.etree.ElementTree as ET
xml=ET.fromstring(read(FILES[3])); locked=['selectCandidatesForUpdate','selectTopicEvidenceCandidatesForUpdate',
 'selectTopicCashRowsForUpdate','selectTopicActivityCashRowsForUpdate','selectCashBasisBlockingRefundIdsForUpdate']
for name in locked:
    query=xml.find("select[@id='%s']"%name); assert query is not None
    assert query.attrib.get('useCache')=='false' and query.attrib.get('flushCache')=='true',name
    assert 'for update' in ''.join(query.itertext()).lower(),name
activity=''.join(xml.find("select[@id='selectTopicActivityCashRowsForUpdate']").itertext()).lower()
assert 'r.owner_id = a.id' in activity and 'a.topic_id = #{topicid}' in activity and 'del_flag' not in activity
assert 'countPaidActivityRegistrations' not in current
print('PASS: five explicit uncached locking reads; real activity parent including deleted shells; no ordinary COUNT cash admission')
base_files=set(git('ls-tree','-r','--name-only',BASE).decode().splitlines())
new_unit=0
for p in FILES:
    if not p.startswith(TEST): continue
    now=read(p)
    assert not re.search(r'@Disabled|Assumptions\.|assumeTrue\(|@EnabledIf',now),p
    names=set(re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)',now))
    before=git('show',BASE+':'+p).decode() if p in base_files else ''
    old_names=set(re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)',before))
    assert old_names<=names,p
    assert len(re.findall(r'\bassert\w+\(',now))>=len(re.findall(r'\bassert\w+\(',before)),p
    if not p.endswith('MySqlIT.java'): new_unit+=len(names-old_names)
assert new_unit==10,new_unit
it=read(FILES[5]); assert len(re.findall(r'@Test\s+void',it))==4
for token in ['"HOST","PORT","USER","PASSWORD","DB_SUFFIX"','@AfterAll','connection.getCatalog()',
 'DROP DATABASE','information_schema.schemata','ISOLATION_REPEATABLE_READ','innodb_lock_wait_timeout=1']:
    assert token in it,token
print('PASS: original test names/assertion counts retained; added 10 unit and 4 SQL, explicit IT env/UUID-only cleanup')
# Lightweight credential marker scan of this source delta, not a replacement for root gitleaks.
for p in FILES:
    text=read(p)
    assert not re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|AKIA[A-Z0-9]{16}|gh[pousr]_[A-Za-z0-9]{30,}',text),p
manifest={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sorted(FILES)}
aggregate=hashlib.sha256(''.join(p+'\0'+s+'\n' for p,s in manifest.items()).encode()).hexdigest()
(OUT/'source-hashes.json').write_text(json.dumps({'parent':BASE,'aggregate_sha256':aggregate,'files':manifest},indent=2)+'\n')
print('PASS: source manifest SHA256 '+aggregate)
print('NOT_RUN: Java compile/unit, MySQL 4 new SQL, full financial services, late payment/refund fence, production/DDL')
