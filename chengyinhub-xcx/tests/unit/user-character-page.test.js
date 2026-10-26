const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const character = require('../../utils/user-character.js');
const PAGE = 'subpackageMember/character/index';
const clone = value => JSON.parse(JSON.stringify(value));

function attribute(extra = {}) {
  return Object.assign({ stateKey: 'counter.hp', label: '体力', value: 6, min: 0, max: 10,
    displayType: 'bar', persistScope: 'run', rules: null, color: null, icon: null }, extra);
}
function card(extra = {}) {
  return Object.assign({ topicId: '901', created: true, running: true,
    attributes: [attribute()] }, extra);
}
function harness(relative = PAGE) {
  const requests = [], navigation = [], writes = [];
  let definition, userId = '71', token = 'fixture-account-a', loginResult = false;
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => userId, getAuthorization: () => token,
    sendRequest(options) {
      requests.push(options);
      return { abort() {} };
    },
  };
  const filename = path.join(ROOT, relative + '.js');
  const context = {
    getApp: () => app, Page: value => { definition = value; }, console,
    getCurrentPages: () => [{ route: 'previous' }, { route: relative }],
    wx: {
      navigateTo: options => navigation.push(options), navigateBack() {}, redirectTo() {},
    },
    require(id) {
      if (id.endsWith('auth/require-login.js')) return { requireLogin: () => Promise.resolve(loginResult) };
      if (id.endsWith('merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} };
      return require(path.resolve(path.dirname(filename), id));
    },
  };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), context, { filename });
  assert.ok(definition, 'actual Page must register');
  const page = Object.assign({}, definition, {
    data: clone(definition.data),
    setData(patch, done) { writes.push(clone(patch)); Object.assign(this.data, clone(patch)); if (done) done(); },
  });
  return { page, requests, navigation, writes, app,
    account(id, auth = 'fixture-account-b') { userId = id; token = auth; },
    allowLogin() { loginResult = true; },
    open(options = { topicId: '901' }) {
      page.onLoad(options); page.onShow();
    },
  };
}

test('real Page requests only the exact theme and renders server-owned dynamic values', () => {
  const h = harness();
  h.open();
  const req = h.requests[0];
  assert.equal(req.url, '/api/play/character');
  assert.equal(req.method, 'GET');
  assert.deepEqual(clone(req.data), { topicId: '901' });
  assert.equal(req.header, undefined, 'authorization stays in shared request transport');
  req.success({ code: '200', data: card({ attributes: [attribute(), attribute({ stateKey: 'counter.rank',
    label: '声望', value: 8, displayType: 'stars', persistScope: 'topic',
    rules: { levels: [{ at: 0, label: '初识' }, { at: 5, label: '熟悉' }] } })] }) });
  assert.equal(h.page.data.state, 'ready');
  assert.equal(h.page.data.card.running, true);
  assert.equal(h.page.data.card.attributes[0].value, 6);
  assert.equal(h.page.data.card.attributes[0].percent, 60);
  assert.equal(h.page.data.card.attributes[1].scopeText, '同主题跨局保留');
  assert.equal(h.page.data.card.attributes[1].levelLabel, '熟悉');
  assert.equal(h.page.data.card.attributes[1].stars.filter(s => s.filled).length, 8);
  assert.doesNotMatch(JSON.stringify(h.page.data), /fixture-account|authorization|"rules"/);
  assert.equal(h.requests.length, 1, 'viewing never creates a character or writes attributes');
});

test('template, empty template and empty created card remain distinct from request failure', () => {
  for (const created of [false, true]) {
    for (const attributes of [[], [attribute({ value: 3 })]]) {
      const h = harness(); h.open();
      h.requests[0].success({ code: 200, data: card({ created, running: false, attributes }) });
      assert.equal(h.page.data.state, created ? 'ready' : 'template');
      assert.equal(h.page.data.card.created, created);
      assert.equal(h.page.data.card.attributes.length, attributes.length);
      if (attributes.length) assert.equal(h.page.data.card.attributes[0].value, 3);
    }
  }
  const h = harness(); h.open(); h.requests[0].fail({ errMsg: 'offline' });
  assert.equal(h.page.data.state, 'error');
  assert.equal(h.page.data.card, null);
});

test('hidden attributes and arbitrary backend metadata never enter Page data', () => {
  const h = harness(); h.open();
  h.requests[0].success({ code: 200, data: card({
    attributes: [attribute({ rules: { levels: [], secret: 'SECRET_RULE' }, privateValue: 'SECRET_RAW' }),
      attribute({ stateKey: 'counter.secret', label: 'SECRET_LABEL', visible: false }),
      attribute({ stateKey: 'counter.secret2', label: 'SECRET_OTHER', hidden: true })],
    variables: { hidden: 'SECRET_VARIABLE' }, frozenDefinitions: { hidden: 'SECRET_DEFINITION' },
  }) });
  assert.equal(h.page.data.card.attributes.length, 1);
  assert.doesNotMatch(JSON.stringify(h.page.data), /SECRET_|counter.secret|frozenDefinitions/);
});

