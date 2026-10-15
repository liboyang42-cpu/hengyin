#!/usr/bin/env python3
"""Lightweight source assertions; no Java, backend, network, or database execution."""
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
BASELINE = 'd551c6babd018d41aec90dad2c0acaf1c534644d'
MEMBER_XML = 'chengyinhub-system/src/main/resources/mapper/business/UmsMemberMapper.xml'
BATCH_XML = 'chengyinhub-system/src/main/resources/mapper/business/MerchantPayableBatchMapper.xml'
AWARD_JAVA = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PointsAwardServiceImpl.java'

def read(path, baseline=False):
    if baseline:
        return subprocess.check_output(['git', 'show', BASELINE + ':' + path], cwd=ROOT, text=True)
    return (ROOT / path).read_text()

def checks(baseline=False):
    member = ET.fromstring(read(MEMBER_XML, baseline))
    batch = ET.fromstring(read(BATCH_XML, baseline))
    locked = member.find("select[@id='selectPointsForUpdate']")
    result = {}
    result['current points read bypasses snapshot/cache and does not fabricate zero'] = (
        locked is not None and locked.get('useCache') == 'false'
        and locked.get('flushCache') == 'true'
        and 'select point from ums_member where id = #{id} for update' in ' '.join(locked.itertext()))
    award = read(AWARD_JAVA, baseline)
    result['both reward paths use current points without ordinary member read'] = (
        award.count('umsMemberMapper.selectPointsForUpdate(memberId)') == 2
        and 'umsMemberService.selectUmsMemberById(memberId)' not in award)
    for action in ('confirm', 'markPaid', 'markInvoiceIssued'):
        sql = ' '.join(' '.join(batch.find("update[@id='%s']" % action).itertext()).split())
        where = sql.split(' where ', 1)[1]
        result[action + ' atomically preserves existing positive/unfrozen policy'] = (
            'amount_total > 0' in where and '(frozen is null or frozen = 0)' in where)
    return result

old, current = checks(True), checks()
for name, passed in current.items():
    print(('PASS' if passed else 'FAIL') + ': ' + name + ' (baseline=' + str(old[name]) + ')')
print('Baseline missing guards: %d/%d; candidate passing source checks: %d/%d' % (
    sum(not value for value in old.values()), len(old), sum(current.values()), len(current)))
print('LIMIT: source checks only; Java compilation, MySQL semantics, transactions and business readback NOT RUN.')
sys.exit(0 if all(current.values()) else 1)
