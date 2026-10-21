'use strict';

// BL-151:补 CL4(#27)两处纯函数的边界用例,不改被测代码。
//   subpackageMember/components/cy/npc-chat/chat-export.js            timeText
//   subpackageMember/components/cy/profile/points-rules.js  pointValue(+ splitPointsRules 缺值兜底)
// 依赖 #27,须在其后合并。

const test = require('node:test');
const assert = require('node:assert/strict');

const { timeText } = require('../../subpackageMember/components/cy/npc-chat/chat-export.js');
const { pointValue, splitPointsRules } = require('../../subpackageMember/components/cy/profile/points-rules.js');

test('timeText:ISO 串带 +08:00 / 毫秒 → 北京时间,去掉 T、毫秒与 +08:00', () => {
  assert.equal(timeText('2026-10-01T12:00:00.000+08:00'), '2026-10-01 12:00:00');
  assert.equal(timeText('2026-10-01T12:00:00+0800'), '2026-10-01 12:00:00');
  assert.equal(timeText('  2026-10-01T12:00:00+08:00  '), '2026-10-01 12:00:00');
});

test('timeText:非 +08:00 的偏移照实保留,不冒充北京时间', () => {
  assert.equal(timeText('2026-10-01T04:00:00.123Z'), '2026-10-01 04:00:00Z');
  assert.equal(timeText('2026-10-01T13:00:00.000+09:00'), '2026-10-01 13:00:00+09:00');
  assert.equal(timeText('2026-10-01T00:00:00-05:00'), '2026-10-01 00:00:00-05:00');
});

test('timeText:毫秒时间戳按北京时间格式化,补零', () => {
  assert.equal(timeText(Date.UTC(2026, 9, 1, 4, 0, 0)), '2026-10-01 12:00:00');
  assert.equal(timeText(Date.UTC(2026, 0, 2, 17, 5, 9)), '2026-01-03 01:05:09');
});

test('timeText:秒级时间戳同样按毫秒解释(服务端只下发 ISO 串,本端不猜单位)', () => {
  // 1759291200 秒 = 2025-10-01 12:00 北京时间;按毫秒解释落在 1970-01-21。
  // 记录现状:若后端改为秒级时间戳,这里会显示 1970 年,需要同步改 timeText。
  assert.equal(timeText(1759291200), '1970-01-21 16:41:31');
});

test('timeText:空值 / 空白串 / 0 / 负数 / 非有限数 → 时间未记录', () => {
  for (const value of [undefined, null, '', '   ', 0, -1, NaN, Infinity, {}, []]) {
    assert.equal(timeText(value), '时间未记录', String(value));
  }
});

test('pointValue:正数与数字串取两位小数', () => {
  assert.equal(pointValue(5), 5);
  assert.equal(pointValue('5'), 5);
  assert.equal(pointValue(1.25), 1.25);
  assert.equal(pointValue('3.456'), 3.46);
});

test('pointValue:0 是真实配置值,保留为 0(不当成缺失)', () => {
  assert.equal(pointValue(0), 0);
  assert.equal(pointValue('0'), 0);
});

test('pointValue:负数、空值、非数字 → null(页面显示「以平台规则为准」,不显示 +0)', () => {
  for (const raw of [-5, '-1', -0.01, null, undefined, '', 'abc', NaN, Infinity, -Infinity]) {
    assert.equal(pointValue(raw), null, String(raw));
  }
});

test('splitPointsRules:缺 value / 负值的启用规则落「以平台规则为准」,0 分照实显示', () => {
  const out = splitPointsRules([
    { id: 1, title: '签到', status: 1, eventType: 1 },
    { id: 2, title: '分享', status: 1, eventType: 2, value: -3 },
    { id: 3, title: '浏览', status: 1, eventType: 3, value: 0 },
    { id: 4, title: '解锁提示', status: 1, eventType: 14, value: '' },
  ]);
  assert.deepEqual(out.earn.map((r) => [r.id, r.points, r.pointsText]), [
    [1, null, '以平台规则为准'],
    [2, null, '以平台规则为准'],
    [3, 0, ''],
  ]);
  assert.deepEqual(out.spend.map((r) => [r.id, r.points, r.pointsText]), [[4, null, '以平台规则为准']]);
});
