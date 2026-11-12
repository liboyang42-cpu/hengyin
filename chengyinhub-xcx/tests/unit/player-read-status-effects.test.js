'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequestClient } = require('../../utils/transport/request-client.js');
const ROOT = path.resolve(__dirname, '../..');
const plain = value => JSON.parse(JSON.stringify(value));
const settle = () => new Promise(resolve => setImmediate(resolve));

// Execute the real App predicates, transport, request wrapper and caller methods.
// Only platform I/O and session renewal are controlled by this harness.
function harness(guest = false, renew = false) {
  const requests = [], callbacks = [], toasts = [], native = [], timers = new Map();
  let app, logins = 0, prompts = 0, timerId = 0;
  const wx = {
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showModal: value => native.push(['modal', value]),
    navigateTo: value => native.push(['navigate', value]),
    reLaunch: value => native.push(['reLaunch', value]),
  };
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'app.js'), 'utf8'), {
    App: value => { app = value; }, wx, console, setTimeout, clearTimeout,
    require: id => id === './utils/toast.js' ? Object.assign(() => {}, { hide() {} }) : require(path.join(ROOT, id)),
  });
  app.getUserID = () => guest ? '' : '11';
  app.getAuthorization = () => guest ? '' : 'fixture-auth';
  app.getSession = () => ({ getRevision: () => 0 });
  app._loginEpoch = 0;
  const client = createRequestClient({
    wxRequest: options => { requests.push(options); return { abort() {} }; },
    getBaseUrl: () => 'https://fixture', getUserID: app.getUserID,
    getAuthorization: app.getAuthorization, getSessionRevision: () => 0,
    reLogin: callback => { logins++; callback(renew); },
    promptLogin: () => { prompts++; return Promise.resolve(false); },
    showToast: message => toasts.push(message), hideToast() {},
    getErrorMessage: app.getRequestErrorMessage.bind(app),
    shouldAutoToast: app.shouldAutoToastRequestError.bind(app), isPlayerView: () => true,
  });
  app.sendRequest = options => {
    const wrapped = { ...options };
    for (const key of ['success', 'fail', 'successStatusAbnormal']) {
      if (typeof options[key] === 'function') wrapped[key] = (...args) => { callbacks.push(key); return options[key](...args); };
    }
    return client.send(wrapped);
  };
  const context = {
    wx, getApp: () => app, console, Date, Behavior: value => value,
    setTimeout, clearTimeout,
    setInterval(fn, delay) { const id = ++timerId; timers.set(id, { fn, delay }); return id; },
    clearInterval(id) { timers.delete(id); },
  };
  // Required by real CommonJS dependencies, matching existing player runtime tests.
  global.Behavior = value => value;
  global.getApp = () => app;
  global.wx = wx;
  function load(relative, kind) {
    let spec;
    const filename = path.join(ROOT, relative);
    vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
      ...context, [kind]: value => { spec = value; },
      require: id => require(path.resolve(path.dirname(filename), id)),
    });
    const instance = {
      ...(kind === 'Page' ? spec : spec.methods), data: plain(spec.data || {}),
      setData(patch) {
        for (const [key, value] of Object.entries(patch)) {
          const parts = key.split('.'); let target = this.data;
          for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {});
          target[parts.at(-1)] = value;
        }
      },
      triggerEvent(name, detail) { native.push(['event', name, detail]); },
    };
    return { instance, spec };
  }
  function reply(request, code, msg, data, statusCode = 200) {
    const response = { statusCode, data: { code, msg, ...(data === undefined ? {} : { data }) } };
    request.success(response);
    if (request.complete) request.complete(response);
  }
  return { app, requests, callbacks, toasts, native, timers, load, reply,
    effects: () => ({ callbacks: [...callbacks], toasts: [...toasts], native: plain(native), logins, prompts, requests: requests.length }) };
}

async function encounter(code, guest = false, renew = false) {
  const h = harness(guest, renew);
  const { instance: page } = h.load('pages/play/index.js', 'Page');
  page.data = { ...page.data, topicId: 19, activityId: 9, nodes: [{ nodeId: 29, done: false }],
    sheet: { show: true, node: { nodeId: 29 } } };
  page._isPlayAccountCurrent = () => true;
  const before = plain(page.data);
  page._probeJourneyCheck({ nodeId: 29 });
  assert.equal(h.requests[0].url, 'https://fixture/api/play/encounter');
  h.reply(h.requests[0], code, code === 409 ? '这一局还没开始' : '请先登录');
  await settle();
  assert.deepEqual(plain(page.data), before, 'failed optional probe must not open or close any gameplay surface');
  return h;
}

