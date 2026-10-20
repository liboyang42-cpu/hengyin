// BL-150：subpackageMember/report-common/view.js 的 appealEventCount / positiveId 单测补齐
// 依赖 #25（CL2），须在其后合并；不改被测代码。
// 事件类型口径：CommunityTrustServiceImpl.java 申诉时写入 eventType "APPEALED"（每次申诉一条）。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const view = require('../../subpackageMember/report-common/view.js');

test('appealEventCount: 非数组 / 空时间线计 0', () => {
  for (const v of [null, undefined, {}, 'APPEALED', 3, []]) {
    assert.equal(view.appealEventCount(v), 0);
  }
});

test('appealEventCount: 只计 APPEALED，大小写与首尾空白归一，忽略空项与其他类型', () => {
  const timeline = [
    null,
    { eventType: 'SUBMITTED' },
    { eventType: 'STAGE_CHANGED', toStage: 'APPEALED' },
    { eventType: ' appealed ' },
    {},
    'APPEALED',
  ];
  assert.equal(view.appealEventCount(timeline), 1);
});

test('appealEventCount: 时间线里重复的 APPEALED 事件逐条计数（不去重）', () => {
  const timeline = [
    { eventType: 'SUBMITTED' },
    { eventType: 'APPEALED', createTime: '2026-10-15 10:00:00' },
    { eventType: 'APPEALED', createTime: '2026-10-15 10:00:00' },
    { eventType: 'APPEALED' },
  ];
  assert.equal(view.appealEventCount(timeline), 3);
});

test('appealConfirmed 依赖 appealEventCount：重复事件下仍按「比提交前多」判定', () => {
  const before = view.appealEventCount([{ eventType: 'APPEALED' }, { eventType: 'APPEALED' }]);
  assert.equal(before, 2);
  // 回读仍是 2 条且阶段已回到调查中：不能当作新申诉已确认
  assert.equal(view.appealConfirmed(before, {
    stage: 'INVESTIGATING',
    timeline: [{ eventType: 'APPEALED' }, { eventType: 'APPEALED' }],
  }), false);
  // 多出一条才确认
  assert.equal(view.appealConfirmed(before, {
    stage: 'INVESTIGATING',
    timeline: [{ eventType: 'APPEALED' }, { eventType: 'APPEALED' }, { eventType: 'APPEALED' }],
  }), true);
  // 阶段直接是 APPEALED 也算确认
  assert.equal(view.appealConfirmed(before, { stage: 'appealed', timeline: [] }), true);
  // 回读为空不确认
  assert.equal(view.appealConfirmed(0, null), false);
});

test('positiveId: 字符串数字转成数字', () => {
  assert.equal(view.positiveId('12'), 12);
  assert.equal(view.positiveId(' 7 '), 7);
  assert.equal(view.positiveId(9), 9);
});

test('positiveId: 非法值一律返回 0（不是 null）', () => {
  for (const v of [0, '0', -1, '-3', 'abc', '12abc', 1.5, '1.5', NaN, Infinity, null, undefined, '', {}, []]) {
    assert.equal(view.positiveId(v), 0, String(v));
  }
  // 现状记录：Number(true) === 1，布尔 true 会被当成 id 1；页面入参来自 URL 字符串，不会出现布尔值
  assert.equal(view.positiveId(true), 1);
});

test('positiveId: 超过安全整数的 id 拒收（防精度丢失后错开别人的举报）', () => {
  assert.equal(view.positiveId(Number.MAX_SAFE_INTEGER), Number.MAX_SAFE_INTEGER);
  assert.equal(view.positiveId('9007199254740993'), 0);
});

test('positiveId: 页面 onLoad 用它取 reportId，非法 id 不发请求的前提', () => {
  const src = require('node:fs').readFileSync(
    require('node:path').join(__dirname, '../../subpackageMember/report-detail/index.js'), 'utf8');
  assert.match(src, /view\.positiveId\(options && options\.reportId\)/);
});
