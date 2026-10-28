const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
// X5：抬头保存从 W8 单页移到「新增 / 编辑抬头」页；原请求号与完整税号只留内存的规则不变。
const PAGE = path.join(ROOT, 'subpackageMember/invoice-title-edit/index.js');
const LIST = path.join(ROOT, 'subpackageMember/invoice-titles/index.js');
const TITLE = '9007199254740993', ORDER = '9223372036854775807', APP = '9007199254740995';
const json = value => JSON.parse(JSON.stringify(value));
const tap = (id, extra = {}) => ({ currentTarget: { dataset: { id, ...extra } } });
const title = (extra = {}) => ({ id: TITLE, titleType: 'COMPANY', titleName: '发票测试单位', taxNoMasked: '91******1234', isDefault: true, ...extra });
const order = (extra = {}) => ({ orderId: ORDER, amount: '12.34', orderTitle: '真实订单', ticketName: '门票', ...extra });
const application = (requestId, extra = {}) => ({ id: APP, requestId, applicationNo: 'INV202610260001', status: 'SUBMITTED',
  titleName: '发票测试单位', taxNoMasked: '91******1234', totalAmount: '12.34', orderCount: 1, orders: [order()], ...extra });

// Load the actual Page and its actual workflow, storage and view helpers in one runtime.
function mount(values = new Map(), initialOwner = '7', file = PAGE) {
  let definition, owner = initialOwner, authorization = owner ? 'session-' + owner : '', timerId = 0;
  const requests = [], dialogs = [], navigations = [], timers = new Map(), cache = new Map();
  const app = {
    getUserID: () => owner, getAuthorization: () => authorization,
    sendRequest(request) {
      const row = { ...request, path: request.url.replace('/api/invoice/', ''), body: JSON.parse(request.data || '{}'), aborted: false };
      row.abort = () => { row.aborted = true; };
      requests.push(row); return row;
    },
    firstLogin: () => { owner = '7'; authorization = 'session-7'; return Promise.resolve({ ok: true }); },
    reLogin: () => Promise.resolve({ ok: true }),
  };
  const wx = {
    getStorageSync: key => values.has(key) ? json(values.get(key)) : '',
    setStorageSync: (key, value) => values.set(key, json(value)),
    removeStorageSync: key => values.delete(key),
    showModal: options => dialogs.push(options), showToast: () => {}, navigateBack: options => navigations.push(options), switchTab: options => navigations.push(options),
  };
  let page;
  const context = vm.createContext({ getApp: () => app, wx, Page: value => { definition = value; },
    getCurrentPages: () => page ? [page] : [],
    setTimeout: callback => { timers.set(++timerId, callback); return timerId; }, clearTimeout: id => timers.delete(id), console });
  function load(file) {
    file = path.resolve(file);
    if (cache.has(file)) return cache.get(file).exports;
    const mod = { exports: {} }; cache.set(file, mod);
    const wrapper = vm.runInContext('(function(module,exports,require){' + fs.readFileSync(file, 'utf8') + '\n})', context, { filename: file });
    wrapper(mod, mod.exports, name => load(path.resolve(path.dirname(file), name)));
    return mod.exports;
  }
  load(file);
  page = Object.assign({}, definition, { data: json(definition.data), setData(patch) { Object.assign(this.data, patch); } });
  function latest(route) { const row = requests.filter(r => r.path === route).at(-1); assert.ok(row, route + ' was requested'); return row; }
  function respond(route, data, extra = {}) {
    // listApplications does not fetch order lines; only detail/apply/cancel carry them.
    if (route === 'list' && Array.isArray(data)) data = data.map(row => ({ ...row, orders: [] }));
    latest(route).success({ code: 200, data, ...extra });
  }
  const h = { page, values, wx, requests, dialogs, navigations, latest, respond, app,
    owner(value) { owner = value; authorization = value ? 'session-' + value : ''; },
    confirm() { assert.ok(dialogs.length); dialogs.at(-1).success({ confirm: true }); },
    deadline() { assert.ok(timers.size); [...timers.values()].forEach(callback => callback()); },
    count(route) { return requests.filter(row => row.path === route).length; },
    boot() { page.onLoad({ id: TITLE }); page.onShow(); },
    seed() { respond('titles', [title()]); },
  };
  return h;
}

function begin(h) {
  h.boot(); h.seed();
  h.page.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } });
  h.page.saveTitle(); return h.latest('titles/save-request');
}
const receipt = requestId => ({ requestId, titleId: TITLE, status: 'SAVED' });

test('title receipt freezes the complete original payload and retries the same request after timeout', () => {
  const h = mount(); const first = begin(h), body = json(first.body);
  assert.match(body.requestId, /^vr_/);
  first.fail({ errMsg: 'timeout' });
  assert.equal(h.page.data.titlePending, true);
  h.page.input({ ...tap('', { field: 'taxNo' }), detail: { value: 'DIFFERENT' } });
  h.page.titleType(tap('', { type: 'PERSONAL' }));
  h.page.retryTitleSave(); const second = h.latest('titles/save-request');
  assert.deepEqual(second.body, body);
  h.page.retryTitleSave(); assert.equal(h.count('titles/save-request'), 2);
  second.success({ code: 200, data: receipt(body.requestId) });
  assert.equal(h.page.data.titlePending, false); assert.equal(h.page.data.writing, false);
  assert.equal(h.page._titleIntent, null); assert.equal(h.values.size, 0);
  assert.equal(JSON.stringify([...h.values]).includes(body.taxNo), false);
});

