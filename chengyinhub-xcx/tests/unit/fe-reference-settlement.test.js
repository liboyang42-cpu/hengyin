const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { referenceView, disputeNoteError, voucherError } = require('../../components/cy/fe-reference-settlement/view-model.js');

const ROOT = path.join(__dirname, '../..');
const componentPath = '../../components/cy/fe-reference-settlement/index.js';
const modalPath = require.resolve('../../utils/modal.js');
const toastPath = require.resolve('../../utils/toast.js');
const row = (patch) => Object.assign({ id: 1, chapterName: '示例站点', merchantName: '示例店铺', supplierKind: 'MERCHANT',
  basis: 'PERK_REDEEMED', quantity: 1, unitAmount: 12.5, amount: 12.5, status: 'OPEN', statusText: '待线下结款',
  voucherNo: null, settledAt: null }, patch);
const reply = (groups, open = 12.5, settled = 0) => ({ code: 200, data: { groups, totalOpenAmount: open, totalSettledAmount: settled } });
const group = (rows, patch) => Object.assign({ activityId: 71, activityTitle: '示例站点 · 周六场', payerName: '示例主办方',
  openAmount: 12.5, settledAmount: 0, rows }, patch);

test('按场次分组：主办方、核销人次、参考金额与状态都照服务端合同显示', () => {
  const view = referenceView(reply([group([row(), row({ id: 2, status: 'SETTLED_OFFLINE', statusText: '已线下结清',
    voucherNo: 'V-1', amount: 8, unitAmount: 8 }), row({ id: 3, status: 'VOID', statusText: '已作废' })])], 12.5, 8), 'merchant');
  const g = view.groups[0];
  assert.equal(g.title, '示例站点 · 周六场');
  assert.equal(g.metaText, '主办方：示例主办方 · 核销 2 人次');
  assert.deepEqual(view.stats.map((s) => s.value), ['¥12.50', '¥8.00']);
  assert.deepEqual(g.rows.map((r) => r.statusText), ['待线下结款', '已线下结清', '已作废']);
  assert.equal(g.rows[1].voucherNo, 'V-1');
  assert.deepEqual(g.rows.map((r) => r.canDispute), [true, true, false]);
  assert.deepEqual(g.openIds, [1]);
});

test('没有数据金额显示 ¥0.00；商家权益未填参考价显示「按约定」；作者自营不可异议也不待结', () => {
  assert.deepEqual(referenceView(reply([], 0, 0), 'merchant').stats.map((s) => s.value), ['¥0.00', '¥0.00']);
  const view = referenceView(reply([group([row({ amount: 0, unitAmount: 0 }),
    row({ id: 2, supplierKind: 'AUTHOR', amount: 0, unitAmount: 0, status: 'SETTLED_OFFLINE', statusText: '作者自营' })])], 0, 0), 'payer');
  assert.equal(view.groups[0].rows[0].amountText, '按约定');
  assert.equal(view.groups[0].rows[1].amountText, '¥0.00');
  assert.equal(view.groups[0].rows[1].statusText, '作者自营');
  assert.equal(view.groups[0].rows[1].canDispute, false);
  assert.deepEqual(view.groups[0].openIds, [1]);
  assert.equal(view.groups[0].rows[0].title, '示例站点 · 示例店铺', '收款人视角要看到是哪家店');
});

test('畸形响应整份判失败，不用零元掩盖', () => {
  for (const bad of [null, { code: 403 }, { code: 200, data: {} }, reply([group([row({ status: 'PAID' })])]),
    reply([group([row({ amount: -1 })])]), reply([group([row(), row()])]), reply([group([])]),
    { code: 200, data: { groups: [], totalOpenAmount: null, totalSettledAmount: 0 } }]) {
    assert.equal(referenceView(bad, 'merchant'), null);
  }
  assert.equal(referenceView(reply([]), 'club'), null);
  assert.equal(disputeNoteError(' '), '请写明异议原因');
  assert.equal(disputeNoteError('x'.repeat(201)), '异议原因最多 200 字');
  assert.equal(voucherError('x'.repeat(65)), '凭证号最多 64 字');
  assert.equal(voucherError(''), '');
});

