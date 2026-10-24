"""Lightweight source checks only; never compiles/runs Java or touches a database."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

BASE = 'dfe083a89cd5538b7df77c6d407da3908e9a1cc8'
CONTROLLER = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiPlayProgressController.java'
UNIT = 'chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/PlayLeadAdmissionFailureTest.java'
IT = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/PlayLeadAdmissionFailureMySqlIT.java'

def original(path):
    return subprocess.check_output(['git', 'show', BASE + ':' + path]).decode()

old = original(CONTROLLER)
source = Path(CONTROLLER).read_text()
start = old.index('    private PreferencePlayCtx resolvePreferencePlayCtx(')
end = old.index('    private static final class PreferencePlayCtx', start)
lead = ('        String leadErr = leadGateError(out.playCtx.actId, out.node);\n'
        '        if (leadErr != null) { out.error = error(leadErr); return out; }\n')
unlock = '        String unlockErr = unlockGateError(memberId, out.playCtx.actId, out.node);'
old_block = old[start:end]
assert old_block.count(lead) == 1
assert old_block.index(lead) > old_block.index('routeAccessError(')  # Counterfactual source, not a Java red-test run.
expected = old_block.replace(lead, '').replace(unlock, lead + unlock)
assert source == old[:start] + expected + old[end:]
assert expected.index('resolveCtx(') < expected.index('if (out.playCtx.error != null)') < expected.index(lead)
assert expected.index(lead) < expected.index(unlock) < expected.index('routeAccessError(')

rows = []
for path in (CONTROLLER, UNIT, IT):
    text = Path(path).read_text()
    stripped = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', '', text, flags=re.S)
    stack = []
    for char in stripped:
        if char in '([{':
            stack.append(char)
        elif char in ')]}':
            assert stack and stack.pop() == dict(zip(')]}', '([{'))[char], (path, char)
    assert not stack, path
    count = len(re.findall(r'@Test\s+void', text))
    rows.append(dict(path=path, sha256=hashlib.sha256(Path(path).read_bytes()).hexdigest(),
                     lexical_delimiters='PASS', source_test_methods=count,
                     new_test_methods=count-len(re.findall(r'@Test\s+void', original(path)))))
assert rows[1]['source_test_methods'] == 16 and rows[1]['new_test_methods'] == 5
assert rows[2]['source_test_methods'] == 4 and rows[2]['new_test_methods'] == 2
# Keep the original hint/debit and claim-rollback test bodies byte-for-byte.
old_it = original(IT)
marker = '    @Test void realLeadStorageFaultReturnsNoHintAndHasZeroEconomicOrRegistrationEffect()'
assert old_it[old_it.index(marker):] == Path(IT).read_text()[Path(IT).read_text().index(marker):]
for name in ('PlayHintUnlockMySqlIT.java', 'ThemeRuntimeMySqlIT.java'):
    path = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/' + name
    assert Path(path).read_text() == original(path)
for mapper in ('CmsRegistrationMapper', 'CmsActivityMapper', 'ClubLeadMapper', 'CmsPointsSettingMapper',
               'UmsMemberMapper', 'UmsMemberPointsDetailMapper', 'GameSessionRuntimeMapper', 'TopicRouteSessionMapper'):
    ET.parse(Path('chengyinhub-system/src/main/resources/mapper/business') / (mapper + '.xml'))
route_ddl = Path('chengyinhub-admin/sql/migration_20260823_topic_route_graph.sql').read_text()
for table in ('topic_route_session', 'topic_route_action'):
    assert len(re.findall(r'(?s)CREATE TABLE IF NOT EXISTS ' + table + r' \(.*?\) ENGINE=InnoDB[^;]*;', route_ddl)) == 1
checks = dict(exact_existing_two_line_move_only=True, identity_registration_node_ownership_before_lead=True,
              old_route_before_lead_source_negative_control=True, original_two_IT_bodies_unchanged=True,
              hint10_and_Theme_unchanged=True, mapper_xml_parse='8 parsed; not MyBatis loading',
              route_canonical_DDL='2 unique CREATE matches; not MySQL execution')
print(json.dumps(dict(parent=BASE, checks=checks, files=rows,
                      java_compile='NOT RUN', junit='NOT RUN', mysql='NOT RUN', ci_dispatch='NOT PERFORMED'),
                 ensure_ascii=False, indent=2))
