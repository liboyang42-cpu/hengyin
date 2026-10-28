const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
// X5（10-28）：W8 单页拆成 5 页，各页共用 subpackageMember/invoice/base.js 的读写与原请求号恢复。
const PAGES = {
  list: 'subpackageMember/invoice/index',
  detail: 'subpackageMember/invoice-detail/index',
  apply: 'subpackageMember/invoice-apply/index',
  titles: 'subpackageMember/invoice-titles/index',
  edit: 'subpackageMember/invoice-title-edit/index',
};
const TITLE = '9007199254740993', ORDER = '9223372036854775807', APP = '9007199254740995', ISSUER = '31', OTHER = '32';
const json = value => JSON.parse(JSON.stringify(value));
const tap = (id, extra = {}) => ({ currentTarget: { dataset: { id, ...extra } } });
const title = (extra = {}) => ({ id: TITLE, titleType: 'COMPANY', titleName: '发票测试单位', taxNoMasked: '91******1234', isDefault: true, ...extra });
const order = (extra = {}) => ({ orderId: ORDER, amount: '12.34', orderTitle: '真实订单', ticketName: '门票', issuerId: ISSUER, issuerName: '开票单位一', ...extra });
const application = (requestId, extra = {}) => ({ id: APP, requestId, applicationNo: 'INV202610260001', status: 'SUBMITTED', titleType: 'COMPANY',
  titleName: '发票测试单位', taxNoMasked: '91******1234', totalAmount: '12.34', orderCount: 1, orders: [order()], ...extra });

// Load the actual Page and its actual workflow, storage and view helpers in one runtime.
// All five pages share one storage map, so a pending request written by one page is visible to the others.
function mount(name, options = {}, values = new Map(), initialOwner = '7') {
  let definition, owner = initialOwner, authorization = owner ? 'session-' + owner : '', timerId = 0;
  const requests = [], dialogs = [], navigations = [], toasts = [], timers = new Map(), cache = new Map();
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
  const nav = kind => options => navigations.push({ kind, ...options });
  const wx = {
    getStorageSync: key => values.has(key) ? json(values.get(key)) : '',
    setStorageSync: (key, value) => values.set(key, json(value)),
    removeStorageSync: key => values.delete(key),
    showModal: options => dialogs.push(options), showToast: options => toasts.push(options.title),
    navigateBack: nav('back'), switchTab: nav('tab'), navigateTo: nav('to'), redirectTo: nav('redirect'),
  };
  let page;
  const stack = [];
  const context = vm.createContext({ getApp: () => app, wx, Page: value => { definition = value; },
    getCurrentPages: () => stack.concat(page ? [page] : []),
    setTimeout: callback => { timers.set(++timerId, callback); return timerId; }, clearTimeout: id => timers.delete(id), console });
  function load(file) {
    file = path.resolve(file);
    if (cache.has(file)) return cache.get(file).exports;
    const mod = { exports: {} }; cache.set(file, mod);
    const wrapper = vm.runInContext('(function(module,exports,require){' + fs.readFileSync(file, 'utf8') + '\n})', context, { filename: file });
    wrapper(mod, mod.exports, name => load(path.resolve(path.dirname(file), name)));
    return mod.exports;
  }
  load(path.join(ROOT, PAGES[name] + '.js'));
  page = Object.assign({}, definition, { route: PAGES[name], data: json(definition.data), setData(patch) { Object.assign(this.data, patch); } });
  function latest(route) { const row = requests.filter(r => r.path === route).at(-1); assert.ok(row, route + ' was requested'); return row; }
  function respond(route, data, extra = {}) {
    // listApplications does not fetch order lines; only detail/apply/cancel carry them.
    if (route === 'list' && Array.isArray(data)) data = data.map(row => ({ ...row, orders: [] }));
    latest(route).success({ code: 200, data, ...extra });
  }
  return { page, values, wx, app, requests, dialogs, navigations, toasts, stack, latest, respond,
    owner(value) { owner = value; authorization = value ? 'session-' + value : ''; },
    confirm() { assert.ok(dialogs.length); dialogs.at(-1).success({ confirm: true }); },
    deadline() { assert.ok(timers.size); [...timers.values()].forEach(callback => callback()); },
    count(route) { return requests.filter(row => row.path === route).length; },
    boot() { page.onLoad(options); page.onShow(); },
  };
}
// 申请开票页：抬头 + 可开票订单都回来后选中唯一一笔。
function applyPage(values, orders = [order()]) {
  const h = mount('apply', {}, values); h.boot();
  h.respond('titles', [title()]); h.respond('orders/eligible', orders);
  return h;
}
function submitted(h) {
  h.page.pick(tap(ORDER)); h.page.apply();
  return h.latest('apply');
}

