'use strict';

// C-02b:队长建队后没有任何前端入口把「公开招募」改回「仅邀请」。
// 后端 POST /api/team/join-mode 只认队长 + joinMode∈{1,2}(没有满员/进行中的状态闸),
// 队伍详情页只在 status=0(招募中)给这一行 —— 满员/已开始/已结束的队伍本就不在
// 「附近的队伍」里,切换对任何界面都没有效果。
//
// 契约:① 队长 + 招募中 → 开关按 team.joinMode 回显;
//      ② 切换发 POST /api/team/command，冻结 targetId、joinMode 和 requestId;
//      ③ UNKNOWN 保留原输入；只有原请求终态后重新读取回显，不乐观更新;
//      ④ 行只对队长且 status=0 渲染。
// 负控:去掉提交与回滚,对应用例必须真红。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { createRequire } = require('node:module');
const path = require('node:path');
const vm = require('node:vm');

const { platform, receipt } = require('./helpers/team-event-platform');
const ROOT = path.resolve(__dirname, '../..');
const PAGE_REL = 'pages/team/detail/index.js';
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

function loadPage(source) {
  const requests = [];
  const toasts = [];
  let definition = null;
  const absolutePath = path.join(ROOT, PAGE_REL);
  const app = {
    getUserID: () => '71', getAuthorization: () => 'token-a',
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest(options) { requests.push(options); return { abort() {}, aborted: false }; },
  };
  const wxApi = { ...platform(),
    stopPullDownRefresh() {}, navigateBack() {}, switchTab() {}, showToast() {},
  };
  vm.runInNewContext(source || read(PAGE_REL), {
    console,
    getApp: () => app,
    getCurrentPages: () => [{}],
    Page: (config) => { definition = config; },
    require(request) {
      // toast 出口:直接收进 toasts,不落到 wx 兜底
      if (request.indexOf('toast') !== -1) return (title) => { toasts.push(title); };
      return createRequire(absolutePath)(request);
    },
    setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {},
    wx: wxApi,
  }, { filename: PAGE_REL });
  assert.ok(definition, PAGE_REL + ' 必须注册 Page');
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) {
      Object.entries(patch).forEach(([key, value]) => {
        const parts = key.split('.');
        let cursor = this.data;
        parts.slice(0, -1).forEach((part) => { cursor = cursor[part] || (cursor[part] = {}); });
        cursor[parts.at(-1)] = value;
      });
      if (done) done.call(this);
    },
  });
  return { page, requests, toasts };
}

function teamFixture(overrides) {
  return Object.assign({
    id: 9, joinMode: 2, status: 0, title: '夜行小队', joinedCount: 2, maxMembers: 4,
    expireTime: '2099-01-01 10:00:00',
  }, overrides || {});
}

function openWithTeam(overrides, source) {
  const env = loadPage(source);
  env.page.onLoad({ teamId: '9' });
  env.page.load();
  const info = env.requests.find((r) => r.url === '/api/team/info');
  assert.ok(info, '进页必须读队伍详情');
  info.success({ code: '200', data: { team: teamFixture(overrides), members: [], joined: true, leader: true } });
  return env;
}

test('C-02b:队长切换先冻结原请求，不乐观改开关，APPLIED后从info回读', async () => {
  const { page, requests } = openWithTeam({ joinMode: 2 });
  await page.onJoinModeChange({ detail: { value: false } });
  const update = requests.find(r => r.url === '/api/team/command');
  assert.ok(update); const body = JSON.parse(update.data);
  assert.equal(body.action, 'join-mode'); assert.equal(body.targetId, '9'); assert.equal(body.joinMode, 1);
  assert.match(body.requestId, /^[a-f0-9]{32}$/);
  assert.equal(page.data.joinModePublic, true); assert.equal(page.data.joinModeSaving, true);
  update.success(receipt(update, 'APPLIED'));
  requests.at(-1).success({ code: 200, data: { team: teamFixture({ joinMode: 1 }), members: [], joined: true, leader: true } });
  assert.equal(page.data.joinModePublic, false); assert.equal(page.data.team.joinMode, 1);
});

test('C-02b:反向切换与重试始终保留原号和目标模式', async () => {
  const { page, requests } = openWithTeam({ joinMode: 1 });
  await page.onJoinModeChange({ detail: { value: true } });
  const update = requests.at(-1); assert.equal(JSON.parse(update.data).joinMode, 2);
  update.fail({}); page.retryCommand();
  assert.equal(requests.at(-1).data, update.data);
});

test('C-02b:业务拒绝终态和网络UNKNOWN都不乐观修改；UNKNOWN不放行新输入', async () => {
  for (const state of ['REJECTED', 'UNKNOWN']) {
    const { page, requests } = openWithTeam({ joinMode: 2 });
    await page.onJoinModeChange({ detail: { value: false } });
    const update = requests.at(-1);
    if (state === 'UNKNOWN') update.fail({}); else update.success(receipt(update, state));
    assert.equal(page.data.joinModePublic, true);
    assert.equal(page.data.commandRecovery.state, state);
    const count = requests.length;
    await page.onJoinModeChange({ detail: { value: false } }); assert.equal(requests.length, count);
  }
});

test('C-02b:入口仍只给招募中的队长，负控乐观改值必须被原位断言捕获', async () => {
  const wxml = read('pages/team/detail/index.wxml');
  assert.match(wxml, /wx:if="\{\{leader && team\.status == 0\}\}"/);
  assert.match(wxml, /disabled="\{\{joinModeSaving\}\}"/);
  const source = read(PAGE_REL);
  const before = "    this.setData({ joinModePublic: prevMode === 2 });\n    if (this._recovery.pending()) return;";
  const broken = source.replace(before, "    this.setData({ joinModePublic: nextMode === 2 });\n    if (this._recovery.pending()) return;");
  assert.notEqual(broken, source, '负控必须命中');
  const { page } = openWithTeam({ joinMode: 2 }, broken);
  await page.onJoinModeChange({ detail: { value: false } });
  assert.equal(page.data.joinModePublic, false, '负控确实复现错误的乐观开关');
});
