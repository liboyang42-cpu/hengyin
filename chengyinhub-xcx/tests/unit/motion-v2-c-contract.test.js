// 动效 v2 · lane v2-c:时刻 4 支付到账 / 时刻 5 半屏原地打勾 / 时刻 6 主题卡→详情。
// 只测呈现层的行为合同(阶段、时长、终态、失败不演),不碰任何支付/同意业务。
'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ROOT = path.resolve(__dirname, '../..');
const read = (f) => fs.readFileSync(path.join(ROOT, f), 'utf8');

const NEW_WXSS = [
  'components/cy/edge-glow/index.wxss',
  'pages/topic/components/cy/self-play-checkout/index.wxss',
  'pages/club/components/club-director-broadcast-sheet/index.wxss',
];

test('三个时刻没有新增 @keyframes(动画走 transition 与 this.animate)', () => {
  for (const f of NEW_WXSS) assert.ok(!/@keyframes/.test(read(f)), f);
  for (const f of ['components/cy/edge-glow/index.js', 'pages/topic/components/cy/self-play-checkout/index.js', 'pages/club/components/club-director-broadcast-sheet/index.js', 'utils/hero-fly.js']) {
    assert.ok(!/@keyframes/.test(read(f)), f);
  }
  assert.match(read('components/cy/edge-glow/index.js'), /this\.animate\('\.eg__sw'/);
});

/* ---- 通用:装一个组件 ---- */
function loadComponent(file, wxStub) {
  let cfg = null;
  const prev = { Component: global.Component, Behavior: global.Behavior, wx: global.wx };
  global.Component = (c) => { cfg = c; };
  global.Behavior = (b) => b;
  global.wx = Object.assign({ getStorageSync: () => '', setStorageSync() {}, onAppShow() {}, offAppShow() {}, createSelectorQuery: () => { const q = { in: () => q, select: () => q, boundingClientRect: (cb) => { q._cb = cb; return q; }, exec: () => q._cb && q._cb({ width: 200 }) }; return q; } }, wxStub);
  const p = path.join(ROOT, file);
  delete require.cache[p];
  try { require(p); } finally { global.Component = prev.Component; global.Behavior = prev.Behavior; }
  const calls = { animate: [], events: [] };
  const vm = Object.assign({}, cfg.methods, {
    data: Object.assign({}, cfg.data, { reducedMotion: false }),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb(); },
    animate(sel, frames, ms, cb) { calls.animate.push({ sel, frames, ms }); if (cb && !/spin/.test(sel)) cb(); }, // 转圈靠回调续播,桩里不续
    triggerEvent(n, d) { calls.events.push({ n, d }); },
  });
  return { cfg, vm, calls, restore: () => { global.wx = prev.wx; } };
}

test('边缘光:一次 1.3s 扫屏;减少动态时不扫;外观色来自宿主传入的 --lk-*', () => {
  const c = loadComponent('components/cy/edge-glow/index.js');
  try {
    c.vm.data.lkVars = '--lk-a:#E9E2D0;--lk-b:#9AA0AA';
    c.vm.play();
    assert.equal(c.vm.data.on, true);
    assert.equal(c.vm.data.vars, '--lk-a:#E9E2D0;--lk-b:#9AA0AA');
    assert.deepEqual(c.calls.animate.map((a) => [a.sel, a.ms]), [['.eg__sw', 1300], ['.eg', 1300]]);
    assert.equal(c.calls.animate[1].frames[0].ease, 'cubic-bezier(.32,.72,0,1)');
    c.vm.data.on = false; c.calls.animate.length = 0; c.vm.data.reducedMotion = true;
    c.vm.play();
    assert.equal(c.vm.data.on, false, '减少动态:终态=不扫');
    assert.equal(c.calls.animate.length, 0);
  } finally { c.restore(); }
});

