'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const copy = value => JSON.parse(JSON.stringify(value));

// Page methods, invoice mixins, transport, message sanitizer, toast and modal are
// production modules. Only the platform boundary and unused App lifecycle imports
// are substituted; the App error-message and auto-toast methods execute unchanged.
function mount(relative, playerView = true) {
  let definition, page, appDefinition;
  const cache = new Map(), values = new Map(), requests = [];
  const effects = { toasts: [], modals: [], navigations: [], logins: 0, prompts: 0, retries: 0, refreshStops: 0 };
  const wx = {
    getStorageSync: key => values.has(key) ? copy(values.get(key)) : '',
    setStorageSync: (key, value) => values.set(key, copy(value)),
    removeStorageSync: key => values.delete(key),
    showToast: options => effects.toasts.push(options.title), hideToast() {},
    showModal: options => effects.modals.push(options.content),
    navigateTo: options => effects.navigations.push(options.url),
    redirectTo: options => effects.navigations.push(options.url),
    navigateBack: () => effects.navigations.push('back'),
    stopPullDownRefresh: () => effects.refreshStops++,
  };
  const app = { getUserID: () => '7', getAuthorization: () => 'token-7' };
  const context = vm.createContext({ wx, console, getApp: () => app,
    getCurrentPages: () => page ? [page] : [], Page: value => { definition = value; },
    setTimeout: () => { effects.retries++; return 1; }, clearTimeout() {} });
  function load(file) {
    file = path.resolve(file);
    if (!path.extname(file)) file += '.js';
    if (cache.has(file)) return cache.get(file).exports;
    const mod = { exports: {} }; cache.set(file, mod);
    const source = fs.readFileSync(file, 'utf8');
    if (file.endsWith('.json')) { mod.exports = JSON.parse(source); return mod.exports; }
    const wrapper = vm.runInContext('(function(module,exports,require){' + source + '\n})', context, { filename: file });
    wrapper(mod, mod.exports, name => load(path.resolve(path.dirname(file), name)));
    return mod.exports;
  }
  const safeMessage = load(path.join(ROOT, 'utils/transport/safe-user-message.js'));
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'app.js'), 'utf8'), {
    App: value => { appDefinition = value; },
    require: name => name === './utils/transport/safe-user-message.js' ? safeMessage : {},
  }, { filename: path.join(ROOT, 'app.js') });
  app.getRequestErrorMessage = appDefinition.getRequestErrorMessage;
  app.shouldAutoToastRequestError = appDefinition.shouldAutoToastRequestError;
  const toast = load(path.join(ROOT, 'utils/toast.js'));
  const { createRequestClient } = load(path.join(ROOT, 'utils/transport/request-client.js'));
  const client = createRequestClient({
    wxRequest: options => { const row = { options, aborted: false }; requests.push(row); return { abort() { row.aborted = true; } }; },
    getBaseUrl: () => 'https://example.invalid',
    getUserID: app.getUserID, getAuthorization: app.getAuthorization,
    isPlayerView: () => playerView,
    getErrorMessage: app.getRequestErrorMessage,
    shouldAutoToast: app.shouldAutoToastRequestError,
    showToast: toast, hideToast: toast.hide,
    reLogin: callback => { effects.logins++; callback(false); },
    promptLogin: () => { effects.prompts++; return Promise.resolve(false); },
    scheduleRetry: () => effects.retries++,
  });
  app.sendRequest = options => client.send(options);
  load(path.join(ROOT, relative));
  page = Object.assign({}, definition, { data: copy(definition.data), setData(patch) { Object.assign(this.data, patch); } });
  function reply(suffix, body) {
    const row = requests.find(item => !item.done && item.options.url.endsWith(suffix));
    assert.ok(row, suffix + ' reached real wx.request boundary');
    row.done = true;
    const response = { statusCode: 200, data: copy(body) };
    row.options.success(response);
    row.options.complete(response);
  }
  function snapshot() {
    return copy({ data: page.data, effects, requests: requests.map(row => ({ url: row.options.url, aborted: row.aborted })) });
  }
  return { app, page, effects, requests, reply, snapshot, load };
}

function assertNoNewActions(h) {
  assert.deepEqual(h.effects.toasts, []);
  assert.deepEqual(h.effects.modals, []);
  assert.deepEqual(h.effects.navigations, []);
  assert.equal(h.effects.logins, 0);
  assert.equal(h.effects.prompts, 0);
  assert.equal(h.effects.retries, 0);
}