test('five pages drive all real JSON endpoints: titles, save, delete, eligible, apply, list, detail and cancel', () => {
  const values = new Map(), paths = new Set();
  const edit = mount('edit', { id: TITLE }, values); edit.boot(); edit.respond('titles', [title()]);
  assert.equal(edit.page.data.draft.taxNo, '');
  edit.page.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } });
  edit.page.saveTitle();
  const saveRequestId = edit.latest('titles/save-request').body.requestId;
  assert.match(saveRequestId, /^vr_/);
  assert.deepEqual(edit.latest('titles/save-request').body, { id: TITLE, titleType: 'COMPANY', titleName: '发票测试单位', taxNo: '913100001234567890', isDefault: true, requestId: saveRequestId });
  edit.respond('titles/save-request', { requestId: saveRequestId, titleId: TITLE, status: 'SAVED' });
  assert.equal(edit.navigations.length, 0, 'single-page stack stays put');
  assert.equal(JSON.stringify([...values]).includes('913100001234567890'), false);
  edit.requests.forEach(row => paths.add(row.path));

  const apply = applyPage(values); const request = submitted(apply), requestId = request.body.requestId;
  assert.deepEqual(request.body, { titleId: TITLE, orderIds: [ORDER], requestId });
  assert.ok([...values.values()].some(row => row.attemptId === requestId));
  request.success({ code: 200, data: application(requestId, { replayed: false }) });
  assert.equal(apply.page.data.pending, false);
  assert.deepEqual(apply.navigations.at(-1), { kind: 'redirect', url: '/subpackageMember/invoice/index' });
  apply.requests.forEach(row => paths.add(row.path));

  const list = mount('list', {}, values); list.boot(); list.respond('list', [application(requestId)]);
  assert.equal(list.page.data.rows[0].id, APP);
  assert.equal(list.page.data.rows[0].playerStatus, '开具中');
  list.page.cancel(tap(APP)); list.confirm();
  assert.deepEqual(list.latest('cancel').body, { id: APP });
  list.respond('cancel', application(requestId, { status: 'CANCELLED' }));
  list.respond('list', [application(requestId, { status: 'CANCELLED' })]);
  assert.equal(list.page.data.rows[0].playerStatus, '已撤回');
  list.requests.forEach(row => paths.add(row.path));

  const detail = mount('detail', { id: APP }, values); detail.boot();
  assert.deepEqual(detail.latest('detail').body, { id: APP });
  detail.respond('detail', application(requestId, { status: 'ISSUED', invoiceNo: '26312000000012345678', issuedAt: '2026-10-29 16:20:00' }));
  assert.equal(detail.page.data.detail.statusKey, 'issued');
  assert.equal(detail.page.data.detail.issuedText, '10月29日 16:20');
  detail.requests.forEach(row => paths.add(row.path));

  const titles = mount('titles', {}, values); titles.boot(); titles.respond('titles', [title()]);
  assert.equal(titles.page.data.titles[0].line, '单位 · 91******1234 · 默认');
  titles.page.remove(tap(TITLE)); titles.confirm();
  assert.deepEqual(titles.latest('titles/delete').body, { id: TITLE });
  titles.respond('titles/delete', null); titles.respond('titles', []);
  assert.equal(titles.page.data.titles.length, 0);
  titles.requests.forEach(row => paths.add(row.path));

  assert.deepEqual([...paths].sort(), ['titles', 'titles/save-request', 'titles/delete', 'orders/eligible', 'apply', 'list', 'detail', 'cancel'].sort());
  assert.equal(values.size, 0);
});

