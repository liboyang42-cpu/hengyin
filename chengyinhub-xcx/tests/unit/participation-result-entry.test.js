const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const SCENE = 'subpackageMember/components/scene-member-participation-detail/index';
const SHELL = 'subpackageMember/mycanyuinfo/mycanyuinfo';
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const clone = value => JSON.parse(JSON.stringify(value));
const topic = extra => ({ id: '81', memberId: '71', ownerType: 1, ownerId: '901', cmsTopic: { id: '901' }, ...extra });
const activity = extra => topic({ ownerType: 2, ownerId: '88', cmsTopic: null, cmsActivity: { id: '88', topicId: '901' }, ...extra });

function mount(shell = false) {
  let componentDef, pageDef, user = '71', token = 'token-a', closeHook;
  const requests = [], events = [], writes = [];
  const app = { globalData: {}, getUserID: () => user, getAuthorization: () => token,
    sendRequest: options => { requests.push(options); return { abort() {} }; } };
  function load(rel) {
    const filename = path.join(ROOT, rel + '.js');
    vm.runInNewContext(read(rel + '.js'), {
      getApp: () => app, Component: def => { componentDef = def; }, Page: def => { pageDef = def; },
      getCurrentPages: () => [{}, {}], console,
      wx: { navigateTo: options => events.push(['navigate', options.url]),
        navigateBack: () => events.push(['back']), redirectTo: () => events.push(['redirect']) },
      require: id => id.endsWith('merchant-theme.js') ? { merchantPageShow() {}, merchantPageRestore() {} }
        : require(path.resolve(path.dirname(filename), id)),
    }, { filename });
  }
  load(SCENE);
  const component = Object.assign({}, componentDef.methods, { data: clone(componentDef.data),
    setData(patch) { writes.push(clone(patch)); Object.assign(this.data, clone(patch)); },
    triggerEvent(name) {
      events.push([name]);
      if (closeHook) closeHook();
      if (shell) {
        const binding = read(SHELL + '.wxml').match(new RegExp('bind:' + name + '="(\\w+)"'));
        if (binding) page[binding[1]]();
      } else if (name === 'close') componentDef.lifetimes.detached.call(this);
    },
  });
  let page;
  if (shell) {
    load(SHELL);
    page = Object.assign({}, pageDef, { data: clone(pageDef.data), setData(patch) { Object.assign(this.data, patch); } });
  }
  return { component, requests, events, writes,
    boot(id = '81') {
      component.data.recordId = shell ? '' : id;
      componentDef.lifetimes.attached.call(component);
      if (shell) { page.onLoad({ id }); page.onShow(); componentDef.observers.recordId.call(component, page.data.id); }
      componentDef.observers.recordId.call(component, id);
      componentDef.pageLifetimes.show.call(component);
    },
    respond(data) { requests.at(-1).success({ code: 200, data }); },
    click(label) {
      const tag = read(SCENE + '.wxml').match(new RegExp('<cy-card[^>]*bindtap="(\\w+)"[^>]*>\\s*<view>' + label + '</view>'));
      assert.ok(tag, label + ' must have a visible card binding');
      assert.ok(component.data.resultTopicId, 'card condition is true');
      component[tag[1]]();
    },
    account(id, auth = 'token-b') { user = id; token = auth; },
    onClose(callback) { closeHook = callback; },
    hide() { componentDef.pageLifetimes.hide.call(component); },
    show() { componentDef.pageLifetimes.show.call(component); },
    detach() { componentDef.lifetimes.detached.call(component); },
    record(id) { componentDef.observers.recordId.call(component, id); },
  };
}

test('normal participation sheet and deep-link shell expose both entries using one existing registration request', () => {
  const normal = read('subpackageMember/mycanyu/mycanyu.wxml');
  assert.match(normal, /<cy-scene-member-participation-detail[^>]*record-id="\{\{sceneCurrent.params.id\}\}"/);
  assert.match(read(SHELL + '.wxml'), /<cy-scene-member-participation-detail[^>]*record-id="\{\{id\}\}"/);
  assert.doesNotMatch(read(SHELL + '.js'), /sendRequest|loadCharacterEntry/);
  assert.doesNotMatch(read(SHELL + '.wxml'), /bindtap="goCharacter"|characterTopicId/);
  for (const shell of [false, true]) for (const info of [topic(), activity()]) {
    for (const [label, route] of [['我的角色卡', '/subpackageMember/character/index?topicId=901'],
      ['定向成绩', '/subpackageMember/orient-score/index?topicId=901&activityId=' + (info.ownerType === 1 ? '0' : '88')]]) {
      const h = mount(shell); h.boot();
      assert.equal(h.requests.length, 1);
      assert.equal(h.requests[0].url, '/api/registration/info');
      assert.equal(h.requests[0].method, 'POST');
      assert.equal(h.requests[0].data.id, '81');
      h.respond(info); h.click(label);
      assert.deepEqual(h.events, [['close'], ['navigate', route]], 'close precedes navigation, shell must not race navigateBack');
      assert.equal(h.requests.length, 1, 'entry click never fetches duplicate registration or creates a character');
      h.component.goScore(); assert.equal(h.events.length, 2, 'repeated tap cannot navigate twice');
    }
  }
});

