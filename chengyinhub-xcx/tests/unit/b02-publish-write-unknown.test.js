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

test('edit-detail A request cannot apply after switching to B', () => {
  const page = makePage();
  let applied = 0;
  page.applyEditingTopic = () => { applied++; };
  page.loadEditingTopic('77');
  memberId = 43;
  sent[0].success({ code: '200', data: { topic: { id: 77 }, chapters: [], ticketList: [] } });
  sent[0].fail({ msg: 'late failure' });
  sent[0].complete();
  assert.equal(applied, 0);
  assert.equal(page.data.editLoadError, '');
  assert.deepEqual(storage, {});
});

test('edit-detail callback after unload cannot mutate page', () => {
  const page = makePage();
  page.loadEditingTopic('77');
  page.destroyPreviewAudio = () => {};
  page.onUnload();
  const before = JSON.stringify(page.data);
  sent[0].fail({ msg: 'late failure' });
  sent[0].complete();
  assert.equal(JSON.stringify(page.data), before);
});

test('revision conflict modal cannot restore or remove A draft after switching to B', () => {
  const page = makePage();
  page.data.editingTopicId = '77';
  const draft = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  draft.saveDraft(wx, { memberId: 42, topicId: '77', baseRevision: 'old',
    formData: { name: 'A unsent text', chapters: [] } });
  const before = JSON.stringify(storage);
  wx.showModal = options => { modals.push(options); };
  page._finishEditingDraftBootstrap({ topic: { id: 77, updateTime: 'new' } });
  assert.equal(modals.length, 1);
  memberId = 43;
  modals[0].success({ confirm: false });
  assert.equal(JSON.stringify(storage), before);
  assert.notEqual(page._draftAutosaveReady, true);
});

test('publish callback from A cannot clear draft or navigate under B', () => {
  const page = makePage();
  page.data.draftUuid = 'a-draft';
  page.data.draftMemberId = 42;
  page._persistDraftEnvelope();
  page.sendData({ name: 'A topic' });
  const before = JSON.stringify(storage);
  memberId = 43;
  sent[0].success({ code: '200', data: 77 });
  assert.equal(JSON.stringify(storage), before);
  assert.deepEqual(navigations, []);
  assert.equal(page.data.resultSheet.show, false);
});

test('create commit with lost response blocks retry and survives real storage reload', () => {
  const page = makePage();
  page.data.draftUuid = 'lost-create';
  page.data.draftMemberId = 42;
  page.data.formData.name = '唯一主题';
  page.data.pendingMaterials = [{ _localId: 'p1', name: '待编排素材' }];
  page.sendData({ name: '唯一主题' });
  assert.equal(sent.length, 1);
  sent[0].fail({ errMsg: 'timeout after commit' });
  page.sendData({ name: '唯一主题' });
  assert.equal(sent.length, 1);
  const next = makePage();
  next._ensureLocalIds = next._materializeCityStoryChapters = next.updateAllStatistics = () => {};
  next._bootstrapNewLocalDraft({ draftUuid: 'lost-create' });
  assert.equal(next.data.formData.name, '唯一主题');
  assert.equal(next.data.pendingMaterials[0].name, '待编排素材');
  next.sendData({ name: '唯一主题' });
  assert.equal(sent.length, 1);
  assert.match(next.data.submitError, /避免重复创建/);
  const draft = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  assert.equal(draft.loadDraft(wx, { draftUuid: 'lost-create' }, { memberId: 42 }).envelope.publishAttempt.status, 'UNKNOWN');
});

test('explicit business failure clears unknown and permits next write', () => {
  const page = makePage();
  page.data.draftUuid = 'rejected';
  page.data.draftMemberId = 42;
  page.sendData({ name: 'invalid' });
  sent[0].success({ code: '400', msg: '名称必填' });
  assert.equal(page._publishAttempt, null);
  page.sendData({ name: 'corrected' });
  assert.equal(sent.length, 2);
});

test('storage failure prevents network write', () => {
  const page = makePage();
  page.data.draftUuid = 'full';
  page.data.draftMemberId = 42;
  const original = wx.setStorageSync;
  wx.setStorageSync = () => { throw new Error('full'); };
  try { page.sendData({ name: 'topic' }); } finally { wx.setStorageSync = original; }
  assert.equal(sent.length, 0);
});

