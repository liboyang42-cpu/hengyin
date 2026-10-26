const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const PAGE = path.join(ROOT, 'subpackageMember/invoice/index.js');
const TITLE = '9007199254740993', ORDER = '9223372036854775807', APP = '9007199254740995';
const json = value => JSON.parse(JSON.stringify(value));
const tap = (id, extra = {}) => ({ currentTarget: { dataset: { id, ...extra } } });
const title = (extra = {}) => ({ id: TITLE, titleType: 'COMPANY', titleName: '发票测试单位', taxNoMasked: '91******1234', isDefault: true, ...extra });
const order = (extra = {}) => ({ orderId: ORDER, amount: '12.34', orderTitle: '真实订单', ticketName: '门票', ...extra });
const application = (requestId, extra = {}) => ({ id: APP, requestId, applicationNo: 'INV202610260001', status: 'SUBMITTED',
  titleName: '发票测试单位', taxNoMasked: '91******1234', totalAmount: '12.34', orderCount: 1, orders: [order()], ...extra });

// Load the actual Page and its actual workflow, storage and view helpers in one runtime.
function mount(values = new Map(), initialOwner = '7') {
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
    showModal: options => dialogs.push(options), navigateBack: options => navigations.push(options), switchTab: options => navigations.push(options),
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
  load(PAGE);
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
    boot() { page.onLoad({}); page.onShow(); },
    seed() { respond('titles', [title()]); respond('orders/eligible', [order()]); respond('list', []); },
    select() { page.pickTitle(tap(TITLE)); page.pickOrder(tap(ORDER)); },
  };
  return h;
}

test('Page drives all eight real JSON endpoints: titles, save, delete, eligible, apply, list, detail and cancel', () => {
  const h = mount(); h.boot(); h.seed();
  const p = h.page;
  p.editTitle(tap(TITLE));
  assert.equal(p.data.draft.taxNo, '');
  p.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } });
  p.saveTitle();
  assert.deepEqual(h.latest('titles/save').body, { id: TITLE, titleType: 'COMPANY', titleName: '发票测试单位', taxNo: '913100001234567890', isDefault: true });
  h.respond('titles/save', title()); h.respond('titles', [title()]);
  assert.equal(p.data.editing, false);
  assert.equal(JSON.stringify([...h.values]).includes('913100001234567890'), false);
  h.select(); p.apply();
  const request = h.latest('apply'), requestId = request.body.requestId;
  assert.deepEqual(request.body, { titleId: TITLE, orderIds: [ORDER], requestId });
  assert.match(requestId, /^[A-Za-z0-9_-]{16,64}$/);
  assert.ok([...h.values.values()].some(row => row.attemptId === requestId));
  request.success({ code: 200, data: application(requestId, { replayed: false }) });
  assert.equal(p.data.detail.id, APP); assert.equal(p.data.pending, false);
  h.respond('list', [application(requestId)]); h.respond('orders/eligible', []);
  assert.equal(p.data.records[0].id, APP);
  assert.equal(p.data.records[0].orderCount, 1);
  assert.deepEqual(json(p.data.records[0].orders), []);
  p.closeDetail(); p.openDetail(tap(APP));
  assert.equal(h.latest('detail').body.id, APP);
  h.respond('detail', application(requestId));
  p.cancel(); assert.equal(h.count('cancel'), 0); h.confirm();
  assert.deepEqual(h.latest('cancel').body, { id: APP });
  h.respond('cancel', application(requestId, { status: 'CANCELLED' }));
  assert.equal(p.data.detail.statusText, '已撤回');
  h.respond('list', [application(requestId, { status: 'CANCELLED' })]); h.respond('orders/eligible', [order()]);
  p.deleteTitle(tap(TITLE)); h.confirm();
  assert.deepEqual(h.latest('titles/delete').body, { id: TITLE });
  h.respond('titles/delete', null); h.respond('titles', []);
  assert.equal(p.data.titles.length, 0);
  assert.deepEqual([...new Set(h.requests.map(row => row.path))].sort(),
    ['titles', 'titles/save', 'titles/delete', 'orders/eligible', 'apply', 'list', 'detail', 'cancel'].sort());
  for (const request of h.requests) { assert.equal(request.method, 'POST'); assert.equal(request.header['Content-Type'], 'application/json'); }
  assert.equal(h.values.size, 0);
});