test('title receipt restores only original identity after remount and never trusts a negative or wrong receipt', () => {
  const h = mount(); const first = begin(h), requestId = first.body.requestId;
  first.fail({ errMsg: 'timeout' }); h.page.onHide();
  assert.equal(h.page._titleIntent, null);
  assert.equal(JSON.stringify([...h.values]).includes(first.body.taxNo), false);
  assert.equal(JSON.stringify([...h.values]).includes(first.body.titleName), false);
  const next = mount(h.values); next.boot(); next.seed();
  assert.equal(next.page.data.titlePending, true);
  next.page.queryTitleSave();
  assert.deepEqual(next.latest('titles/request').body, { requestId });
  next.latest('titles/request').success({ code: 500, errorCode: 'INVOICE_NOT_FOUND' });
  assert.equal(next.count('titles/save-request'), 0, 'no original input left in memory, so nothing is resent');
  next.page.retryTitleSave(); next.page.saveTitle(); assert.equal(next.count('titles/save-request'), 0);
  assert.equal(next.page.data.titlePending, true);
  next.page.queryTitleSave(); next.respond('titles/request', receipt('wrong-request'));
  assert.equal(next.page.data.titlePending, true);
  next.page.queryTitleSave(); next.respond('titles/request', receipt(requestId));
  assert.equal(next.page.data.titlePending, false); assert.equal(next.values.size, 0);
});

test('same-request retry deadline, conflict and stale account callbacks cannot clear UNKNOWN', () => {
  const h = mount(); const first = begin(h); first.fail({ errMsg: 'timeout' });
  h.page.retryTitleSave(); const late = h.latest('titles/save-request'); h.deadline();
  assert.equal(h.page.data.writing, false); assert.equal(h.page.data.titlePending, true);
  late.success({ code: 200, data: receipt(first.body.requestId) });
  assert.equal(h.page.data.titlePending, true);
  h.page.retryTitleSave(); h.latest('titles/save-request').success({ code: 500, errorCode: 'INVOICE_REQUEST_REUSED' });
  assert.equal(h.page.data.titlePending, true);
  h.page.retryTitleSave(); const old = h.latest('titles/save-request'); h.owner('8');
  old.success({ code: 200, data: receipt(first.body.requestId) });
  assert.equal(h.page.data.formReady, false); assert.equal(h.page._titleIntent, null);
  h.page.onHide(); h.owner('7'); h.page.onShow(); h.seed();
  assert.equal(h.page.data.titlePending, true);
  assert.equal(h.page.data.titleRequestId, first.body.requestId);
});

test('initial save accepts only its original receipt and never treats another title list as proof', () => {
  const h = mount(); const first = begin(h);
  first.success({ code: 200, data: receipt('wrong-request') });
  assert.equal(h.page.data.titlePending, true);
  const list = mount(h.values, '7', LIST); list.boot(); list.respond('titles', [title()]);
  assert.equal(list.page.data.titlePending, true);
  assert.equal(h.page.data.titlePending, true);
  h.page.queryTitleSave(); h.respond('titles/request', receipt(first.body.requestId));
  assert.equal(h.page.data.titlePending, false);
});

test('wrong target, feature-off retry and a late retry after readback do not replace the original result', () => {
  const h = mount(); const first = begin(h), id = first.body.requestId;
  first.success({ code: 200, data: { ...receipt(id), titleId: '99' } });
  assert.equal(h.page.data.titlePending, true);
  h.page.queryTitleSave(); const query = h.latest('titles/request');
  h.page.retryTitleSave(); const retry = h.latest('titles/save-request');
  query.success({ code: 200, data: receipt(id) });
  assert.equal(h.page.data.titlePending, false); assert.equal(h.page.data.writing, false);
  const before = json(h.page.data);
  retry.success({ code: 200, data: receipt(id) }); retry.fail({ errMsg: 'late' });
  assert.deepEqual(json(h.page.data), before);
  const off = mount(); const write = begin(off); write.fail({ errMsg: 'timeout' });
  off.page.retryTitleSave(); off.latest('titles/save-request').success({ code: 500, errorCode: 'INVOICE_FEATURE_OFF' });
  assert.equal(off.page.data.unavailable, true); assert.equal(off.page._titleIntent, null);
  assert.ok(off.values.size);
});

test('missing new backend endpoint never falls back to the legacy non-idempotent title writer', () => {
  const h = mount(); const first = begin(h);
  assert.equal(first.url, '/api/invoice/titles/save-request');
  first.successStatusAbnormal({ msg: 'not deployed' }, 404);
  h.page.retryTitleSave();
  assert.deepEqual(h.latest('titles/save-request').body, first.body);
  h.latest('titles/save-request').success({ code: 200, data: title() });
  assert.equal(h.page.data.titlePending, true, 'legacy title-shaped response is not a command receipt');
  assert.equal(h.count('titles/save'), 0);
});
