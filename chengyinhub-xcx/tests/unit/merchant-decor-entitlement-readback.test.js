'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const pagePath = require.resolve('../../pages/merchant/decor/index.js');
function harness(t) {
  const h = { uid: '9', requests: [], payments: [], toasts: [] };
  global.getApp = () => ({ globalData: {}, getUserID: () => h.uid,
    sendRequest(row) { h.requests.push(row); return { abort() {} }; } });
  global.wx = { showToast: row => h.toasts.push(row.title), requestPayment: row => h.payments.push(row),
    showModal() {}, hideLoading() {}, setNavigationBarColor() {} };
  global.Page = row => { h.definition = row; };
  delete require.cache[pagePath]; require(pagePath);
  h.page = { ...h.definition, data: JSON.parse(JSON.stringify(h.definition.data)),
    setData(patch) { Object.assign(this.data, patch); } };
  h.find = part => h.requests.filter(row => row.url.endsWith(part)).at(-1);
  h.open = () => { h.page.loadSubs(); h.find('/capabilities').success({ code: 200, data: { selfCheckoutEnabled: true } });
    h.find('/subscription').success({ code: 200, data: [] }); };
  h.buy = () => h.page.tapTier({ currentTarget: { dataset: { key: 'premium_template' } } });
  t.after(() => h.page.onUnload());
  return h;
}
const grant = (extra = {}) => ({ id: 31, merchantId: 90, subscriptionType: 'premium_template', isActive: 1,
  startDate: '2020-01-01 00:00:00', endDate: '2099-01-01 00:00:00', ...extra });
for (const value of [true, false, 'false', 'true', 0, 1, null, {}]) {
  test('capabilities accepts only actual boolean: ' + JSON.stringify(value), t => {
    const h = harness(t); h.page.loadSubs();
    assert.equal(h.page.data.capabilityState, 'loading');
    h.find('/capabilities').success({ code: 200, data: { selfCheckoutEnabled: value } });
    assert.equal(h.page.data.capabilityState, typeof value === 'boolean' ? 'ready' : 'error');
    assert.equal(h.page.data.chargeEnabled, typeof value === 'boolean' ? value : null);
  });
}
test('failed capability read is unknown, never a false/manual-route decision', t => {
  const h = harness(t); h.page.loadSubs(); h.find('/capabilities').fail({ errMsg: 'request:fail' }); h.buy();
  assert.equal(h.page.data.capabilityState, 'error'); assert.equal(h.page.data.chargeEnabled, null);
  assert.equal(h.requests.some(row => row.url.endsWith('/order')), false);
  assert.ok(!h.toasts.some(text => /联系运营开通/.test(text)));
});
test('subscriptions distinguish failure from legitimate empty and active grants', t => {
  const h = harness(t); h.page.loadActiveSubs(); h.find('/subscription').fail({ errMsg: 'request:fail' });
  assert.equal(h.page.data.subscriptionState, 'error');
  h.page.loadActiveSubs(); h.find('/subscription').success({ code: 200, data: [] });
  assert.equal(h.page.data.subscriptionState, 'empty');
  h.page.loadActiveSubs(); h.find('/subscription').success({ code: 200, data: [grant()] });
  assert.equal(h.page.data.subscriptionState, 'ready'); assert.equal(h.page.data.activeSubs.length, 1);
});
for (const paidPath of ['order-replay', 'sdk-and-status']) {
  for (const readback of ['failed', 'empty', 'future', 'other-type', 'active']) {
    test(paidPath + ' requires authoritative matching active grant: ' + readback, t => {
      const h = harness(t); h.open(); h.buy();
      if (paidPath === 'order-replay') h.find('/order').success({ code: 200, data: { orderSn: 'synthetic-order', paymentStatus: 'success' } });
      else {
        h.find('/order').success({ code: 200, data: { orderSn: 'synthetic-order', paymentStatus: 'pending',
          timeStamp: '1', nonceStr: 'test', package: 'prepay_id=test', signType: 'RSA', paySign: 'test' } });
        h.payments[0].success(); h.find('/status').success({ code: 200, data: { paymentStatus: 'success' } });
      }
      assert.equal(h.page.data.checkoutState, 'entitlementPending');
      assert.ok(!h.toasts.some(text => /到账|生效/.test(text)));
      const read = h.find('/subscription');
      if (readback === 'failed') read.fail({ errMsg: 'request:fail' });
      else read.success({ code: 200, data: readback === 'empty' ? [] : [grant(readback === 'future'
        ? { startDate: '2098-01-01 00:00:00' } : readback === 'other-type' ? { subscriptionType: 'custom_event' } : {})] });
      assert.equal(h.page.data.checkoutState, readback === 'active' ? 'success' : 'entitlementUnknown');
      assert.equal(h.toasts.some(text => /已确认.*权益生效/.test(text)), readback === 'active');
      const writes = h.requests.filter(row => row.url.endsWith('/order')).length, pays = h.payments.length;
      h.buy();
      if (readback !== 'active') h.page.retryEntitlements();
      assert.equal(h.requests.filter(row => row.url.endsWith('/order')).length, writes);
      assert.equal(h.payments.length, pays);
    });
  }
}
for (const late of ['capabilities', 'subscription', 'order']) {
  test('owner switch rejects late ' + late + ' results and payment launch', t => {
    const h = harness(t); h.open();
    if (late === 'order') h.buy(); else if (late === 'capabilities') h.page.loadSubs(); else h.page.loadActiveSubs();
    const row = h.find('/' + late); h.uid = '22';
    const snapshot = JSON.stringify(h.page.data);
    row.success({ code: 200, data: late === 'capabilities' ? { selfCheckoutEnabled: true } : late === 'subscription' ? [grant()]
      : { orderSn: 'synthetic-order', paymentStatus: 'pending', timeStamp: '1', nonceStr: 'test', package: 'test', signType: 'RSA', paySign: 'test' } });
    assert.equal(JSON.stringify(h.page.data), snapshot); assert.equal(h.payments.length, 0);
  });
}