test('company edits never write masked tax back; input is cleared on hide and unknown save stays protected after remount', () => {
  const values = new Map(), h = mount('edit', { id: TITLE }, values); h.boot(); h.respond('titles', [title()]); const p = h.page;
  p.saveTitle(); assert.equal(h.count('titles/save-request'), 0);
  p.input({ ...tap('', { field: 'taxNo' }), detail: { value: '91******1234' } }); p.saveTitle(); assert.equal(h.count('titles/save-request'), 0);
  p.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } }); p.saveTitle();
  h.latest('titles/save-request').fail({ errMsg: 'timeout' });
  assert.equal(p.data.titlePending, true); assert.equal(p.data.draft.taxNo, '');
  p.saveTitle(); assert.equal(h.count('titles/save-request'), 1);
  assert.equal(JSON.stringify([...values]).includes('913100001234567890'), false);
  p.onHide(); assert.equal(p.data.draft.taxNo, '');
  const next = mount('edit', { id: TITLE }, values); next.boot(); next.respond('titles', [title()]);
  assert.equal(next.page.data.titlePending, true);
  next.page.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } });
  next.page.saveTitle(); assert.equal(next.count('titles/save-request'), 0);
  const titles = mount('titles', {}, values); titles.boot(); titles.respond('titles', [title()]);
  assert.equal(titles.page.data.titlePending, true);
  titles.page.edit(tap(TITLE)); assert.equal(titles.navigations.length, 0, 'editing is locked until the original save is confirmed');
});

test('unknown title save: query reads the exact request, retries the same request only while this page still holds the input', () => {
  const values = new Map(), h = mount('edit', {}, values); h.boot(); const p = h.page;
  p.titleType(tap('', { type: 'PERSONAL' }));
  p.input({ ...tap('', { field: 'titleName' }), detail: { value: '林小舟' } }); p.saveTitle();
  const sent = h.latest('titles/save-request'), requestId = sent.body.requestId;
  assert.equal(sent.body.taxNo, undefined);
  sent.fail({ errMsg: 'timeout' });
  p.queryTitleSave(); assert.deepEqual(h.latest('titles/request').body, { requestId });
  h.latest('titles/request').success({ code: 500, errorCode: 'INVOICE_NOT_FOUND' });
  const retry = h.latest('titles/save-request');
  assert.notEqual(retry, sent); assert.deepEqual(retry.body, sent.body);
  retry.success({ code: 200, data: { requestId, titleId: TITLE, status: 'SAVED' } });
  assert.equal(p.data.titlePending, false); assert.equal(values.size, 0);

  const other = mount('titles', {}, values); other.boot();
  assert.equal(other.page.data.titlePending, false);
});

test('a backend title rejection keeps its own sentence and allows another attempt', () => {
  const h = mount('edit'); h.boot();
  h.page.input({ ...tap('', { field: 'titleName' }), detail: { value: '示例单位' } });
  h.page.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } });
  h.page.saveTitle();
  h.latest('titles/save-request').success({ code: 500, errorCode: 'INVOICE_TAX_NO_INVALID', msg: '纳税人识别号不合法' });
  assert.equal(h.page.data.saveError, '纳税人识别号不合法');
  assert.equal(h.page.data.titlePending, false);
  assert.equal(h.values.size, 0);
});

