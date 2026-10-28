'use strict';
// 原型 B(L6):本场招募(满员不列出)、主题详情开局前准备(只在作者配了时出现,服务端拒绝才写中性说明)、
// 活动详情赛事榜单(公示期「暂列第 N 名」)与安全须知勾选。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const ROOT = path.resolve(__dirname, '../..');
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const clone = v => JSON.parse(JSON.stringify(v));

function load(rel, app, wxExtra = {}, stubs = {}) {
  const file = path.join(ROOT, rel); let def; const modals = [], toasts = [], navs = [];
  const toast = msg => toasts.push(msg); toast.success = toast; toast.error = toast;
  const wx = { navigateTo: o => navs.push(o.url), getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {}, ...wxExtra };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: d => { def = d; }, Component: d => { def = d; }, getApp: () => app, wx, console, setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    require: id => { for (const k of Object.keys(stubs)) if (id.includes(k)) return stubs[k]; if (id.includes('/modal')) return { show: o => modals.push(o) }; if (id.includes('toast')) return toast; return createRequire(file)(id); } });
  const page = { ...def, data: clone(def.data), setData(patch) { for (const [k, v] of Object.entries(patch)) { const p = k.split('.'); if (p.length === 2) this.data[p[0]] = { ...this.data[p[0]], [p[1]]: v }; else this.data[k] = v; } } };
  return { page, modals, toasts, navs };
}

