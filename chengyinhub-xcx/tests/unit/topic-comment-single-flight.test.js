'use strict';

// BL-198:路线评价「发布评价」请求在途时连点只发一次 /api/comment/add,回包后可再次提交。
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE = path.join(ROOT, 'pages/topic/index/index.js');

function harness() {
  const requests = [];
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
    tips() {},
  };
  global.getApp = () => app;
  global.wx = {
    getStorageSync() { return ''; },
    getAccountInfoSync() { return { miniProgram: { envVersion: 'release' } }; },
    showToast() {}, showLoading() {}, hideLoading() {},
    stopPullDownRefresh() {}, pageScrollTo() {},
  };
  global.Page = value => { definition = value; };
  global.Component = value => { definition = value; };
  global.Behavior = value => value;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
  const vm = Object.assign({}, definition);
  vm.data = Object.assign(JSON.parse(JSON.stringify(definition.data || {})), {
    id: 11, answer: 4, plnr: '很好玩', uploadImages: [], canSubmitComment: true,
  });
  vm.setData = function (patch) { Object.assign(this.data, patch); };
  vm.voteClose = () => {};
  vm.getData = () => {};
  return { vm, requests };
}

const commentRequests = requests => requests.filter(r => r.url === '/api/comment/add');

test('BL-198 连点两次「发布评价」只发 1 个请求', () => {
  const { vm, requests } = harness();
  vm.submitComment();
  vm.submitComment();
  assert.equal(commentRequests(requests).length, 1);
});

test('BL-198 回包后(成功或失败)可再次提交', () => {
  const { vm, requests } = harness();
  vm.submitComment();
  const first = commentRequests(requests)[0];
  first.success({ code: '200' });
  first.complete();
  vm.submitComment();
  assert.equal(commentRequests(requests).length, 2);
  const second = commentRequests(requests)[1];
  second.fail && second.fail({});
  second.complete();
  vm.submitComment();
  assert.equal(commentRequests(requests).length, 3);
});
