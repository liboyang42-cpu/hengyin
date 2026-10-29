'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js');
const { afterEach } = require('node:test');
const view = require('../../utils/coop-invite-view.js');
const ROOT = path.resolve(__dirname, '../..');
const CREATE = 'pages/coop/invite/index.js';
const DETAIL = 'pages/coop/invite-detail/index.js';
const LIST = 'pages/coop/list/index.js';
const plain = value => JSON.parse(JSON.stringify(value));
const fixtures = [];
afterEach(() => { for (const page of fixtures.splice(0)) if (page.onUnload) page.onUnload(); });

function pageFixture(file, fixtureOptions = {}) {
  let definition;
  const requests = [], modals = [], tips = [], storage = fixtureOptions.storage || new Map();
  const app = { globalData: { statusBarHeight: 20, navBarHeight: 44 }, _owner: '7',
    getUserID() { return this._owner; }, getUserRole() { return 'merchant'; }, getUserType() { return 2; },
    getAuthorization() { return 'Bearer station-fixture'; },
    sendRequest(options) { requests.push(options); },
  };
  const wx = { getStorageSync(key) { return storage.has(key) ? plain(storage.get(key)) : ''; },
    setStorageSync(key, value) { storage.set(key, plain(value)); }, removeStorageSync(key) { storage.delete(key); },
    showToast(options) { tips.push(options.title); },
    showModal(options) { modals.push(options); }, stopPullDownRefresh() {},
    setNavigationBarColor() {}, showLoading() {}, hideLoading() {}, };
  const emptyPorts = new Set(['merchant-theme.js', 'identity-policy.js', 'checkout-workflow.js',
    'payment-verifier.js', 'merchant-official-channel.js', 'credit-view.js']);
  const sandbox = { getApp: () => app, Page(value) { definition = value; }, wx, console,
    getCurrentPages: () => [], setTimeout() { return 1; }, clearTimeout() {},
    require(id) {
      if (id.includes('coop-invite-view')) return view;
      if (id.includes('verification-readback')) return require('../../utils/verification-readback.js');
      if (id.includes('coop-invite-request')) return require('../../utils/coop-invite-request.js');
      if (id.includes('response-shape')) return require('../../utils/response-shape.js');
      if (id.includes('datetime')) return require('../../utils/datetime.js');
      if (id.includes('subscribe')) return { request: () => Promise.resolve({ status: 'accepted' }) };
      if (emptyPorts.has(path.basename(id))) return { merchantPageShow() {}, merchantPageRestore() {} };
      throw new Error('Unexpected page dependency: ' + id);
    },
  };
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, file), 'utf8'), sandbox, { filename: file });
  const page = Object.assign({}, definition); page.data = plain(definition.data);
  fixtures.push(page);
  page.setData = function (patch) { for (const [key, value] of Object.entries(patch)) {
    const parts = key.split('.'); let current = this.data;
    for (const part of parts.slice(0, -1)) current = current[part];
    current[parts[parts.length - 1]] = value;
  } };
  return { page, app, requests, modals, tips, storage, writes: () => requests.filter(r => r.url === '/api/coop/invite' || r.url === '/api/coop/handle') };
}
const byUrl = (f, url) => f.requests.filter(r => r.url === url);
function creation(productType = 1, fixtureOptions = {}) {
  const f = pageFixture(CREATE, fixtureOptions);
  f.page.onLoad({ type: '0', topicId: '9', toId: '11', toName: '原商家' });
  const metadata = byUrl(f, '/api/topic/list')[0];
  assert.ok(metadata); metadata.success({ code: 200, data: { rows: [{ id: '9', name: '原主题', productType }], total: 1 } });
  return f;
}
function stations(f, rows) {
  const request = byUrl(f, '/api/coop/supply-stations').at(-1);
  assert.equal(request.method, 'GET'); assert.notEqual(request.auth, false, '据点读取沿默认认证，不可匿名');
  assert.deepEqual(plain(request.data), { topicId: '9', merchantMemberId: '11' });
  request.success({ code: 200, data: { requiresSupplyStation: true, stations: rows } });
}
const options = [{ supplyStationId: '101', stationName: '西门据点' }, { supplyStationId: '102', stationName: '东门据点' }];
function choose(f, index = 1) { f.page.onSupplyStationChange({ currentTarget: { dataset: { toid: '11' } }, detail: { value: index } }); }
function invite(extra = {}) { return Object.assign({ id: '21', inviteId: '21', inviteType: 0, fromType: 'talent',
  toType: 'merchant', toId: '11', topicId: '9', status: 0, requiresSupplyStation: true, supplyStationId: '102', supplyStationName: '东门据点' }, extra); }