test('missing, mismatched, unsafe and non-owned registration relationships fail closed with visible retry', () => {
  for (const info of [topic({ id: '82' }), topic({ memberId: '72' }), topic({ memberId: null }),
    topic({ ownerType: 3 }), topic({ ownerType: true }), topic({ ownerId: '902' }),
    topic({ cmsTopic: null }), topic({ topicId: '902' }), topic({ ownerId: 9007199254740992, cmsTopic: { id: 9007199254740992 } }),
    activity({ cmsActivity: { id: '89', topicId: '901' } }),
    activity({ cmsActivity: { id: '88', topicId: '9223372036854775808' } }), activity({ cmsActivity: null }),
    { id: '81', memberId: '71', topicId: '901' }]) {
    const h = mount(); h.boot(); h.respond(info);
    assert.equal(h.component.data.resultTopicId, '');
    assert.match(h.component.data.resultEntryError, /无法确认.*重新加载/);
    h.component.goCharacter(); h.component.goScore();
    assert.equal(h.events.length, 0); assert.equal(h.requests.length, 1);
  }
  for (const id of ['0', '01', '-1', '1e3', '9223372036854775808', 9007199254740992]) {
    const h = mount(); h.boot(id);
    if (h.requests.length) h.respond(topic({ id }));
    assert.equal(h.component.data.resultTopicId, '');
    h.component.goScore(); assert.equal(h.events.length, 0);
  }
  assert.match(read(SCENE + '.wxml'), /<cy-error[^>]*resultEntryError[^>]*bind:retry="onErrorAction"/);
  const h = mount(); h.boot(); h.respond(topic({ ownerId: 'wrong' }));
  h.component.onErrorAction(); assert.equal(h.requests.length, 2);
  h.respond(topic()); assert.equal(h.component.data.resultEntryError, ''); h.click('我的角色卡');
});

test('registrations without any theme relation hide both entries without an error card', () => {
  const plain = extra => ({ id: '81', memberId: '71', ownerType: 2, ownerId: '88', cmsTopic: null, ...extra });
  for (const info of [plain({ cmsActivity: { id: '88', topicId: null } }), plain({ cmsActivity: { id: '88' } }),
    plain({ topicId: 0, cmsActivity: { id: '88', topicId: 0 } }), plain({ ownerType: 3, ownerId: '5', cmsActivity: null }),
    plain({ ownerType: 2, memberId: '72', cmsActivity: { id: '88', topicId: null } })]) {
    const h = mount(); h.boot(); h.respond(info);
    assert.equal(h.component.data.loadState, 'ready');
    assert.equal(h.component.data.resultTopicId, '');
    assert.equal(h.component.data.resultEntryError, '', 'plain activity detail must not show the red entry error');
    assert.equal(h.requests.length, 1);
  }
});

test('exact Long relationship strings remain exact in score and character URLs', () => {
  const h = mount(); h.boot('9223372036854775807');
  h.respond(activity({ id: '9223372036854775807', ownerId: '9007199254740993',
    cmsActivity: { id: '9007199254740993', topicId: '9007199254740995' } }));
  h.click('定向成绩');
  assert.equal(h.events[1][1], '/subpackageMember/orient-score/index?topicId=9007199254740995&activityId=9007199254740993');
});

test('account or token switch before response, click or close cannot navigate old identity', () => {
  for (const [id, token] of [['72', 'token-b'], ['71', 'refreshed'], ['', '']]) {
    const h = mount(); h.boot(); h.respond(topic()); h.account(id, token);
    h.component.goCharacter(); h.component.goScore(); assert.deepEqual(h.events, []);
    assert.equal(h.component.data.resultTopicId, ''); assert.match(h.component.data.resultEntryError, /当前账号/);
    const pending = mount(); pending.boot(); pending.account(id, token); pending.respond(topic());
    assert.equal(pending.component.data.resultTopicId, ''); pending.component.goScore(); assert.deepEqual(pending.events, []);
  }
  const h = mount(); h.boot(); h.respond(topic()); h.onClose(() => h.account('72')); h.click('定向成绩');
  assert.deepEqual(h.events, [['close']], 'recheck identity after close event');
});

