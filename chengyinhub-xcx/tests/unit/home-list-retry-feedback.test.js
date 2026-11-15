'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

function setPath(target, dotted, value) {
  const parts = dotted.split('.');
  let cursor = target;
  parts.slice(0, -1).forEach((part) => { cursor = cursor[part] || (cursor[part] = {}); });
  cursor[parts.at(-1)] = value;
}

function loadPage(settings = {}) {
  let config;
  const requests = [];
  const controls = [];
  const toasts = [];
  const tips = [];
  global.Page = (value) => { config = value; };
  global.Component = () => {};
  global.wx = {
    getStorageSync: () => '',
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    showToast: (options) => { toasts.push(options); },
  };
  global.getApp = () => ({
    globalData: { features: {} },
    getUserID: () => settings.userId || '',
    getUserType: () => 1,
    getPageSize: () => 10,
    getImgUrl: (src) => src ? 'cdn/' + src : '',
    getUsageConsent: () => ({ ensure: () => Promise.resolve() }),
    setOpenID() {},
    tips: (message) => { tips.push(message); },
    sendRequest: (options) => {
      requests.push(options);
      const control = {
        aborted: false,
        abort() {
          this.aborted = true;
          if (settings.abortCompletesSync) {
            options.fail({ errMsg: 'request:fail abort' });
            options.complete();
          }
        },
      };
      controls.push(control);
      return control;
    },
  });
  if (settings.source) {
    // A-14-2 负控:按变异源码建模块,不污染 require 缓存
    const Module = require('node:module');
    const modulePath = path.resolve(__dirname, '../../pages/index/index.js');
    const m = new Module(modulePath, module);
    m.filename = modulePath;
    m.paths = Module._nodeModulePaths(path.dirname(modulePath));
    m._compile(settings.source, modulePath);
  } else {
    delete require.cache[require.resolve('../../pages/index/index.js')];
    require('../../pages/index/index.js');
  }
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  if (settings.nearby) page._nearbyThemeOwner = '|';
  page.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => setPath(page.data, key, value));
    if (callback) callback();
  };
  return { page, getRequest: () => requests.at(-1), requests, controls, toasts, tips };
}

test('首页列表首次加载和重试都立即进入 loading，失败后可恢复到 error', () => {
  const { page, getRequest } = loadPage({ nearby: true });
  page.data.listErr.nearbyTopicList = true;
  page.getListData('nearbyTopicList', '/api/roam/hangout/nearby', {});
  assert.equal(page.data.listLoading.nearbyTopicList, true);
  assert.equal(page.data.listErr.nearbyTopicList, false);

  getRequest().fail({ errMsg: 'request:fail timeout' });
  assert.equal(page.data.listErr.nearbyTopicList, true);
  getRequest().complete();
  assert.equal(page.data.listLoading.nearbyTopicList, false);
});

test('同一首页列表的新请求会取消旧请求，迟到回调不得覆盖新条件或提前结束 loading', () => {
  const { page, requests, controls } = loadPage({ nearby: true });
  page.getListData('nearbyTopicList', '/api/roam/hangout/nearby', { lat: 31.23, lng: 121.47, radius: 1000 });
  page.getListData('nearbyTopicList', '/api/roam/hangout/nearby', { lat: 31.23, lng: 121.47, radius: 3000 });
  assert.equal(controls[0].aborted, true, '第二次查询必须取消同列表旧请求');

  requests[0].success({ code: 200, data: { items: [{ id: 1, name: '旧条件' }] } });
  requests[0].complete();
  assert.deepEqual(page.data.nearbyTopicList, []);
  assert.equal(page.data.listLoading.nearbyTopicList, true, '旧请求 complete 不得关闭新请求 loading');

  requests[1].success({ code: 200, data: { items: [{ id: 2, name: '新条件' }] } });
  requests[1].complete();
  assert.equal(page.data.nearbyTopicList[0].name, '新条件');
  assert.equal(page.data.listLoading.nearbyTopicList, false);
});