function received(file, row = invite()) {
  const f = pageFixture(file); f.page.data.invite = row; f.page.data.received = [row];
  f.page._inviteId = row.inviteId; f.page._detailIdentity = '7|merchant|2';
  return f;
}

test('creation binds the scoped GET and requires an explicit choice; sends the actual station ID once', () => {
  const f = creation(); stations(f, options);
  assert.equal(f.page.data.selected[0].supplyStationIndex, -1);
  assert.equal(f.page.data.selected[0].supplyStationId, '');
  f.page.sendInvite(); assert.equal(f.writes().length, 0); assert.equal(f.page.data.inviteModal.show, false);
  choose(f); f.page.sendInvite(); assert.equal(f.page.data.inviteModal.show, true);
  f.page.confirmInvite(); f.page.confirmInvite(); assert.equal(f.writes().length, 1);
  const command = JSON.parse(f.writes()[0].data);
  assert.match(command.requestId, /^[A-Za-z0-9_-]{1,64}$/);
  assert.equal(command.supplyStationId, '102'); assert.equal(command.topicId, '9'); assert.equal(command.toId, '11');
  assert.equal(command.shareMode, 0); assert.equal(command.inviteType, 0);
  for (const field of ['nodeId', 'expectedVersion', 'termsHash', 'intentId']) assert.equal(Object.hasOwn(command, field), false);
});
test('empty, malformed, duplicate or node-only station lists never select or permit a write', () => {
  for (const rows of [[], [{ nodeId: '101', stationName: '不是站点身份' }], [options[0], options[0]]]) {
    const f = creation(); stations(f, rows); choose(f, 0); f.page.sendInvite(); f.page.confirmInvite();
    assert.equal(f.writes().length, 0); assert.equal(f.page.data.selected[0].supplyStationId, '');
  }
});
test('a changed local target or station invalidates the confirmation instead of resending another intent', () => {
  const f = creation(); stations(f, options); choose(f); f.page.sendInvite(); choose(f, 0);
  f.page.confirmInvite(); assert.equal(f.writes().length, 0);
  assert.equal(f.page.data.sendResult, '邀约对象或核销据点已变化，请重新确认');
  assert.equal(f.tips.at(-1), '对象或据点已变，请重新确认');
  assert.ok(f.tips.at(-1).length <= 16, '完整说明留在页内，toast 保持可读长度');
  f.page.sendInvite(); f.page.confirmInvite(); assert.equal(JSON.parse(f.writes()[0].data).supplyStationId, '101');
});
test('an in-flight or unknown creation retains its original station across hide/show and never retries', () => {
  const f = creation(); stations(f, options); choose(f); f.page.sendInvite(); f.page.confirmInvite();
  const original = f.writes()[0], command = JSON.parse(original.data);
  assert.equal(f.page.data.selected[0].supplyStationId, '102');
  assert.equal(f.page.data.submitting, true, '原活跃请求仍保持单飞 busy');
  f.page.onHide(); f.page.onShow();
  f.page.retrySupplyStations(); f.page.retrySupplyTopic(); choose(f, 0);
  original.fail({ errMsg: 'request:fail timeout' });
  if (original.complete) original.complete();
  f.page.sendInvite(); f.page.confirmInvite();
  assert.equal(f.writes().length, 1);
  const key = require('../../utils/coop-invite-request.js').writeKey('7', command);
  assert.equal(f.page._inviteWorkflow().pendingRequest(key).requestId, command.requestId);
  assert.equal(f.page.data.unknownTargets[0].payload.supplyStationId, '102');
  assert.equal(f.page.data.unknownTargets[0].key, key);
  assert.equal(JSON.parse(original.data).supplyStationId, '102');
  assert.equal(f.page.data.submitting, false, '旧内存 busy 已退休，原持久待办仍占位，不能重发');
  assert.match(f.page.data.sendResult, /结果待确认/);
  const reentered = creation(1, { storage: f.storage }); stations(reentered, options); choose(reentered);
  reentered.page.sendInvite(); reentered.page.confirmInvite();
  assert.equal(reentered.writes().length, 0, '原请求跨实例仍占位，不靠旧内存 busy 假装防重');
  assert.equal(reentered.page._inviteWorkflow().pendingRequest(key).requestId, command.requestId);
});
test('late station reads cannot attach to a different topic, account, selection or page lifetime', () => {
  for (const change of [f => { f.page.data.topicId = '10'; }, f => { f.app._owner = '8'; },
    f => f.page.onHide(), f => { f.page._setSelected([]); }]) {
    const f = creation(), request = byUrl(f, '/api/coop/supply-stations')[0]; change(f);
    request.success({ code: 200, data: { requiresSupplyStation: true, stations: options } });
    assert.ok(!f.page.data.selected.length || f.page.data.selected[0].supplyStationId === '');
    assert.equal(f.writes().length, 0);
  }
});
test('scoped station GET preserves default transport feedback and page retry without an invite', () => {
  for (const reject of [request => request.fail({ errMsg: 'request:fail timeout' }),
    request => request.successStatusAbnormal({ code: 503, msg: '据点读取暂不可用' }),
    request => request.success({ code: 200, data: { requiresSupplyStation: false, stations: options } })]) {
    const f = creation(), request = byUrl(f, '/api/coop/supply-stations')[0];
    assert.notEqual(request.auth, false); assert.equal(request.silentError, undefined, '沿 request-client 默认可见错误，不新增静默豁免');
    reject(request);
    assert.equal(f.page.data.selected[0].supplyState, 'error');
    assert.ok(f.page.data.selected[0].supplyError);
    assert.equal(f.page.data.selected[0].supplyStationId, '');
    assert.equal(f.writes().length, 0); assert.equal(f.tips.length, 0);
    const previous = byUrl(f, '/api/coop/supply-stations').length;
    f.page.retrySupplyStations();
    assert.equal(byUrl(f, '/api/coop/supply-stations').length, previous + 1);
  }
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/coop/invite/index.wxml'), 'utf8');
  assert.match(wxml, /<cy-error[^>]*item\.supplyState === 'error'[^>]*sub="\{\{item\.supplyError\}\}"[^>]*bind:retry="retrySupplyStations"/);
});
test('owner topic metadata failures stay visible and cannot guess a station requirement or send', () => {
  for (const reject of [request => request.fail({ errMsg: 'request:fail timeout' }),
    request => request.successStatusAbnormal({ code: 503, msg: '主题读取暂不可用' }),
    request => request.success({ code: 200, data: { rows: [{ id: '9', name: '原主题' }], total: 1 } })]) {
    const f = pageFixture(CREATE);
    f.page.onLoad({ type: '0', topicId: '9', toId: '11' });
    const request = byUrl(f, '/api/topic/list')[0];
    assert.equal(request.data.is_my, 1); assert.notEqual(request.auth, false);
    assert.equal(request.silentError, undefined, '主题类型读取沿默认可见错误，不新增静默豁免');
    reject(request);
    assert.equal(f.page.data.supplyTopicState, 'error'); assert.ok(f.page.data.supplyTopicError);
    assert.equal(byUrl(f, '/api/coop/supply-stations').length, 0);
    assert.equal(f.writes().length, 0); assert.equal(f.tips.length, 0);
    const previous = byUrl(f, '/api/topic/list').length;
    f.page.retrySupplyTopic();
    assert.equal(byUrl(f, '/api/topic/list').length, previous + 1);
  }
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/coop/invite/index.wxml'), 'utf8');
  assert.match(wxml, /<cy-error[^>]*supplyTopicState === 'error'[^>]*sub="\{\{supplyTopicError\}\}"[^>]*bind:retry="retrySupplyTopic"/);
});
test('CLUB and known non-classic flows never request supply stations and preserve their original payload', () => {
  const club = pageFixture(CREATE); club.page.onLoad({ type: '1', topicId: '9', toId: '11' });
  assert.equal(byUrl(club, '/api/coop/supply-stations').length, 0);
  club.page.sendInvite(); club.page.confirmInvite(); assert.equal(club.writes().length, 1);
  const command = JSON.parse(club.writes()[0].data); assert.equal(command.inviteType, 1); assert.equal(Object.hasOwn(command, 'supplyStationId'), false);
  const other = creation(2); assert.equal(byUrl(other, '/api/coop/supply-stations').length, 0);
  other.page.sendInvite(); other.page.confirmInvite(); assert.equal(other.writes().length, 1);
  assert.equal(Object.hasOwn(JSON.parse(other.writes()[0].data), 'supplyStationId'), false);
});
test('scoped candidates cannot reuse one station across separate invitation targets', () => {
  const f = creation(); stations(f, [options[0]]); choose(f, 0);
  f.page._setSelected([...f.page.data.selected, { toId: '12', name: '第二商家' }]);
  const request = byUrl(f, '/api/coop/supply-stations').at(-1);
  assert.equal(request.data.merchantMemberId, '12');
  request.success({ code: 200, data: { requiresSupplyStation: true, stations: [options[0]] } });
  f.page.onSupplyStationChange({ currentTarget: { dataset: { toid: '12' } }, detail: { value: 0 } });
  f.page.sendInvite(); assert.equal(f.writes().length, 0); assert.equal(f.page.data.inviteModal.show, false);
});
test('station projections never fabricate history or confuse nodeId with a station; long IDs remain exact', () => {
  const old = invite({ supplyStationId: null }); assert.equal(view.supplyStationView(old).supplyStationText, '原合同未记录归属');
  assert.throws(() => view.supplyStationConfirmation(old)); assert.equal(old.supplyStationId, null);
  const original = invite({ supplyStationId: '9223372036854775807' }), proof = view.supplyStationConfirmation(original);
  assert.equal(proof.supplyStationId, '9223372036854775807'); assert.equal(Object.isFrozen(proof), true);
  assert.equal(view.sameSupplyStationConfirmation(invite({ supplyStationId: '101' }), proof), false);
  assert.throws(() => view.supplyStationConfirmation(invite({ supplyStationId: 9007199254740992 })));
  assert.throws(() => view.supplyStationConfirmation(invite({ id: '22' })));
  assert.equal(view.supplyStationConfirmation(invite({ inviteType: 1, toType: 'club' })), null);
  assert.equal(view.supplyStationConfirmation(invite({ nodeId: '103' })), null);
});
for (const file of [DETAIL, LIST]) {
  test(file + ': explicitly non-applicable merchant and CLUB rows preserve acceptance without a station', () => {
    for (const row of [invite({ requiresSupplyStation: false, supplyStationId: null }), invite({ inviteType: 1, toType: 'club', supplyStationId: null })]) {
      const f = received(file, row); if (file === DETAIL) { f.page.accept(); f.modals[0].success({ confirm: true }); }
      else f.page.acceptReceived({ currentTarget: { dataset: { id: '21' } } });
      assert.equal(f.writes().length, 1); assert.equal(Object.hasOwn(JSON.parse(f.writes()[0].data), 'supplyStationId'), false);
      assert.equal(byUrl(f, '/api/coop/supply-stations').length, 0);
      assert.equal(view.supplyStationView(row).supplyStationText, '');
    }
  });
  test(file + ': accept visibly confirms and submits exactly the original station; no version is invented', () => {
    const f = received(file); if (file === DETAIL) f.page.accept(); else f.page.acceptReceived({ currentTarget: { dataset: { id: '21' } } });
    assert.equal(f.writes().length, 0); assert.equal(f.modals.length, 1); assert.match(f.modals[0].content, /102/);
    assert.match(f.modals[0].content, /东门据点/); assert.equal(byUrl(f, '/api/coop/supply-stations').length, 0);
    f.modals[0].success({ confirm: true }); assert.equal(f.writes().length, 1);
    const command = JSON.parse(f.writes()[0].data); assert.equal(command.supplyStationId, '102'); assert.equal(String(command.id), '21'); assert.equal(command.status, 1);
    for (const field of ['nodeId', 'expectedVersion', 'termsHash', 'intentId', 'supplyStationName']) assert.equal(Object.hasOwn(command, field), false);
  });
  test(file + ': missing scope and late target/account/page changes cannot write or auto-retry', () => {
    const old = received(file, invite({ supplyStationId: null }));
    if (file === DETAIL) old.page.accept(); else old.page.acceptReceived({ currentTarget: { dataset: { id: '21' } } });
    assert.equal(old.writes().length, 0); assert.equal(old.modals.length, 0);
    for (const change of [f => { f.page.data.invite.supplyStationId = '101'; }, f => { f.app._owner = '8'; }, f => f.page.onHide()]) {
      const f = received(file); if (file === DETAIL) f.page.accept(); else f.page.acceptReceived({ currentTarget: { dataset: { id: '21' } } });
      change(f); f.modals[0].success({ confirm: true }); assert.equal(f.writes().length, 0);
    }
  });
}
