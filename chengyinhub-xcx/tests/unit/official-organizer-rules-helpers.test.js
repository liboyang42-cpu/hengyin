'use strict';

// BL-144:补 #27(CL4)organizer-rules 纯函数单测(依 #180 TEST-GAPS.md),不改被测代码。
const test = require('node:test');
const assert = require('node:assert/strict');
const r = require('../../pages/activity/official-organizer/organizer-rules.js');

test('BL-144 composePauseField:预设+补充用全角冒号拼接,两侧都 trim', () => {
  assert.equal(r.composePauseField({ preset: '天气或安全原因', extra: '暴雨预警' }), '天气或安全原因：暴雨预警');
  assert.equal(r.composePauseField({ preset: '  天气或安全原因 ', extra: '  暴雨预警  ' }), '天气或安全原因：暴雨预警');
});

test('BL-144 composePauseField:只有一边时单独成立', () => {
  assert.equal(r.composePauseField({ preset: '全部已报名玩家' }), '全部已报名玩家');
  assert.equal(r.composePauseField({ preset: '全部已报名玩家', extra: '   ' }), '全部已报名玩家');
  assert.equal(r.composePauseField({ extra: '只影响三号点位' }), '只影响三号点位');
  assert.equal(r.composePauseField({ preset: '', extra: '只影响三号点位' }), '只影响三号点位');
});

test('BL-144 composePauseField:空值/非字符串一律返回空串', () => {
  for (const entry of [undefined, null, {}, { preset: '', extra: '' }, { preset: '  ', extra: '\n' },
    { preset: 1, extra: true }, { preset: ['x'], extra: { a: 1 } }]) {
    assert.equal(r.composePauseField(entry), '', JSON.stringify(entry));
  }
});

test('BL-144 composePauseField 贯穿 validateForm/buildPayload:四项缺一不可,payload 用合成值', () => {
  const full = {
    reason: { preset: '天气或安全原因' },
    impactScope: { extra: '全部玩家' },
    alternativePlan: { preset: '改期，时间另行通知', extra: '下周六' },
    notificationPlan: { preset: '主办方另行发布公告' },
  };
  assert.equal(r.validateForm('pause', full), '');
  assert.deepEqual(r.buildPayload('pause', full), {
    reason: '天气或安全原因',
    impactScope: '全部玩家',
    alternativePlan: '改期，时间另行通知：下周六',
    notificationPlan: '主办方另行发布公告',
  });
  const missing = Object.assign({}, full, { notificationPlan: { preset: ' ', extra: '' } });
  assert.match(r.validateForm('pause', missing), /都要选一项或写几句/);
  const tooLong = Object.assign({}, full, { reason: { extra: 'x'.repeat(301) } });
  assert.match(r.validateForm('pause', tooLong), /最多 300 字/);
});

test('BL-144 verifyReadBack schedule:status>=1 的整数才 confirmed', () => {
  assert.equal(r.verifyReadBack('schedule', { status: 1 }), 'confirmed');
  assert.equal(r.verifyReadBack('schedule', { status: 3 }), 'confirmed');
  for (const after of [{ status: 0 }, { status: '1' }, { status: 1.5 }, { status: null }, {}, null, undefined]) {
    assert.equal(r.verifyReadBack('schedule', after), 'mismatch', JSON.stringify(after));
  }
});

test('BL-144 verifyReadBack pause/resume:回读不一致必须失败', () => {
  assert.equal(r.verifyReadBack('pause', { controlState: 'PAUSED' }), 'confirmed');
  assert.equal(r.verifyReadBack('resume', { controlState: 'NORMAL' }), 'confirmed');
  assert.equal(r.verifyReadBack('pause', { controlState: 'NORMAL' }), 'mismatch');
  assert.equal(r.verifyReadBack('resume', { controlState: 'PAUSED' }), 'mismatch');
  assert.equal(r.verifyReadBack('pause', { controlState: 'paused' }), 'mismatch', '大小写不同不得放过');
  assert.equal(r.verifyReadBack('pause', {}), 'mismatch');
  assert.equal(r.verifyReadBack('resume', null), 'mismatch');
});

test('BL-144 verifyReadBack:未知动作恒 mismatch', () => {
  assert.equal(r.verifyReadBack('invite', { status: 1, controlState: 'PAUSED' }), 'mismatch');
  assert.equal(r.verifyReadBack(undefined, { status: 1 }), 'mismatch');
});
