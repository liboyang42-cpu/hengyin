#!/usr/bin/env python3
"""Source/XML equivalence only. Does not run Java, execute mapper SQL or measure performance."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[5]
BASE = '9e14a73e50c9afd5c8f38027c3ecd059967a51d3'
OWNER = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicQueryService.java'
XML = 'chengyinhub-system/src/main/resources/mapper/business/CmsMemberTemplateMapper.xml'


def original(path):
    return subprocess.check_output(['git', 'show', BASE + ':' + path], cwd=ROOT, text=True)


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
        params = re.sub(r'\s+', '', header[header.index('('):header.rindex(')') + 1])
        result[(match['name'], params)] = source[begin:end + 1]
    return result


old = original(OWNER)
current = (ROOT / OWNER).read_text()
before, after = methods(old), methods(current)
changed = [key for key in before if before[key] != after[key]]
assert changed == [('buildTopicTemplateDetailFromSnapshot', '(CmsTopictopic,booleanviewerCanViewRecruitment,IntegermerchantCategoryId)')]
expected = before[changed[0]].replace(
    '                java.util.List<CmsTopicNode> nodes = cmsTopicNodeMapper.selectCmsTopicNodeList(probeN);',
    '                java.util.List<CmsTopicNode> nodes = cmsTopicNodeMapper.selectCmsTopicNodeList(probeN);\n'
    '                // Same mapper projection/predicate as the original id reads; bounded batches per chapter.\n'
    '                java.util.Map<Long, CmsMemberTemplate> chapterTemplates = selectChapterTemplates(nodes);')
expected = expected.replace('CmsMemberTemplate tpl = cmsMemberTemplateMapper.selectCmsMemberTemplateById(n.getTemplateId());',
                            'CmsMemberTemplate tpl = chapterTemplates.get(n.getTemplateId());')
assert after[changed[0]] == expected
assert len(before) == 22 and len(after) == 23
assert '@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)' in current
xml = original(XML)
if (ROOT / XML).exists(): assert (ROOT / XML).read_text() == xml
root = ET.fromstring(xml)
one = root.find("select[@id='selectCmsMemberTemplateById']")
many = root.find("select[@id='selectCmsMemberTemplateByIds']")
assert one.get('resultMap') == many.get('resultMap') == 'CmsMemberTemplateResult'
assert [n.get('refid') for n in one.findall('include')] == [n.get('refid') for n in many.findall('include')] == ['selectCmsMemberTemplateVo']
assert ''.join(one.itertext()).strip() == 'where id = #{id}'
assert ''.join(many.itertext()).split('where id in')[0].strip() == ''
assert many.find('foreach').get('collection') == 'ids'
assert many.find('foreach').text.strip() == '#{id}'
print('STATIC PASS: 21 original bodies unchanged; original detail differs only in template acquisition; existing XML projection/resultMap identical, SQL unchanged')
