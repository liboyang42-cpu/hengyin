'use strict';
// L21 · 改动清单 B9 退款被拒订阅:utils/subscribe.js 定义了 refundRejected 却没人申请。
// 报名时已占满 3 个模板(baoming.js),所以改在「确认退款」这一下申请 ['refund','refundRejected']。
// 微信订阅规则:只能在用户点击链里同步发起 —— 这里断言在确认回调的同步段内、退款请求之前申请;
// 不点确认 / 零元单(没钱可退)/ 未付单的普通取消一律不弹。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

const root = path.resolve(__dirname, '../..');
const entries = [
  ['detail', 'subpackageMember/components/cy/scene-member-order-detail/index.js', 'cancelRegistration'],
  ['history', 'subpackageMember/components/cy/scene-member-order-history/index.js', 'cancelOrder'],
  ['participation', 'subpackageMember/components/scene-member-participation-detail/index.js', 'cancelParticipation'],
];

function mount(file, order) {
  let definition;
  let gesture = false;
  const log = [];
  const prompts = [];
  const app = { globalData: {}, getUserID: () => '11', getPageSize: () => 10,
    getRequestErrorMessage: (_r, fallback) => fallback,
    sendRequest: (request) => log.push(['request', request.url]) };
  const toast = () => {}; toast.success = toast; toast.error = toast;
  const localRequire = createRequire(path.join(root, file));
  vm.runInNewContext(fs.readFileSync(path.join(root, file), 'utf8'), {
    getApp: () => app, wx: { getStorageSync: () => '', setNavigationBarColor() {} },
    Component: (config) => { definition = config; },
    require(name) {
      if (name.endsWith('/subscribe.js')) return { request(keys) {
        assert.equal(gesture, true, '订阅必须在确认手势的同步段内发起');
        log.push(['subscribe', Array.from(keys)]);
        return Promise.resolve({ status: 'accepted' });
      } };
      if (name.endsWith('/modal.js')) return { show: (options) => prompts.push(options) };
      if (name.endsWith('/toast.js')) return toast;
      if (name.endsWith('/loading.js')) return { show() {}, hide() {} };
      if (name.endsWith('/merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} };
      return localRequire(name);
    },
    console: { warn() {}, log() {}, error() {} }, setTimeout, clearTimeout,
  }, { filename: file });
  const page = Object.assign({}, definition.methods);
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = (patch) => Object.assign(page.data, patch);
  page.triggerEvent = () => {};
  page._orderId = '7'; page._orderGeneration = 1; page._attached = true;
  page.data.id = 7;
  page.data.info = Object.assign({ id: 7, canRequestRefund: true }, order);
  page.data.list = [Object.assign({ id: 7 }, order)];
  return {
    log,
    tap(handler) {
      const status = Number(order.paymentStatus) === 2 ? 2 : 0;
      page[handler]({ currentTarget: { dataset: { id: 7, index: 0, status } } });
    },
    answer(confirm) { gesture = true; prompts.at(-1).success({ confirm, cancel: !confirm }); gesture = false; },
  };
}

const PAID = { paymentStatus: 2, totalAmount: 30, payableAmount: 30 };
const FREE = { paymentStatus: 2, totalAmount: 0, payableAmount: 0, isFreeOrder: true };
const UNPAID = { paymentStatus: 0, totalAmount: 30, payableAmount: 30 };

for (const [label, file, handler] of entries) {
  test(`B9 ${label}:已付单确认退款 → 先申请 refund+refundRejected,再发退款请求`, () => {
    const h = mount(file, PAID);
    h.tap(handler);
    assert.deepEqual(h.log, [], '点开确认框不申请');
    h.answer(true);
    assert.deepEqual(h.log, [['subscribe', ['refund', 'refundRejected']], ['request', '/api/registration/cancel-refund']]);
  });

  test(`B9 ${label}:点「再想想」不申请也不发`, () => {
    const h = mount(file, PAID);
    h.tap(handler);
    h.answer(false);
    assert.deepEqual(h.log, []);
  });

  test(`B9 ${label}:零元单没有钱可退,不弹退款订阅`, () => {
    const h = mount(file, FREE);
    h.tap(handler);
    h.answer(true);
    assert.equal(h.log.filter(([kind]) => kind === 'subscribe').length, 0);
    assert.equal(h.log.filter(([kind]) => kind === 'request').length, 1);
  });

  test(`B9 ${label}:未付单的普通取消不弹退款订阅`, () => {
    const h = mount(file, UNPAID);
    h.tap(handler);
    h.answer(true);
    assert.deepEqual(h.log, [['request', '/api/registration/cancel']]);
  });
}

test('B9 refundRejected 模板已配置,且全仓只在这三处确认退款时申请(不在页面加载等非点击时机)', () => {
  const subscribe = require('../../utils/subscribe.js');
  assert.equal(subscribe.resolveTemplateIds(['refund', 'refundRejected']).length, 2);
  const users = entries.map(([, file]) => fs.readFileSync(path.join(root, file), 'utf8'));
  users.forEach((src) => assert.equal((src.match(/\.request\(\['refund', 'refundRejected'\]\)/g) || []).length, 1));
});
