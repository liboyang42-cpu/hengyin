'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');
const component = 'pages/square/components/cy/post-compose/index.js';
const clone = value => JSON.parse(JSON.stringify(value));
const flush = () => new Promise(resolve => setImmediate(resolve));

// Component lifecycle and callbacks use real definitions; canonical mode also runs the real HTTP pipeline.
function mount(canonical = false) {
  let definition;
  const member = { value: 'member-A' }, requests = [], events = [], tips = [], drafts = [], intents = [], modals = [];
  let writes = 0;
  const app = {
    // Explicit current-account server-open fixture; a changed actor is not granted by default.
    globalData: { features: { communityPostRead: true, communityPostWrite: true } },
    _communityFeatureOwner: 'member-A', _communityFeatureAuthorization: 'Bearer fixture-A',
    getAuthorization: () => 'Bearer fixture-A',
    canUseCommunity(write) {
      return this._communityFeatureOwner === String(this.getUserID() || '')
        && this._communityFeatureAuthorization === this.getAuthorization()
        && this.globalData.features[write ? 'communityPostWrite' : 'communityPostRead'] === true;
    },
    getUserID: () => member.value, getAvatar: () => '', getNickname: () => '',
    getRequestErrorMessage: (res, fallback) => res && (res.msg || res.errMsg) || fallback,
    tips: text => tips.push(text),
    sendRequest(options) {
      const task = { aborted: false, abort() { this.aborted = true; } };
      requests.push({ options, task });
      return task;
    },
  };
  const storage = new Map();
  const wx = { getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,clone(value)),removeStorageSync:key=>storage.delete(key), getWindowInfo: () => ({ windowWidth: 375 }), showModal: options => modals.push(options) };
  vm.runInNewContext(fs.readFileSync(path.join(root, component), 'utf8'), {
    getApp: () => app, Component: value => { definition = value; }, wx, console,
    require(id) {
      if (id.includes('community-post-api')) return canonical ? require('../../utils/community-post-api') : require('../helpers/community-page-seam');
      if (id.includes('pro-editor-draft')) return {
        loadDraft(wx, identity, options) {
          drafts.push(['load', options.memberId]);
          return { status: 'ready', envelope: { formData: { contents: 'draft-' + options.memberId } } };
        },
        saveDraft(wx, payload) { drafts.push(['save', payload.memberId, payload.formData.contents]); },
        removeDraft(wx, identity, options) { drafts.push(['remove', options.memberId]); },
      };
      if (id.includes('publish-intent')) return {
        begin(wx, payload) { intents.push(['begin', payload.memberId]); return { key: 'stable-intent-key', previousUnknown: false }; },
        settle(wx, scope, owner, key, state) { intents.push(['settle', owner, key, state]); },
        discard(wx, scope, owner, key) { intents.push(['discard', owner, key]); },
      };
      if (id.includes('/toast')) return text => tips.push(text);
      if (id.includes('nav-safe-area')) return { resolveMenuChrome: () => ({ sheetTop: 60 }) };
      if (id.includes('datetime')) return { chinaParts: () => null };
      if (id.includes('location-manager')) return {};
      throw new Error('Unexpected module ' + id);
    },
  });
  const instance = Object.assign({
    data: Object.assign(clone(definition.data), { show: false, editPost: null, prefill: '' }),
    setData(patch) { writes++; Object.assign(this.data, patch); },
    triggerEvent(name, detail) { events.push([name, detail]); }, selectComponent: () => null,
  }, definition.methods);
  definition.lifetimes.attached.call(instance);
  const h = {
    instance, member, requests, events, tips, drafts, intents, modals,
    open(edit = null) { instance.data.show = true; instance.data.editPost = edit; definition.observers.show.call(instance, true); },
    close() { instance.data.show = false; definition.observers.show.call(instance, false); },
    detach() { definition.lifetimes.detached.call(instance); },
    hide() { definition.pageLifetimes.hide.call(instance); },
    resume() { definition.pageLifetimes.show.call(instance); },
    state() { return clone({ data: instance.data, events, tips, drafts, intents, writes }); },
  };
  h.open({ id: '56', contents: 'saved text', dataId: 9, dataType: 1 });
  return h;
}

