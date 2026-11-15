'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { platform, tick } = require('./helpers/team-event-platform');
const root = path.resolve(__dirname, '../..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const TEAM = '91', ACTIVITY = '9223372036854775807';
const preview = { id: TEAM, title: '同行', status: 0, joinMode: 1, joinedCount: 1, maxMembers: 4 };
const missing = (target = { ownerType: 2, ownerId: ACTIVITY }) => ({
  joined: false, canJoinWithCode: false, joinBlockers: ['TICKET_REQUIRED'], purchaseTarget: target,
});
const eligible = () => ({ joined: false, canJoinWithCode: true, joinBlockers: [] });

function mount() {
  const file = path.join(root, 'pages/team/join/index.js'), realRequire = createRequire(file);
  let definition, owner = '71', auth = 'auth-a';
  const requests = [], routes = [], observers = [], wx = platform();
  wx.navigateTo = options => routes.push(options);
  const app = { globalData: {}, getUserID: () => owner, getAuthorization: () => auth,
    sendRequest: r => requests.push(r), getRequestErrorMessage: (r, fallback) => r.msg || fallback };
  vm.runInNewContext(read('pages/team/join/index.js'), {
    Page: p => { definition = p; }, getApp: () => app, wx,
    require: p => p.endsWith('/toast.js') ? () => {} : p.endsWith('/page-motion.js') ? { enter() {} }
      : p.endsWith('/team-event-command.js') ? { createRecovery(options) {
        observers.push(options);
        return realRequire(p).createRecovery(options);
      } } : realRequire(p),
  });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch); } });
  page.onLoad({ code: 'original-invite' });
  const infos = () => requests.filter(r => r.url === '/api/team/info');
  const commands = () => requests.filter(r => r.url === '/api/team/command');
  return { page, routes, observers, requests, infos, commands,
    info(viewer, team = preview, request = infos().at(-1)) {
      request.success({ code: 200, data: { team, viewer, joined: false } });
    },
    switchOwner() { owner = '72'; auth = 'auth-b'; }, rotate() { auth = 'auth-b'; },
    anonymous() { owner = ''; auth = ''; },
  };
}

function buyVisible(page) {
  const tag = read('pages/team/join/index.wxml').match(/<view\b[^>]*bindtap="goBuyTicket"[^>]*>/)[0];
  const expression = tag.match(/wx:elif="\{\{(.*?)\}\}"/)[1];
  return !!vm.runInNewContext(expression, page.data);
}

test('six-field nonmember preview uses authenticated viewer target for the real WXML and checkout', () => {
  const h = mount(); h.info(missing());
  assert.equal(Object.hasOwn(h.page.data.team, 'ownerId'), false);
  assert.equal(buyVisible(h.page), true);
  h.page.goBuyTicket();
  assert.deepEqual(h.routes.map(r => r.url), ['/pages/activity/baoming/baoming?activityId=' + ACTIVITY]);
  assert.equal(h.commands().length, 0, 'navigation must never join automatically');
});

test('legacy raw team owner is never a fallback when viewer omits purchaseTarget', () => {
  const h = mount(); h.info(missing(null), { ...preview, ownerType: 2, ownerId: '101' });
  assert.equal(buyVisible(h.page), false); h.page.goBuyTicket(); assert.equal(h.routes.length, 0);
});

for (const target of [null, {}, { ownerType: 1, ownerId: '101' }, { ownerType: true, ownerId: '101' },
  { ownerType: 2, ownerId: 9223372036854775807 }, { ownerType: 2, ownerId: '../101' },
  { ownerType: 2, ownerId: '0' }, { ownerType: 2, ownerId: ['101'] }]) {
  test('invalid target is fail-closed: ' + JSON.stringify(target), () => {
    const h = mount(); h.info(missing(target)); assert.equal(buyVisible(h.page), false);
    h.page.goBuyTicket(); assert.equal(h.routes.length, 0);
  });
}