test('read-only RPG public projection displays momentum, clocks, facets and active aspects', () => {
  const h = harness(); h.open();
  h.requests[0].success({ code: 200, data: card({ rpg: {
    enabled: true, momentum: { value: 2, max: 5, secret: 'SECRET_MOMENTUM' },
    clocks: [{ var: 'counter.time', label: '倒计时', value: 2, segments: 4, filled: false },
      { var: 'counter.hidden', label: 'SECRET_CLOCK', visible: false }],
    facets: [{ key: 'curious', label: '好奇', desc: '喜欢发现', private: 'SECRET_FACET' }],
    aspects: [{ key: 'tired', label: '疲惫', desc: '本局状态' }],
    environment: { secret: 'SECRET_ENV' },
  } }) });
  const rpg = h.page.data.card.rpg;
  assert.equal(rpg.momentum.value, 2);
  assert.equal(rpg.clocks[0].percent, 50);
  assert.equal(rpg.facets[0].label, '好奇');
  assert.equal(rpg.aspects[0].label, '疲惫');
  assert.doesNotMatch(JSON.stringify(h.page.data), /SECRET_|counter.hidden/);
});

test('actual Page separates authentication, permission, unavailable theme, upgrade and failures', () => {
  for (const [response, status, state] of [
    [{ code: 401, errorCode: 'CHARACTER_LOGIN_REQUIRED' }, undefined, 'login'],
    [{ code: '2' }, undefined, 'login'],
    [{ code: '403' }, undefined, 'forbidden'],
    [{ code: 200, data: card() }, 403, 'forbidden'],
    [{ code: 404, errorCode: 'CHARACTER_TOPIC_NOT_FOUND' }, undefined, 'unavailable'],
    [{ code: 426 }, undefined, 'upgrade'],
    [{ code: 400 }, undefined, 'missing-param'],
    [{ code: 500, msg: 'SECRET_STACK' }, undefined, 'error'],
    [{ reason: 'sessionChanged' }, undefined, 'session-changed'],
  ]) {
    const h = harness(); h.open();
    if (status) h.requests[0].successStatusAbnormal(response, status);
    else h.requests[0].success(response);
    assert.equal(h.page.data.state, state);
    assert.equal(h.page.data.card, null);
    assert.doesNotMatch(JSON.stringify(h.page.data), /SECRET_/);
  }
});

test('guest does not send authenticated reads or silently create a user; explicit login resumes read', async () => {
  const h = harness(); h.account('', ''); h.open();
  assert.equal(h.requests.length, 0);
  assert.equal(h.page.data.state, 'login');
  h.account('72'); h.allowLogin(); h.page.onStateAction();
  await Promise.resolve();
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].method, 'GET');
});

test('invalid and rounded theme IDs are rejected without issuing requests', () => {
  for (const topicId of ['', '0', '1e3', '-1', '01', '9223372036854775808', 9007199254740992, {}, true]) {
    const h = harness(); h.open({ topicId });
    assert.equal(h.requests.length, 0);
    assert.equal(h.page.data.state, 'missing-param');
  }
  const h = harness(); const topicId = '9223372036854775807'; h.open({ topicId });
  assert.equal(h.requests[0].data.topicId, topicId);
  h.requests[0].success({ code: 200, data: card({ topicId }) });
  assert.equal(h.page.data.card.topicId, topicId);
});

test('mismatched theme, unsafe response IDs and malformed attributes cannot become a card', () => {
  for (const value of [card({ topicId: '902' }), card({ topicId: 9007199254740992 }),
    card({ created: 'true' }), card({ created: false }), card({ attributes: null }),
    card({ attributes: [attribute({ value: '6' })] }), card({ attributes: [attribute(), attribute()] })]) {
    const h = harness(); h.open(); h.requests[0].success({ code: 200, data: value });
    assert.equal(h.page.data.card, null);
    assert.equal(h.page.data.state, 'error');
  }
});

test('switching account or token during an in-flight read clears the result and requires fresh context', () => {
  for (const id of ['72', '71']) {
    const h = harness(); h.open(); const old = h.requests[0];
    h.account(id, 'new-session'); old.success({ code: 200, data: card() });
    assert.equal(h.page.data.card, null);
    assert.equal(h.page.data.state, 'session-changed');
    h.page.onShow(); const latest = h.requests[1];
    old.fail({ code: 403 });
    assert.equal(h.page.data.state, 'loading');
    latest.success({ code: 200, data: card({ attributes: [attribute({ value: 2 })] }) });
    assert.equal(h.page.data.card.attributes[0].value, 2);
  }
});

test('switching topic and retrying isolate late success, failure and abnormal callbacks', () => {
  const h = harness(); h.open(); const first = h.requests[0];
  h.page.onLoad({ topicId: '902' }); h.page.onShow(); const second = h.requests[1];
  first.success({ code: 200, data: card() });
  assert.equal(h.page.data.card, null);
  second.success({ code: 200, data: card({ topicId: '902' }) });
  first.fail({ code: 403 }); first.successStatusAbnormal({ code: 401 }, 401);
  assert.equal(h.page.data.card.topicId, '902');
  h.page.loadCharacter(); const third = h.requests[2];
  h.page.loadCharacter(); const fourth = h.requests[3];
  fourth.fail({ errMsg: 'offline' }); third.success({ code: 200, data: card({ topicId: '902' }) });
  assert.equal(h.page.data.state, 'error');
  assert.equal(h.page.data.card, null);
});

