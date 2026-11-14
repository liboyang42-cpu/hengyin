// 自玩票虚拟支付客户端:服务端决定渠道;不支持的设备如实说明、不改走微信支付;支付成功回调不算已付,须服务端回查。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const x = require('../../utils/checkout/selfplay-xpay-pay.js');

const VP = { mode: 'short_series_goods', signData: '{}', paySig: 'p', signature: 's' };

function fakeApp(handlers) {
  const calls = [];
  return {
    calls,
    sendRequest(opts) {
      calls.push(opts);
      const h = handlers[opts.url];
      if (h) h(opts); else if (opts.fail) opts.fail();
    }
  };
}

test('渠道判定只认服务端 payChannel,未签名时需要签名', () => {
  assert.equal(x.isIntent({ payChannel: 'XPAY' }), true);
  assert.equal(x.isIntent({ payParams: { package: 'prepay_id=1' } }), false);
  assert.equal(x.needsSigning({ payChannel: 'XPAY', jsCodeRequired: true }), true);
  assert.equal(x.needsSigning({ payChannel: 'XPAY', virtualPayment: VP }), false);
  assert.equal(x.hasVirtualPayment({ virtualPayment: { mode: 'm' } }), false);
});

test('签发:wx.login 后带 jsCode 调 /pay,返回虚拟支付参数', () => {
  const app = fakeApp({ '/api/registration/pay': (o) => o.success({ code: '200', data: { registrationId: 9, payChannel: 'XPAY', virtualPayment: VP } }) });
  const wxApi = { login: (o) => o.success({ code: 'c1' }) };
  let out;
  x.signIntent(app, wxApi, 9, (r) => { out = r; });
  assert.equal(out.ok, true);
  assert.deepEqual(app.calls[0].data, { id: 9, jsCode: 'c1' });
});

test('签发:过期单(410)标记 expired 交给页面重建,其余失败带后端原文', () => {
  const wxApi = { login: (o) => o.success({ code: 'c1' }) };
  let out;
  x.signIntent(fakeApp({ '/api/registration/pay': (o) => o.success({ code: 410, msg: '订单已过期' }) }), wxApi, 9, (r) => { out = r; });
  assert.equal(out.ok, false);
  assert.equal(out.expired, true);
  x.signIntent(fakeApp({ '/api/registration/pay': (o) => o.success({ code: 409, msg: '订单当前不可支付' }) }), wxApi, 9, (r) => { out = r; });
  assert.equal(out.expired, false);
  assert.equal(out.msg, '订单当前不可支付');
});

test('设备不支持 requestVirtualPayment:说明原因,不调用微信支付、不发任何请求', () => {
  const app = fakeApp({});
  let paymentCalled = false;
  const wxApi = { requestPayment: () => { paymentCalled = true; } };
  let out;
  x.pay(app, wxApi, { registrationId: 9, virtualPayment: VP }, (r) => { out = r; });
  assert.equal(out.ok, false);
  assert.equal(out.unsupported, true);
  assert.equal(out.errMsg, x.UNSUPPORTED_MESSAGE);
  assert.equal(paymentCalled, false);
  assert.equal(app.calls.length, 0);
});

test('取消不回查;非取消失败先回查,服务端已付按成功,未付按失败', () => {
  const paid = { code: '200', data: { paymentStatus: 2, registrationStatus: 2 } };
  const unpaid = { code: '200', data: { paymentStatus: 0, registrationStatus: 1 } };
  const make = (err) => ({ requestVirtualPayment: (o) => o.fail(err) });
  let out;
  const app1 = fakeApp({});
  x.pay(app1, make({ errCode: -2, errMsg: 'requestVirtualPayment:fail cancel' }), { registrationId: 9, virtualPayment: VP }, (r) => { out = r; });
  assert.equal(out.cancelled, true);
  assert.equal(app1.calls.length, 0);
  x.pay(fakeApp({ '/api/registration/xpay/confirm': (o) => o.success(paid) }), make({ errCode: 1, errMsg: 'x' }), { registrationId: 9, virtualPayment: VP }, (r) => { out = r; });
  assert.equal(out.ok, true);
  x.pay(fakeApp({ '/api/registration/xpay/confirm': (o) => o.success(unpaid) }), make({ errCode: 1, errMsg: 'x' }), { registrationId: 9, virtualPayment: VP }, (r) => { out = r; });
  assert.equal(out.ok, false);
});

test('SDK 成功回调不算已付:核账前一定先让服务端回查,再走订单状态核账', () => {
  const app = fakeApp({ '/api/registration/xpay/confirm': (o) => o.success({ code: '200', data: { paymentStatus: 2, registrationStatus: 2 } }) });
  const order = [];
  const verifier = (ref, cb) => { order.push('verify'); cb({ ok: true }); return { abort() {} }; };
  const origSend = app.sendRequest;
  app.sendRequest = (o) => { order.push(o.url); origSend.call(app, o); };
  let out;
  x.verify(app, verifier, { registrationId: 9 }, (r) => { out = r; });
  assert.deepEqual(order, ['/api/registration/xpay/confirm', 'verify']);
  assert.equal(out.ok, true);
});

test('核账控制柄 abort 后不再启动订单状态核账', () => {
  let confirmCb;
  const app = { sendRequest(o) { confirmCb = o.success; } };
  let started = false;
  const handle = x.verify(app, () => { started = true; return null; }, { registrationId: 9 }, () => {});
  handle.abort();
  confirmCb({ code: '200', data: {} });
  assert.equal(started, false);
});
