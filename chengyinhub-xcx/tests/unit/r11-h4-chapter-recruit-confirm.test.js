process.env.TZ = 'UTC';

const { test } = require('node:test');
const assert = require('node:assert/strict');

/* 第十一轮 h4 L1:开放「商家承接」后,这一章必须有商家承接成功才能过开售审核。
   以前点开就静默生效,主理人到开售审核被拒才知道;现在开的那一刻先讲后果,确认才发请求。
   关闭不加确认(关掉不会造成卡审)。 */
const PAGE = '../../pages/topic/merchantinfo/merchantinfo.js';

function load() {
  const requests = [];
  const modals = [];
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 1,
    sendRequest: (options) => { requests.push(options); },
    tips: () => {},
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  };
  let pageConfig = null;
  global.getApp = () => app;
  global.wx = {
    navigateTo: () => {}, reLaunch: () => {}, showToast: () => {}, showLoading: () => {},
    hideLoading: () => {}, navigateBack: () => {}, openLocation: () => {}, pageScrollTo: () => {},
    showModal: () => {},
  };
  global.Page = (config) => { pageConfig = config; };
  const modalPath = require.resolve('../../utils/modal.js');
  const realModal = require.cache[modalPath];
  require.cache[modalPath] = { id: modalPath, filename: modalPath, loaded: true,
    exports: { show: (options) => { modals.push(options); } } };
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
  if (realModal) require.cache[modalPath] = realModal; else delete require.cache[modalPath];
  const page = Object.assign({}, pageConfig);
  page.data = JSON.parse(JSON.stringify(pageConfig.data));
  page.setData = (patch) => { Object.keys(patch).forEach((k) => { if (k.indexOf('.') < 0) page.data[k] = patch[k]; }); };
  page.data.role = 'host';
  return { page, requests, modals };
}

test('开放商家承接:先讲清「要有商家承接成功才能开售」,确认后才发请求', () => {
  const { page, requests, modals } = load();
  page._hostChapters = [{ id: 42, recruitEnabled: 0 }];
  page.toggleChapterRecruit();
  assert.equal(requests.length, 0, '确认前不发请求');
  assert.equal(modals.length, 1);
  assert.match(modals[0].content, /这一章要有商家承接成功才能开售/);
  assert.equal(modals[0].confirmText, '开放');

  modals[0].success({ confirm: false });
  assert.equal(requests.length, 0, '取消不发请求');

  modals[0].success({ confirm: true });
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/topic/chapter/recruit');
  assert.equal(requests[0].data.enabled, 1);
  assert.equal(requests[0].data.chapterId, 42);
});

test('关闭商家承接不弹确认,直接发请求', () => {
  const { page, requests, modals } = load();
  page._hostChapters = [{ id: 42, recruitEnabled: 1 }];
  page.toggleChapterRecruit();
  assert.equal(modals.length, 0);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].data.enabled, 0);
});