test('hide, detach, record changes and settled failures reject late callbacks and old clicks', () => {
  for (const stop of ['hide', 'detach']) {
    const h = mount(); h.boot(); const pending = h.requests[0]; h[stop](); const before = h.writes.length;
    pending.success({ code: 200, data: topic() }); pending.fail({ msg: 'late' });
    h.component.goCharacter(); h.component.goScore();
    assert.equal(h.writes.length, before); assert.deepEqual(h.events, []);
  }
  const h = mount(); h.boot(); const old = h.requests[0]; h.record('82');
  old.success({ code: 200, data: topic() }); assert.equal(h.component.data.resultTopicId, '');
  h.respond(topic({ id: '82' })); h.hide(); h.show();
  assert.equal(h.component.data.resultTopicId, '', 'on show waits for fresh data');
  h.respond(topic({ id: '82' })); h.click('我的角色卡');
  const failed = mount(); failed.boot(); const first = failed.requests[0]; first.fail({ msg: '网络失败' });
  first.success({ code: 200, data: topic() });
  assert.equal(failed.component.data.loadState, 'error'); assert.equal(failed.component.data.resultTopicId, '');
  failed.component.goScore(); assert.equal(failed.events.length, 0);
  failed.component.onErrorAction(); failed.respond(topic()); failed.click('定向成绩');
});

test('hide/show after account or token switch requires fresh ownership readback', () => {
  for (const [id, token] of [['72', 'token-b'], ['71', 'refreshed']]) {
    const h = mount(); h.boot(); const old = h.requests[0]; h.respond(topic());
    h.hide(); h.account(id, token); h.show();
    assert.equal(h.requests.length, 2);
    const before = h.writes.length;
    old.success({ code: 200, data: topic() }); old.fail({ msg: 'late' });
    assert.equal(h.writes.length, before);
    assert.equal(h.component.data.resultTopicId, '');
    h.component.goCharacter(); h.component.goScore(); assert.deepEqual(h.events, []);
    h.respond(topic({ memberId: id })); h.click('定向成绩');
    assert.deepEqual(h.events, [['close'], ['navigate', '/subpackageMember/orient-score/index?topicId=901&activityId=0']]);
  }
});

test('F3 entry preserves exact owned registration ID from both existing participation hosts', () => {
  const id = '9223372036854775807';
  for (const shell of [false, true]) for (const extra of [{ purchaseKind: 3 }, { cmsTopic: { id: '901', productType: 2 } }]) {
    const h = mount(shell); h.boot(id); h.respond(topic({ id, ...extra }));
    assert.equal(h.component.data.bundleRegistrationId, id);
    h.click('自由探索权益');
    assert.deepEqual(h.events, [['close'], ['navigate', '/subpackageMember/free-explore/index?registrationId=' + id]]);
    h.component.goBundle(); assert.equal(h.events.length, 2);
    assert.equal(h.requests.length, 1);
  }
});

test('F3 entry rejects foreign, non-F3, activity, stale and mismatched registrations', () => {
  for (const info of [topic(), activity({ purchaseKind: 3 }), topic({ purchaseKind: 3, memberId: '72' }),
    topic({ purchaseKind: 3, id: '82' }), topic({ purchaseKind: 3, id: 9007199254740992 })]) {
    const h = mount(); h.boot(); h.respond(info);
    assert.equal(h.component.data.bundleRegistrationId, ''); h.component.goBundle(); assert.deepEqual(h.events, []);
  }
  for (const stop of ['hide', 'detach']) {
    const h = mount(); h.boot(); h.respond(topic({ purchaseKind: 3 })); h[stop]();
    h.component.goBundle(); assert.deepEqual(h.events, []);
  }
  const h = mount(); h.boot(); h.respond(topic({ purchaseKind: 3 })); h.account('72');
  h.component.goBundle(); assert.deepEqual(h.events, []); assert.equal(h.component.data.bundleRegistrationId, '');
  const closing = mount(); closing.boot(); closing.respond(topic({ purchaseKind: 3 }));
  closing.onClose(() => closing.account('72')); closing.click('自由探索权益'); assert.deepEqual(closing.events, [['close']]);
});

test('integration registers delivered routes and mounts merchant desk with actual access and no redemption adapter', () => {
  const config = JSON.parse(read('app.json'));
  const member = config.subPackages.find(item => item.root === 'subpackageMember');
  assert.equal(member.pages.filter(item => item === 'free-explore/index').length, 1);
  for (const ext of ['js', 'json', 'wxml', 'wxss']) assert.ok(read('subpackageMember/free-explore/index.' + ext));
  const merchant = JSON.parse(read('pages/merchant/index/index.json'));
  assert.equal(merchant.usingComponents['cy-merchant-desk'], '/pages/merchant/components/desk/index');
  const tag = read('pages/merchant/index/index.wxml').match(/<cy-merchant-desk\b[^>]*\/>/)[0];
  assert.match(tag, /wx:if="\{\{merchantAccess.active\}\}"/);
  assert.match(tag, /access="\{\{merchantAccess\}\}"/);
  assert.doesNotMatch(tag, /redemption-ready|redemption-result|redeemrequest/);
});
