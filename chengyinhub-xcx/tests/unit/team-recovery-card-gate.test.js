'use strict';
// 2026-11-02 page-polish T1:恢复卡只在「本页刚发出的请求在途」时隐藏。
// 失败/未知、重进页面读到存档、从卡上查询原请求时都必须照常显示 —— 这张卡是防重复提交的围栏。
// (第十轮 j2 F-11:入队页的 APPLIED 是确定结果,不再出卡,见第一条。)
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { platform, receipt } = require('./helpers/team-event-platform');
function boot(kind, wx = platform()) {
  let definition;
  const requests = [];
  const app = { getRequestErrorMessage: (res, fallback) => fallback, globalData: {}, getUserID: () => '71', getAuthorization: () => 'a',
    sendRequest: r => requests.push(r) };
  Object.assign(wx, { setNavigationBarColor() {}, getSystemInfoSync: () => ({}), showToast() {}, requestSubscribeMessage() {} });
  global.wx = wx; global.getApp = () => app; global.Page = p => { definition = p; };
  const file = path.resolve(__dirname, '../../pages', kind, 'index.js');
  delete require.cache[file]; require(file);
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData(p) { Object.assign(this.data, p); } };
  page.onLoad(kind === 'team/join' ? { code: 'opaque-invite' } : { id: '9007199254740993' });
  return { page, requests, wx };
}
function invite(h) { h.requests[0].success({ code: 200, data: { team: { id: '9007199254740993', status: 0 }, joined: false } }); }

// 第十轮 f2 j2 F-11:原断言「APPLIED 回执卡留到点确认结果」编码的是旧决定 —— 成功入队后页面上挂着
// 「接受邀请入队原请求已执行原请求号：…」。入队已执行是确定结果,页面重读队伍显示已加入,回执直接归档、不出卡。
test('normal join: no recovery card while the fresh command is in flight; APPLIED shows no card and archives the receipt', async () => {
  const h = boot('team/join'); invite(h);
  assert.equal(h.page.data.recoveryShown, false);
  await h.page.join(); const command = h.requests.at(-1);
  assert.equal(command.url, '/api/team/command');
  assert.equal(h.page.data.commandRecovery.state, 'UNKNOWN'); assert.equal(h.page.data.commandRecovery.busy, true);
  assert.equal(h.page.data.joining, true, 'button keeps its own busy label');
  assert.equal(h.page.data.recoveryShown, false);
  command.success(receipt(command, 'APPLIED'));
  assert.equal(h.page.data.recoveryShown, false, '成功入队不挂原请求号卡');
  await Promise.resolve();
  assert.equal(h.page.data.commandRecovery.state, '', 'APPLIED 回执已归档');
  assert.equal(h.page.data.recoveryShown, false);
  assert.ok(h.requests.some(r => r.url !== '/api/team/command'), '页面重读队伍');
});

test('fresh join whose reply fails shows the UNKNOWN card at once', async () => {
  const h = boot('team/join'); invite(h); await h.page.join();
  h.requests.at(-1).fail({});
  assert.equal(h.page.data.commandRecovery.state, 'UNKNOWN');
  assert.equal(h.page.data.recoveryShown, true);
});

test('crash/reload with an in-flight command shows the stored UNKNOWN card on the new page', async () => {
  const h = boot('team/join'); invite(h); await h.page.join();
  assert.equal(h.page.data.recoveryShown, false);
  // no reply ever arrives; the mini program is killed and reopened with the same storage
  const next = boot('team/join', h.wx);
  assert.equal(next.page.data.commandRecovery.state, 'UNKNOWN');
  assert.equal(next.page.data.recoveryShown, true);
  await next.page.join();
  assert.equal(next.requests.filter(r => r.url === '/api/team/command').length, 0, 'no new command while unknown');
});

test('hide and show during flight reopens from storage and shows the card', async () => {
  const h = boot('team/join'); invite(h); await h.page.join();
  h.page.onHide(); h.page.onShow();
  assert.equal(h.page.data.commandRecovery.state, 'UNKNOWN');
  assert.equal(h.page.data.recoveryShown, true);
});

test('querying from the card keeps the card visible while busy', async () => {
  const h = boot('team/join'); invite(h); await h.page.join();
  h.requests.at(-1).fail({});
  h.page.queryCommand();
  assert.equal(h.page.data.commandRecovery.busy, true);
  assert.equal(h.page.data.recoveryShown, true);
});

test('signup page uses the same gate', async () => {
  const h = boot('activity/official-detail'); await h.page.doSignup(); const command = h.requests.at(-1);
  assert.equal(h.page.data.recoveryShown, false);
  command.fail({});
  assert.equal(h.page.data.recoveryShown, true);
  const next = boot('activity/official-detail', h.wx);
  assert.equal(next.page.data.recoveryShown, true);
});

test('all four consumers gate the card on recoveryShown only', () => {
  for (const f of ['pages/team/join/index.wxml', 'pages/team/detail/index.wxml', 'pages/activity/official-detail/index.wxml', 'pages/activity/official-organizer/index.wxml']) {
    const wxml = fs.readFileSync(path.resolve(__dirname, '../..', f), 'utf8');
    assert.match(wxml, /<view wx:if="\{\{recoveryShown\}\}" class="(team|od|oo)-card" aria-role="status">/, f);
    assert.doesNotMatch(wxml, /commandRecovery\.state \|\| commandRecovery\.busy/, f);
  }
});