test('deadline keeps original request and frozen IDs; late success and negative reads never unlock another apply', () => {
  const values = new Map(), h = applyPage(values); const sent = submitted(h), id = sent.body.requestId;
  h.deadline(); assert.equal(sent.aborted, true); assert.equal(h.page.data.pending, true);
  assert.equal(h.page.data.requestId, id); assert.equal(h.page.data.writing, false); assert.equal(h.page.data.submit, 'unknown');
  h.page.pick(tap(ORDER)); assert.equal(h.page.data.selectedCount, 1);
  h.page.apply(); assert.equal(h.count('apply'), 1);
  sent.success({ code: 200, data: application(id, { replayed: false }) }); assert.equal(h.page.data.pending, true);
  h.page.queryResult(); h.latest('request').success({ code: 500, errorCode: 'INVOICE_NOT_FOUND' }); assert.equal(h.page.data.pending, true);
  h.page.queryResult(); h.respond('request', application('another-request')); assert.equal(h.page.data.pending, true);
  assert.equal(h.count('detail'), 0);
  h.page.onUnload();

  const next = applyPage(values);
  assert.equal(next.page.data.requestId, id); assert.equal(next.page.data.submit, 'unknown');
  assert.equal(next.page.data.selectedCount, 1, 'frozen orders stay selected');
  next.page.apply(); assert.equal(next.count('apply'), 0);

  const list = mount('list', {}, values); list.boot();
  assert.equal(list.page.data.recovery, 'unknown');
  list.respond('list', []);
  list.page.queryOriginal(); list.latest('request').success({ code: 500, errorCode: 'INVOICE_NOT_FOUND', msg: '暂未查到原申请' });
  assert.equal(list.page.data.recovery, 'notfound'); assert.equal(list.page.data.pending, true);
  list.page.queryOriginal(); list.respond('request', application(id));
  list.respond('detail', application('wrong-request')); assert.equal(list.page.data.pending, true);
  list.page.queryOriginal(); list.respond('request', application(id));
  list.respond('detail', application(id, { orders: [order({ orderId: '19' })], orderCount: 1 }));
  assert.equal(list.page.data.pending, true, 'positive recovery must match the frozen order set');
  list.page.queryOriginal(); list.respond('request', application(id)); list.respond('detail', application(id));
  assert.equal(list.page.data.pending, false); assert.equal(list.page.data.recovery, '');
  assert.equal(values.size, 0); assert.equal(next.count('apply'), 0);
});

test('a pending apply found in the recent list is confirmed only through its exact detail', () => {
  const values = new Map(), h = applyPage(values); const id = submitted(h).body.requestId;
  h.latest('apply').fail({ errMsg: 'timeout' });
  const list = mount('list', {}, values); list.boot(); list.respond('list', [application(id)]);
  assert.deepEqual(list.latest('detail').body, { id: APP });
  list.respond('detail', application(id));
  assert.equal(list.page.data.pending, false); assert.equal(values.size, 0);
});

test('apply page recovers its own unknown submit by the exact original request and returns to the list', () => {
  const values = new Map(), h = applyPage(values); const id = submitted(h).body.requestId;
  h.latest('apply').fail({ errMsg: 'timeout' });
  h.page.queryResult(); assert.deepEqual(h.latest('request').body, { requestId: id });
  h.respond('request', application(id)); h.respond('detail', application(id));
  assert.equal(values.size, 0);
  assert.deepEqual(h.navigations.at(-1), { kind: 'redirect', url: '/subpackageMember/invoice/index' });
});

test('a transport failure cannot use a successful-looking body to settle a write', () => {
  const h = applyPage(); const original = submitted(h);
  original.fail({ code: 200, data: application(original.body.requestId, { replayed: false }) });
  assert.equal(h.page.data.pending, true);
  assert.equal(h.page.data.requestId, original.body.requestId);
  h.page.apply(); assert.equal(h.count('apply'), 1);
});

test('malformed successful apply responses remain UNKNOWN, explicit transaction rejection permits a new attempt', () => {
  for (const bad of [{}, application('wrong', { replayed: false }), application('wrong', { replayed: true, orders: [] })]) {
    const h = applyPage(); submitted(h); h.respond('apply', bad);
    assert.equal(h.page.data.pending, true); h.page.apply(); assert.equal(h.count('apply'), 1);
  }
  const h = applyPage(); const first = submitted(h).body.requestId;
  h.latest('apply').success({ code: 500, errorCode: 'INVOICE_ORDER_NOT_ELIGIBLE', msg: '订单不可开票' });
  assert.equal(h.page.data.pending, false); assert.equal(h.page.data.submit, 'failed');
  assert.equal(h.page.data.submitSub, '订单有变化，请重新选择');
  h.page.apply(); assert.equal(h.count('apply'), 1, 'submit stays blocked until the player reselects');
  h.page.reselect(); h.respond('orders/eligible', [order()]);
  assert.equal(h.page.data.selectedCount, 0);
  h.page.pick(tap(ORDER)); h.page.apply(); assert.notEqual(h.latest('apply').body.requestId, first);
  const max = applyPage(); submitted(max);
  max.latest('apply').success({ code: 500, errorCode: 'INVOICE_ORDERS_TOO_MANY', msg: '一次最多合并50笔订单' });
  assert.equal(max.page.data.submit, 'max');
});

