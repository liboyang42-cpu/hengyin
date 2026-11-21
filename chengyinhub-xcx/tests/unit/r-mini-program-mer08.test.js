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

test('招商截止:项目页底部主按钮写明「报名已结束 / 报名未开始」,窗口内照旧(走查第 2 条)', () => {
  const { signUpWindowText } = require('../../utils/merchant-signup-window.js');
  const info = { merchantSignUpStartDate: '2026-02-05', merchantSignUpEndDate: '2026-03-28' };
  assert.equal(signUpWindowText(info, Date.parse('2026-03-28T23:00:00+08:00')), '');
  assert.equal(signUpWindowText(info, Date.parse('2026-03-29T00:00:01+08:00')), '报名已结束');
  assert.equal(signUpWindowText(info, Date.parse('2026-02-04T23:59:00+08:00')), '报名未开始');
  assert.equal(signUpWindowText({}, Date.now()), '');
  assert.equal(signUpWindowText({ merchantSignUpStartDate: 'x', merchantSignUpEndDate: 'y' }, Date.now()), '');
  const wxml = read('pages/topic/merchantinfo/merchantinfo.wxml');
  assert.match(wxml, /disabled="\{\{!myChapterApplications\.length && !!signUpClosedText\}\}"/);
  assert.match(wxml, /signUpClosedText \|\| '申请承接'/);
  assert.match(read('pages/topic/merchantinfo/merchantinfo.js'), /signUpClosedText: signUpWindowText\(d, Date\.now\(\)\)/);
});

test('入驻审核状态页手机号脱敏,表单与刚提交的回执保留原号(走查 §3.8)', () => {
  const src = read('pages/merchant/apply/index.js');
  const fn = src.match(/function maskPhone\(value\) \{[\s\S]*?\n\}/);
  assert.ok(fn, '入驻页必须带 maskPhone');
  const maskPhone = new Function(fn[0] + '; return maskPhone;')();
  assert.equal(maskPhone('13800001234'), '138****1234');
  assert.equal(maskPhone(' 13800001234 '), '138****1234');
  assert.equal(maskPhone('021-12345678'), '021-12345678');
  assert.equal(maskPhone(null), ''); assert.equal(maskPhone(''), '');
  const wxml = read('pages/merchant/apply/index.wxml');
  assert.match(wxml, /\{\{apply\.phoneMasked \|\| '—'\}\}/);
  assert.match(wxml, /value="\{\{phone\}\}"/);
  const js = read('pages/merchant/apply/index.js');
  assert.match(js, /apply\.phoneMasked = maskPhone\(apply\.phone\)/);
});