test('company edits never write masked tax back; input is cleared on hide and unknown save stays protected after remount', () => {
  const h = mount(); h.boot(); h.seed(); const p = h.page;
  p.editTitle(tap(TITLE)); p.saveTitle(); assert.equal(h.count('titles/save'), 0);
  p.input({ ...tap('', { field: 'taxNo' }), detail: { value: '91******1234' } }); p.saveTitle(); assert.equal(h.count('titles/save'), 0);
  p.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } }); p.saveTitle();
  h.latest('titles/save').fail({ errMsg: 'timeout' });
  assert.equal(p.data.titlePending, true); assert.equal(p.data.draft.taxNo, '');
  p.saveTitle(); assert.equal(h.count('titles/save'), 1);
  assert.equal(JSON.stringify([...h.values]).includes('913100001234567890'), false);
  p.onHide(); assert.equal(p.data.draft.taxNo, '');
  const next = mount(h.values); next.boot(); next.seed();
  next.page.editTitle(tap(TITLE)); next.page.saveTitle();
  assert.equal(next.page.data.titlePending, true); assert.equal(next.count('titles/save'), 0);
});

test('deadline keeps original request and frozen IDs; late success and negative reads never unlock another apply', () => {
  const h = mount(); h.boot(); h.seed(); h.select(); h.page.apply();
  const sent = h.latest('apply'), id = sent.body.requestId;
  h.deadline(); assert.equal(sent.aborted, true); assert.equal(h.page.data.pending, true);
  assert.equal(h.page.data.requestId, id); assert.equal(h.page.data.writing, false);
  h.page.pickOrder(tap(ORDER)); assert.equal(h.page.data.selectedCount, 1);
  h.page.apply(); assert.equal(h.count('apply'), 1);
  sent.success({ code: 200, data: application(id, { replayed: false }) }); assert.equal(h.page.data.pending, true);
  h.page.queryOriginal(); h.respond('list', []); assert.equal(h.page.data.pending, true);
  h.page.queryOriginal(); h.respond('list', [application('another-request')]); assert.equal(h.page.data.pending, true);
  h.page.onUnload();
  const next = mount(h.values); next.boot(); next.seed();
  assert.equal(next.page.data.requestId, id);
  assert.equal(next.page.data.frozenTitleId, TITLE);
  assert.deepEqual(json(next.page.data.frozenOrderIds), [ORDER]);
  assert.equal(next.page.data.selectedCount, 1);
  next.page.apply(); assert.equal(next.count('apply'), 0);
  next.page.queryOriginal(); next.respond('list', [application(id)]);
  assert.equal(next.page.data.pending, true);
  next.respond('detail', application('wrong-request')); assert.equal(next.page.data.pending, true);
  next.page.queryOriginal(); next.respond('list', [application(id)]);
  next.respond('detail', application(id, { orders: [] }));
  assert.equal(next.page.data.pending, true); assert.equal(next.page.data.detail, null);
  next.page.queryOriginal(); next.respond('list', [application(id)]); next.respond('detail', application(id));
  assert.equal(next.page.data.pending, false); assert.equal(next.page.data.detail.requestId, id);
  assert.equal(next.values.size, 0); assert.equal(next.count('apply'), 0);
});

test('a transport failure cannot use a successful-looking body to settle a write', () => {
  const h = mount(); h.boot(); h.seed(); h.select(); h.page.apply();
  const original = h.latest('apply');
  original.fail({ code: 200, data: application(original.body.requestId, { replayed: false }) });
  assert.equal(h.page.data.pending, true);
  assert.equal(h.page.data.detail, null);
  assert.equal(h.page.data.requestId, original.body.requestId);
  h.page.apply(); assert.equal(h.count('apply'), 1);
});

