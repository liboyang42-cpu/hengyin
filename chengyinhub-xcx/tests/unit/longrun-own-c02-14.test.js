// Real Page methods, deferred request callbacks and local storage exercise lost-write responses.
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/fabu/index.js';

let sent = [];
let documentCallback;
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
  chooseDocument: callback => { documentCallback = callback; },
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

test('chapter upload and duration callbacks cannot cross drawer replacement or revive cleared audio', () => {
  const probes = [];
  wx.createInnerAudioContext = () => {
    const audio = { duration:63, destroyed:false, onCanplay(fn){this.canplay=fn;}, onError(fn){this.error=fn;}, destroy(){this.destroyed=true;} };
    probes.push(audio); return audio;
  };
  const page = makePage();
  page.data.popChapter = true;
  page.data.popChapterIndex = 0;
  page.data.chapterForm = { audioUrl:'', audioDuration:0 };
  page.uploadChapterAudio();
  const oldUpload = documentCallback;
  page.data.chapterForm = { audioUrl:'B', audioDuration:0 };
  page.data.popChapterIndex = 1;
  oldUpload([{ url:'A', filename:'A.mp3' }]);
  assert.equal(page.data.chapterForm.audioUrl, 'B');
  page.uploadChapterAudio();
  documentCallback([{ url:'newB', filename:'B.mp3' }]);
  page.clearChapterAudio();
  probes[0].canplay();
  assert.equal(page.data.chapterForm.audioUrl, '');
  assert.equal(page.data.chapterForm.audioDuration, 0);
  assert.equal(probes[0].destroyed, true);
  page.uploadChapterAudio();
  documentCallback([{ url:'valid', filename:'valid.mp3' }]);
  probes[1].canplay();
  assert.equal(page.data.chapterForm.audioDuration, 63);
  assert.equal(probes[1].destroyed, true);
});
