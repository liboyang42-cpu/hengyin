// Phase 1.3 analytics 迁移 + 并发缺陷修复 —— 先写失败测试(TDD RED)。
//
// 契约(current-api-inventory.md):
//  #31 track 仅放行白名单;事件结构/回退不变。
//  #32 POST /api/analytics/events/batch,body={events},成功判定 code===200 且 accepted===原批长度。
//  #33 MAX_QUEUE=100/MAX_BATCH=50;成功才出队、失败保留重试。
//  #35 修并发缺陷底线:投递窗口内新 track 的事件不被快照覆盖永久丢失;已成功事件最终计一次。
//  迁移:不再直接 wx.request,改走 app.sendRequest(认证由 request-client 自动注入,不手拼 Authorization)。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

// storage 桩
const store = {};
global.wx = {
<<<<<<< public
  getStorageSync: (k) => { if (failReads) throw new Error('storage read failed'); return store[k]; },
  setStorageSync: (k, v) => { if (failWrites) throw new Error('storage write failed'); store[k] = v; },
=======
  getStorageSync: (k) => store[k] === undefined ? undefined : JSON.parse(JSON.stringify(store[k])),
  setStorageSync: (k, v) => { store[k] = JSON.parse(JSON.stringify(v)); },
>>>>>>> private-codex/batch1
  removeStorageSync: (k) => { delete store[k]; },
};

// app 桩:捕获 sendRequest 调用,测试手动驱动 success
let sent = [];
let currentUserId = 'u1';
let currentAuthorization = 'T';
let failWrites = false;
let failReads = false;
let throwSend = false;
global.getApp = () => ({
  globalData: { siteBaseUrl: 'https://base' },
  getAuthorization: () => currentAuthorization,
  getUserID: () => currentUserId,
  sendRequest: (param) => { if (throwSend) throw new Error('sync transport error'); sent.push(param); },
});

const QKEY = 'p3_analytics_queue_v1:u1';
const ANALYTICS_PATH = process.env.MINI_B_ANALYTICS_SOURCE || '../../utils/analytics.js';

// 每个测试重新加载模块,隔离模块级状态(投递锁 flushing、sessionContext)。
let analytics;
beforeEach(() => {
  for (const k of Object.keys(store)) delete store[k];
  sent = [];
  currentUserId = 'u1';
  currentAuthorization = 'T';
  failReads = false;
  throwSend = false;
  failWrites = false;
  delete require.cache[require.resolve(ANALYTICS_PATH)];
  analytics = require(ANALYTICS_PATH);
  analytics.setSessionContext({ sessionId: 's1' });
});

test('在途期间队列溢出后，旧成功只删原事件，不误删新队头', () => {
  analytics.track('content_view', { bizId: 'first', idempotencyKey: 'first-event-key-0001' });
  for (let i = 1; i <= 105; i++) {
    analytics.track('content_view', { bizId: 'next' + i, idempotencyKey: 'next-event-key-' + i });
  }
  assert.equal(store[QKEY].length, 100);
  const firstRetained = store[QKEY][0].bizId;
  sent[0].success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY].length, 100, '原事件已被上限截掉，不能再按旧数量删除新事件');
  assert.equal(store[QKEY][0].bizId, firstRetained);
  assert.equal(sent[1].data.events[0].bizId, firstRetained);
});

test('入队未确认保存时不触发投递，原队列保持可重试', () => {
  analytics.track('content_view', { bizId: 'saved' });
  sent[0].fail();
  const original = JSON.stringify(store[QKEY]);
  const write = wx.setStorageSync;
  for (const fault of ['drop', 'throw']) {
    wx.setStorageSync = () => { if (fault === 'throw') throw new Error('quota'); };
    try {
      assert.equal(analytics.track('content_view', { bizId: 'not saved' }), false);
      assert.equal(sent.length, 1);
      assert.equal(JSON.stringify(store[QKEY]), original);
    } finally { wx.setStorageSync = write; }
  }
});

test('队列读取失败或损坏不当作空队列覆盖旧记录', () => {
  analytics.track('content_view', { bizId: 'saved' });
  sent[0].fail();
  const read = wx.getStorageSync;
  const original = JSON.stringify(store[QKEY]);
  wx.getStorageSync = () => { throw new Error('read unavailable'); };
  try {
    assert.equal(analytics.track('content_view', { bizId: 'new' }), false);
    assert.equal(JSON.stringify(store[QKEY]), original);
    assert.equal(sent.length, 1);
  } finally { wx.getStorageSync = read; }
  store[QKEY] = 'broken queue';
  assert.doesNotThrow(() => analytics.track('content_view', { bizId: 'new' }));
  assert.equal(store[QKEY], 'broken queue');
});