const success = { code: 200, data: { lifecycle: 'PUBLISHED' } };
const callbacks = {
  success: request => request.success(success),
  business: request => request.success({ code: 409, msg: 'version conflict' }),
  network: request => request.fail({ errMsg: 'request:fail timeout' }),
  complete: request => request.complete(),
};

for (const transition of ['reopen-edit', 'reopen-create', 'switch-account', 'detach', 'hide', 'owner-changed']) {
  for (const [callback, invoke] of Object.entries(callbacks)) {
    test(transition + ': old ' + callback + ' cannot mutate the newer editor or show errors', () => {
      const h = mount();
      h.instance.onPublish();
      const old = h.requests[0].options;
      if (transition === 'detach') h.detach();
      else if (transition === 'hide') h.hide();
      else if (transition === 'owner-changed') h.member.value = 'member-B';
      else {
        h.close();
        if (transition === 'switch-account') h.member.value = 'member-B';
        h.open(transition === 'reopen-create' ? null : { id: '57', contents: 'new text' });
        if (transition === 'switch-account') h.open({ id: '57', contents: 'new text' });
        h.instance.onInput({ detail: { value: 'new unsaved text' } });
        h.instance.onPublish();
      }
      const before = h.state();
      invoke(old);
      assert.deepEqual(h.state(), before);
    });
  }
}

for (const transition of ['close', 'cancel', 'detach', 'new-record']) {
  test(transition + ': stop the previous task and allow a fresh operation', () => {
    const h = mount();
    h.instance.onPublish();
    if (transition === 'close') h.close();
    if (transition === 'cancel') h.instance.onCancel();
    if (transition === 'detach') h.detach();
    if (transition === 'new-record') h.instance.applyEdit({ id: '57', contents: 'new text' });
    assert.equal(h.requests[0].task.aborted, true);
    if (transition !== 'detach') assert.equal(h.instance.data.submitting, false);
  });
}

test('switch account: preserve the original draft, reject old input/prefill and load only the new owner', () => {
  const h = mount();
  h.close(); h.open();
  h.instance.onInput({ detail: { value: 'original private draft' } });
  h.member.value = 'member-B';
  h.instance.onInput({ detail: { value: 'new actor input' } });
  assert.equal(h.instance.data.content, 'original private draft');
  h.instance.data.prefill = 'old inline text';
  h.open();
  assert.ok(h.drafts.some(call => call[0] === 'save' && call[1] === 'member-A' && call[2] === 'original private draft'));
  assert.equal(h.instance.data.content, '');
  assert.ok(h.events.some(event => event[0] === 'close'));
  h.instance.data.prefill = ''; h.open();
  assert.equal(h.instance.data.content, 'draft-member-B');
});

test('cancel creation: unknown intent belongs to the captured owner, late success cannot remove either draft', () => {
  const h = mount(); h.close(); h.open(); h.instance.onPublish();
  h.member.value = 'member-B'; h.close();
  assert.ok(h.intents.some(call => call[0] === 'settle' && call[1] === 'member-A' && call[3] === 'unknown'));
  const before = h.state(); h.requests[0].options.success(success);
  assert.deepEqual(h.state(), before);
  assert.equal(h.drafts.some(call => call[0] === 'remove'), false);
});

for (const editing of [true, false]) {
  test((editing ? 'edit' : 'create') + ': saving an earlier body retains newer unsaved text', () => {
    const h = mount(); if (!editing) { h.close(); h.open(); }
    h.instance.onPublish();
    h.instance.onInput({ detail: { value: 'newer unsaved body' } });
    h.requests[0].options.success(success); h.requests[0].options.complete();
    assert.equal(h.instance.data.content, 'newer unsaved body');
    assert.equal(h.instance.data.submitting, false);
    assert.ok(h.events.some(event => event[0] === 'published' && event[1].keepOpen === true));
    assert.equal(h.events.some(event => event[0] === 'close'), false);
    if (!editing) assert.ok(h.drafts.some(call => call[0] === 'save' && call[2] === 'newer unsaved body'));
  });
}

