// NOT_RUN: verification deferred by user.
// Real Page methods, deferred request callbacks and local storage exercise lost-write responses.
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/fabu/index.js';

let sent = [];
let imageCallback;
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
  chooseImage: callback => { imageCallback = callback; },
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

test('story image callback cannot insert into another chapter and a current insertion still uses the real story command', () => {
  const page = makePage();
  page.updateAllStatistics = () => {};
  page.data.formData.chapters = [{ _localId:'A', nodes:[], blocks:[] }, { _localId:'B', nodes:[], blocks:[] }];
  page.data.storyEditor = { show:true, chapterIndex:0, insertMenuAt:0 };
  page.insertStoryImageAt({ currentTarget:{ dataset:{ index:0 } } });
  const oldUpload = imageCallback;
  page.data.storyEditor = { show:true, chapterIndex:1, insertMenuAt:0 };
  oldUpload(['A.jpg']);
  assert.equal(page.data.formData.chapters[1].blocks.length, 0);
  page.insertStoryImageAt({ currentTarget:{ dataset:{ index:0 } } });
  imageCallback(['B.jpg']);
  assert.equal(page.data.formData.chapters[1].blocks[0].url, 'B.jpg');
  assert.equal(page.data.formData.chapters[0].blocks.length, 0);
});