test('update UNKNOWN retains original ID and local edits after server read; only explicit server adoption unlocks', () => {
  const page = makePage();
  page.data.editingTopicId = '77';
  page.data.draftMemberId = 42;
  page.data.baseRevision = 'old';
  page.data.formData.name = 'local unsent';
  page.sendData({ name: 'local unsent' });
  assert.match(sent[0].url, /update$/);
  assert.equal(JSON.parse(sent[0].data).id, '77');
  sent[0].fail({ errMsg: 'response lost' });
  const draft = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  const stored = draft.loadDraft(wx, { topicId: '77' }, { memberId: 42 }).envelope;
  assert.equal(stored.publishAttempt.topicId, '77');
  const next = makePage();
  next.data.editingTopicId = '77';
  next._ensureLocalIds = next._materializeCityStoryChapters = next.updateAllStatistics = () => {};
  wx.showModal = options => { modals.push(options); };
  next.data.formData.name = 'server content';
  next._finishEditingDraftBootstrap({ topic: { id: 77, updateTime: 'new' } });
  assert.equal(modals.length, 1);
  // The pre-existing conflict decision is deferred: a read response alone must not enable writing.
  next.sendData({ name: 'local unsent' });
  assert.equal(sent.length, 1);
  modals[0].success({ confirm: false });
  assert.equal(next._publishAttempt, null);
  assert.equal(next.data.formData.name, 'server content');
  assert.equal(next.data.baseRevision, 'new');
  next.sendData({ name: 'explicit later edit' });
  assert.equal(sent.length, 2);
  assert.equal(JSON.parse(sent[1].data).id, '77');
});

test('unload while create is in flight preserves UNKNOWN for reentry', () => {
  const page = makePage();
  page.data.draftUuid = 'unloaded';
  page.data.draftMemberId = 42;
  page.sendData({ name: 'topic' });
  page.destroyPreviewAudio = () => {};
  page.onUnload();
  sent[0].success({ code: '200', data: 77 });
  const draft = require('../../pages/publish/utils/publish/pro-editor-draft.js');
  assert.equal(draft.loadDraft(wx, { draftUuid: 'unloaded' }, { memberId: 42 }).envelope.publishAttempt.status, 'UNKNOWN');
  assert.deepEqual(navigations, []);
});

test('known rejection remains retryable after local storage recovers', () => {
  const page = makePage();
  page.data.draftUuid = 'known-rejection';
  page.data.draftMemberId = 42;
  page.sendData({ name: 'invalid' });
  const original = wx.setStorageSync;
  wx.setStorageSync = () => { throw new Error('full'); };
  try { sent[0].success({ code: '400', msg: '明确拒绝' }); } finally { wx.setStorageSync = original; }
  page.sendData({ name: 'corrected' });
  assert.equal(sent.length, 2);
});

test('HTTP200 server error or unrecognized body remains UNKNOWN across repeated clicks', () => {
  for (const response of [{ code: 500, msg: 'after commit failure' }, { code: 503 }, {}, null]) {
    const page = makePage();
    page.data.draftUuid = 'ambiguous-' + sent.length;
    page.data.draftMemberId = 42;
    page.sendData({ name: 'topic' });
    const count = sent.length;
    sent[count - 1].success(response);
    page.sendData({ name: 'topic' });
    page.sendData({ name: 'topic' });
    assert.equal(sent.length, count);
    assert.equal(page._publishAttempt.status, 'UNKNOWN');
  }
});

test('real retry-load entry invalidates in-flight update callback and preserves new read context', () => {
  const page = makePage();
  page.data.editingTopicId = '77';
  page.data.draftMemberId = 42;
  page.sendData({ name: 'topic' });
  const write = sent[0];
  page.retryLoadEditingTopic();
  assert.equal(sent[1].url, '/api/topic/author-edit-detail');
  const before = JSON.stringify(storage);
  const pageBefore = JSON.stringify(page.data);
  write.success({ code: 200, data: 77 });
  write.fail({ errMsg: 'late timeout' });
  assert.equal(JSON.stringify(storage), before);
  assert.equal(JSON.stringify(page.data), pageBefore);
  assert.deepEqual(navigations, []);
});