function mount(kind) {
  let config; let modalOptions = null; const toasts = [];
  const requests = [];
  global.getApp = () => ({ getUserID: () => 9001, sendRequest: (r) => requests.push(r) });
  global.Component = (c) => { config = c; };
  require.cache[modalPath] = { id: modalPath, filename: modalPath, loaded: true, exports: { show: (o) => { modalOptions = o; } } };
  const toast = (t) => toasts.push(t);
  require.cache[toastPath] = { id: toastPath, filename: toastPath, loaded: true, exports: toast };
  delete require.cache[require.resolve(componentPath)]; require(componentPath);
  delete require.cache[modalPath]; delete require.cache[toastPath];
  const vm = { ...config.methods, properties: { kind }, data: JSON.parse(JSON.stringify(config.data)),
    setData(patch) { Object.assign(this.data, patch); } };
  config.lifetimes.attached.call(vm);
  return { vm, requests, toasts, modal: () => modalOptions };
}

test('收款人确认后才一次标记本场待结行，凭证号选填；成功后重新拉取', () => {
  const { vm, requests, toasts, modal } = mount('payer');
  assert.equal(requests[0].url, '/api/fe/reference-settlement/payer');
  assert.equal(requests[0].method, 'GET');
  requests[0].success(reply([group([row(), row({ id: 2, status: 'DISPUTED', statusText: '有异议' })])]));
  vm.onSettle({ currentTarget: { dataset: { key: 'activity-71' } } });
  assert.equal(requests.length, 1, '未确认前不发写请求');
  assert.equal(modal().confirmText, '标记已结清');
  modal().success({ confirm: true, content: ' V-9 ' });
  assert.equal(requests[1].url, '/api/fe/reference-settlement/settle');
  assert.equal(requests[1].method, 'POST');
  assert.deepEqual(JSON.parse(requests[1].data), { ids: [1, 2], voucherNo: 'V-9' });
  requests[1].success({ code: 200, data: { settledCount: 2 } });
  assert.deepEqual(toasts, ['已标记结清']);
  assert.equal(requests[2].url, '/api/fe/reference-settlement/payer');
});

test('店铺对本店一行提异议必须写原因；取消不发请求；收款人视角没有数据时整块不摆', () => {
  const { vm, requests, modal } = mount('merchant');
  assert.equal(requests[0].url, '/api/fe/reference-settlement/merchant');
  requests[0].success(reply([group([row({ id: 5 })])]));
  vm.onDispute({ currentTarget: { dataset: { id: 5 } } });
  modal().success({ confirm: false });
  assert.equal(requests.length, 1);
  assert.equal(modal().validate(''), '请写明异议原因');
  modal().success({ confirm: true, content: ' 没收到 ' });
  assert.deepEqual(JSON.parse(requests[1].data), { id: 5, note: '没收到' });
  vm.onDispute({ currentTarget: { dataset: { id: 999 } } });
  assert.equal(requests.length, 2, '不是本店可异议的行不发请求');
  const wxml = fs.readFileSync(path.join(ROOT, 'components/cy/fe-reference-settlement/index.wxml'), 'utf8');
  assert.match(wxml, /自由探索结算（参考）/);
  assert.match(wxml, /kind === 'payer' && state === 'ready' && view && !view\.groups\.length/);
});

test('店铺流水与俱乐部结算页挂上参考结算；权益卡表单显示服务端下发的线下结算说明', () => {
  const ledger = fs.readFileSync(path.join(ROOT, 'pages/merchant/ledger/index.wxml'), 'utf8');
  assert.match(ledger, /<cy-fe-reference-settlement wx:if="\{\{merchantAccess\.canReadFinance\}\}" kind="merchant" \/>/);
  assert.match(ledger, /<cy-fe-reference-settlement kind="payer" \/>/);
  assert.match(fs.readFileSync(path.join(ROOT, 'pages/club/settlement/index.wxml'), 'utf8'), /<cy-fe-reference-settlement[^>]*kind="payer"/);
  const form = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-node-form/index.wxml'), 'utf8');
  assert.match(form, /wx:if="\{\{settlementNote\}\}">\{\{settlementNote\}\}/);
  const formJs = fs.readFileSync(path.join(ROOT, 'pages/topic/components/cy/chapter-node-form/index.js'), 'utf8');
  assert.match(formJs, /url: '\/api\/fe\/reference-settlement\/note'/);
});