test('anonymous, non-ticket, multiple blockers and joined/full previews cannot navigate a target', () => {
  for (const mode of ['anonymous', 'eligible', 'multiple', 'joined', 'full']) {
    const h = mount();
    if (mode === 'anonymous') { h.anonymous(); h.page.onShow(); }
    const viewer = mode === 'eligible' ? { ...eligible(), purchaseTarget: missing().purchaseTarget } : missing();
    if (mode === 'multiple') viewer.joinBlockers.push('APPLY_BLOCKED');
    const req = h.infos().at(-1);
    if (mode === 'joined') req.success({ code: 200, data: { team: preview, viewer, joined: true } });
    else h.info(viewer, { ...preview, status: mode === 'full' ? 1 : 0 });
    h.page.goBuyTicket(); assert.equal(h.routes.length, 0, mode);
  }
});

test('return reload replaces missing-ticket state and only an explicit join sends a command', async () => {
  const h = mount(); h.info(missing()); h.page.goBuyTicket(); h.page.onHide(); h.page.onShow();
  assert.equal(h.infos().length, 2); h.info(eligible());
  assert.equal(h.page.data.ticketRequired, false); assert.equal(h.page.data.joinError, '');
  assert.equal(buyVisible(h.page), false); assert.equal(h.commands().length, 0);
  h.page.join(); await tick(); assert.equal(h.commands().length, 1);
  assert.equal(JSON.parse(h.commands()[0].data).inviteCode, 'original-invite');
});

test('real rejected command is preserved as history while fresh eligibility clears only the old ticket display', async () => {
  const h = mount(); h.info(eligible()); h.page.join(); await tick();
  const request = h.commands().at(-1), command = JSON.parse(request.data);
  request.success({ code: 200, data: { requestId: command.requestId, state: 'REJECTED',
    errorCode: 'TICKET_REQUIRED', message: '需要本场票' } });
  h.info(eligible());
  assert.equal(h.page.data.ticketRequired, false); assert.equal(h.page.data.joinError, '');
  const late = { busy: false, card: false, outcome: { state: 'REJECTED', errorCode: 'TICKET_REQUIRED', message: '需要本场票' } };
  h.observers.at(-1).changed(late); // Page callback boundary only; not an SDK or server proof.
  assert.equal(h.page.data.ticketRequired, false); assert.equal(h.page.data.joinError, '');
  assert.equal(late.outcome.state, 'REJECTED', 'do not rewrite the business receipt');
  h.page.join(); await tick(); assert.equal(h.commands().length, 2);
  assert.notEqual(JSON.parse(h.commands()[1].data).requestId, command.requestId);
});

test('a new real missing-ticket rejection invalidates an older eligible projection', async () => {
  const h = mount(); h.info(eligible()); h.page.join(); await tick();
  const r = h.commands().at(-1);
  r.success({ code: 200, data: { requestId: JSON.parse(r.data).requestId,
    state: 'REJECTED', errorCode: 'TICKET_REQUIRED', message: '票已失效' } });
  h.info(missing()); assert.equal(h.page.data.ticketRequired, true);
  assert.equal(buyVisible(h.page), true);
});

test('fresh ticket eligibility does not erase a different real rejection or an unknown projection', async () => {
  for (const code of ['APPLY_BLOCKED', 'TICKET_REQUIRED']) {
    const h = mount(); h.info(eligible()); h.page.join(); await tick();
    const r = h.commands().at(-1);
    r.success({ code: 200, data: { requestId: JSON.parse(r.data).requestId,
      state: 'REJECTED', errorCode: code, message: '原拒绝' } });
    h.info(code === 'APPLY_BLOCKED' ? eligible() : {});
    assert.equal(h.page.data.joinError, '原拒绝');
    h.page.join(); await tick(); assert.equal(h.commands().length, 1);
  }
});

for (const change of ['rotate', 'switchOwner', 'hide', 'unload', 'code', 'refresh', 'fail', 'refused']) {
  test('cached purchase target cannot navigate after ' + change, () => {
    const h = mount(); h.info(missing());
    if (change === 'rotate' || change === 'switchOwner') h[change]();
    if (change === 'hide') h.page.onHide();
    if (change === 'unload') h.page.onUnload();
    if (change === 'code') h.page.inviteCode = 'different-invite';
    if (['refresh', 'fail', 'refused'].includes(change)) {
      h.page.load();
      if (change === 'fail') h.infos().at(-1).fail();
      if (change === 'refused') h.infos().at(-1).success({ code: 500, errorCode: 'TEAM_INFO_REFUSED', msg: '邀请已失效' });
    }
    h.page.goBuyTicket(); assert.equal(h.routes.length, 0);
  });
}

