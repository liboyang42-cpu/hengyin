'use strict';
// CL-06(F-W5-08 / F-W1-11):场次按时间排序并显示集合时刻。
// 「接下来」列表的排序/过滤由 w5-club-topic-ui-tail.test.js 的 F-W5-08 用例钉住;
// 这里补「选择场次」半屏(openSessionPick):每行念集合时刻,且按集合时刻升序。
// 后端 /api/topic/info 的 activityList 按 create_time desc 下发(CmsActivityMapper.xml selectPublicCmsActivityList),
// 所以顺序必须由前端排。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');

function loadPage() {
  const pagePath = path.join(ROOT, 'pages/club/topic-detail/index.js');
  let config = null;
  const previous = { getApp: global.getApp, Page: global.Page, wx: global.wx, getCurrentPages: global.getCurrentPages };
  global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 }, sendRequest: () => {} });
  global.Page = (value) => { config = value; };
  global.getCurrentPages = () => [{}];
  global.wx = { getStorageSync: () => '', setStorageSync: () => {}, removeStorageSync: () => {}, getSystemInfoSync: () => ({}) };
  delete require.cache[pagePath];
  try { require(pagePath); } finally { Object.assign(global, previous); }
  const page = Object.assign({}, config, { data: {} });
  page.setData = (patch) => Object.assign(page.data, patch);
  return page;
}

const future = (days, hh) => {
  const d = new Date(Date.now() + days * 86400000 + 8 * 3600000);
  return d.toISOString().slice(0, 10) + ' ' + hh + ':00';
};

// 与后端一致:新建的在前(create_time desc),而新建的那场反而更晚开始
const ACTIVITY_LIST = [
  { id: 3, name: '后天场', startDate: future(2, '09:30'), endDate: future(2, '23:59') },
  { id: 1, name: '明天场', startDate: future(1, '19:00'), endDate: future(1, '23:59') },
];

function openPicker(page) {
  page.data.sessions = page.buildSessions({ activityList: ACTIVITY_LIST });
  page._topicActivities = ACTIVITY_LIST.map((a) => ({ id: a.id, startDate: a.startDate }));
  page.openSessionPick();
  return page.data.sessionPickRows;
}

test('CL-06 选择场次:每行念出集合时刻(月日 时:分)与场次名', () => {
  const page = loadPage();
  const rows = openPicker(page);
  assert.equal(page.data.sessionPickVisible, true);
  assert.equal(rows.length, 2);
  for (const row of rows) assert.match(row.whenText, /^\d+月\d+日 \d{2}:\d{2}$/);
  assert.deepEqual(rows.map((r) => r.whoText).sort(), ['后天场', '明天场']);
});

// 原 DEFECT_FOUND(CL-06):openSessionPick 按 _topicActivities(=activityList 原序)出行,没有按集合时刻排序。
// 已修(openSessionPick 改为与「接下来」同源同序),本用例为回归。
test('CL-06 选择场次:按集合时刻升序(与「接下来」同序)', () => {
  const page = loadPage();
  const rows = openPicker(page);
  assert.deepEqual(rows.map((r) => r.id), page.data.sessions.map((s) => s.id));
  assert.deepEqual(rows.map((r) => r.id), [1, 3]);
});