test('C4 + issuer rules: one issuer per application, unconfigured issuers greyed, 全选本单位 selects only its own orders', () => {
  const rows = [order(), order({ orderId: '41' }), order({ orderId: '42', issuerId: OTHER, issuerName: '开票单位二' }),
    order({ orderId: '43', issuerId: null, issuerName: null })];
  const h = applyPage(new Map(), rows); const p = h.page;
  assert.deepEqual(json(p.data.groups.map(group => group.blocked ? 'blocked' : group.name)), ['开票单位一', '开票单位二', 'blocked']);
  p.pick(tap('43')); assert.equal(p.data.selectedCount, 0); assert.equal(h.toasts.at(-1), '开票单位开通后可申请');
  p.pickAll(tap('', { issuer: ISSUER }));
  assert.equal(p.data.selectedCount, 2); assert.equal(p.data.total, '24.68');
  assert.equal(p.data.groups[1].rows[0].disabled, true); assert.equal(p.data.groups[1].canPickAll, false);
  p.pick(tap('42')); assert.equal(p.data.selectedCount, 2); assert.equal(h.toasts.at(-1), '不同开票单位需分开申请');
  p.pickAll(tap('', { issuer: OTHER })); assert.equal(p.data.selectedCount, 2);
  p.pickAll(tap('', { issuer: ISSUER })); assert.equal(p.data.selectedCount, 0, 'second tap clears the group');
  p.pick(tap('42')); assert.equal(p.data.selectedCount, 1); assert.equal(p.data.groups[0].rows[0].disabled, true);
  p.apply(); assert.deepEqual(h.latest('apply').body.orderIds, ['42']);
});

test('more than 50 orders show the backend sentence in the bottom bar and never post', () => {
  const rows = Array.from({ length: 51 }, (_, index) => order({ orderId: String(100 + index) }));
  const h = applyPage(new Map(), rows);
  h.page.pickAll(tap('', { issuer: ISSUER }));
  assert.equal(h.page.data.submit, 'max'); assert.equal(h.page.data.selectedCount, 0);
  h.page.apply(); assert.equal(h.count('apply'), 0);
  h.page.reselect(); assert.equal(h.page.data.submit, '');
});

test('title sheet picks among the member titles; no titles leaves submit disabled', () => {
  const h = mount('apply'); h.boot();
  h.respond('titles', [title({ isDefault: false }), title({ id: '18', titleType: 'PERSONAL', titleName: '林小舟', taxNoMasked: null, isDefault: true })]);
  h.respond('orders/eligible', [order()]);
  assert.equal(h.page.data.title.id, '18'); assert.equal(h.page.data.title.line, '个人 · 默认');
  h.page.openTitles(); assert.equal(h.page.data.sheet, true);
  h.page.pickTitle(tap(TITLE)); assert.equal(h.page.data.title.id, TITLE); assert.equal(h.page.data.sheet, false);
  h.page.pick(tap(ORDER)); h.page.apply(); assert.equal(h.latest('apply').body.titleId, TITLE);

  const empty = mount('apply'); empty.boot(); empty.respond('titles', []); empty.respond('orders/eligible', [order()]);
  empty.page.pick(tap(ORDER)); empty.page.apply();
  assert.equal(empty.page.data.title, null); assert.equal(empty.count('apply'), 0);
  empty.page.addTitle(); assert.equal(empty.navigations.at(-1).url, '/subpackageMember/invoice-title-edit/index');
});

