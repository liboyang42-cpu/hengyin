// NOT_RUN: verification deferred by user.
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

test('published own-template responses retain account, scope, live page and latest-read context', () => {
  const page = makePage();
  page.getTempList();
  assert.equal(sent[0].data.is_quote, 1);
  const old = sent[0];
  memberId = 43;
  old.success({ code:200, data:{ rows:[{ id:42 }] } });
  assert.deepEqual(page.data.tempList, []);
  page.getTempList();
  sent[1].success({ code:200, data:{ rows:[{ id:43 }] } });
  old.fail({});
  assert.equal(page.data.tempList[0].id, 43);
  page.getTempList();
  page.data.operationScope = 'MERCHANT';
  sent[2].success({ code:200, data:{ rows:[{ id:99 }] } });
  assert.deepEqual(page.data.tempList, []);
  page.getTempList();
  page.destroyPreviewAudio = () => {};
  page.onUnload();
  const before = JSON.stringify(page.data);
  sent[3].success({ code:200, data:{ rows:[{ id:100 }] } });
  assert.equal(JSON.stringify(page.data), before);
});
