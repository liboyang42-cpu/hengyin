// NOT_RUN: local verification deferred by user.
// Real Page methods, deferred request callbacks and local storage exercise lost-write responses.
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

test('own-story draft responses are account and request-generation fenced through showTemp', () => {
  const page = makePage();
  page.data.storyDraftList = [{ id:1, title:'cached' }];
  page.showTemp();
  const old = sent[0];
  assert.deepEqual(page.data.storyDraftList, []);
  memberId = 43;
  old.success({ code:200, data:{ rows:[{ id:42, title:'A private draft' }] } });
  assert.deepEqual(page.data.storyDraftList, []);
  page.showTemp();
  sent[1].success({ code:200, data:{ rows:[{ id:43, title:'B own draft' }] } });
  old.fail();
  assert.equal(page.data.storyDraftList[0].id, 43);
  page.showTemp();
  page.destroyPreviewAudio = () => {};
  page.onUnload();
  const before = JSON.stringify(page.data);
  sent[2].success({ code:200, data:{ rows:[{ id:99 }] } });
  assert.equal(JSON.stringify(page.data), before);
});
