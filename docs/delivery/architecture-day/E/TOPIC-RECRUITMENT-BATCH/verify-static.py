#!/usr/bin/env python3
"""Static migration/predicate/fixture evidence only; no Java, SQL execution or performance measurement."""
from pathlib import Path
import ast
import json
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[5]
DOCS = Path(__file__).resolve().parent
BASE = 'b9b894b170d75b4e6315f2bde41ede8f551522c6'
OWNER = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicQueryService.java'


def original(path):
    return subprocess.check_output(['git', 'show', BASE + ':' + path], cwd=ROOT, text=True)


# Reuse this delivery chain's frozen lexical method reader, without executing its old migration assertions.
parser = ast.parse(original('docs/delivery/architecture-day/E/TOPIC-QUERY/verify-migration.py'))
function = next(item for item in parser.body if isinstance(item, ast.FunctionDef) and item.name == 'methods')
namespace = {'re': re}
exec(compile(ast.Module(body=[function], type_ignores=[]), '<frozen-method-reader>', 'exec'), namespace)
methods = namespace['methods']
old, new = original(OWNER), (ROOT / OWNER).read_text()
before, after = methods(old), methods(new)
changed = [key for key in before if before[key][1] != after[key][1]]
assert changed == [('buildTopicTemplateDetailFromSnapshot', '(CmsTopictopic,booleanviewerCanViewRecruitment,IntegermerchantCategoryId)')]
assert len(before) == 23 and len(after) == 25
body = before[changed[0]][1]
query = ('                    com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats stats =\n'
         '                            merchantChapterApplicationService.getRecruitmentStats(ch.getId(), ch.getMaxMerchant());\n')
assert body.count(query) == 1
start = body.index('                    rec.setCurrentOfferCount(stats.getReservedMerchantCount());')
end = body.index('                    chVO.setRecruitStatus(rec);', start)
projection = body[start:end]
expected = body[:start] + '                    recruitment.add(new RecruitmentProjection(ch.getId(), ch.getMaxMerchant(), rec));\n' + body[end:]
expected = expected.replace(query, '')
marker = '        java.util.Map<Long, com.chengyinhub.business.domain.vo.TopicTemplateGameVO> gameMap = new java.util.LinkedHashMap<>();\n'
expected = expected.replace(marker, marker + '        java.util.List<RecruitmentProjection> recruitment = new java.util.ArrayList<>();\n')
expected = expected.replace('        vo.setChapters(chapterVOs);',
                            '        applyRecruitmentStats(recruitment, topic.getLifecycle(), recruitmentWindowOpen);\n        vo.setChapters(chapterVOs);')
assert after[changed[0]][1] == expected
helper = next(value[1] for key, value in after.items() if key[0] == 'applyRecruitmentStats')
copied = helper[helper.index('                rec.setCurrentOfferCount'):helper.index('                rec.setIsOpen')]
copied += helper[helper.index('                rec.setIsOpen'):helper.index(';', helper.index('                rec.setIsOpen')) + 1]
assert re.sub(r'\s+', '', projection.replace('topic.getLifecycle()', 'lifecycle')) == re.sub(r'\s+', '', copied)
assert 'TopicSizeLimits.MAX_CHAPTERS' in helper and 'if (results == null)' in helper and 'if (stats == null)' in helper

def xml(name):
    return ET.fromstring(original('chengyinhub-system/src/main/resources/mapper/business/' + name + '.xml'))

applications, offers = xml('MerchantChapterApplicationMapper'), xml('MerchantChapterOfferMapper')
one = applications.find("select[@id='selectByChapterAndStatus']")
many = applications.find("select[@id='selectByChapterIdsAndStatus']")
assert one.get('resultMap') == many.get('resultMap')
assert [n.get('refid') for n in one.findall('include')] == [n.get('refid') for n in many.findall('include')]
assert 'chapter_id = #{chapterId} and status = #{status} and del_flag = 0' in ''.join(one.itertext())
assert 'status = #{status} and del_flag = 0' in ''.join(many.itertext())
assert many.find('foreach').get('collection') == 'chapterIds'
for name in ['selectByChapter', 'selectLiveByChapterIds']:
    assert offers.find("select[@id='" + name + "']/include[@refid='liveOffer']") is not None
assert 'status = 1 and (valid_end is null or valid_end > now())' in ''.join(offers.find("sql[@id='liveOffer']").itertext())

fixtures = json.loads((DOCS / 'legacy-fixture-evidence.json').read_text())
with tempfile.TemporaryDirectory(prefix='e-recruitment-fixtures-') as tmp:
    for fixture in fixtures:
        value = original(fixture['path']).replace('CmsTopicServiceImpl', 'TopicQueryService')
        value = re.sub(r'(?m)^[ \t]*org\.springframework\.test\.util\.ReflectionTestUtils\.setField\(service, "supplyReviewService",[\s\S]*?;\n', '', value)
        target = Path(tmp) / fixture['path']
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value)
    subprocess.run(['git', 'apply', str(DOCS / 'legacy-fixture.review.patch')], cwd=tmp, check=True)
    for fixture in fixtures:
        value = (Path(tmp) / fixture['path']).read_text()
        assert len(re.findall(r'@Test\b', value)) == fixture['cases_retained']
        old_assertions = [line for line in original(fixture['path']).replace('CmsTopicServiceImpl', 'TopicQueryService').splitlines() if re.search(r'\bassert\w+\(', line)]
        assert old_assertions == [line for line in value.splitlines() if re.search(r'\bassert\w+\(', line)]
print('STATIC PASS: 22 original method bodies unchanged; original gate/body changed only for deferred statistics; state/count projection exact; SQL predicates shared; 20 legacy cases/assertions retained')
