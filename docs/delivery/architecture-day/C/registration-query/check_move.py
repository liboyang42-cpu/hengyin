#!/usr/bin/env python3
"""Read-only checks for this one mechanical query extraction. No Java/DB/CI invocation."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]
HERE = Path(__file__).resolve().parent
CONTRACT = json.loads((HERE / 'move-contract.json').read_text())
CONTROLLER = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java'
APPLICATION = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/service/registration/RegistrationQueryApplication.java'


def baseline(path):
    return subprocess.check_output(['git', 'show', CONTRACT['baseline'] + ':' + path], cwd=ROOT, text=True)


def method(source, name):
    found = re.search(r'^    (?:public|private|protected|static)[^\n]*\b' + name + r'\(', source, re.M)
    assert found, name
    start = source.index('{', found.start())
    depth, state, i = 0, 'code', start
    while i < len(source):
        ch, pair = source[i], source[i:i + 2]
        if state == 'line':
            if ch == '\n':
                state = 'code'
        elif state == 'comment':
            if pair == '*/':
                state = 'code'
                i += 1
        elif state in ['"', "'"]:
            if ch == '\\':
                i += 1
            elif ch == state:
                state = 'code'
        elif pair == '//':
            state = 'line'
            i += 1
        elif pair == '/*':
            state = 'comment'
            i += 1
        elif ch in ['"', "'"]:
            state = ch
        elif ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return source[found.start():i + 1]
        i += 1
    raise AssertionError(name)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def verify():
    old, current = baseline(CONTROLLER), (ROOT / CONTROLLER).read_text()
    application = (ROOT / APPLICATION).read_text()
    args = {
        'getRegistrationDetail': 'Long memberId, String id', 'exploreCompletion': 'Long memberId, String id',
        'getJoinRegistrationDetail': 'Long memberId, String member_id',
        'countByDate': 'Long principalMemberId, String owner_type, String owner_id',
        'myRegistrationList': 'Long member_id, String owner_type, String status, Integer is_online', 'myJoined': 'Long memberId',
    }
    moved = []
    for name in CONTRACT['methods']:
        expected = method(old, name)
        block = CONTRACT['actorBlocks'][name]
        assert block in expected, name
        expected = expected.replace(block, '', 1).replace('return error(', 'return AjaxResult.error(')
        expected = re.sub(r'public AjaxResult ' + name + r'\([^\n]*\)', 'public AjaxResult ' + name + '(' + args[name] + ')', expected, count=1)
        if name == 'myRegistrationList':
            expected = expected.replace('        TableDataInfo data = getDataTable(list);\n        return AjaxResult.success(data);',
                                        '        return AjaxResult.success(list);')
        if name == 'countByDate':
            expected = expected.replace('hasEffectiveMerchantCoopOrder(ownerId, access.getOwnerMemberId())',
                                        'hasEffectiveMerchantCoopOrder(coopOrderMapper, ownerId, access.getOwnerMemberId())')
        assert expected == method(application, name), 'moved query drift: ' + name
        moved.append({'name': name, 'adaptedBodySha256': digest(expected)})
    for name in CONTRACT['helpers'] + ['hasEffectiveMerchantCoopOrder']:
        expected = method(old, name)
        if name == 'filterEndedOverWeek':
            expected = expected.replace('    static List', '    public static List', 1)
        if name == 'hasEffectiveMerchantCoopOrder':
            expected = expected.replace('    private boolean hasEffectiveMerchantCoopOrder(Long topicId, Long memberId)',
                                        '    public static boolean hasEffectiveMerchantCoopOrder(com.chengyinhub.business.mapper.CoopOrderMapper coopOrderMapper, Long topicId, Long memberId)')
        assert expected == method(application, name), 'moved helper drift: ' + name
        moved.append({'name': name, 'adaptedBodySha256': digest(expected)})
    names = re.findall(r'^    (?:public|private|protected|static)[^\n=;{]*\b(\w+)\(', old, re.M)
    untouched = []
    shared_bridge = False
    expected_bridge = '    private boolean hasEffectiveMerchantCoopOrder(Long topicId, Long memberId) {\n        return com.chengyinhub.web.service.registration.RegistrationQueryApplication.hasEffectiveMerchantCoopOrder(\n                coopOrderMapper, topicId, memberId);\n    }'
    for name in names:
        if name in CONTRACT['methods'] + CONTRACT['helpers']:
            continue
        if name == 'hasEffectiveMerchantCoopOrder' and method(current, name) == expected_bridge:
            shared_bridge = True
        else:
            assert method(old, name) == method(current, name), 'unowned method drift: ' + name
            untouched.append(name)
    assert re.findall(r'@PostMapping\([^\n]*', old) == re.findall(r'@PostMapping\([^\n]*', current)
    fixture_results = []
    for entry in json.loads((HERE / 'fixture-inventory.json').read_text()):
        path = entry['file']
        before, after = baseline(path), (ROOT / path).read_text()
        after = re.sub(r'RegistrationQueryTestFixtures\.wire\((controllerAs\([^()]*\)|listController\([^()]*\)|controller|c)\)', r'\1', after)
        after = after.replace('RegistrationQueryTestFixtures.application(controller)', 'controller').replace('RegistrationQueryTestFixtures.application(c)', 'c')
        after = after.replace('import com.chengyinhub.web.controller.api.RegistrationQueryTestFixtures;\n', '')
        after = after.replace('import static com.chengyinhub.web.service.registration.RegistrationQueryApplication.filterEndedOverWeek;',
                              'import static com.chengyinhub.web.controller.api.ApiRegistrationController.filterEndedOverWeek;')
        after = after.replace('com.chengyinhub.web.service.registration.RegistrationQueryApplication#filterEndedOverWeek',
                              'ApiRegistrationController#filterEndedOverWeek')
        after = after.replace('LoggerFactory.getLogger(com.chengyinhub.web.service.registration.RegistrationQueryApplication.class)',
                              'LoggerFactory.getLogger(ApiRegistrationController.class)')
        assert before == after, 'fixture changed beyond exact owner wiring: ' + path
        # Includes package-private @Test bodies, lambda assertions and continuation lines.
        assertions = lambda text: '\n'.join(line for line in text.splitlines() if re.match(r'\s*(?:assert\w+|verify\w*)\(', line))
        assert assertions(before) == assertions(after), path
        assert len(re.findall(r'@(Test|ParameterizedTest)\b', before)) == len(re.findall(r'@(Test|ParameterizedTest)\b', after)), path
        fixture_results.append({**entry, 'testDeclarations': len(re.findall(r'@(Test|ParameterizedTest)\b', before)),
                                'originalAssertionLinesSha256': digest(assertions(before)), 'assertionsAndCleanupPreserved': True})
    for patch in ['shared-coop-read.review.patch', 'refund-snapshot-owner.review.patch']:
        check = subprocess.run(['git', 'apply', '--check', str(HERE / patch)], cwd=ROOT, capture_output=True)
        if check.returncode:
            subprocess.run(['git', 'apply', '--reverse', '--check', str(HERE / patch)], cwd=ROOT, check=True)
    return {'baseline': CONTRACT['baseline'], 'moved': moved, 'unchangedNonQueryMethods': untouched,
            'originalHttpMappingsPreserved': True, 'sharedCoopReadBridgeApplied': shared_bridge, 'fixtureResults': fixture_results,
            'testDeclarationsPreserved': sum(x['testDeclarations'] for x in fixture_results),
            'newJavaCasesNotRun': 6, 'reviewPatchesApplyCheck': True}


if __name__ == '__main__':
    print(json.dumps(verify(), ensure_ascii=False, indent=2))