test('首页离页会取消仍在途的列表请求', () => {
  const { page, controls } = loadPage({ abortCompletesSync: true, nearby: true });
  page.getListData('nearbyTopicList', '/api/roam/hangout/nearby', {});
  page.getListData('recommendedTopicList', '/api/topic/list', {});
  let unloadSetDataCalls = 0;
  const originalSetData = page.setData;
  page.setData = (...args) => {
    unloadSetDataCalls += 1;
    return originalSetData(...args);
  };
  page.onUnload();
  assert.deepEqual(controls.map((control) => control.aborted), [true, true]);
  assert.equal(unloadSetDataCalls, 0, 'abort 同步回调也不得在离页过程中继续 setData');
});

test('首页列表 200 空 payload 不得抛异常，必须进入可重试错误态', () => {
  const { page, getRequest } = loadPage({ nearby: true });
  page.getListData('nearbyTopicList', '/api/roam/hangout/nearby', {});

  assert.doesNotThrow(() => getRequest().success({ code: 200, data: null }));
  getRequest().complete();

  assert.equal(page.data.listErr.nearbyTopicList, true);
  assert.equal(page.data.listLoading.nearbyTopicList, false);
});

test('首页私有用户信息 200 空 payload 不得崩溃或继续 loading', () => {
  const { page, getRequest, tips } = loadPage({ userId: 7 });
  page.getUserData();

  assert.doesNotThrow(() => getRequest().success({ code: 200, data: null }));

  assert.equal(page.data.isLoading, false);
  assert.deepEqual(tips, ['用户信息加载失败']);
});

test('首页私有用户信息 200 数组 payload 也必须按畸形响应拒绝', () => {
  const { page, getRequest, tips } = loadPage({ userId: 7 });
  page.getUserData();

  getRequest().success({ code: 200, data: [] });

  assert.deepEqual(tips, ['用户信息加载失败']);
  assert.deepEqual(page.data.userInfo, { avatar: '', nickname: '', point: '0' });
});

test('首页底部主题和活动列表 200 空 payload 都按加载失败处理', () => {
  const topic = loadPage();
  topic.page.loadBottomTopicList(1);
  assert.doesNotThrow(() => topic.requests[0].success({ code: 200, data: null }));
  assert.equal(topic.page.data.isBottomLoading, false);
  // A-14-2:失败不再只弹一次 toast,改为列表底部的行内重试标记(失败页)。
  assert.equal(topic.page.data.bottomTopicFailedPage, 1);
  assert.equal(topic.toasts.length, 0, '行内重试已承接失败反馈，不再叠 toast');

  const activity = loadPage();
  activity.page.loadBottomActivityList(1);
  assert.doesNotThrow(() => activity.requests[0].success({ code: 200, data: null }));
  assert.equal(activity.page.data.isBottomLoading, false);
  assert.equal(activity.page.data.bottomActivityFailedPage, 1);
  assert.equal(activity.toasts.length, 0, '行内重试已承接失败反馈，不再叠 toast');
});

test('A-14-2:底部信息流刷新失败落行内重试,重试复用失败页并在成功后清标记', () => {
  const { page, requests } = loadPage();
  page.loadBottomTopicList(1, true);
  const first = requests.at(-1);
  assert.equal(first.url, '/api/topic/list');
  first.fail({ errMsg: 'request:fail timeout' });
  assert.equal(page.data.bottomTopicFailedPage, 1, '失败页必须落进 data,wxml 才渲染行内重试');
  assert.equal(page.data.isBottomLoading, false);

  page.retryBottomTopic();
  const retry = requests.at(-1);
  assert.equal(retry.data.pageNum, 1, '重试沿用失败页');
  retry.success({ code: 200, data: { rows: [], total: 0 } });
  assert.equal(page.data.bottomTopicFailedPage, 0, '成功后清标记');
});