test('selection survives a trip to 管理抬头 but not an account change', () => {
  const h = applyPage(new Map(), [order(), order({ orderId: '41' })]);
  h.page.pick(tap('41')); h.page.onHide(); h.page.onShow();
  h.respond('titles', [title()]); h.respond('orders/eligible', [order(), order({ orderId: '41' })]);
  assert.equal(h.page.data.selectedCount, 1);
  h.page.onHide(); h.owner('8'); h.page.onShow();
  h.respond('titles', [title()]); h.respond('orders/eligible', [order(), order({ orderId: '41' })]);
  assert.equal(h.page.data.selectedCount, 0);
});

test('account changes isolate old requests, private data and pending contexts, including delayed confirmation dialogs', () => {
  const values = new Map(), titles = mount('titles', {}, values); titles.boot(); titles.respond('titles', [title()]);
  titles.page.remove(tap(TITLE)); const oldDialog = titles.dialogs.at(-1);
  titles.owner('8'); oldDialog.success({ confirm: true });
  assert.equal(titles.count('titles/delete'), 0);

  const h = applyPage(values); const old = submitted(h);
  h.owner('8'); h.page.pick(tap(ORDER));
  assert.equal(h.page.data.pending, false); assert.equal(h.page.data.titles.length, 0);
  old.success({ code: 200, data: application(old.body.requestId, { replayed: false }) });
  assert.equal(h.navigations.length, 0);
  h.page.onHide(); h.owner('7'); h.page.onShow(); h.respond('titles', [title()]); h.respond('orders/eligible', [order()]);
  assert.equal(h.page.data.pending, true); assert.equal(h.page.data.requestId, old.body.requestId);
});

test('hidden and unloaded pages ignore stale reads and recover only from a fresh visible session', () => {
  const h = mount('titles'); h.boot(); const old = h.latest('titles');
  h.page.onHide(); assert.equal(old.aborted, true);
  old.success({ code: 200, data: [title()] }); assert.equal(h.page.data.titles.length, 0);
  h.page.onShow(); h.respond('titles', [title()]); assert.equal(h.page.data.titles.length, 1);
  h.page.onUnload(); h.page.onShow(); assert.equal(h.page.data.titles.length, 0);
});

test('unsafe Long response fields and invalid IDs never become write targets', () => {
  const h = mount('apply'); h.boot(); h.respond('titles', [title({ id: 9007199254740992 })]);
  h.respond('orders/eligible', [order({ orderId: 9007199254740992 })]);
  assert.equal(h.page.data.titles.length, 0); assert.equal(h.page.data.groups.length, 0);
  assert.equal(h.page.data.error, 'read');
  h.page.pickTitle(tap('9223372036854775808')); h.page.pick(tap('01')); h.page.apply();
  assert.equal(h.count('apply'), 0);
  const detail = mount('detail', { id: '01' }); detail.boot();
  assert.equal(detail.count('detail'), 0); assert.equal(detail.page.data.error, 'read');
});

test('FEATURE_OFF from every read/write response collapses to the same unavailable view', () => {
  const cases = [
    ['list', 'list', h => {}],
    ['detail', 'detail', h => {}],
    ['apply', 'titles', h => {}],
    ['apply', 'orders/eligible', h => {}],
    ['apply', 'apply', h => { h.respond('titles', [title()]); h.respond('orders/eligible', [order()]); h.page.pick(tap(ORDER)); h.page.apply(); }],
    ['titles', 'titles', h => {}],
    ['titles', 'titles/delete', h => { h.respond('titles', [title()]); h.page.remove(tap(TITLE)); h.confirm(); }],
    ['edit', 'titles/save-request', h => {
      h.page.input({ ...tap('', { field: 'titleName' }), detail: { value: '单位' } });
      h.page.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } }); h.page.saveTitle(); }],
    ['list', 'cancel', h => { h.respond('list', [application('known')]); h.page.cancel(tap(APP)); h.confirm(); }],
  ];
  for (const [name, route, act] of cases) {
    const h = mount(name, name === 'detail' ? { id: APP } : {}); h.boot(); act(h);
    h.latest(route).success({ code: 500, errorCode: 'INVOICE_FEATURE_OFF', msg: '发票功能暂未开放' });
    assert.equal(h.page.data.unavailable, true, name + ' ' + route);
    const count = h.requests.length;
    if (h.page.apply) h.page.apply();
    if (h.page.loadRecords) h.page.loadRecords();
    if (h.page.loadTitles) h.page.loadTitles();
    assert.equal(h.requests.length, count, name + ' ' + route);
  }
});