test('结算层支付键:busy 先量宽再收成 52px 圆并转圈;done 画勾;失败回原宽', () => {
  const c = loadComponent('pages/topic/components/cy/self-play-checkout/index.js');
  try {
    const timers = []; const realST = global.setTimeout; global.setTimeout = (fn) => { timers.push(fn); return 1; };
    try {
      c.vm.data.phase = 'busy';
      c.vm._phase('busy');
      assert.equal(c.vm.data.payW, 200, '先写现宽作为 transition 起点');
      timers.splice(0).forEach((fn) => fn());
      assert.equal(c.vm.data.payW, 52, '下一拍收成 52px 圆');
      assert.ok(c.calls.animate.some((a) => a.sel === '.ck__spin' && a.ms === 800), '转圈走 this.animate');
      c.vm._phase('');
      assert.equal(c.vm.data.payW, 0, '失败/取消回到原宽');
      c.vm.data.phase = 'done';
      c.vm._phase('done');
      assert.equal(c.vm.data.paidOut, true, '收起时原地淡出,不右滑');
      timers.splice(0).forEach((fn) => fn());
      const arms = c.calls.animate.filter((a) => /ck__arm/.test(a.sel)).map((a) => a.sel);
      assert.deepEqual(arms, ['.ck__arm--a', '.ck__arm--b'], '勾按笔顺画出:短笔 → 长笔');
    } finally { global.setTimeout = realST; }
  } finally { c.restore(); }
  const wxml = read('pages/topic/components/cy/self-play-checkout/index.wxml');
  assert.match(wxml, /ck__spin/);
  assert.match(wxml, /已购买/);
  assert.match(wxml, /票已放进票夹/);
});

test('广播半屏:发出后同行人每 160ms 打一个勾 → 按钮「已发出」→ 700ms 后通知宿主收起', () => {
  const c = loadComponent('pages/club/components/club-director-broadcast-sheet/index.js');
  try {
    c.vm.data.audience = [{ id: 'a', picked: true }, { id: 'b', picked: true }, { id: 'c', picked: true }, { id: 'd', picked: false }];
    const ts = []; const realST = global.setTimeout; global.setTimeout = (fn, ms) => { ts.push({ fn, ms }); return ts.length; };
    try {
      c.vm._phase('sent');
      assert.equal(c.vm.data.ticking, true);
      assert.deepEqual(ts.map((t) => t.ms), [160, 320, 480, 480, 1180], '三位依次 160/320/480,按钮 480,收起 480+700');
      ts.forEach((t) => t.fn());
      assert.deepEqual(Object.keys(c.vm.data.sentMap).sort(), ['a', 'b', 'c'], '只给被选中的人打勾');
      assert.equal(c.vm.data.sentDone, true);
      assert.deepEqual(c.calls.events.map((e) => e.n), ['sentdone']);
      assert.equal(c.calls.events[0].d.reduced, false);
    } finally { global.setTimeout = realST; }
    c.vm._phase('');
    assert.equal(c.vm.data.ticking, false);
    assert.deepEqual(c.vm.data.sentMap, {});
    c.vm.data.sendPhase = 'sending'; c.vm.data.confirmDisabled = false;
    c.vm.onConfirmTap();
    assert.equal(c.calls.events.filter((e) => e.n === 'confirm').length, 0, '发送中不得重复提交');
  } finally { c.restore(); }
});

test('广播半屏:减少动态一步到终态', () => {
  const c = loadComponent('pages/club/components/club-director-broadcast-sheet/index.js', { getStorageSync: (k) => (k === 'play_reduced_motion' ? true : '') });
  try {
    c.vm.data.audience = [{ id: 'a', picked: true }, { id: 'b', picked: true }];
    const ts = []; const realST = global.setTimeout; global.setTimeout = (fn, ms) => { ts.push(ms); fn(); return 1; };
    try { c.vm._phase('sent'); } finally { global.setTimeout = realST; }
    assert.ok(ts.every((ms) => ms === 0));
    assert.equal(c.vm.data.sentDone, true);
    assert.equal(c.calls.events[0].d.reduced, true);
  } finally { c.restore(); }
});

