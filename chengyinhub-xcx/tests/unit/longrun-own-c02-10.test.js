// NOT_RUN: user deferred verification.
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

test('unreadable active draft cannot be treated as missing and cannot send or overwrite', () => {
  const page = makePage();
  const originalGet = wx.getStorageSync;
  const before = JSON.stringify(storage);
  wx.getStorageSync = () => { throw new Error('storage read failed'); };
  try {
    assert.doesNotThrow(() => page._bootstrapNewLocalDraft({}));
    page.sendData({ name:'new topic' });
    assert.equal(page._persistDraftEnvelope(), false);
    page.clearLocalDraft();
    assert.equal(sent.length, 0);
    assert.equal(JSON.stringify(storage), before);
    assert.equal(navigations.length, 0);
    assert.match(page.data.submitError, /读取失败/);
  } finally { wx.getStorageSync = originalGet; }
});
test('a read failure of a pointed UNKNOWN envelope propagates instead of silently creating another UUID', () => {
  const d = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  const key = d.saveDraft(wx, { memberId:42, draftUuid:'unknown', publishAttempt:{ status:'UNKNOWN', topicId:'' }, formData:{} });
  const originalGet = wx.getStorageSync;
  wx.getStorageSync = k => { if (k === key) throw new Error('unreadable envelope'); return originalGet(k); };
  try {
    const page = makePage();
    page._bootstrapNewLocalDraft({});
    page.sendData({ name:'duplicate' });
    assert.equal(page._draftStorageUnavailable, true);
    assert.equal(sent.length, 0);
  } finally { wx.getStorageSync = originalGet; }
});