test('hide clears rendered personal data; hidden and unloaded pages reject all late writes', () => {
  const h = harness(); h.open(); h.requests[0].success({ code: 200, data: card() });
  h.page.loadCharacter(); const pending = h.requests[1];
  h.page.onHide(); assert.equal(h.page.data.card, null);
  const before = h.writes.length;
  pending.success({ code: 200, data: card() }); pending.fail({ code: 403 });
  assert.equal(h.writes.length, before);
  h.page.onShow(); const last = h.requests[2]; h.page.onUnload();
  const unloaded = h.writes.length;
  last.success({ code: 200, data: card() }); last.successStatusAbnormal({ code: 404 }, 404);
  assert.equal(h.writes.length, unloaded);
});

// Participation entry scenarios execute the shared Component in participation-result-entry.test.js.

test('page markup uses real cy components, separate state text and no attribute editing controls', () => {
  const wxml = fs.readFileSync(path.join(ROOT, PAGE + '.wxml'), 'utf8');
  const config = JSON.parse(fs.readFileSync(path.join(ROOT, PAGE + '.json'), 'utf8'));
  for (const [name, target] of Object.entries(config.usingComponents)) {
    assert.ok(fs.existsSync(path.join(ROOT, target.slice(1) + '.js')), name + ' exists');
  }
  assert.match(wxml, /角色模板/);
  assert.match(wxml, /模板暂无可展示属性/);
  assert.match(wxml, /item.scopeText/);
  assert.match(wxml, /card.rpg.clocks/);
  assert.doesNotMatch(wxml, /<(input|slider|textarea|switch)\b|bindchange|冻结定义/);
  const css = fs.readFileSync(path.join(ROOT, PAGE + '.wxss'), 'utf8');
  assert.doesNotMatch(css, /#[0-9a-f]{3,8}\b|rgba?\(/i);
});


test('partial frozen definitions never invent persistence metadata and legacy live definitions are explicit', () => {
  const h = harness(); h.open();
  h.requests[0].success({ code: 200, data: card({ definitionSource: 'SESSION_SNAPSHOT_PARTIAL',
    attributes: [attribute({ displayType: null, persistScope: null })] }) });
  assert.equal(h.page.data.state, 'ready');
  assert.equal(h.page.data.card.attributes[0].scopeText, '保留范围未记录');
  assert.match(h.page.data.card.definitionNote, /开始时的配置/);
  assert.equal(character.projectCard(card({ attributes: [attribute({ persistScope: null })] }), '901'), null);
  assert.match(character.projectCard(card({ definitionSource: 'LEGACY_LIVE' }), '901').definitionNote, /可能随主题更新/);
});

test('range boundaries and a renamed label preserve the server state key', () => {
  for (const value of [-10000, 10000]) {
    const h = harness(); h.open();
    h.requests[0].success({ code: 200, data: card({ attributes: [attribute({
      stateKey: 'counter.hp', label: '耐力', min: -10000, max: 10000, value,
    })] }) });
    assert.equal(h.page.data.card.attributes[0].stateKey, 'counter.hp');
    assert.equal(h.page.data.card.attributes[0].label, '耐力');
    assert.equal(h.page.data.card.attributes[0].value, value);
  }
  for (const extra of [{ value: -1 }, { value: 11 }, { min: 11 }, { value: 1.5 }]) {
    const h = harness(); h.open();
    h.requests[0].success({ code: 200, data: card({ attributes: [attribute(extra)] }) });
    assert.equal(h.page.data.state, 'error');
    assert.equal(h.page.data.card, null);
  }
});

test('reentering the same topic rejects a late previous-run card and duplicate callbacks', () => {
  const h = harness(); h.open(); const previousRun = h.requests[0];
  h.page.onHide(); h.page.onShow(); const currentRun = h.requests[1];
  currentRun.success({ code: 200, data: card({ attributes: [attribute({ value: 2 })] }) });
  previousRun.success({ code: 200, data: card({ attributes: [attribute({ value: 9 })] }) });
  currentRun.success({ code: 200, data: card({ attributes: [attribute({ value: 8 })] }) });
  currentRun.fail({ code: 500 });
  assert.equal(h.page.data.state, 'ready');
  assert.equal(h.page.data.card.attributes[0].value, 2);
});

test('partial card missing a numeric boundary is rejected without inferring live defaults', () => {
  for (const field of ['value', 'min', 'max']) {
    const row = attribute({ displayType: null, persistScope: null }); delete row[field];
    const h = harness(); h.open();
    h.requests[0].success({ code: 200, data: card({ definitionSource: 'SESSION_SNAPSHOT_PARTIAL', attributes: [row] }) });
    assert.equal(h.page.data.state, 'error');
    assert.equal(h.page.data.card, null);
    assert.equal(h.requests.length, 1, 'no live-definition request may fill a partial snapshot');
  }
});
