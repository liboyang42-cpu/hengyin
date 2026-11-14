// E-B7/E-B8/E-B10:自玩下单页的支付未知恢复、资料取失败、重建标记复位(行为测试)。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');

const PAGE_PATH = path.resolve(__dirname, '../../pages/topic/index/index.js');

function loadTopicPage() {
  const requests = [];
  const toasts = [];
  const navigations = [];
  const payments = [];
  const subscriptions = [];
  let pageConfig = null;

  // wx 在方法调用时才查全局，所以它必须留到测试跑完；getApp/Page 只在 require 期间需要。
  const previous = {
    getApp: global.getApp,
    Page: global.Page
  };

  const consents = [];
  const modals = [];
  const modalControl = { confirm: false };
  const consentControl = { reject: false };
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserType: () => 1,
    getUserID: () => 500,
    tips: message => toasts.push(message),
    sendRequest: options => { requests.push(options); },
    recordConsent: input => {
      consents.push(input);
      return consentControl.reject ? Promise.reject(new Error('consent down')) : Promise.resolve({ code: 200 });
    }
  });
  global.Page = config => { pageConfig = config; };
  global.wx = {
    showLoading: () => {},
    hideLoading: () => {},
    showToast: options => toasts.push(options.title),
    showModal: options => {
      modals.push(options);
      if (options.success) options.success({ confirm: modalControl.confirm });
    },
    requestPayment: options => { payments.push(options); },
    requestSubscribeMessage: options => { subscriptions.push(options); },
    navigateTo: options => { navigations.push(options.url); },
    redirectTo: options => { navigations.push(options.url); },
    getSystemInfo: () => {},
    getStorageSync: () => '',
    setStorageSync: () => {},
    createSelectorQuery: () => { const q = { in: () => q, select: () => q, selectAll: () => q, boundingClientRect: () => q, scrollOffset: () => q, exec: () => {} }; return q; }
  };

  delete require.cache[PAGE_PATH];
  try {
    require(PAGE_PATH);
  } finally {
    global.getApp = previous.getApp;
    global.Page = previous.Page;
  }

  const vm = Object.assign({}, pageConfig.data, pageConfig.methods || {}, pageConfig, {
    setData(patch) { Object.assign(this.data, patch); }
  });
  vm.data = Object.assign({}, pageConfig.data);
  return { vm, requests, toasts, navigations, payments, consents, consentControl, modals, modalControl, subscriptions };
}

// 确保每个 page 加载后 workflow 已初始化，否则 _createSelfPlayOrder 里的 workflow.submit 会报错
function loadTopicPageWithWorkflow() {
  const ctx = loadTopicPage();
  if (ctx.vm._initWorkflow) ctx.vm._initWorkflow.call(ctx.vm);
  // RUN-41：建单要带真实姓名+手机号（后端 RegistrationRequest 上 realName/phone 是 @NotBlank）。
  // 幂等/同意这些用例关心的是「发几次单、什么时候发」，资料是否齐不该干扰它们，所以预置成齐。
  // 缓存走实例字段 _signupProfile 而不是 data —— 本页 wxml 不渲染它（U4 A2 会判红）。
  ctx.vm._signupProfile = { realName: '张三', phone: '13800001111' };
  return ctx;
}


function paidOrder() {
  return { registrationId: 1001, payableAmount: 100,
    payParams: { timeStamp: '1720000000', nonceStr: 'nonce', package: 'prepay_id=x', signType: 'RSA', paySign: 'signature' } };
}

function pageWithWorkflow() {
  const ctx = loadTopicPage();
  ctx.vm._initWorkflow.call(ctx.vm);
  ctx.vm._signupProfile = { realName: '张三', phone: '13800001111' };
  ctx.vm._later = (ms, fn) => { (ctx.timers = ctx.timers || []).push({ ms, fn }); };
  return ctx;
}

test('E-B8 资料请求失败 ≠ 资料为空:不弹「还差姓名和手机号」、不跳资料页', () => {
  const ctx = pageWithWorkflow();
  const { vm, requests, modals, navigations, toasts } = ctx;
  vm._signupProfile = null;
  vm._createSelfPlayOrder.call(vm, 500);
  // 两路资料请求:一路网络失败,一路成功但为空
  const info = requests.find((r) => r.url === '/api/user/info');
  const addr = requests.find((r) => r.url === '/api/user/address/list');
  info.fail();
  addr.success({ code: '200', data: { rows: [] } });
  assert.equal(modals.length, 0, '网络失败不能当成资料为空');
  assert.deepEqual(navigations, []);
  assert.ok(toasts.length >= 1);
  assert.equal(requests.filter((r) => r.url === '/api/registration/create').length, 0);
});