test('legitimate false keeps existing manual route and never buys', t => {
  const h = harness(t); h.page.loadSubs();
  h.find('/capabilities').success({ code: 200, data: { selfCheckoutEnabled: false } }); h.buy();
  assert.ok(h.toasts.includes('请点下方「联系运营开通」'));
  assert.equal(h.requests.some(row => row.url.endsWith('/order')), false);
});
test('late SDK success after owner switch cannot query under another owner', t => {
  const h = harness(t); h.open(); h.buy();
  h.find('/order').success({ code: 200, data: { orderSn: 'synthetic-order', paymentStatus: 'pending',
    timeStamp: '1', nonceStr: 'test', package: 'prepay_id=test', signType: 'RSA', paySign: 'test' } });
  h.uid = '22'; const count = h.requests.length; h.payments[0].success();
  assert.equal(h.requests.length, count); assert.ok(!h.toasts.some(text => /到账|生效/.test(text)));
});
test('new owner must read its capabilities and never reuse old owner requestId', t => {
  const h = harness(t); h.open(); h.buy(); const original = JSON.parse(h.find('/order').data).requestId;
  h.uid = '22'; h.buy(); assert.equal(h.find('/capabilities'), h.requests.at(-2));
  h.find('/capabilities').success({ code: 200, data: { selfCheckoutEnabled: true } });
  h.find('/subscription').success({ code: 200, data: [] }); h.buy();
  assert.notEqual(JSON.parse(h.find('/order').data).requestId, original);
});
test('new read wins; stale list and unloaded page cannot overwrite entitlement state', t => {
  const h = harness(t); h.page.loadActiveSubs(); const old = h.find('/subscription');
  h.page.loadActiveSubs(); h.find('/subscription').success({ code: 200, data: [] });
  old.success({ code: 200, data: [grant()] }); assert.equal(h.page.data.subscriptionState, 'empty');
  h.page.loadActiveSubs(); const late = h.find('/subscription'); h.page.onUnload();
  const snapshot = JSON.stringify(h.page.data); late.success({ code: 200, data: [grant()] });
  assert.equal(JSON.stringify(h.page.data), snapshot);
});
for (const data of [null, {}, [null], [grant({ isActive: false })]]) {
  test('malformed subscription is error rather than not-opened: ' + JSON.stringify(data), t => {
    const h = harness(t); h.page.loadActiveSubs(); h.find('/subscription').success({ code: 200, data });
    assert.equal(h.page.data.subscriptionState, 'error');
  });
}
test('show under another owner clears old visible grants and payment claim, then reads only', t => {
  const h = harness(t); h.page.data.merchantAccess.canWriteProfile = true; h.open();
  h.page.data.activeSubs = [{ key: '31' }]; h.page.data.checkoutState = 'entitlementUnknown';
  h.uid = '22'; h.page._loadedOnce = true; h.page.load = () => {}; h.page.loadPerks = () => {};
  h.page.onShow();
  assert.equal(h.page.data.checkoutState, 'idle'); assert.deepEqual(h.page.data.activeSubs, []);
  assert.equal(h.page.data.capabilityState, 'loading'); assert.equal(h.page.data.subscriptionState, 'loading');
  assert.equal(h.requests.some(row => row.url.endsWith('/order')), false);
});
