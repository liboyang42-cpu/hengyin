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

test('story duration locates original chapter/block after reordering and unload cancels remaining probes', () => {
  const probes = [];
  wx.createInnerAudioContext = () => {
    const probe = { duration:37, onCanplay(fn){this.canplay=fn;}, onError(fn){this.error=fn;}, destroy(){this.destroyed=true;} };
    probes.push(probe); return probe;
  };
  const page = makePage();
  const audio = { key:'audioA', type:'audio', url:'a.mp3' };
  const text = { key:'textA', type:'text', content:'text' };
  const a = { _localId:'A', blocks:[audio,text] };
  const b = { _localId:'B', blocks:[] };
  page.data.formData.chapters = [a,b];
  page.data.storyEditor.chapterIndex = 0;
  page._fillStoryAudioMeta();
  page.data.formData.chapters = [b,a];
  a.blocks = [text,audio];
  probes[0].canplay();
  assert.equal(audio._duration, 37);
  assert.equal(text._duration, undefined);
  delete audio._duration;
  page.data.storyEditor.chapterIndex = 1;
  page._fillStoryAudioMeta();
  page.destroyPreviewAudio = () => {};
  page.onUnload();
  probes[1].canplay();
  assert.equal(audio._duration, undefined);
  assert.equal(probes[1].destroyed, true);
});