for (const playerView of [true, false]) {
  for (const hasLoaded of [false, true]) {
    test(`official mine 500 vs 403: identical refusal/refresh UI, player=${playerView}, snapshot=${hasLoaded}`, () => {
      function scenario(code) {
        const h = mount('pages/activity/official-mine/index.js', playerView);
        if (hasLoaded) Object.assign(h.page.data, { hasLoaded: true, events: [{ id: 7, title: '已有活动' }] });
        h.page.fetch();
        h.reply('/api/official/my-published', { code, msg: '无官方发布权限' });
        assertNoNewActions(h);
        assert.equal(h.requests.length, 1);
        assert.equal(h.effects.refreshStops, 1);
        assert.equal(h.page.data.loading, false);
        assert.equal(h.page.data.refreshing, false);
        if (!hasLoaded) {
          assert.equal(h.page.data.refused, true);
          assert.equal(h.page.data.error, '无官方发布权限');
        } else assert.equal(h.page.data.events[0].title, '已有活动');
        return h.snapshot();
      }
      assert.deepEqual(scenario(403), scenario(500));
    });
  }
  for (const hasSnapshot of [false, true]) {
    test(`official organizer fallback 500 vs 403: identical unknown state, player=${playerView}, snapshot=${hasSnapshot}`, () => {
      function scenario(code) {
        const h = mount('pages/activity/official-organizer/index.js', playerView);
        h.page._id = '7'; h.page._loadSeq = 1;
        if (hasSnapshot) Object.assign(h.page.data, { phase: 'ready', view: { title: '已有活动' } });
        h.page._loadEventState(1, hasSnapshot);
        h.reply('/api/official/events/7', { code: 500, msg: '活动不存在' });
        h.reply('/api/official/my-published', { code, msg: '无官方发布权限' });
        assertNoNewActions(h);
        assert.equal(h.requests.length, 2);
        assert.equal(h.page.data.phase, 'ready');
        assert.equal(h.page.data.view.stateKnown, false);
        return h.snapshot();
      }
      assert.deepEqual(scenario(403), scenario(500));
    });
  }
}

for (const [relative, endpoint, options] of [
  ['invoice', 'list', {}], ['invoice-titles', 'titles', {}],
  ['invoice-apply', 'titles', {}], ['invoice-title-edit', 'titles', {}],
  ['invoice-title-edit', 'titles', { id: '17' }],
]) {
  test(`${relative} ${JSON.stringify(options)} feature-off 500 vs 503: same unavailable state and guards`, () => {
    function scenario(code) {
      const h = mount('subpackageMember/' + relative + '/index.js');
      h.page.onLoad(options); h.page.onShow();
      h.reply('/api/invoice/' + endpoint, { code, msg: '发票功能暂未开放', errorCode: 'INVOICE_FEATURE_OFF' });
      assertNoNewActions(h);
      assert.equal(h.page.data.unavailable, true);
      assert.equal(h.page.data.login, false);
      assert.equal(h.page.data.error, '');
      assert.equal(h.page.data.loading, false);
      assert.equal(h.page.ready(), false);
      assert.equal(h.page._workflow, null);
      const before = h.requests.length;
      h.page.read(endpoint, {}, value => value, () => assert.fail('feature-off cannot reopen reads'));
      assert.equal(h.requests.length, before);
      return h.snapshot();
    }
    assert.deepEqual(scenario(503), scenario(500));
  });
}

// The controller's feature guard is shared by writes and recovery endpoints too.
// The immutable errorCode must continue to outrank numeric status classification.
for (const endpoint of ['titles', 'titles/save', 'titles/save-request', 'titles/request', 'titles/delete',
  'orders/eligible', 'apply', 'list', 'detail', 'request', 'cancel']) {
  test(`shared invoice guard ${endpoint}: 500 vs 503 keeps definitive feature-off classification`, () => {
    function scenario(code) {
      const h = mount('subpackageMember/invoice/index.js');
      const invoice = h.load(path.join(ROOT, 'utils/user-invoice.js'));
      let result;
      invoice.send(h.app, endpoint, { method: 'POST', autoErrorToast: false, hideLoading: true,
        success: res => { result = invoice.classify(res, 200, () => assert.fail('feature-off must not decode')); } });
      h.reply('/api/invoice/' + endpoint, { code, msg: '发票功能暂未开放', errorCode: 'INVOICE_FEATURE_OFF' });
      assert.equal(result.status, 'failed');
      assert.equal(result.response.errorCode, 'INVOICE_FEATURE_OFF');
      assert.equal(result.response.msg, '发票功能暂未开放');
      assertNoNewActions(h);
      assert.equal(h.requests.length, 1);
      return { status: result.status, message: result.response.msg, effects: h.snapshot() };
    }
    assert.deepEqual(scenario(503), scenario(500));
  });
}