test('positive recovery must match the frozen order set before releasing the original request', () => {
  const h = mount(); h.boot(); h.seed(); h.select(); h.page.apply();
  const requestId = h.latest('apply').body.requestId;
  h.latest('apply').fail({ errMsg: 'timeout' });
  h.page.queryOriginal(); h.respond('list', [application(requestId)]);
  h.respond('detail', application(requestId, { orders: [order({ orderId: '19' })] }));
  assert.equal(h.page.data.pending, true);
  assert.equal(h.page.data.requestId, requestId);
  assert.match(h.page.data.error, /冻结输入不匹配/);
  h.page.apply(); assert.equal(h.count('apply'), 1);
  h.page.queryOriginal(); h.respond('list', [application(requestId)]); h.respond('detail', application(requestId));
  assert.equal(h.page.data.pending, false);
  assert.equal(h.page.data.detail.requestId, requestId);
});

test('closing detail or selecting another tab invalidates late detail responses', () => {
  const h = mount(); h.boot(); h.seed();
  h.page.openDetail(tap(APP)); const first = h.latest('detail'); h.page.closeDetail();
  first.success({ code: 200, data: application('known') });
  assert.equal(h.page.data.detail, null);
  h.page.openDetail(tap(APP)); const second = h.latest('detail');
  h.page.tab(tap('', { tab: 'titles' }));
  second.success({ code: 200, data: application('known') });
  assert.equal(h.page.data.tab, 'titles'); assert.equal(h.page.data.detail, null);
});

test('late responses after an account change clear old personal inputs without another UI action', () => {
  const h = mount(); h.boot(); h.seed();
  h.page.editTitle(tap(TITLE));
  h.page.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } });
  h.page.loadTitles(); const old = h.latest('titles');
  h.owner('8'); old.success({ code: 200, data: [title()] });
  assert.equal(h.page.data.draft.taxNo, '');
  assert.equal(h.page.data.titles.length, 0);
  assert.equal(h.page.data.editing, false);
  h.respond('titles', [title({ id: '18', titleName: '新账号' })]);
  assert.equal(h.page.data.titles[0].titleName, '新账号');
});

test('a synchronous read transport exception becomes a visible error and settles its loading flag', () => {
  const h = mount(); h.boot(); h.seed();
  h.app.sendRequest = () => { throw Error('SECRET_HOST'); };
  assert.doesNotThrow(() => h.page.loadTitles());
  assert.equal(h.page.data.loading, false);
  assert.match(h.page.data.error, /加载失败/);
  assert.doesNotMatch(h.page.data.error, /SECRET_HOST/);
});

test('account changes isolate old requests, private data and pending contexts, including delayed confirmation dialogs', () => {
  const h = mount(); h.boot(); h.seed(); h.page.editTitle(tap(TITLE));
  h.page.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } });
  h.page.deleteTitle(tap(TITLE)); const oldDialog = h.dialogs.at(-1);
  h.page.closeEditor(); h.select(); h.page.apply(); const old = h.latest('apply');
  h.owner('8'); h.page.pickOrder(tap(ORDER));
  assert.equal(h.page.data.pending, false); assert.equal(h.page.data.titles.length, 0); assert.equal(h.page.data.draft.taxNo, '');
  old.success({ code: 200, data: application(old.body.requestId, { replayed: false }) }); oldDialog.success({ confirm: true });
  assert.equal(h.page.data.detail, null); assert.equal(h.count('titles/delete'), 0);
  h.seed(); h.page.onHide(); h.owner('7'); h.page.onShow(); h.seed();
  assert.equal(h.page.data.pending, true); assert.equal(h.page.data.requestId, old.body.requestId);
});