test('本场招募:带场次进来读 lobby,不要定位;满员不列出;已在队里的进入队伍,其余申请走原请求协议', () => {
  const requests = [];
  const app = { globalData: {}, getUserID: () => '7', getAuthorization: () => 't', sendRequest: r => requests.push(r) };
  const h = load('subpackageRoam/my-teams/index.js', app, { getLocation() { throw new Error('lobby must not ask for location'); } });
  h.page.onLoad({ tab: 'open', ownerType: '2', ownerId: '31', scope: encodeURIComponent('城南旧事 · 10月25日 19:30 场') });
  assert.deepEqual(clone(h.page.data.lobby), { ownerType: 2, ownerId: '31', scope: '城南旧事 · 10月25日 19:30 场' });
  h.page._rows = { lead: [], ok: [{ teamId: '11' }] };
  h.page.loadOpen();
  const r = requests.at(-1);
  assert.equal(r.url, '/api/orient/team/lobby'); assert.equal(r.method, 'GET'); assert.deepEqual(clone(r.data), { ownerType: 2, ownerId: '31' });
  r.success({ code: 200, data: [
    { teamId: 11, title: '周六夜行小队', joinedCount: 3, maxMembers: 4, leaderName: '阿栗' },
    { teamId: 12, title: '', joinedCount: 2, maxMembers: 4, leaderName: '小满' },
    { teamId: 13, title: '满员队', joinedCount: 4, maxMembers: 4 },
  ] });
  assert.deepEqual(clone(h.page.data.openRows.map(x => [x.teamId, x.badge, x.go])), [['11', '已加入', '进入队伍'], ['12', '可申请', '申请加入']]);
  h.page.openLobbyTeam({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(h.navs.at(-1), '/pages/team/detail/index?teamId=11');
  h.page.openLobbyTeam({ currentTarget: { dataset: { index: 1 } } });
  assert.equal(h.modals.at(-1).title, '申请加入这支队伍？'); assert.equal(h.modals.at(-1).content, '队长同意后会通知你。');
  const wxml = read('subpackageRoam/my-teams/index.wxml');
  for (const s of ['本场招募', '正在加载…', '加载失败 · 轻点重试', '本场暂无招募中的队伍']) assert.ok(wxml.includes(s), s);
});

test('本场招募读失败是一句可重试的错误,不是空态', () => {
  const requests = [];
  const app = { globalData: {}, getUserID: () => '7', getAuthorization: () => 't', sendRequest: r => requests.push(r) };
  const h = load('subpackageRoam/my-teams/index.js', app);
  h.page.onLoad({ ownerType: '1', ownerId: '5' }); h.page._rows = {}; h.page.loadOpen(); requests.at(-1).fail();
  assert.equal(h.page.data.openError, '加载失败'); assert.equal(h.page.data.openLoaded, false);
});

function topicPage(request) {
  const app = { globalData: {}, getUserID: () => '7', sendRequest: r => request(r) };
  return load('pages/topic/index/index.js', app);
}

test('开局前准备:作者没配准备项也没配可玩时段时整行不出', () => {
  const h = topicPage(() => {});
  h.page._buildPrep({ id: 1 });
  assert.equal(h.page.data.prep.show, false);
});

test('开局前准备:勾完才能开始;服务端拒绝只写一句中性说明;不出现未成年夜间规则', () => {
  const reqs = []; const h = topicPage(r => reqs.push(r));
  h.page.data.info = { id: 9, name: '城南旧事', prepItems: ['手机电量充足', '带手电筒'], playWindow: { startMinute: 0, endMinute: 1439 } };
  h.page.openPrep();
  assert.equal(h.page.data.prepShow, true); assert.equal(h.page.data.prep.rowText, '开局前准备 · 还有 2 项未勾选'); assert.equal(h.page.data.prep.ready, false);
  h.page.startPrepPlay(); assert.equal(reqs.length, 0, '没勾完不发预检');
  h.page.onPrepTick({ currentTarget: { dataset: { index: 0 } }, detail: { checked: true } });
  h.page.onPrepTick({ currentTarget: { dataset: { index: 1 } }, detail: { checked: true } });
  assert.equal(h.page.data.prep.rowText, '开局前准备 · 已全部勾选'); assert.equal(h.page.data.prep.ready, true);
  h.page.startPrepPlay();
  assert.equal(reqs[0].url, '/api/orient/safety/check'); assert.deepEqual(JSON.parse(reqs[0].data), { topicId: 9 });
  reqs[0].success({ code: 200, data: { allowed: false, blockers: [{ message: '未满 18 岁' }] } });
  assert.equal(h.page.data.prep.refused, true); assert.equal(h.navs.length, 0);
  const wxml = read('pages/topic/index/index.wxml');
  assert.ok(wxml.includes('按平台规则，现在不能开始这个主题。'));
  assert.ok(!/未满\s*18|未成年/.test(wxml.split('开局前准备')[1] || ''), '玩家端不印未成年夜间规则');
  const ok = topicPage(r => r.success({ code: 200, data: { allowed: true } }));
  ok.page.data.info = { id: 9, name: '城南旧事', prepItems: ['带手电筒'] }; ok.page.openPrep();
  ok.page.onPrepTick({ currentTarget: { dataset: { index: 0 } }, detail: { checked: true } }); ok.page.startPrepPlay();
  assert.match(ok.navs.at(-1), /^\/pages\/play\/index\?topicId=9/);
});

test('可玩时段:夜间时段写「夜间 20:00–06:00」,时段外开始游玩置灰', () => {
  const h = topicPage(() => {});
  const realDate = Date;
  h.page._buildPrep({ id: 1, playWindow: { startMinute: 1200, endMinute: 360 } });
  assert.equal(h.page.data.prep.windowText, '夜间 20:00–06:00');
  const hour = new realDate().getHours();
  const open = hour >= 20 || hour < 6;
  assert.equal(h.page.data.prep.closed, !open);
  assert.equal(h.page.data.prep.ready, open);
  if (!open) assert.equal(h.page.data.prep.rowText, '开局前准备 · 夜间 20:00–06:00 开放');
});

test('活动详情:赛事榜单只在主办开了时出现,公示期内「暂列第 N 名」;安全须知没勾不报名', () => {
  const app = { globalData: {}, getUserID: () => '7', getAuthorization: () => 't', sendRequest() {} };
  const h = load('pages/activity/official-detail/index.js', app);
  const off = h.page._decorate({ id: 1, title: 'x' });
  assert.equal(off._board, null); assert.equal(off._policyTitle, '');
  const on = h.page._decorate({ id: 1, title: 'x', leaderboard: { enabled: true, final: false, myRank: 12, rows: [
    { rank: 1, name: '阿栗', doneCount: 3, totalCount: 3, elapsedSeconds: 6130 }, { rank: 1, name: '夜行者', doneCount: 3, totalCount: 3, elapsedSeconds: 6130 }] },
    safetyNotice: { title: '城南夜行安全须知', version: '3' } });
  assert.equal(on._board.chip, '暂列第 12 名');
  assert.deepEqual(clone(on._board.rows.map(r => [r.rank, r.name, r.value])), [[1, '阿栗', '3 / 3 · 1:42:10'], [1, '夜行者', '3 / 3 · 1:42:10']]);
  assert.equal(h.page._decorate({ id: 1, leaderboard: { enabled: true, final: true, myRank: 3, rows: [] } })._board.chip, '第 3 名');
  h.page.data.id = '1'; h.page.data.e = on; h.page.data.policyChecked = false;
  h.page.doSignup();
  assert.deepEqual(h.toasts, ['请先勾选安全须知']);
  const wxml = read('pages/activity/official-detail/index.wxml');
  for (const s of ['赛事榜单', '暂无成绩', '安全须知', '按平台规则，你暂时不能报名这一场。']) assert.ok(wxml.includes(s), s);
  assert.ok(wxml.indexOf('<cy-event-attendance') < wxml.lastIndexOf('</scroll-view>'), '集合与签到在页面内容里,不挂在根节点之外');
});
