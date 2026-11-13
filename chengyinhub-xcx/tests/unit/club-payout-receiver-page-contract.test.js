'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { shapeStatus, errorText } = require('../../pages/club/payout-receiver/view-model.js');

const ROOT = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

test('状态：未绑定 / 已绑定 / 已暂停 只由服务端事实决定', () => {
  const base = { available: true, payoutEnabled: true, paused: false, canUnbind: true };
  assert.equal(shapeStatus({ ...base, receiverState: 'UNBOUND' }).pageState, 'unbound');
  const bound = shapeStatus({ ...base, receiverState: 'VERIFIED' });
  assert.equal(bound.pageState, 'bound');
  assert.equal(bound.payoutText, '');
  assert.equal(bound.canUnbind, true);
  const paused = shapeStatus({ ...base, receiverState: 'CONSENTED', paused: true, pauseReason: 'PERSONAL_BINDING_REQUIRED' });
  assert.equal(paused.pageState, 'paused');
  assert.equal(paused.pauseText, '绑定尚未确认');
});

test('平台开关未开：以状态行表达，不是说明文字；未绑定时不能解除', () => {
  const s = shapeStatus({ available: true, receiverState: 'VERIFIED', payoutEnabled: false, paused: false, canUnbind: true });
  assert.equal(s.pageState, 'bound');
  assert.equal(s.payoutText, '平台开通后开始');
  assert.equal(shapeStatus({ available: true, receiverState: 'UNBOUND', payoutEnabled: false, canUnbind: true }).canUnbind, false);
});

test('未知形状一律判失败；错误编码不露给用户', () => {
  assert.equal(shapeStatus(null), null);
  assert.equal(shapeStatus({ available: false }), null);
  assert.equal(shapeStatus({ available: true, receiverState: 'X' }), null);
  assert.equal(errorText('SOMETHING_UNKNOWN', '兜底'), '兜底');
  assert.equal(errorText('还有收款正在处理，处理完成后才能解除绑定', '兜底'), '还有收款正在处理，处理完成后才能解除绑定');
  assert.equal(errorText('CLUB_OWNER_REQUIRED', '兜底'), '仅俱乐部主理人可设置收款');
});

test('页面在分包内、不在 play、不发 openid；解除走危险确认', () => {
  const app = JSON.parse(read('app.json'));
  const club = (app.subpackages || app.subPackages).find((s) => s.root === 'pages/club');
  assert.ok(club.pages.includes('payout-receiver/index'));
  assert.ok(!app.pages.some((p) => p.includes('payout-receiver')));
  const js = read('pages/club/payout-receiver/index.js');
  assert.ok(!/openid/i.test(js));
  assert.ok(/consent: true/.test(js));
  assert.ok(/dc\.open\('club\.personal-receiver\.unbind'/.test(js));
  assert.ok(!/@keyframes/.test(read('pages/club/payout-receiver/index.wxss')));
});

test('入口：俱乐部结算页（主理人）与我的资产更多菜单', () => {
  assert.ok(/payout-receiver\/index\?clubId=/.test(read('pages/club/settlement/index.js')));
  assert.ok(/summary\.isOwner && clubId/.test(read('pages/club/settlement/index.wxml')));
  assert.ok(/payout-receiver\/index\?mine=1/.test(read('subpackageA/components/scene-asset-earnings/index.js')));
});