test('成功后出队保存失败不立即重复投递，恢复后仍使用原事件ID和内容', () => {
  analytics.track('content_view', { bizId: 'original' });
  const original = JSON.parse(JSON.stringify(sent[0].data.events));
  const write = wx.setStorageSync;
  wx.setStorageSync = () => {};
  try {
    sent[0].success({ code: 200, data: { accepted: 1 } });
    assert.equal(sent.length, 1, '未确认出队时不能递归重新投递');
    assert.deepEqual(store[QKEY], original);
  } finally { wx.setStorageSync = write; }
  analytics.flush();
  assert.deepEqual(sent[1].data.events, original);
});

test('旧批次重复回调不得解锁新投递或删除新事件', () => {
  analytics.track('content_view', { bizId: 'A' });
  analytics.track('content_view', { bizId: 'B' });
  sent[0].success({ code: 200, data: { accepted: 1 } });
  const next = sent[1];
  sent[0].success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY].length, 1);
  assert.equal(store[QKEY][0].bizId, 'B');
  analytics.track('content_view', { bizId: 'C' });
  assert.equal(sent.length, 2, '新投递的锁仍有效');
  next.success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY][0].bizId, 'C');
});


test('冷重进后仍重试原事件ID、时间与内容，不重新归一化', () => {
  analytics.track('content_view', { bizId: 'original' });
  const original = JSON.parse(JSON.stringify(sent[0].data.events));
  sent[0].fail();
  delete require.cache[require.resolve(ANALYTICS_PATH)];
  analytics = require(ANALYTICS_PATH);
  analytics.setSessionContext({ sessionId: 'new-session' });
  analytics.flush();
  assert.deepEqual(sent[1].data.events, original);
});

test('统一请求通道同步抛错时不崩溃、不丢队列，下一次可用原事件重试', () => {
  const appGetter = global.getApp;
  global.getApp = () => ({
    ...appGetter(),
    sendRequest() { throw new Error('transport unavailable'); },
  });
  try {
    assert.doesNotThrow(() => analytics.track('content_view', { bizId: 'original' }));
    assert.equal(store[QKEY].length, 1);
  } finally { global.getApp = appGetter; }
  const original = JSON.parse(JSON.stringify(store[QKEY]));
  analytics.flush();
  assert.deepEqual(sent[0].data.events, original);
});

test('缺失或非法 accepted 回执不消费原队列，合法重复确认零新增可出队', () => {
  analytics.track('content_view', { bizId: 'original' });
  const original = JSON.parse(JSON.stringify(store[QKEY]));
  for (const body of [
    { code: 200 },
    { code: 200, data: {} },
    { code: 200, data: { accepted: -1 } },
    { code: 200, data: { accepted: 2 } },
    { code: 200, data: { accepted: '1' } },
    { code: 200, data: { accepted: 0.5 } },
  ]) {
    const count = sent.length;
    sent[count - 1].success(body);
    assert.deepEqual(store[QKEY], original);
    assert.equal(sent.length, count, '畸形回执不能自动继续发送');
    analytics.flush();
  }
  sent[sent.length - 1].success({ code: 200, data: { accepted: 0 } });
  assert.equal(store[QKEY].length, 0, '重复原事件导致零新增仍是合法回执');
});

test('迁移:走 app.sendRequest、POST 正确地址、不手拼 Authorization', () => {
  analytics.track('content_view', { bizId: 'A' });
  assert.equal(sent.length, 1);
  assert.equal(sent[0].url, '/api/analytics/events/batch');
  assert.equal(sent[0].method, 'POST');
  assert.equal(sent[0].header.Authorization, undefined, '认证由 request-client 注入,不在 analytics 手拼');
  assert.equal(sent[0].data.events.length, 1);
  assert.equal(sent[0].data.events[0].bizId, 'A');
});

test('白名单:未注册事件不投递(契约#31)', () => {
  analytics.track('not_allowed_event', {});
  assert.equal(sent.length, 0);
});

test('Chat 埋点白名单完整且不携带用户输入或回答原文', () => {
  ['npc_chat_open', 'npc_chat_submit', 'npc_chat_complete', 'npc_chat_cancel'].forEach((eventName) => {
    analytics.track(eventName, {
      bizType: 'roam', bizId: 91, outcomeStatus: 'SUCCEEDED', safetyDecision: 'PASS',
      message: '用户原文不得进入 analytics', reply: '模型原文不得进入 analytics'
    });
  });

  const queue = store[QKEY] || [];
  assert.deepEqual(queue.map((event) => event.eventName),
    ['npc_chat_open', 'npc_chat_submit', 'npc_chat_complete', 'npc_chat_cancel']);
  queue.forEach((event) => {
    const properties = JSON.parse(event.propertiesJson);
    assert.equal(properties.message, undefined);
    assert.equal(properties.reply, undefined);
    assert.equal(event.bizId, 91);
  });
});