test('hidden and unloaded pages ignore stale reads and recover only from a fresh visible session', () => {
  const h = mount(); h.boot(); const old = h.latest('titles');
  h.page.onHide(); assert.equal(old.aborted, true);
  old.success({ code: 200, data: [title()] }); assert.equal(h.page.data.titles.length, 0);
  h.page.onShow(); h.seed(); assert.equal(h.page.data.titles.length, 1);
  h.page.onUnload(); h.page.onShow(); assert.equal(h.page.data.titles.length, 0);
});

test('malformed successful apply responses remain UNKNOWN, explicit transaction rejection permits a new attempt', () => {
  for (const bad of [{}, application('wrong', { replayed: false }), application('wrong', { replayed: true, orders: [] })]) {
    const h = mount(); h.boot(); h.seed(); h.select(); h.page.apply(); h.respond('apply', bad);
    assert.equal(h.page.data.pending, true); h.page.apply(); assert.equal(h.count('apply'), 1);
  }
  const h = mount(); h.boot(); h.seed(); h.select(); h.page.apply(); const first = h.latest('apply').body.requestId;
  h.latest('apply').success({ code: 500, errorCode: 'INVOICE_ORDER_NOT_ELIGIBLE', msg: '订单不可开票' });
  assert.equal(h.page.data.pending, false); assert.match(h.page.data.error, /不可开票/);
  h.page.apply(); assert.notEqual(h.latest('apply').body.requestId, first);
  h.page.onUnload();
});

test('unsafe Long response fields and invalid IDs never become write targets', () => {
  const h = mount(); h.boot(); h.respond('titles', [title({ id: 9007199254740992 })]);
  h.respond('orders/eligible', [order({ orderId: 9007199254740992 })]); h.respond('list', []);
  assert.equal(h.page.data.titles.length, 0); assert.equal(h.page.data.orders.length, 0);
  assert.match(h.page.data.error, /数据无法确认/);
  h.page.pickTitle(tap('9223372036854775808')); h.page.openDetail(tap('01')); h.page.apply();
  assert.equal(h.count('apply'), 0); assert.equal(h.count('detail'), 0);
});

test('FEATURE_OFF from every read/write response collapses to the same unavailable view', () => {
  const cases = ['titles', 'orders/eligible', 'list', 'detail', 'apply', 'titles/save', 'titles/delete', 'cancel'];
  for (const route of cases) {
    const h = mount(); h.boot(); h.seed(); const p = h.page;
    if (route === 'detail') p.openDetail(tap(APP));
    if (route === 'apply') { h.select(); p.apply(); }
    if (route === 'titles/save') { p.editTitle(tap(TITLE)); p.input({ ...tap('', { field: 'taxNo' }), detail: { value: '913100001234567890' } }); p.saveTitle(); }
    if (route === 'titles/delete') { p.deleteTitle(tap(TITLE)); h.confirm(); }
    if (route === 'cancel') { p.openDetail(tap(APP)); h.respond('detail', application('known')); p.cancel(); h.confirm(); }
    // A new read is needed after seed already settled its initial response.
    if (route === 'titles') p.loadTitles();
    if (route === 'orders/eligible') p.loadOrders();
    if (route === 'list') p.loadRecords();
    h.latest(route).success({ code: 500, errorCode: 'INVOICE_FEATURE_OFF', msg: '发票功能暂未开放' });
    assert.equal(p.data.unavailable, true, route); assert.equal(p.data.detail, null, route);
    assert.equal(p.data.titles.length, 0, route); const count = h.requests.length;
    p.reload(); p.apply(); assert.equal(h.requests.length, count, route);
  }
});

