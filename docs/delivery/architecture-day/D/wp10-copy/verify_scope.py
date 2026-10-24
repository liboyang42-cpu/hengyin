#!/usr/bin/env python3
"""Source-only ownership/body checks; not Java compilation or a business-equivalence test.
Requires the exact ad7e read-only source tree and the candidate source tree.
"""
import argparse
import hashlib
import json
import pathlib
import re

PROD = 'chengyinhub-system/src/main/java/com/chengyinhub/business/'
GOD = PROD + 'service/impl/CmsTopicServiceImpl.java'
APP = PROD + 'service/impl/TopicContentCopyServiceImpl.java'
MOVED = {'copyCmsTopicWithContentOwner', 'preserveSameOwnerCopiedCompletionRule', 'copyTopicBasicInfo',
         'copyChaptersAndNodes', 'assertCopiedChapterRecruitment', 'assertCopiedChapterSupplyTerms',
         'copyNodes', 'copyNodeNpc', 'copyCollaborators', 'remapCopiedJourneyStory'}
DELEGATED = {'copyCmsTopic', 'copyOfficialCmsTopic', 'copyTemplateForOwner',
             'declareRequiredPlatformContentSlot', 'remapTemplateRuleIdentities'}


def mask(text):
    return re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda m: ''.join('\n' if c == '\n' else ' ' for c in m[0]), text)


def methods(text):
    masked = mask(text)
    found = {}
    pat = r'(?m)^    (?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>, ?\[\].]+?\s+(\w+)\s*\(([^;{}]*)\)\s*(?:throws\s+[\w., ]+)?\{'
    for match in re.finditer(pat, masked):
        depth, end = 1, match.end()
        while depth:
            depth += (masked[end] == '{') - (masked[end] == '}')
            end += 1
        signature = (match[1], ' '.join(match[2].split()))
        assert signature not in found, signature
        found[signature] = text[match.end():end - 1]
    return found


def named(items, name):
    values = [body for (method, args), body in items.items() if method == name]
    assert len(values) == 1, name
    return values[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-root', required=True, type=pathlib.Path)
    parser.add_argument('--candidate-root', required=True, type=pathlib.Path)
    args = parser.parse_args()
    base, candidate = args.base_root, args.candidate_root
    raw = (base / GOD).read_text()
    data = raw.encode()
    assert hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == '9686e2e983ce139c6593cfe9692b42bcdbfdc4de'
    old, new = methods(raw), methods((candidate / GOD).read_text())
    unchanged = 0
    for key, body in old.items():
        if key[0] in MOVED:
            assert key not in new, key
        elif key[0] not in DELEGATED:
            assert new[key] == body, 'unowned method body changed: ' + str(key)
            unchanged += 1
    assert set(new) == {k for k in old if k[0] not in MOVED}, 'unexpected facade method changes'
    app_text = (candidate / APP).read_text()
    app = methods(app_text)
    for name in MOVED | {'copyCmsTopic', 'copyOfficialCmsTopic'}:
        expected = named(old, name)
        expected = expected.replace('CmsTopic originalTopic = selectCmsTopicById(id);',
                                    'CmsTopic originalTopic = cmsTopicMapper.selectCmsTopicById(id);')
        expected = expected.replace('declareRequiredPlatformContentSlot(newTopicId);',
                                    'platformContentSlotGuard.declareRequiredPlatformContentSlot(newTopicId);')
        expected = expected.replace('RouteNodeMaps', 'CopiedContentIds').replace(
            'remapTemplateRuleIdentities(', 'TopicTemplateRuleIdentityRemapper.remap(')
        assert named(app, name) == expected, 'moved body changed: ' + name
    guard = methods((candidate / (PROD + 'service/support/TopicPlatformContentSlotGuard.java')).read_text())
    pure = methods((candidate / (PROD + 'service/support/TopicTemplateRuleIdentityRemapper.java')).read_text())
    assert named(guard, 'declareRequiredPlatformContentSlot') == named(old, 'declareRequiredPlatformContentSlot')
    assert named(pure, 'remap') == named(old, 'remapTemplateRuleIdentities')
    for forbidden in ['ICmsTopicService', 'CmsTopicServiceImpl', 'TopicSale', 'Wechat', 'Notify',
                      'TransactionTemplate', 'Controller', 'ApplicationContext', 'getBean(']:
        assert forbidden not in mask(app_text), forbidden
    assert 'class RouteNodeMaps' in (candidate / GOD).read_text()
    template = PROD + 'service/impl/CmsMemberTemplateServiceImpl.java'
    old_template, new_template = methods((base / template).read_text()), methods((candidate / template).read_text())
    for key, body in old_template.items():
        assert new_template[key] == body, 'existing template method changed: ' + str(key)
    assert {k[0] for k in new_template} - {k[0] for k in old_template} == {'copyForContentOwner'}
    clone = named(old, 'copyTemplateForOwner')
    original_clone = clone[clone.index('        CmsMemberTemplate source ='):clone.index('        templateIdMap.put(templateId, copy.getId());')]
    original_clone = original_clone.replace('            templateIdMap.put(templateId, templateId);\n', '')
    assert original_clone in named(new_template, 'copyForContentOwner'), 'private-template clone logic changed'
    counts = json.loads((candidate / 'docs/delivery/architecture-day/D/wp10-copy/existing-test-counts.json').read_text())
    for item in counts:
        before, after = (base / item['path']).read_text(), (candidate / item['path']).read_text()
        names = lambda source: re.findall(r'@Test\b[^{};]*?\bvoid\s+(\w+)\s*\(', mask(source))
        assert names(before) == names(after), item['path']
        assert len(re.findall(r'@Test\b', before)) == len(re.findall(r'@Test\b', after)) == item['tests_before']
        # Only fixture-owner assembly / source-owner path / logger category may change in pre-existing tests.
        restored = re.sub(r'com\.chengyinhub\.business\.service\.support\.TopicCopyTestWiring\.(?:wire|owner)\((service|svc)\)', r'\1', after)
        restored = restored.replace('com.chengyinhub.business.service.impl.TopicContentCopyServiceImpl.class', 'CmsTopicServiceImpl.class')
        restored = restored.replace('impl/TopicContentCopyServiceImpl.java', 'impl/CmsTopicServiceImpl.java')
        if item['path'].endswith('RevshareGateWiringTest.java'):
            restored = restored.replace('flat("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java")', 'flat(TOPIC)')
        assert restored == before, 'existing assertions/behavior changed: ' + item['path']
    # A bounded delimiter check is deliberately weaker than Java compilation.
    for path in [candidate / APP, candidate / GOD, candidate / template]:
        clean = mask(path.read_text())
        stack = []
        for char in clean:
            if char in '([{': stack.append(char)
            elif char in ')]}': assert stack and stack.pop() == {')': '(', ']': '[', '}': '{'}[char], path
        assert not stack, path
    print(json.dumps({'result': 'PASS', 'unowned_facade_methods_byte_identical': unchanged,
                      'moved_method_bodies_matched': 12, 'shared_guard_and_pure_bodies_matched': 2,
                      'old_test_classes': len(counts), 'old_test_cases_preserved': sum(c['tests_before'] for c in counts),
                      'java_mysql_executed': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