test('encounter 500 and not-started 409 are quiet, but 401 changes authenticated callback and renewal behavior', async () => {
  const old = await encounter(500);
  const conflict = await encounter(409);
  assert.deepEqual(conflict.effects(), old.effects());
  assert.deepEqual(old.effects(), { callbacks: ['success'], toasts: [], native: [], logins: 0, prompts: 0, requests: 1 });
  const auth = await encounter(401);
  assert.deepEqual(auth.effects(), { callbacks: ['fail'], toasts: [], native: [], logins: 1, prompts: 0, requests: 1 });
  const retry = await encounter(401, false, true);
  assert.equal(retry.effects().logins, 1);
  assert.equal(retry.requests.length, 2, 'new auth code retries the actual encounter GET');
  assert.equal(retry.requests[1].url, retry.requests[0].url);
  retry.reply(retry.requests[1], 500, '请先登录');
  await settle();
  assert.deepEqual(retry.callbacks, ['success']);
});

test('encounter 401 also switches guest reads from success to fail without a login prompt', async () => {
  const old = await encounter(500, true);
  const auth = await encounter(401, true);
  assert.deepEqual(old.callbacks, ['success']);
  assert.deepEqual(auth.callbacks, ['fail']);
  assert.equal(auth.effects().logins, 0);
  assert.equal(auth.effects().prompts, 0);
});

function orderCompletion(code, msg) {
  const h = harness();
  const { instance: order } = h.load('subpackageMember/components/cy/scene-member-order-detail/index.js', 'Component');
  order.data.completion = { show: true, stale: true };
  order.data.info = { id: '71', purchaseKind: 3, paymentStatus: 2 };
  const info = plain(order.data.info);
  order.loadCompletion(info);
  assert.equal(h.requests[0].url, 'https://fixture/api/registration/explore-completion');
  h.reply(h.requests[0], code, msg);
  assert.equal(order.data.completion.show, false);
  assert.deepEqual(plain(order.data.info), info, 'optional failure retains the actual order');
  return { ...h.effects(), completion: plain(order.data.completion) };
}

for (const [code, msg] of [[400, '参数不合规'], [404, '报名记录不存在'], [403, '无权查看该报名信息'], [409, '该订单没有自由探索完局面']]) {
  test('order completion body ' + code + ' preserves legacy 500 callback, toast and hidden optional section', () => {
    const old = orderCompletion(500, msg);
    const next = orderCompletion(code, msg);
    assert.deepEqual(next, old);
    assert.deepEqual(next.callbacks, ['success']);
    assert.equal(next.toasts.length, 1);
    assert.deepEqual(next.native, []);
    assert.equal(next.logins, 0);
    assert.equal(next.requests, 1);
  });
}

function qrCompletion(code, msg) {
  const h = harness();
  const { instance: ticket, spec } = h.load('pages/play/components/scene-qr-ticket/index.js', 'Component');
  ticket.data.registrationId = '71';
  spec.lifetimes.attached.call(ticket);
  h.reply(h.requests[0], 200, '', { qrcodeUrl: 'qr-1', expiresAt: Date.now() + 300000 });
  assert.equal(ticket.data.state, 'ready');
  assert.equal(h.timers.size, 2, 'QR expiry clock and optional redemption poll are distinct');
  h.reply(h.requests[1], code, msg);
  assert.equal(ticket._redeemPoll, null);
  assert.equal(h.timers.size, 1);
  assert.equal(h.timers.get(ticket._timer).delay, 1000);
  assert.equal(ticket.data.qr, 'qr-1');
  const afterRejection = h.effects();
  ticket._expiresAt = Date.now() + 20000;
  h.timers.get(ticket._timer).fn();
  assert.equal(h.requests.length, 3, 'independent QR clock still requests silent renewal');
  assert.equal(h.requests[2].url, 'https://fixture/api/verify/dyncode/issue');
  h.reply(h.requests[2], 500, '续签失败');
  assert.equal(ticket.data.qr, 'qr-1');
  assert.equal(ticket.data.state, 'ready');
  assert.ok(ticket._retryAt > Date.now());
  ticket._expiresAt = Date.now() - 1;
  h.timers.get(ticket._timer).fn();
  assert.equal(ticket.data.state, 'error');
  assert.equal(ticket.data.qr, '');
  assert.equal(h.timers.size, 0);
  return { afterRejection, expired: plain(ticket.data) };
}

for (const [code, msg] of [[400, '参数不合规'], [404, '报名记录不存在'], [403, '无权查看该报名信息'], [409, '该订单没有自由探索完局面']]) {
  test('QR completion body ' + code + ' matches 500 quiet stop without killing QR renewal or expiry', () => {
    const old = qrCompletion(500, msg);
    const next = qrCompletion(code, msg);
    assert.deepEqual(next, old);
    assert.deepEqual(next.afterRejection, { callbacks: ['success', 'success'], toasts: [], native: [], logins: 0, prompts: 0, requests: 2 });
  });
}
