const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { shapeAccount, STATE_TEXT } = require('../../pages/merchant/payout-account/view-model.js');

test('平台未开放时只给不可用态，不显示任何账户数据', () => {
  assert.deepEqual(shapeAccount({ available: false, unavailableReason: 'PLATFORM_NOT_OPEN' }), { pageState: 'unavailable' });
});

test('未登记时为未开通空态', () => {
  assert.deepEqual(shapeAccount({ available: true, state: 'NONE' }), { pageState: 'none' });
});

test('待签约给出 https 签约链接；已开通给掩码商户号且不给链接', () => {
  const sign = shapeAccount({ available: true, state: 'NEED_SIGN', signUrl: 'https://pay.weixin.qq.com/x', stateDesc: '请签约' });
  assert.equal(sign.pageState, 'ready');
  assert.equal(sign.stateText, '待签约');
  assert.equal(sign.signUrl, 'https://pay.weixin.qq.com/x');
  assert.equal(sign.stateDesc, '请签约');
  assert.equal(sign.subMchid, '');
  const done = shapeAccount({ available: true, state: 'FINISH', subMchidMasked: '19****0101', signUrl: 'https://x', sellerRouting: true });
  assert.equal(done.stateText, '已开通');
  assert.equal(done.stateTone, 'success');
  assert.equal(done.subMchid, '19****0101');
  assert.equal(done.signUrl, '');
  assert.equal(done.routing, true);
});

test('非 https 链接、未知状态、坏回包一律不当作可用', () => {
  assert.equal(shapeAccount({ available: true, state: 'NEED_SIGN', signUrl: 'http://x' }).signUrl, '');
  assert.equal(shapeAccount({ available: true, state: 'WHATEVER' }), null);
  assert.equal(shapeAccount(null), null);
  assert.equal(shapeAccount([]), null);
  assert.equal(shapeAccount({ available: true }), null);
});

test('每个服务端状态都有中文名', () => {
  for (const s of ['DRAFT', 'CHECKING', 'ACCOUNT_NEED_VERIFY', 'AUDITING', 'REJECTED', 'NEED_SIGN', 'FINISH', 'FROZEN', 'CANCELED']) {
    assert.ok(STATE_TEXT[s], s);
  }
});

test('入口只在财务查看权限下出现，并登记在商家分包', () => {
  const root = path.join(__dirname, '../..');
  const wxml = fs.readFileSync(path.join(root, 'pages/merchant/index/index.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{merchantAccess\.canReadFinance\}\}" bindtap="onMoreItem" data-action="payoutAccount"/);
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
  const merchant = app.subPackages.find((p) => p.root === 'pages/merchant');
  assert.ok(merchant.pages.includes('payout-account/index'));
});