test('network and authority failures settle loading state and keep unknown writes protected', () => {
  const h = mount('list'); h.boot(); h.latest('list').fail({ errMsg: 'offline' });
  assert.equal(h.page.data.loading, false); assert.equal(h.page.data.error, 'read');
  h.page.loadRecords(); h.latest('list').successStatusAbnormal({ code: 401 }, 401);
  assert.equal(h.page.data.login, true);
  const a = applyPage(); submitted(a); a.latest('apply').successStatusAbnormal({ msg: 'gateway' }, 504);
  assert.equal(a.page.data.writing, false); assert.equal(a.page.data.pending, true);
  a.page.queryResult(); a.latest('request').fail({ errMsg: 'offline' });
  assert.equal(a.page.data.pending, true);
});

test('a synchronous read transport exception becomes a visible error and settles its loading flag', () => {
  const h = mount('titles'); h.app.sendRequest = () => { throw Error('SECRET_HOST'); };
  assert.doesNotThrow(() => h.boot());
  assert.equal(h.page.data.loading, false); assert.equal(h.page.data.error, 'read');
});

test('pending persistence and input-context persistence both fail closed before POST', () => {
  for (const failure of ['workflow', 'context']) {
    const h = applyPage();
    h.wx.setStorageSync = (key, value) => {
      if (failure === 'workflow' || key.startsWith('user_invoice_apply_v1:')) throw Error('disk full');
      h.values.set(key, json(value));
    };
    h.page.pick(tap(ORDER)); h.page.apply(); assert.equal(h.count('apply'), 0); assert.equal(h.page.data.pending, true);
  }
});

test('cancel timeout keeps original detail locked until positive CANCELLED readback', () => {
  const values = new Map(), h = mount('detail', { id: APP }, values); h.boot(); h.respond('detail', application('known'));
  h.page.cancel(); h.confirm(); h.latest('cancel').fail({ errMsg: 'timeout' });
  assert.equal(h.page.data.cancelPending, true); h.page.cancel(); assert.equal(h.count('cancel'), 1);
  h.page.refresh(); h.respond('detail', application('known')); assert.equal(h.page.data.cancelPending, true);
  h.page.onUnload();
  const next = mount('detail', { id: APP }, values); next.boot(); next.respond('detail', application('known'));
  assert.equal(next.page.data.cancelPending, true);
  next.page.cancel(); assert.equal(next.count('cancel'), 0);
  next.page.refresh(); next.respond('detail', application('known', { status: 'CANCELLED' }));
  assert.equal(next.page.data.cancelPending, false); assert.equal(next.page.data.detail.playerStatus, '已撤回');
  assert.equal(values.size, 0);
});

test('confirmed cancellation cannot be overwritten by a detail read started before its receipt', () => {
  const h = mount('detail', { id: APP }); h.boot(); h.respond('detail', application('known'));
  h.page.cancel(); h.confirm();
  h.page.refresh(); const oldDetail = h.latest('detail');
  h.respond('cancel', application('known', { status: 'CANCELLED' }));
  oldDetail.success({ code: 200, data: application('known') });
  assert.equal(h.page.data.detail.status, 'CANCELLED');
  h.page.cancel(); assert.equal(h.count('cancel'), 1);
});