test('older info and disposed recovery callbacks cannot replace the new reader/code projection', () => {
  const h = mount(), first = h.infos()[0], old = h.observers[0];
  h.page.onHide(); h.switchOwner(); h.page.onShow(); h.info(eligible());
  h.info(missing(), preview, first);
  old.changed({ busy: false, outcome: { state: 'REJECTED', errorCode: 'TICKET_REQUIRED', message: '旧缺票' } });
  assert.equal(h.page.data.ticketRequired, false); assert.equal(h.page.data.joinError, '');
  h.page.goBuyTicket(); assert.equal(h.routes.length, 0);
});

test('UNKNOWN command survives normal return and blocks a second join or purchase', async () => {
  const h = mount(); h.info(eligible()); h.page.join(); await tick(); h.commands()[0].fail();
  h.page.onHide(); h.page.onShow(); h.info(missing());
  assert.equal(h.page._recovery.pending(), true);
  assert.equal(h.page.data.recoveryShown, true);
  h.page.goBuyTicket(); h.page.join(); await tick();
  assert.equal(h.routes.length, 0); assert.equal(h.commands().length, 1);
});

function orderShell(routes) {
  let page; const navigations = [];
  vm.runInNewContext(read('subpackageMember/orderinfo/orderinfo.js'), {
    Page: p => { page = p; }, require: () => ({}), getCurrentPages: () => routes.map(route => ({ route })),
    wx: { navigateBack: o => navigations.push(['back', o.delta]), redirectTo: o => navigations.push(['redirect', o.url]) },
  });
  return { page, navigations };
}

test('only the real order navigation bar returns to an existing invitation; error exits keep order-list semantics', () => {
  const markup = read('subpackageMember/orderinfo/orderinfo.wxml');
  const handler = markup.match(/<cy-nav-bar\b[^>]*bind:back="([^"]+)"/)[1];
  const h = orderShell(['pages/team/join/index', 'subpackageMember/orderinfo/orderinfo']);
  h.page[handler](); assert.deepEqual(h.navigations, [['back', 1]]);
  assert.match(markup, /<cy-scene-member-order-detail[^>]*bind:back="backToOrderList" bind:close="backToOrderList"/);
  h.navigations.length = 0; h.page.backToOrderList();
  assert.deepEqual(h.navigations, [['redirect', '/subpackageMember/order/order']]);
  for (const stack of [[], ['subpackageMember/signup/index', 'subpackageMember/orderinfo/orderinfo']]) {
    const other = orderShell(stack); other.page[handler]();
    assert.deepEqual(other.navigations, [['redirect', '/subpackageMember/order/order']]);
  }
  const list = orderShell(['subpackageMember/order/order', 'subpackageMember/orderinfo/orderinfo']);
  list.page[handler](); assert.deepEqual(list.navigations, [['back', 1]]);
  const moving = orderShell(['pages/team/join/index', 'subpackageMember/orderinfo/orderinfo']);
  moving.page[handler]({ detail: { navigating: true } }); assert.deepEqual(moving.navigations, []);
});

test('existing successful checkout still redirects to order detail; result is never converted to automatic join', () => {
  const source = read('pages/activity/baoming/baoming.js');
  const body = source.match(/onResultSheetClose\(\) \{([\s\S]*?)\n  \},/)[1];
  const routes = [], timers = [];
  const page = vm.runInNewContext('({onResultSheetClose() {' + body + '}})', {
    SHEET_EXIT_MS: 1, setTimeout: fn => { timers.push(fn); return 1; },
    wx: { redirectTo: options => routes.push(options.url) },
  });
  page.data = { resultSheet: { kind: 'success' } }; page._regId = '501'; page.setData = () => {};
  page.onResultSheetClose(); assert.equal(routes.length, 0); timers[0]();
  assert.deepEqual(routes, ['/subpackageMember/orderinfo/orderinfo?id=501&from=signup']);
});
