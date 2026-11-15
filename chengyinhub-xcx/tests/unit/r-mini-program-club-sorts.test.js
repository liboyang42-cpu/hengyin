'use strict';
// T6 mini-program / CLB-13:公开贡献榜的排序档采用 /api/club/leaderboard/sorts。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const DETAIL = '../../pages/club/detail/index.js';
let pageConfig, requests;
global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 }, getUserID: () => 1, getUserRole: () => 'club',
  getImgUrl: u => u, getAuthorization: () => 'token', sendRequest: r => { requests.push(r); },
});
global.wx = { showToast() {}, showModal() {}, showLoading() {}, hideLoading() {}, stopPullDownRefresh() {}, navigateTo() {}, redirectTo() {}, showShareMenu() {} };
global.Page = c => { pageConfig = c; };
beforeEach(() => { requests = []; });

function loadPage() {
  delete require.cache[require.resolve(DETAIL)];
  require(DETAIL);
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) });
  page.setData = (patch, cb) => { Object.assign(page.data, patch); if (cb) cb(); };
  page._leaderboardSort = 'composite';
  page.data.clubId = 7;
  return page;
}
const byUrl = url => requests.filter(r => r.url === url);
const body = r => JSON.parse(r.data);

test('配速与计数榜的主数值', () => {
  const page = loadPage();
  assert.equal(page.decorateRank({ pace: 5.5 }, 'pace').primaryText, '5\'30"/km');
  assert.equal(page.decorateRank({ pace: null }, 'pace').primaryText, '—');
  assert.equal(page.decorateRank({ attendedCount: 3 }, 'events').primaryText, '3 场');
  assert.equal(page.decorateRank({ checkinCount: 4 }, 'checkins').primaryText, '4 次');
  assert.equal(page.decorateRank({}, 'hosted').primaryText, '0 场');
  assert.equal(page.decorateRank({ score: 12 }, 'composite').primaryText, '12.0 分');
});

test('只认已知档、去重;默认档不在列表里用第一档', () => {
  const page = loadPage();
  page.loadLeaderboard();
  byUrl('/api/club/leaderboard/sorts')[0].success({ code: 200, data: { sorts: ['x', 'checkins', 'checkins', 'hosted'], defaultSort: 'nope' } });
  assert.deepEqual(page.data.rankTabs.map(t => t.label), ['打卡次数', '带队场次']);
  assert.equal(page._leaderboardSort, 'checkins');
});

test('跑里程的俱乐部:今天的「综合」照常读,只换成服务端给的七档,不重读', () => {
  const page = loadPage();
  page.loadLeaderboard();
  assert.deepEqual(requests.map(r => r.url), ['/api/club/leaderboard/sorts', '/api/club/leaderboard']);
  assert.equal(body(byUrl('/api/club/leaderboard')[0]).sortBy, 'composite');
  byUrl('/api/club/leaderboard/sorts')[0].success({ code: 200, data: { clubId: 7, sorts: ['events', 'checkins', 'hosted', 'composite', 'mileage', 'pace', 'duration'], defaultSort: 'composite', runningMetrics: true } });
  assert.equal(page.data.rankTabs.length, 7); assert.equal(page.data.leaderboardSortLabel, '综合');
  assert.equal(byUrl('/api/club/leaderboard').length, 1);
});

test('不跑里程的俱乐部:换到服务端默认「到场场次」重读,旧档晚到的回包不覆盖', () => {
  const page = loadPage();
  page.loadLeaderboard();
  byUrl('/api/club/leaderboard/sorts')[0].success({ code: 200, data: { sorts: ['events', 'checkins', 'hosted'], defaultSort: 'events', runningMetrics: false } });
  const boards = byUrl('/api/club/leaderboard');
  assert.equal(boards.length, 2); assert.equal(body(boards[1]).sortBy, 'events');
  assert.equal(page.data.leaderboardSortLabel, '到场场次');
  boards[1].success({ code: '200', data: [{ memberId: 1, nickname: 'A', attendedCount: 3 }] });
  boards[0].success({ code: '200', data: [{ memberId: 2, nickname: 'B', score: 9 }] });
  assert.equal(page.data.leaderboard[0].primaryText, '3 场');
  assert.equal(page.data.leaderboard.length, 1);
});

test('sorts 读不到(旧服务端):保持今天的三档,榜照常', () => {
  const page = loadPage();
  page.loadLeaderboard();
  byUrl('/api/club/leaderboard/sorts')[0].successStatusAbnormal({ code: 404 }, 404);
  assert.deepEqual(page.data.rankTabs.map(t => t.key), ['composite', 'mileage', 'duration']);
  byUrl('/api/club/leaderboard')[0].success({ code: '200', data: [] });
  assert.equal(page.data.leaderboardEmptyTitle, '本周还没有人产生贡献');
  page.loadLeaderboard();
  assert.equal(byUrl('/api/club/leaderboard/sorts').length, 2, '失败后下次再问');
});

test('同一俱乐部只问一次可用档', () => {
  const page = loadPage();
  page.loadLeaderboard();
  byUrl('/api/club/leaderboard/sorts')[0].success({ code: 200, data: { sorts: ['events'], defaultSort: 'events' } });
  page.loadLeaderboard();
  assert.equal(byUrl('/api/club/leaderboard/sorts').length, 1);
});