test('成功投递后出队;失败保留重试(契约#33)', () => {
  analytics.track('content_view', { bizId: 'A' });
  sent[0].fail(); // 失败 → 保留
  let q = store[QKEY] || [];
  assert.equal(q.length, 1, '失败保留');
  // 再次 flush 重试
  analytics.flush();
  sent[sent.length - 1].success({ code: 200, data: { accepted: 1 } }); // 成功 → 出队
  q = store[QKEY] || [];
  assert.equal(q.length, 0, '成功出队');
});

test('并发底线#35:投递中新 track 的事件不被快照覆盖丢失', () => {
  analytics.track('content_view', { bizId: 'A' }); // push A → flush(在途 sent[0])
  assert.equal(sent.length, 1);
  analytics.track('content_view', { bizId: 'B' }); // 在途期间 push B
  assert.equal(sent.length, 1, '投递锁:在途不再并发投递,避免重复投');
  sent[0].success({ code: 200, data: { accepted: 1 } });                  // A 投递成功
  const q = store[QKEY] || [];
  assert.equal(q.length, 1, 'B 不应被旧快照回写抹掉');
  assert.equal(q[0].bizId, 'B');
});

test('F24 A 清会话后 B 不会补发 A 的离线事件，且会重建 session', () => {
  analytics.track('content_view', { bizId: 'A' });
  const aSession = sent[0].data.events[0].sessionId;
  sent[0].fail();

  currentUserId = 'u2';
  analytics.track('content_view', { bizId: 'B' });
  const bCall = sent[sent.length - 1];
  assert.deepEqual(bCall.data.events.map((event) => event.bizId), ['B']);
  assert.notEqual(bCall.data.events[0].sessionId, aSession);
  assert.equal((store['p3_analytics_queue_v1:u1'] || []).length, 1);
  assert.equal((store['p3_analytics_queue_v1:u2'] || []).length, 1);
});

test('F24 旧无账号队列只删除不迁给当前账号', () => {
  store.p3_analytics_queue_v1 = [{ eventName: 'content_view', bizId: 'legacy' }];
  analytics.flush();
  assert.equal(store.p3_analytics_queue_v1, undefined);
  assert.equal(sent.length, 0);
});

for (const body of [
  { code: 200 }, { code: 200, data: { accepted: 0 } },
  { code: 200, data: { accepted: '1' } }, { code: 200, data: { accepted: 2 } },
  { code: 200, data: { accepted: 0.5 } }, { code: 500, data: { accepted: 1 } }
]) {
  test('非合法原批回执不出队 ' + JSON.stringify(body), () => {
    analytics.track('content_view', { bizId: 1 });
    sent[0].success(body);
    assert.equal(store[QKEY].length, 1);
    assert.equal(sent.length, 1);
  });
}

test('部分 accepted 无明细保留全部原批，下次重用键及时间', () => {
  analytics.track('content_view', { bizId: 1 });
  sent[0].fail();
  analytics.track('content_view', { bizId: 2 });
  const snapshot = JSON.parse(JSON.stringify(sent[1].data.events));
  sent[1].success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY].length, 2);
  analytics.flush();
  assert.deepEqual(sent[2].data.events, snapshot);
});

test('旧回执只移除原对象快照，同键新内容及新排队事件保留', () => {
  analytics.track('content_view', { bizId: 1, idempotencyKey: 'stable' });
  const snapshot = JSON.parse(JSON.stringify(store[QKEY][0]));
  store[QKEY] = [Object.assign({}, snapshot, { bizId: 2 })];
  sent[0].success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY][0].bizId, 2);
});

test('重复 success/fail 回调不能删除下一批或释放下一批锁', () => {
  analytics.track('content_view', { bizId: 1 });
  analytics.track('content_view', { bizId: 2 });
  sent[0].success({ code: 200, data: { accepted: 1 } });
  sent[0].success({ code: 200, data: { accepted: 1 } });
  sent[0].fail();
  analytics.flush();
  assert.equal(sent.length, 2);
  assert.equal(store[QKEY].length, 1);
});

test('enqueue 落盘失败不发送，同进程 flush 重试原事件', () => {
  failWrites = true;
  assert.equal(analytics.track('content_view', { bizId: 1, idempotencyKey: 'original' }), false);
  assert.equal(sent.length, 0);
  assert.equal(store[QKEY], undefined);
  failWrites = false;
  analytics.flush();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].data.events[0].idempotencyKey, 'original');
});

test('ack 出队落盘失败保留原身份重试且不忙循环', () => {
  analytics.track('content_view', { bizId: 1, idempotencyKey: 'original' });
  failWrites = true;
  sent[0].success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY].length, 1);
  assert.equal(sent.length, 1);
  failWrites = false;
  analytics.flush();
  assert.deepEqual(sent[1].data.events, sent[0].data.events);
  sent[1].success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY].length, 0);
});

