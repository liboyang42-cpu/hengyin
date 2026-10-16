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

function restoreUnknown(attemptTopicId = '77') {
  const draft = require('../../utils/publish/pro-editor-draft.js');
  draft.saveDraft(wx, { memberId: 42, topicId: '77', baseRevision: 'same-second',
    publishAttempt: { status: 'UNKNOWN', topicId: attemptTopicId },
    formData: { name: 'local pending', chapters: [] } });
  const page = makePage();
  page.data.editingTopicId = '77';
  page.data.formData.name = 'saved content';
  page._ensureLocalIds = page._materializeCityStoryChapters = page.updateAllStatistics = () => {};
  wx.showModal = options => { modals.push(options); };
  page._finishEditingDraftBootstrap({ topic: { id: 77, updateTime: 'same-second' } });
  return page;
}

test('equal revision UNKNOWN requires explicit saved-version adoption before another update', () => {
  const page = restoreUnknown();
  assert.equal(modals.length, 1);
  assert.equal(modals[0].cancelText, '使用已保存版本');
  page.sendData({ name: 'must wait' });
  assert.equal(sent.length, 0);
  assert.equal(page._publishAttempt.status, 'UNKNOWN');
  modals[0].success({ confirm: false });
  assert.equal(page._publishAttempt, null);
  assert.equal(page.data.formData.name, 'saved content');
  assert.equal(page.data.baseRevision, 'same-second');
  page.sendData({ name: 'explicit later edit' });
  assert.equal(sent.length, 1);
  assert.equal(JSON.parse(sent[0].data).id, '77');
});

test('keeping local draft with equal revision retains UNKNOWN and local text', () => {
  const page = restoreUnknown();
  modals[0].success({ confirm: true });
  assert.equal(page.data.formData.name, 'local pending');
  assert.equal(page._publishAttempt.status, 'UNKNOWN');
  page.sendData({ name: 'local pending' });
  assert.equal(sent.length, 0);
  const draft = require('../../utils/publish/pro-editor-draft.js');
  assert.equal(draft.loadDraft(wx, { topicId: '77' }, { memberId: 42 }).envelope.publishAttempt.status, 'UNKNOWN');
});

test('equal revision with unidentified postwrite topic does not offer adoption unlock', () => {
  const page = restoreUnknown('');
  assert.equal(modals.length, 0);
  assert.equal(page._publishAttempt.status, 'UNKNOWN');
  page.sendData({ name: 'no blind resend' });
  assert.equal(sent.length, 0);
});

test('equal revision adoption callback cannot clear another account draft', () => {
  const page = restoreUnknown();
  const before = JSON.stringify(storage);
  memberId = 43;
  modals[0].success({ confirm: false });
  assert.equal(JSON.stringify(storage), before);
  assert.equal(page._publishAttempt.status, 'UNKNOWN');
});
