// 2026-11-01 业主:俱乐部活动详情与商家主题工作台功能对齐 ——
//   主办底部主键按状态变(售票 / 进行中 =「去核销」直接扫码)、开售后出「核销」三格、主题卡进剧情与玩法(含答案);
//   俱乐部自办进行中底部是「去核销」(Figma H6),结束活动挪进「更多」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const { hostPrimary } = require('../../utils/project-progress.js');

test('主办底部主键按 lifecycle 变,售票 / 进行中是去核销', () => {
  assert.deepEqual(hostPrimary({ lifecycle: 7 }), { text: '等待审核中', act: '', key: '', disabled: true });
  assert.equal(hostPrimary({ lifecycle: 0 }).act, 'goAddStations');
  assert.deepEqual(hostPrimary({ lifecycle: 1 }), { text: '去招商', act: 'onQuickAction', key: 'merchant', disabled: false });
  assert.deepEqual(hostPrimary({ lifecycle: 1, needExecutorClub: true }), { text: '去邀请承办', act: 'onQuickAction', key: 'club', disabled: false });
  assert.equal(hostPrimary({ lifecycle: 2 }).text, '配置票务');
  for (const lc of [3, 4]) assert.deepEqual(hostPrimary({ lifecycle: lc }), { text: '去核销', act: 'goVerify', key: '', disabled: false });
  for (const lc of [5, 6]) assert.equal(hostPrimary({ lifecycle: lc }).act, 'goFinance');
  assert.equal(hostPrimary({}).text, '');
});

test('主办视图:核销三格、主题卡、底部固定条都在,动作走白名单派发', () => {
  const wxml = read('pages/topic/components/project-host/index.wxml');
  assert.match(wxml, /wx:if="\{\{ showVerify \}\}"[\s\S]*?data-act="goLedger"/);
  assert.match(wxml, /class="tcard" bindtap="emit" data-act="goStory"/);
  assert.match(wxml, /class="bottom-bar" wx:if="\{\{ hostPrimary\.text \}\}"/);
  assert.match(wxml, /data-act="\{\{ hostPrimary\.act \}\}" data-key="\{\{ hostPrimary\.key \}\}"/);
  const page = read('pages/topic/merchantinfo/merchantinfo.js');
  assert.match(page, /goVerify: 1, goStory: 1,/);
  for (const act of ['goAddStations', 'onQuickAction', 'goFinance', 'goLedger', 'toggleRules']) {
    assert.match(page, new RegExp('\\b' + act + ': 1'), act + ' 必须在 onJoinAct 白名单里');
  }
  assert.match(read('pages/topic/components/project-join/index.wxml'), /class="tcard" bindtap="emit" data-act="goStory"/);
});

let pageConfig;
let navigations;
global.getApp = () => ({ globalData: {}, getUserID: () => 1, sendRequest: () => ({ abort() {} }), tips() {}, getRequestErrorMessage: (r, f) => f });
global.wx = { navigateTo: (o) => { navigations.push(o.url); if (o.complete) o.complete(); }, reLaunch() {}, showToast() {}, setNavigationBarColor() {}, setBackgroundColor() {}, nextTick: (fn) => fn() };
global.Page = (config) => { pageConfig = config; };
require('../../pages/topic/merchantinfo/merchantinfo.js');
function makePage(data) {
  const page = Object.assign({}, pageConfig);
  page.data = Object.assign(JSON.parse(JSON.stringify(pageConfig.data)), data || {});
  page.setData = (patch) => Object.assign(page.data, patch);
  return page;
}

test('去核销 / 扫码:navigateTo 商家首页 scan=1(返回仍在主题);俱乐部主办去俱乐部页扫码', () => {
  navigations = [];
  makePage({ hostOwnerType: 'merchant' }).goVerify();
  const club = makePage({ hostOwnerType: 'club' });
  club._clubNotifyTarget = { clubId: 42, activityId: 0 };
  club.goVerify();
  assert.deepEqual(navigations, ['/pages/merchant/index/index?scan=1', '/pages/club/detail/index?id=42&scan=1']);
});

test('主题卡 → 剧情与玩法:商家带 scope 走主办答案接口,俱乐部主办带 clubId', () => {
  navigations = [];
  makePage({ topicId: 990045, operationScope: 'MERCHANT', hostOwnerType: 'merchant' }).goStory();
  const club = makePage({ topicId: 990045, hostOwnerType: 'club' });
  club._clubNotifyTarget = { clubId: 42 };
  club.goStory();
  assert.deepEqual(navigations, ['/pages/club/topic-story/index?topicId=990045&scope=MERCHANT', '/pages/club/topic-story/index?topicId=990045&clubId=42']);
  const story = read('pages/club/topic-story/index.js');
  assert.match(story, /url: clubEntry \? '\/api\/club\/topic-node-answer' : '\/api\/topic\/host-node-answer'/);
});

test('商家首页 / 俱乐部页 scan=1:只在 onLoad 拉起一次扫码,不挂在数据回读回调里', () => {
  const home = read('pages/merchant/index/index.js');
  // 只挂 onLoad:身份 / 权限回读回调「重新加载」等按钮也会走,挂在那里会让这些按钮顺带拉起扫码
  assert.match(home, /if \(options && options\.scan === '1'\) wx\.nextTick\(\(\) => this\.goScanQR\(\)\);/);
  assert.doesNotMatch(home, /_pendingScan/);
  const club = read('pages/club/detail/index.js');
  assert.match(club, /if \(opt\.scan === '1'\) wx\.nextTick\(\(\) => this\.startClubScan\(\)\);/);
  assert.match(club, /onClubScan\(\) \{[\s\S]*?this\.startClubScan\(\);\s*\},/);
});

test('俱乐部自办进行中:底部「去核销」扫码,结束活动在「更多」里', () => {
  const js = read('pages/club/topic-detail/index.js');
  assert.match(js, /selfrun: +\{ text: '进行中', +primary: '去核销'/);
  assert.match(js, /if \(key === 'selfrun'\) \{ this\.goScan\(\); return; \}/);
  assert.match(js, /\/pages\/club\/detail\/index\?id=' \+ encodeURIComponent\(this\._clubId\) \+ '&scan=1'/);
  assert.match(read('pages/club/topic-detail/index.wxml'), /wx:if="\{\{statusKey === 'selfrun' && primary\.text\}\}" class="tset-row" bindtap="openEndSession"/);
});
