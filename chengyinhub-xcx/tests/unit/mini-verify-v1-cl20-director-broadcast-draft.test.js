'use strict';
// CL-20(F-R2S-12):断网时导演台广播表单不丢。
// 写了广播、点发送后请求在网络层失败(结果待核对) / 被后端驳回:草稿内容必须还在,
// 关掉再打开广播半屏(director.js openBroadcast → _setBroadcastTargetType)也要带回原文;
// 只有写成功才清空。商家站点准备表单那一半已由 merchant-game-node-page.test.js 覆盖。
// 负控:把 executeAction 的 catch 分支或 openBroadcast 改成重置 content,本文件必须真红。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const DIRECTOR_PATH = path.resolve(__dirname, '../../pages/club/topic-detail/director.js');
const settle = () => new Promise((resolve) => setImmediate(resolve));

function setup(submit) {
  const storage = {};
  const toasts = [];
  global.getApp = () => ({ getUserID: () => '7', globalData: {} });
  const pages = [];
  global.getCurrentPages = () => pages;
  global.wx = {
    getStorageSync: (k) => storage[k],
    setStorageSync: (k, v) => { storage[k] = v; },
    removeStorageSync: (k) => { delete storage[k]; },
    showToast: (v) => toasts.push(v.title),
    showModal() {},
  };
  delete require.cache[DIRECTOR_PATH];
  const { DIRECTOR_DATA, DIRECTOR_METHODS } = require(DIRECTOR_PATH);
  const ctx = Object.assign({}, DIRECTOR_METHODS, {
    data: Object.assign(JSON.parse(JSON.stringify(DIRECTOR_DATA)), {
      loadState: 'ready', revision: 4, writeLocked: false, canBroadcast: true,
      teams: [{ teamId: 1, nameText: '红队', memberCount: 3 }, { teamId: 2, nameText: '蓝队', memberCount: 2 }],
    }),
    setData(patch) { Object.assign(this.data, patch); },
    _activityId: 55,
    _directorAdapter: { submit },
    loadProjection() {},
    fetchDetail() {},
  });
  // The real lifecycle guard must see this fixture as an active page; never stub the guard itself.
  pages.push(ctx);
  return { ctx, toasts, storage };
}

function writeAndSend(ctx, content) {
  ctx.openBroadcast();
  assert.equal(ctx.data.broadcastSheetVisible, true);
  assert.equal(ctx.data.broadcastPreviewReady, true, '夹具:全部在场 5 人，可发送');
  ctx.onBroadcastContentInput({ detail: { value: content } });
  ctx.confirmBroadcast();
}

test('CL-20 广播请求断网(结果待核对):草稿原文保留,解锁后重开半屏仍带回', async () => {
  const sent = [];
  const { ctx } = setup((command) => { sent.push(command); return Promise.reject(new Error('request:fail timeout')); });
  writeAndSend(ctx, '集合点改到南门，十分钟后出发');
  assert.equal(sent.length, 1);
  assert.equal(sent[0].action, 'BROADCAST');
  assert.equal(sent[0].payload.content, '集合点改到南门，十分钟后出发');
  await settle();
  assert.equal(ctx.data.writeState, 'unknown-write');
  assert.equal(ctx.data.writeLocked, true, '结果未确认期间仍禁止重复发送');
  ctx.confirmBroadcast();
  assert.equal(sent.length, 1, '未知结果不能当作未发送而重发');
  assert.equal(ctx.data.broadcastDraft.content, '集合点改到南门，十分钟后出发', '断网后草稿不得清空');

  // 核对结束(这里直接模拟解锁)后再开半屏,原文要回到输入框
  ctx.setData({ writeLocked: false, writeState: 'idle' });
  ctx.openBroadcast();
  assert.equal(ctx.data.broadcastSheetVisible, true);
  assert.equal(ctx.data.broadcastDraft.content, '集合点改到南门，十分钟后出发');
});

test('CL-20 广播被后端驳回:草稿保留;写成功才清空(正控)', async () => {
  const rejected = setup(() => Promise.resolve({ state: 'rejected', raw: { reasonCode: 'GAME_BROADCAST_NO_RECIPIENT' } }));
  writeAndSend(rejected.ctx, '驳回也要留着');
  await settle();
  assert.equal(rejected.ctx.data.writeLocked, false);
  assert.equal(rejected.ctx.data.broadcastDraft.content, '驳回也要留着');

  const ok = setup(() => Promise.resolve({ state: 'confirmed', raw: {} }));
  writeAndSend(ok.ctx, '成功后清空');
  await settle();
  assert.equal(ok.ctx.data.broadcastDraft.content, '', '写成功后草稿清空，下一条从空白开始');
});