test('A-14-2:加载更多失败按翻页口径重试(pageNum 前进),活动列表同理', () => {
  const { page, requests } = loadPage();
  page.setData({ bottomTopicPageNo: 1, hasMoreBottomTopic: true });
  page.loadBottomTopicList(2);
  requests.at(-1).fail({ errMsg: 'request:fail timeout' });
  assert.equal(page.data.bottomTopicFailedPage, 2, '翻页失败记的是第 2 页');

  page.retryBottomTopic();
  assert.equal(requests.at(-1).data.pageNum, 2, '重试翻的是同一页，不从头再来');

  const activity = loadPage();
  activity.page.setData({ bottomActivityPageNo: 1, hasMoreBottomActivity: true });
  activity.page.loadBottomActivityList(2);
  activity.requests.at(-1).fail({ errMsg: 'request:fail timeout' });
  assert.equal(activity.page.data.bottomActivityFailedPage, 2);
  activity.page.retryBottomActivity();
  assert.equal(activity.requests.at(-1).data.pageNum, 2);
});

test('A-14-2:底部信息流行内重试入口接了两条线,负控撤掉失败标记必须真红', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/index/index.wxml'), 'utf8');
  // 行内重试元素本身带 `>` 比较表达式,正则按元素内逐条属性钉,不跨 `>` 匹配
  const errorBlock = (cond, handler) => {
    const start = wxml.indexOf('wx:if="{{' + cond + '}}"');
    const block = wxml.slice(start, wxml.indexOf('/>', start));
    return { start, block, handler };
  };
  const topicBlock = errorBlock('bottomTopicFailedPage', 'retryBottomTopic');
  assert.ok(topicBlock.start > -1, '主题流必须有行内重试元素');
  assert.match(topicBlock.block, /action="重试"/);
  assert.match(topicBlock.block, /bind:action="retryBottomTopic"/);
  const activityBlock = errorBlock('bottomActivityFailedPage', 'retryBottomActivity');
  assert.ok(activityBlock.start > -1, '活动流必须有行内重试元素');
  assert.match(activityBlock.block, /action="重试"/);
  assert.match(activityBlock.block, /bind:action="retryBottomActivity"/);
  const json = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../pages/index/index.json'), 'utf8'));
  assert.equal(json.usingComponents['cy-inline-error'], '/components/cy/inline-error/index');

  const js = fs.readFileSync(path.resolve(__dirname, '../../pages/index/index.js'), 'utf8');
  const broken = js.replaceAll('bottomTopicFailedPage: pageNo', 'bottomTopicFailedPage: 0');
  assert.notEqual(broken, js, '负控锚点失效:失败标记未命中');
  const { page, requests } = loadPage({ source: broken });
  page.loadBottomTopicList(1, true);
  requests.at(-1).fail({ errMsg: 'request:fail timeout' });
  assert.equal(page.data.bottomTopicFailedPage, 0, '负控必须复现「失败后没有任何行内重试入口」');
});

test('首页三类列表首次 loading 使用同构骨架，不得短暂冒充空态', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/index/index.wxml'), 'utf8');
  assert.match(wxml, /listLoading\.nearbyTopicList\s*&&\s*!nearbyTopicList\.length[\s\S]{0,220}<cy-skeleton[^>]*type="feed-card"/);
  assert.match(wxml, /listLoading\.recommendedTopicList\s*&&\s*!recoHero[\s\S]{0,220}<cy-skeleton[^>]*type="feed-card"/);
  assert.match(wxml, /listLoading\.upcomingActivityList\s*&&\s*!upcomingActivityList\.length[\s\S]{0,220}<cy-skeleton[^>]*type="feed-card"/);
});

test('首页旧内容重试时显示轻量同步态，离页会取消 onShow 延迟任务', () => {
  const js = fs.readFileSync(path.resolve(__dirname, '../../pages/index/index.js'), 'utf8');
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/index/index.wxml'), 'utf8');

  for (const condition of [
    'listLoading.recommendedTopicList && recoHero',
    'listLoading.nearbyTopicList && nearbyTopicList.length',
    'listLoading.upcomingActivityList && upcomingActivityList.length',
  ]) {
    assert.match(wxml, new RegExp(`wx:if="\\{\\{${condition.replace(/\./g, '\\.') }\\}\\}"[^>]*aria-role="status"`));
  }
  assert.doesNotMatch(wxml, /已显示上次内容，本次刷新失败 · 重试/);
  assert.match(js, /this\._onShowRefreshTimer\s*=\s*setTimeout/);
  assert.match(js, /clearTimeout\(this\._onShowRefreshTimer\)/);
});