test('导演台:没发成(驳回/待确认/异常/版本冲突)半屏立刻收起;发成才留着打勾,草稿成功后清空', () => {
  const src = read('pages/club/topic-detail/director.js');
  assert.match(src, /_broadcastEnd\(\) \{/);
  // 四条失败/待确认出口都要收起半屏
  assert.equal((src.match(/_broadcastEnd\(\)/g) || []).length >= 5, true);
  assert.match(src, /broadcastSendPhase: 'sent', broadcastDraft:/);
  assert.match(src, /onBroadcastSentDone\(e\)/);
  const wxml = read('pages/club/topic-detail/index.wxml');
  assert.match(wxml, /send-phase="\{\{broadcastSendPhase\}\}"/);
  assert.match(wxml, /bind:sentdone="onBroadcastSentDone"/);
});

/* 小程序 setData 支持 'a.b' 路径写法,桩里照做 */
function setPath(data, patch) {
  Object.keys(patch).forEach((k) => {
    const parts = k.split('.');
    let o = data;
    for (let i = 0; i < parts.length - 1; i++) { if (typeof o[parts[i]] !== 'object' || o[parts[i]] === null) o[parts[i]] = {}; o = o[parts[i]]; }
    o[parts[parts.length - 1]] = patch[k];
  });
}

/* ---- 主题页:到账 → 成功卡 → 原卡已购 ---- */
function loadTopicPage() {
  const toasts = [];
  let cfg = null;
  const prev = { getApp: global.getApp, Page: global.Page, wx: global.wx };
  global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 }, getUserType: () => 1, getUserID: () => 500, tips: (m) => toasts.push(m), sendRequest() {}, recordConsent: () => Promise.resolve({ code: 200 }) });
  global.Page = (c) => { cfg = c; };
  global.wx = { showLoading() {}, hideLoading() {}, showToast: (o) => toasts.push(o.title), showModal() {}, navigateTo(o) { toasts.push('NAV ' + o.url); }, getSystemInfo() {}, getStorageSync: () => '', setStorageSync() {}, getWindowInfo: () => ({ windowWidth: 390, windowHeight: 844 }), createSelectorQuery: () => { const q = { in: () => q, select: () => q, selectAll: () => q, boundingClientRect: () => q, scrollOffset: () => q, exec() {} }; return q; } };
  const p = path.join(ROOT, 'pages/topic/index/index.js');
  delete require.cache[p];
  try { require(p); } finally { global.getApp = prev.getApp; global.Page = prev.Page; }
  const calls = { animate: [], later: [] };
  const vm = Object.assign({}, cfg.methods, cfg, {
    setData(patch, cb) { setPath(this.data, patch); if (cb) cb(); },
    animate(sel, frames, ms) { calls.animate.push({ sel, ms }); },
    _later(ms, fn) { calls.later.push({ ms, fn }); },
  });
  vm.data = Object.assign({}, cfg.data, { info: { id: 7, name: '星尘便笺', selfPlay: 1 } });
  return { vm, calls, toasts, restore: () => { global.wx = prev.wx; } };
}

test('到账:边缘光 + 成功卡,停 1.6s 后结算层收起,再 350ms 票卡原地变「已购 · 开始游戏」并弹一下', () => {
  const h = loadTopicPage();
  try {
    h.vm._playPaidMotion(7, 55);
    assert.equal(h.vm.data.selfPlayPhase, 'done');
    assert.equal(h.vm.data.glowKey, 1);
    assert.equal(h.vm.data.reveal.show, false, '不再自动弹整屏揭晓层');
    assert.equal(h.calls.later[0].ms, 1600);
    h.vm.data.selfPlayCheckoutShow = true;
    h.calls.later[0].fn();
    assert.equal(h.vm.data.selfPlayCheckoutShow, false);
    assert.equal(h.vm.data.selfPlayPhase, '');
    assert.equal(h.calls.later[1].ms, 350);
    h.calls.later[1].fn();
    assert.deepEqual(h.vm.data.heroCta, { kind: 'play', text: '已购 · 开始游戏', paid: true });
    assert.equal(h.vm.data.is_join, 1);
    assert.deepEqual(h.calls.animate.map((a) => [a.sel, a.ms]), [['.hero-cta', 500]]);
    h.vm._navigating = false;
    h.vm.onHeroCta();
    assert.ok(h.toasts.some((t) => /pages\/play\/index\?topicId=7&registrationId=55&entry=ticket/.test(t)), '开始游戏带着这张票直接开局');
  } finally { h.restore(); }
});

test('到账:没有 registrationId 时下一步退回「去票夹」;减少动态不等不弹', () => {
  const h = loadTopicPage();
  try {
    h.vm.data.reducedMotion = true;
    h.vm._playPaidMotion(7, 0);
    assert.equal(h.calls.later[0].ms, 0);
    h.calls.later[0].fn(); h.calls.later[1].fn();
    assert.equal(h.vm.data.heroCta.kind, 'joined');
    assert.equal(h.vm.data.heroCta.text, '已购 · 去票夹');
    assert.equal(h.calls.animate.length, 0);
  } finally { h.restore(); }
});

test('主题页:建单起支付键进入 busy,所有失败出口都回到空闲(不留死圈)', () => {
  const src = read('pages/topic/index/index.js');
  assert.match(src, /that\.setData\(\{ selfPlayPhase: 'busy' \}\)/);
  assert.equal((src.match(/setData\(\{ selfPlayPhase: '' \}\)/g) || []).length >= 6, true);
});

