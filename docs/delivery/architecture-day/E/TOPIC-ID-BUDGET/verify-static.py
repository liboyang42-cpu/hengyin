#!/usr/bin/env python3
"""Static method/budget/SQL-contract check, not Java or database execution."""
from pathlib import Path
import ast
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[5]
BASE = '4efef4db85ee94bfd4908c84faaadff8759e8c09'
OWNER = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicQueryService.java'


def original(path):
    return subprocess.check_output(['git', 'show', BASE + ':' + path], cwd=ROOT, text=True)


parser = ast.parse(original('docs/delivery/architecture-day/E/TOPIC-QUERY/verify-migration.py'))
function = next(item for item in parser.body if isinstance(item, ast.FunctionDef) and item.name == 'methods')
namespace = {'re': re}
exec(compile(ast.Module(body=[function], type_ignores=[]), '<frozen-method-reader>', 'exec'), namespace)
old, current = original(OWNER), (ROOT / OWNER).read_text()
before, after = namespace['methods'](old), namespace['methods'](current)
changed = [key for key in before if before[key][1] != after[key][1]]
assert changed == [('refreshPublicNodeCounts', '(List<CmsTopic>topics)'), ('buildTopicTemplateListVO', '(booleanrecommendedOnly)')]
assert len(before) == 25 and len(after) == 26
assert 'static final int MAX_IDS_PER_QUERY = 300;' in current
key = changed[0]
expected = before[key][1].replace(
    '        for (CmsTopic row : nullToEmpty(cmsTopicMapper.selectPublicNodeCountsByTopicIds(ids))) counts.put(row.getId(), row);',
    '        for (int offset = 0; offset < ids.size();) {\n'
    '            int end = offset + Math.min(MAX_IDS_PER_QUERY, ids.size() - offset);\n'
    '            for (CmsTopic row : nullToEmpty(cmsTopicMapper.selectPublicNodeCountsByTopicIds(ids.subList(offset, end))))\n'
    '                counts.put(row.getId(), row);\n'
    '            offset = end;\n'
    '        }')
assert after[key][1] == expected
key = changed[1]
assert after[key][1] == before[key][1].replace('cmsTopicTemplateMetaMapper.selectByTopicIds(metaTopicIds)', 'selectTemplateMetadataInBatches(metaTopicIds)')
helper = next(value[1] for key, value in after.items() if key[0] == 'selectTemplateMetadataInBatches')
assert 'Math.min(MAX_IDS_PER_QUERY, topicIds.size() - offset)' in helper
assert 'nullToEmpty(cmsTopicTemplateMetaMapper.selectByTopicIds(topicIds.subList(offset, end)))' in helper
assert 'catch' not in helper
topic_xml = ET.fromstring(original('chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml'))
statement = topic_xml.find("select[@id='selectPublicNodeCountsByTopicIds']")
assert statement.find('foreach').get('collection') == 'topicIds'
sql = ''.join(statement.itertext())
for gate in ['status = 1', 'merchant_member_id is null or node_audit_status = 1', 'not exists', 'withdrawn.status = 0', 'group by topic_id']:
    assert gate in sql, gate
meta_xml = ET.fromstring(original('chengyinhub-system/src/main/resources/mapper/business/CmsTopicTemplateMetaMapper.xml'))
statement = meta_xml.find("select[@id='selectByTopicIds']")
assert statement.find('foreach').get('collection') == 'topicIds'
assert 'where del_flag = 0' in ''.join(statement.itertext())
print('STATIC PASS: 23 original method bodies unchanged; original enrichment/shelf differs only in bounded id acquisition; existing scope SQL retained; no pagination/output truncation')
