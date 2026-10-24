#!/usr/bin/env python3
"""Bounded G slice source checks only. No Java/SQL or business execution."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

BASE='4197f6a6b11d62cb0a742aae73d7167c93114585'
READER='chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/CompletedRouteFactReader.java'
MAPPER='chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CompletedRouteFactMapper.java'
XML='chengyinhub-system/src/main/resources/mapper/business/CompletedRouteFactMapper.xml'
TESTROOT='chengyinhub-admin/src/test/java/com/chengyinhub/business/'
NEW_TESTS=[TESTROOT+'service/CompletedRouteProgressPageReaderTest.java',TESTROOT+'it/CompletedRouteProgressPageMySqlIT.java']
OLD_TESTS=[TESTROOT+'service/CompletedRouteFactReaderTest.java',TESTROOT+'it/CompletedRouteFactMapperMySqlIT.java']
DOCS='docs/delivery/architecture-day/G/wp06b-fact-batch/'

def git(*args):return subprocess.check_output(['git',*args]).decode()
def original(path):return git('show',BASE+':'+path)
def digest(text):return hashlib.sha256(text.encode()).hexdigest()
def masked(text):
    assert '"""' not in text
    return re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: ''.join('\n' if c=='\n' else ' ' for c in m[0]),text,flags=re.S)

for path in [READER,MAPPER,*NEW_TESTS]:
    depth=0
    for char in masked(Path(path).read_text()):
        if char=='{':depth+=1
        elif char=='}':depth-=1
        assert depth>=0,path
    assert depth==0,path
xml=Path(XML).read_text();root=ET.fromstring(xml)
for tag,identity in [('resultMap','CompletionFact'),('select','selectCurrentCompletedRoutes')]:
    pattern='<'+tag+' id="'+identity+'"[\\s\\S]*?</'+tag+'>'
    assert re.search(pattern,original(XML))[0]==re.search(pattern,xml)[0],identity
assert all(n.tag in {'resultMap','select'} for n in root)
assert '${' not in xml
assert all(n.attrib.get('id') in {'selectCurrentCompletedRoutes','selectNodeProgressPage'} for n in root if n.tag=='select')
method=r'    @Transactional\(readOnly = true\)\n    public List<TopicRouteSession> findCurrentCompletedRoutes[\s\S]*?\n    }'
assert re.search(method,original(READER))[0]==re.search(method,Path(READER).read_text())[0]
rows=[]
for path in NEW_TESTS+OLD_TESTS:
    text=Path(path).read_text()
    if path in OLD_TESTS:assert text==original(path),path
    assert not re.search(r'ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|@Disabled|Assumptions\.|assumeTrue',text),path
    names=re.findall(r'@(?:org\.junit\.jupiter\.api\.)?Test\s+void\s+(\w+)\s*\(',text)
    rows.append(dict(path=path,ordinary_declarations=len(names),parameterized_declarations=0,expected_expanded_invocations=len(names),case_names=names,execution_status='NOT_RUN_BY_G'))
assert [r['expected_expanded_invocations'] for r in rows]==[9,11,3,4]
allowed={READER,MAPPER,XML,*NEW_TESTS}
for path in git('diff','--name-only',BASE).splitlines():assert path in allowed or path.startswith(DOCS),path
protected=[
 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java',
 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ShopDayProofServiceImpl.java',
 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayerNodeProgressServiceImpl.java',
 'chengyinhub-system/src/main/resources/mapper/business/PlayerNodeProgressMapper.xml',
 'chengyinhub-system/src/main/resources/mapper/business/TopicRouteSessionMapper.xml',
 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ExploreCompletionReadServiceImpl.java',
 *OLD_TESTS]
for path in protected:assert Path(path).read_text()==original(path),path
paths=[READER,MAPPER,XML,*NEW_TESTS,*protected,
       'chengyinhub-admin/src/test/resources/it-schema/journey-full-flow-it-schema.sql',
       'chengyinhub-admin/sql/migration_20260823_topic_route_graph.sql',
       'chengyinhub-admin/sql/migration_theme_runtime_20261004.sql']
files=[]
for path in dict.fromkeys(paths):
    item=dict(path=path,candidate_sha256=hashlib.sha256(Path(path).read_bytes()).hexdigest())
    if path not in NEW_TESTS:item['base_sha256']=digest(original(path))
    files.append(item)
print(json.dumps(dict(kind='STATIC_SOURCE_ONLY_NOT_JAVA_OR_DATABASE_VERIFICATION',base=BASE,
                     tests=rows,new_expected_invocations=20,four_G_classes_expected_invocations=27,
                     existing_route_method_and_statements_byte_unchanged=True,
                     protected_files_byte_unchanged=protected,files=files,
                     consumer_switch=False,java_or_database_execution_by_G=0),ensure_ascii=False,indent=2))