/* ---- 时刻 6 ---- */
test('主题卡 → 详情:点卡片量封面矩形随 eventChannel 交给详情页;量不到就普通跳转', () => {
  const nav = [];
  const prev = global.wx;
  const emitted = [];
  global.wx = { navigateTo: (o) => { nav.push(o.url); if (o.success) o.success({ eventChannel: { emit: (n, d) => emitted.push([n, d]) } }); if (o.complete) o.complete(); } };
  const p = path.join(ROOT, 'utils/hero-fly.js'); delete require.cache[p];
  try {
    const { go } = require(p);
    const page = { createSelectorQuery: () => { const q = { select: (s) => { q.s = s; return q; }, boundingClientRect: (cb) => { q.cb = cb; return q; }, exec: () => q.cb({ left: 20, top: 100, width: 350, height: 170 }) }; return q; } };
    go(page, { currentTarget: { dataset: { id: 9, cover: '/c.jpg' } } }, '/pages/topic/index/index?id=9');
    assert.deepEqual(emitted, [['heroRect', { left: 20, top: 100, width: 350, height: 170, cover: '/c.jpg' }]]);
    go(page, { currentTarget: { dataset: { id: 9 } } }, '/pages/topic/index/index?id=9');
    assert.equal(emitted.length, 1, '没有封面图就不传矩形');
    assert.equal(nav.length, 2);
  } finally { global.wx = prev; }
  const home = read('pages/index/index.wxml');
  assert.match(home, /id="tc-\{\{item\.id\}\}" class="v2-fcard"/);
});

test('详情页:头图从卡片矩形长到全屏(.56s 原型缓动),落定后撤飞层并让主按钮滑上来;返回反向缩回', () => {
  const h = loadTopicPage();
  try {
    h.vm.data.topicLoaded = true;
    h.vm._windowSize = () => ({ w: 390, h: 844, safeBottom: 0 });
    h.vm._heroRect = { left: 20, top: 100, width: 350, height: 170, cover: '/c.jpg' };
    h.vm._heroFly(true);
    assert.equal(h.vm.data.hf.show, true);
    assert.match(h.vm.data.hf.style, /left:20px;top:100px;width:350px;height:170px;border-radius:24px;transition:none;/);
    h.calls.later.shift().fn(); // 下一拍写终态
    assert.match(h.vm.data.hf.style, /left:0px;top:0px;width:390px;height:844px;border-radius:0px;transition:left \.56s cubic-bezier\(\.32,\.72,0,1\)/);
    h.calls.later.shift().fn(); // 580ms 后落定
    h.calls.later.shift().fn(); // 撤飞层
    assert.equal(h.vm.data.hf.show, false);
    assert.deepEqual(h.calls.animate.map((a) => [a.sel, a.ms]), [['#heroActs', 500]]);
    // 返回:反向缩回再退页
    const back = []; global.wx.navigateBack = (o) => back.push(o);
    h.vm.data.reducedMotion = false; h.vm.data.currentScreen = 0; h.vm._heroBack = false;
    h.vm.goBack();
    assert.equal(h.vm.data.hf.show, true);
    assert.match(h.vm.data.hf.style, /left:0px;top:0px;width:390px;height:844px;/);
    h.calls.later.shift().fn();
    assert.match(h.vm.data.hf.style, /left:20px;top:100px;width:350px;height:170px;border-radius:24px;/);
    h.calls.later.shift().fn();
    assert.equal(back.length, 1);
  } finally { h.restore(); }
});

test('详情页:减少动态 / 无矩形(深链)时不做飞层', () => {
  const h = loadTopicPage();
  try {
    const handlers = {};
    h.vm.getOpenerEventChannel = () => ({ on: (n, f) => { handlers[n] = f; } });
    h.vm._listenHeroRect();
    h.vm.data.reducedMotion = true;
    handlers.heroRect({ left: 1, top: 1, width: 10, height: 10, cover: '/c.jpg' });
    assert.equal(h.vm.data.hf.show, false);
    h.vm.data.reducedMotion = false;
    handlers.heroRect(null);
    assert.equal(h.vm.data.hf.show, false);
    h.vm._windowSize = () => ({ w: 390, h: 844, safeBottom: 0 });
    handlers.heroRect({ left: 1, top: 1, width: 10, height: 10, cover: '/c.jpg' });
    assert.equal(h.vm.data.hf.show, true);
  } finally { h.restore(); }
});

test('报名结算页:支付键走 morph 变圈,到账 glowKey 触发边缘光', () => {
  assert.match(read('pages/activity/baoming/baoming.wxml'), /<cy-btn variant="primary" morph /);
  assert.match(read('pages/activity/baoming/baoming.wxml'), /<cy-edge-glow trigger="\{\{glowKey\}\}" \/>/);
  assert.match(read('pages/activity/baoming/baoming.js'), /glowKey: \(this\.data\.glowKey \|\| 0\) \+ 1/);
});
