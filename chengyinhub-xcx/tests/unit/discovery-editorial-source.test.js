'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = name => fs.readFileSync(path.join(__dirname, '../..', name), 'utf8');

function loadPage() {
  let config;
  const requests = [], dialogs = [], routes = [];
  global.wx = {
    getStorageSync: () => '',
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    showModal: options => dialogs.push(options),
    navigateTo: options => routes.push(options.url),
  };
  global.getApp = () => ({
    globalData: { features: {} },
    getUserID: () => '',
    getUserType: () => 1,
    getImgUrl: value => value,
    sendRequest: options => requests.push(options),
  });
  global.Page = value => { config = value; };
  delete require.cache[require.resolve('../../pages/index/index.js')];
  require('../../pages/index/index.js');
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = (patch, callback) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.');
      const target = parts.slice(0, -1).reduce((row, part) => row[part] || (row[part] = {}), page.data);
      target[parts.at(-1)] = value;
    }
    if (callback) callback();
  };
  return { page, requests, dialogs, routes };
}

test('首页真实请求只取人工推荐位，正常回包生成精选卡且保留详情入口', () => {
  const { page, requests, routes } = loadPage();
  page.getUserData = page.loadContinueExplore = page.loadBottomTopicList = page.loadBottomActivityList = () => {};
  page.loadPageData();
  const request = requests.find(row => row.url === '/api/topic/list');
  assert.equal(request.data.is_recommend, 1);
  assert.equal(request.data.pageSize, 5);
  assert.equal(page.data.listLoading.recommendedTopicList, true);
  request.success({ code: 200, data: { rows: [{
    id: 31, name: '夜游路线', imgUrl: 'route.jpg', startDate: '2000-01-01 12:00:00',
    clubName: '夜游俱乐部', sysCategoryList: [{ categoryName: '夜游' }], signupCount: 3,
  }] } });
  request.complete();
  assert.equal(page.data.listLoading.recommendedTopicList, false);
  assert.equal(page.data.listErr.recommendedTopicList, false);
  assert.equal(page.data.recoHero.id, 31);
  assert.equal(page.data.recoHero.cover, 'route.jpg');
  assert.deepEqual(page.data.recoHero.tags, ['夜游']);
  assert.match(page.data.recoHero.metaLine, /3 人已报名/);
  assert.equal(page.data.recoHero.statusLabel, '');
  page.goRecoHero({ currentTarget: { dataset: { index: 0 } } });
  assert.deepEqual(routes, ['/pages/topic/index/index?id=31']);
});

test('开始时间已过、恰好开始、未来、无效或缺省均不能推断当前可参加状态', t => {
  t.mock.method(Date, 'now', () => Date.parse('2026-10-21T12:00:00+08:00'));
  const { page } = loadPage();
  page.data.recommendedTopicList = [
    '2000-01-01 12:00:00', '2026-10-21 12:00:00', '2999-01-01 12:00:00', 'invalid', undefined,
  ].map((startDate, index) => ({ id: index + 1, name: '主题', startDate, status: 1, betaFlag: 1 }));
  page._buildRecoHero();
  assert.equal(page.data.recoCards.length, 5);
  assert.deepEqual(page.data.recoCards.map(row => row.statusLabel), ['', '', '', '', '']);
  assert.ok(page.data.recoCards.every(row => row.betaFlag === 1), 'Beta 信息仍来自服务端字段');
});

test('刷新保留现有精选卡，空回包清空轮播，失败仍保留独立错误和重试参数', () => {
  const { page, requests } = loadPage();
  page.data.recommendedTopicList = [{ id: 31, name: '旧主题' }];
  page._buildRecoHero();
  page.getListData('recommendedTopicList', '/api/topic/list', { is_recommend: 1 });
  assert.equal(page.data.listLoading.recommendedTopicList, true);
  assert.equal(page.data.recoHero.id, 31);
  requests[0].success({ code: 200, data: { rows: [] } });
  requests[0].complete();
  assert.equal(page.data.recoHero, null);
  assert.deepEqual(page.data.recoCards, []);
  assert.deepEqual(page.data.recoCardStyles, []);
  assert.equal(page.data.recoCurrent, 0);
  assert.equal(page.data.listErr.recommendedTopicList, false);
  page.retryList({ currentTarget: { dataset: { list: 'recommendedTopicList' } } });
  assert.deepEqual(requests[1].data, { is_recommend: 1 });
  requests[1].fail({ errMsg: 'request:fail' });
  requests[1].complete();
  assert.equal(page.data.listErr.recommendedTopicList, true);
  assert.equal(page.data.listLoading.recommendedTopicList, false);
});

// 2026-10-27 真机走查 P-02(owner 明确要求删除):编辑精选下的「为什么看到」按钮与其弹窗一起撤掉。
// 原用例钉的是按钮 + 弹窗文案，随入口退役改为钉「已删除」;编辑精选的来源说明仍由卡片文案承担。
test('P-02:编辑精选不再有「为什么看到」入口，来源说明留在卡片文案里', () => {
  const { page } = loadPage();
  assert.equal(page.showRecommendationSource, undefined);
  const wxml = read('pages/index/index.wxml');
  assert.doesNotMatch(wxml, /为什么看到|showRecommendationSource/);
  assert.match(wxml, /编辑精选 · 参加条件以详情为准/);
  assert.match(wxml, /<cy-modal-host[^>]*id="cy-modal-host"/);
  assert.match(wxml, /listLoading\.recommendedTopicList && !recoHero/);
  assert.match(wxml, /listErr\.recommendedTopicList && !recoHero/);
  assert.match(wxml, /!listLoading\.recommendedTopicList && !recoHero/);
  assert.match(wxml, /listLoading\.recommendedTopicList && recoHero/);
});