test('C2/C3: handling states read as 开具中, paused as 退款核对中; only SUBMITTED can be withdrawn; no request number anywhere', () => {
  for (const [status, text] of [['SUBMITTED', '开具中'], ['CLAIMED', '开具中'], ['PROCESSING', '开具中'], ['ISSUE_UNKNOWN', '开具中'],
    ['PAUSED', '退款核对中'], ['ISSUED', '已开具'], ['REJECTED', '已驳回'], ['CANCELLED', '已撤回']]) {
    const h = mount('detail', { id: APP }); h.boot(); h.respond('detail', application('flow-' + status, { status }));
    assert.equal(h.page.data.detail.playerStatus, text, status);
    h.page.cancel(); if (status !== 'SUBMITTED') assert.equal(h.dialogs.length, 0, status);
  }
  const list = mount('list'); list.boot();
  list.respond('list', ['SUBMITTED', 'PAUSED', 'ISSUED', 'REJECTED', 'CANCELLED'].map((status, index) => application('r' + index, { id: String(50 + index), status })));
  const tabs = {};
  for (const tab of ['ing', 'issued', 'rejected', 'cancelled']) { list.page.onTab({ detail: { key: tab } }); tabs[tab] = list.page.data.rows.map(row => row.status); }
  assert.deepEqual(tabs, { ing: ['SUBMITTED', 'PAUSED'], issued: ['ISSUED'], rejected: ['REJECTED'], cancelled: ['CANCELLED'] });
  for (const name of Object.keys(PAGES)) {
    const xml = fs.readFileSync(path.join(ROOT, PAGES[name] + '.wxml'), 'utf8');
    assert.doesNotMatch(xml, /applicationNo|\{\{\s*requestId\s*\}\}|申请号|请求号|开票主体|待开具/, name);
  }
});

test('rejected applications reapply through the apply page with fresh selections only', () => {
  const h = mount('detail', { id: APP }); h.boot(); h.respond('detail', application('rejected-original', { status: 'REJECTED', rejectReason: '单位名称与税号不一致' }));
  h.page.reapply();
  assert.deepEqual(h.navigations.at(-1), { kind: 'to', url: '/subpackageMember/invoice-apply/index' });
  const apply = applyPage(); assert.equal(apply.page.data.selectedCount, 0);
  apply.page.pick(tap(ORDER)); apply.page.apply();
  assert.notEqual(apply.latest('apply').body.requestId, 'rejected-original');
});

test('anonymous pages prompt login and only read after the app returns a real session', async () => {
  const h = mount('list', {}, new Map(), ''); h.boot(); assert.equal(h.page.data.login, true); assert.equal(h.requests.length, 0);
  await h.page.login(); assert.equal(h.page.data.login, false); assert.equal(h.requests.length, 1);
  h.page.onBack(); assert.equal(h.navigations.at(-1).url, '/pages/member/index/index');
});

test('WXML actions reference real Page methods and registered cy components with defined design tokens', () => {
  const tokens = fs.readFileSync(path.join(ROOT, 'style/tokens.wxss'), 'utf8');
  for (const name of Object.keys(PAGES)) {
    const h = mount(name), file = path.join(ROOT, PAGES[name]);
    const wxml = fs.readFileSync(file + '.wxml', 'utf8');
    for (const match of wxml.matchAll(/(?:bind|catch)(?::[\w-]+|[\w-]+)="([\w]+)"/g)) assert.equal(typeof h.page[match[1]], 'function', name + ' ' + match[1]);
    const config = JSON.parse(fs.readFileSync(file + '.json', 'utf8'));
    for (const match of wxml.matchAll(/<(cy-[\w-]+)/g)) assert.ok(config.usingComponents[match[1]], name + ' ' + match[1]);
    for (const component of Object.values(config.usingComponents)) assert.ok(fs.existsSync(path.join(ROOT, component + '.json')), component);
    const styles = fs.readFileSync(file + '.wxss', 'utf8');
    for (const match of styles.matchAll(/var\((--[\w-]+)/g)) assert.ok(tokens.includes(match[1] + ':') || wxml.includes(match[1] + ':'), name + ' ' + match[1]);
    assert.doesNotMatch(styles, /#[0-9a-f]{3,8}\b/i, name);
    assert.doesNotMatch(wxml, /¥\{\{/, name);
  }
});