test('read 落盘读取失败不得覆盖旧事件', () => {
  store[QKEY] = [{ eventName: 'content_view', idempotencyKey: 'old' }];
  failReads = true;
  analytics.track('content_view', { bizId: 1 });
  analytics.flush();
  assert.equal(sent.length, 0);
  assert.equal(store[QKEY][0].idempotencyKey, 'old');
});

test('账号迟到回执不修改任一队列，新账号使用自己的 actorScope', () => {
  analytics.track('content_view', { bizId: 1 });
  currentUserId = 'u2';
  analytics.track('content_view', { bizId: 2 });
  sent[0].success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY].length, 1);
  assert.equal(store['p3_analytics_queue_v1:u2'].length, 1);
  assert.equal(sent[1].data.events[0].actorScope, 'member:u2');
});

test('账号 A-B-A 的旧代回执不能出队 A 原记录', () => {
  analytics.track('content_view', { bizId: 1 });
  currentUserId = 'u2';
  analytics.setSessionContext({ sessionId: 'b' });
  currentUserId = 'u1';
  currentAuthorization = 'T';
  analytics.setSessionContext({ sessionId: 'a-again' });
  sent[0].success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY].length, 1);
});

test('满队列不会挤掉在途原事件，同键重试不覆盖原内容', () => {
  analytics.track('content_view', { bizId: 1, idempotencyKey: 'original' });
  analytics.track('content_view', { bizId: 2, idempotencyKey: 'original' });
  assert.equal(store[QKEY].length, 1);
  assert.equal(store[QKEY][0].bizId, 1);
  for (let i = 0; i < 99; i++) analytics.track('content_view', { bizId: 10 + i });
  assert.equal(analytics.track('content_view', { bizId: 'overflow' }), false);
  assert.equal(store[QKEY].length, 100);
  assert.equal(store[QKEY][0].idempotencyKey, 'original');
  sent[0].success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY].length, 99);
});

test('同步请求异常保留原队列并释放单飞锁，可按原身份重试', () => {
  throwSend = true;
  assert.doesNotThrow(() => analytics.track('content_view', { idempotencyKey: 'sync-original' }));
  assert.equal(store[QKEY].length, 1);
  throwSend = false;
  analytics.flush();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].data.events[0].idempotencyKey, 'sync-original');
});

test('A 报名 producer 的原 eventKey 与报名ID进入现有请求协议，显式 idempotencyKey 优先', () => {
  analytics.track('payment_success', {
    eventKey: 'payment_success_registration_9', bizType: 'activity', bizId: 42,
    properties: { registrationId: 9, payableAmount: 100 }
  });
  assert.equal(sent[0].data.events[0].idempotencyKey, 'payment_success_registration_9');
  assert.equal(JSON.parse(sent[0].data.events[0].propertiesJson).registrationId, 9);
  sent[0].fail();
  analytics.track('signup_submit', { eventKey: 'alias', idempotencyKey: 'explicit', properties: { registrationId: 10 } });
  assert.equal(sent[1].data.events[1].idempotencyKey, 'explicit');
});

test('失败原批保留且每轮只发一次，不阻断后批独立原事件', () => {
  const events = Array.from({ length: 52 }, (_, i) => ({
    eventName: 'content_view', actorScope: 'member:u1', idempotencyKey: 'original-' + i,
    occurredAt: '2026-10-13T00:00:00Z', bizId: i
  }));
  store[QKEY] = events;
  analytics.flush();
  assert.equal(sent[0].data.events.length, 50);
  sent[0].fail();
  assert.equal(sent.length, 2);
  assert.equal(sent[1].data.events.length, 2);
  assert.equal(sent[1].data.events[0].idempotencyKey, 'original-50');
  sent[1].success({ code: 200, data: { accepted: 2 } });
  assert.equal(store[QKEY].length, 50);
  assert.equal(sent.length, 2, '失败批不能在同轮忙循环');
  analytics.flush();
  assert.deepEqual(sent[2].data.events, events.slice(0, 50), '后续重试只用失败批原对象');
});

test('未经过埋点的 A-B-A 登录换凭据，原凭据迟到响应也不写队列', () => {
  analytics.track('content_view', { bizId: 1, idempotencyKey: 'original-auth' });
  // A→B→A 之间没有 track/setSessionContext；新登录凭据仍须区分代次。
  currentAuthorization = 'new-A-login';
  sent[0].success({ code: 200, data: { accepted: 1 } });
  assert.equal(store[QKEY].length, 1);
  assert.equal(sent.length, 2);
  assert.equal(sent[1].data.events[0].idempotencyKey, 'original-auth');
});