test('network and authority failures settle loading state and keep unknown writes protected', () => {
  const h = mount(); h.boot(); h.latest('titles').fail({ errMsg: 'offline' });
  assert.equal(h.page.data.loading, false); assert.match(h.page.data.error, /加载失败/);
  h.page.loadTitles(); h.latest('titles').successStatusAbnormal({ msg: '无权查看' }, 403);
  assert.equal(h.page.data.loading, false); assert.equal(h.page.data.error, '无权查看');
  h.page.loadTitles(); h.respond('titles', [title()]); h.respond('orders/eligible', [order()]); h.respond('list', []);
  h.select(); h.page.apply(); h.latest('apply').successStatusAbnormal({ msg: 'gateway' }, 504);
  assert.equal(h.page.data.writing, false); assert.equal(h.page.data.pending, true);
  h.page.queryOriginal(); h.latest('list').fail({ errMsg: 'offline' });
  assert.equal(h.page.data.recovering, false); assert.equal(h.page.data.pending, true);
});

test('pending persistence and input-context persistence both fail closed before POST', () => {
  for (const failure of ['workflow', 'context']) {
    const h = mount(); h.boot(); h.seed(); h.select();
    h.wx.setStorageSync = (key, value) => {
      if (failure === 'workflow' || key.startsWith('user_invoice_apply_v1:')) throw Error('disk full');
      h.values.set(key, json(value));
    };
    h.page.apply(); assert.equal(h.count('apply'), 0); assert.equal(h.page.data.pending, true);
  }
});

test('cancel timeout keeps original detail locked until positive CANCELLED readback', () => {
  const h = mount(); h.boot(); h.seed(); h.page.openDetail(tap(APP)); h.respond('detail', application('known'));
  h.page.cancel(); h.confirm(); h.latest('cancel').fail({ errMsg: 'timeout' });
  assert.equal(h.page.data.cancelPending, true); h.page.cancel(); assert.equal(h.count('cancel'), 1);
  h.page.refreshDetail(); h.respond('detail', application('known')); assert.equal(h.page.data.cancelPending, true);
  h.page.onUnload();
  const next = mount(h.values); next.boot(); next.seed();
  next.page.openDetail(tap(APP)); next.respond('detail', application('known'));
  assert.equal(next.page.data.cancelPending, true);
  next.page.cancel(); assert.equal(next.count('cancel'), 0);
  next.page.refreshDetail(); next.respond('detail', application('known', { status: 'CANCELLED' }));
  assert.equal(next.page.data.cancelPending, false); assert.equal(next.page.data.detail.statusText, '已撤回');
  assert.equal(next.values.size, 0);
});

test('anonymous Page prompts login and only reads after the app returns a real session', async () => {
  const h = mount(new Map(), ''); h.boot(); assert.equal(h.page.data.login, true); assert.equal(h.requests.length, 0);
  await h.page.login(); assert.equal(h.page.data.login, false); assert.equal(h.requests.length, 3);
  h.page.onBack(); assert.equal(h.navigations.at(-1).url, '/pages/member/index/index');
});

test('WXML actions reference real Page methods and registered cy components with defined design tokens', () => {
  const h = mount(), wxml = fs.readFileSync(PAGE.replace('.js', '.wxml'), 'utf8');
  for (const match of wxml.matchAll(/bind(?::[\w-]+|[\w-]+)="([\w]+)"/g)) assert.equal(typeof h.page[match[1]], 'function', match[1]);
  const config = JSON.parse(fs.readFileSync(PAGE.replace('.js', '.json'), 'utf8'));
  for (const match of wxml.matchAll(/<(cy-[\w-]+)/g)) assert.ok(config.usingComponents[match[1]], match[1]);
  for (const component of Object.values(config.usingComponents)) assert.ok(fs.existsSync(path.join(ROOT, component + '.json')));
  const styles = fs.readFileSync(PAGE.replace('.js', '.wxss'), 'utf8'), tokens = fs.readFileSync(path.join(ROOT, 'style/tokens.wxss'), 'utf8');
  for (const match of styles.matchAll(/var\((--[\w-]+)/g)) assert.ok(tokens.includes(match[1] + ':'), match[1]);
  assert.doesNotMatch(styles, /#[0-9a-f]{3,8}\b|\d+rpx/i);
});
