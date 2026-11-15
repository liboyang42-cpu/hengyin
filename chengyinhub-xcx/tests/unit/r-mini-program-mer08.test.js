'use strict';
// T6 mini-program / MER-08:2026-09-27 商家走查缺陷复验后的修复。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const wb = require('../../utils/merchant-workbench.js');
const read = rel => fs.readFileSync(path.resolve(__dirname, '../..', rel), 'utf8');

test('起始日:带时区的时刻按上海日历日,裸串照旧(走查「起始日差一天」)', () => {
  assert.equal(wb.toDayStr('2026-09-25T16:00:00.000Z'), '2026-09-26');
  assert.equal(wb.toDayStr('2026-09-25T16:00:00.000+0000'), '2026-09-26');
  assert.equal(wb.toDayStr('2026-09-26T00:00:00+08:00'), '2026-09-26');
  assert.equal(wb.toDayStr('2026-09-26'), '2026-09-26');
  assert.equal(wb.toDayStr('2026-09-26 23:30:00'), '2026-09-26');
  assert.equal(wb.toDayStr(''), ''); assert.equal(wb.toDayStr(null), ''); assert.equal(wb.toDayStr('bad'), '');
  // 状态签与已结束判据跟着同一换算
  assert.equal(wb.statusLabelOf('2026-09-25T16:00:00Z', '2026-10-03T16:00:00Z', '2026-09-26'), '进行中');
  assert.equal(wb.statusLabelOf('2026-09-25T16:00:00Z', '2026-10-03T16:00:00Z', '2026-10-04'), '进行中');
  assert.equal(wb.statusLabelOf('2026-09-25T16:00:00Z', '2026-10-03T16:00:00Z', '2026-10-05'), '已结束');
});

test('待审核:工作台卡、「我的」预览、项目页同一个词', () => {
  const cards = wb.buildProjectCards({ joinList: [{ id: 1, topicId: 9, status: 0, startDate: '2099-01-01', endDate: '2099-01-09' }], today: '2026-11-15' });
  assert.equal(cards[0].statusLabel, '待审核');
  assert.match(read('subpackageMember/components/cy/profile/index.js'), /hit\.status === 0 \? '待审核'/);
  assert.match(read('pages/topic/merchantinfo/merchantinfo.js'), /key: 'pending', cls: 'state-neutral', text: '待审核'/);
  assert.match(read('subpackageMember/components/cy/profile/index.js'), /return dayKey\(str\)\.replace/);
});
