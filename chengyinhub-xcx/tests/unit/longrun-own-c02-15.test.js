// Real Page methods and deferred request callbacks exercise owner-list read fences.
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/fabu/index.js';

let sent = [];
let storage = {};
let navigations = [];
let toasts = [];
let modals = [];
let memberId = 42;

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  tips: (m) => { toasts.push(m); },
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  chooseImage: () => {},
  getUserID: () => memberId,
  getUserInfo: () => null,
  getToken: () => '',
});
global.wx = {
  getStorageSync: (k) => storage[k],
  setStorageSync: (k, v) => { storage[k] = v; },
  removeStorageSync: (k) => { delete storage[k]; },
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  showToast: (o) => { toasts.push(o && o.title); },
  showLoading: () => {}, hideLoading: () => {},
  showModal: (options) => { modals.push(options); if (options.success) options.success({ confirm: true }); },
  navigateTo: (options) => { navigations.push(options.url); },
  redirectTo: (options) => { navigations.push(options.url); },
  createMapContext: () => ({ getCenterLocation: () => {} }),
  pageScrollTo: () => {}, nextTick: (f) => f(),
};

let pageConfig = null;
global.Page = (cfg) => { pageConfig = cfg; };

beforeEach(() => {
  memberId = 42;
  sent = [];
  storage = {};
  navigations = [];
  toasts = [];
  modals = [];
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const inst = Object.assign({}, pageConfig);
  inst.data = JSON.parse(JSON.stringify(pageConfig.data));
  inst.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => {
      const parts = k.replace(/\[(\d+)\]/g, '.$1').split('.');
      let o = inst.data;
      for (let i = 0; i < parts.length - 1; i++) o = o[parts[i]];
      o[parts[parts.length - 1]] = patch[k];
    });
    if (cb) cb();
  };
  return inst;
}

test('showTemp clears retired story drafts without requesting private draft data', () => {
  const page = makePage();
  page.data.storyDraftList = [{ id:1, title:'cached' }];
  page.showTemp();
  assert.deepEqual(page.data.storyDraftList, []);
  assert.equal(sent.length, 0);
});

test('own published-template responses are account and unload fenced through getTempList', () => {
  const page = makePage();
  page.data.tempList = [{ id:1, title:'cached' }];
  page.getTempList();
  const old = sent[0];
  assert.equal(old.url, '/api/template/my-list');
  assert.equal(old.method, 'POST');
  assert.equal(old.data.is_quote, 1);
  assert.equal(old.data.draft_status, 1);
  assert.deepEqual(page.data.tempList, []);
  memberId = 43;
  old.success({ code:200, data:{ rows:[{ id:42, title:'A own template', draftStatus:1, status:1 }] } });
  assert.deepEqual(page.data.tempList, []);
  page.getTempList();
  sent[1].success({ code:200, data:{ rows:[{ id:43, title:'B own template', draftStatus:1, status:1 }] } });
  old.fail();
  assert.equal(page.data.tempList[0].id, 43);
  page.getTempList();
  page.destroyPreviewAudio = () => {};
  page.onUnload();
  const before = JSON.stringify(page.data);
  sent[2].success({ code:200, data:{ rows:[{ id:99, draftStatus:1, status:1 }] } });
  assert.equal(JSON.stringify(page.data), before);
  sent[2].fail();
  assert.equal(JSON.stringify(page.data), before);
});

test('own-template reads reject superseded same-account requests and changed owner scope', () => {
  const page = makePage();
  const response = id => ({ code:200, data:{ rows:[{ id, draftStatus:1, status:1 }] } });
  page.getTempList();
  page.getTempList();
  sent[1].success(response(2));
  sent[0].success(response(1));
  sent[0].fail();
  assert.equal(page.data.tempList[0].id, 2);
  page.data.operationScope = 'MERCHANT';
  page.getTempList();
  assert.equal(sent[2].data.scope, 'MERCHANT');
  page.data.operationScope = '';
  const before = JSON.stringify(page.data);
  sent[2].success(response(3));
  assert.equal(JSON.stringify(page.data), before);
  sent[2].fail();
  assert.equal(JSON.stringify(page.data), before);
});