test('E-B8 资料真的为空才提示补全', () => {
  const ctx = pageWithWorkflow();
  const { vm, requests, modals } = ctx;
  vm._signupProfile = null;
  vm._createSelfPlayOrder.call(vm, 500);
  requests.find((r) => r.url === '/api/user/info').success({ code: '200', data: {} });
  requests.find((r) => r.url === '/api/user/address/list').success({ code: '200', data: { rows: [] } });
  assert.equal(modals.length, 1);
  assert.match(modals[0].title, /还差姓名和手机号/);
});

test('E-B7 支付结果未知:弹「刷新订单状态」,刷新确认已付 → 进入通行证到账(_onSelfPlayPaid)', () => {
  const { createCheckoutWorkflow } = require('../../utils/checkout/checkout-workflow.js');
  const ctx = pageWithWorkflow();
  const { vm, modals } = ctx;
  const verifies = [];
  vm._workflow = createCheckoutWorkflow({
    createOrder: (payload, cb) => { cb({ ok: true, data: paidOrder() }); },
    requestPayment: (data, cb) => { cb({ ok: true }); },
    verifyPayment: (data, cb) => { verifies.push(cb); },
  });
  const paid = [];
  vm._onSelfPlayPaid = (topicId, data) => paid.push([topicId, data]);
  vm._createSelfPlayOrder.call(vm, 500);
  verifies[0]({ ok: false, status: 'unknown' });
  const unknownModal = modals.find((m) => /支付结果待确认/.test(m.title));
  assert.ok(unknownModal, '应弹出支付结果待确认: ' + JSON.stringify(modals.map((m) => m.title)));
  assert.equal(unknownModal.confirmText, '刷新订单状态');
  assert.equal(vm._workflow.getState(), 'unknown');
  assert.equal(paid.length, 0);
  // 轮询已排上(不重复弹窗):仍不明时静默
  assert.ok(ctx.timers.some((t) => t.ms === 5000));
  vm._recheckPayment();
  verifies[1]({ ok: false, status: 'unknown' });
  assert.equal(modals.filter((m) => /支付结果待确认/.test(m.title)).length, 1, '刷新仍不明不重复弹窗');
  // 刷新:服务端确认已付
  vm._recheckPayment();
  verifies[2]({ ok: true, status: 'success', data: {} });
  assert.equal(paid.length, 1, '确认已付必须走 _onSelfPlayPaid');
  assert.equal(vm._workflow.getState(), 'done');
});

test('E-B10 每次购买意图复位自动重建标记;死代码 _retrySelfPlayPayment 已删', () => {
  const ctx = pageWithWorkflow();
  const { vm } = ctx;
  vm._createSelfPlayOrder = () => {};
  vm._rebuildUsed = true;
  vm._rebuildSelfPlayOrderWithNewKey(500);
  assert.equal(vm._rebuildUsed, true);
  assert.equal(vm._rebuildSelfPlayOrderWithNewKey(500), false, '同一意图只自动重建一次');
  vm.data.selfPlayConsentChecked = true;
  vm._hostShareConsentReady = true;
  vm._selfPlayTopicId = 500;
  vm.data.selfPlayCheckoutShow = false;
  vm.onSelfPlayConfirm();
  assert.equal(vm._rebuildUsed, false, '新的购买意图复位');
  assert.equal(typeof vm._retrySelfPlayPayment, 'undefined');
});

test('E-B9 报价失败时点击支付不建单', () => {
  const ctx = pageWithWorkflow();
  const { vm, requests } = ctx;
  vm.data.selfPlayCheckoutShow = true;
  vm.data.selfPlayConsentChecked = true;
  vm._hostShareConsentReady = true;
  vm._selfPlayTopicId = 500;
  vm._checkoutFacts = { info: { id: 500 }, quoteState: 'failed' };
  vm.onSelfPlayConfirm();
  assert.equal(requests.filter((r) => r.url === '/api/registration/create').length, 0);
});

test('E-B9 重新获取价格:只重取报价,状态回 loading', () => {
  const ctx = pageWithWorkflow();
  const { vm, requests } = ctx;
  vm._checkoutFacts = { info: { id: 500 }, quoteState: 'failed' };
  vm.data.checkout = {};
  vm.onCheckoutRequote();
  assert.equal(vm._checkoutFacts.quoteState, 'loading');
  assert.ok(requests.some((r) => r.url === '/api/registration/quote'));
});

test('结算页开着时 toast 抬到底栏之上(支付失败提示不再盖住同意行)', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/topic/index/index.wxml'), 'utf8');
  assert.match(wxml, /<cy-toast id="cy-toast" lift="\{\{selfPlayCheckoutShow\}\}"/);
});
