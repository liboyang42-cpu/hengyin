'use strict';
// T6 mini-program / SEC-14:登录后问 18 周岁(文案与 App 一致),已确认不再问,拒绝给朴素说明并退出登录。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const gate = require('../../utils/auth/age-gate.js');

const root = path.resolve(__dirname, '../..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');

function harness({ status, confirm = [], answers = [] } = {}) {
  const requests = [], shown = [], toasts = [];
  let cleared = 0;
  const app = {
    sendRequest(p) {
      requests.push(p);
      const body = p.url === gate.STATUS_URL ? status : confirm.shift();
      if (body === 'network') p.fail({});
      else p.success(body);
    },
    getSession() { return { clearSession() { cleared++; } }; },
  };
  const g = gate.createAgeGate({
    show(o) { shown.push(o); const a = answers.shift(); if (a === 'fail') o.fail(); else o.success({ confirm: a === 'adult', cancel: a !== 'adult' }); },
    toast(t) { toasts.push(t); },
  });
  return { g, app, requests, shown, toasts, cleared: () => cleared };
}

const ok = data => ({ code: 200, data });

test('已确认的账号不再问,不发确认', async () => {
  const h = harness({ status: ok({ confirmed: true }) });
  assert.equal(await h.g.check(h.app), true);
  assert.equal(h.shown.length, 0);
  assert.deepEqual(h.requests.map(r => r.url), ['/api/compliance/age/status']);
});

test('读不到状态不拦人(与 App 相同)', async () => {
  for (const status of ['network', { code: 500, msg: 'x' }, ok({}), ok({ confirmed: 'true' })]) {
    const h = harness({ status });
    assert.equal(await h.g.check(h.app), true);
    assert.equal(h.shown.length, 0);
  }
});

test('未确认:用 App 的文案问,选已满 18 → POST confirm {adult:true, source:sign_in}', async () => {
  const h = harness({ status: ok({ confirmed: false }), confirm: [ok({ confirmed: true })], answers: ['adult'] });
  assert.equal(await h.g.check(h.app), true);
  assert.equal(h.shown.length, 1);
  assert.equal(h.shown[0].title, '确认年龄');
  assert.equal(h.shown[0].content, '城瘾只面向 18 周岁及以上的玩家。请确认你的年龄。');
  assert.equal(h.shown[0].confirmText, '我已年满 18 周岁');
  assert.equal(h.shown[0].cancelText, '我未满 18 周岁');
  const post = h.requests[1];
  assert.equal(post.url, '/api/compliance/age/confirm'); assert.equal(post.method, 'POST');
  assert.deepEqual(post.data, { adult: true, source: 'sign_in' });
  assert.equal(post.header['content-type'], 'application/json');
  assert.equal(post.silentError, true);
  assert.equal(h.cleared(), 0);
});

test('选未满 18:服务端说明原文,退出登录,返回 false', async () => {
  const refusal = { code: 500, errorCode: 'AGE_UNDER_18', msg: '城瘾目前只面向 18 周岁及以上的玩家，暂时无法为你提供服务。' };
  const h = harness({ status: ok({ confirmed: false }), confirm: [refusal], answers: ['under', 'adult'] });
  assert.equal(await h.g.check(h.app), false);
  assert.deepEqual(h.requests[1].data, { adult: false, source: 'sign_in' });
  assert.equal(h.cleared(), 1);
  assert.equal(h.shown[1].content, refusal.msg);
  assert.equal(h.shown[1].showCancel, false);
});

test('选未满 18 且服务端不可达:仍拒绝,用本地同一句说明', async () => {
  const h = harness({ status: ok({ confirmed: false }), confirm: ['network'], answers: ['under', 'adult'] });
  assert.equal(await h.g.check(h.app), false);
  assert.equal(h.shown[1].content, gate.COPY.refused);
  assert.equal(h.cleared(), 1);
});

test('保存失败:提示再试一次并重新问;三次都失败先放行', async () => {
  const h = harness({ status: ok({ confirmed: false }), confirm: ['network', ok({ confirmed: true })], answers: ['adult', 'adult'] });
  assert.equal(await h.g.check(h.app), true);
  assert.deepEqual(h.toasts, ['没有保存成功，请再试一次。']);
  assert.equal(h.shown.length, 2);
  const h3 = harness({ status: ok({ confirmed: false }), confirm: ['network', 'network', 'network'], answers: ['adult', 'adult', 'adult'] });
  assert.equal(await h3.g.check(h3.app), true);
  assert.equal(h3.toasts.length, 3); assert.equal(h3.cleared(), 0);
});

test('原生弹窗回落:长按钮失败时换四字按钮再问', async () => {
  const h = harness({ status: ok({ confirmed: false }), confirm: [ok({ confirmed: true })], answers: ['fail', 'adult'] });
  assert.equal(await h.g.check(h.app), true);
  assert.equal(h.shown[1].confirmText, '已满18'); assert.equal(h.shown[1].cancelText, '未满18');
});

test('并发调用共享一次检查', async () => {
  const h = harness({ status: ok({ confirmed: true }) });
  const [a, b] = await Promise.all([h.g.check(h.app), h.g.check(h.app)]);
  assert.equal(a && b, true); assert.equal(h.requests.length, 1);
});

test('app.js:只在游客刚登录成功时检查;冷启动恢复缓存会话不检查', () => {
  const src = read('app.js');
  const body = src.slice(src.indexOf('firstLogin: function'), src.indexOf('hasCachedSession: function'));
  assert.match(body, /if \(!owner && !authorization\) that\.startAgeCheck\(\);/);
  assert.match(body, /awaitAgeCheck: function/);
});

function loginGuard({ age }) {
  const guard = require('../../utils/auth/require-login.js');
  const app = { id: '', token: '', getUserID() { return this.id; }, getAuthorization() { return this.token; },
    firstLogin() { this.id = '7'; this.token = 'NEW'; return Promise.resolve({ ok: true }); },
    awaitAgeCheck() { if (age === false) { this.id = ''; this.token = ''; } return Promise.resolve(age); } };
  return guard.createRequireLogin({ getApp: () => app, show: o => o.success({ confirm: true }) });
}

test('登录弹窗:年龄确认通过才恢复原动作;未确认(已退出)不执行', async () => {
  let resumed = 0;
  assert.equal(await loginGuard({ age: true }).withLogin({ reason: 'club' }, () => { resumed++; }), true);
  assert.equal(resumed, 1);
  assert.equal(await loginGuard({ age: false }).withLogin({ reason: 'club' }, () => { resumed++; }), false);
  assert.equal(resumed, 1);
});

test('文案与 App 的 String Catalog(zh-Hans)一致', () => {
  assert.equal(gate.COPY.confirm, '我已年满 18 周岁');
  assert.equal(gate.COPY.under, '我未满 18 周岁');
  assert.equal(gate.COPY.refused, '城瘾目前只面向 18 周岁及以上的玩家，暂时无法为你提供服务。');
  assert.equal(gate.COPY.saveFailed, '没有保存成功，请再试一次。');
});
