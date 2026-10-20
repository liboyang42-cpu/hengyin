'use strict';

// BL-201:主题移交俱乐部在途/成功后不再重复发 /api/topic/transfer-to-club(服务端每调一次复制一份草稿),失败后可再次移交。
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE = path.join(ROOT, 'pages/topic/index/index.js');

function harness() {
  const requests = [];
  const modals = [];
  const tips = [];
  let definition;
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44, menuButtonInfo: { left: 300 } },
    sendRequest(options) { requests.push(options); },
    getRequestErrorMessage(_res, fallback) { return fallback; },
    getUserID() { return 7; },
    getUserRole() { return 'player'; },
    getUserType() { return 1; },
    getPageSize() { return 10; },
    getTotalPage() { return 1; },
    setUserRole() {},
    getImgUrl(value) { return value || ''; },
    tips(msg) { tips.push(msg); },
  };
  global.getApp = () => app;
  global.wx = {
    getStorageSync() { return ''; },
    getAccountInfoSync() { return { miniProgram: { envVersion: 'release' } }; },
    showToast() {}, showLoading() {}, hideLoading() {},
    stopPullDownRefresh() {}, pageScrollTo() {}, navigateTo() {},
    showModal(opts) { modals.push(opts); },
  };
  global.Page = value => { definition = value; };
  global.Component = value => { definition = value; };
  global.Behavior = value => value;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
  const vm = Object.assign({}, definition);
  vm.data = Object.assign(JSON.parse(JSON.stringify(definition.data || {})), { id: 11 });
  vm.setData = function (patch) { Object.assign(this.data, patch); };
  return { vm, requests, modals, tips };
}

const byUrl = (requests, url) => requests.filter(r => r.url === url);

// 走一遍「移交 → 选俱乐部 → 确认移交」,返回确认弹窗
function selectClubAndConfirm(h) {
  h.vm.onTransferToClub();
  const clubReq = byUrl(h.requests, '/api/club/my').pop();
  clubReq.success({ code: '200', data: [{ id: 5, name: '城瘾俱乐部' }] });
  h.vm.onClubSheetSelect({ detail: { index: 0 } });
  const confirm = h.modals[h.modals.length - 1];
  assert.equal(confirm.title, '移交给俱乐部承接');
  assert.equal(confirm.confirmText, '确认移交');
  return confirm;
}

test('BL-201 确认后连点两次只发 1 个移交请求', () => {
  const h = harness();
  const first = selectClubAndConfirm(h);
  const second = selectClubAndConfirm(h);
  first.success({ confirm: true });
  second.success({ confirm: true });
  assert.equal(byUrl(h.requests, '/api/topic/transfer-to-club').length, 1);
});

test('BL-201 请求在途时再点入口不再请求 /api/club/my', () => {
  const h = harness();
  selectClubAndConfirm(h).success({ confirm: true });
  h.vm.onTransferToClub();
  assert.equal(byUrl(h.requests, '/api/club/my').length, 1);
});

test('BL-201 成功后再点入口不再请求 /api/club/my,并提示到新主题继续配置', () => {
  const h = harness();
  selectClubAndConfirm(h).success({ confirm: true });
  byUrl(h.requests, '/api/topic/transfer-to-club')[0].success({ code: '200', data: { newTopicId: 99 } });
  assert.equal(h.modals[h.modals.length - 1].title, '已移交');
  h.vm.onTransferToClub();
  assert.equal(byUrl(h.requests, '/api/club/my').length, 1);
  assert.equal(h.tips[h.tips.length - 1], '已移交，请到新主题继续配置');
});

test('BL-201 业务失败或网络失败后可以再次移交', () => {
  const h = harness();
  selectClubAndConfirm(h).success({ confirm: true });
  byUrl(h.requests, '/api/topic/transfer-to-club')[0].success({ code: '500', msg: '承接俱乐部不可用' });
  assert.equal(h.tips[h.tips.length - 1], '承接俱乐部不可用');
  selectClubAndConfirm(h).success({ confirm: true });
  assert.equal(byUrl(h.requests, '/api/topic/transfer-to-club').length, 2);
  byUrl(h.requests, '/api/topic/transfer-to-club')[1].fail({});
  assert.equal(h.tips[h.tips.length - 1], '网络异常，请重试');
  selectClubAndConfirm(h).success({ confirm: true });
  assert.equal(byUrl(h.requests, '/api/topic/transfer-to-club').length, 3);
});