test('推荐主题切换后忽略旧 transition 尾帧，焦点卡不得重新变糊', async () => {
  const { page } = loadPage();
  page.data.recoCards = [{ id: 1 }, { id: 2 }];
  page.data.recoCurrent = 0;

  page.onRecoChange({ detail: { current: 1 } });
  await new Promise((resolve) => setTimeout(resolve, 180));
  page.onRecoTransition({ detail: { dx: 500 } });

  assert.match(page.data.recoCardStyles[1], /--reco-card-scale:1\.0000;--reco-mask-opacity:0\.0000;--reco-blur:0\.00px/,
    '第 2 张成为当前项后，旧手势尾帧不能把它恢复成非焦点态');

  page._recoTransitionLocked = false;
  page.onRecoTransition({ detail: { dx: 500 } });
  assert.doesNotMatch(page.data.recoCardStyles[1], /--reco-card-scale:1\.0000;--reco-mask-opacity:0\.0000;--reco-blur:0\.00px/,
    '负控：不拦旧尾帧时必须能复现焦点卡重新变糊');
  clearTimeout(page._recoTransitionUnlockTimer);
});

test('附近主题只在主动定位后查询真实 topic，支持失败重试并隔离离页和账号变化', async () => {
  const { page, requests, controls } = loadPage({ abortCompletesSync: true });
  page.data.reducedMotion = true;
  page.getUserData = () => {};
  page.loadBottomTopicList = () => {};
  page.loadBottomActivityList = () => {};
  page.loadPageData();
  assert.equal(requests.some(r => r.url.includes('/hangout/')), false, '未定位不能查询附近或用全量主题兜底');
  assert.equal(requests.find(r => r.url === '/api/topic/list').data.is_recommend, 1);

  const modals = [], positions = [], navigations = [];
  wx.showModal = (options) => modals.push(options);
  wx.getLocation = (options) => positions.push(options);
  wx.navigateTo = (options) => navigations.push(options.url);
  wx.setNavigationBarColor = () => {};
  page.locateNearbyThemes();
  modals.at(-1).success({ confirm: false });
  assert.equal(positions.length, 0, '拒绝用途说明不得定位');
  page.locateNearbyThemes();
  modals.at(-1).success({ confirm: true });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.data.listLoading.nearbyTopicList, true);
  positions.at(-1).success({ latitude: 31.23, longitude: 121.47 });
  const first = requests.at(-1);
  assert.equal(first.url, '/api/roam/hangout/nearby');
  assert.equal(first.method, 'GET');
  assert.deepEqual(first.data, { lat: 31.23, lng: 121.47, radius: 3000 });
  first.fail({ errMsg: 'timeout' });
  first.complete();
  assert.equal(page.data.listErr.nearbyTopicList, true);
  page.retryList({ currentTarget: { dataset: { list: 'nearbyTopicList' } } });
  const retry = requests.at(-1);
  assert.deepEqual(retry.data, first.data, '重试必须保持真实定位条件');
  retry.success({ code: 200, data: { items: [
    { kind: 'activity', id: 11, name: '活动', distance: 1 },
    { kind: 'hangout', id: 12, name: '旧局', distance: 2 },
    { kind: 'topic', id: 42, name: '真实主题', picUrl: 'theme.jpg', productType: 2, distance: 0 },
  ] } });
  retry.complete();
  assert.deepEqual(page.data.nearbyTopicList.map(item => item.id), [42]);
  assert.equal(page.data.nearbyTopicList[0].cover, 'cdn/theme.jpg');
  assert.equal(page.data.nearbyTopicList[0].distance, '0.00km');
  assert.equal(page.data.listErr.nearbyTopicList, false);
  page.goTopic({ currentTarget: { dataset: { id: 42 } } });
  assert.deepEqual(navigations, ['/pages/topic/index/index?id=42']);

  page.retryList({ currentTarget: { dataset: { list: 'nearbyTopicList' } } });
  const stale = requests.at(-1);
  page.onHide();
  assert.equal(controls.at(-1).aborted, true);
  assert.equal(page._listReq.nearbyTopicList, undefined, '离页必须清除本次位置参数');
  page._homeLocationHidden = false;
  stale.success({ code: 200, data: { items: [{ kind: 'topic', id: 99 }] } });
  stale.complete();
  assert.deepEqual(page.data.nearbyTopicList, [], '离页前请求即使在回到首页后返回也不能落地');

  page.locateNearbyThemes();
  modals.at(-1).success({ confirm: true });
  await new Promise(resolve => setImmediate(resolve));
  positions.at(-1).success({ latitude: 31.23, longitude: 121.47 });
  requests.at(-1).success({ code: 200, data: { items: [] } });
  requests.at(-1).complete();
  assert.equal(page.data.listLoaded.nearbyTopicList, true);
  assert.equal(page.data.listErr.nearbyTopicList, false, '真实空结果应落空态');
  page.retryList({ currentTarget: { dataset: { list: 'nearbyTopicList' } } });
  requests.at(-1).success({ code: 200, data: { rows: [] } });
  requests.at(-1).complete();
  assert.equal(page.data.listErr.nearbyTopicList, true, '错误的接口形状不得当成空附近');

  page.retryList({ currentTarget: { dataset: { list: 'nearbyTopicList' } } });
  const pending = requests.at(-1);
  page._nearbyThemeOwner = 'different-account|';
  pending.success({ code: 200, data: { items: [{ kind: 'topic', id: 100 }] } });
  assert.deepEqual(page.data.nearbyTopicList, [], '账号变化后不得接收旧结果');
  page.onUnload();

  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/index/index.wxml'), 'utf8');
  const nearby = wxml.slice(wxml.indexOf('id="sec-nearby"'), wxml.indexOf('id="sec-upcoming"'));
  assert.match(wxml, /v3-sec-title">推荐主题<\/text>/);
  assert.match(wxml, /title="暂无推荐主题"/);
  assert.match(nearby, /v3-sec-title">附近主题<\/text>/);
  assert.match(nearby, /cta="定位查找" bind:cta="locateNearbyThemes"/);
  assert.match(nearby, /title="3 公里内暂无主题"/);
  assert.match(nearby, /data-list="nearbyTopicList" bind:retry="retryList"/);
  assert.match(nearby, /bindtap="goTopic"/);
  assert.doesNotMatch(nearby, /nearbyActivityList|goActivity|home-route-city-placeholder|route_city_cover/);
  assert.match(nearby, /class="v3-nearby-bg" wx:if="\{\{item\.cover\}\}" src="\{\{item\.cover\}\}"/, '缺封面时仅显示既有中性卡面，不补泛用照片');

  const denied = loadPage();
  const prompts = [], fixes = [], settings = [];
  wx.showModal = options => prompts.push(options);
  wx.getLocation = options => fixes.push(options);
  wx.openSetting = options => settings.push(options);
  wx.setNavigationBarColor = () => {};
  denied.page.locateNearbyThemes();
  prompts.at(-1).success({ confirm: true });
  await new Promise(resolve => setImmediate(resolve));
  fixes.at(-1).fail({ errMsg: 'getLocation:fail auth deny' });
  assert.equal(denied.page.data.listLoading.nearbyTopicList, false);
  prompts.at(-1).success({ confirm: true });
  denied.page.onHide();
  settings.at(-1).success({ authSetting: { 'scope.userLocation': true } });
  assert.equal(fixes.length, 1, '离页后从设置返回不得再次采集 GPS');
  assert.equal(denied.requests.length, 0, '拒权与离页不能查询附近主题');
  denied.page.onUnload();
});