test('current request: single flight, one error, retained body and successful retry', () => {
  const h = mount(); h.instance.onPublish(); h.instance.onPublish();
  assert.equal(h.requests.length, 1);
  h.requests[0].options.success({ code: 409, msg: 'version conflict' }); h.requests[0].options.complete();
  assert.deepEqual(h.tips, ['version conflict']);
  assert.equal(h.instance.data.content, 'saved text');
  h.instance.onPublish(); h.requests[1].options.success(success); h.requests[1].options.complete();
  assert.equal(h.instance.data.content, '');
  assert.equal(h.instance.data.submitting, false);
  assert.deepEqual(h.events.map(event => event[0]), ['published', 'close']);
});

test('hidden page: cancel the old task and retain the edit for retry on return', () => {
  const h = mount(); h.instance.onPublish(); h.hide();
  assert.equal(h.requests[0].task.aborted, true);
  h.resume(); h.instance.onPublish();
  assert.equal(h.requests.length, 2);
  assert.equal(h.requests[1].options.data.contents, 'saved text');
});

test('create: a newly selected attachment also survives the previous publication result', () => {
  const h = mount(); h.close(); h.open(); h.instance.onPublish();
  h.instance.setData({ picList: ['new-image'] });
  h.requests[0].options.success(success);
  assert.deepEqual(h.instance.data.picList, ['new-image']);
  assert.ok(h.events.some(event => event[0] === 'published' && event[1].keepOpen));
  assert.equal(h.events.some(event => event[0] === 'close'), false);
});

test('real canonical business failure: the current editor gives one visible error and preserves content', async () => {
  const h = mount(true); h.instance.onPublish(); await flush();
  h.requests[0].options.success({ code: 503, msg: 'guideline unavailable' }); await flush();
  assert.deepEqual(h.tips, ['guideline unavailable']);
  assert.equal(h.instance.data.content, 'saved text');
  assert.equal(h.instance.data.submitting, false);
});

for (const pending of ['guidelines', 'modal']) {
  test('real canonical pipeline: close during ' + pending + ' prevents every subsequent write', async () => {
    const h = mount(true); h.instance.onPublish(); await flush();
    assert.equal(h.requests[0].options.url, '/api/v1/community/guidelines/active');
    const guideline = { code: 200, data: { id: 1, content_json: JSON.stringify({ rules: [{ title: 'Test rule' }] }) } };
    if (pending === 'modal') { h.requests[0].options.success(guideline); await flush(); assert.equal(h.modals.length, 1); }
    h.close(); const before = h.state();
    if (pending === 'modal') h.modals[0].success({ confirm: true });
    else h.requests[0].options.success(guideline);
    await flush();
    assert.equal(h.requests.length, 1, 'must not acknowledge, create, save or publish after close');
    if (pending === 'guidelines') assert.equal(h.modals.length, 0, 'a closed editor must not open an old guideline modal');
    assert.deepEqual(h.state(), before);
  });
}

for (const route of ['detail']) { // 2026-10-29:广场列表页已删
  test(route + ' parent: refresh the real view and preserve the newer open editor', () => {
    let definition;
    vm.runInNewContext(fs.readFileSync(path.join(root, 'pages/square/' + route + '/index.js'), 'utf8'), {
      Page: value => { definition = value; }, getApp: () => ({ globalData: {} }), wx: {},
      require: id => id.includes('community-post-api') ? { createClient: () => ({}) } : Object.assign(() => {}, { remixTopicTemplate() {} }),
    });
    let refreshes = 0, dirties = 0;
    const page = { data: { composeShow: true, composeEdit: { id: 56 }, composePrefill: 'newer text', valCont: 'inline draft', canSubmit: true },
      setData(patch) { Object.assign(this.data, patch); }, refreshList() { refreshes++; }, getData() { refreshes++; }, markListDirty() { dirties++; } };
    const before = clone(page.data);
    definition.onComposePublished.call(page, { detail: { keepOpen: true } });
    assert.deepEqual(page.data, before);
    assert.equal(refreshes, 1); if (route === 'detail') assert.equal(dirties, 1);
    definition.onComposePublished.call(page);
    if (route === 'detail') assert.equal(page.data.composeShow, false);
    assert.equal(page.data.composeEdit, null);
    assert.equal(refreshes, 2);
  });
}
